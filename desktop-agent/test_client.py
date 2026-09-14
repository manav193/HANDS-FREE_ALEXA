"""Manual LAN smoke-test client.

Usage:
    python test_client.py --host 192.168.1.20 --token YOUR_TOKEN ping
    python test_client.py --host 192.168.1.20 --token YOUR_TOKEN open_app --name chrome

This is intentionally separate from the Android app. It lets us validate the
agent/protocol while the tablet integration is still being developed.
"""

from __future__ import annotations

import argparse
import asyncio
import json

import websockets

from protocol import make_request


async def send(host: str, port: int, token: str, command: str, args: dict) -> None:
    uri = "ws://{}:{}".format(host, port)
    async with websockets.connect(uri, max_size=64 * 1024, open_timeout=5, close_timeout=5) as ws:
        await ws.send(json.dumps(make_request(command, args), ensure_ascii=False, separators=(",", ":")))
        print(json.dumps(json.loads(await ws.recv()), indent=2, ensure_ascii=False))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--token", required=True)
    parser.add_argument("command", choices=["ping", "status", "open_app", "open_url", "type_text", "key", "hotkey", "media", "move_mouse", "click"])
    parser.add_argument("--name")
    parser.add_argument("--url")
    parser.add_argument("--text")
    parser.add_argument("--key")
    parser.add_argument("--keys", nargs="+")
    parser.add_argument("--action")
    parser.add_argument("--x", type=int)
    parser.add_argument("--y", type=int)
    parser.add_argument("--button", default="left")
    args = parser.parse_args()

    payload = {k: v for k, v in {
        "name": args.name,
        "url": args.url,
        "text": args.text,
        "key": args.key,
        "keys": args.keys,
        "action": args.action,
        "x": args.x,
        "y": args.y,
        "button": args.button,
    }.items() if v is not None}
    asyncio.run(send(args.host, args.port, args.token, args.command, payload))


if __name__ == "__main__":
    main()
