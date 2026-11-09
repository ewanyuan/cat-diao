#!/usr/bin/env python3
"""Generate Android string resources for Cat Diao.

Edit this catalog, then run: python3 android/i18n/catalog.py
Shipped resources are the generated XML files. Translations are not
native-speaker reviewed.
"""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
RES = ROOT / "app" / "src" / "main" / "res"

LOCALES = [
    "en", "zh-CN", "zh-TW", "ja", "ko", "es", "fr", "de", "pt-BR", "ru",
    "ar", "hi", "id", "vi", "th", "it", "tr", "nl", "pl", "uk",
]

QUALIFIER = {
    "en": "values",
    "zh-CN": "values-zh-rCN",
    "zh-TW": "values-zh-rTW",
    "ja": "values-ja",
    "ko": "values-ko",
    "es": "values-es",
    "fr": "values-fr",
    "de": "values-de",
    "pt-BR": "values-pt-rBR",
    "ru": "values-ru",
    "ar": "values-ar",
    "hi": "values-hi",
    "id": "values-id",
    "vi": "values-vi",
    "th": "values-th",
    "it": "values-it",
    "tr": "values-tr",
    "nl": "values-nl",
    "pl": "values-pl",
    "uk": "values-uk",
}

# Extra folders so system-follow still hits the right script or legacy code.
ALIASES = {
    "values-zh": "zh-CN",
    "values-zh-rSG": "zh-CN",
    "values-b+zh+Hans": "zh-CN",
    "values-zh-rHK": "zh-TW",
    "values-zh-rMO": "zh-TW",
    "values-b+zh+Hant": "zh-TW",
    "values-in": "id",
}

STRINGS = {}
PLURALS = {}


def S(key, values):
    if len(values) != len(LOCALES):
        raise SystemExit(f"{key}: expected {len(LOCALES)} locales, got {len(values)}")
    if key in STRINGS or key in PLURALS:
        raise SystemExit(f"duplicate {key}")
    STRINGS[key] = dict(zip(LOCALES, values))


def P(key, forms):
    if set(forms) != set(LOCALES):
        missing = set(LOCALES) - set(forms)
        raise SystemExit(f"{key} plurals missing {missing}")
    PLURALS[key] = forms


def row(*values):
    return list(values)


# fmt: off
S("app_name", row(
"Cat Diao","猫叼","貓叼","キャットディオ","캣댜오","Cat Diao","Cat Diao","Cat Diao","Cat Diao","Кэт Дяо",
"كات دياو","कैट डियाओ","Cat Diao","Cat Diao","แคทเดียว","Cat Diao","Cat Diao","Cat Diao","Cat Diao","Кет Дяо"))

S("accessibility_label", row(
"Cat Diao screen control","猫叼屏幕控制","貓叼螢幕控制","画面操作","화면 제어","Control de pantalla","Contrôle de l'écran","Bildschirmsteuerung","Controle de tela","Управление экраном",
"التحكم بالشاشة","स्क्रीन नियंत्रण","Kontrol layar","Điều khiển màn hình","ควบคุมหน้าจอ","Controllo schermo","Ekran denetimi","Schermbediening","Sterowanie ekranem","Керування екраном"))

S("accessibility_description", row(
"Lets a paired computer see and tap this phone when you allow it. Saving does not need this.",
"配对的电脑在你允许后可以查看和点按这台手机。收藏不需要这一项。",
"配對的電腦在你允許後可以查看和點按這支手機。收藏不需要這一项。",
"許可したときだけ、ペアリングしたパソコンから画面の表示とタップができます。保存には不要です。",
"허용한 경우에만 페어링된 컴퓨터가 화면을 보고 누를 수 있습니다. 저장에는 필요 없습니다.",
"El ordenador vinculado puede ver y tocar este teléfono si lo permites. Guardar no lo necesita.",
"L'ordinateur appairé peut voir et toucher ce téléphone si vous l'autorisez. L'enregistrement n'en a pas besoin.",
"Der gekoppelte Computer kann dieses Telefon sehen und tippen, wenn du es erlaubst. Sichern braucht das nicht.",
"O computador pareado pode ver e tocar neste telefone se você permitir. Salvar não precisa disso.",
"Связанный компьютер может видеть экран и нажимать, если вы разрешите. Для сохранения это не нужно.",
"يستطيع الكمبيوتر المقترن رؤية الهاتف ولمسه إذا سمحت. الحفظ لا يحتاج هذا.",
"अनुमति देने पर जोड़ा कंप्यूटर इस फ़ोन को देख और छू सकता है। सहेजने के लिए यह ज़रूरी नहीं।",
"Komputer yang dipasangkan bisa melihat dan mengetuk ponsel ini jika Anda mengizinkan. Menyimpan tidak memerlukannya.",
"Máy tính đã ghép nối có thể xem và chạm điện thoại này khi bạn cho phép. Lưu không cần mục này.",
"คอมพิวเตอร์ที่จับคู่จะดูและแตะโทรศัพท์นี้ได้เมื่อคุณอนุญาต การบันทึกไม่ต้องใช้สิ่งนี้",
"Il computer associato può vedere e toccare questo telefono se lo consenti. Salvare non lo richiede.",
"Eşlenen bilgisayar, izin verirsen bu telefonu görüp dokunabilir. Kaydetmek için gerekmez.",
"De gekoppelde computer kan dit toestel zien en tikken als je dat toestaat. Opslaan heeft dit niet nodig.",
"Sparowany komputer może widzieć i dotykać ten telefon, gdy na to pozwolisz. Zapisywanie tego nie wymaga.",
"Зв'язаний комп'ютер може бачити екран і торкатися його, якщо ви дозволите. Для збереження це не потрібно."))

S("channel_name", row(
"Cat Diao","猫叼","貓叼","キャットディオ","캣댜오","Cat Diao","Cat Diao","Cat Diao","Cat Diao","Кэт Дяо",
"كات دياو","कैट डियाओ","Cat Diao","Cat Diao","แคทเดียว","Cat Diao","Cat Diao","Cat Diao","Cat Diao","Кет Дяо"))

S("chevron_forward", row(
"›","›","›","›","›","›","›","›","›","›",
"‹","›","›","›","›","›","›","›","›","›"))

S("action_later", row(
"Not now","稍后","稍後","あとで","나중에","Ahora no","Plus tard","Später","Agora não","Не сейчас",
"ليس الآن","अभी नहीं","Nanti","Để sau","ภายหลัง","Non ora","Şimdi değil","Niet nu","Nie teraz","Не зараз"))

S("action_cancel", row(
"Cancel","取消","取消","キャンセル","취소","Cancelar","Annuler","Abbrechen","Cancelar","Отмена",
"إلغاء","रद्द करें","Batal","Hủy","ยกเลิก","Annulla","İptal","Annuleren","Anuluj","Скасувати"))

S("action_continue", row(
"Continue","继续","繼續","続ける","계속","Continuar","Continuer","Weiter","Continuar","Далее",
"متابعة","जारी रखें","Lanjut","Tiếp tục","ทำต่อ","Continua","Devam","Doorgaan","Dalej","Далі"))

S("action_done", row(
"Done","完成","完成","完了","완료","Listo","Terminé","Fertig","Concluir","Готово",
"تم","हो गया","Selesai","Xong","เสร็จ","Fatto","Bitti","Klaar","Gotowe","Готово"))

S("action_remove", row(
"Remove","移除","移除","解除","제거","Quitar","Retirer","Entfernen","Remover","Удалить",
"إزالة","हटाएँ","Hapus","Gỡ","เอาออก","Rimuovi","Kaldır","Verwijderen","Usuń","Вилучити"))

S("language_title", row(
"Language","语言","語言","言語","언어","Idioma","Langue","Sprache","Idioma","Язык",
"اللغة","भाषा","Bahasa","Ngôn ngữ","ภาษา","Lingua","Dil","Taal","Język","Мова"))

S("language_system", row(
"Follow system","跟随系统","跟隨系統","システムの言語","시스템 언어","Según el sistema","Langue du système","Systemsprache","Idioma do sistema","Как в системе",
"حسب النظام","सिस्टम के अनुसार","Ikuti sistem","Theo hệ thống","ตามระบบ","Come il sistema","Sistem dili","Systeemtaal","Jak w systemie","Як у системі"))

S("menu_language", row(
"Language","语言","語言","言語","언어","Idioma","Langue","Sprache","Idioma","Язык",
"اللغة","भाषा","Bahasa","Ngôn ngữ","ภาษา","Lingua","Dil","Taal","Język","Мова"))

S("menu_more", row(
"More","更多操作","更多操作","その他","더보기","Más","Plus","Mehr","Mais","Ещё",
"المزيد","और","Lainnya","Thêm","เพิ่มเติม","Altro","Diğer","Meer","Więcej","Більше"))

S("menu_send", row(
"Send to computer","发到小窝","傳到電腦","パソコンへ送る","컴퓨터로 보내기","Enviar al ordenador","Envoyer à l'ordinateur","An Computer senden","Enviar ao computador","На компьютер",
"إرسال إلى الكمبيوتر","कंप्यूटर पर भेजें","Kirim ke komputer","Gửi tới máy tính","ส่งไปคอมพิวเตอร์","Invia al computer","Bilgisayara gönder","Naar computer","Wyślij na komputer","На комп'ютер"))

S("menu_overlay_off", row(
"Turn off floating cat","关闭悬浮猫","關閉懸浮貓","浮き猫を消す","떠 있는 고양이 끄기","Quitar el gato","Retirer le chat","Katze ausblenden","Desligar o gato","Убрать кота",
"إيقاف القطة","बिल्ली बंद करें","Matikan kucing","Tắt mèo nổi","ปิดแมวลอย","Spegni il gatto","Kediyi kapat","Kat uitzetten","Wyłącz kota","Вимкнути кота"))

S("menu_overlay_on", row(
"Turn on floating cat","开启悬浮猫","開啟懸浮貓","浮き猫を出す","떠 있는 고양이 켜기","Mostrar el gato","Afficher le chat","Katze einblenden","Ligar o gato","Показать кота",
"تشغيل القطة","बिल्ली चालू करें","Nyalakan kucing","Bật mèo nổi","เปิดแมวลอย","Mostra il gatto","Kediyi aç","Kat aanzetten","Włącz kota","Увімкнути кота"))

S("menu_connection", row(
"Computer link","小窝连接","電腦連線","パソコン接続","컴퓨터 연결","Enlace","Connexion","Verbindung","Ligação","Связь",
"اتصال الكمبيوتر","कनेक्शन","Sambungan","Kết nối","การเชื่อมต่อ","Collegamento","Bağlantı","Verbinding","Połączenie","Зв'язок"))

S("menu_guide", row(
"Guide","使用指南","使用指南","使いかた","사용 안내","Guía","Guide","Anleitung","Guia","Справка",
"الدليل","गाइड","Panduan","Hướng dẫn","คู่มือ","Guida","Kılavuz","Uitleg","Poradnik","Довідка"))

S("menu_bluetooth", row(
"Bluetooth","蓝牙连接","藍牙連線","Bluetooth","블루투스","Bluetooth","Bluetooth","Bluetooth","Bluetooth","Bluetooth",
"بلوتوث","ब्लूटूथ","Bluetooth","Bluetooth","บลูทูธ","Bluetooth","Bluetooth","Bluetooth","Bluetooth","Bluetooth"))

S("menu_permissions", row(
"Battery and permissions","权限与省电","權限與省電","権限と電池","권한과 배터리","Batería y permisos","Batterie et accès","Akku und Rechte","Bateria e permissões","Батарея и доступ",
"البطارية والأذونات","बैटरी और अनुमति","Baterai dan izin","Pin và quyền","แบตเตอรี่และสิทธิ์","Batteria e permessi","Pil ve izinler","Batterij en rechten","Bateria i uprawnienia","Батарея й доступ"))

S("home_loading", row(
"Checking saves…","正在查看收藏…","正在查看收藏…","保存を確認しています…","저장 항목 확인 중…","Revisando guardados…","Vérification…","Speicher wird gelesen…","Verificando salvos…","Проверка записей…",
"جارٍ التحقق من المحفوظات…","सेव जाँचे जा रहे हैं…","Memeriksa simpanan…","Đang xem mục đã lưu…","กำลังตรวจรายการที่บันทึก…","Controllo dei salvataggi…","Kayıtlar bakılıyor…","Opgeslagen items nakijken…","Sprawdzanie zapisów…","Перевірка збереженого…"))

S("home_capture", row(
"Save copied text","收藏复制内容","收藏複製內容","コピーを保存","복사한 내용 저장","Guardar lo copiado","Enregistrer la copie","Kopiertes sichern","Salvar o texto copiado","Сохранить скопированное",
"حفظ النص المنسوخ","कॉपी किया टेक्स्ट सहेजें","Simpan teks salinan","Lưu chữ đã chép","บันทึกข้อความที่คัดลอก","Salva il testo copiato","Kopyalananı kaydet","Gekopieerde tekst bewaren","Zapisz skopiowane","Зберегти скопійоване"))

S("home_retry", row(
"Try again","再试一次","再試一次","もう一度","다시 시도","Reintentar","Réessayer","Erneut versuchen","Tentar de novo","Ещё раз",
"حاول مرة أخرى","फिर कोशिश करें","Coba lagi","Thử lại","ลองอีกครั้ง","Riprova","Yeniden dene","Opnieuw","Spróbuj ponownie","Ще раз"))

S("home_searching", row(
"Looking…","正在找小窝…","正在找電腦…","探しています…","찾는 중…","Buscando…","Recherche…","Suche…","Procurando…","Поиск…",
"جارٍ البحث…","खोज रहे हैं…","Mencari…","Đang tìm…","กำลังหา…","Ricerca…","Aranıyor…","Zoeken…","Szukanie…","Пошук…"))

S("home_bluetooth", row(
"Use Bluetooth","用蓝牙连接","用藍牙連線","Bluetoothで接続","블루투스로 연결","Usar Bluetooth","Passer par Bluetooth","Per Bluetooth","Usar Bluetooth","Через Bluetooth",
"استخدم البلوتوث","ब्लूटूथ इस्तेमाल करें","Pakai Bluetooth","Dùng Bluetooth","ใช้บลูทูธ","Usa Bluetooth","Bluetooth kullan","Via Bluetooth","Użyj Bluetooth","Через Bluetooth"))

S("home_connect_link", row(
"Connect computer","连接电脑","連線電腦","パソコンにつなぐ","컴퓨터 연결","Conectar el ordenador","Connecter l'ordinateur","Computer verbinden","Conectar o computador","Подключить компьютер",
"صِل الكمبيوتر","कंप्यूटर जोड़ें","Hubungkan komputer","Kết nối máy tính","เชื่อมต่อคอมพิวเตอร์","Collega il computer","Bilgisayarı bağla","Computer verbinden","Połącz komputer","Підключити комп'ютер"))

S("home_revoke", row(
"Remove computer","移除小窝连接","移除電腦連線","パソコンを解除","컴퓨터 연결 제거","Quitar ordenador","Retirer l'ordinateur","Computer entfernen","Remover computador","Удалить компьютер",
"إزالة الكمبيوتر","कंप्यूटर हटाएँ","Hapus komputer","Gỡ máy tính","เอาคอมพิวเตอร์ออก","Rimuovi computer","Bilgisayarı kaldır","Computer verwijderen","Usuń komputer","Вилучити комп'ютер"))

S("home_control_title", row(
"Finish phone control","继续设置手机控制","繼續設定手機控制","スマホ操作の設定","휴대폰 제어 설정","Terminar el control","Finir le contrôle","Telefonsteuerung","Concluir o controle","Настройка управления",
"إكمال التحكم بالهاتف","फ़ोन नियंत्रण पूरा करें","Selesaikan kontrol ponsel","Hoàn tất điều khiển","ตั้งค่าการควบคุมให้ครบ","Completa il controllo","Telefon denetimini bitir","Telefoonbediening afronden","Dokończ sterowanie","Завершити керування"))

S("home_recent", row(
"Recent saves","最近收藏","最近收藏","最近の保存","최근 저장","Guardados recientes","Enregistrements récents","Zuletzt gesichert","Salvos recentes","Недавние записи",
"المحفوظات الأخيرة","हाल के सेव","Simpanan terbaru","Mục lưu gần đây","รายการล่าสุด","Salvataggi recenti","Son kayıtlar","Recente items","Ostatnie zapisy","Нещодавні записи"))

S("home_empty_title", row(
"No saves yet","还没有收藏","還沒有收藏","まだ保存がありません","아직 저장이 없습니다","Aún no hay nada","Rien pour l'instant","Noch nichts gesichert","Nenhum salvo ainda","Пока пусто",
"لا محفوظات بعد","अभी कोई सेव नहीं","Belum ada simpanan","Chưa có mục nào","ยังไม่มีรายการ","Nessun salvataggio","Henüz kayıt yok","Nog niets opgeslagen","Brak zapisów","Поки порожньо"))

S("home_empty_body", row(
"Share something you like to Cat Diao.",
"看到喜欢的内容，分享给猫叼就行。",
"看到喜歡的內容，分享給貓叼就行。",
"気に入ったものをキャットディオに共有してください。",
"마음에 드는 내용을 캣댜오로 공유하세요.",
"Comparte lo que te guste con Cat Diao.",
"Partagez ce qui vous plaît avec Cat Diao.",
"Teile etwas, das dir gefällt, mit Cat Diao.",
"Compartilhe o que você gostar com o Cat Diao.",
"Поделитесь понравившимся с Кэт Дяо.",
"شارك ما يعجبك مع كات دياو.",
"जो पसंद आए उसे कैट डियाओ में शेयर करें।",
"Bagikan yang Anda suka ke Cat Diao.",
"Chia sẻ thứ bạn thích cho Cat Diao.",
"แชร์สิ่งที่ชอบให้แคทเดียว",
"Condividi ciò che ti piace con Cat Diao.",
"Beğendiğini Cat Diao ile paylaş.",
"Deel iets dat je leuk vindt met Cat Diao.",
"Udostępnij Cat Diao to, co ci się podoba.",
"Поділіться вподобаним із Кет Дяо."))

S("home_pending_body", row(
"Saved on this phone. It sends when the computer connects.",
"已保存在手机，连上小窝后自动送达。",
"已存在手機，連上電腦後自動送出。",
"このスマホに保存しました。パソコンがつながると送られます。",
"이 휴대폰에 저장했습니다. 컴퓨터가 연결되면 보내집니다.",
"Guardado en este teléfono. Se envía al conectar el ordenador.",
"Enregistré sur ce téléphone. Envoi quand l'ordinateur se connecte.",
"Auf diesem Telefon gesichert. Versand, sobald der Computer verbunden ist.",
"Salvo neste telefone. Envia quando o computador conectar.",
"Сохранено на телефоне. Уйдёт, когда компьютер подключится.",
"حُفظ على هذا الهاتف. يُرسل عند اتصال الكمبيوتر.",
"इस फ़ोन पर सहेजा गया। कंप्यूटर जुड़ते ही चला जाएगा।",
"Tersimpan di ponsel ini. Terkirim saat komputer tersambung.",
"Đã lưu trên điện thoại. Sẽ gửi khi máy tính kết nối.",
"เก็บบนโทรศัพท์นี้แล้ว จะส่งเมื่อคอมพิวเตอร์เชื่อมต่อ",
"Salvato su questo telefono. Parte quando il computer si collega.",
"Bu telefonda kayıtlı. Bilgisayar bağlanınca gider.",
"Opgeslagen op deze telefoon. Verstuurt zodra de computer verbindt.",
"Zapisane na tym telefonie. Wyjdzie, gdy komputer się połączy.",
"Збережено на телефоні. Піде, коли комп'ютер підключиться."))

S("home_all_done", row(
"All saves sent","都叼回小窝了","都送到電腦了","すべて送りました","모두 보냈습니다","Todo enviado","Tout est envoyé","Alles gesendet","Tudo enviado","Всё отправлено",
"أُرسل كل المحفوظ","सभी सेव भेज दिए","Semua terkirim","Đã gửi hết","ส่งครบแล้ว","Tutto inviato","Hepsi gönderildi","Alles verstuurd","Wszystko wysłane","Усе надіслано"))

S("home_read_error", row(
"Can't read saves","暂时读不到收藏","暫時讀不到收藏","保存を読めません","저장을 읽지 못했습니다","No se pueden leer","Lecture impossible","Lesen fehlgeschlagen","Não foi possível ler","Не удалось прочитать",
"تعذر قراءة المحفوظات","सेव पढ़े नहीं गए","Tidak bisa membaca","Không đọc được","อ่านรายการไม่ได้","Lettura non riuscita","Kayıtlar okunamadı","Lezen lukt niet","Nie można odczytać","Не вдалося прочитати"))

S("home_read_error_body", row(
"Open Cat Diao again in a moment.",
"请稍后重新打开猫叼。",
"請稍後重新打開貓叼。",
"しばらくしてから開き直してください。",
"잠시 후 캣댜오를 다시 여세요.",
"Abre Cat Diao de nuevo en un momento.",
"Rouvrez Cat Diao dans un instant.",
"Öffne Cat Diao gleich noch einmal.",
"Abra o Cat Diao de novo daqui a pouco.",
"Откройте Кэт Дяо ещё раз чуть позже.",
"افتح كات دياو مرة أخرى بعد قليل.",
"थोड़ी देर बाद कैट डियाओ फिर खोलें।",
"Buka Cat Diao lagi sebentar lagi.",
"Mở lại Cat Diao sau một lát.",
"เปิดแคทเดียวอีกครั้งอีกสักครู่",
"Riapri Cat Diao tra un momento.",
"Biraz sonra Cat Diao'yu yeniden aç.",
"Open Cat Diao zo meteen opnieuw.",
"Otwórz Cat Diao ponownie za chwilę.",
"Відкрийте Кет Дяо ще раз за мить."))

S("home_recent_empty", row(
"No saves yet. Share something here next time.",
"还没有收藏。下次看到想留下的内容，直接分享到猫叼。",
"還沒有收藏。下次看到想留下的內容，直接分享到貓叼。",
"まだありません。次は共有してください。",
"아직 없습니다. 다음에는 공유해 보세요.",
"Aún no hay nada. La próxima vez, compártelo aquí.",
"Rien encore. Partagez ici la prochaine fois.",
"Noch nichts. Teile es beim nächsten Mal hier.",
"Ainda nada. Da próxima vez, compartilhe aqui.",
"Пока пусто. В следующий раз поделитесь сюда.",
"لا شيء بعد. في المرة القادمة شاركه هنا.",
"अभी कुछ नहीं। अगली बार यहाँ शेयर करें।",
"Belum ada. Lain kali bagikan di sini.",
"Chưa có gì. Lần sau hãy chia sẻ vào đây.",
"ยังไม่มี ครั้งหน้าแชร์มาที่นี่",
"Ancora niente. La prossima volta condividilo qui.",
"Henüz yok. Gelecek sefere buraya paylaş.",
"Nog niets. Deel het de volgende keer hier.",
"Jeszcze nic. Następnym razem udostępnij tutaj.",
"Поки нічого. Наступного разу поділіться сюди."))

S("home_unnamed", row(
"Untitled","未命名收藏","未命名收藏","無題","제목 없음","Sin título","Sans titre","Ohne Titel","Sem título","Без названия",
"بدون عنوان","बिना शीर्षक","Tanpa judul","Chưa đặt tên","ไม่มีชื่อ","Senza titolo","Adsız","Zonder titel","Bez tytułu","Без назви"))

S("home_state_pending", row(
"Waiting to send","待叼回小窝","待送回電腦","送信待ち","보낼 대기","Por enviar","À envoyer","Wartet","Por enviar","Ждёт отправки",
"بانتظار الإرسال","भेजने की प्रतीक्षा","Menunggu kirim","Đang chờ gửi","รอส่ง","In attesa","Gönderilmeyi bekliyor","Wacht op verzending","Czeka na wysłanie","Чекає на надсилання"))

S("home_state_sent", row(
"On the computer","已到小窝","已到電腦","パソコンに届きました","컴퓨터에 도착","En el ordenador","Sur l'ordinateur","Auf dem Computer","No computador","На компьютере",
"على الكمبيوتر","कंप्यूटर पर पहुँचा","Sudah di komputer","Đã tới máy tính","ถึงคอมพิวเตอร์แล้ว","Sul computer","Bilgisayarda","Op de computer","Na komputerze","На комп'ютері"))

S("home_source_line", row(
"%1$s · %2$s","%1$s · %2$s","%1$s · %2$s","%1$s · %2$s","%1$s · %2$s","%1$s · %2$s","%1$s · %2$s","%1$s · %2$s","%1$s · %2$s","%1$s · %2$s",
"%1$s · %2$s","%1$s · %2$s","%1$s · %2$s","%1$s · %2$s","%1$s · %2$s","%1$s · %2$s","%1$s · %2$s","%1$s · %2$s","%1$s · %2$s","%1$s · %2$s"))

S("source_share", row(
"Share","分享","分享","共有","공유","Compartir","Partager","Teilen","Compartilhar","Поделиться",
"مشاركة","शेयर","Bagikan","Chia sẻ","แชร์","Condivisione","Paylaşım","Delen","Udostępnienie","Поділитися"))

S("source_overlay", row(
"Floating cat","悬浮猫","懸浮貓","浮き猫","떠 있는 고양이","Gato flotante","Chat flottant","Schwebende Katze","Gato flutuante","Плавающий кот",
"القطة العائمة","तैरती बिल्ली","Kucing mengambang","Mèo nổi","แมวลอย","Gatto fluttuante","Yüzen kedi","Zwevende kat","Pływający kot","Плаваючий кіт"))

S("source_system_share", row(
"System share","系统分享","系統分享","システム共有","시스템 공유","Compartir del sistema","Partage système","Systemfreigabe","Compartilhamento do sistema","Системная отправка",
"مشاركة النظام","सिस्टम शेयर","Berbagi sistem","Chia sẻ hệ thống","การแชร์ของระบบ","Condivisione di sistema","Sistem paylaşımı","Systeemdeling","Udostępnianie systemowe","Системний поділ"))

S("source_system_share_from", row(
"Shared from %1$s","系统分享：%1$s","系統分享：%1$s","%1$s から共有","%1$s에서 공유","Compartido desde %1$s","Partagé depuis %1$s","Geteilt von %1$s","Compartilhado de %1$s","Отправлено из %1$s",
"مشاركة من %1$s","%1$s से शेयर","Dibagikan dari %1$s","Chia sẻ từ %1$s","แชร์จาก %1$s","Condiviso da %1$s","%1$s uygulamasından","Gedeeld vanuit %1$s","Udostępniono z %1$s","Поділилися з %1$s"))

S("source_clipboard", row(
"Copied text","复制链接","複製內容","コピー","복사한 내용","Texto copiado","Texte copié","Kopierter Text","Texto copiado","Скопированный текст",
"نص منسوخ","कॉपी किया टेक्स्ट","Teks salinan","Chữ đã chép","ข้อความที่คัดลอก","Testo copiato","Kopyalanan metin","Gekopieerde tekst","Skopiowany tekst","Скопійований текст"))

S("source_unknown", row(
"Unknown app","未知应用","未知應用程式","不明なアプリ","알 수 없는 앱","App desconocida","Application inconnue","Unbekannte App","App desconhecido","Неизвестное приложение",
"تطبيق غير معروف","अज्ञात ऐप","Aplikasi tak dikenal","Ứng dụng lạ","แอปที่ไม่รู้จัก","App sconosciuta","Bilinmeyen uygulama","Onbekende app","Nieznana aplikacja","Невідомий застосунок"))

S("home_pair_title", row(
"Computer wants to connect","电脑正在请求连接","電腦正在要求連線","パソコンが接続を求めています","컴퓨터가 연결을 요청합니다","El ordenador quiere conectar","L'ordinateur veut se connecter","Der Computer möchte sich verbinden","O computador quer conectar","Компьютер хочет подключиться",
"الكمبيوتر يريد الاتصال","कंप्यूटर जुड़ना चाहता है","Komputer ingin tersambung","Máy tính muốn kết nối","คอมพิวเตอร์ขอเชื่อมต่อ","Il computer vuole connettersi","Bilgisayar bağlanmak istiyor","Computer wil verbinden","Komputer chce się połączyć","Комп'ютер хоче підключитися"))

S("home_pair_body", row(
"Tap to check the 6-digit code.",
"点这里核对 6 位数字。",
"點這裡核對 6 位數字。",
"タップして 6 桁の数字を照合します。",
"눌러서 6자리 숫자를 확인하세요.",
"Toca para comprobar el código de 6 dígitos.",
"Touchez pour vérifier le code à 6 chiffres.",
"Tippe, um die 6 Ziffern zu prüfen.",
"Toque para conferir o código de 6 dígitos.",
"Нажмите, чтобы сверить 6 цифр.",
"انقر للتحقق من الرمز المكوّن من 6 أرقام.",
"6 अंकों का कोड जाँचने के लिए टैप करें।",
"Ketuk untuk memeriksa kode 6 digit.",
"Chạm để đối chiếu mã 6 số.",
"แตะเพื่อตรวจรหัส 6 หลัก",
"Tocca per controllare il codice di 6 cifre.",
"6 haneli kodu kontrol etmek için dokun.",
"Tik om de 6 cijfers te controleren.",
"Stuknij, aby sprawdzić 6 cyfr.",
"Торкніться, щоб звірити 6 цифр."))

S("home_value_line", row(
"Text you copy on the phone is ready on the computer.",
"手机上复制的东西，电脑上直接能用。",
"手機上複製的東西，電腦上直接能用。",
"スマホでコピーしたものが、パソコンですぐ使えます。",
"휴대폰에서 복사한 내용이 컴퓨터에서 바로 쓰입니다.",
"Lo que copias en el teléfono queda listo en el ordenador.",
"Ce que vous copiez sur le téléphone est prêt sur l'ordinateur.",
"Kopierter Text vom Telefon ist am Computer sofort da.",
"O que você copia no telefone fica pronto no computador.",
"Скопированное на телефоне сразу доступно на компьютере.",
"ما تنسخه على الهاتف يصبح جاهزًا على الكمبيوتر.",
"फ़ोन पर कॉपी की चीज़ कंप्यूटर पर तुरंत मिलती है।",
"Teks yang disalin di ponsel langsung siap di komputer.",
"Chữ bạn chép trên điện thoại dùng được ngay trên máy tính.",
"สิ่งที่คัดลอกบนโทรศัพท์ ใช้บนคอมพิวเตอร์ได้เลย",
"Ciò che copi sul telefono è subito pronto sul computer.",
"Telefonda kopyaladığın şey bilgisayarda hemen hazır.",
"Wat je op de telefoon kopieert, staat meteen op de computer.",
"To, co skopiujesz w telefonie, jest od razu na komputerze.",
"Скопійоване на телефоні одразу доступне на комп'ютері."))

S("home_finding_title", row(
"Looking · %1$d s","正在找小窝 · %1$d 秒","正在找電腦 · %1$d 秒","検索中 · %1$d 秒","찾는 중 · %1$d초","Buscando · %1$d s","Recherche · %1$d s","Suche · %1$d s","Procurando · %1$d s","Поиск · %1$d с",
"بحث · %1$d ث","खोज · %1$d से","Mencari · %1$d d","Đang tìm · %1$d giây","กำลังหา · %1$d วิ","Ricerca · %1$d s","Aranıyor · %1$d sn","Zoeken · %1$d s","Szukanie · %1$d s","Пошук · %1$d с"))

S("home_finding_body", row(
"Saves stay on this phone until it connects.",
"收藏已保存在手机，连上后会自动送达。",
"收藏已存在手機，連上後會自動送出。",
"保存はこのスマホに残り、つながると送られます。",
"저장은 이 휴대폰에 남고, 연결되면 보내집니다.",
"Los guardados siguen en el teléfono hasta que conecte.",
"Les enregistrements restent ici jusqu'à la connexion.",
"Sicherungen bleiben hier, bis die Verbindung steht.",
"Os salvos ficam neste telefone até conectar.",
"Записи остаются на телефоне, пока нет связи.",
"تبقى المحفوظات على الهاتف إلى أن يتصل.",
"कनेक्ट होने तक सेव इस फ़ोन पर रहते हैं।",
"Simpanan tetap di ponsel sampai tersambung.",
"Mục đã lưu ở lại điện thoại cho đến khi kết nối.",
"รายการจะอยู่บนโทรศัพท์จนกว่าจะเชื่อมต่อ",
"I salvataggi restano qui finché non si collega.",
"Kayıtlar bağlanana kadar bu telefonda kalır.",
"Items blijven hier tot er verbinding is.",
"Zapisy zostają w telefonie, aż będzie połączenie.",
"Записи лишаються на телефоні, доки немає зв'язку."))

S("home_missing_title", row(
"Computer not found","还没找到小窝","還沒找到電腦","パソコンが見つかりません","컴퓨터를 찾지 못했습니다","Ordenador no encontrado","Ordinateur introuvable","Computer nicht gefunden","Computador não encontrado","Компьютер не найден",
"لم يُعثر على الكمبيوتر","कंप्यूटर नहीं मिला","Komputer tidak ketemu","Không thấy máy tính","ไม่พบคอมพิวเตอร์","Computer non trovato","Bilgisayar bulunamadı","Computer niet gevonden","Nie znaleziono komputera","Комп'ютер не знайдено"))

S("home_missing_stopped", row(
"Open Cat Diao again. Saves are still on this phone.",
"重新打开猫叼，再试一次。收藏还在手机里。",
"重新打開貓叼，再試一次。收藏還在手機裡。",
"開き直してください。保存はスマホに残っています。",
"캣댜오를 다시 여세요. 저장은 휴대폰에 있습니다.",
"Abre Cat Diao de nuevo. Lo guardado sigue en el teléfono.",
"Rouvrez Cat Diao. Les enregistrements sont toujours ici.",
"Öffne Cat Diao erneut. Die Sicherungen sind noch hier.",
"Abra o Cat Diao de novo. Os salvos continuam no telefone.",
"Откройте Кэт Дяо снова. Записи всё ещё на телефоне.",
"افتح كات دياو مرة أخرى. المحفوظات ما زالت على الهاتف.",
"कैट डियाओ फिर खोलें। सेव अभी फ़ोन पर हैं।",
"Buka Cat Diao lagi. Simpanan masih di ponsel ini.",
"Mở lại Cat Diao. Mục đã lưu vẫn ở trên điện thoại.",
"เปิดแคทเดียวอีกครั้ง รายการยังอยู่บนโทรศัพท์",
"Riapri Cat Diao. I salvataggi sono ancora qui.",
"Cat Diao'yu yeniden aç. Kayıtlar hâlâ bu telefonda.",
"Open Cat Diao opnieuw. De items staan nog op deze telefoon.",
"Otwórz Cat Diao ponownie. Zapisy nadal są w telefonie.",
"Відкрийте Кет Дяо знову. Записи досі на телефоні."))

S("home_missing_body", row(
"Is Cat Diao running on the computer? On a new network, try Bluetooth.",
"确认电脑上的猫叼接收已开启。换了网络？可以用蓝牙找回连接。",
"確認電腦上的貓叼接收已開啟。換了網路？可以用藍牙找回連線。",
"パソコン側は起動していますか。回線が変わったら Bluetooth を試してください。",
"컴퓨터에서 캣댜오가 켜져 있나요? 네트워크가 바뀌면 블루투스를 써 보세요.",
"¿Está Cat Diao abierto en el ordenador? Si cambió la red, prueba Bluetooth.",
"Cat Diao tourne-t-il sur l'ordinateur ? Sur un autre réseau, essayez Bluetooth.",
"Läuft Cat Diao auf dem Computer? In einem neuen Netz Bluetooth versuchen.",
"O Cat Diao está aberto no computador? Em outra rede, tente o Bluetooth.",
"Кэт Дяо запущен на компьютере? В новой сети попробуйте Bluetooth.",
"هل كات دياو يعمل على الكمبيوتر؟ في شبكة جديدة جرّب البلوتوث.",
"क्या कंप्यूटर पर कैट डियाओ चल रहा है? नया नेटवर्क हो तो ब्लूटूथ आज़माएँ।",
"Apakah Cat Diao berjalan di komputer? Di jaringan baru, coba Bluetooth.",
"Cat Diao đã chạy trên máy tính chưa? Đổi mạng thì thử Bluetooth.",
"แคทเดียวบนคอมพิวเตอร์เปิดอยู่ไหม ถ้าเปลี่ยนเครือข่าย ลองบลูทูธ",
"Cat Diao è aperto sul computer? In un'altra rete prova Bluetooth.",
"Bilgisayarda Cat Diao açık mı? Ağ değiştiyse Bluetooth dene.",
"Draait Cat Diao op de computer? Op een nieuw netwerk: probeer Bluetooth.",
"Czy Cat Diao działa na komputerze? W nowej sieci spróbuj Bluetooth.",
"Чи запущено Кет Дяо на комп'ютері? У новій мережі спробуйте Bluetooth."))

S("home_bt_title", row(
"Connected by Bluetooth","已通过蓝牙连接","已透過藍牙連線","Bluetoothで接続中","블루투스로 연결됨","Conectado por Bluetooth","Connecté en Bluetooth","Per Bluetooth verbunden","Conectado por Bluetooth","Подключено по Bluetooth",
"متصل عبر البلوتوث","ब्लूटूथ से जुड़ा","Tersambung lewat Bluetooth","Đã kết nối bằng Bluetooth","เชื่อมต่อด้วยบลูทูธ","Connesso via Bluetooth","Bluetooth ile bağlı","Verbonden via Bluetooth","Połączono przez Bluetooth","Підключено через Bluetooth"))

S("home_bt_body", row(
"Saves will send. Use the same Wi-Fi for files and clipboard.",
"收藏会自动送达。传文件或剪贴板文字时，请连同一个 Wi-Fi。",
"收藏會自動送出。傳檔案或剪貼簿文字時，請連同一個 Wi-Fi。",
"保存は送られます。ファイルとクリップボードは同じ Wi-Fi が必要です。",
"저장은 보내집니다. 파일과 클립보드는 같은 Wi-Fi가 필요합니다.",
"Los guardados se enviarán. Archivos y portapapeles necesitan el mismo Wi-Fi.",
"Les enregistrements partent. Fichiers et presse-papiers exigent le même Wi-Fi.",
"Sicherungen werden gesendet. Dateien und Zwischenablage brauchen dasselbe WLAN.",
"Os salvos serão enviados. Arquivos e área de transferência precisam do mesmo Wi-Fi.",
"Записи уйдут. Для файлов и буфера нужен один и тот же Wi-Fi.",
"ستُرسل المحفوظات. الملفات والحافظة يحتاجان شبكة Wi-Fi نفسها.",
"सेव भेज दिए जाएँगे। फ़ाइल और क्लिपबोर्ड के लिए एक ही Wi-Fi चाहिए।",
"Simpanan akan terkirim. File dan papan klip perlu Wi-Fi yang sama.",
"Mục đã lưu sẽ được gửi. Tệp và bảng nhớ tạm cần cùng Wi-Fi.",
"รายการจะถูกส่ง ไฟล์และคลิปบอร์ดต้องใช้ Wi-Fi เดียวกัน",
"I salvataggi partiranno. File e appunti richiedono la stessa Wi-Fi.",
"Kayıtlar gider. Dosya ve pano için aynı Wi-Fi gerekir.",
"Items worden verstuurd. Bestanden en klembord vragen hetzelfde wifi-netwerk.",
"Zapisy wyjdą. Pliki i schowek wymagają tej samej sieci Wi-Fi.",
"Записи підуть. Для файлів і буфера потрібен той самий Wi-Fi."))

S("home_sending_title", row(
"Sending saves","正在送到小窝","正在送到電腦","送信しています","보내는 중","Enviando","Envoi en cours","Wird gesendet","Enviando","Отправка",
"جارٍ الإرسال","भेजा जा रहा है","Mengirim","Đang gửi","กำลังส่ง","Invio in corso","Gönderiliyor","Bezig met verzenden","Wysyłanie","Надсилання"))

S("home_sending_body", row(
"Sending now.","稍等一下，正在补送收藏。","稍等一下，正在補送收藏。","今、保存を送っています。","지금 저장 항목을 보내는 중입니다.","Enviando ahora.","Envoi en cours.","Wird gerade gesendet.","Enviando agora.","Отправляем сейчас.",
"يتم الإرسال الآن.","अभी भेजा जा रहा है।","Sedang mengirim.","Đang gửi.","กำลังส่งอยู่","Invio in corso.","Şimdi gönderiliyor.","Nu aan het verzenden.","Wysyłanie trwa.","Надсилаємо зараз."))

S("home_held_title", row(
"Saves still on this phone","收藏还在手机里","收藏還在手機裡","保存はスマホに残っています","저장이 아직 휴대폰에 있습니다","Siguen en el teléfono","Toujours sur le téléphone","Noch auf diesem Telefon","Ainda neste telefone","Всё ещё на телефоне",
"ما زالت على الهاتف","अभी फ़ोन पर हैं","Masih di ponsel ini","Vẫn ở trên điện thoại","ยังอยู่บนโทรศัพท์","Ancora su questo telefono","Hâlâ bu telefonda","Nog op deze telefoon","Nadal na tym telefonie","Досі на телефоні"))

S("home_held_body", row(
"Not sent this time. Cat Diao will try again.",
"这次还没送达，猫叼会自动再试。",
"這次還沒送到，貓叼會自動再試。",
"今回は届きませんでした。自動でもう一度試します。",
"이번에는 전달되지 않았습니다. 자동으로 다시 시도합니다.",
"Esta vez no se envió. Cat Diao lo reintentará.",
"Pas parti cette fois. Cat Diao réessaiera.",
"Diesmal nicht angekommen. Cat Diao versucht es erneut.",
"Não foi desta vez. O Cat Diao tenta de novo.",
"В этот раз не ушло. Кэт Дяо попробует снова.",
"لم يُرسل هذه المرة. سيعيد كات دياو المحاولة.",
"इस बार नहीं गया। कैट डियाओ फिर कोशिश करेगा।",
"Belum terkirim kali ini. Cat Diao akan mencoba lagi.",
"Lần này chưa gửi được. Cat Diao sẽ thử lại.",
"ครั้งนี้ยังส่งไม่ถึง แคทเดียวจะลองใหม่",
"Questa volta non è partito. Cat Diao riproverà.",
"Bu sefer gitmedi. Cat Diao yeniden deneyecek.",
"Deze keer niet aangekomen. Cat Diao probeert het opnieuw.",
"Tym razem nie doszło. Cat Diao spróbuje ponownie.",
"Цього разу не пішло. Кет Дяо спробує ще раз."))

S("status_unpaired", row(
"Not paired yet","还未与电脑配对","尚未與電腦配對","まだペアリングしていません","아직 페어링되지 않음","Aún sin vincular","Pas encore appairé","Noch nicht gekoppelt","Ainda sem parear","Ещё не связано",
"لم يتم الاقتران بعد","अभी जोड़ा नहीं","Belum dipasangkan","Chưa ghép nối","ยังไม่ได้จับคู่","Non ancora associato","Henüz eşlenmedi","Nog niet gekoppeld","Jeszcze nie sparowano","Ще не зв'язано"))

S("status_stopped", row(
"Phone service stopped. Open Cat Diao again.",
"手机连接服务已停止，请重新打开猫叼。",
"手機連線服務已停止，請重新打開貓叼。",
"スマホ側のサービスが止まりました。開き直してください。",
"휴대폰 서비스가 멈췄습니다. 캣댜오를 다시 여세요.",
"El servicio del teléfono se detuvo. Abre Cat Diao de nuevo.",
"Le service du téléphone s'est arrêté. Rouvrez Cat Diao.",
"Der Telefondienst ist gestoppt. Öffne Cat Diao erneut.",
"O serviço do telefone parou. Abra o Cat Diao de novo.",
"Служба телефона остановилась. Откройте Кэт Дяо снова.",
"توقفت خدمة الهاتف. افتح كات دياو مرة أخرى.",
"फ़ोन सेवा रुक गई। कैट डियाओ फिर खोलें।",
"Layanan ponsel berhenti. Buka Cat Diao lagi.",
"Dịch vụ điện thoại đã dừng. Mở lại Cat Diao.",
"บริการบนโทรศัพท์หยุดแล้ว เปิดแคทเดียวอีกครั้ง",
"Il servizio del telefono si è fermato. Riapri Cat Diao.",
"Telefon hizmeti durdu. Cat Diao'yu yeniden aç.",
"De telefoondienst is gestopt. Open Cat Diao opnieuw.",
"Usługa telefonu zatrzymała się. Otwórz Cat Diao ponownie.",
"Служба телефону зупинилася. Відкрийте Кет Дяо знову."))

S("status_wifi", row(
"Wi-Fi · %1$s","Wi-Fi 已连接 · %1$s","Wi-Fi 已連線 · %1$s","Wi-Fi · %1$s","Wi-Fi · %1$s","Wi-Fi · %1$s","Wi-Fi · %1$s","WLAN · %1$s","Wi-Fi · %1$s","Wi-Fi · %1$s",
"Wi-Fi · %1$s","Wi-Fi · %1$s","Wi-Fi · %1$s","Wi-Fi · %1$s","Wi-Fi · %1$s","Wi-Fi · %1$s","Wi-Fi · %1$s","Wifi · %1$s","Wi-Fi · %1$s","Wi-Fi · %1$s"))

S("status_bluetooth", row(
"Bluetooth · %1$s","蓝牙已连接 · %1$s","藍牙已連線 · %1$s","Bluetooth · %1$s","블루투스 · %1$s","Bluetooth · %1$s","Bluetooth · %1$s","Bluetooth · %1$s","Bluetooth · %1$s","Bluetooth · %1$s",
"بلوتوث · %1$s","ब्लूटूथ · %1$s","Bluetooth · %1$s","Bluetooth · %1$s","บลูทูธ · %1$s","Bluetooth · %1$s","Bluetooth · %1$s","Bluetooth · %1$s","Bluetooth · %1$s","Bluetooth · %1$s"))

S("status_finding", row(
"Looking · %1$s","正在找小窝 · %1$s","正在找電腦 · %1$s","検索中 · %1$s","찾는 중 · %1$s","Buscando · %1$s","Recherche · %1$s","Suche · %1$s","Procurando · %1$s","Поиск · %1$s",
"بحث · %1$s","खोज · %1$s","Mencari · %1$s","Đang tìm · %1$s","กำลังหา · %1$s","Ricerca · %1$s","Aranıyor · %1$s","Zoeken · %1$s","Szukanie · %1$s","Пошук · %1$s"))

S("status_offline", row(
"Not connected · %1$s","小窝暂未连接 · %1$s","電腦尚未連線 · %1$s","未接続 · %1$s","연결 안 됨 · %1$s","Sin conexión · %1$s","Hors ligne · %1$s","Nicht verbunden · %1$s","Sem conexão · %1$s","Нет связи · %1$s",
"غير متصل · %1$s","जुड़ा नहीं · %1$s","Tidak tersambung · %1$s","Chưa kết nối · %1$s","ยังไม่เชื่อมต่อ · %1$s","Non connesso · %1$s","Bağlı değil · %1$s","Niet verbonden · %1$s","Brak połączenia · %1$s","Немає зв'язку · %1$s"))

S("toast_service_down", row(
"Cat Diao can't reach the computer. Open the app again.",
"猫叼暂时连不上小窝，请重新打开",
"貓叼暫時連不上電腦，請重新打開",
"パソコンに届きません。アプリを開き直してください。",
"컴퓨터에 닿지 않습니다. 앱을 다시 여세요.",
"Cat Diao no llega al ordenador. Abre la app de nuevo.",
"Cat Diao n'atteint pas l'ordinateur. Rouvrez l'application.",
"Cat Diao erreicht den Computer nicht. Öffne die App erneut.",
"O Cat Diao não alcança o computador. Abra o app de novo.",
"Кэт Дяо не достучался до компьютера. Откройте приложение снова.",
"تعذر الوصول إلى الكمبيوتر. افتح التطبيق مرة أخرى.",
"कंप्यूटर तक नहीं पहुँच सका। ऐप फिर खोलें।",
"Cat Diao tidak menjangkau komputer. Buka aplikasi lagi.",
"Cat Diao không tới được máy tính. Mở lại ứng dụng.",
"แคทเดียวติดต่อคอมพิวเตอร์ไม่ได้ เปิดแอปอีกครั้ง",
"Cat Diao non raggiunge il computer. Riapri l'app.",
"Cat Diao bilgisayara ulaşamıyor. Uygulamayı yeniden aç.",
"Cat Diao bereikt de computer niet. Open de app opnieuw.",
"Cat Diao nie sięga komputera. Otwórz aplikację ponownie.",
"Кет Дяо не досягає комп'ютера. Відкрийте застосунок знову."))

S("toast_check_failed", row(
"Couldn't start the check. Open Cat Diao again.",
"连接检查未能启动，请重新打开猫叼。",
"連線檢查未能啟動，請重新打開貓叼。",
"確認を開始できませんでした。開き直してください。",
"확인을 시작하지 못했습니다. 캣댜오를 다시 여세요.",
"No se pudo iniciar la comprobación. Abre Cat Diao de nuevo.",
"Impossible de lancer la vérification. Rouvrez Cat Diao.",
"Prüfung konnte nicht starten. Öffne Cat Diao erneut.",
"Não foi possível iniciar a verificação. Abra o Cat Diao de novo.",
"Не удалось начать проверку. Откройте Кэт Дяо снова.",
"تعذر بدء الفحص. افتح كات دياو مرة أخرى.",
"जाँच शुरू नहीं हुई। कैट डियाओ फिर खोलें।",
"Pemeriksaan tidak bisa dimulai. Buka Cat Diao lagi.",
"Không bắt đầu được lần kiểm tra. Mở lại Cat Diao.",
"เริ่มการตรวจไม่ได้ เปิดแคทเดียวอีกครั้ง",
"Impossibile avviare il controllo. Riapri Cat Diao.",
"Denetim başlatılamadı. Cat Diao'yu yeniden aç.",
"De controle startte niet. Open Cat Diao opnieuw.",
"Nie udało się rozpocząć sprawdzania. Otwórz Cat Diao ponownie.",
"Не вдалося почати перевірку. Відкрийте Кет Дяо знову."))

S("toast_battery_settings", row(
"In battery settings, allow Cat Diao to run in the background.",
"请到系统设置的电池页面，允许猫叼在后台运行",
"請到系統設定的電池頁面，允許貓叼在背景執行",
"電池設定で、バックグラウンド実行を許可してください。",
"배터리 설정에서 백그라운드 실행을 허용하세요.",
"En los ajustes de batería, permite que Cat Diao siga en segundo plano.",
"Dans les réglages de batterie, autorisez Cat Diao en arrière-plan.",
"Erlaube Cat Diao in den Akku-Einstellungen die Hintergrundnutzung.",
"Nas configurações de bateria, permita o Cat Diao em segundo plano.",
"В настройках батареи разрешите работу в фоне.",
"في إعدادات البطارية، اسمح لكات دياو بالعمل في الخلفية.",
"बैटरी सेटिंग में कैट डियाओ को बैकग्राउंड में चलने दें।",
"Di setelan baterai, izinkan Cat Diao berjalan di latar belakang.",
"Trong phần pin, hãy cho Cat Diao chạy nền.",
"ในการตั้งค่าแบตเตอรี่ อนุญาตให้แคทเดียวทำงานเบื้องหลัง",
"Nelle impostazioni batteria, consenti a Cat Diao di restare in background.",
"Pil ayarlarında Cat Diao'nun arka planda çalışmasına izin ver.",
"Sta in de batterij-instellingen toe dat Cat Diao op de achtergrond draait.",
"W ustawieniach baterii zezwól Cat Diao na działanie w tle.",
"У налаштуваннях батареї дозвольте роботу у фоні."))

S("toast_overlay_on", row(
"Floating cat is on","悬浮猫已开启","懸浮貓已開啟","浮き猫を表示しました","떠 있는 고양이를 켰습니다","El gato ya se muestra","Le chat est affiché","Katze ist eingeblendet","O gato está ligado","Кот показан",
"القطة العائمة تعمل","बिल्ली चालू है","Kucing sudah nyala","Đã bật mèo nổi","เปิดแมวลอยแล้ว","Il gatto è attivo","Kedi açık","Kat staat aan","Kot jest włączony","Кота показано"))

S("toast_overlay_kept_off", row(
"Floating cat stays off. Other ways to save still work.",
"悬浮猫未开启，其他收藏方式仍可使用",
"懸浮貓未開啟，其他收藏方式仍可使用",
"浮き猫はオフのままです。ほかの方法で保存できます。",
"떠 있는 고양이는 꺼진 채로 있습니다. 다른 저장 방법은 됩니다.",
"El gato sigue apagado. Aún puedes guardar de otras formas.",
"Le chat reste masqué. Vous pouvez encore enregistrer autrement.",
"Die Katze bleibt aus. Sichern geht auch anders.",
"O gato continua desligado. Dá para salvar de outros jeitos.",
"Кот остаётся выключенным. Сохранять можно иначе.",
"تبقى القطة متوقفة. ما زال الحفظ ممكنًا بطرق أخرى.",
"बिल्ली बंद रहेगी। सहेजने के और तरीके चलते रहेंगे।",
"Kucing tetap mati. Cara menyimpan yang lain tetap bisa.",
"Mèo nổi vẫn tắt. Cách lưu khác vẫn dùng được.",
"แมวลอยยังปิดอยู่ ยังบันทึกด้วยวิธีอื่นได้",
"Il gatto resta spento. Puoi salvare in altri modi.",
"Kedi kapalı kalır. Başka kaydetme yolları çalışır.",
"De kat blijft uit. Opslaan kan ook anders.",
"Kot pozostaje wyłączony. Zapisywać można inaczej.",
"Кіт лишається вимкненим. Зберігати можна інакше."))

S("toast_bt_off", row(
"Bluetooth is off. Wi-Fi still works.",
"蓝牙未开启，仍可通过 Wi-Fi 连接",
"藍牙未開啟，仍可透過 Wi-Fi 連線",
"Bluetooth はオフです。Wi-Fi は使えます。",
"블루투스가 꺼져 있습니다. Wi-Fi는 됩니다.",
"Bluetooth está apagado. El Wi-Fi sigue funcionando.",
"Bluetooth est désactivé. Le Wi-Fi fonctionne encore.",
"Bluetooth ist aus. WLAN funktioniert weiter.",
"O Bluetooth está desligado. O Wi-Fi continua funcionando.",
"Bluetooth выключен. Wi-Fi по-прежнему работает.",
"البلوتوث متوقف. ما زال Wi-Fi يعمل.",
"ब्लूटूथ बंद है। Wi-Fi फिर भी चलेगा।",
"Bluetooth mati. Wi-Fi tetap bisa.",
"Bluetooth đang tắt. Wi-Fi vẫn dùng được.",
"บลูทูธปิดอยู่ Wi-Fi ยังใช้ได้",
"Bluetooth è spento. Il Wi-Fi funziona ancora.",
"Bluetooth kapalı. Wi-Fi çalışmaya devam eder.",
"Bluetooth staat uit. Wifi werkt nog.",
"Bluetooth jest wyłączony. Wi-Fi nadal działa.",
"Bluetooth вимкнено. Wi-Fi і далі працює."))

S("toast_lan_needed", row(
"Allow local network access so Cat Diao can find the computer.",
"允许局域网访问后，猫叼才能找到小窝",
"允許區域網路存取後，貓叼才能找到電腦",
"ローカルネットワークを許可するとパソコンを探せます。",
"로컬 네트워크를 허용해야 컴퓨터를 찾을 수 있습니다.",
"Permite el acceso a la red local para encontrar el ordenador.",
"Autorisez le réseau local pour trouver l'ordinateur.",
"Erlaube den Zugriff aufs lokale Netz, damit der Computer gefunden wird.",
"Permita o acesso à rede local para achar o computador.",
"Разрешите локальную сеть, чтобы найти компьютер.",
"اسمح بالشبكة المحلية حتى يجد كات دياو الكمبيوتر.",
"लोकल नेटवर्क की अनुमति दें ताकि कंप्यूटर मिल सके।",
"Izinkan jaringan lokal agar komputer bisa ditemukan.",
"Hãy cho phép mạng cục bộ để tìm máy tính.",
"อนุญาตเครือข่ายท้องถิ่นเพื่อหาคอมพิวเตอร์",
"Consenti la rete locale per trovare il computer.",
"Bilgisayarı bulmak için yerel ağa izin ver.",
"Sta lokaal netwerk toe, zodat de computer gevonden wordt.",
"Zezwól na sieć lokalną, aby znaleźć komputer.",
"Дозвольте локальну мережу, щоб знайти комп'ютер."))

S("toast_need_pair_first", row(
"Start the connection on the computer, then confirm it here.",
"请先从小窝发起连接，并在猫叼确认",
"請先從電腦發起連線，並在貓叼確認",
"まずパソコンから接続し、ここで確認してください。",
"먼저 컴퓨터에서 연결을 시작한 뒤 여기서 확인하세요.",
"Inicia la conexión en el ordenador y confírmala aquí.",
"Lancez la connexion sur l'ordinateur, puis confirmez ici.",
"Starte die Verbindung am Computer und bestätige sie hier.",
"Inicie a conexão no computador e confirme aqui.",
"Начните подключение на компьютере и подтвердите его здесь.",
"ابدأ الاتصال من الكمبيوتر ثم أكّده هنا.",
"कनेक्शन कंप्यूटर से शुरू करें, फिर यहाँ पुष्टि करें।",
"Mulai sambungan dari komputer, lalu konfirmasi di sini.",
"Hãy bắt đầu kết nối từ máy tính, rồi xác nhận ở đây.",
"เริ่มการเชื่อมต่อจากคอมพิวเตอร์ แล้วยืนยันที่นี่",
"Avvia la connessione dal computer e confermala qui.",
"Bağlantıyı bilgisayardan başlat, sonra burada onayla.",
"Start de verbinding op de computer en bevestig die hier.",
"Rozpocznij połączenie na komputerze i potwierdź je tutaj.",
"Почніть підключення на комп'ютері й підтвердьте його тут."))

S("toast_file_unopenable", row(
"Can't open this file. Try another.",
"这个文件打不开，请换一个再试",
"這個檔案打不開，請換一個再試",
"このファイルは開けません。別のファイルを試してください。",
"이 파일은 열 수 없습니다. 다른 파일을 고르세요.",
"No se puede abrir este archivo. Prueba con otro.",
"Impossible d'ouvrir ce fichier. Essayez-en un autre.",
"Diese Datei lässt sich nicht öffnen. Nimm eine andere.",
"Não dá para abrir este arquivo. Tente outro.",
"Не удаётся открыть этот файл. Выберите другой.",
"تعذر فتح هذا الملف. جرّب ملفًا آخر.",
"यह फ़ाइल नहीं खुली। दूसरी आज़माएँ।",
"File ini tidak bisa dibuka. Coba yang lain.",
"Không mở được tệp này. Hãy thử tệp khác.",
"เปิดไฟล์นี้ไม่ได้ ลองไฟล์อื่น",
"Impossibile aprire questo file. Provane un altro.",
"Bu dosya açılmıyor. Başka birini dene.",
"Dit bestand gaat niet open. Probeer een ander.",
"Nie można otworzyć tego pliku. Spróbuj innego.",
"Не вдається відкрити цей файл. Спробуйте інший."))

S("toast_clipboard_needs_wifi", row(
"Sending to the clipboard needs Wi-Fi. You can save the text instead.",
"发送到电脑剪贴板需要 Wi-Fi；也可以先收藏这段文字",
"傳到電腦剪貼簿需要 Wi-Fi；也可以先收藏這段文字",
"クリップボードへ送るには Wi-Fi が必要です。先に保存もできます。",
"컴퓨터 클립보드로 보내려면 Wi-Fi가 필요합니다. 먼저 저장할 수도 있습니다.",
"Enviar al portapapeles exige Wi-Fi. También puedes guardarlo.",
"L'envoi vers le presse-papiers exige le Wi-Fi. Vous pouvez d'abord l'enregistrer.",
"Die Zwischenablage braucht WLAN. Du kannst den Text auch erst sichern.",
"Enviar à área de transferência exige Wi-Fi. Você também pode salvar o texto.",
"Буфер компьютера требует Wi-Fi. Текст можно сначала сохранить.",
"الإرسال إلى الحافظة يحتاج Wi-Fi. يمكنك حفظ النص بدلًا من ذلك.",
"क्लिपबोर्ड पर भेजने के लिए Wi-Fi चाहिए। टेक्स्ट पहले सहेज भी सकते हैं।",
"Mengirim ke papan klip perlu Wi-Fi. Anda juga bisa menyimpan teksnya dulu.",
"Gửi tới bảng nhớ tạm cần Wi-Fi. Bạn cũng có thể lưu chữ trước.",
"การส่งไปคลิปบอร์ดต้องใช้ Wi-Fi จะบันทึกข้อความก่อนก็ได้",
"Gli appunti del computer richiedono il Wi-Fi. Puoi prima salvare il testo.",
"Panoya göndermek Wi-Fi ister. Metni önce kaydedebilirsin.",
"Het klembord heeft wifi nodig. Je kunt de tekst ook eerst bewaren.",
"Schowek komputera wymaga Wi-Fi. Tekst można najpierw zapisać.",
"Буфер комп'ютера потребує Wi-Fi. Текст можна спершу зберегти."))

S("toast_connect_first", row(
"Connect the computer first","请先连接小窝","請先連線電腦","先にパソコンを接続してください","먼저 컴퓨터를 연결하세요","Conecta primero el ordenador","Connectez d'abord l'ordinateur","Verbinde zuerst den Computer","Conecte o computador primeiro","Сначала подключите компьютер",
"صِل الكمبيوتر أولًا","पहले कंप्यूटर जोड़ें","Hubungkan komputer dulu","Hãy kết nối máy tính trước","เชื่อมต่อคอมพิวเตอร์ก่อน","Collega prima il computer","Önce bilgisayarı bağla","Verbind eerst de computer","Najpierw połącz komputer","Спочатку підключіть комп'ютер"))

S("toast_copy_first", row(
"Copy some text on the phone first",
"请先在手机上复制一段文字",
"請先在手機上複製一段文字",
"先にスマホで文字をコピーしてください",
"먼저 휴대폰에서 글을 복사하세요",
"Copia primero un texto en el teléfono",
"Copiez d'abord un texte sur le téléphone",
"Kopiere zuerst Text auf dem Telefon",
"Copie primeiro um texto no telefone",
"Сначала скопируйте текст на телефоне",
"انسخ نصًا على الهاتف أولًا",
"पहले फ़ोन पर कुछ टेक्स्ट कॉपी करें",
"Salin dulu teks di ponsel",
"Hãy chép chữ trên điện thoại trước",
"คัดลอกข้อความบนโทรศัพท์ก่อน",
"Copia prima un testo sul telefono",
"Önce telefonda bir metin kopyala",
"Kopieer eerst tekst op de telefoon",
"Najpierw skopiuj tekst na telefonie",
"Спочатку скопіюйте текст на телефоні"))

S("toast_clipboard_empty", row(
"Nothing on the clipboard","手机剪贴板里没有文字","手機剪貼簿裡沒有文字","クリップボードは空です","클립보드가 비어 있습니다","El portapapeles está vacío","Le presse-papiers est vide","Die Zwischenablage ist leer","A área de transferência está vazia","Буфер пуст",
"الحافظة فارغة","क्लिपबोर्ड खाली है","Papan klip kosong","Bảng nhớ tạm trống","คลิปบอร์ดว่าง","Gli appunti sono vuoti","Pano boş","Klembord is leeg","Schowek jest pusty","Буфер порожній"))

S("toast_text_too_big", row(
"Text is over 256 KB. Send a file instead.",
"文字超过 256 KB，请改用文件传输",
"文字超過 256 KB，請改用檔案傳送",
"文字が 256 KB を超えています。ファイルで送ってください。",
"글이 256 KB를 넘습니다. 파일로 보내세요.",
"El texto supera 256 KB. Envía un archivo.",
"Le texte dépasse 256 Ko. Envoyez un fichier.",
"Der Text ist größer als 256 KB. Sende eine Datei.",
"O texto passa de 256 KB. Envie um arquivo.",
"Текст больше 256 КБ. Отправьте файл.",
"النص أكبر من 256 كيلوبايت. أرسل ملفًا.",
"टेक्स्ट 256 KB से बड़ा है। फ़ाइल भेजें।",
"Teks lebih dari 256 KB. Kirim sebagai file.",
"Chữ vượt quá 256 KB. Hãy gửi bằng tệp.",
"ข้อความเกิน 256 KB ให้ส่งเป็นไฟล์",
"Il testo supera 256 KB. Invia un file.",
"Metin 256 KB'den büyük. Dosya olarak gönder.",
"De tekst is groter dan 256 KB. Stuur een bestand.",
"Tekst ma ponad 256 KB. Wyślij plik.",
"Текст понад 256 КБ. Надішліть файл."))

S("toast_clipboard_sent", row(
"On the computer clipboard","已放入小窝剪贴板","已放入電腦剪貼簿","パソコンのクリップボードに入れました","컴퓨터 클립보드에 넣었습니다","En el portapapeles del ordenador","Dans le presse-papiers de l'ordinateur","In der Zwischenablage des Computers","Na área de transferência do computador","В буфере компьютера",
"في حافظة الكمبيوتر","कंप्यूटर के क्लिपबोर्ड पर","Di papan klip komputer","Đã vào bảng nhớ tạm của máy tính","อยู่ในคลิปบอร์ดของคอมพิวเตอร์","Negli appunti del computer","Bilgisayarın panosunda","Op het klembord van de computer","W schowku komputera","У буфері комп'ютера"))

S("toast_clipboard_failed", row(
"The computer didn't receive it. Try again.",
"小窝暂时没收到，稍后再试",
"電腦暫時沒收到，稍後再試",
"パソコンが受け取りませんでした。あとでもう一度。",
"컴퓨터가 받지 못했습니다. 나중에 다시 시도하세요.",
"El ordenador no lo recibió. Inténtalo luego.",
"L'ordinateur ne l'a pas reçu. Réessayez plus tard.",
"Der Computer hat es nicht bekommen. Versuch es später erneut.",
"O computador não recebeu. Tente de novo depois.",
"Компьютер не получил. Попробуйте позже.",
"لم يستلمه الكمبيوتر. حاول لاحقًا.",
"कंप्यूटर को नहीं मिला। बाद में फिर कोशिश करें।",
"Komputer tidak menerimanya. Coba lagi nanti.",
"Máy tính chưa nhận. Hãy thử lại sau.",
"คอมพิวเตอร์ยังไม่ได้รับ ลองใหม่ภายหลัง",
"Il computer non l'ha ricevuto. Riprova più tardi.",
"Bilgisayar almadı. Sonra yeniden dene.",
"De computer ontving het niet. Probeer het later opnieuw.",
"Komputer tego nie dostał. Spróbuj później.",
"Комп'ютер не отримав. Спробуйте пізніше."))

S("toast_file_needs_wifi", row(
"Files need the phone and computer on the same Wi-Fi.",
"传文件请让手机和电脑连同一个 Wi-Fi",
"傳檔案請讓手機和電腦連同一個 Wi-Fi",
"ファイルはスマホとパソコンが同じ Wi-Fi である必要があります。",
"파일은 휴대폰과 컴퓨터가 같은 Wi-Fi에 있어야 합니다.",
"Los archivos necesitan el teléfono y el ordenador en el mismo Wi-Fi.",
"Les fichiers exigent le téléphone et l'ordinateur sur le même Wi-Fi.",
"Dateien brauchen Telefon und Computer im selben WLAN.",
"Arquivos exigem o telefone e o computador no mesmo Wi-Fi.",
"Для файлов телефон и компьютер должны быть в одном Wi-Fi.",
"الملفات تحتاج الهاتف والكمبيوتر على شبكة Wi-Fi نفسها.",
"फ़ाइलों के लिए फ़ोन और कंप्यूटर एक ही Wi-Fi पर हों।",
"File perlu ponsel dan komputer di Wi-Fi yang sama.",
"Tệp cần điện thoại và máy tính cùng một Wi-Fi.",
"ไฟล์ต้องให้โทรศัพท์และคอมพิวเตอร์อยู่ Wi-Fi เดียวกัน",
"I file richiedono telefono e computer sulla stessa Wi-Fi.",
"Dosyalar için telefon ve bilgisayar aynı Wi-Fi'de olmalı.",
"Bestanden vragen telefoon en computer op hetzelfde wifi-netwerk.",
"Pliki wymagają telefonu i komputera w tej samej sieci Wi-Fi.",
"Для файлів телефон і комп'ютер мають бути в одному Wi-Fi."))

S("toast_file_sending", row(
"Sending the file…","正在发送文件到小窝…","正在傳送檔案到電腦…","ファイルを送っています…","파일을 보내는 중…","Enviando el archivo…","Envoi du fichier…","Datei wird gesendet…","Enviando o arquivo…","Отправка файла…",
"جارٍ إرسال الملف…","फ़ाइल भेजी जा रही है…","Mengirim file…","Đang gửi tệp…","กำลังส่งไฟล์…","Invio del file…","Dosya gönderiliyor…","Bestand wordt verstuurd…","Wysyłanie pliku…","Надсилання файла…"))

S("toast_file_failed", row(
"The computer didn't get the file. Try again.",
"小窝暂时没收到文件，稍后再试",
"電腦暫時沒收到檔案，稍後再試",
"パソコンがファイルを受け取りませんでした。あとでもう一度。",
"컴퓨터가 파일을 받지 못했습니다. 나중에 다시 시도하세요.",
"El ordenador no recibió el archivo. Inténtalo luego.",
"L'ordinateur n'a pas reçu le fichier. Réessayez plus tard.",
"Der Computer hat die Datei nicht bekommen. Versuch es später erneut.",
"O computador não recebeu o arquivo. Tente de novo depois.",
"Компьютер не получил файл. Попробуйте позже.",
"لم يصل الملف إلى الكمبيوتر. حاول لاحقًا.",
"कंप्यूटर को फ़ाइल नहीं मिली। बाद में फिर कोशिश करें।",
"Komputer tidak menerima file. Coba lagi nanti.",
"Máy tính chưa nhận tệp. Hãy thử lại sau.",
"คอมพิวเตอร์ยังไม่ได้รับไฟล์ ลองใหม่ภายหลัง",
"Il computer non ha ricevuto il file. Riprova più tardi.",
"Bilgisayar dosyayı almadı. Sonra yeniden dene.",
"De computer kreeg het bestand niet. Probeer het later opnieuw.",
"Komputer nie dostał pliku. Spróbuj później.",
"Комп'ютер не отримав файл. Спробуйте пізніше."))

S("toast_file_sent", row(
"On the computer: %1$s","已到小窝：%1$s","已到電腦：%1$s","パソコンに届きました：%1$s","컴퓨터에 도착: %1$s","En el ordenador: %1$s","Sur l'ordinateur : %1$s","Auf dem Computer: %1$s","No computador: %1$s","На компьютере: %1$s",
"على الكمبيوتر: %1$s","कंप्यूटर पर: %1$s","Di komputer: %1$s","Đã tới máy tính: %1$s","ถึงคอมพิวเตอร์แล้ว: %1$s","Sul computer: %1$s","Bilgisayarda: %1$s","Op de computer: %1$s","Na komputerze: %1$s","На комп'ютері: %1$s"))

S("toast_file_name", row(
"file","文件","檔案","ファイル","파일","archivo","fichier","Datei","arquivo","файл",
"ملف","फ़ाइल","file","tệp","ไฟล์","file","dosya","bestand","plik","файл"))

S("toast_setup_ready", row(
"Computer link is ready","小窝连接已设好","電腦連線已設好","パソコン接続の準備ができました","컴퓨터 연결이 준비되었습니다","El enlace está listo","La connexion est prête","Die Verbindung ist bereit","A ligação está pronta","Связь готова",
"اتصال الكمبيوتر جاهز","कनेक्शन तैयार है","Sambungan siap","Kết nối đã sẵn sàng","การเชื่อมต่อพร้อมแล้ว","Il collegamento è pronto","Bağlantı hazır","De verbinding is klaar","Połączenie gotowe","Зв'язок готовий"))

S("toast_settings_fail", row(
"Can't open settings. Turn Cat Diao on from the phone settings.",
"无法打开系统设置，请从手机设置里开启猫叼",
"無法打開系統設定，請從手機設定裡開啟貓叼",
"設定を開けません。スマホの設定から許可してください。",
"설정을 열 수 없습니다. 휴대폰 설정에서 켜 주세요.",
"No se pueden abrir los ajustes. Actívalo en los ajustes del teléfono.",
"Impossible d'ouvrir les réglages. Activez-le dans les réglages du téléphone.",
"Einstellungen lassen sich nicht öffnen. Schalte es in den Telefoneinstellungen ein.",
"Não foi possível abrir as configurações. Ative nas configurações do telefone.",
"Не удалось открыть настройки. Включите это в настройках телефона.",
"تعذر فتح الإعدادات. فعّله من إعدادات الهاتف.",
"सेटिंग नहीं खुली। फ़ोन की सेटिंग से चालू करें।",
"Setelan tidak bisa dibuka. Aktifkan dari setelan ponsel.",
"Không mở được phần cài đặt. Hãy bật trong cài đặt điện thoại.",
"เปิดการตั้งค่าไม่ได้ ให้เปิดจากตั้งค่าโทรศัพท์",
"Impossibile aprire le impostazioni. Attivalo dalle impostazioni del telefono.",
"Ayarlar açılmıyor. Telefon ayarlarından aç.",
"Instellingen gaan niet open. Zet het aan in de telefooninstellingen.",
"Nie można otworzyć ustawień. Włącz to w ustawieniach telefonu.",
"Не вдалося відкрити налаштування. Увімкніть це в налаштуваннях телефону."))

S("toast_app_info_fail", row(
"Open Cat Diao's app info in system settings.",
"请到系统设置里打开猫叼的应用信息",
"請到系統設定裡打開貓叼的應用程式資訊",
"システムのアプリ情報を開いてください。",
"시스템 설정의 앱 정보를 여세요.",
"Abre la información de la app en los ajustes.",
"Ouvrez la fiche de l'application dans les réglages.",
"Öffne die App-Info in den Systemeinstellungen.",
"Abra as informações do app nas configurações.",
"Откройте сведения о приложении в настройках.",
"افتح معلومات التطبيق من إعدادات النظام.",
"सिस्टम सेटिंग में ऐप जानकारी खोलें।",
"Buka info aplikasi di setelan sistem.",
"Hãy mở thông tin ứng dụng trong cài đặt.",
"เปิดข้อมูลแอปในการตั้งค่าระบบ",
"Apri le info dell'app nelle impostazioni.",
"Sistem ayarlarında uygulama bilgisini aç.",
"Open de app-info in de systeeminstellingen.",
"Otwórz informacje o aplikacji w ustawieniach.",
"Відкрийте відомості про застосунок у налаштуваннях."))

S("toast_a11y_fail", row(
"In Accessibility, turn on Cat Diao screen control.",
"请到系统设置的无障碍里开启「猫叼屏幕控制」",
"請到系統設定的無障礙裡開啟「貓叼螢幕控制」",
"ユーザー補助で画面操作をオンにしてください。",
"접근성에서 화면 제어를 켜세요.",
"En Accesibilidad, activa el control de pantalla.",
"Dans Accessibilité, activez le contrôle de l'écran.",
"Aktiviere die Bildschirmsteuerung unter Bedienungshilfen.",
"Em Acessibilidade, ative o controle de tela.",
"Во включении спецвозможностей включите управление экраном.",
"في إمكانية الوصول، شغّل التحكم بالشاشة.",
"ऐक्सेसिबिलिटी में स्क्रीन नियंत्रण चालू करें।",
"Di Aksesibilitas, aktifkan kontrol layar.",
"Trong Trợ năng, hãy bật điều khiển màn hình.",
"ในความสามารถในการเข้าถึง ให้เปิดควบคุมหน้าจอ",
"In Accessibilità, attiva il controllo schermo.",
"Erişilebilirlik'te ekran denetimini aç.",
"Zet onder Toegankelijkheid de schermbediening aan.",
"W ułatwieniach dostępu włącz sterowanie ekranem.",
"У спецможливостях увімкніть керування екраном."))

S("toast_bt_settings_fail", row(
"Turn on Bluetooth in the phone settings.",
"请在手机设置中打开蓝牙",
"請在手機設定中打開藍牙",
"スマホの設定で Bluetooth をオンにしてください。",
"휴대폰 설정에서 블루투스를 켜세요.",
"Activa Bluetooth en los ajustes del teléfono.",
"Activez Bluetooth dans les réglages du téléphone.",
"Schalte Bluetooth in den Telefoneinstellungen ein.",
"Ative o Bluetooth nas configurações do telefone.",
"Включите Bluetooth в настройках телефона.",
"شغّل البلوتوث من إعدادات الهاتف.",
"फ़ोन सेटिंग में ब्लूटूथ चालू करें।",
"Nyalakan Bluetooth di setelan ponsel.",
"Hãy bật Bluetooth trong cài đặt điện thoại.",
"เปิดบลูทูธในการตั้งค่าโทรศัพท์",
"Attiva Bluetooth nelle impostazioni del telefono.",
"Telefon ayarlarında Bluetooth'u aç.",
"Zet Bluetooth aan in de telefooninstellingen.",
"Włącz Bluetooth w ustawieniach telefonu.",
"Увімкніть Bluetooth у налаштуваннях телефону."))
# fmt: on

def emit():
    import more_strings
    more_strings.load(S, P, row)
    _check()
    folders = {qual: locale for locale, qual in QUALIFIER.items()}
    for alias, locale in ALIASES.items():
        folders[alias] = locale
    for folder, locale in folders.items():
        path = RES / folder
        path.mkdir(parents=True, exist_ok=True)
        lines = [
            '<?xml version="1.0" encoding="utf-8"?>',
            "<resources>",
            "    <!-- Generated by android/i18n/catalog.py. Not native-speaker reviewed. -->",
        ]
        for key in STRINGS:
            lines.append(f'    <string name="{key}">{xml_text(STRINGS[key][locale])}</string>')
        for key in PLURALS:
            lines.append(f'    <plurals name="{key}">')
            for quantity, text in PLURALS[key][locale].items():
                lines.append(f'        <item quantity="{quantity}">{xml_text(text)}</item>')
            lines.append("    </plurals>")
        lines.append("</resources>")
        lines.append("")
        (path / "strings.xml").write_text("\n".join(lines), encoding="utf-8")
    print(f"wrote {len(folders)} folders, {len(STRINGS)} strings, {len(PLURALS)} plurals")


def xml_text(value):
    # Double real backslashes first. Android eats a single \ before % or a letter,
    # which turned scripts\run.ps1 into scriptsrun.ps1.
    return (value.replace("\\", "\\\\")
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("'", "\\'")
            .replace('"', "&quot;")
            .replace("\n", "\\n"))


def _check():
    for key, values in STRINGS.items():
        if set(values) != set(LOCALES):
            raise SystemExit(f"{key} locales {set(values) ^ set(LOCALES)}")
        if any(not text.strip() for text in values.values()):
            raise SystemExit(f"empty {key}")
    for key, forms in PLURALS.items():
        if set(forms) != set(LOCALES):
            raise SystemExit(f"plural {key} locales")


if __name__ == "__main__":
    emit()
