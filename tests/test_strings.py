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
                self.assertLessEqual(len(strings[key]), 120, f"{folder}:{key}")
        zh = self.packs["values-zh-rCN"][0]
        self.assertLess(len(zh["conn_not_paired_body"]), 40)
        self.assertIn("允许受限设置", zh["perm_restricted_body"])
        self.assertIn("右上角", zh["perm_restricted_body"])
        self.assertEqual(zh["pause_remote"], "暂停遥控")
        self.assertIn("任选一种", zh["conn_waiting_body"])
        self.assertIn("允许", zh["conn_advanced"])
        self.assertIn("%1$s", zh["conn_advanced"])
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
