# -*- coding: utf-8 -*-
"""Wake-time discovery timeouts must outlast a slow OPPO screen-on reply."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class WakeHelloTimeoutChecks(unittest.TestCase):
    def test_phone_bridge_keeps_long_hello_on_known_paths(self):
        phone = (ROOT / "cat-diao" / "scripts" / "手机直达.py").read_text(encoding="utf-8")
        timeout = float(re.search(r"SAVED_HELLO_TIMEOUT = ([0-9.]+)", phone).group(1))
        self.assertGreaterEqual(timeout, 4.0)
        # Saved address, mDNS candidates, post-scan verify, and final pair hello.
        self.assertGreaterEqual(phone.count("hello_timeout=SAVED_HELLO_TIMEOUT"), 3)
        self.assertIn("timeout=SAVED_HELLO_TIMEOUT", phone)
        # Must not fall back to a bare 2.5s candidate budget anymore.
        self.assertNotIn("time.monotonic() + 2.5)", phone)

    def test_receiver_uses_wake_timeout_not_three_seconds(self):
        collector = (ROOT / "cat-diao" / "scripts" / "收藏同步.py").read_text(encoding="utf-8")
        self.assertIn("SAVED_HELLO_TIMEOUT", collector)
        self.assertNotIn("timeout=3, deadline=deadline)", collector)


if __name__ == "__main__":
    unittest.main()
