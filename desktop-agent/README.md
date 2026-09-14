# Laptop Integration Agent

Standalone LAN agent for the Hands-Free Alexa project. The Android tablet will eventually send approved voice commands to this agent; the agent then performs a small, explicit allow-listed action on Windows or macOS.

## What is implemented now

- WebSocket transport on TCP `8765`.
- HMAC-SHA256 authentication with a per-device shared token.
- Timestamp validation and one-time nonce replay protection.
- Maximum message size limit.
- No arbitrary shell execution.
- Windows and macOS controllers behind the same command protocol.
- Manual `test_client.py` so the desktop side can be tested before Android integration.

## Supported commands

| Command | Arguments | Purpose |
|---|---|---|
| `ping` | none | Connectivity test |
| `status` | none | Agent status/platform |
| `open_app` | `name` | Open an allow-listed app |
| `open_url` | `url` | Open an HTTP(S) URL |
| `type_text` | `text` | Type up to 2000 characters |
| `key` | `key` | Press an allow-listed key |
| `hotkey` | `keys[]` | Press a 2–4 key shortcut |
| `media` | `action` | Play/pause, next, previous, volume, mute |
| `move_mouse` | `x`, `y` | Move pointer |
| `click` | `button` | Left/right/middle click |

Destructive operations such as shutdown, reboot, deletion, package installation, and arbitrary terminal commands are intentionally **not implemented**.

## Windows 11

1. Install Python 3.10+.
2. Open PowerShell in this folder.
3. Install dependencies:

```powershell
py -m pip install -r requirements-windows.txt
```

4. Generate a strong token and keep it private. Example:

```powershell
py -c "import secrets; print(secrets.token_urlsafe(32))"
```

5. Start the agent:

```powershell
py agent.py --token YOUR_LONG_RANDOM_TOKEN
```

Allow inbound TCP `8765` only on the trusted private network if Windows Firewall asks.

## macOS Catalina

Use Python 3.9.x for the Catalina machine.

```bash
python3 -m pip install -r requirements-macos.txt
python3 -c 'import secrets; print(secrets.token_urlsafe(32))'
python3 agent.py --token YOUR_LONG_RANDOM_TOKEN
```

Grant Accessibility permission to the terminal/Python process under **System Preferences → Security & Privacy → Privacy → Accessibility**. PyAutoGUI actions will otherwise be blocked.

## Test without the Android tablet

On the laptop running the agent:

```bash
python test_client.py --host 127.0.0.1 --token YOUR_LONG_RANDOM_TOKEN ping
```

From another device on the same LAN, replace `127.0.0.1` with the laptop's private IP:

```bash
python test_client.py --host 192.168.1.20 --token YOUR_LONG_RANDOM_TOKEN status
```

Example actions:

```bash
python test_client.py --host 192.168.1.20 --token YOUR_LONG_RANDOM_TOKEN open_app --name chrome
python test_client.py --host 192.168.1.20 --token YOUR_LONG_RANDOM_TOKEN open_url --url https://www.youtube.com
python test_client.py --host 192.168.1.20 --token YOUR_LONG_RANDOM_TOKEN media --action play_pause
```

## Planned Android integration

The tablet will become the client and keep the desktop connection separate from the wake-word/Alexa microphone lifecycle:

```text
"Alexa"
   ↓
wake-word detector
   ↓
Alexa voice UI
   ↓
command classification / routing
   ├── normal Alexa → Amazon Alexa
   ├── Android action → Android Intent
   ├── Windows action → LAN WebSocket → Windows agent
   └── Mac action → LAN WebSocket → macOS agent
```

The Android client should store the paired token securely, address each laptop by private IP (or add LAN discovery later), and only send commands from the same allow-list. The desktop agent should remain LAN-only and should never be exposed to the public internet.

## Security model

This is a local automation system, so pairing and network boundaries matter. The token must be treated like a password. Do not commit a real token to GitHub or put it in screenshots/logs. For untrusted networks, add TLS before using the agent there.
