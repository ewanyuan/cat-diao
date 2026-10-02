"""Receive saved phone links, index them in WeKnora, and maintain monthly Excel ledgers."""

import argparse
import hmac
import ipaddress
import json
import os
import sqlite3
import threading
import time
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import quote

from openpyxl import Workbook, load_workbook
from openpyxl.styles import Alignment, Font, PatternFill

import 手机直达 as phone_bridge
import nest_paths
import weknora_client as weknora_api


ROOT = Path(__file__).resolve().parent.parent / "assets"
DATA = nest_paths.ledger_dir()
DB = DATA / "captures.sqlite3"
SETTINGS = DATA / "settings.json"
PAIRING = nest_paths.pairing_file()
ENV_FILE = nest_paths.env_file()
PORT = 8793
KB_NAME = "手机随手收藏"
PHONE_CONNECTION_INTERVAL_S = 10
HEADERS = ["收藏ID", "收藏时间", "来源", "分享文字", "链接", "入库状态", "全文状态", "WeKnora资料ID", "WeKnora网页ID", "AI处理状态", "AI反馈", "反馈时间", "备注"]
LOCK = threading.RLock()


def database():
    DATA.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(DB, timeout=20)
    db.row_factory = sqlite3.Row
    db.execute("""CREATE TABLE IF NOT EXISTS captures (
        id TEXT PRIMARY KEY, created_at INTEGER NOT NULL, source TEXT NOT NULL,
        shared_text TEXT NOT NULL, url TEXT NOT NULL, status TEXT NOT NULL,
        full_text_status TEXT NOT NULL DEFAULT '待补全文', note_id TEXT NOT NULL DEFAULT '',
        page_id TEXT NOT NULL DEFAULT '', ai_status TEXT NOT NULL DEFAULT '未处理',
        ai_feedback TEXT NOT NULL DEFAULT '', feedback_at TEXT NOT NULL DEFAULT '',
        remark TEXT NOT NULL DEFAULT '', last_attempt INTEGER NOT NULL DEFAULT 0
    )""")
    db.commit()
    return db


def weknora_client():
    values = weknora_api.read_env_file(str(ENV_FILE)) if ENV_FILE.is_file() else {}
    base = os.environ.get("WEKNORA_BASE_URL") or values.get("WEKNORA_BASE_URL") or values.get("WEKNORA_HOST") or ""
    key = os.environ.get("WEKNORA_API_KEY") or values.get("WEKNORA_API_KEY") or values.get("WEKNORA_TOKEN") or ""
    return weknora_api.WeKnoraClient(base, key)


def payload_id(result):
    data = result.get("data", result) if isinstance(result, dict) else {}
    if not isinstance(data, dict):
        return ""
    for key in ("id", "knowledge_id", "knowledgeId"):
        if data.get(key):
            return str(data[key])
    for key in ("knowledge", "item", "document"):
        nested = data.get(key)
        if isinstance(nested, dict) and nested.get("id"):
            return str(nested["id"])
    return ""


def ensure_kb(client):
    DATA.mkdir(parents=True, exist_ok=True)
    if SETTINGS.exists():
        saved = json.loads(SETTINGS.read_text(encoding="utf-8"))
        if saved.get("kb_id"):
            return saved["kb_id"]
    listing = client.request("GET", "knowledge-bases")
    bases = listing.get("data", []) if isinstance(listing, dict) else []
    if isinstance(bases, dict):
        bases = bases.get("items") or bases.get("list") or []
    for base in bases:
        if base.get("name") == KB_NAME:
            kb_id = base["id"]
            break
    else:
        result = client.request("POST", "knowledge-bases", {
            "name": KB_NAME,
            "description": "从手机分享和悬浮按钮收藏的文章链接及分享文字；全文状态以月度台账为准。",
            "type": "document",
            "is_temporary": False,
        })
        kb_id = payload_id(result)
        if not kb_id:
            raise RuntimeError("WeKnora 未返回新知识库 ID")
    SETTINGS.write_text(json.dumps({"kb_id": kb_id, "kb_name": KB_NAME}, ensure_ascii=False, indent=2), encoding="utf-8")
    return kb_id


def month_path(timestamp_ms):
    month = datetime.fromtimestamp(timestamp_ms / 1000).strftime("%Y-%m")
    return DATA / f"{month}.xlsx"


def ledger(timestamp_ms):
    path = month_path(timestamp_ms)
    if path.exists():
        return path
    DATA.mkdir(parents=True, exist_ok=True)
    workbook = Workbook()
    sheet = workbook.active
    sheet.title = "收藏与反馈"
    sheet.append(HEADERS)
    sheet.freeze_panes = "A2"
    sheet.auto_filter.ref = "A1:M1"
    for cell in sheet[1]:
        cell.font = Font(name="Arial", bold=True, color="FFFFFF")
        cell.fill = PatternFill("solid", fgColor="255DD7")
        cell.alignment = Alignment(vertical="center", wrap_text=True)
    widths = [38, 20, 16, 60, 60, 24, 24, 38, 38, 18, 60, 20, 40]
    for col, width in zip("ABCDEFGHIJKLM", widths):
        sheet.column_dimensions[col].width = width
    sheet.row_dimensions[1].height = 30
    workbook.save(path)
    return path


def sync_ledger(row):
    path = ledger(row["created_at"])
    workbook = load_workbook(path)
    sheet = workbook["收藏与反馈"]
    target = next((number for number in range(2, sheet.max_row + 1)
                   if sheet.cell(number, 1).value == row["id"]), sheet.max_row + 1)
    existing_feedback = sheet.cell(target, 11).value if target <= sheet.max_row else None
    existing_ai_status = sheet.cell(target, 10).value if target <= sheet.max_row else None
    existing_feedback_at = sheet.cell(target, 12).value if target <= sheet.max_row else None
    values = [
        row["id"], datetime.fromtimestamp(row["created_at"] / 1000).strftime("%Y-%m-%d %H:%M:%S"),
        row["source"], row["shared_text"], row["url"], row["status"],
        row["full_text_status"], row["note_id"], row["page_id"],
        row["ai_status"] if row["ai_status"] != "未处理" else (existing_ai_status or "未处理"),
        row["ai_feedback"] or existing_feedback or "", row["feedback_at"] or existing_feedback_at or "", row["remark"],
    ]
    if target <= sheet.max_row and all(sheet.cell(target, column).value == value
                                       for column, value in enumerate(values, 1)):
        workbook.close()
        return
    for column, value in enumerate(values, 1):
        cell = sheet.cell(target, column, value)
        cell.data_type = "s"
        cell.font = Font(name="Arial", size=10)
        cell.alignment = Alignment(vertical="top", wrap_text=column in (4, 11, 13))
    sheet.auto_filter.ref = f"A1:M{sheet.max_row}"
    workbook.save(path)


def receive(items):
    accepted = []
    with LOCK, database() as db:
        for item in items:
            identifier = str(item.get("id", ""))
            if not identifier or len(identifier) > 80:
                continue
            text = str(item.get("shared_text", ""))[:30000]
            url = str(item.get("url", ""))[:4096]
            source = str(item.get("source", "未知应用"))[:100]
            stamp = int(item.get("created_at", 0))
            if not text or stamp < 946684800000 or stamp > int(time.time() * 1000) + 86400000:
                continue
            db.execute("INSERT OR IGNORE INTO captures(id,created_at,source,shared_text,url,status) VALUES(?,?,?,?,?,?)",
                       (identifier, stamp, source, text, url, "已到电脑，待入库"))
            accepted.append(identifier)
        db.commit()
        for identifier in accepted:
            row = db.execute("SELECT * FROM captures WHERE id=?", (identifier,)).fetchone()
            try:
                sync_ledger(row)
            except PermissionError:
                pass  # A workbook open in Excel will be refreshed on the next process pass.
    return accepted


def process_one(db, row, client, kb_id):
    identifier = row["id"]
    now = int(time.time())
    db.execute("UPDATE captures SET last_attempt=? WHERE id=?", (now, identifier))
    db.commit()
    note_id = row["note_id"]
    if not note_id:
        title = (row["shared_text"].splitlines()[0] or row["url"] or "手机收藏")[:90]
        content = (f"# {title}\n\n收藏时间：{datetime.fromtimestamp(row['created_at']/1000).isoformat(timespec='seconds')}"
                   f"\n来源：{row['source']}\n原始链接：{row['url'] or '无'}\n\n"
                   f"## 分享文字\n{row['shared_text']}\n\n"
                   "说明：本条记录包含手机分享文字和链接。链接页面正文是否成功抓取，请查看月度台账的「全文状态」。")
        result = client.request("POST", f"knowledge-bases/{quote(kb_id, safe='')}/knowledge/manual",
                                {"title": title, "content": content, "status": "publish", "channel": "api"})
        note_id = payload_id(result)
        if not note_id:
            raise RuntimeError("WeKnora 未返回资料 ID")
        db.execute("UPDATE captures SET note_id=?, status='链接与分享文字已提交入库' WHERE id=?", (note_id, identifier))
        db.commit()
    if row["url"] and not row["page_id"]:
        try:
            result = client.request("POST", f"knowledge-bases/{quote(kb_id, safe='')}/knowledge/url",
                                    {"url": row["url"]})
            page_id = payload_id(result)
            db.execute("UPDATE captures SET page_id=?, full_text_status='网页抓取已提交，待确认', remark='' WHERE id=?",
                       (page_id, identifier))
        except Exception as error:
            db.execute("UPDATE captures SET full_text_status='待补全文', remark=? WHERE id=?",
                       (str(error)[:300], identifier))
    else:
        db.execute("UPDATE captures SET full_text_status=? WHERE id=?",
                   ("无链接，保留分享文字" if not row["url"] else row["full_text_status"], identifier))
    db.commit()
    sync_ledger(db.execute("SELECT * FROM captures WHERE id=?", (identifier,)).fetchone())


def process_pending():
    with LOCK, database() as db:
        rows = db.execute("SELECT * FROM captures WHERE note_id='' OR (page_id!='' AND full_text_status='网页抓取已提交，待确认') OR status='已到电脑，待入库'").fetchall()
        if not rows:
            return
        client = weknora_client()
        kb_id = ensure_kb(client)
        for row in rows:
            if row["page_id"] and row["full_text_status"] == "网页抓取已提交，待确认":
                try:
                    result = client.request("GET", f"knowledge/{quote(row['page_id'], safe='')}")
                    page = result.get("data", result) if isinstance(result, dict) else {}
                    state = str(page.get("status", "")) if isinstance(page, dict) else ""
                    if state.lower() in ("completed", "success", "processed", "published"):
                        db.execute("UPDATE captures SET full_text_status='全文已解析' WHERE id=?", (row["id"],))
                    elif state.lower() in ("failed", "error"):
                        db.execute("UPDATE captures SET full_text_status='待补全文' WHERE id=?", (row["id"],))
                    db.commit()
                    sync_ledger(db.execute("SELECT * FROM captures WHERE id=?", (row["id"],)).fetchone())
                except Exception:
                    pass
                continue
            try:
                process_one(db, row, client, kb_id)
            except Exception as error:
                db.execute("UPDATE captures SET status='入库失败，待重试', remark=? WHERE id=?",
                           (str(error)[:300], row["id"]))
                db.commit()
                try:
                    sync_ledger(db.execute("SELECT * FROM captures WHERE id=?", (row["id"],)).fetchone())
                except PermissionError:
                    pass


def refresh_ledgers():
    with LOCK, database() as db:
        for row in db.execute("SELECT * FROM captures").fetchall():
            try:
                sync_ledger(row)
            except PermissionError:
                pass


def maintain_phone_connection():
    saved = phone_bridge.config_read()
    token = saved.get("token", "")
    if not token:
        return
    address = saved.get("address", "")
    if address and phone_bridge.is_reachable(address):
        try:
            phone_bridge.request(address, "GET", "/heartbeat", token, timeout=3)
            return
        except RuntimeError:
            try:
                # Keep older paired apps reachable until their update is installed.
                phone_bridge.request(address, "GET", "/status", token, timeout=3)
                return
            except Exception:
                pass
        except Exception:
            pass
    found = phone_bridge.discover(token)
    if not found:
        return
    latest = phone_bridge.config_read()
    if latest.get("token") == token and latest.get("address") != found:
        latest["address"] = found
        phone_bridge.config_save(latest)


def same_lan(remote):
    try:
        ip = ipaddress.ip_address(remote)
        if ip.version != 4:
            return False
        if ip.is_loopback:
            return True
        if not ip.is_private:
            return False
        return any(ip in ipaddress.IPv4Network(f"{local}/{mask}", strict=False)
                   for local, mask in phone_bridge.local_interfaces())
    except (OSError, ValueError):
        return False


class Receiver(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/health" and self.client_address[0] in ("127.0.0.1", "::1"):
            return self.respond(200, {"service": "catdiao-nest"})
        if self.path not in ("/phone-bridge.apk", "/cat-diao.apk") or not same_lan(self.client_address[0]):
            return self.respond(404, {"error": "未找到"})
        apk = ROOT / "cat-diao-android-1.11.apk"
        if not apk.is_file():
            return self.respond(404, {"error": "安装包尚未生成"})
        self.send_response(200)
        self.send_header("Content-Type", "application/vnd.android.package-archive")
        self.send_header("Content-Disposition", 'attachment; filename="cat-diao.apk"')
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(apk.stat().st_size))
        self.end_headers()
        try:
            with apk.open("rb") as stream:
                while chunk := stream.read(65536):
                    self.wfile.write(chunk)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_POST(self):
        try:
            saved = json.loads(PAIRING.read_text(encoding="utf-8"))
            expected = saved.get("token", "")
            if self.path not in ("/captures", "/clipboard", "/file-ready") or not same_lan(self.client_address[0]) or not expected or not hmac.compare_digest(
                    self.headers.get("X-Phone-Token", ""), expected):
                return self.respond(403, {"error": "未授权"})
            length = int(self.headers.get("Content-Length", "0"))
            if length < 1 or length > (700000 if self.path == "/captures" else 262144 if self.path == "/clipboard" else 4096):
                return self.respond(413, {"error": "请求过大"})
            if self.path == "/file-ready":
                self.rfile.read(length)
                destination = phone_bridge.receive_phone_file(self.client_address[0], expected)
                return self.respond(200, {"saved": str(destination)})
            payload = json.loads(self.rfile.read(length).decode("utf-8"))
            if self.path == "/clipboard":
                value = payload.get("text")
                if not isinstance(value, str) or not value:
                    return self.respond(400, {"error": "没有可发送的文字"})
                phone_bridge.write_windows_clipboard(value)
                return self.respond(200, {"copied": True})
            items = payload.get("items", [])
            if not isinstance(items, list) or len(items) > 20:
                return self.respond(400, {"error": "收藏格式错误"})
            accepted = receive(items)
            self.respond(200, {"accepted": accepted})
        except Exception as error:
            self.respond(502 if self.path == "/file-ready" else 500,
                         {"error": str(error)[:160]})

    def respond(self, code, payload):
        if self.command == "POST" and self.path == "/captures":
            print(f"收藏接收请求：{self.client_address[0]}，HTTP {code}", flush=True)
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        return


def main():
    parser = argparse.ArgumentParser(description="手机随手收藏接收与入库")
    parser.add_argument("command", choices=["init", "listen", "sync", "status", "feedback"])
    parser.add_argument("--id", help="收藏 ID")
    parser.add_argument("--ai-status", default="已处理", help="AI 处理状态")
    parser.add_argument("--text", help="AI 的实际反馈内容")
    args = parser.parse_args()
    with database() as db:
        if args.command == "status":
            print(json.dumps({"rows": db.execute("SELECT count(*) FROM captures").fetchone()[0],
                              "kb": json.loads(SETTINGS.read_text(encoding="utf-8")) if SETTINGS.exists() else None}, ensure_ascii=False))
            return
        if args.command == "feedback":
            if not args.id or not args.text:
                parser.error("feedback 需要 --id 和 --text")
            db.execute("UPDATE captures SET ai_status=?, ai_feedback=?, feedback_at=? WHERE id=?",
                       (args.ai_status, args.text, datetime.now().isoformat(timespec="seconds"), args.id))
            db.commit()
            row = db.execute("SELECT * FROM captures WHERE id=?", (args.id,)).fetchone()
            if row is None:
                parser.error("没有这条收藏 ID")
            sync_ledger(row)
            print("AI 反馈已写入月度台账")
            return
    if args.command == "init":
        kb_id = ensure_kb(weknora_client())
        path = ledger(int(time.time() * 1000))
        print(json.dumps({"kb_id": kb_id, "ledger": str(path)}, ensure_ascii=False))
        return
    if args.command == "sync":
        process_pending()
        return
    def worker():
        while True:
            try:
                if ENV_FILE.is_file() or os.environ.get("WEKNORA_API_KEY"):
                    process_pending()
                refresh_ledgers()
            except Exception as error:
                print(f"知识库暂时无法同步：{str(error)[:160]}", flush=True)
            time.sleep(45)
    def phone_connection_worker():
        while True:
            try:
                maintain_phone_connection()
            except Exception:
                pass
            time.sleep(PHONE_CONNECTION_INTERVAL_S)

    threading.Thread(target=worker, daemon=True).start()
    threading.Thread(target=phone_connection_worker, daemon=True,
                     name="catdiao-phone-connection").start()
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Receiver)
    print(f"手机收藏接收已开启，端口 {PORT}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
