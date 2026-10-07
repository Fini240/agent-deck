"""Regenerates core/push/src/test/resources/push-fixture.json.

Uses Python `cryptography` AESGCM, an implementation independent of the Kotlin decryptor,
with a fixed key and nonces so the vectors are deterministic. Not used at runtime.
"""
import base64, json, sys
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

key = bytes(range(32))
cases = [
    ("progress-determinate", bytes.fromhex("000102030405060708090a0b"), {
        "eventId": "evt-0001", "type": "progress", "sessionId": "s-claude-1",
        "title": "Claude Code is working", "body": "Running tests", "agent": "claude",
        "timestamp": "2026-10-06T21:00:00Z", "progress": {"current": 3, "total": 10, "unit": "tests"},
        "stage": "Testing"}),
    ("progress-unknown", bytes.fromhex("0c0d0e0f1011121314151617"), {
        "eventId": "evt-0002", "type": "progress", "sessionId": "s-codex-1",
        "title": "Codex is working", "body": "Installing dependencies", "agent": "codex",
        "timestamp": "2026-10-06T21:00:05Z", "progress": None, "stage": "Install"}),
    ("completed-unicode", bytes.fromhex("18191a1b1c1d1e1f20212223"), {
        "eventId": "evt-0003", "type": "completed", "sessionId": "s-claude-1",
        "title": "Fertig ✓", "body": "Alle Tests grün – 10/10", "agent": "claude",
        "timestamp": "2026-10-06T21:01:00Z", "progress": None, "stage": None}),
]
out = {"keyBase64": base64.b64encode(key).decode(), "cases": []}
for name, nonce, payload in cases:
    pt = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode()
    ct = AESGCM(key).encrypt(nonce, pt, None)
    out["cases"].append({"name": name, "data": {"v": "1", "nonce": base64.b64encode(nonce).decode(),
                         "ciphertext": base64.b64encode(ct).decode()}, "plaintext": pt.decode()})
# Tampered: flip one ciphertext bit of case 0; must fail authentication.
bad = dict(out["cases"][0]["data"]); raw = bytearray(base64.b64decode(bad["ciphertext"])); raw[0] ^= 1
bad["ciphertext"] = base64.b64encode(bytes(raw)).decode()
out["tampered"] = bad
json.dump(out, open(sys.argv[1], "w"), indent=2, ensure_ascii=False)
