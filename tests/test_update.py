import importlib
import io
from pathlib import Path
import sys
import unittest
from contextlib import redirect_stdout
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "cat-diao" / "scripts"))
bridge = importlib.import_module("手机直达")


class UpdateChecks(unittest.TestCase):
    def run_update(self, sent, install=None):
        calls = []

        def fake_request(address, method, path, token=None, body=None, **kw):
            calls.append(path)
            if isinstance(install, Exception):
                raise install
            return install or {}

        out = io.StringIO()
        with mock.patch.object(bridge, "send_file", return_value=sent), \
                mock.patch.object(bridge, "request", side_effect=fake_request), redirect_stdout(out):
            bridge.update_app("http://x", "t", "a.apk")
        return calls, out.getvalue()

    def test_new_phone_installs_by_itself(self):
        calls, _ = self.run_update({"saved": "a.apk", "self_update": "installer_opened"})
        self.assertEqual(calls, [])

    def test_old_phone_is_asked_to_open_installer(self):
        calls, text = self.run_update({"saved": "a.apk"}, {"state": "opened"})
        self.assertEqual(calls, ["/install"])
        self.assertIn("安装", text)

    def test_very_old_phone_gets_one_plain_step(self):
        _, text = self.run_update({"saved": "a.apk"}, RuntimeError("404"))
        self.assertIn("文件管理", text)
        self.assertLess(len(text.strip().splitlines()), 3)

    def test_every_note_is_one_short_sentence(self):
        for note in bridge.UPDATE_NOTES.values():
            self.assertLess(len(note), 45)


if __name__ == "__main__":
    unittest.main()
