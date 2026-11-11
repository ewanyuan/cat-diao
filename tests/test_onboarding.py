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

    def test_discovery_tries_the_saved_phone_address_before_network_probes(self):
        address = "http://10.69.1.253:8767"
        with patch.object(bridge, "config_read", return_value={"address": address}), \
                patch.object(bridge, "_phone_at", return_value=True) as phone_check, \
                patch.object(bridge, "discover_broadcast", side_effect=AssertionError("unexpected UDP")), \
                patch.object(bridge, "discover_mdns", side_effect=AssertionError("unexpected mDNS")):
            found = bridge.discover(deadline=bridge.time.monotonic() + 2)
        self.assertEqual(found, address)
        phone_check.assert_called_once()
        self.assertEqual(phone_check.call_args.args[0], address)

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
        self.assertIn("公共 Wi-Fi", message)
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

    def test_poll_retries_timeout_and_refused_then_saves(self):
        token = "poll-retry-token"
        calls = {"fetch": 0}

        def fetch(address, pair_id, deadline):
            calls["fetch"] += 1
            if calls["fetch"] == 1:
                raise TimeoutError("timed out")
            if calls["fetch"] == 2:
                raise ConnectionRefusedError()
            self.assertEqual(pair_id, "pair-1")
            return "approved"

        def prove(*args):
            raise AssertionError("an approved status does not need a token probe")

        previous = bridge.config_read()
        try:
            output = io.StringIO()
            with redirect_stdout(output):
                bridge.await_pair_approval(
                    "http://127.0.0.1:8767", "pair-1", token, "书房电脑",
                    bridge.time.monotonic() + 5,
                    fetch=fetch, prove=prove, sleep=lambda _seconds: None)
            text = output.getvalue()
            saved = bridge.config_read()
            self.assertEqual(saved.get("token"), token)
            self.assertEqual(saved.get("address"), "http://127.0.0.1:8767")
            self.assertEqual(saved.get("name"), "书房电脑")
            self.assertIn("手机已确认这台电脑", text)
            self.assertNotIn(token, text)
            self.assertGreaterEqual(calls["fetch"], 3)
        finally:
            bridge.config_save(previous)

    def test_missing_status_still_saves_when_the_token_was_accepted(self):
        token = "approved-dh-token"

        def fetch(*args):
            raise bridge.PairGone()

        def prove(address, proved_token, deadline):
            self.assertEqual(proved_token, token)
            self.assertGreater(deadline, bridge.time.monotonic())
            return True

        previous = bridge.config_read()
        try:
            output = io.StringIO()
            with redirect_stdout(output):
                bridge.await_pair_approval(
                    "http://192.168.101.131:8767", "gone-id", token, "书房电脑",
                    bridge.time.monotonic() + 5,
                    fetch=fetch, prove=prove, sleep=lambda _seconds: None)
            self.assertEqual(bridge.config_read().get("token"), token)
            self.assertEqual(bridge.config_read().get("address"), "http://192.168.101.131:8767")
            self.assertIn("手机已确认这台电脑", output.getvalue())
            self.assertNotIn(token, output.getvalue())
        finally:
            bridge.config_save(previous)

    def test_rejected_confirm_does_not_save_or_probe(self):
        previous = bridge.config_read()

        def fetch(*args):
            return "rejected"

        def prove(*args):
            raise AssertionError("a refusal must not be recovered as approval")

        try:
            with self.assertRaises(RuntimeError) as caught:
                bridge.await_pair_approval(
                    "http://127.0.0.1:8767", "pair-1", "unused-token", "书房电脑",
                    bridge.time.monotonic() + 5,
                    fetch=fetch, prove=prove, sleep=lambda _seconds: None)
            self.assertIn("未允许", str(caught.exception))
            self.assertEqual(bridge.config_read().get("token"), previous.get("token"))
        finally:
            bridge.config_save(previous)

    def test_status_blips_until_the_window_ends_are_not_an_immediate_abort(self):
        previous = bridge.config_read()
        probes = {"n": 0}

        def fetch(*args):
            raise TimeoutError("timed out")

        def prove(*args):
            probes["n"] += 1
            return False

        try:
            with self.assertRaises(RuntimeError) as caught:
                bridge.await_pair_approval(
                    "http://127.0.0.1:8767", "pair-1", "unused-token", "书房电脑",
                    bridge.time.monotonic() + 0.05,
                    fetch=fetch, prove=prove, sleep=lambda _seconds: None)
            self.assertIn("确认超时", str(caught.exception))
            self.assertNotIsInstance(caught.exception, TimeoutError)
            self.assertEqual(probes["n"], 1)
            self.assertEqual(bridge.config_read().get("token"), previous.get("token"))
        finally:
            bridge.config_save(previous)

    def test_closed_port_is_the_connection_error_the_poll_retries(self):
        import socket
        sock = socket.socket()
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
        sock.close()
        with patch.object(bridge, "target", return_value=("127.0.0.1", port)):
            with self.assertRaises(ConnectionRefusedError):
                bridge.fetch_pair_state("http://127.0.0.1:8767", "pair-1", bridge.time.monotonic() + 2)

    def test_pair_saves_after_dropped_status_polls(self):
        self._pair_against(self._DropThenApprove)

    def test_pair_saves_when_status_is_gone_but_heartbeat_accepts_the_token(self):
        self._pair_against(self._GoneButApproved)

    def _pair_against(self, handler):
        previous = bridge.config_read()
        server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            output = io.StringIO()
            with patch.object(bridge, "discover", return_value="http://127.0.0.1:8767"), \
                    patch.object(bridge, "target", return_value=server.server_address), \
                    redirect_stdout(output):
                bridge.pair("", "书房电脑", 2)
            text = output.getvalue()
            saved = bridge.config_read()
            self.assertEqual(saved.get("token"), handler.seen["token"])
            self.assertEqual(saved.get("address"), "http://127.0.0.1:8767")
            self.assertIn(handler.seen["code"], text)
            self.assertIn("手机已确认这台电脑", text)
            self.assertNotIn(handler.seen["token"], text)
        finally:
            bridge.config_save(previous)
            server.shutdown()
            server.server_close()

    class _PairPhone(BaseHTTPRequestHandler):
        seen = {}

        def do_GET(self):
            if self.path == "/hello":
                self._send(200, {"name": "猫叼", "model": "Test", "protocol": "dh-2048-v1"})
                return
            self.handle_get()

        def handle_get(self):
            self._send(200, {"state": "approved"})

        def do_POST(self):
            length = int(self.headers.get("Content-Length", "0"))
            incoming = json.loads(self.rfile.read(length).decode("utf-8"))
            token, code = protocol.derive(self.private_hex, incoming["public_key"])
            type(self).seen = {"token": token, "code": code}
            self._send(202, {"id": "pair-1", "state": "pending", "public_key": self.public_hex})

        def _send(self, status, payload, token=None, nonce=""):
            body = json.dumps(payload).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            if token and nonce:
                self.send_header("X-CatDiao-Sig", protocol.sign_response(token, nonce, status, body))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *args):
            pass

    class _DropThenApprove(_PairPhone):
        private_hex, public_hex = protocol.generate_keypair()
        polls = {"n": 0}

        def handle_get(self):
            if not self.path.startswith("/pair-status"):
                self._send(404, {"error": "no"})
                return
            type(self).polls["n"] += 1
            if type(self).polls["n"] < 3:
                self.close_connection = True
                return
            self._send(200, {"state": "approved"})

    class _GoneButApproved(_PairPhone):
        private_hex, public_hex = protocol.generate_keypair()

        def handle_get(self):
            if self.path.startswith("/pair-status"):
                self._send(404, {"error": "请求已失效"})
                return
            if self.path == "/heartbeat":
                token = type(self).seen.get("token", "")
                verdict = protocol.verify_request("GET", "/heartbeat", token, b"", self.headers)
                status = 200 if verdict.ok else 401
                self._send(status, {"connected": verdict.ok}, token if verdict.ok else None, verdict.nonce)
                return
            self._send(404, {"error": "no"})

    def test_skill_points_at_one_command_and_keeps_the_wording_rule(self):
        text = (ROOT / "cat-diao" / "SKILL.md").read_text(encoding="utf-8")
        shown = "用猫叼连上我这台手机。请从 https://github.com/ewanyuan/cat-diao 安装。"
        pasted = "用猫叼连上我这台手机。请从 https://github.com/ewanyuan/cat-diao 安装（需要 Python 3）。"
        self.assertIn("run.ps1 pair", text)
        self.assertIn(shown, text)
        self.assertIn(pasted, text)
        self.assertIn("不写 Python 3", text)
        self.assertIn("先让手机和电脑连同一个 Wi-Fi", text)
        self.assertIn("电脑连上后", text)
        self.assertIn("二维码", text)
        self.assertNotIn("让它从 GitHub 装好猫叼（需要 Python 3）", text)
        self.assertNotIn(r"请运行 cat-diao 的 scripts\run.ps1 pair；找不到时运行 run.ps1 pair --address http://IP:8767（IP 换成手机显示的地址）。", text)
        self.assertIn("看画面、点画面的真机验证", text)
        self.assertIn("不替代 ADB", text)
        self.assertNotIn("phone pair --wait 60", text)
        self.assertLess(text.index("用猫叼连上我这台手机"), text.index("run.ps1 pair --address"))
        guide = (ROOT / "docs" / "first-connection.md").read_text(encoding="utf-8")
        readme = (ROOT / "README.md").read_text(encoding="utf-8")
        for name, body in (("skill", text), ("guide", guide), ("readme", readme)):
            self.assertIn("先让手机和电脑连同一个 Wi-Fi", body, name)
            self.assertIn("不写 Python 3", body, name)
            self.assertNotIn("让它从 GitHub 装好猫叼（需要 Python 3）", body, name)
        self.assertIn(shown, guide)
        self.assertIn(pasted, guide)
        self.assertIn("https://github.com/ewanyuan/cat-diao", readme)
        script = (ROOT / "cat-diao" / "scripts" / "run.ps1").read_bytes()
        self.assertTrue(script.startswith(b"\xef\xbb\xbf"))
        commands = script.decode("utf-8-sig")
        self.assertIn("pair @pairArgs", commands)
        self.assertIn("'--wait', '60'", commands)


if __name__ == "__main__":
    unittest.main()
