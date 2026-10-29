"""MCP stdio server so an AI can see the phone screen and tap it.

The supported use is 看画面、点画面的真机验证. This server does not replace ADB:
there is no logcat, no breakpoint debugging, and no silent install. The first
install needs one confirmation tap on the phone.
"""

from __future__ import annotations

import argparse
import base64
import json
import sys
from types import SimpleNamespace

import 手机直达 as phone

SERVER_NAME = "cat-diao"
SERVER_VERSION = "2.0"
PROTOCOL_VERSION = "2024-11-05"
INSTRUCTIONS = (
    "猫叼让 AI 做看画面、点画面的真机验证。"
    "它不替代 ADB：不能看 logcat、不能下断点、不能静默安装。"
    "首次安装需要用户在手机上点一次确认。"
    "请只在用户信任的 Wi-Fi 上使用；内容在局域网上是明文。"
)


class LivePhone:
    """Calls the existing phone commands. Tests can substitute another object."""

    def status(self):
        address, token = phone.authenticated(SimpleNamespace(address=None))
        return phone.request(address, "GET", "/status", token)

    def screenshot(self):
        address, token = phone.authenticated(SimpleNamespace(address=None))
        return phone.fetch_bytes(address, token, "/screen")

    def tap(self, x, y):
        return self._action({"type": "tap", "x": int(x), "y": int(y)})

    def swipe(self, x1, y1, x2, y2, duration_ms):
        return self._action({"type": "swipe", "x1": int(x1), "y1": int(y1),
                              "x2": int(x2), "y2": int(y2), "duration_ms": int(duration_ms)})

    def press(self, name):
        if name not in {"home", "back", "recents", "notifications"}:
            raise ValueError("不认识的按键")
        return self._action({"type": name})

    def open_app(self, package):
        address, token = phone.authenticated(SimpleNamespace(address=None))
        return phone.request(address, "POST", "/open", token, {"package": package})

    def send_file(self, filename):
        address, token = phone.authenticated(SimpleNamespace(address=None))
        phone.send_file(address, token, filename)
        return {"saved": True, "note": "首次安装需要用户在手机上点一次确认"}

    def wallpaper(self, filename):
        address, token = phone.authenticated(SimpleNamespace(address=None))
        phone.set_wallpaper(address, token, filename)
        return {"updated": True}

    def _action(self, body):
        address, token = phone.authenticated(SimpleNamespace(address=None))
        return phone.request(address, "POST", "/action", token, body)


def _tool(name, description, properties, required):
    return {
        "name": name,
        "description": description,
        "inputSchema": {"type": "object", "properties": properties, "required": required},
    }


def tool_definitions():
    number = {"type": "integer"}
    return [
        _tool("phone_screenshot", "看画面：获取手机当前屏幕，用于真机验证。不替代 ADB。", {}, []),
        _tool("phone_tap", "点画面：点按截图上的像素坐标。",
              {"x": number, "y": number}, ["x", "y"]),
        _tool("phone_swipe", "在画面上滑动。",
              {"x1": number, "y1": number, "x2": number, "y2": number, "duration_ms": number},
              ["x1", "y1", "x2", "y2"]),
        _tool("phone_press", "按下 home、back、recents 或 notifications。",
              {"name": {"type": "string", "enum": ["home", "back", "recents", "notifications"]}},
              ["name"]),
        _tool("phone_open", "打开已安装应用。", {"package": {"type": "string"}}, ["package"]),
        _tool("phone_send", "把电脑上的文件发到手机。首次安装需要用户在手机上点一次确认。",
              {"file": {"type": "string"}}, ["file"]),
        _tool("phone_wallpaper", "设置手机桌面壁纸，不改锁屏。",
              {"image": {"type": "string"}}, ["image"]),
        _tool("phone_status", "查看电量、存储和权限。", {}, []),
    ]


def _text(value):
    if isinstance(value, str):
        text = value
    else:
        text = json.dumps(value, ensure_ascii=False)
    return {"content": [{"type": "text", "text": text}]}


def call_tool(name, arguments, client):
    arguments = arguments or {}
    if name == "phone_screenshot":
        image = client.screenshot()
        encoded = base64.b64encode(image).decode("ascii")
        return {"content": [{"type": "image", "data": encoded, "mimeType": "image/jpeg"}]}
    if name == "phone_tap":
        return _text(client.tap(arguments["x"], arguments["y"]))
    if name == "phone_swipe":
        return _text(client.swipe(arguments["x1"], arguments["y1"], arguments["x2"], arguments["y2"],
                                  arguments.get("duration_ms", 350)))
    if name == "phone_press":
        return _text(client.press(arguments["name"]))
    if name == "phone_open":
        return _text(client.open_app(arguments["package"]))
    if name == "phone_send":
        return _text(client.send_file(arguments["file"]))
    if name == "phone_wallpaper":
        return _text(client.wallpaper(arguments["image"]))
    if name == "phone_status":
        return _text(client.status())
    raise ValueError("没有这个工具：" + name)


def handle(message, client):
    """Answer one JSON-RPC object. Notifications return None."""
    method = message.get("method", "")
    has_id = "id" in message and message.get("id") is not None
    request_id = message.get("id")
    params = message.get("params") or {}
    try:
        if method == "initialize":
            result = {
                "protocolVersion": PROTOCOL_VERSION,
                "capabilities": {"tools": {"listChanged": False}},
                "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION},
                "instructions": INSTRUCTIONS,
            }
        elif method == "notifications/initialized":
            return None
        elif method == "ping":
            result = {}
        elif method == "tools/list":
            result = {"tools": tool_definitions()}
        elif method == "tools/call":
            result = call_tool(params.get("name", ""), params.get("arguments") or {}, client)
        elif not has_id:
            return None
        else:
            return {"jsonrpc": "2.0", "id": request_id,
                    "error": {"code": -32601, "message": "不支持的方法"}}
        if not has_id:
            return None
        return {"jsonrpc": "2.0", "id": request_id, "result": result}
    except Exception as error:
        if not has_id:
            return None
        return {"jsonrpc": "2.0", "id": request_id,
                "error": {"code": -32000, "message": str(error)}}


def read_message(stream):
    headers = {}
    while True:
        line = stream.readline()
        if line == b"":
            return None
        if line in (b"\r\n", b"\n"):
            break
        text = line.decode("ascii", errors="replace").strip()
        if ":" in text:
            name, value = text.split(":", 1)
            headers[name.lower()] = value.strip()
    length = int(headers.get("content-length", "0"))
    if length < 1:
        raise ValueError("缺少 Content-Length")
    payload = stream.read(length)
    if len(payload) != length:
        raise ValueError("消息不完整")
    return json.loads(payload.decode("utf-8"))


def write_message(stream, payload):
    raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    stream.write(f"Content-Length: {len(raw)}\r\n\r\n".encode("ascii"))
    stream.write(raw)
    stream.flush()


def serve(input_stream, output_stream, client=None):
    client = client or LivePhone()
    while True:
        message = read_message(input_stream)
        if message is None:
            return
        reply = handle(message, client)
        if reply is not None:
            write_message(output_stream, reply)


def main():
    parser = argparse.ArgumentParser(description="猫叼 MCP：看画面、点画面的真机验证。不替代 ADB。")
    parser.parse_args()
    serve(sys.stdin.buffer, sys.stdout.buffer)


if __name__ == "__main__":
    main()
