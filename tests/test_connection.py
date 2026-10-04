import hashlib
import hmac
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
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "ewan-android-phone" / "scripts"))
bridge = importlib.import_module("手机直达")
collector = importlib.import_module("收藏同步")
TOKEN = "test-pair-secret"


class ConnectionChecks(unittest.TestCase):
    def setUp(self):
        bridge.config_save({"token": TOKEN, "address": "http://127.0.0.1:8767", "name": "Test PC"})

    def test_receiver_requires_current_pairing_proof(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), collector.Receiver)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            challenge = "1234567890abcdef" * 2
            proof = hmac.new(TOKEN.encode(), ("request:" + challenge).encode(), hashlib.sha256).hexdigest()
            connection = http.client.HTTPConnection(*server.server_address, timeout=2)
            connection.request("GET", "/connection-check?challenge=" + challenge,
                               headers={"X-CatDiao-Auth": proof})
            reply = connection.getresponse()
            self.assertEqual(reply.status, 200)
            self.assertEqual(reply.getheader("X-CatDiao-Proof"), hmac.new(
                TOKEN.encode(), ("response:" + challenge).encode(), hashlib.sha256).hexdigest())
            self.assertTrue(json.loads(reply.read())["connected"])
            connection.close()
            connection = http.client.HTTPConnection(*server.server_address, timeout=2)
            connection.request("GET", "/connection-check?challenge=" + challenge,
                               headers={"X-CatDiao-Auth": "0" * 64})
            reply = connection.getresponse()
            self.assertEqual(reply.status, 403)
            self.assertIsNone(reply.getheader("X-CatDiao-Proof"))
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
                    value = {"name": "猫叼"}
                else:
                    value = {"connected": self.headers.get("X-Phone-Token") == TOKEN}
                body = json.dumps(value).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

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
                code = 200 if self.headers.get("X-Phone-Token") == TOKEN else 401
                body = json.dumps({"connected": code == 200}).encode()
                self.send_response(code)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

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
