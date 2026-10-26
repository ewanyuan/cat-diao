"""Small Windows-friendly client for the user's WeKnora REST API.

Adapted from the API operations documented by @lyingbug/weknora v1.0.1.
No secret values are printed or persisted by this script.
"""

from __future__ import annotations

import argparse
import http.client
import ipaddress
import json
import mimetypes
import os
import sys
import uuid
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode, urlsplit
from urllib.request import Request, urlopen


class WeKnoraError(Exception):
    pass


def read_env_file(path: str | None) -> dict[str, str]:
    if not path:
        return {}
    source = Path(path).expanduser()
    if not source.is_file():
        raise WeKnoraError(f"Credential file does not exist: {source}")
    result: dict[str, str] = {}
    for line in source.read_text(encoding="utf-8-sig").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        if line.startswith("export "):
            line = line[7:].strip()
        name, value = line.split("=", 1)
        name, value = name.strip(), value.strip()
        if value.startswith(('"', "'")) and value.endswith(value[:1]):
            value = value[1:-1]
        result[name] = value
    return result


def normalize_base_url(raw: str) -> str:
    value = raw.strip().rstrip("/")
    parsed = urlsplit(value)
    if not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise WeKnoraError("WEKNORA_BASE_URL must be a plain server URL")
    if parsed.scheme not in {"http", "https"}:
        raise WeKnoraError("WEKNORA_BASE_URL must use HTTPS or local HTTP")
    if parsed.scheme == "http":
        host = parsed.hostname.lower()
        try:
            loopback = ipaddress.ip_address(host).is_loopback
        except ValueError:
            loopback = host == "localhost"
        if not loopback:
            raise WeKnoraError("HTTP is allowed only for a loopback WeKnora address")
    if not parsed.path.endswith("/api/v1"):
        if parsed.path not in {"", "/"}:
            raise WeKnoraError("WEKNORA_BASE_URL must end at the server root or /api/v1")
        value += "/api/v1"
    return value


class WeKnoraClient:
    def __init__(self, base_url: str, api_key: str, *, timeout: int = 60) -> None:
        if not api_key:
            raise WeKnoraError("WEKNORA_API_KEY is not configured")
        self.base_url = normalize_base_url(base_url)
        self.api_key = api_key
        self.timeout = timeout

    def _decode(self, raw: bytes) -> object:
        try:
            result = json.loads(raw.decode("utf-8")) if raw else None
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise WeKnoraError("WeKnora returned a non-JSON response") from exc
        if isinstance(result, dict) and result.get("success") is False:
            detail = result.get("message") or result.get("error") or "request failed"
            raise WeKnoraError(f"WeKnora rejected the request: {str(detail)[:300]}")
        return result

    def request(self, method: str, path: str, payload: dict | None = None) -> object:
        url = f"{self.base_url}/{path.lstrip('/')}"
        headers = {
            "Accept": "application/json",
            "X-API-Key": self.api_key,
            "X-Request-ID": str(uuid.uuid4()),
        }
        body = None
        if payload is not None:
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        request = Request(url, data=body, headers=headers, method=method)
        try:
            with urlopen(request, timeout=self.timeout) as response:
                return self._decode(response.read())
        except HTTPError as exc:
            detail = exc.read(1000).decode("utf-8", errors="replace")
            raise WeKnoraError(f"WeKnora HTTP {exc.code}: {detail[:300]}") from exc
        except (URLError, TimeoutError) as exc:
            raise WeKnoraError(f"Cannot reach WeKnora: {exc.reason if isinstance(exc, URLError) else exc}") from exc

    def upload_file(self, kb_id: str, source: Path, *, multimodel: bool = False) -> object:
        source = source.expanduser().resolve()
        if not source.is_file():
            raise WeKnoraError(f"Upload file does not exist: {source}")
        boundary = f"weknora-{uuid.uuid4().hex}"
        safe_name = "".join(c for c in source.name if c.isascii() and c not in '"\\\r\n') or "upload"
        mime = mimetypes.guess_type(source.name)[0] or "application/octet-stream"
        prefix = (
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="file"; filename="{safe_name}"\r\n'
            f"Content-Type: {mime}\r\n\r\n"
        ).encode("utf-8")
        suffix = (
            f"\r\n--{boundary}\r\n"
            'Content-Disposition: form-data; name="fileName"\r\n\r\n'
            f"{source.name}\r\n"
            f"--{boundary}\r\n"
            'Content-Disposition: form-data; name="enable_multimodel"\r\n\r\n'
            f"{'true' if multimodel else 'false'}\r\n"
            f"--{boundary}--\r\n"
        ).encode("utf-8")
        size = len(prefix) + source.stat().st_size + len(suffix)
        url = urlsplit(f"{self.base_url}/knowledge-bases/{quote(kb_id, safe='')}/knowledge/file")
        connection_type = http.client.HTTPSConnection if url.scheme == "https" else http.client.HTTPConnection
        connection = connection_type(url.hostname, url.port, timeout=max(self.timeout, 120))
        try:
            connection.putrequest("POST", url.path)
            connection.putheader("Accept", "application/json")
            connection.putheader("X-API-Key", self.api_key)
            connection.putheader("X-Request-ID", str(uuid.uuid4()))
            connection.putheader("Content-Type", f"multipart/form-data; boundary={boundary}")
            connection.putheader("Content-Length", str(size))
            connection.endheaders()
            connection.send(prefix)
            with source.open("rb") as file:
                while chunk := file.read(1024 * 1024):
                    connection.send(chunk)
            connection.send(suffix)
            response = connection.getresponse()
            raw = response.read()
            if response.status < 200 or response.status >= 300:
                raise WeKnoraError(f"WeKnora HTTP {response.status}: {raw[:300].decode('utf-8', errors='replace')}")
            return self._decode(raw)
        except (OSError, TimeoutError) as exc:
            raise WeKnoraError(f"File upload failed: {exc}") from exc
        finally:
            connection.close()


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description="Direct WeKnora knowledge operations")
    root.add_argument("--env-file", default=os.environ.get("WEKNORA_ENV_FILE"), help="Private .env with WeKnora URL and key")
    commands = root.add_subparsers(dest="command", required=True)
    commands.add_parser("list-kbs", help="List accessible knowledge bases")
    listing = commands.add_parser("list-docs", help="List documents in one knowledge base")
    listing.add_argument("--kb", required=True, help="Actual knowledge base ID")
    listing.add_argument("--page", type=int, default=1)
    listing.add_argument("--page-size", type=int, default=30)
    reading = commands.add_parser("get", help="Read one knowledge entry's details")
    reading.add_argument("--id", required=True, help="Actual knowledge ID")
    searching = commands.add_parser("search", help="Hybrid search in one knowledge base")
    searching.add_argument("--kb", required=True)
    searching.add_argument("--query", required=True)
    searching.add_argument("--limit", type=int, default=5)
    across = commands.add_parser("search-across", help="Search selected knowledge bases")
    across.add_argument("--kb", action="append", required=True, help="Repeat for each KB ID")
    across.add_argument("--query", required=True)
    saving = commands.add_parser("save-md", help="Save a Markdown note")
    saving.add_argument("--kb", required=True)
    saving.add_argument("--title", required=True)
    saving.add_argument("--content-file", required=True)
    saving.add_argument("--draft", action="store_true", help="Do not publish for indexing yet")
    importing = commands.add_parser("import-url", help="Import a web page or remote file")
    importing.add_argument("--kb", required=True)
    importing.add_argument("--url", required=True)
    uploading = commands.add_parser("upload-file", help="Stream a local file to WeKnora")
    uploading.add_argument("--kb", required=True)
    uploading.add_argument("--file", required=True)
    uploading.add_argument("--multimodel", action="store_true")
    return root


def run(args: argparse.Namespace, client: WeKnoraClient) -> object:
    if args.command == "list-kbs":
        return client.request("GET", "knowledge-bases")
    if args.command == "list-docs":
        if args.page < 1 or args.page_size < 1:
            raise WeKnoraError("page and page-size must be positive")
        query = urlencode({"page": args.page, "page_size": args.page_size})
        return client.request("GET", f"knowledge-bases/{quote(args.kb, safe='')}/knowledge?{query}")
    if args.command == "get":
        return client.request("GET", f"knowledge/{quote(args.id, safe='')}")
    if args.command == "search":
        if not args.query.strip() or not 1 <= args.limit <= 30:
            raise WeKnoraError("query is required and limit must be between 1 and 30")
        return client.request(
            "POST", f"knowledge-bases/{quote(args.kb, safe='')}/hybrid-search",
            {"query_text": args.query, "match_count": args.limit},
        )
    if args.command == "search-across":
        if not args.query.strip():
            raise WeKnoraError("query is required")
        return client.request("POST", "knowledge-search", {"query": args.query, "knowledge_base_ids": args.kb})
    if args.command == "save-md":
        file = Path(args.content_file).expanduser()
        if not file.is_file():
            raise WeKnoraError(f"Markdown file does not exist: {file}")
        content = file.read_text(encoding="utf-8-sig")
        if not args.title.strip() or not content.strip():
            raise WeKnoraError("Title and Markdown content must not be empty")
        return client.request(
            "POST", f"knowledge-bases/{quote(args.kb, safe='')}/knowledge/manual",
            {"title": args.title, "content": content, "status": "draft" if args.draft else "publish", "channel": "api"},
        )
    if args.command == "import-url":
        parsed = urlsplit(args.url)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname:
            raise WeKnoraError("Import URL must be an HTTP(S) URL")
        return client.request("POST", f"knowledge-bases/{quote(args.kb, safe='')}/knowledge/url", {"url": args.url})
    if args.command == "upload-file":
        return client.upload_file(args.kb, Path(args.file), multimodel=args.multimodel)
    raise WeKnoraError(f"Unknown command: {args.command}")


def main() -> int:
    args = parser().parse_args()
    api_key = ""
    try:
        values = read_env_file(args.env_file)
        base_url = os.environ.get("WEKNORA_BASE_URL") or values.get("WEKNORA_BASE_URL") or values.get("WEKNORA_HOST") or ""
        api_key = os.environ.get("WEKNORA_API_KEY") or values.get("WEKNORA_API_KEY") or values.get("WEKNORA_TOKEN") or ""
        if not base_url or not api_key:
            raise WeKnoraError("Set WEKNORA_BASE_URL and WEKNORA_API_KEY in the environment or private .env")
        client = WeKnoraClient(base_url, api_key)
        result = run(args, client)
        print(json.dumps(result, ensure_ascii=True, indent=2))
        return 0
    except WeKnoraError as exc:
        # The server may include arbitrary text in errors. Never echo a key.
        message = str(exc).replace(api_key, "[redacted]") if api_key else str(exc)
        print(json.dumps({"error": message}, ensure_ascii=True), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
