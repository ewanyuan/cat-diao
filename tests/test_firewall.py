"""Firewall status is a pure decision. Live Windows commands are not required."""
import importlib
from pathlib import Path
import sys
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

    def test_non_windows_status_does_not_claim_a_rule(self):
        if sys.platform == "win32":
            self.skipTest("this environment is Windows")
        status = firewall.live_status()
        self.assertEqual(status["state"], "not_windows")
        self.assertEqual(status["rules"], [])


if __name__ == "__main__":
    unittest.main()
