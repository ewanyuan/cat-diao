"""One scheduled health check; launch the existing receiver only when its port is free."""
import http.client
import argparse
import base64
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time

options = argparse.ArgumentParser()
options.add_argument("--home-base64")
options.add_argument("--resolve-home", action="store_true")
arguments = options.parse_args()
if arguments.home_base64:
    configured = base64.b64decode(arguments.home_base64, validate=True).decode("utf-8")
    if not Path(configured).is_absolute():
        raise ValueError("Receiver data directory must be absolute")
    os.environ["CATDIAO_NEST_HOME"] = configured
import nest_paths


def physical_home(path):
    """Resolve MSIX filesystem redirection before a non-packaged scheduled launch."""
    if sys.platform != "win32":
        return str(path.resolve())
    import ctypes
    from ctypes import wintypes
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.CreateFileW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD,
                                   ctypes.c_void_p, wintypes.DWORD, wintypes.DWORD, wintypes.HANDLE]
    kernel.CreateFileW.restype = wintypes.HANDLE
    kernel.GetFinalPathNameByHandleW.argtypes = [wintypes.HANDLE, wintypes.LPWSTR, wintypes.DWORD, wintypes.DWORD]
    kernel.GetFinalPathNameByHandleW.restype = wintypes.DWORD
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    handle = kernel.CreateFileW(str(path), 0, 7, None, 3, 0x02000000, None)
    if handle == ctypes.c_void_p(-1).value:
        raise ctypes.WinError(ctypes.get_last_error())
    try:
        buffer = ctypes.create_unicode_buffer(32768)
        length = kernel.GetFinalPathNameByHandleW(handle, buffer, len(buffer), 0)
        if not length or length >= len(buffer):
            raise ctypes.WinError(ctypes.get_last_error())
        resolved = buffer.value
        if resolved.startswith("\\\\?\\UNC\\"):
            return "\\\\" + resolved[8:]
        return resolved[4:] if resolved.startswith("\\\\?\\") else resolved
    finally:
        kernel.CloseHandle(handle)


if arguments.resolve_home:
    anchor = nest_paths.pairing_file()
    if anchor.is_file():
        home = Path(physical_home(anchor)).parent
    else:
        # First setup has a runtime but no pairing yet. Resolve a file, since
        # MSIX can redirect individual files without redirecting the directory.
        home = nest_paths.HOME if nest_paths.IN_REPO else Path(physical_home(nest_paths.HOME / "runtime" / "pyvenv.cfg")).parent.parent
    print(base64.b64encode(str(home).encode("utf-8")).decode("ascii"))
    sys.exit(0)


def ensure_receiver():
    connection = http.client.HTTPConnection("127.0.0.1", 8793, timeout=3)
    try:
        connection.request("GET", "/health")
        reply = connection.getresponse()
        if reply.status == 200 and json.loads(reply.read(65536)).get("service") == "catdiao-nest":
            return
    except (OSError, ValueError, http.client.HTTPException):
        pass
    finally:
        connection.close()
    try:
        with socket.create_connection(("127.0.0.1", 8793), timeout=1):
            return  # Never start a competing listener on an occupied port.
    except OSError:
        pass
    logs = nest_paths.HOME / "logs"
    logs.mkdir(parents=True, exist_ok=True)
    environment = dict(os.environ, CATDIAO_NEST_HOME=str(nest_paths.HOME), PYTHONUNBUFFERED="1")
    with (logs / "recovery-start.log").open("a", encoding="utf-8") as record:
        record.write(time.strftime("%Y-%m-%d %H:%M:%S ") + str(nest_paths.HOME) + "\n")
    receiver = Path(__file__).resolve().with_name("收藏同步.py")
    # Reuse venv dependencies while avoiding its Unicode-path executable launcher.
    dependencies = str((nest_paths.REPO_ROOT / ".venv" if nest_paths.IN_REPO else nest_paths.HOME / "runtime") / "Lib" / "site-packages")
    environment["PYTHONPATH"] = dependencies + (os.pathsep + environment["PYTHONPATH"] if environment.get("PYTHONPATH") else "")
    with (logs / "recovery-receiver.log").open("ab") as output, (logs / "recovery-receiver-error.log").open("ab") as errors:
        process = subprocess.Popen([sys.executable, str(receiver), "listen"], cwd=str(receiver.parent),
                                   env=environment, stdin=subprocess.DEVNULL, stdout=output, stderr=errors,
                                   creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0), close_fds=True)
    time.sleep(1)
    if process.poll() is not None:
        raise RuntimeError("Cat Diao receiver exited during recovery")


if __name__ == "__main__":
    try:
        ensure_receiver()
    except Exception as error:
        logs = nest_paths.HOME / "logs"
        logs.mkdir(parents=True, exist_ok=True)
        with (logs / "recovery-error.log").open("a", encoding="utf-8") as log:
            log.write(time.strftime("%Y-%m-%d %H:%M:%S ") + type(error).__name__ + ": " + str(error)[:180] + "\n")
        sys.exit(1)
