import http.client
import importlib
import json
import os
from pathlib import Path
import socket
import sys
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch
from types import SimpleNamespace


TASK_HOME = tempfile.TemporaryDirectory(prefix="catdiao-tests-")
os.environ["CATDIAO_NEST_HOME"] = TASK_HOME.name
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "cat-diao" / "scripts"))
bridge = importlib.import_module("手机直达")
collector = importlib.import_module("收藏同步")
protocol = importlib.import_module("catdiao_protocol")
TOKEN = "test-pair-secret"


def signed_headers(method, path, body=b"", token=TOKEN):
    headers = protocol.sign_headers(method, path, token, body)
    if body:
        headers["Content-Type"] = "application/json"
    return headers


def write_signed(handler, token, status, payload):
    body = json.dumps(payload).encode("utf-8")
    nonce = handler.headers.get("X-CatDiao-Nonce") or ""
    handler.send_response(status)
    handler.send_header("Content-Type", "application/json")
    handler.send_header("Content-Length", str(len(body)))
    if nonce:
        handler.send_header("X-CatDiao-Sig", protocol.sign_response(token, nonce, status, body))
    handler.end_headers()
    handler.wfile.write(body)


class ConnectionChecks(unittest.TestCase):
    def setUp(self):
        bridge.config_save({"token": TOKEN, "address": "http://127.0.0.1:8767", "name": "Test PC"})

    def test_receiver_requires_current_pairing_proof(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), collector.Receiver)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            connection = http.client.HTTPConnection(*server.server_address, timeout=2)
            headers = signed_headers("GET", "/connection-check")
            connection.request("GET", "/connection-check", headers=headers)
            reply = connection.getresponse()
            raw = reply.read()
            self.assertEqual(reply.status, 200)
            self.assertTrue(protocol.verify_response(
                TOKEN, headers["X-CatDiao-Nonce"], reply.status, raw, reply.getheader("X-CatDiao-Sig") or ""))
            self.assertTrue(json.loads(raw)["connected"])
            connection.close()
            connection = http.client.HTTPConnection(*server.server_address, timeout=2)
            bad = signed_headers("GET", "/connection-check")
            bad["X-CatDiao-Sig"] = "0" * 64
            connection.request("GET", "/connection-check", headers=bad)
            reply = connection.getresponse()
            self.assertEqual(reply.status, 403)
            self.assertIsNone(reply.getheader("X-CatDiao-Sig"))
            reply.read()
            connection.close()
        finally:
            server.shutdown()
            server.server_close()

    def test_slow_http_body_cannot_extend_absolute_deadline(self):
        class SlowBody(BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(200)
                self.send_header("Content-Length", "100")
                self.end_headers()
                try:
                    for _ in range(100):
                        self.wfile.write(b" ")
                        self.wfile.flush()
                        time.sleep(0.03)
                except (OSError, BrokenPipeError):
                    pass

            def log_message(self, *args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), SlowBody)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        started = time.monotonic()
        try:
            with patch.object(bridge, "target", return_value=server.server_address):
                with self.assertRaises((OSError, http.client.HTTPException, ValueError)):
                    bridge.request("test", "GET", "/", deadline=started + 0.35)
            self.assertLess(time.monotonic() - started, 0.8)
        finally:
            server.shutdown()
            server.server_close()

    def test_discovery_interfaces_share_one_deadline(self):
        interfaces = [("127.0.0.1", "255.0.0.0")] * 6
        started = time.monotonic()
        with patch.object(bridge, "local_interfaces", return_value=interfaces):
            self.assertIsNone(bridge.discover(TOKEN, deadline=started + 0.35))
        self.assertLess(time.monotonic() - started, 0.8)

    def test_discovery_verifies_reply_with_many_interfaces(self):
        calls = []

        class Phone(BaseHTTPRequestHandler):
            def do_GET(self):
                calls.append(self.path)
                if self.path == "/hello":
                    write_signed(self, TOKEN, 200, {"name": "猫叼"})
                    return
                verdict = protocol.verify_request("GET", self.path, TOKEN, b"", self.headers)
                write_signed(self, TOKEN, 200 if verdict.ok else 401,
                             {"connected": verdict.ok, "model": "Test"})

            def log_message(self, *args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Phone)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        responder = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        responder.bind(("127.0.0.1", 0))
        responder.settimeout(0.1)
        stopped = threading.Event()

        def answer():
            while not stopped.is_set():
                try:
                    payload, peer = responder.recvfrom(128)
                    if payload == b"PHONE_BRIDGE_DISCOVER_V1":
                        responder.sendto(b"PHONE_BRIDGE_V1:8767", peer)
                except socket.timeout:
                    continue
                except OSError:
                    return

        threading.Thread(target=answer, daemon=True).start()
        started = time.monotonic()
        try:
            with patch.object(bridge, "local_interfaces", return_value=[("127.0.0.1", "255.0.0.0")] * 8), \
                    patch.object(bridge.ipaddress, "IPv4Network",
                                 return_value=SimpleNamespace(broadcast_address="127.0.0.1")), \
                    patch.object(bridge, "DISCOVERY_PORT", responder.getsockname()[1]), \
                    patch.object(bridge, "target", return_value=server.server_address):
                self.assertEqual(bridge.discover(TOKEN, deadline=started + 2), "http://127.0.0.1:8767")
            self.assertEqual(calls, ["/hello", "/heartbeat"])
            self.assertLess(time.monotonic() - started, 2)
        finally:
            stopped.set()
            responder.close()
            server.shutdown()
            server.server_close()

    def test_actual_phone_reply_is_recorded(self):
        class Phone(BaseHTTPRequestHandler):
            def do_GET(self):
                path = self.path.split("?", 1)[0]
                verdict = protocol.verify_request("GET", path, TOKEN, b"", self.headers)
                status = 200 if verdict.ok else 401
                write_signed(self, TOKEN, status, {"connected": verdict.ok, "model": "Test"})

            def log_message(self, *args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Phone)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            with patch.object(bridge, "target", return_value=server.server_address):
                collector.maintain_phone_connection()
            result = collector.phone_connection_state()
            self.assertEqual(result["state"], "connected")
            self.assertGreaterEqual(result["finished_at"], result["started_at"])
            self.assertLess(result["elapsed_ms"], 1000)
        finally:
            server.shutdown()
            server.server_close()


if __name__ == "__main__":
    try:
        unittest.main()
    finally:
        TASK_HOME.cleanup()
