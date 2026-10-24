"""Real-stream transport checks; all data remains in temporary test folders."""
import importlib
import json
import os
from pathlib import Path
import socket
import struct
import sys
import tempfile
import threading
import time
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "ewan-android-phone/scripts"))
import bluetooth_link as bt
import bluetooth_windows as native

TOKEN = "paired-test-secret"


def phone(channel, bad_proof=False, replay=False):
    with channel:
        hello = bt.read_frame(channel)
        nonce = "ab" * 16
        addresses = ["10.69.1.253"]
        bt.send_frame(channel, {"nonce": nonce, "addresses": addresses,
            "proof": "0" * 64 if bad_proof else bt.proof(TOKEN,
                "welcome:" + hello["nonce"] + ":" + nonce + ":" + ",".join(addresses))})
        if bad_proof:
            return
        key = bt.proof(TOKEN, "session:" + hello["nonce"] + ":" + nonce)
        request = bt.read_frame(channel)
        sequence = request["sequence"] - (1 if replay else 0)
        payload = json.dumps({"items": [], "addresses": addresses}, ensure_ascii=False)
        bt.send_frame(channel, {"sequence": sequence, "payload": payload,
                               "proof": bt.proof(key, f"phone:{sequence}:" + payload)})


class BluetoothChecks(unittest.TestCase):
    def test_virtual_proxy_addresses_match_android_lan_rules(self):
        self.assertEqual(bt.valid_addresses(["198.18.0.1", "192.168.101.46", "10.8.0.2", "172.18.144.1", "127.0.0.1", "169.254.1.2"]),
                         ["192.168.101.46", "10.8.0.2", "172.18.144.1"])
    def test_authenticated_address_recovery_and_tick(self):
        a, b = socket.socketpair()
        a.settimeout(2)
        thread = threading.Thread(target=phone, args=(b,), daemon=True)
        thread.start()
        with a:
            session = bt.Session(a, TOKEN, ["10.69.13.7"])
            self.assertEqual(session.addresses, ["10.69.1.253"])
            self.assertEqual(session.exchange({"accepted": [], "addresses": ["10.69.13.7"]})["items"], [])
        thread.join(2)

    def test_other_pair_and_replayed_receipt_rejected(self):
        for bad_proof, replay in ((True, False), (False, True)):
            a, b = socket.socketpair()
            a.settimeout(2)
            thread = threading.Thread(target=phone, args=(b, bad_proof, replay), daemon=True)
            thread.start()
            with a, self.assertRaises(ValueError):
                session = bt.Session(a, TOKEN, ["10.69.13.7"])
                session.exchange({"accepted": [], "addresses": ["10.69.13.7"]})
            thread.join(2)

    def test_unicode_frame_survives_fragmentation(self):
        a, b = socket.socketpair()
        raw = json.dumps({"text": "链接与长文字" * 1000}, ensure_ascii=False).encode()
        packet = struct.pack("!I", len(raw)) + raw
        def write():
            with b:
                for offset in range(0, len(packet), 11):
                    b.sendall(packet[offset:offset + 11])
        thread = threading.Thread(target=write, daemon=True)
        thread.start()
        with a:
            a.settimeout(2)
            self.assertEqual(bt.read_frame(a)["text"], "链接与长文字" * 1000)
        thread.join(2)

    def test_truncated_or_excessive_frames_fail(self):
        for packet in (struct.pack("!I", 10) + b"{}", struct.pack("!I", bt.MAX_FRAME + 1)):
            a, b = socket.socketpair()
            b.sendall(packet)
            b.close()
            with a, self.assertRaises((ValueError, EOFError)):
                bt.read_frame(a)

    def test_slow_peer_cannot_extend_absolute_deadline(self):
        a, b = socket.socketpair()
        def drip():
            try:
                with b:
                    b.sendall(struct.pack("!I", 100))
                    for _ in range(100):
                        b.sendall(b" ")
                        time.sleep(0.02)
            except OSError:
                pass
        thread = threading.Thread(target=drip, daemon=True)
        thread.start()
        started = time.monotonic()
        with a, self.assertRaises((OSError, EOFError)):
            with bt.frame_deadline(a, .15):
                bt.read_frame(a)
        self.assertLess(time.monotonic() - started, .6)
        thread.join(2)

    def test_wire_matches_java_fixture(self):
        self.assertEqual(bt.proof("key", "phone:1:收藏"),
                         "ecfb35e9c371b1c9d129799e5ef9f1c4cc8ee96a21b5d448e657e141b9c138cc")

    def test_windows_native_structures(self):
        if sys.platform != "win32": self.skipTest("Windows ABI")
        import ctypes
        self.assertEqual(ctypes.sizeof(native.BluetoothAddress), 30)
        self.assertEqual(ctypes.sizeof(native.DeviceInfo), 560)
        self.assertEqual(native.SearchParams.radio.offset, 32 if ctypes.sizeof(ctypes.c_void_p) == 8 else 28)

    def test_reconnect_receipt_retry_is_idempotent(self):
        collector = importlib.import_module("收藏同步")
        with tempfile.TemporaryDirectory() as directory:
            from unittest.mock import patch
            item = {"id": "bluetooth-retry-test", "shared_text": "测试收藏", "url": "", "source": "test",
                    "created_at": int(time.time() * 1000)}
            with patch.object(collector, "DATA", Path(directory)), patch.object(collector, "DB", Path(directory) / "captures.sqlite3"):
                self.assertEqual(collector.receive([item]), [item["id"]])
                self.assertEqual(collector.receive([item]), [item["id"]])
                with collector.managed_database() as db:
                    self.assertEqual(db.execute("SELECT count(*) FROM captures").fetchone()[0], 1)

    def test_receipt_is_sent_only_after_durable_save(self):
        from types import SimpleNamespace
        from unittest.mock import patch
        from contextlib import contextmanager
        item = {"id": "durable-id", "shared_text": "蓝牙收藏", "created_at": int(time.time() * 1000)}
        settings = {"token": TOKEN}
        @contextmanager
        def writer(): yield time.monotonic() + 2
        bridge = SimpleNamespace(config_read=lambda: dict(settings), config_writer=writer,
                                 save_config_locked=lambda value, _: settings.update(value))
        received = []
        def receive(items):
            received.extend(items)
            return [value["id"] for value in items]
        receiver = bt.BluetoothReceiver(bridge, receive)
        receiver.addresses = lambda: ["10.69.13.7"]
        receiver.recover_address = lambda *_: None
        a, b = socket.socketpair()
        a.settimeout(2); b.settimeout(2)
        acknowledgements = []
        def peer():
            with b:
                hello = bt.read_frame(b)
                nonce = "cd" * 16
                bt.send_frame(b, {"nonce": nonce, "addresses": ["10.69.1.253"],
                    "proof": bt.proof(TOKEN, "welcome:" + hello["nonce"] + ":" + nonce + ":10.69.1.253")})
                key = bt.proof(TOKEN, "session:" + hello["nonce"] + ":" + nonce)
                for index in range(2):
                    request = bt.read_frame(b)
                    body = json.loads(request["payload"])
                    acknowledgements.append(body["accepted"])
                    if index == 1:
                        self.assertEqual(received, [item])
                        receiver.stop.set()
                    payload = json.dumps({"addresses": ["10.69.1.253"], "items": [item] if index == 0 else []})
                    sequence = request["sequence"]
                    bt.send_frame(b, {"sequence": sequence, "payload": payload,
                        "proof": bt.proof(key, f"phone:{sequence}:" + payload)})
        thread = threading.Thread(target=peer, daemon=True); thread.start()
        with a:
            receiver.serve(a, {"token": TOKEN}, {"address": "123456789ABC", "name": "Test Phone"})
        thread.join(2)
        self.assertEqual(acknowledgements, [[], ["durable-id"]])
        self.assertEqual(settings["bluetooth_address"], "123456789ABC")

    def test_transient_ledger_error_does_not_end_reconnection(self):
        from types import SimpleNamespace
        from unittest.mock import patch
        from contextlib import nullcontext
        import sqlite3
        bridge = SimpleNamespace(config_read=lambda: {"token": TOKEN})
        receiver = bt.BluetoothReceiver(bridge, lambda _: [])
        attempts = []
        def devices(_):
            if len(attempts) >= 1: receiver.stop.set()
            return [{"address": "123456789ABC", "name": "Phone"}]
        def serve(*_):
            attempts.append(1)
            raise sqlite3.OperationalError("temporary test lock")
        receiver.serve = serve
        with patch.object(native, "paired_phones", side_effect=devices), \
             patch.object(native, "connect", return_value=nullcontext()), \
             patch.object(receiver.stop, "wait", return_value=False):
            receiver.run()
        self.assertEqual(len(attempts), 1)
        self.assertTrue(receiver.stop.is_set(), "worker must reach the next device lookup")

if __name__ == "__main__": unittest.main()
