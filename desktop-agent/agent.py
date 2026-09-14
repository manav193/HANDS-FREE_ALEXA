"""LAN desktop agent for the Hands-Free Alexa project.

Run with:
    python agent.py --token YOUR_LONG_RANDOM_TOKEN

The agent deliberately exposes an allow-listed command set. It never executes
arbitrary shell commands received from the tablet.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
from typing import Any

import websockets

from protocol import MAX_MESSAGE_BYTES, make_response, validate_envelope

LOG = logging.getLogger("handsfree-agent")


class DesktopAgent:
    def __init__(self, token: str):
        self.token = token
        self.platform = self._load_platform()
        self.seen_nonces: set[str] = set()

    @staticmethod
    def _load_platform():
        import platform
        if platform.system() == "Windows":
            from platform_windows import WindowsController
            return WindowsController()
        if platform.system() == "Darwin":
            from platform_macos import MacController
            return MacController()
        raise RuntimeError("Only Windows and macOS are supported")

    async def dispatch(self, command: str, args: dict[str, Any]) -> Any:
        allowed = {
            "ping": self.platform.ping,
            "status": self.platform.status,
            "open_app": self.platform.open_app,
            "open_url": self.platform.open_url,
            "type_text": self.platform.type_text,
            "key": self.platform.key,
            "hotkey": self.platform.hotkey,
            "media": self.platform.media,
            "move_mouse": self.platform.move_mouse,
            "click": self.platform.click,
        }
        handler = allowed.get(command)
        if handler is None:
            raise ValueError(f"command not allowed: {command}")
        return await asyncio.to_thread(handler, args)

    async def handle(self, websocket):
        peer = websocket.remote_address
        LOG.info("client connected: %s", peer)
        try:
            async for raw in websocket:
                if len(raw.encode("utf-8")) > MAX_MESSAGE_BYTES:
                    await websocket.close(code=1009, reason="message too large")
                    return
                try:
                    envelope = json.loads(raw)
                    payload = validate_envelope(envelope, self.token)
                    nonce = payload["nonce"]
                    if nonce in self.seen_nonces:
                        raise ValueError("replayed request")
                    self.seen_nonces.add(nonce)
                    # Keep replay cache bounded.
                    if len(self.seen_nonces) > 2048:
                        self.seen_nonces.clear()

                    result = await self.dispatch(payload["command"], payload.get("args") or {})
                    response = make_response(payload["id"], True, result=result)
                except Exception as exc:
                    LOG.warning("request rejected: %s", exc)
                    request_id = payload.get("id", "unknown") if "payload" in locals() and isinstance(payload, dict) else "unknown"
                    response = make_response(request_id, False, error=str(exc))
                await websocket.send(json.dumps(response, ensure_ascii=False, separators=(",", ":")))
        finally:
            LOG.info("client disconnected: %s", peer)


def main() -> None:
    parser = argparse.ArgumentParser(description="Hands-Free Alexa LAN desktop agent")
    parser.add_argument("--token", required=True, help="shared secret; use a long random value")
    parser.add_argument("--host", default="0.0.0.0", help="bind address (default: all LAN interfaces)")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--log-level", default="INFO")
    args = parser.parse_args()

    if len(args.token) < 32:
        raise SystemExit("Token must be at least 32 characters")
    logging.basicConfig(level=getattr(logging, args.log_level.upper(), logging.INFO), format="%(asctime)s %(levelname)s %(message)s")

    agent = DesktopAgent(args.token)

    async def run() -> None:
        async with websockets.serve(
            agent.handle,
            args.host,
            args.port,
            max_size=MAX_MESSAGE_BYTES,
            ping_interval=20,
            ping_timeout=20,
            close_timeout=5,
        ):
            LOG.info("Hands-Free Alexa agent listening on %s:%d", args.host, args.port)
            await asyncio.Future()

    asyncio.run(run())


if __name__ == "__main__":
    main()
