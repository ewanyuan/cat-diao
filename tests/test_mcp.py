"""MCP wording and tool calls. No phone is contacted."""
import importlib
import io
import json
import os
from pathlib import Path
import sys
import unittest

os.environ.setdefault("CATDIAO_NEST_HOME", "/tmp/catdiao-mcp-tests")
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "cat-diao" / "scripts"))
mcp = importlib.import_module("mcp_server")


class FakePhone:
    def __init__(self):
        self.calls = []

    def screenshot(self):
        self.calls.append("screenshot")
        return b"\xff\xd8fake-jpeg"

    def tap(self, x, y):
        self.calls.append(("tap", x, y))
        return {"ok": True}

    def swipe(self, x1, y1, x2, y2, duration_ms):
        self.calls.append("swipe")
        return {"ok": True}

    def press(self, name):
        self.calls.append(("press", name))
        return {"ok": True}

    def open_app(self, package):
        return {"package": package}

    def send_file(self, filename):
        return {"saved": True, "note": "首次安装需要用户在手机上点一次确认", "file": filename}

    def wallpaper(self, filename):
        return {"updated": True}

    def status(self):
        return {"model": "Test"}


def frame(payload):
    raw = json.dumps(payload).encode("utf-8")
    return f"Content-Length: {len(raw)}\r\n\r\n".encode("ascii") + raw


def read_frame(stream):
    header = b""
    while b"\r\n\r\n" not in header:
        header += stream.read(1)
    length = int(header.decode("ascii").split(":", 1)[1].strip())
    return json.loads(stream.read(length).decode("utf-8"))


class McpChecks(unittest.TestCase):
    def test_instructions_describe_real_device_checks_and_not_adb(self):
        incoming = io.BytesIO(frame({
            "jsonrpc": "2.0", "id": 1, "method": "initialize",
            "params": {"protocolVersion": "2024-11-05", "capabilities": {},
                       "clientInfo": {"name": "test", "version": "0"}},
        }))
        outgoing = io.BytesIO()
        message = mcp.read_message(incoming)
        mcp.write_message(outgoing, mcp.handle(message, FakePhone()))
        outgoing.seek(0)
        reply = read_frame(outgoing)
        text = reply["result"]["instructions"]
        self.assertIn("看画面、点画面的真机验证", text)
        self.assertIn("不替代 ADB", text)
        self.assertIn("点一次", text)
        lowered = text.lower()
        self.assertNotIn("可以替代 adb", lowered)
        self.assertNotIn("replaces adb", lowered)
        self.assertEqual(reply["result"]["serverInfo"]["name"], "cat-diao")
        self.assertEqual(reply["result"]["serverInfo"]["version"], "2.0")

    def test_tools_include_screenshot_and_tap(self):
        names = [item["name"] for item in mcp.tool_definitions()]
        self.assertIn("phone_screenshot", names)
        self.assertIn("phone_tap", names)
        self.assertNotIn("list_new_captures", names)
        phone = FakePhone()
        listed = mcp.handle({"jsonrpc": "2.0", "id": 2, "method": "tools/list"}, phone)
        self.assertIn("phone_screenshot", json.dumps(listed, ensure_ascii=False))
        image = mcp.handle({"jsonrpc": "2.0", "id": 3, "method": "tools/call",
                             "params": {"name": "phone_screenshot", "arguments": {}}}, phone)
        self.assertEqual(image["result"]["content"][0]["type"], "image")
        tapped = mcp.handle({"jsonrpc": "2.0", "id": 4, "method": "tools/call",
                              "params": {"name": "phone_tap", "arguments": {"x": 12, "y": 34}}}, phone)
        self.assertEqual(phone.calls[1], ("tap", 12, 34))
        self.assertIn("ok", tapped["result"]["content"][0]["text"])
        sent = mcp.handle({"jsonrpc": "2.0", "id": 5, "method": "tools/call",
                            "params": {"name": "phone_send", "arguments": {"file": "a.apk"}}}, phone)
        self.assertIn("点一次确认", sent["result"]["content"][0]["text"])


if __name__ == "__main__":
    unittest.main()
