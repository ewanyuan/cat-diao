"""Decide whether the nest receiver has a private-network firewall rule.

Windows setup creates the rule. This module only reports status and can be
tested without touching a live firewall.
"""

from __future__ import annotations

import json
import sys

from catdiao_version import NEST_PORT

RULE_NAME = "猫叼小窝"
PORT = NEST_PORT


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


def live_status() -> dict:
    if sys.platform != "win32":
        return {"state": "not_windows", "port": PORT, "public_networks": [], "private_networks": [],
                "needs_private_profile": False, "rules": []}
    try:
        import subprocess
        script = (
            "$profiles = @(Get-NetConnectionProfile | Select-Object Name, NetworkCategory); "
            "$rules = @(Get-NetFirewallPortFilter -Protocol TCP | "
            "Where-Object { $_.LocalPort -eq '" + str(PORT) + "' } | ForEach-Object { "
            "Get-NetFirewallRule -AssociatedNetFirewallPortFilter $_ } | "
            "Where-Object { $_.Enabled -eq 'True' -and $_.Direction -eq 'Inbound' -and $_.Action -eq 'Allow' } | "
            "ForEach-Object { $remote = (Get-NetFirewallAddressFilter -AssociatedNetFirewallRule $_).RemoteAddress; "
            "[PSCustomObject]@{ name=$_.DisplayName; port=" + str(PORT) + "; profiles=@($_.Profile); "
            "remote=($remote -join ','); direction='Inbound'; action='Allow'; enabled=$true } }); "
            "[PSCustomObject]@{ profiles=$profiles; rules=$rules } | ConvertTo-Json -Depth 5 -Compress"
        )
        completed = subprocess.run(
            ["powershell.exe", "-NoProfile", "-Command", script],
            capture_output=True, text=True, timeout=8, check=False)
        if completed.returncode != 0 or not completed.stdout.strip():
            return {"state": "unknown", "port": PORT, "public_networks": [], "private_networks": [],
                    "needs_private_profile": False, "rules": []}
        payload = json.loads(completed.stdout)
        profiles = payload.get("profiles") or []
        rules = payload.get("rules") or []
        if isinstance(profiles, dict):
            profiles = [profiles]
        if isinstance(rules, dict):
            rules = [rules]
        normalized_profiles = [{"name": item.get("Name", ""),
                                "category": str(item.get("NetworkCategory", ""))} for item in profiles]
        normalized_rules = []
        for item in rules:
            profiles_value = item.get("profiles") or item.get("Profiles") or []
            if isinstance(profiles_value, str):
                profiles_value = [profiles_value]
            normalized_rules.append({
                "name": item.get("name") or item.get("Name") or "",
                "port": PORT,
                "profiles": list(profiles_value),
                "remote": item.get("remote") or item.get("Remote") or "",
                "direction": "Inbound",
                "action": "Allow",
                "enabled": True,
            })
        return assess(PORT, normalized_profiles, normalized_rules)
    except (OSError, ValueError, json.JSONDecodeError):
        return {"state": "unknown", "port": PORT, "public_networks": [], "private_networks": [],
                "needs_private_profile": False, "rules": []}
