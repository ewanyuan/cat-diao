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


# Get-NetConnectionProfile.NetworkCategory on Windows PowerShell 5.1.
_CATEGORY_NUMBERS = {0: "Public", 1: "Private", 2: "Domain"}
_CATEGORY_NAMES = {
    "public": "Public", "公用": "Public",
    "private": "Private", "专用": "Private",
    "domain": "Domain", "domainauthenticated": "Domain", "域": "Domain",
}
# Get-NetFirewallRule.Profile is a bitmask: Domain=1, Private=2, Public=4. 0 means Any.
_PROFILE_BITS = ((1, "Domain"), (2, "Private"), (4, "Public"))


def normalize_category(value) -> str:
    """Map a connection category. 0 is Public; do not treat 0 as missing."""
    if value is None or isinstance(value, bool):
        return ""
    if isinstance(value, int):
        return _CATEGORY_NUMBERS.get(value, "")
    text = str(value).strip()
    if not text:
        return ""
    if text.lstrip("-").isdigit():
        return _CATEGORY_NUMBERS.get(int(text), "")
    return _CATEGORY_NAMES.get(text.lower(), "")


def normalize_rule_profiles(value) -> list[str]:
    """Map a firewall profile bitmask or name. 2 is Private, which is not Domain."""
    if value is None or isinstance(value, bool):
        return []
    if isinstance(value, (list, tuple)):
        names: list[str] = []
        for item in value:
            for name in normalize_rule_profiles(item):
                if name not in names:
                    names.append(name)
        return names
    if isinstance(value, int) or (isinstance(value, str) and str(value).strip().lstrip("-").isdigit()):
        number = int(value)
        if number == 0:
            return ["Domain", "Private", "Public"]
        return [name for bit, name in _PROFILE_BITS if number & bit]
    text = str(value).strip()
    if not text:
        return []
    pieces = [piece.strip() for piece in text.replace(";", ",").split(",") if piece.strip()]
    if len(pieces) > 1:
        names = []
        for piece in pieces:
            for name in normalize_rule_profiles(piece):
                if name not in names:
                    names.append(name)
        return names
    lowered = text.lower()
    if lowered == "any":
        return ["Domain", "Private", "Public"]
    names = []
    for token, name in (("domain", "Domain"), ("private", "Private"), ("public", "Public")):
        if token in lowered:
            names.append(name)
    if "域" in text and "Domain" not in names:
        names.append("Domain")
    if "专用" in text and "Private" not in names:
        names.append("Private")
    if "公用" in text and "Public" not in names:
        names.append("Public")
    return names


def normalize_direction(value) -> str:
    if isinstance(value, int) or (isinstance(value, str) and str(value).strip().lstrip("-").isdigit()):
        return {1: "Inbound", 2: "Outbound"}.get(int(value), "")
    text = str(value or "").strip()
    if text.lower() in {"in", "inbound"} or "入站" in text:
        return "Inbound"
    return text


def normalize_action(value) -> str:
    if isinstance(value, int) or (isinstance(value, str) and str(value).strip().lstrip("-").isdigit()):
        return {2: "Allow", 4: "Block"}.get(int(value), "")
    text = str(value or "").strip()
    if text.lower() == "allow" or "允许" in text:
        return "Allow"
    return text


def _enabled(value) -> bool:
    if isinstance(value, str):
        return value.strip().lower() in {"true", "yes", "1", "是"}
    return bool(value)


def _local_subnet(remote) -> bool:
    text = str(remote or "").strip()
    if not text:
        return False
    if "本地子网" in text:
        return True
    parts = [part.strip().lower() for part in text.split(",") if part.strip()]
    return bool(parts) and all(part == "localsubnet" for part in parts)


def _public_hint(public_names: list[str]) -> str:
    shown = "、".join(name for name in public_names if name) or "当前网络"
    return (f"当前网络「{shown}」是公用网络。猫叼的防火墙规则只对专用网络放行，手机现在连不上。"
            "请打开 Windows 设置 → 网络和 Internet，点开这块网络，把网络配置文件改成「专用」。")


def assess(port: int, profiles: list[dict], rules: list[dict]) -> dict:
    """profiles: [{name, category}] category is Public, Private, or Domain.
    rules: [{name, port, profiles, remote, direction, action, enabled}]
    Profile and category accept Windows PowerShell 5.1 numbers and names.
    """
    public = []
    private = []
    for item in profiles:
        category = normalize_category(item.get("category"))
        name = item.get("name") or ""
        if category == "Public":
            public.append(name)
        elif category == "Private":
            private.append(name)
    matching = []
    covers_public = False
    private_only = False
    for rule in rules:
        if not _enabled(rule.get("enabled", True)):
            continue
        try:
            rule_port = int(rule.get("port", 0))
        except (TypeError, ValueError):
            continue
        names = normalize_rule_profiles(rule.get("profiles"))
        if (rule_port == port and normalize_direction(rule.get("direction")) == "Inbound"
                and normalize_action(rule.get("action")) == "Allow" and "Private" in names
                and _local_subnet(rule.get("remote"))):
            matching.append(rule.get("name") or RULE_NAME)
            if "Public" in names:
                covers_public = True
            else:
                private_only = True
    if matching:
        state = "ok"
    else:
        state = "missing"
    hint = ""
    if public and matching and private_only and not covers_public:
        hint = _public_hint(public)
    return {
        "state": state,
        "port": port,
        "public_networks": public,
        "private_networks": private,
        "needs_private_profile": bool(public) and not private and not covers_public,
        "rules": matching,
        "hint": hint,
    }


def unknown_status() -> dict:
    return {"state": "unknown", "port": PORT, "public_networks": [], "private_networks": [],
            "needs_private_profile": False, "rules": [], "hint": ""}


def not_windows_status() -> dict:
    return {"state": "not_windows", "port": PORT, "public_networks": [], "private_networks": [],
            "needs_private_profile": False, "rules": [], "hint": ""}


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
    # Windows PowerShell 5.1 ConvertTo-Json emits enums as numbers. Name them here too.
    return (
        "[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false; "
        "$OutputEncoding = [Console]::OutputEncoding; "
        "function Convert-CatDiaoCategory([object]$value) { switch ([string]$value) { "
        "'0' { 'Public' } '1' { 'Private' } '2' { 'Domain' } "
        "'Public' { 'Public' } 'Private' { 'Private' } 'DomainAuthenticated' { 'Domain' } 'Domain' { 'Domain' } "
        "default { [string]$value } } }; "
        "function Convert-CatDiaoProfiles([object]$value) { "
        "$names = New-Object System.Collections.Generic.List[string]; "
        "foreach ($item in @($value)) { $text = [string]$item; $number = 0; "
        "if ([int]::TryParse($text, [ref]$number)) { "
        "if ($number -eq 0) { foreach ($name in @('Domain','Private','Public')) { $names.Add($name) } } "
        "else { if (($number -band 1) -ne 0) { $names.Add('Domain') }; "
        "if (($number -band 2) -ne 0) { $names.Add('Private') }; "
        "if (($number -band 4) -ne 0) { $names.Add('Public') } } } "
        "else { if ($text -match 'Domain') { $names.Add('Domain') }; "
        "if ($text -match 'Private') { $names.Add('Private') }; "
        "if ($text -match 'Public') { $names.Add('Public') } } }; "
        "@($names | Select-Object -Unique) }; "
        "$profiles = @(Get-NetConnectionProfile -ErrorAction SilentlyContinue | ForEach-Object { "
        "[PSCustomObject]@{ Name=$_.Name; NetworkCategory=(Convert-CatDiaoCategory $_.NetworkCategory) } }); "
        "$rules = @(Get-NetFirewallPortFilter -Protocol TCP -ErrorAction SilentlyContinue | "
        "Where-Object { $_.LocalPort -eq '" + str(port) + "' } | ForEach-Object { "
        "Get-NetFirewallRule -AssociatedNetFirewallPortFilter $_ -ErrorAction SilentlyContinue } | "
        "Where-Object { $enabled = [string]$_.Enabled; $direction = $_.Direction; $action = $_.Action; "
        "(($enabled -eq 'True') -or ($enabled -eq '1')) -and "
        "(($direction -eq 1) -or ([string]$direction -eq 'Inbound')) -and "
        "(($action -eq 2) -or ([string]$action -eq 'Allow')) } | "
        "ForEach-Object { $remote = (Get-NetFirewallAddressFilter -AssociatedNetFirewallRule $_).RemoteAddress; "
        "[PSCustomObject]@{ name=$_.DisplayName; port=" + str(port) + "; "
        "profiles=@(Convert-CatDiaoProfiles $_.Profile); remote=($remote -join ','); "
        "direction='Inbound'; action='Allow'; enabled=$true } }); "
        "[PSCustomObject]@{ profiles=$profiles; rules=$rules } | ConvertTo-Json -Depth 6 -Compress"
    )


def parse_powershell_json(text: str, port: int = PORT) -> dict:
    payload = json.loads(text)
    profiles = payload.get("profiles") or []
    rules = payload.get("rules") or []
    if isinstance(profiles, dict):
        profiles = [profiles]
    if isinstance(rules, dict):
        rules = [rules]
    normalized_profiles = []
    for item in profiles:
        if not isinstance(item, dict):
            continue
        if "NetworkCategory" in item and item.get("NetworkCategory") is not None:
            category = item.get("NetworkCategory")
        elif "category" in item and item.get("category") is not None:
            category = item.get("category")
        else:
            category = ""
        normalized_profiles.append({"name": item.get("Name", "") or item.get("name", ""),
                                    "category": category})
    normalized_rules = []
    for item in rules:
        if not isinstance(item, dict):
            continue
        if "profiles" in item and item.get("profiles") is not None:
            profiles_value = item.get("profiles")
        elif "Profiles" in item and item.get("Profiles") is not None:
            profiles_value = item.get("Profiles")
        elif "Profile" in item and item.get("Profile") is not None:
            profiles_value = item.get("Profile")
        else:
            profiles_value = []
        normalized_rules.append({
            "name": item.get("name") or item.get("Name") or item.get("DisplayName") or "",
            "port": item.get("port") if item.get("port") is not None else port,
            "profiles": profiles_value,
            "remote": item.get("remote") or item.get("Remote") or item.get("RemoteAddress") or "",
            "direction": item.get("direction") or item.get("Direction") or "Inbound",
            "action": item.get("action") or item.get("Action") or "Allow",
            "enabled": item.get("enabled", item.get("Enabled", True)),
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
