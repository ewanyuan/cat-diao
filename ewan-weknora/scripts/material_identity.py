"""Classified WeKnora writes and provenance-aware retrieval. No credentials are stored."""
from datetime import datetime, timezone, timedelta
import copy
import json
import os
from pathlib import Path
import re
from urllib.parse import quote, urlsplit

KINDS = {
 'collection': '收藏资料',
 'personal_experience': '我的经验总结',
 'personal_artifact': '我的项目成果',
 'unclassified': '待核实',
 'external_reference': '外部资料',
 'personal_statement': '本人陈述',
 'personal_understanding': '本人理解',
 'practice_record': '实践记录',
 'artifact_original': '成果原件',
 'discussion_draft': '讨论草案',
}
def personal_kb():
    """Owner's personal knowledge base, only when configured outside the repo."""
    return (os.environ.get('WEKNORA_PERSONAL_KB_ID') or '').strip()

def reference_kb():
    """Capture knowledge base, only when configured outside the repo."""
    return (os.environ.get('WEKNORA_KB_ID') or '').strip()

def known_kbs():
    return {value for value in (personal_kb(), reference_kb()) if value}

ALIASES = {'collection':'external_reference','personal_experience':'practice_record',
           'personal_artifact':'practice_record'}
PROCESS = {'summary_enabled':False,'enable_multimodel':False,'graph_enabled':False,
           'question_generation_config':{'enabled':False}}
CREATION_MODES = {'human_authored','user_led_ai_assisted','ai_generated','captured_original','unknown'}
OWNERS = {'personal','external','unknown'}
EVIDENCE = {'observed','documented_content','user_report','external_confirmation','proposal','inference'}
FIELDS = {'material_owner','creation_mode','provenance_note','evidence_sources','artifact_refs','project_refs',
          'artifact_state','source_date','content_depth','original_author','derived_from'}

class ClassificationError(ValueError): pass

def unwrap(response):
    if not isinstance(response,dict): raise ClassificationError('知识库返回格式异常。')
    data=response.get('data',response)
    if not isinstance(data,dict): raise ClassificationError('知识库没有返回条目对象。')
    return data

def read_provenance(path):
    if not path:return {}
    p=Path(path).expanduser()
    if p.stat().st_size>100000:raise ClassificationError('来源说明过大。')
    value=json.loads(p.read_text(encoding='utf-8-sig'))
    if not isinstance(value,dict):raise ClassificationError('来源说明应为 JSON 对象。')
    return value

def text(value, field, limit=8000):
    if not isinstance(value,str) or not value.strip() or len(value)>limit:
        raise ClassificationError(field+'必须是非空文字。')
    return value.strip()

def refs(values, field, artifact=False):
    if not isinstance(values,list) or len(values)>50:raise ClassificationError(field+'应为不超过50项的列表。')
    for value in values:
        if not isinstance(value,dict) or not {'title','locator'}<=set(value):raise ClassificationError(field+'需要标题和来源位置。')
        if not set(value)<={'title','locator','evidence_kind','as_of','kind'}:raise ClassificationError(field+'含未知字段。')
        text(value['title'],field+'标题',500); locator=text(value['locator'],field+'位置',4000)
        parsed=urlsplit(locator)
        local=re.match(r'^[A-Za-z]:[/\\]',locator) or locator.startswith('/')
        if local:
            if artifact and not Path(locator).exists():raise ClassificationError('成果文件不存在：'+locator)
        elif parsed.scheme not in {'http','https','thread','weknora'} or not (parsed.hostname or parsed.path):
            raise ClassificationError('来源位置应为完整文件路径或可定位链接。')
        if parsed.username or parsed.password:raise ClassificationError('来源链接不能含账号或凭据。')
        if 'evidence_kind' in value and value['evidence_kind'] not in EVIDENCE:raise ClassificationError('证据身份不正确。')
        if 'as_of' in value:
            try:datetime.fromisoformat(text(value['as_of'],'来源日期').replace('Z','+00:00'))
            except ValueError:raise ClassificationError('来源日期应使用 ISO 日期。') from None
    return copy.deepcopy(values)

def metadata_for(kind, provenance=None):
    if kind not in KINDS:raise ClassificationError('材料身份不正确。')
    p=copy.deepcopy(provenance or {})
    if not isinstance(p,dict) or set(p)-FIELDS:raise ClassificationError('来源说明含未知字段。')
    own = kind in {'personal_experience','personal_artifact','practice_record','artifact_original','personal_statement','personal_understanding'}
    external = kind in {'collection','external_reference'}
    owner=p.get('material_owner','personal' if own else 'unknown')
    mode=p.get('creation_mode','captured_original' if external else 'unknown')
    if owner not in OWNERS or mode not in CREATION_MODES:raise ClassificationError('材料归属或整理方式不正确。')
    if own and owner!='personal':raise ClassificationError('自己的陈述、理解、经验或成果需要明确个人归属。')
    sources=refs(p.get('evidence_sources',[]),'证据来源')
    # A practice record may retain historical file locators. Only classifying
    # an actual delivered artifact requires its local file to still exist.
    artifacts=refs(p.get('artifact_refs',[]),'交付物',artifact=kind in {'personal_artifact','artifact_original'})
    if own:
        if not sources or not p.get('provenance_note'):raise ClassificationError('自己的经验或成果需要来源及归属说明，不能只按标题判断。')
        if not any(s.get('evidence_kind') in {'observed','documented_content','user_report','external_confirmation'} for s in sources):
            raise ClassificationError('设想和推断不能单独证明自己的经验或成果。')
    if kind in {'personal_artifact','artifact_original'} and (not artifacts or p.get('artifact_state') not in {'implemented','verified','released'}):
        raise ClassificationError('成果需要已交付状态和实际作品入口；项目想法和草稿不能直接标为成果。')
    if 'project_refs' in p and (not isinstance(p['project_refs'],list) or any(not isinstance(v,str) or len(v)>500 for v in p['project_refs'])):
        raise ClassificationError('项目关联应为文字列表。')
    if 'derived_from' in p and (not isinstance(p['derived_from'],list) or any(not isinstance(v,str) for v in p['derived_from'])):
        raise ClassificationError('关联原件应为条目编号列表。')
    if 'content_depth' in p and p['content_depth'] not in {'title_only','share_text','transcript','full_text','unknown'}:
        raise ClassificationError('内容深度不正确。')
    if 'provenance_note' in p:text(p['provenance_note'],'归属说明')
    if 'source_date' in p:
        text(p['source_date'],'来源日期或日期范围',100)
    if 'original_author' in p:text(p['original_author'],'原作者',200)
    if 'artifact_state' in p and p['artifact_state'] not in {'draft','implemented','verified','released'}:
        raise ClassificationError('成果状态不正确。')
    p.update({'material_kind':kind,'material_owner':owner,'creation_mode':mode,
              'classification_version':'1','recorded_at':datetime.now(timezone(timedelta(hours=8))).isoformat(timespec='seconds')})
    p.setdefault('provenance_note','仅记录收藏取得，不把文章作者叙述当成用户经历。' if external else '材料身份尚未核实，不用于证明用户经验或成果。')
    if sources:p['evidence_sources']=sources
    if artifacts:p['artifact_refs']=artifacts
    return p

def identity_provenance(meta):
    """Read API-compatible provenance while retaining pre-existing flat fields."""
    p={k:copy.deepcopy(v) for k,v in meta.items() if k in FIELDS}
    encoded=meta.get('material_provenance')
    if encoded is not None:
        if not isinstance(encoded,str):raise ClassificationError('已有材料来源格式异常，未覆盖。')
        try:stored=json.loads(encoded)
        except ValueError:raise ClassificationError('已有材料来源无法读取，未覆盖。') from None
        if not isinstance(stored,dict):raise ClassificationError('已有材料来源应为对象。')
        if set(stored)-(FIELDS|{'classification_version','recorded_at'}):raise ClassificationError('已有材料来源含未支持字段，未覆盖。')
        p.update({k:v for k,v in stored.items() if k in FIELDS})
    # Historical records use these documented depth/mode aliases. Keep their
    # assertions while validating the canonical provenance, not the raw body.
    depth={'share_stub':'share_text','share_text_and_link':'share_text','original':'full_text','original_bundle':'full_text'}
    mode={'user_led_ai':'user_led_ai_assisted','user_authored':'human_authored',
          'user_statement':'human_authored','user_statement_with_ai_summary':'user_led_ai_assisted',
          'ai_generated_under_user_direction':'user_led_ai_assisted'}
    if p.get('content_depth') in depth:p['content_depth']=depth[p['content_depth']]
    if p.get('creation_mode') in mode:p['creation_mode']=mode[p['creation_mode']]
    if p.get('material_owner')=='user':p['material_owner']='personal'
    return p

def storage_patch(kind,provenance=None,existing=None):
    """WeKnora accepts <=20 scalar fields, <=1000 UTF-8 bytes per value."""
    existing=existing or {}
    incoming=copy.deepcopy(provenance or {})
    if not isinstance(incoming,dict) or set(incoming)-FIELDS:raise ClassificationError('来源说明含未知字段。')
    p=identity_provenance(existing)
    # Replacing an identity does not silently reuse an old assertion of ownership.
    same = ALIASES.get(existing.get('material_kind'),existing.get('material_kind')) == ALIASES.get(kind,kind)
    if existing.get('material_kind') is not None and not same and not (kind=='personal_artifact' and existing.get('material_kind')=='artifact_original'):
        p={}
    p.update(incoming)
    full=metadata_for(kind,p)
    prior={}
    if isinstance(existing.get('material_provenance'),str):prior=json.loads(existing['material_provenance'])
    if prior.get('recorded_at'):full['recorded_at']=prior['recorded_at']
    detail={k:v for k,v in full.items() if k!='material_kind'}
    encoded=json.dumps(detail,ensure_ascii=False,separators=(',',':'))
    patch={'material_kind':kind,'material_provenance':encoded}
    if 'material_owner' in existing:patch['material_owner']=full['material_owner']
    validate_storage({**existing,**patch})
    return patch


def target_identity(kb, kind, document=None):
    """Public legacy aliases remain accepted; the new KB stores its shared ontology."""
    if kb not in known_kbs():
        return kind, None
    stored = ALIASES.get(kind,kind)
    role = {'collection':'capture','personal_experience':'practice','personal_artifact':'artifact_index'}.get(kind)
    if role is None:
        role={'external_reference':'capture','personal_statement':'user_statement',
              'personal_understanding':'understanding','practice_record':'practice',
              'artifact_original':'artifact_original','discussion_draft':'discussion_draft'}.get(kind)
    if kind=='personal_artifact' and document and document.get('file_type') not in {'manual',None}:
        stored,role='artifact_original','artifact_original'
    if reference_kb() and kb==reference_kb() and stored not in {'external_reference','discussion_draft','unclassified'}:
        raise ClassificationError('本人陈述、实践或成果应放入「知识与实践」，未写入参考库。')
    if personal_kb() and kb==personal_kb() and stored=='external_reference':
        raise ClassificationError('外部收藏应放入「参考与情报」，未写入主库。')
    return stored,role


def target_patch(kb,kind,provenance=None,existing=None,document=None):
    stored,role=target_identity(kb,kind,document)
    patch=storage_patch(kind,provenance,existing)
    if kb in known_kbs():
        patch.update(material_kind=stored,identity_kind=kind)
        old_role=(existing or {}).get('record_role')
        if role and (not old_role or kind in {'personal_experience','personal_artifact'}):patch['record_role']=role
        if role and (existing or {}).get('material_kind') not in {None,stored,kind}:
            patch['record_role']=role
        if stored in {'unclassified','discussion_draft'} and old_role in {'practice','artifact_index','artifact_original'}:
            patch['record_role']='discussion_draft'
    validate_storage({**(existing or {}),**patch})
    return patch


def identity_labels(kb,kind,document=None):
    stored,role=target_identity(kb,kind,document)
    labels={'身份:'+KINDS[kind]}
    if kb in known_kbs():
        labels.add('身份:'+KINDS[stored])
        if stored=='practice_record' and (role=='artifact_index' or (document or {}).get('custom_metadata',{}).get('record_role')=='artifact_index'):
            labels.add('身份:'+KINDS['personal_artifact'])
    return labels


def selected_identity_label(kb,kind,document=None):
    allowed=identity_labels(kb,kind,document)
    existing=[t.get('name') for t in (document or {}).get('tags',[]) if isinstance(t,dict) and t.get('name','').startswith('身份:')]
    if len(existing)==1 and existing[0] in allowed:return existing[0]
    stored,_=target_identity(kb,kind,document)
    return '身份:'+KINDS[stored]

def validate_storage(meta):
    if len(meta)>20:raise ClassificationError('既有元数据已接近20字段上限，不能在保留原字段的同时补标；未修改。')
    for key,value in meta.items():
        if not isinstance(key,str) or not key.strip() or len(key.encode('utf-8'))>64:
            raise ClassificationError('元数据字段名称不符合接口限制。')
        if not isinstance(value,(str,int,float,bool,type(None))):
            raise ClassificationError('元数据只接受标量；来源列表需通过材料来源说明保存。')
        display=value if isinstance(value,str) else json.dumps(value)
        if len(display.encode('utf-8'))>1000:
            raise ClassificationError('来源说明或元数据超过1000字节；请精简为可追溯入口，详细证据保留在原文，未截断保存。')

def list_tags(client,kb):
    result=[]
    for page in range(1,101):
        data=unwrap(client.request('GET',f'knowledge-bases/{quote(kb,safe="")}/tags?page={page}&page_size=100'))
        rows=data.get('data') or [];result.extend(rows)
        if len(result)>=data.get('total',len(result)) or len(rows)<100:return result
    raise ClassificationError('标签过多，未完成读取。')

def resolve_tags(client,kb,names):
    names=list(dict.fromkeys(names));known={t['name']:t['id'] for t in list_tags(client,kb)}
    for name in names:
        if name not in known:
            created=unwrap(client.request('POST',f'knowledge-bases/{quote(kb,safe="")}/tags',{'name':name}))
            if not created.get('id'):raise ClassificationError('未能创建分类标签。')
            known[name]=created['id']
    return [known[n] for n in names]

def tag_ids(doc):
    return [t['id'] if isinstance(t,dict) else t for t in (doc.get('tags') or [])]

def check_extra_tags(kind,tags):
    if kind not in KINDS:raise ClassificationError('材料身份不正确。')
    for t in tags or []:
        text(t,'标签',200)
        if t.startswith('身份:') and t!='身份:'+KINDS[kind]:raise ClassificationError('附加标签不能指定第二种材料身份。')

def classify(client,kb,ident,kind,provenance=None,extra_tags=None,replace=False):
    check_extra_tags(kind,extra_tags)
    doc=unwrap(client.request('GET','knowledge/'+quote(ident,safe='')))
    if doc.get('knowledge_base_id')!=kb:raise ClassificationError('条目不属于指定知识库，未修改。')
    old=doc.get('custom_metadata') or {}
    old_kind=old.get('material_kind')
    stored_kind,_=target_identity(kb,kind,doc)
    allowed_labels=identity_labels(kb,kind,doc)
    identity=[t for t in (doc.get('tags') or []) if isinstance(t,dict) and t.get('name','').startswith('身份:')]
    if not replace and ((old_kind and old_kind not in {kind,stored_kind}) or any(t['name'] not in allowed_labels for t in identity) or len(identity)>1):
        raise ClassificationError('已有不同的身份标注；核对后使用 --replace-identity，未覆盖。')
    if not replace and kind=='personal_experience' and old.get('record_role') in {'artifact_index','artifact_original'}:
        raise ClassificationError('已有成果档案角色；核对后使用 --replace-identity，未覆盖。')
    target_patch(kb,kind,provenance,old,doc)
    label=selected_identity_label(kb,kind,doc if not replace else None)
    names=[label,*(extra_tags or [])]
    wanted=resolve_tags(client,kb,names)
    # Re-read immediately before merging: preserve unrelated tags and metadata added since discovery.
    latest=unwrap(client.request('GET','knowledge/'+quote(ident,safe='')))
    if latest.get('knowledge_base_id')!=kb or (latest.get('custom_metadata') or {}).get('material_kind')!=old_kind:
        raise ClassificationError('条目身份在核对期间发生变化，未覆盖。')
    if not replace and any(isinstance(t,dict) and t.get('name','').startswith('身份:') and t['name']!=label for t in latest.get('tags') or []):
        raise ClassificationError('身份标签在核对期间发生变化，未覆盖。')
    current_tags=tag_ids(latest)
    remove={t['id'] for t in (latest.get('tags') or []) if isinstance(t,dict) and t.get('name','').startswith('身份:')} if replace else set()
    merged_tags=list(dict.fromkeys([t for t in current_tags if t not in remove]+wanted))
    patch=target_patch(kb,kind,provenance,latest.get('custom_metadata') or {},latest)
    merged={**(latest.get('custom_metadata') or {}),**patch}
    if (latest.get('custom_metadata') or {})!=merged:
        client.request('PUT','knowledge/'+quote(ident,safe=''),{'custom_metadata':merged})
    if current_tags!=merged_tags:
        client.request('PUT','knowledge/tags',{'updates':{ident:merged_tags}})
    verified=unwrap(client.request('GET','knowledge/'+quote(ident,safe='')))
    if verified.get('knowledge_base_id')!=kb or verified.get('custom_metadata')!=merged or set(tag_ids(verified))!=set(merged_tags):
        raise ClassificationError('分类回读不一致；保留条目 '+ident+'，请核对后补齐，不要重复创建。')
    return {'id':ident,'title':verified.get('title'),'material_kind':stored_kind,'identity_kind':kind,'material_owner':identity_provenance(merged)['material_owner'],
            'identity_tag':label,'parse_status':verified.get('parse_status'),'classification_verified':True}

def create_note(client,kb,title,content,kind,provenance=None,tags=None,draft=False):
    check_extra_tags(kind,tags)
    target_patch(kb,kind,provenance)
    ids=resolve_tags(client,kb,[selected_identity_label(kb,kind),*(tags or [])])
    created=unwrap(client.request('POST',f'knowledge-bases/{quote(kb,safe="")}/knowledge/manual',
                   {'title':title,'content':content,'status':'draft','tag_ids':ids,'channel':'api',
                    **({'process_config':PROCESS} if kb in known_kbs() else {})}))
    ident=created.get('id')
    if not ident:raise ClassificationError('保存未返回条目编号，停止重试并核对知识库。')
    try:
        result=classify(client,kb,ident,kind,provenance,tags)
        if not draft:
            latest=unwrap(client.request('GET','knowledge/'+quote(ident,safe='')))
            manual=latest.get('metadata') or {}
            if not isinstance(manual,dict) or manual.get('content')!=content or latest.get('title')!=title:
                raise ClassificationError('草稿正文在保存期间发生变化，未覆盖发布。')
            result.update(publish_note(client,kb,ident))
        result['status']='draft' if draft else 'publish'
        return result
    except Exception as exc:
        raise ClassificationError('条目已创建：'+ident+'；分类或发布未完成。请对这个编号补齐，不要再次创建。') from exc

def publish_note(client,kb,ident):
    """Resume publication of an already classified manual note; never create a copy."""
    path='knowledge/'+quote(ident,safe='')
    first=unwrap(client.request('GET',path))
    if first.get('knowledge_base_id')!=kb:raise ClassificationError('条目不属于指定知识库，未发布。')
    manual=first.get('metadata') or {}
    text(first.get('title'),'标题');text(manual.get('content'),'笔记正文',10000000)
    if manual.get('status') not in {'draft','publish'}:raise ClassificationError('条目不是可发布的手工笔记。')
    meta=first.get('custom_metadata') or {};kind=meta.get('identity_kind') or meta.get('material_kind')
    if kind not in KINDS:raise ClassificationError('请先对原编号补齐材料身份，再发布。')
    target_patch(kb,kind,identity_provenance(meta),meta,first)
    identities=[t['name'] for t in first.get('tags') or [] if isinstance(t,dict) and t.get('name','').startswith('身份:')]
    if len(identities)!=1 or identities[0] not in identity_labels(kb,kind,first):raise ClassificationError('身份标签未核对一致，请先补齐分类。')
    if manual.get('status')=='publish' and first.get('parse_status')!='failed':
        return {'id':ident,'status':'publish','parse_status':first.get('parse_status')}
    latest=unwrap(client.request('GET',path))
    if latest.get('knowledge_base_id')!=kb or latest.get('title')!=first.get('title') or latest.get('metadata')!=manual or latest.get('custom_metadata')!=meta or set(tag_ids(latest))!=set(tag_ids(first)):
        raise ClassificationError('笔记在核对期间发生变化，未覆盖发布。')
    client.request('PUT','knowledge/manual/'+quote(ident,safe=''),{'title':first['title'],'content':manual['content'],'status':'publish','tag_ids':tag_ids(latest),
                   **({'process_config':PROCESS} if kb in known_kbs() else {})})
    result=unwrap(client.request('GET',path))
    if result.get('parse_status')=='failed' or (result.get('metadata') or {}).get('status')!='publish' or (result.get('metadata') or {}).get('content')!=manual['content'] or result.get('custom_metadata')!=meta or set(tag_ids(result))!=set(tag_ids(first)):
        raise ClassificationError('未确认发布状态；保留原条目 '+ident+'，不要重复创建。')
    return {'id':ident,'status':'publish','parse_status':result.get('parse_status')}

def list_documents(client,kb):
    documents=[]
    for page in range(1,101):
        response=client.request('GET',f'knowledge-bases/{quote(kb,safe="")}/knowledge?page={page}&page_size=100')
        rows=response.get('data') or []
        if not isinstance(rows,list):raise ClassificationError('文档列表格式异常。')
        if any(d.get('knowledge_base_id')!=kb for d in rows):raise ClassificationError('文档列表超出指定知识库。')
        documents.extend(rows)
        if len(rows)<100:return documents
    raise ClassificationError('文档过多，未完成身份范围核对。')

def effective_kind(document):
    meta=document.get('custom_metadata') or {}
    kind=meta.get('material_kind')
    if kind in KINDS:
        if kind in {'personal_experience','personal_artifact','practice_record','artifact_original','personal_statement','personal_understanding'}:
            try:owner=identity_provenance(meta).get('material_owner')
            except ClassificationError:return 'unclassified'
            if owner is not None:
                if owner!='personal':return 'unclassified'
            elif meta.get('material_owner') not in {'user','personal'} and meta.get('owner')!='袁良运':return 'unclassified'
        return kind
    # Existing phone records remain recognizable as captures; this does not infer their authorship.
    if any(isinstance(t,dict) and t.get('name')=='来源:手机收藏' for t in document.get('tags') or []):return 'collection'
    return 'unclassified'


def kind_matches(document,kinds):
    kind=effective_kind(document)
    if kind in kinds:return True
    if kind=='external_reference' and 'collection' in kinds:return True
    if kind=='artifact_original' and 'personal_artifact' in kinds:return True
    if kind=='practice_record':
        role=(document.get('custom_metadata') or {}).get('record_role')
        return ('personal_artifact' in kinds if role=='artifact_index' else 'personal_experience' in kinds)
    return False

def scoped_search(client,kb,query,kinds,limit):
    documents=list_documents(client,kb)
    allowed={d['id']:d for d in documents if kind_matches(d,kinds)}
    if not allowed:return {'success':True,'data':[],'material_scope':kinds,'scope_documents':0}
    response=client.request('POST',f'knowledge-bases/{quote(kb,safe="")}/hybrid-search',
                            {'query_text':query,'match_count':limit,'knowledge_ids':list(allowed),'skip_context_enrichment':True})
    hits=response.get('data') or []
    if not isinstance(hits,list):raise ClassificationError('检索结果格式异常。')
    filtered=[]
    for hit in hits:
        ident=hit.get('knowledge_id')
        if ident in allowed:
            filtered.append({**hit,'material_kind':effective_kind(allowed[ident]),'material_owner':identity_provenance(allowed[ident].get('custom_metadata') or {}).get('material_owner','unknown')})
    return {**response,'data':filtered,'material_scope':kinds,'scope_documents':len(allowed)}
