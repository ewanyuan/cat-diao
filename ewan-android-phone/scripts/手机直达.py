"""Control the owner's Android phone through the locally approved phone bridge."""

import argparse
from contextlib import contextmanager
import ctypes
import hmac
import http.client
import http.server
import ipaddress
import json
import mimetypes
import os
import re
import secrets
import select
import socket
import subprocess
import sys
import threading
import time
import urllib.parse
from pathlib import Path

import nest_paths


CONFIG = nest_paths.pairing_file()
DEFAULT_ADDRESS = "http://127.0.0.1:8767"
CLIPBOARD_RECEIVER_PORT = 8791
DISCOVERY_PORT = 8769
MAX_CLIPBOARD_BYTES = 262144
PHONE_FILE_INBOX = nest_paths.inbox_dir()
FILE_NAME_LOCK = threading.Lock()
RESERVED_FILE_NAMES = set()
CONFIG_WRITE_LOCK = threading.RLock()


def windows_clipboard_functions():
    if sys.platform != "win32":
        raise RuntimeError("目前只支持 Windows 电脑剪贴板")
    user = ctypes.WinDLL("user32", use_last_error=True)
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    user.OpenClipboard.argtypes = [ctypes.c_void_p]
    user.OpenClipboard.restype = ctypes.c_int
    user.CloseClipboard.argtypes = []
    user.EmptyClipboard.argtypes = []
    user.GetClipboardData.argtypes = [ctypes.c_uint]
    user.GetClipboardData.restype = ctypes.c_void_p
    user.SetClipboardData.argtypes = [ctypes.c_uint, ctypes.c_void_p]
    user.SetClipboardData.restype = ctypes.c_void_p
    kernel.GlobalAlloc.argtypes = [ctypes.c_uint, ctypes.c_size_t]
    kernel.GlobalAlloc.restype = ctypes.c_void_p
    kernel.GlobalLock.argtypes = [ctypes.c_void_p]
    kernel.GlobalLock.restype = ctypes.c_void_p
    kernel.GlobalUnlock.argtypes = [ctypes.c_void_p]
    kernel.GlobalFree.argtypes = [ctypes.c_void_p]
    return user, kernel


def open_windows_clipboard(user):
    for _ in range(20):
        if user.OpenClipboard(None):
            return
        time.sleep(0.05)
    raise RuntimeError("电脑剪贴板正被其他程序占用")


def read_windows_clipboard():
    user, kernel = windows_clipboard_functions()
    open_windows_clipboard(user)
    try:
        handle = user.GetClipboardData(13)  # CF_UNICODETEXT
        if not handle:
            raise RuntimeError("电脑剪贴板里没有文字")
        pointer = kernel.GlobalLock(handle)
        if not pointer:
            raise RuntimeError("无法读取电脑剪贴板")
        try:
            return ctypes.wstring_at(pointer)
        finally:
            kernel.GlobalUnlock(handle)
    finally:
        user.CloseClipboard()


def write_windows_clipboard(value):
    user, kernel = windows_clipboard_functions()
    data = (value + "\0").encode("utf-16-le")
    handle = kernel.GlobalAlloc(2, len(data))  # GMEM_MOVEABLE
    if not handle:
        raise RuntimeError("无法分配电脑剪贴板内存")
    transferred = False
    try:
        pointer = kernel.GlobalLock(handle)
        if not pointer:
            raise RuntimeError("无法写入电脑剪贴板内存")
        ctypes.memmove(pointer, data, len(data))
        kernel.GlobalUnlock(handle)
        open_windows_clipboard(user)
        try:
            if not user.EmptyClipboard() or not user.SetClipboardData(13, handle):
                raise RuntimeError("无法写入电脑剪贴板")
            transferred = True
        finally:
            user.CloseClipboard()
    finally:
        if not transferred:
            kernel.GlobalFree(handle)


def config_read(deadline=None):
    deadline = deadline if deadline is not None else time.monotonic() + 0.5
    with config_thread_lock(deadline):
        while True:
            try:
                return json.loads(CONFIG.read_text(encoding="utf-8"))
            except FileNotFoundError:
                return {}
            except PermissionError:
                # Windows can briefly deny a read while another process replaces the file.
                time.sleep(min(0.02, remaining_timeout(deadline, 0.02)))


@contextmanager
def config_thread_lock(deadline):
    if not CONFIG_WRITE_LOCK.acquire(timeout=remaining_timeout(deadline, 2)):
        raise TimeoutError("连接信息正被更新，请重试")
    try:
        yield
    finally:
        CONFIG_WRITE_LOCK.release()


@contextmanager
def config_writer(deadline=None):
    deadline = deadline if deadline is not None else time.monotonic() + 2
    CONFIG.parent.mkdir(parents=True, exist_ok=True)
    with config_thread_lock(deadline), CONFIG.with_name(CONFIG.name + ".lock").open("a+b") as lock:
        if lock.seek(0, os.SEEK_END) == 0:
            lock.write(b"\0")
            lock.flush()
        acquired = False
        try:
            while not acquired:
                remaining_timeout(deadline, 2)
                lock.seek(0)
                try:
                    if sys.platform == "win32":
                        import msvcrt
                        msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
                    else:
                        import fcntl
                        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    acquired = True
                except OSError:
                    time.sleep(min(0.02, remaining_timeout(deadline, 0.02)))
            yield deadline
        finally:
            if acquired:
                lock.seek(0)
                if sys.platform == "win32":
                    msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)
                else:
                    fcntl.flock(lock, fcntl.LOCK_UN)


def save_config_locked(data, deadline):
    value = json.dumps(data, ensure_ascii=False, indent=2)
    temporary = CONFIG.with_name(CONFIG.name + "." + secrets.token_hex(6) + ".tmp")
    try:
        with temporary.open("x", encoding="utf-8") as file:
            file.write(value)
            file.flush()
            os.fsync(file.fileno())
        while True:
            try:
                os.replace(temporary, CONFIG)
                break
            except PermissionError:
                time.sleep(min(0.02, remaining_timeout(deadline, 0.02)))
    finally:
        temporary.unlink(missing_ok=True)


def config_save(data, deadline=None):
    with config_writer(deadline) as finish_by:
        save_config_locked(data, finish_by)


def update_pair_address(token, address, deadline=None):
    with config_writer(deadline) as finish_by:
        saved = config_read()
        if saved.get("token") != token:
            return False
        if saved.get("address") != address:
            saved["address"] = address
            save_config_locked(saved, finish_by)
        return True


def target(address):
    parsed = urllib.parse.urlsplit(address)
    if parsed.scheme != "http" or not parsed.hostname or parsed.port != 8767 or parsed.path not in ("", "/"):
        raise ValueError("地址须为手机显示的 http://局域网IP:8767")
    ip = ipaddress.ip_address(parsed.hostname)
    if not (ip.is_private or ip.is_loopback):
        raise ValueError("只允许连接局域网手机")
    return parsed.hostname, parsed.port


def remaining_timeout(deadline, maximum):
    if deadline is None:
        return maximum
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise TimeoutError("连接检查超时")
    return min(maximum, remaining)


def source_ip(remote, deadline=None):
    destination = ipaddress.ip_address(remote)
    if destination.is_loopback:
        return None
    for address, mask in local_interfaces(deadline=deadline):
        try:
            if destination in ipaddress.IPv4Network(f"{address}/{mask}", strict=False):
                return address
        except ValueError:
            continue
    return None


def local_interfaces(deadline=None):
    interfaces = []
    if sys.platform == "win32":
        try:
            result = subprocess.run(["ipconfig"], capture_output=True, text=True, errors="replace",
                                    check=False, timeout=remaining_timeout(deadline, 2))
        except (OSError, subprocess.SubprocessError):
            return []
        for block in re.split(r"\r?\n\s*\r?\n", result.stdout):
            found = re.findall(r"(?<!\d)(?:\d{1,3}\.){3}\d{1,3}(?!\d)", block)
            if len(found) >= 2:
                try:
                    ip = ipaddress.ip_address(found[0])
                    mask = ipaddress.ip_address(found[1])
                    if ip.is_private and not ip.is_link_local and not ip.is_loopback:
                        interfaces.append((str(ip), str(mask)))
                except ValueError:
                    pass
    if interfaces:
        return interfaces
    if deadline is not None:
        # A hostname lookup has no portable cancellation; do not exceed a check deadline.
        return []
    addresses = []
    try:
        addresses += socket.gethostbyname_ex(socket.gethostname())[2]
    except OSError:
        pass
    return [(ip, "255.255.255.0") for ip in addresses if ipaddress.ip_address(ip).is_private]


def discover(token=None, deadline=None):
    deadline = deadline if deadline is not None else time.monotonic() + 10
    candidates = []
    receivers = []
    try:
        for address, mask in local_interfaces(deadline=deadline):
            udp = None
            try:
                remaining_timeout(deadline, 1.2)
                network = ipaddress.IPv4Network(f"{address}/{mask}", strict=False)
                broadcast = str(network.broadcast_address)
                udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                udp.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                udp.bind((address, 0))
                udp.setblocking(False)
                udp.sendto(b"PHONE_BRIDGE_DISCOVER_V1", (broadcast, DISCOVERY_PORT))
                receivers.append(udp)
            except (OSError, ValueError):
                if udp is not None:
                    udp.close()
                continue
        # All interfaces share one response window, leaving time to verify a real phone reply.
        broadcast_deadline = min(deadline, time.monotonic() + 1.2)
        while receivers and time.monotonic() < broadcast_deadline:
            ready, _, _ = select.select(receivers, [], [], remaining_timeout(broadcast_deadline, 1.2))
            if not ready:
                break
            for udp in ready:
                try:
                    payload, peer = udp.recvfrom(128)
                    if payload == b"PHONE_BRIDGE_V1:8767":
                        candidates.append("http://" + peer[0] + ":8767")
                except OSError:
                    continue
    finally:
        for udp in receivers:
            udp.close()
    for candidate in dict.fromkeys(candidates):
        try:
            hello = request(candidate, "GET", "/hello", timeout=2, deadline=deadline)
            if not isinstance(hello, dict) or hello.get("name") not in ("手机直达", "猫叼"):
                continue
            if token:
                try:
                    heartbeat = request(candidate, "GET", "/heartbeat", token, timeout=3, deadline=deadline)
                    if not isinstance(heartbeat, dict) or heartbeat.get("connected") is not True:
                        raise RuntimeError("手机未确认连接")
                except RuntimeError:
                    # Older app versions do not expose /heartbeat; /status still
                    # verifies the paired token and keeps discovery compatible.
                    status = request(candidate, "GET", "/status", token, timeout=3, deadline=deadline)
                    if not isinstance(status, dict) or not status.get("model"):
                        raise RuntimeError("手机未确认连接")
            return candidate
        except (OSError, RuntimeError, ValueError):
            continue
    return None


def is_reachable(address, deadline=None):
    try:
        host, port = target(address)
        source = source_ip(host, deadline=deadline)
        if not source and not ipaddress.ip_address(host).is_loopback:
            return False
        with socket.create_connection((host, port), timeout=remaining_timeout(deadline, 2),
                                      source_address=(source, 0) if source else None):
            return True
    except (OSError, ValueError):
        return False


def connection(address, timeout=30, deadline=None):
    host, port = target(address)
    source = source_ip(host, deadline=deadline)
    return http.client.HTTPConnection(host, port, timeout=remaining_timeout(deadline, timeout),
                                      source_address=(source, 0) if source else None)


def request(address, method, path, token=None, body=None, headers=None, stream=False, timeout=30,
            deadline=None):
    conn = connection(address, timeout=timeout, deadline=deadline)
    fields = dict(headers or {})
    if token:
        fields["X-Phone-Token"] = token
    if isinstance(body, dict):
        body = json.dumps(body, ensure_ascii=False).encode("utf-8")
        fields["Content-Type"] = "application/json"
    if isinstance(body, bytes):
        fields["Content-Length"] = str(len(body))
    watchdog = None
    reply = None
    keep_open = False
    try:
        conn.connect()
        if deadline is not None:
            connected_socket = conn.sock
            connected_socket.settimeout(remaining_timeout(deadline, timeout))

            def abort():
                try:
                    connected_socket.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                connected_socket.close()

            watchdog = threading.Timer(remaining_timeout(deadline, 3600), abort)
            watchdog.daemon = True
            watchdog.start()
        conn.request(method, path, body=body, headers=fields)
        reply = conn.getresponse()
        if stream:
            if deadline is not None:
                raise ValueError("流式传输不使用连接检查的截止时间")
            keep_open = True
            return conn, reply
        raw = reply.read()
        remaining_timeout(deadline, timeout)
        result = json.loads(raw.decode("utf-8")) if raw else {}
        if reply.status >= 400:
            raise RuntimeError(result.get("error", f"手机返回错误 {reply.status}"))
        return result
    finally:
        if watchdog is not None:
            watchdog.cancel()
        if not keep_open:
            if reply is not None:
                reply.close()
            conn.close()


def send_clipboard_text(address, token, value):
    if not value:
        raise RuntimeError("没有要发送的文字")
    payload = json.dumps({"text": value}, ensure_ascii=False).encode("utf-8")
    if len(payload) > MAX_CLIPBOARD_BYTES:
        raise RuntimeError("文字超过 256 KB，请改用文件传输")
    result = request(address, "POST", "/clipboard", token, {"text": value})
    if not result.get("copied"):
        raise RuntimeError("手机没有确认写入剪贴板")
    print("已放入手机剪贴板")


def receive_phone_file(phone_ip, token):
    conn, reply = request(f"http://{phone_ip}:8767", "GET", "/shared-file", token=token, stream=True)
    destination = partial = None
    created = False
    reserved = False
    try:
        if reply.status >= 400:
            detail = json.loads(reply.read().decode("utf-8")).get("error", "手机文件不可用")
            raise RuntimeError(detail)
        original = urllib.parse.unquote(reply.getheader("X-Filename", "phone-file"))
        name = safe_filename(original)
        expected = reply.getheader("Content-Length")
        expected_size = int(expected) if expected is not None else None
        if expected_size is not None and expected_size < 0:
            raise ValueError("手机返回了无效的文件大小")
        PHONE_FILE_INBOX.mkdir(parents=True, exist_ok=True)
        with FILE_NAME_LOCK:
            base = Path(name)
            destination = PHONE_FILE_INBOX / name
            number = 2
            while destination.exists() or str(destination) in RESERVED_FILE_NAMES:
                destination = PHONE_FILE_INBOX / f"{base.stem} ({number}){base.suffix}"
                number += 1
            RESERVED_FILE_NAMES.add(str(destination))
            reserved = True
        partial = destination.with_name(destination.name + f".{secrets.token_hex(6)}.partial")
        received = 0
        with partial.open("xb") as file:
            created = True
            while chunk := reply.read(65536):
                file.write(chunk)
                received += len(chunk)
        if expected_size is not None and received != expected_size:
            raise OSError(f"文件传输中断：收到 {received} / {expected_size} 字节")
        # On Windows rename refuses an existing destination, even if it appeared mid-transfer.
        partial.rename(destination)
        return destination
    finally:
        reply.close()
        conn.close()
        if created:
            partial.unlink(missing_ok=True)
        if reserved:
            with FILE_NAME_LOCK:
                RESERVED_FILE_NAMES.discard(str(destination))


def listen_for_phone_clipboard():
    saved = config_read()
    address, token = saved.get("address", ""), saved.get("token", "")
    if not address or not token:
        raise RuntimeError("请先让手机允许这台电脑连接")
    phone_ip, _ = target(address)
    computer_ip = source_ip(phone_ip)
    if not computer_ip:
        raise RuntimeError("电脑与手机当前不在同一局域网")

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_POST(self):
            length_text = self.headers.get("Content-Length", "")
            try:
                length = int(length_text)
            except ValueError:
                self.reply(400, "请求长度无效")
                return
            remote_ip = self.client_address[0]
            supplied = self.headers.get("X-Phone-Token", "")
            current = config_read().get("token", "")
            if (self.path not in ("/clipboard", "/file-ready") or source_ip(remote_ip) != computer_ip or
                    not current or not hmac.compare_digest(supplied, current)):
                self.reply(403, "连接未获授权")
                return
            if self.path == "/file-ready":
                if length < 0 or length > 4096:
                    self.reply(413, "文件请求无效")
                    return
                self.rfile.read(length)
                try:
                    destination = receive_phone_file(remote_ip, current)
                except (OSError, ValueError, RuntimeError) as error:
                    self.reply(502, "文件未保存：" + str(error)[:180])
                    return
                self.reply(200, "已保存到电脑", saved=str(destination))
                return
            if length <= 0 or length > MAX_CLIPBOARD_BYTES:
                self.reply(413, "文字不能超过 256 KB")
                return
            try:
                body = json.loads(self.rfile.read(length).decode("utf-8"))
                value = body.get("text")
                if not isinstance(value, str) or not value:
                    raise ValueError("没有文字")
                write_windows_clipboard(value)
            except (UnicodeError, ValueError, RuntimeError):
                self.reply(400, "文字接收失败")
                return
            self.reply(200, "已放入电脑剪贴板")

        def reply(self, status, message, **extra):
            body = json.dumps({"message": message, **extra}, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, format, *args):
            pass

    class Server(http.server.ThreadingHTTPServer):
        daemon_threads = True

    with Server((computer_ip, CLIPBOARD_RECEIVER_PORT), Handler) as server:
        if sys.stdout:
            print(f"电脑文字与文件接收已开启：{computer_ip}:{CLIPBOARD_RECEIVER_PORT}")
        server.serve_forever(poll_interval=0.5)


def pair(address, computer_name, wait_seconds=0):
    token = secrets.token_hex(32)
    deadline = time.monotonic() + wait_seconds
    while True:
        try:
            if not is_reachable(address):
                address = discover() or address
            hello = request(address, "GET", "/hello")
            break
        except OSError:
            if time.monotonic() >= deadline:
                raise
            time.sleep(2)
    print(f"找到手机：{hello.get('model', 'Android')}。正在请求手机确认电脑连接。")
    result = request(address, "POST", "/pair", body={"name": computer_name, "token": token})
    pair_id = result["id"]
    print("请打开手机上的「猫叼」，在弹出的连接请求中点「允许」。")
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        state = request(address, "GET", "/pair-status?id=" + urllib.parse.quote(pair_id))["state"]
        if state == "approved":
            config_save({"address": address, "token": token, "name": computer_name})
            print("手机已确认这台电脑。请在猫叼按引导完成手机设置；全部完成后即可使用电脑控制功能。")
            return
        if state != "pending":
            raise RuntimeError("手机未允许本次连接：" + state)
        time.sleep(2)
    raise RuntimeError("手机确认超时，请重新执行连接")


def authenticated(args):
    saved = config_read()
    address = args.address or saved.get("address", DEFAULT_ADDRESS)
    token = saved.get("token")
    if not token:
        raise RuntimeError("尚未连接手机。请先运行：python 手机直达.py pair")
    if not args.address and not is_reachable(address):
        found = discover(token)
        if found:
            address = found
            if not update_pair_address(token, found):
                raise RuntimeError("小窝连接信息已变化，请重新执行本次操作。")
        else:
            raise RuntimeError("手机暂时没有接收连接。请确认手机和电脑在同一 Wi-Fi，并解锁手机打开一次「猫叼」。")
    return address, token


def save_download(address, token, path, output):
    conn, reply = request(address, "GET", path, token=token, stream=True)
    destination = None
    created = False
    try:
        if reply.status >= 400:
            message = json.loads(reply.read().decode("utf-8"))
            raise RuntimeError(message.get("error", "手机下载失败"))
        suggested = safe_filename(urllib.parse.unquote(reply.getheader("X-Filename", "phone-file")))
        destination = Path(output or suggested).expanduser().resolve()
        if destination.exists():
            raise RuntimeError("文件已存在，请指定其他保存路径：" + str(destination))
        expected = reply.getheader("Content-Length")
        expected_size = int(expected) if expected is not None else None
        if expected_size is not None and expected_size < 0:
            raise ValueError("手机下载返回了无效的文件大小")
        destination.parent.mkdir(parents=True, exist_ok=True)
        received = 0
        with destination.open("xb") as file:
            created = True
            while True:
                chunk = reply.read(65536)
                if not chunk:
                    break
                file.write(chunk)
                received += len(chunk)
        if expected_size is not None and received != expected_size:
            raise OSError(f"文件传输中断：收到 {received} / {expected_size} 字节，请重试")
    except BaseException:
        if created:
            destination.unlink(missing_ok=True)
        raise
    finally:
        reply.close()
        conn.close()
    print("已保存：" + str(destination))


def safe_filename(value):
    name = re.split(r"[/\\]", value)[-1]
    name = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", name).strip(" .")[:180].rstrip(" .") or "phone-file"
    if name.split(".", 1)[0].rstrip(" ").upper() in {"CON", "PRN", "AUX", "NUL", *[f"COM{i}" for i in range(1, 10)],
                                        *[f"LPT{i}" for i in range(1, 10)]}:
        name = "_" + name
    return name


def send_file(address, token, filename):
    path = Path(filename).expanduser().resolve()
    if not path.is_file():
        raise ValueError("文件不存在：" + str(path))
    size = path.stat().st_size
    if size <= 0:
        raise ValueError("文件不能为空")
    hello = request(address, "GET", "/hello")
    try:
        phone_version = tuple(int(part) for part in hello.get("version", "0").split("."))
    except (TypeError, ValueError):
        phone_version = (0,)
    if size > 100 * 1024 * 1024 and phone_version < (0, 6):
        raise RuntimeError("手机上的「手机直达」需更新到 0.6 才能发送超过 100 MB 的文件")
    status = request(address, "GET", "/status", token)
    free = status.get("storage_free_bytes")
    if isinstance(free, int) and size > free - 64 * 1024 * 1024:
        raise ValueError("手机剩余空间不足，无法保存此文件")
    conn = connection(address, timeout=180)
    with path.open("rb") as file:
        conn.request("POST", "/file", body=file, headers={
            "X-Phone-Token": token,
            "X-Filename": urllib.parse.quote(path.name, safe=""),
            "Content-Type": mimetypes.guess_type(path.name)[0] or "application/octet-stream",
            "Content-Length": str(size),
        })
        reply = conn.getresponse()
        data = json.loads(reply.read().decode("utf-8"))
    conn.close()
    if reply.status >= 400:
        raise RuntimeError(data.get("error", "文件发送失败"))
    print("已发送到手机：" + data["folder"] + "/" + data["saved"])


def set_wallpaper(address, token, filename):
    path = Path(filename).expanduser().resolve()
    if not path.is_file() or not 0 < path.stat().st_size <= 20 * 1024 * 1024:
        raise ValueError("壁纸图片不存在、为空，或超过 20 MB")
    conn = connection(address)
    with path.open("rb") as image:
        conn.request("POST", "/wallpaper/home", body=image, headers={
            "X-Phone-Token": token,
            "Content-Type": mimetypes.guess_type(path.name)[0] or "application/octet-stream",
            "Content-Length": str(path.stat().st_size),
        })
        reply = conn.getresponse()
        result = json.loads(reply.read().decode("utf-8"))
    conn.close()
    if reply.status >= 400:
        raise RuntimeError(result.get("error", "设置壁纸失败"))
    if result.get("before") == result.get("after"):
        raise RuntimeError("手机未确认桌面壁纸变化")
    if not result.get("lock_unchanged"):
        raise RuntimeError("桌面已更换，但锁屏编号也发生变化，请查看手机")
    print("桌面壁纸已更新；锁屏未变。编号：" +
          str(result["before"]) + " → " + str(result["after"]))


def diagnosis(status):
    issues = []
    if status["battery_percent"] >= 0 and status["battery_percent"] <= 15 and not status["charging"]:
        issues.append("电量偏低，建议充电")
    if status["battery_celsius"] is not None and status["battery_celsius"] >= 43:
        issues.append("电池温度偏高，先停止高负载应用并让手机散热")
    if status["storage_free_bytes"] < 2 * 1024**3:
        issues.append("可用存储不足 2 GB")
    if status["memory_low"]:
        issues.append("系统报告内存紧张")
    if not status["screen_control_enabled"]:
        issues.append("跨应用屏幕控制尚未开启")
    return issues


def main():
    parser = argparse.ArgumentParser(description="通过局域网控制已允许的安卓手机；无需无线调试")
    parser.add_argument("--address", help="猫叼首页右上角「⋯ → 小窝连接」显示的连接地址，默认使用上次地址")
    commands = parser.add_subparsers(dest="command", required=True)
    pairing = commands.add_parser("pair", help="请求手机允许这台电脑")
    pairing.add_argument("--name", default=socket.gethostname())
    pairing.add_argument("--wait", type=int, default=0, help="等待手机打开应用的秒数")
    commands.add_parser("status", help="查看电量、温度、存储、内存与权限")
    commands.add_parser("diagnose", help="查看基础异常提示")
    commands.add_parser("apps", help="列出可打开的应用")
    app = commands.add_parser("open", help="打开应用；在后台时需要点手机通知")
    app.add_argument("package")
    volume = commands.add_parser("volume", help="设置媒体或铃声音量")
    volume.add_argument("level", type=int)
    volume.add_argument("--ring", action="store_true")
    brightness = commands.add_parser("brightness", help="设置屏幕亮度，范围 1 到 255")
    brightness.add_argument("level", type=int)
    sent = commands.add_parser("send", help="把电脑文件发送到手机下载目录")
    sent.add_argument("file")
    clipboard_send = commands.add_parser("clipboard-send", help="把电脑剪贴板或指定文字放入手机剪贴板")
    clipboard_send.add_argument("--text", help="要发送的文字；省略时读取电脑剪贴板")
    commands.add_parser("clipboard-listen", help="接收手机发来的文字并放入电脑剪贴板")
    wallpaper = commands.add_parser("wallpaper", help="后台直接设置手机桌面壁纸，不需停留在应用页面")
    wallpaper.add_argument("image")
    received = commands.add_parser("get", help="下载手机上选中的文件")
    received.add_argument("output", nargs="?")
    screen = commands.add_parser("screen", help="保存手机屏幕截图")
    screen.add_argument("output", nargs="?", default="phone-screen.jpg")
    for action in ("home", "back", "recents", "notifications"):
        commands.add_parser(action)
    tap = commands.add_parser("tap", help="点按屏幕像素坐标")
    tap.add_argument("x", type=int); tap.add_argument("y", type=int)
    swipe = commands.add_parser("swipe", help="从起点滑到终点")
    for name in ("x1", "y1", "x2", "y2"):
        swipe.add_argument(name, type=int)
    swipe.add_argument("--duration", type=int, default=350)
    args = parser.parse_args()
    if args.command == "pair":
        saved = config_read()
        pair(args.address or saved.get("address", DEFAULT_ADDRESS), args.name, args.wait)
        return
    if args.command == "clipboard-listen":
        listen_for_phone_clipboard()
        return
    address, token = authenticated(args)
    command = args.command
    if command == "send":
        send_file(address, token, args.file); return
    if command == "clipboard-send":
        send_clipboard_text(address, token,
                            args.text if args.text is not None else read_windows_clipboard())
        return
    if command == "wallpaper":
        set_wallpaper(address, token, args.image); return
    if command in ("get", "screen"):
        save_download(address, token, "/shared-file" if command == "get" else "/screen", args.output)
        return
    if command in ("status", "diagnose"):
        result = request(address, "GET", "/status", token)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        if command == "diagnose":
            issues = diagnosis(result)
            print("可检查的问题：" + ("；".join(issues) if issues else "基础指标未见明显异常"))
        return
    if command == "apps":
        result = request(address, "GET", "/apps", token)
        for item in result:
            print(item["name"] + "\t" + item["package"])
        return
    if command == "open":
        path, body = "/open", {"package": args.package}
    elif command == "volume":
        path, body = "/volume", {"channel": "ring" if args.ring else "media", "level": args.level}
    elif command == "brightness":
        path, body = "/brightness", {"level": args.level}
    elif command == "tap":
        path, body = "/action", {"type": "tap", "x": args.x, "y": args.y}
    elif command == "swipe":
        path, body = "/action", {"type": "swipe", "x1": args.x1, "y1": args.y1,
                                  "x2": args.x2, "y2": args.y2, "duration_ms": args.duration}
    else:
        path, body = "/action", {"type": command}
    print(json.dumps(request(address, "POST", path, token, body), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (TimeoutError, ConnectionRefusedError) as error:
        raise SystemExit("手机直达：手机暂时没有接收连接。请确认手机与电脑在同一 Wi-Fi，解锁手机并打开一次「猫叼」；若地址变化，用 --address 指定新地址。") from error
    except (OSError, ValueError, RuntimeError, json.JSONDecodeError) as error:
        raise SystemExit("手机直达：" + str(error)) from error
