"""Shared authenticated protocol for the Hands-Free Alexa desktop agents.

Transport: WebSocket over a trusted LAN.
Security: HMAC-SHA256 using a pre-shared token. No arbitrary shell execution.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import secrets
import time
from typing import Any, Dict

PROTOCOL_VERSION = 1
MAX_MESSAGE_BYTES = 64 * 1024
CLOCK_SKEW_SECONDS = 60


def canonical_json(payload: Dict[str, Any]) -> bytes:
    return json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def make_nonce() -> str:
    return secrets.token_urlsafe(24)


def sign_message(payload: Dict[str, Any], token: str) -> str:
    return hmac.new(token.encode("utf-8"), canonical_json(payload), hashlib.sha256).hexdigest()


def verify_message(payload: Dict[str, Any], signature: str, token: str) -> bool:
    expected = sign_message(payload, token)
    return hmac.compare_digest(expected, signature)


def make_request(command: str, args: Dict[str, Any], token: str) -> Dict[str, Any]:
    payload = {
        "v": PROTOCOL_VERSION,
        "type": "request",
        "id": secrets.token_hex(12),
        "ts": int(time.time()),
        "nonce": make_nonce(),
        "command": command,
        "args": args,
    }
    return {"payload": payload, "sig": sign_message(payload, token)}


def validate_envelope(envelope: Dict[str, Any], token: str) -> Dict[str, Any]:
    if not isinstance(envelope, dict):
        raise ValueError("invalid envelope")
    payload = envelope.get("payload")
    signature = envelope.get("sig")
    if not isinstance(payload, dict) or not isinstance(signature, str):
        raise ValueError("invalid envelope fields")
    if payload.get("v") != PROTOCOL_VERSION or payload.get("type") != "request":
        raise ValueError("unsupported protocol")
    timestamp = payload.get("ts")
    if not isinstance(timestamp, int) or abs(int(time.time()) - timestamp) > CLOCK_SKEW_SECONDS:
        raise ValueError("stale request")
    if not verify_message(payload, signature, token):
        raise ValueError("authentication failed")
    return payload


def make_response(request_id: str, ok: bool, result: Any = None, error: str | None = None) -> Dict[str, Any]:
    return {
        "v": PROTOCOL_VERSION,
        "type": "response",
        "id": request_id,
        "ok": ok,
        "result": result,
        "error": error,
    }
