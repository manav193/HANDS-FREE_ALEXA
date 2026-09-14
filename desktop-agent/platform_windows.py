"""Windows implementation for the Hands-Free Alexa desktop agent."""

from __future__ import annotations

import os
import subprocess
import time
import webbrowser
from typing import Any

import pyautogui


APP_ALIASES = {
    "chrome": ["chrome.exe"],
    "google chrome": ["chrome.exe"],
    "edge": ["msedge.exe"],
    "notepad": ["notepad.exe"],
    "calculator": ["calc.exe"],
    "explorer": ["explorer.exe"],
    "file explorer": ["explorer.exe"],
}

MEDIA_KEYS = {
    "play_pause": "playpause",
    "next": "nexttrack",
    "previous": "prevtrack",
    "volume_up": "volumeup",
    "volume_down": "volumedown",
    "mute": "volumemute",
}


class WindowsController:
    def ping(self, args: dict[str, Any]) -> dict[str, str]:
        return {"pong": "windows"}

    def status(self, args: dict[str, Any]) -> dict[str, Any]:
        return {"platform": "windows", "ready": True, "pid": os.getpid()}

    def open_app(self, args: dict[str, Any]) -> dict[str, str]:
        name = str(args.get("name", "")).strip().lower()
        if name not in APP_ALIASES:
            raise ValueError("app is not allow-listed")
        subprocess.Popen(APP_ALIASES[name], shell=False)
        return {"opened": name}

    def open_url(self, args: dict[str, Any]) -> dict[str, str]:
        url = str(args.get("url", "")).strip()
        if not (url.startswith("https://") or url.startswith("http://")):
            raise ValueError("only http(s) URLs are allowed")
        webbrowser.open(url, new=2)
        return {"opened": url}

    def type_text(self, args: dict[str, Any]) -> dict[str, int]:
        text = str(args.get("text", ""))
        if len(text) > 2000:
            raise ValueError("text too long")
        pyautogui.write(text, interval=0.005)
        return {"characters": len(text)}

    def key(self, args: dict[str, Any]) -> dict[str, str]:
        key = str(args.get("key", "")).strip().lower()
        allowed = {"enter", "esc", "escape", "tab", "space", "backspace", "delete", "up", "down", "left", "right", "home", "end", "pageup", "pagedown"}
        allowed.update({f"f{i}" for i in range(1, 13)})
        if key not in allowed:
            raise ValueError("key is not allow-listed")
        pyautogui.press("esc" if key == "escape" else key)
        return {"key": key}

    def hotkey(self, args: dict[str, Any]) -> dict[str, Any]:
        keys = args.get("keys")
        if not isinstance(keys, list) or not 2 <= len(keys) <= 4:
            raise ValueError("hotkey requires 2-4 keys")
        keys = [str(k).lower() for k in keys]
        allowed = {"ctrl", "shift", "alt", "win", "command", "enter", "esc", "tab", "space", "c", "v", "x", "a", "z", "s", "f"}
        if any(k not in allowed for k in keys):
            raise ValueError("hotkey contains a non-allow-listed key")
        pyautogui.hotkey(*keys)
        return {"keys": keys}

    def media(self, args: dict[str, Any]) -> dict[str, str]:
        action = str(args.get("action", "")).strip().lower()
        key = MEDIA_KEYS.get(action)
        if key is None:
            raise ValueError("media action is not allow-listed")
        pyautogui.press(key)
        return {"action": action}

    def move_mouse(self, args: dict[str, Any]) -> dict[str, int]:
        x, y = int(args["x"]), int(args["y"])
        pyautogui.moveTo(x, y, duration=0.1)
        return {"x": x, "y": y}

    def click(self, args: dict[str, Any]) -> dict[str, str]:
        button = str(args.get("button", "left")).lower()
        if button not in {"left", "right", "middle"}:
            raise ValueError("invalid mouse button")
        pyautogui.click(button=button)
        return {"button": button}
