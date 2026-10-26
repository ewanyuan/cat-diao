"""Decide whether the nest receiver has a private-network firewall rule.

Windows setup creates the rule. This module only reports status and can be
tested without touching a live firewall.
"""

from __future__ import annotations

import json
import sys
import threading
import time

from catdiao_version import NEST_PORT

RULE_NAME = "猫叼小窝"
PORT = NEST_PORT
CACHE_SECONDS = 60
_CACHE_LOCK = threading.Lock()
_CACHE = {"expires": 0.0, "value": None, "running": False, "thread": None}


def desired_rule(port: int = PORT) -> dict:
    return {
        "name": RULE_NAME,
        "port": port,
        "profiles": ["Private"],
        "remote": "LocalSubnet",
        "direction": "Inbound",
        "protocol": "TCP",
        "action": "Allow",
    }


def assess(port: int, profiles: list[dict], rules: list[dict]) -> dict:
    """profiles: [{name, category}] category is Public, Private, or Domain.
    rules: [{name, port, profiles, remote, direction, action, enabled}]
    """
    public = [item.get("name", "") for item in profiles if item.get("category") == "Public"]
    private = [item.get("name", "") for item in profiles if item.get("category") == "Private"]
    matching = []
    for rule in rules:
        if not rule.get("enabled", True):
            continue
        try:
            rule_port = int(rule.get("port", 0))
        except (TypeError, ValueError):
            continue
        profiles_text = " ".join(rule.get("profiles") or [])
        remote = str(rule.get("remote") or "")
        if (rule_port == port and rule.get("direction") == "Inbound"
                and rule.get("action") == "Allow" and "Private" in profiles_text
                and remote.lower() == "localsubnet"):
            matching.append(rule.get("name") or RULE_NAME)
    if matching:
        state = "ok"
    else:
        state = "missing"
    return {
        "state": state,
        "port": port,
        "public_networks": public,
        "private_networks": private,
        "needs_private_profile": bool(public) and not private,
        "rules": matching,
    }


def unknown_status() -> dict:
    return {"state": "unknown", "port": PORT, "public_networks": [], "private_networks": [],
            "needs_private_profile": False, "rules": []}


def not_windows_status() -> dict:
    return {"state": "not_windows", "port": PORT, "public_networks": [], "private_networks": [],
            "needs_private_profile": False, "rules": []}


def decode_console(raw: bytes | str | None) -> str:
    """PowerShell is asked for UTF-8. Chinese Windows netsh still emits GBK."""
    if raw is None:
        return ""
    if isinstance(raw, str):
        return raw
    if not raw:
        return ""
    if raw.startswith(b"\xef\xbb\xbf"):
        return raw.decode("utf-8-sig", errors="replace")
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        for encoding in ("gbk", "mbcs"):
            try:
                return raw.decode(encoding)
            except (UnicodeDecodeError, LookupError):
                continue
        return raw.decode("utf-8", errors="replace")


def powershell_script(port: int = PORT) -> str:
    return (
        "[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false; "
        "$OutputEncoding = [Console]::OutputEncoding; "
        "$profiles = @(Get-NetConnectionProfile -ErrorAction SilentlyContinue | "
        "Select-Object Name, NetworkCategory); "
        "$rules = @(Get-NetFirewallPortFilter -Protocol TCP -ErrorAction SilentlyContinue | "
        "Where-Object { $_.LocalPort -eq '" + str(port) + "' } | ForEach-Object { "
        "Get-NetFirewallRule -AssociatedNetFirewallPortFilter $_ -ErrorAction SilentlyContinue } | "
        "Where-Object { $_.Enabled -eq 'True' -and $_.Direction -eq 'Inbound' -and $_.Action -eq 'Allow' } | "
        "ForEach-Object { $remote = (Get-NetFirewallAddressFilter -AssociatedNetFirewallRule $_).RemoteAddress; "
        "[PSCustomObject]@{ name=$_.DisplayName; port=" + str(port) + "; profiles=@($_.Profile); "
        "remote=($remote -join ','); direction='Inbound'; action='Allow'; enabled=$true } }); "
        "[PSCustomObject]@{ profiles=$profiles; rules=$rules } | ConvertTo-Json -Depth 5 -Compress"
    )


def parse_powershell_json(text: str, port: int = PORT) -> dict:
    payload = json.loads(text)
    profiles = payload.get("profiles") or []
    rules = payload.get("rules") or []
    if isinstance(profiles, dict):
        profiles = [profiles]
    if isinstance(rules, dict):
        rules = [rules]
    normalized_profiles = [{"name": item.get("Name", "") or item.get("name", ""),
                            "category": str(item.get("NetworkCategory", "") or item.get("category", ""))}
                           for item in profiles]
    normalized_rules = []
    for item in rules:
        profiles_value = item.get("profiles") or item.get("Profiles") or []
        if isinstance(profiles_value, str):
            profiles_value = [profiles_value]
        normalized_rules.append({
            "name": item.get("name") or item.get("Name") or "",
            "port": item.get("port") or port,
            "profiles": list(profiles_value),
            "remote": item.get("remote") or item.get("Remote") or "",
            "direction": item.get("direction") or "Inbound",
            "action": item.get("action") or "Allow",
            "enabled": item.get("enabled", True),
        })
    return assess(port, normalized_profiles, normalized_rules)


def _field_map(text: str) -> dict[str, str]:
    fields = {}
    for line in text.splitlines():
        cleaned = line.strip()
        if not cleaned:
            continue
        for separator in (":", "："):
            if separator in cleaned:
                key, value = cleaned.split(separator, 1)
                fields[key.strip().lower()] = value.strip()
                break
    return fields


def _first(fields: dict[str, str], names: tuple[str, ...]) -> str:
    for name in names:
        if name in fields:
            return fields[name]
    return ""


def parse_netsh(text: str, port: int = PORT) -> dict:
    """Fallback when the PowerShell cmdlets fail. Output may be GBK."""
    fields = _field_map(text)
    name = _first(fields, ("rule name", "规则名称"))
    if not name:
        return assess(port, [], [])
    enabled_text = _first(fields, ("enabled", "已启用")).lower()
    enabled = enabled_text in {"yes", "是"}
    direction_text = _first(fields, ("direction", "方向"))
    inbound = direction_text.lower() in {"in", "inbound"} or "入站" in direction_text
    profile_text = _first(fields, ("profiles", "profile", "配置文件"))
    profiles = []
    if "Private" in profile_text or "专用" in profile_text:
        profiles.append("Private")
    if "Public" in profile_text or "公用" in profile_text:
        profiles.append("Public")
    if "Domain" in profile_text or "域" in profile_text:
        profiles.append("Domain")
    remote_text = _first(fields, ("remoteip", "remote ip", "远程 ip", "远程ip"))
    if "LocalSubnet" in remote_text or "本地子网" in remote_text:
        remote = "LocalSubnet"
    else:
        remote = remote_text
    action_text = _first(fields, ("action", "操作"))
    action = "Allow" if action_text.lower() == "allow" or "允许" in action_text else action_text
    direction = "Inbound" if inbound else direction_text
    port_text = _first(fields, ("localport", "local port", "本地端口"))
    try:
        rule_port = int(port_text) if port_text.isdigit() else port
    except ValueError:
        rule_port = port
    rule = {"name": name, "port": rule_port, "profiles": profiles, "remote": remote,
            "direction": direction, "action": action, "enabled": enabled}
    return assess(port, [], [rule])


def probe_windows(port: int = PORT, runner=None) -> dict:
    """Query the firewall. runner(command) -> (returncode, stdout bytes) for tests."""
    import subprocess

    def default_runner(command):
        completed = subprocess.run(command, capture_output=True, timeout=8, check=False)
        return completed.returncode, completed.stdout or b""

    run = runner or default_runner
    try:
        code, stdout = run(["powershell.exe", "-NoProfile", "-Command", powershell_script(port)])
        text = decode_console(stdout).strip()
        if code == 0 and text:
            return parse_powershell_json(text, port)
    except (OSError, ValueError, json.JSONDecodeError):
        pass
    try:
        _code, stdout = run(["netsh", "advfirewall", "firewall", "show", "rule", f"name={RULE_NAME}"])
        return parse_netsh(decode_console(stdout), port)
    except (OSError, ValueError):
        return unknown_status()


def cached_status(loader=None, now=None, state=None, ttl: float = CACHE_SECONDS) -> dict:
    """Return firewall status without waiting. A slow probe fills the cache in the background."""
    if loader is None and sys.platform != "win32":
        return not_windows_status()
    clock = time.monotonic if now is None else now
    current = clock() if callable(clock) else clock
    box = _CACHE if state is None else state
    load = loader or probe_windows
    with _CACHE_LOCK:
        cached = box.get("value")
        fresh = cached is not None and current < box.get("expires", 0)
        if fresh:
            return cached
        if not box.get("running"):
            box["running"] = True

            def refresh():
                try:
                    value = load()
                except Exception:
                    value = unknown_status()
                finished = clock() if callable(clock) else clock
                with _CACHE_LOCK:
                    box["value"] = value
                    box["expires"] = finished + ttl
                    box["running"] = False

            thread = threading.Thread(target=refresh, daemon=True, name="catdiao-firewall")
            box["thread"] = thread
            thread.start()
        return cached if cached is not None else unknown_status()


def live_status() -> dict:
    if sys.platform != "win32":
        return not_windows_status()
    try:
        return probe_windows()
    except (OSError, ValueError, json.JSONDecodeError):
        return unknown_status()
