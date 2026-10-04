from contextlib import ExitStack, contextmanager, redirect_stdout
import http.client
import io
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

from test_connection import TASK_HOME, TOKEN, bridge, collector


@contextmanager
def serving(handler, server_type=collector.ReceiverServer):
    server = server_type(("127.0.0.1", 0), handler)
    thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
    thread.start()
    try:
        yield server
    finally:
        server.shutdown()
        server.server_close()
        thread.join(2)


def post_capture(server, identifier):
    connection = http.client.HTTPConnection(*server.server_address, timeout=2)
    try:
        body = json.dumps({"items": [{"id": identifier, "created_at": int(time.time() * 1000),
                                      "source": "test", "shared_text": "saved content", "url": ""}]}).encode()
        connection.request("POST", "/captures", body,
                           {"X-Phone-Token": TOKEN, "Content-Type": "application/json"})
        reply = connection.getresponse()
        return reply.status, json.loads(reply.read())
    finally:
        connection.close()


class EdgeChecks(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="edge-", dir=TASK_HOME.name)
        self.addCleanup(self.directory.cleanup)
        self.home = Path(self.directory.name)
        self.context = ExitStack()
        self.addCleanup(self.context.close)
        config = self.home / "connection.json"
        for module, field, value in [(bridge, "CONFIG", config), (collector, "PAIRING", config),
                                      (collector, "DATA", self.home / "ledger"),
                                      (collector, "DB", self.home / "ledger" / "captures.sqlite3"),
                                      (collector, "SETTINGS", self.home / "ledger" / "settings.json")]:
            self.context.enter_context(patch.object(module, field, value))
        bridge.config_save({"token": TOKEN, "address": "http://127.0.0.1:8767", "name": "Test PC"})

    def test_windows_setup_scripts_keep_utf8_bom_for_chinese_paths(self):
        repository = Path(__file__).resolve().parents[1]
        for relative in ("ewan-android-phone/scripts/setup.ps1", "tests/run.ps1"):
            content = (repository / relative).read_bytes()
            self.assertTrue(content.startswith(b"\xef\xbb\xbf"), relative)
            content.decode("utf-8-sig")

    def test_atomic_config_failure_keeps_previous_pairing(self):
        before = bridge.CONFIG.read_bytes()
        with patch.object(bridge.os, "replace", side_effect=OSError("simulated interrupted write")):
            with self.assertRaises(OSError):
                bridge.config_save({"token": "new-pair", "address": "http://127.0.0.2:8767"})
        self.assertEqual(bridge.CONFIG.read_bytes(), before)
        self.assertEqual(list(self.home.glob("connection.json.*.tmp")), [])

    def test_stale_discovery_cannot_overwrite_new_pairing(self):
        bridge.config_save({"token": "new-pair", "address": "http://127.0.0.2:8767", "name": "New PC"})
        self.assertFalse(bridge.update_pair_address(TOKEN, "http://127.0.0.3:8767"))
        self.assertEqual(bridge.config_read(), {"token": "new-pair", "address": "http://127.0.0.2:8767",
                                               "name": "New PC"})
        self.assertTrue(bridge.update_pair_address("new-pair", "http://127.0.0.4:8767"))
        self.assertEqual(bridge.config_read()["token"], "new-pair")

    def test_concurrent_config_reads_always_see_complete_json(self):
        stopped = threading.Event()
        failures = []
        reads = []

        def read_repeatedly():
            while not stopped.is_set():
                try:
                    value = bridge.config_read()
                    self.assertEqual(value["token"], TOKEN)
                    self.assertTrue(value["address"].startswith("http://127.0.0."))
                    reads.append(True)
                except Exception as error:
                    failures.append(error)
                time.sleep(0.001)

        reader = threading.Thread(target=read_repeatedly)
        reader.start()
        try:
            for index in range(20):
                self.assertTrue(bridge.update_pair_address(TOKEN, f"http://127.0.0.{index + 1}:8767"))
        finally:
            stopped.set()
            reader.join(2)
        self.assertTrue(reads)
        self.assertEqual(failures, [])

    def test_config_writer_lock_obeys_deadline(self):
        locked, release = threading.Event(), threading.Event()

        def hold():
            with bridge.CONFIG_WRITE_LOCK:
                locked.set()
                release.wait(2)

        worker = threading.Thread(target=hold)
        worker.start()
        self.assertTrue(locked.wait(1))
        start = time.monotonic()
        try:
            with self.assertRaises(TimeoutError):
                bridge.update_pair_address(TOKEN, "http://127.0.0.2:8767", deadline=start + 0.15)
            self.assertLess(time.monotonic() - start, 0.5)
        finally:
            release.set()
            worker.join(2)

    def test_download_requires_complete_content_and_preserves_existing_file(self):
        class Files(BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(200)
                self.send_header("Content-Length", "50" if self.path == "/truncated" else "5")
                self.send_header("X-Filename", "..%2FCON.txt")
                self.end_headers()
                self.wfile.write(b"hello")
                self.wfile.flush()
                self.close_connection = True

            def log_message(self, *args):
                pass

        with serving(Files, ThreadingHTTPServer) as server, \
                patch.object(bridge, "target", return_value=server.server_address), redirect_stdout(io.StringIO()):
            destination = self.home / "download.bin"
            with self.assertRaises((OSError, http.client.HTTPException)):
                bridge.save_download("test", TOKEN, "/truncated", destination)
            self.assertFalse(destination.exists(), "partial file must not look like a completed download")
            destination.write_bytes(b"user file")
            with self.assertRaises(RuntimeError):
                bridge.save_download("test", TOKEN, "/complete", destination)
            self.assertEqual(destination.read_bytes(), b"user file")
            completed = self.home / "completed.bin"
            bridge.save_download("test", TOKEN, "/complete", completed)
            self.assertEqual(completed.read_bytes(), b"hello")

    def test_download_creation_race_cannot_delete_someone_elses_file(self):
        class Files(BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(200)
                self.send_header("Content-Length", "5")
                self.end_headers()
                self.wfile.write(b"hello")

            def log_message(self, *args):
                pass

        destination = self.home / "raced.bin"
        original_exists = Path.exists

        def race(path):
            if path == destination:
                path.write_bytes(b"created by another writer")
                return False
            return original_exists(path)

        with serving(Files, ThreadingHTTPServer) as server, \
                patch.object(bridge, "target", return_value=server.server_address), patch.object(Path, "exists", race):
            with self.assertRaises(FileExistsError):
                bridge.save_download("test", TOKEN, "/complete", destination)
        self.assertEqual(destination.read_bytes(), b"created by another writer")

    def test_received_names_cannot_escape_or_use_windows_reserved_names(self):
        self.assertEqual(bridge.safe_filename("../folder/CON.txt"), "_CON.txt")
        self.assertEqual(bridge.safe_filename("..\\folder\\file?.txt"), "file_.txt")
        self.assertEqual(bridge.safe_filename(" .. "), "phone-file")
        self.assertEqual(bridge.safe_filename("CON .txt"), "_CON .txt")
        self.assertEqual(bridge.safe_filename("a" * 179 + ".suffix"), "a" * 179)

    def test_phone_file_inbox_checks_complete_data_and_cannot_overwrite_on_publish(self):
        class Files(BaseHTTPRequestHandler):
            truncate = False

            def do_GET(self):
                self.send_response(200)
                self.send_header("Content-Length", "50" if Files.truncate else "5")
                self.send_header("X-Filename", "inbox.txt")
                self.end_headers()
                self.wfile.write(b"hello")
                self.close_connection = True

            def log_message(self, *args):
                pass

        inbox = self.home / "inbox"
        with serving(Files, ThreadingHTTPServer) as server, \
                patch.object(bridge, "target", return_value=server.server_address), \
                patch.object(bridge, "PHONE_FILE_INBOX", inbox):
            Files.truncate = True
            with self.assertRaises((OSError, http.client.HTTPException)):
                bridge.receive_phone_file("127.0.0.1", TOKEN)
            self.assertEqual(list(inbox.iterdir()), [])
            Files.truncate = False
            original_rename = Path.rename

            def race(path, destination):
                Path(destination).write_bytes(b"another writer")
                return original_rename(path, destination)

            with patch.object(Path, "rename", race):
                with self.assertRaises(FileExistsError):
                    bridge.receive_phone_file("127.0.0.1", TOKEN)
            self.assertEqual((inbox / "inbox.txt").read_bytes(), b"another writer")
            self.assertEqual(list(inbox.glob("*.partial")), [])
            received = bridge.receive_phone_file("127.0.0.1", TOKEN)
            self.assertEqual(received.name, "inbox (2).txt")
            self.assertEqual(received.read_bytes(), b"hello")

    def test_slow_knowledge_processing_does_not_block_actual_receipt(self):
        stamp = int(time.time() * 1000)
        collector.receive([{"id": "old", "created_at": stamp, "source": "test", "shared_text": "old", "url": ""}])
        entered, release = threading.Event(), threading.Event()
        failures = []

        class SlowKnowledge:
            def request(self, *args):
                entered.set()
                release.wait(3)
                return {"data": {"id": "test-note"}}

        def process():
            try:
                collector.process_pending()
            except Exception as error:
                failures.append(error)

        with patch.object(collector, "weknora_client", return_value=SlowKnowledge()), \
                patch.object(collector, "ensure_kb", return_value="isolated-test-kb"), serving(collector.Receiver) as server:
            worker = threading.Thread(target=process)
            worker.start()
            try:
                self.assertTrue(entered.wait(2))
                started = time.monotonic()
                status, reply = post_capture(server, "new")
                self.assertEqual((status, reply["accepted"]), (200, ["new"]))
                self.assertLess(time.monotonic() - started, 1)
            finally:
                release.set()
                worker.join(3)
            self.assertFalse(worker.is_alive())
            self.assertEqual(failures, [])

    def test_month_refresh_is_batched_and_keeps_user_feedback_and_remarks(self):
        stamp = int(time.time() * 1000)
        collector.receive([{"id": "first", "created_at": stamp, "source": "test", "shared_text": "one", "url": ""},
                           {"id": "second", "created_at": stamp, "source": "test", "shared_text": "two", "url": ""}])
        path = collector.month_path(stamp)
        workbook = collector.load_workbook(path)
        sheet = workbook["收藏与反馈"]
        for column, text in [(10, "user state"), (11, "user feedback"), (12, "user time"), (13, "user remark")]:
            sheet.cell(2, column, text)
        workbook.save(path)
        workbook.close()
        with collector.managed_database() as db:
            db.execute("UPDATE captures SET remark='system failure' WHERE id='first'")
        with patch.object(collector, "load_workbook", wraps=collector.load_workbook) as load:
            collector.refresh_ledgers()
            self.assertEqual(load.call_count, 1)
        collector.refresh_ledgers()
        workbook = collector.load_workbook(path)
        try:
            sheet = workbook["收藏与反馈"]
            self.assertEqual([sheet.cell(2, column).value for column in (10, 11, 12)],
                             ["user state", "user feedback", "user time"])
            self.assertEqual(sheet.cell(2, 13).value, "user remark\nsystem failure")
            self.assertEqual(sheet.max_row, 3)
        finally:
            workbook.close()

    def test_failed_ledger_replace_preserves_existing_workbook(self):
        stamp = int(time.time() * 1000)
        item = {"id": "saved", "created_at": stamp, "source": "test", "shared_text": "one", "url": ""}
        collector.receive([item])
        path = collector.month_path(stamp)
        before = path.read_bytes()
        with collector.managed_database() as db:
            db.execute("UPDATE captures SET status='new status' WHERE id='saved'")
        with patch.object(collector.os, "replace", side_effect=OSError("simulated interrupted update")):
            with self.assertRaises(OSError):
                collector.refresh_ledgers()
        self.assertEqual(path.read_bytes(), before)
        self.assertEqual(list(path.parent.glob("*.tmp")), [])
        collector.refresh_ledgers()

    def test_pairing_changed_during_body_rejects_old_request(self):
        reading = threading.Event()

        class ObservedReceiver(collector.Receiver):
            def read_payload(self, length):
                reading.set()
                return super().read_payload(length)

        with serving(ObservedReceiver) as server, socket.create_connection(server.server_address, timeout=2) as client:
            body = json.dumps({"items": [{"id": "old-request", "created_at": int(time.time() * 1000),
                                          "source": "test", "shared_text": "old content", "url": ""}]}).encode()
            header = (f"POST /captures HTTP/1.1\r\nHost: localhost\r\nX-Phone-Token: {TOKEN}\r\n"
                      f"Content-Length: {len(body)}\r\n\r\n").encode()
            client.sendall(header)
            self.assertTrue(reading.wait(1))
            bridge.config_save({"token": "new-pair", "address": "http://127.0.0.2:8767"})
            client.sendall(body)
            reply = http.client.HTTPResponse(client)
            reply.begin()
            self.assertEqual(reply.status, 403)
            reply.read()
        with collector.managed_database() as db:
            self.assertEqual(db.execute("SELECT count(*) FROM captures").fetchone()[0], 0)

    def test_actual_receiver_closes_slow_headers_and_serves_parallel_request(self):
        class FastReceiver(collector.Receiver):
            HEADER_TIMEOUT_S = 0.3

        with serving(FastReceiver) as server, socket.create_connection(server.server_address, timeout=1) as slow:
            slow.sendall(b"GET /health HTTP/1.1\r\nX-Slow: ")
            started = time.monotonic()
            connection = http.client.HTTPConnection(*server.server_address, timeout=1)
            connection.request("GET", "/health")
            reply = connection.getresponse()
            self.assertEqual(reply.status, 200)
            self.assertEqual(json.loads(reply.read())["service"], "catdiao-nest")
            connection.close()
            self.assertLess(time.monotonic() - started, 0.2)
            self.assertEqual(slow.recv(1000), b"")
            self.assertLess(time.monotonic() - started, 0.8)

    def test_actual_receiver_rejects_incomplete_body_without_saving(self):
        class FastReceiver(collector.Receiver):
            BODY_TIMEOUT_S = 0.25

            def respond(self, *args, **kwargs):
                try:
                    return super().respond(*args, **kwargs)
                except (OSError, BrokenPipeError):
                    return

        with serving(FastReceiver) as server, socket.create_connection(server.server_address, timeout=1) as slow:
            body = (b"POST /captures HTTP/1.1\r\nHost: localhost\r\nX-Phone-Token: " + TOKEN.encode() +
                    b"\r\nContent-Length: 100\r\n\r\n{")
            started = time.monotonic()
            slow.sendall(body)
            self.assertEqual(slow.recv(1000), b"")
            self.assertLess(time.monotonic() - started, 0.8)
            with collector.managed_database() as db:
                self.assertEqual(db.execute("SELECT count(*) FROM captures").fetchone()[0], 0)

    def test_real_java_batches_arrive_in_database_and_month_ledger(self):
        executable = os.environ.get("CATDIAO_TEST_JAVA")
        self.assertTrue(executable, "set CATDIAO_TEST_JAVA to exercise the actual Android-to-PC protocol")
        classes = Path(__file__).resolve().parent / "build" / "java"
        lengths = []

        class RecordedReceiver(collector.Receiver):
            def read_payload(self, length):
                lengths.append(length)
                return super().read_payload(length)

        with serving(RecordedReceiver) as server:
            result = subprocess.run([executable, "-cp", str(classes), "com.ewan.wallpaperbridge.CatDiaoEdgeTest",
                                     "127.0.0.1", str(server.server_address[1])],
                                    capture_output=True, text=True, timeout=40)
            self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(lengths), 4)
        self.assertTrue(all(length < 600001 for length in lengths))
        with collector.managed_database() as db:
            self.assertEqual(db.execute("SELECT count(*) FROM captures").fetchone()[0], 20)
            self.assertEqual(db.execute("SELECT min(length(shared_text)) FROM captures").fetchone()[0], 30000)
            stamp = db.execute("SELECT created_at FROM captures LIMIT 1").fetchone()[0]
        workbook = collector.load_workbook(collector.month_path(stamp))
        try:
            self.assertEqual(workbook["收藏与反馈"].max_row, 21)
        finally:
            workbook.close()


if __name__ == "__main__":
    unittest.main()
