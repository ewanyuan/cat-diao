#!/usr/bin/env python3
"""Resource completeness for every shipped locale. No device or emulator is required."""
import pathlib
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
RES = ROOT / "android" / "app" / "src" / "main" / "res"
JAVA = ROOT / "android" / "app" / "src" / "main" / "java"

LOCALES = [
    "values", "values-zh-rCN", "values-zh-rTW", "values-ja", "values-ko", "values-es",
    "values-fr", "values-de", "values-pt-rBR", "values-ru", "values-ar", "values-hi",
    "values-id", "values-vi", "values-th", "values-it", "values-tr", "values-nl",
    "values-pl", "values-uk",
]
ALIASES = {
    "values-zh": "values-zh-rCN",
    "values-zh-rSG": "values-zh-rCN",
    "values-b+zh+Hans": "values-zh-rCN",
    "values-zh-rHK": "values-zh-rTW",
    "values-zh-rMO": "values-zh-rTW",
    "values-b+zh+Hant": "values-zh-rTW",
    "values-in": "values-id",
}
PLURAL_RULES = {
    "values": {"one", "other"},
    "values-es": {"one", "other"},
    "values-fr": {"one", "other"},
    "values-de": {"one", "other"},
    "values-pt-rBR": {"one", "other"},
    "values-hi": {"one", "other"},
    "values-it": {"one", "other"},
    "values-tr": {"one", "other"},
    "values-nl": {"one", "other"},
    "values-ru": {"one", "few", "many", "other"},
    "values-pl": {"one", "few", "many", "other"},
    "values-uk": {"one", "few", "many", "other"},
    "values-ar": {"zero", "one", "two", "few", "many", "other"},
}
TOKEN = re.compile(r"%(\d+\$)?[sd]")
BUTTON_KEYS = {
    "action_later", "action_cancel", "action_continue", "action_done", "action_remove",
    "menu_language", "menu_send", "menu_overlay_off", "menu_overlay_on", "menu_connection",
    "menu_guide", "menu_bluetooth", "panel_dismiss", "panel_disable", "notif_hide_cat",
    "conn_start", "conn_copy", "conn_allow", "conn_deny", "conn_done", "conn_retry",
    "conn_close", "conn_back", "conn_show_qr", "conn_hide_qr", "conn_recheck", "conn_try",
    "conn_copy_command", "home_connect_link",
    "onb_skip", "onb_back", "onb_next", "onb_start", "home_retry", "home_capture",
    "capture_save", "pause_remote", "resume_remote",
}
# Stored capture sources, protocol name, and the download folder stay stable.
ALLOWED_CJK_LITERALS = {
    "猫叼", "下载/猫叼", "/猫叼", "猫叼小窝", "系统分享", "系统分享：", "悬浮猫",
    "复制链接", "未知应用", "分享",
    # Language names stay in their own script inside the picker.
    "简体中文", "繁體中文", "日本語",
}


def load(folder):
    path = RES / folder / "strings.xml"
    root = ET.parse(path).getroot()
    strings = {}
    plurals = {}
    for node in root:
        if node.tag == "string":
            strings[node.attrib["name"]] = node.text or ""
        elif node.tag == "plurals":
            plurals[node.attrib["name"]] = {
                item.attrib["quantity"]: item.text or "" for item in node
            }
    return strings, plurals


def tokens(value):
    return TOKEN.findall(value)


def fill(value):
    def repl(match):
        return "1" if match.group(0).endswith("d") else "x"
    return TOKEN.sub(repl, value)


class StringResourcesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.packs = {folder: load(folder) for folder in LOCALES}

    def test_every_locale_has_the_same_keys(self):
        base_strings, base_plurals = self.packs["values"]
        self.assertGreater(len(base_strings), 200)
        for folder, (strings, plurals) in self.packs.items():
            self.assertEqual(set(strings), set(base_strings), folder)
            self.assertEqual(set(plurals), set(base_plurals), folder)

    def test_format_tokens_and_escaping(self):
        base_strings, base_plurals = self.packs["values"]
        for folder, (strings, plurals) in self.packs.items():
            for key, value in strings.items():
                self.assertTrue(value.strip(), f"{folder}:{key}")
                self.assertEqual(tokens(value), tokens(base_strings[key]), f"{folder}:{key}")
                filled = fill(value)
                self.assertNotIn("%", filled.replace("%%", ""), f"leftover format {folder}:{key}")
            for key, forms in plurals.items():
                self.assertIn("other", forms, f"{folder}:{key}")
                expected = PLURAL_RULES.get(folder, {"other"})
                self.assertTrue(expected <= set(forms), f"{folder}:{key} {forms.keys()}")
                base_tokens = tokens(next(iter(base_plurals[key].values())))
                for quantity, value in forms.items():
                    self.assertTrue(value.strip(), f"{folder}:{key}:{quantity}")
                    self.assertEqual(tokens(value), base_tokens, f"{folder}:{key}:{quantity}")
                    self.assertNotIn("%", fill(value).replace("%%", ""))

    def test_short_controls_and_first_run_copy(self):
        for folder, (strings, _) in self.packs.items():
            for key in BUTTON_KEYS:
                self.assertLessEqual(len(strings[key]), 48, f"{folder}:{key}={strings[key]}")
            for key in ("conn_not_paired_body", "conn_waiting_body", "conn_pairing_body"):
                self.assertLessEqual(len(strings[key]), 160, f"{folder}:{key}")
        zh = self.packs["values-zh-rCN"][0]
        en = self.packs["values"][0]
        repo = "https://github.com/ewanyuan/cat-diao"
        self.assertLess(len(zh["conn_not_paired_body"]), 80)
        self.assertIn("Wi-Fi", zh["conn_not_paired_body"])
        self.assertIn("这句话", zh["conn_not_paired_body"])
        self.assertLess(zh["conn_not_paired_body"].index("Wi-Fi"), zh["conn_not_paired_body"].index("这句话"))
        self.assertNotIn("GitHub", zh["conn_not_paired_body"])
        self.assertNotIn("Python", zh["conn_not_paired_body"])
        self.assertIn("same Wi-Fi", en["conn_not_paired_body"])
        self.assertIn("sentence below", en["conn_not_paired_body"])
        self.assertIn("only after", en["conn_code_pending"])
        self.assertIn("just copied", en["conn_waiting_body"])
        shown = "用猫叼连上我这台手机。请从 " + repo + " 安装。"
        pasted = "用猫叼连上我这台手机。请从 " + repo + " 安装（需要 Python 3）。"
        self.assertEqual(zh["conn_ai_line"], shown)
        self.assertEqual(zh["conn_ai_paste"], pasted)
        self.assertEqual(zh["conn_ai_line_with_address"], shown + "手机地址：%1$s")
        self.assertEqual(zh["conn_ai_paste_with_address"], pasted + "手机地址：%1$s")
        self.assertIn("这句话已复制", zh["conn_sentence_copied"])
        self.assertIn("电脑连上后", zh["conn_code_pending"])
        self.assertIn("才会", zh["conn_code_pending"])
        self.assertIn("这句话", zh["conn_waiting_body"])
        paste_keys = {"conn_ai_paste", "conn_ai_paste_with_address"}
        for folder, (strings, _) in self.packs.items():
            body = strings["conn_not_paired_body"]
            self.assertNotIn("GitHub", body, folder)
            self.assertNotIn("github.com", body, folder)
            self.assertNotIn("Python", body, folder)
            for key, value in strings.items():
                if key not in paste_keys:
                    self.assertNotIn("python", value.lower(), f"{folder}:{key}")
            self.assertNotIn("run.ps1", strings["conn_ai_line"], folder)
            self.assertNotIn("%", strings["conn_ai_line"], folder)
            self.assertIn(repo, strings["conn_ai_line"], folder)
            addressed = strings["conn_ai_line_with_address"]
            self.assertIn(repo, addressed, folder)
            self.assertNotIn("Python", addressed, folder)
            self.assertIn("%1$s", addressed, folder)
            self.assertNotIn("run.ps1", addressed, folder)
            self.assertIn(repo, strings["conn_ai_paste"], folder)
            self.assertIn("Python 3", strings["conn_ai_paste"], folder)
            self.assertNotIn("%", strings["conn_ai_paste"], folder)
            pasted_address = strings["conn_ai_paste_with_address"]
            self.assertIn(repo, pasted_address, folder)
            self.assertIn("Python 3", pasted_address, folder)
            self.assertIn("%1$s", pasted_address, folder)
            self.assertNotIn("run.ps1", pasted_address, folder)
        self.assertIn("允许受限设置", zh["perm_restricted_body"])
        self.assertIn("右上角", zh["perm_restricted_body"])
        self.assertEqual(zh["pause_remote"], "暂停遥控")
        self.assertIn("正在等待电脑连接", zh["conn_waiting_title"])
        self.assertIn("%1$d", zh["conn_waiting_body"])
        self.assertNotIn("任选一种", zh["conn_waiting_body"])
        self.assertEqual(zh["home_value_line"], "手机上复制的东西，电脑上直接能用。")
        self.assertEqual(zh["home_connect_link"], "连接电脑")
        self.assertEqual(zh["conn_close"], "退出引导")
        self.assertNotIn("%", zh["conn_advanced"])
        self.assertNotIn("scriptsrun", zh["conn_advanced"])
        self.assertNotEqual(self.packs["values-zh-rCN"][0]["app_name"],
                            self.packs["values-zh-rTW"][0]["app_name"])
        arabic = self.packs["values-ar"][0]["home_empty_body"]
        self.assertTrue(any("\u0600" <= ch <= "\u06ff" for ch in arabic))
        self.assertEqual(self.packs["values-ar"][0]["chevron_forward"], "‹")
        self.assertEqual(self.packs["values"][0]["chevron_forward"], "›")

    def test_alias_folders_match_their_source(self):
        for alias, source in ALIASES.items():
            self.assertEqual((RES / alias / "strings.xml").read_text(encoding="utf-8"),
                             (RES / source / "strings.xml").read_text(encoding="utf-8"), alias)

    def test_locale_config_matches_policy(self):
        root = ET.parse(RES / "xml" / "locales_config.xml").getroot()
        names = [node.attrib["{http://schemas.android.com/apk/res/android}name"] for node in root]
        policy = (JAVA / "com/ewan/wallpaperbridge/LocalePolicy.java").read_text(encoding="utf-8")
        tags = re.findall(r'"(zh-CN|zh-TW|en|ja|ko|es|fr|de|pt-BR|ru|ar|hi|id|vi|th|it|tr|nl|pl|uk)"', policy)
        ordered = []
        for tag in tags:
            if tag not in ordered:
                ordered.append(tag)
        self.assertEqual(names, ordered[:20])

    def test_android_string_escapes_keep_backslashes(self):
        """aapt drops a lone backslash, which turned scripts\\run.ps1 into scriptsrun.ps1."""
        legal = set("\\nt'\"u")
        for folder, (strings, _) in self.packs.items():
            for key, value in strings.items():
                index = 0
                while index < len(value):
                    if value[index] != "\\":
                        index += 1
                        continue
                    self.assertLess(index + 1, len(value), f"dangling slash {folder}:{key}")
                    nxt = value[index + 1]
                    self.assertIn(nxt, legal, f"{folder}:{key} bad escape \\{nxt}")
                    index += 6 if nxt == "u" else 2

    def test_connection_keys_exist(self):
        source = (JAVA / "com/ewan/wallpaperbridge/FirstConnection.java").read_text(encoding="utf-8")
        keys = set(re.findall(r'"(conn_[a-z0-9_]+)"', source))
        self.assertGreater(len(keys), 10)
        for key in keys:
            self.assertIn(key, self.packs["values"][0], key)

    def test_java_ui_literals_are_not_hardcoded_chinese(self):
        literal = re.compile(r'"([^"\\]*(?:\\.[^"\\]*)*)"')
        offenders = []
        for path in JAVA.rglob("*.java"):
            for match in literal.finditer(path.read_text(encoding="utf-8")):
                value = match.group(1)
                if any("\u4e00" <= ch <= "\u9fff" for ch in value) and value not in ALLOWED_CJK_LITERALS:
                    offenders.append(f"{path.name}:{value}")
        self.assertEqual(offenders, [])


if __name__ == "__main__":
    unittest.main()
