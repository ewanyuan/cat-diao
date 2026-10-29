"""DH pairing and per-request HMAC. Tokens never travel on the network."""

from __future__ import annotations

import hashlib
import hmac
import secrets
import time
from dataclasses import dataclass

# RFC 3526 2048-bit MODP group (group 14). The generator is 2.
_PRIME_HEX = (
    "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1"
    "29024E088A67CC74020BBEA63B139B22514A08798E3404DD"
    "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245"
    "E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED"
    "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3D"
    "C2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F"
    "83655D23DCA3AD961C62F356208552BB9ED529077096966D"
    "670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B"
    "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9"
    "DE2BCBF6955817183995497CEA956AE515D2261898FA0510"
    "15728E5A8AACAA68FFFFFFFFFFFFFFFF"
)
PRIME = int(_PRIME_HEX, 16)
GENERATOR = 2
SECRET_BYTES = 256
WINDOW_SECONDS = 60
NONCE_HEX_LENGTH = 32


def body_hash(body: bytes | None) -> str:
    return hashlib.sha256(body or b"").hexdigest()


def valid_public(value: str) -> bool:
    if not isinstance(value, str) or len(value) != SECRET_BYTES * 2:
        return False
    if any(char not in "0123456789abcdef" for char in value):
        return False
    number = int(value, 16)
    return 2 <= number <= PRIME - 2


def generate_keypair() -> tuple[str, str]:
    secret = secrets.randbits(256)
    if secret < 2:
        secret = 2
    public = pow(GENERATOR, secret, PRIME)
    return f"{secret:064x}", f"{public:0{SECRET_BYTES * 2}x}"


def shared_secret(private_hex: str, peer_public_hex: str) -> bytes:
    if not valid_public(peer_public_hex):
        raise ValueError("peer public key rejected")
    try:
        private = int(private_hex, 16)
    except ValueError as error:
        raise ValueError("private key rejected") from error
    if private < 2 or private >= PRIME - 1:
        raise ValueError("private key rejected")
    shared = pow(int(peer_public_hex, 16), private, PRIME)
    if shared < 2:
        raise ValueError("shared secret rejected")
    return shared.to_bytes(SECRET_BYTES, "big")


def token_from_shared(shared: bytes) -> str:
    return hashlib.sha256(b"catdiao-token-v1\n" + shared).hexdigest()


def confirm_code(shared: bytes) -> str:
    digest = hashlib.sha256(b"catdiao-confirm-v1\n" + shared).digest()
    return f"{int.from_bytes(digest[:4], 'big') % 1_000_000:06d}"


def derive(private_hex: str, peer_public_hex: str) -> tuple[str, str]:
    shared = shared_secret(private_hex, peer_public_hex)
    return token_from_shared(shared), confirm_code(shared)


def canonical_request(method: str, path: str, timestamp: str, nonce: str, digest: str) -> str:
    return "\n".join(["request", method.upper(), path, timestamp, nonce, digest])


def canonical_response(nonce: str, status: int, digest: str) -> str:
    return "\n".join(["response", nonce, str(int(status)), digest])


def _hmac(token: str, text: str) -> str:
    return hmac.new(token.encode("utf-8"), text.encode("utf-8"), hashlib.sha256).hexdigest()


def sign_headers(method: str, path: str, token: str, body: bytes | None = None,
                 now: float | None = None, nonce: str | None = None,
                 digest: str | None = None) -> dict[str, str]:
    if not token:
        raise ValueError("missing token")
    digest = digest or body_hash(body)
    timestamp = str(int(time.time() if now is None else now))
    nonce = nonce or secrets.token_hex(16)
    if len(nonce) != NONCE_HEX_LENGTH or any(char not in "0123456789abcdef" for char in nonce):
        raise ValueError("nonce rejected")
    signature = _hmac(token, canonical_request(method, path, timestamp, nonce, digest))
    return {
        "X-CatDiao-Ts": timestamp,
        "X-CatDiao-Nonce": nonce,
        "X-CatDiao-Body": digest,
        "X-CatDiao-Sig": signature,
    }


def sign_response(token: str, nonce: str, status: int, body: bytes | None) -> str:
    return _hmac(token, canonical_response(nonce, status, body_hash(body)))


@dataclass
class Verdict:
    ok: bool
    reason: str = ""
    nonce: str = ""
    body_hash: str = ""


class NonceWindow:
    def __init__(self, limit: int = 4096):
        self.seen: dict[str, int] = {}
        self.limit = limit

    def remember(self, nonce: str, now: int) -> bool:
        expired = [key for key, until in self.seen.items() if until < now]
        for key in expired:
            del self.seen[key]
        if nonce in self.seen:
            return False
        if len(self.seen) >= self.limit:
            oldest = min(self.seen, key=self.seen.get)
            del self.seen[oldest]
        self.seen[nonce] = now + WINDOW_SECONDS
        return True


def _header(headers, name: str) -> str:
    if headers is None:
        return ""
    getter = getattr(headers, "get", None)
    if getter is None:
        return ""
    value = getter(name)
    if value is None:
        value = getter(name.lower())
    return "" if value is None else str(value).strip()


_WINDOW = NonceWindow()


def verify_request(method: str, path: str, token: str, body: bytes | None, headers,
                   now: float | None = None, window: NonceWindow | None = None,
                   check_body: bool = True) -> Verdict:
    """Verify a signed request. Pass check_body=False to authenticate before reading a stream."""
    if not token:
        return Verdict(False, "unpaired")
    timestamp_text = _header(headers, "X-CatDiao-Ts")
    nonce = _header(headers, "X-CatDiao-Nonce").lower()
    digest = _header(headers, "X-CatDiao-Body").lower()
    supplied = _header(headers, "X-CatDiao-Sig").lower()
    if (not timestamp_text.isdigit() or len(nonce) != NONCE_HEX_LENGTH or len(digest) != 64
            or len(supplied) != 64):
        return Verdict(False, "malformed")
    if any(char not in "0123456789abcdef" for char in nonce + digest + supplied):
        return Verdict(False, "malformed")
    now_s = int(time.time() if now is None else now)
    timestamp = int(timestamp_text)
    if abs(now_s - timestamp) > WINDOW_SECONDS:
        return Verdict(False, "expired")
    expected = _hmac(token, canonical_request(method, path, timestamp_text, nonce, digest))
    if not hmac.compare_digest(expected, supplied):
        return Verdict(False, "signature")
    store = window if window is not None else _WINDOW
    if not store.remember(nonce, now_s):
        return Verdict(False, "replay")
    if check_body and body_hash(body) != digest:
        return Verdict(False, "body", nonce, digest)
    return Verdict(True, "", nonce, digest)


def verify_response_digest(token: str, nonce: str, status: int, digest: str, signature: str) -> bool:
    if not token or not nonce or not signature or len(digest) != 64:
        return False
    expected = _hmac(token, canonical_response(nonce, status, digest))
    supplied = signature.strip().lower()
    if len(supplied) != len(expected):
        return False
    return hmac.compare_digest(expected, supplied)


def verify_response(token: str, nonce: str, status: int, body: bytes | None, signature: str) -> bool:
    return verify_response_digest(token, nonce, status, body_hash(body), signature)
