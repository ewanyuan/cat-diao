"""Firewall status is a pure decision. Live Windows commands are not required."""
import importlib
import json
from pathlib import Path
import sys
import time
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "cat-diao" / "scripts"))
firewall = importlib.import_module("firewall_rule")


def rule(name="猫叼小窝", port=8793, remote="LocalSubnet", profiles=None, enabled=True):
    return {"name": name, "port": port, "profiles": profiles or ["Private"],
            "remote": remote, "direction": "Inbound", "action": "Allow", "enabled": enabled}


class FirewallChecks(unittest.TestCase):
    def test_missing_ok_and_public_network(self):
        missing = firewall.assess(8793, [{"name": "Home", "category": "Private"}], [])
        self.assertEqual(missing["state"], "missing")
        self.assertFalse(missing["needs_private_profile"])
        ready = firewall.assess(8793, [{"name": "Home", "category": "Private"}], [rule()])
        self.assertEqual(ready["state"], "ok")
        public = firewall.assess(8793, [{"name": "Cafe", "category": "Public"}], [])
        self.assertEqual(public["state"], "missing")
        self.assertTrue(public["needs_private_profile"])
        self.assertEqual(public["public_networks"], ["Cafe"])
        wrong = firewall.assess(8793, [], [rule(remote="Any")])
        self.assertEqual(wrong["state"], "missing")

    def test_gbk_console_bytes_parse_and_netsh_fallback(self):
        payload = {
            "profiles": [{"Name": "以太网", "NetworkCategory": "Private"}],
            "rules": [rule()],
        }
        raw = json.dumps(payload, ensure_ascii=False).encode("gbk")
        self.assertIn("以太网", firewall.decode_console(raw))
        parsed = firewall.parse_powershell_json(firewall.decode_console(raw))
        self.assertEqual(parsed["state"], "ok")
        self.assertEqual(parsed["private_networks"], ["以太网"])
        self.assertIsInstance(firewall.decode_console(b"\xff"), str)
        self.assertIn("OutputEncoding", firewall.powershell_script())
        self.assertIn("UTF8Encoding", firewall.powershell_script())

        listing = (
            "规则名称:                             猫叼小窝\n"
            "已启用:                               是\n"
            "方向:                                 入站\n"
            "配置文件:                             专用\n"
            "本地端口:                             8793\n"
            "远程 IP:                              本地子网\n"
            "操作:                                 允许\n"
        )

        def runner(command):
            if command[0] == "powershell.exe":
                return 1, "失败".encode("gbk")
            return 0, listing.encode("gbk")

        status = firewall.probe_windows(runner=runner)
        self.assertEqual(status["state"], "ok")
        self.assertEqual(status["rules"], ["猫叼小窝"])

    def test_cached_status_does_not_wait_for_a_slow_probe(self):
        state = {"expires": 0.0, "value": None, "running": False, "thread": None}
        calls = {"n": 0}
        gate = __import__("threading").Event()

        def loader():
            calls["n"] += 1
            gate.wait(2)
            return firewall.assess(8793, [{"name": "以太网", "category": "Private"}], [rule()])

        started = time.monotonic()
        first = firewall.cached_status(loader=loader, state=state)
        self.assertLess(time.monotonic() - started, 0.3)
        self.assertEqual(first["state"], "unknown")
        gate.set()
        state["thread"].join(2)
        second = firewall.cached_status(loader=loader, state=state)
        third = firewall.cached_status(loader=loader, state=state)
        self.assertEqual(second["state"], "ok")
        self.assertEqual(third["rules"], ["猫叼小窝"])
        self.assertEqual(calls["n"], 1)

    def test_non_windows_status_does_not_claim_a_rule(self):
        if sys.platform == "win32":
            self.skipTest("this environment is Windows")
        status = firewall.live_status()
        self.assertEqual(status["state"], "not_windows")
        self.assertEqual(status["rules"], [])


if __name__ == "__main__":
    unittest.main()
