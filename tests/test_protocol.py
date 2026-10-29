"""DH pairing and request signatures. The token and confirm code never travel together."""
import importlib
import json
import os
from pathlib import Path
import sys
import unittest

os.environ.setdefault("CATDIAO_NEST_HOME", "/tmp/catdiao-protocol-tests")
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "cat-diao" / "scripts"))
protocol = importlib.import_module("catdiao_protocol")

TOKEN = "544f98d341f1e199c37a9dcc769e43ee4477e6eaa1f216234fd75e3d00ecbe0d"
CODE = "302041"


def public_for(private):
    return f"{pow(protocol.GENERATOR, private, protocol.PRIME):0{protocol.SECRET_BYTES * 2}x}"


class ProtocolChecks(unittest.TestCase):
    def test_published_vector(self):
        self.assertEqual(pow(protocol.GENERATOR, protocol.PRIME - 1, protocol.PRIME), 1)
        self.assertEqual(protocol.PRIME.bit_length(), 2048)
        pub3 = public_for(3)
        pub5 = public_for(5)
        self.assertTrue(pub3.endswith("0008"))
        self.assertTrue(pub5.endswith("0020"))
        shared = protocol.shared_secret("3", pub5)
        self.assertTrue(shared.hex().endswith("0000000000008000"))
        self.assertEqual(protocol.token_from_shared(shared), TOKEN)
        self.assertEqual(protocol.confirm_code(shared), CODE)
        self.assertEqual(protocol.derive("5", pub3), (TOKEN, CODE))

    def test_two_sides_match_and_a_third_key_does_not(self):
        left_private, left_public = protocol.generate_keypair()
        right_private, right_public = protocol.generate_keypair()
        other_private, _other_public = protocol.generate_keypair()
        left = protocol.derive(left_private, right_public)
        right = protocol.derive(right_private, left_public)
        self.assertEqual(left, right)
        self.assertNotEqual(protocol.derive(other_private, right_public)[1], left[1])

    def test_pair_message_does_not_carry_token_or_code(self):
        _private, public = protocol.generate_keypair()
        body = {"name": "pc", "public_key": public, "protocol": "dh-2048-v1"}
        encoded = json.dumps(body)
        self.assertNotIn("token", encoded)
        self.assertNotIn(CODE, encoded)
        self.assertNotIn("confirm", encoded)

    def test_signature_rejects_tamper_replay_expiry_and_body_mismatch(self):
        body = b'{"ok":true}'
        headers = protocol.sign_headers("POST", "/captures", TOKEN, body, now=1_700_000_000)
        window = protocol.NonceWindow()
        verdict = protocol.verify_request(
            "POST", "/captures", TOKEN, body, headers, now=1_700_000_000, window=window)
        self.assertTrue(verdict.ok)
        again = protocol.verify_request(
            "POST", "/captures", TOKEN, body, headers, now=1_700_000_000, window=window)
        self.assertEqual(again.reason, "replay")
        tampered = dict(headers)
        tampered["X-CatDiao-Sig"] = "ab" * 32
        self.assertEqual(protocol.verify_request(
            "POST", "/captures", TOKEN, body, tampered, now=1_700_000_000,
            window=protocol.NonceWindow()).reason, "signature")
        self.assertEqual(protocol.verify_request(
            "POST", "/captures", TOKEN, body, headers, now=1_700_000_000 + 61,
            window=protocol.NonceWindow()).reason, "expired")
        mismatched = protocol.verify_request(
            "POST", "/captures", TOKEN, b"other", headers, now=1_700_000_000,
            window=protocol.NonceWindow())
        self.assertEqual(mismatched.reason, "body")
        self.assertNotIn(TOKEN, "".join(headers.values()))


if __name__ == "__main__":
    unittest.main()
