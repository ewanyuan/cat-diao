"""First-connection discovery and the words a new user actually sees."""

import importlib
import io
import json
import os
import struct
import sys
import threading
from contextlib import redirect_stdout
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch
import unittest


ROOT = Path(__file__).resolve().parents[1]
os.environ.setdefault("CATDIAO_NEST_HOME", "/tmp/catdiao-onboarding-tests")
sys.path.insert(0, str(ROOT / "cat-diao" / "scripts"))
bridge = importlib.import_module("手机直达")
protocol = importlib.import_module("catdiao_protocol")


def compressed_mdns_reply():
    """SRV plus A record. The A name is a pointer into the SRV target."""
    header = struct.pack(">HHHHHH", 0, 0x8400, 0, 1, 0, 2)
    owner = bridge.dns_name("catdiao._catdiao._tcp.local")
    target = b"\x07catdiao\xc0\x22"
    srv = struct.pack(">HHH", 0, 0, 8767) + target
    packet = header + owner + struct.pack(">HHIH", 33, 1, 120, len(srv)) + srv
    target_at = 12 + len(owner) + 10 + 6
    packet += struct.pack(">H", 0xC000 | target_at)
    packet += struct.pack(">HHIH", 1, 1, 120, 4) + bytes((10, 69, 1, 253))
    return packet


class OnboardingChecks(unittest.TestCase):
    def test_compressed_mdns_reply_yields_phone_address(self):
        packet = compressed_mdns_reply()
        self.assertEqual(bridge.mdns_addresses(packet), ["http://10.69.1.253:8767"])

    def test_mdns_hit_is_accepted_when_broadcast_is_quiet(self):
        calls = []

        class Phone(BaseHTTPRequestHandler):
            def do_GET(self):
                calls.append(self.path.split("?", 1)[0])
                token = "test-pair-secret"
                if self.path == "/hello":
                    payload, status = {"name": "猫叼"}, 200
                else:
                    verdict = protocol.verify_request("GET", self.path, token, b"", self.headers)
                    payload, status = {"connected": verdict.ok, "model": "Test"}, (200 if verdict.ok else 401)
                body = json.dumps(payload).encode("utf-8")
                nonce = self.headers.get("X-CatDiao-Nonce") or ""
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                if nonce:
                    self.send_header("X-CatDiao-Sig", protocol.sign_response(token, nonce, status, body))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Phone)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            with patch.object(bridge, "discover_broadcast", return_value=[]), \
                    patch.object(bridge, "discover_mdns", return_value=["http://127.0.0.1:8767"]), \
                    patch.object(bridge, "target", return_value=server.server_address):
                found = bridge.discover("test-pair-secret", deadline=bridge.time.monotonic() + 2)
            self.assertEqual(found, "http://127.0.0.1:8767")
            self.assertEqual(calls, ["/hello", "/heartbeat"])
        finally:
            server.shutdown()
            server.server_close()

    def test_mdns_socket_failure_stays_empty(self):
        with patch.object(bridge.socket, "socket", side_effect=OSError("no multicast")):
            self.assertEqual(bridge.discover_mdns(bridge.time.monotonic() + 0.2), [])

    def test_placeholder_search_explains_the_miss(self):
        with patch.object(bridge, "discover", return_value=None):
            with self.assertRaises(RuntimeError) as caught:
                bridge.pair("http://127.0.0.1:8767", "书房电脑", 0.05)
        message = str(caught.exception)
        self.assertIn("没有找到手机", message)
        self.assertIn("热点", message)
        self.assertIn("公用", message)
        self.assertIn("run.ps1 pair --address", message)

    def test_pair_search_prints_the_code_without_a_wifi_lecture(self):
        private_hex, public_hex = protocol.generate_keypair()
        seen = {}

        class Phone(BaseHTTPRequestHandler):
            def do_GET(self):
                if self.path == "/hello":
                    payload = {"name": "猫叼", "model": "Test", "protocol": "dh-2048-v1"}
                else:
                    payload = {"state": "approved"}
                body = json.dumps(payload).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_POST(self):
                length = int(self.headers.get("Content-Length", "0"))
                incoming = json.loads(self.rfile.read(length).decode("utf-8"))
                token, code = protocol.derive(private_hex, incoming["public_key"])
                seen["code"] = code
                seen["token"] = token
                body = json.dumps({
                    "id": "pair-1", "state": "pending", "public_key": public_hex,
                }).encode("utf-8")
                self.send_response(202)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Phone)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        previous = bridge.config_read()
        try:
            output = io.StringIO()
            with patch.object(bridge, "discover", return_value="http://127.0.0.1:8767"), \
                    patch.object(bridge, "target", return_value=server.server_address), \
                    redirect_stdout(output):
                bridge.pair("", "书房电脑", 2)
            text = output.getvalue()
            self.assertIn(seen["code"], text)
            self.assertIn("允许", text)
            self.assertIn("拒绝", text)
            self.assertNotIn("禁止设备互访", text)
            saved = bridge.config_read()
            self.assertEqual(saved.get("token"), seen["token"])
            self.assertEqual(saved.get("address"), "http://127.0.0.1:8767")
        finally:
            bridge.config_save(previous)
            server.shutdown()
            server.server_close()

    def test_skill_points_at_one_command_and_keeps_the_wording_rule(self):
        text = (ROOT / "cat-diao" / "SKILL.md").read_text(encoding="utf-8")
        self.assertIn("run.ps1 pair", text)
        self.assertIn(r"请运行 cat-diao 的 scripts\run.ps1 pair，它会自己找到这台手机。", text)
        self.assertIn("看画面、点画面的真机验证", text)
        self.assertIn("不替代 ADB", text)
        self.assertNotIn("phone pair --wait 60", text)
        self.assertLess(text.index("run.ps1 pair"), text.index("--address"))
        script = (ROOT / "cat-diao" / "scripts" / "run.ps1").read_bytes()
        self.assertTrue(script.startswith(b"\xef\xbb\xbf"))
        commands = script.decode("utf-8-sig")
        self.assertIn("pair @pairArgs", commands)
        self.assertIn("'--wait', '60'", commands)


if __name__ == "__main__":
    unittest.main()
