# Laptop Integration Agent

The tablet will eventually act as the voice hub and send approved desktop commands to a small local agent running on the user's laptop.

## Supported laptops

### Acer Nitro — Windows 11
- Windows 11 64-bit
- Python 3.10+ recommended
- Local Wi-Fi/LAN access to the tablet
- Python packages from `requirements-windows.txt`
- Windows Firewall rule for the agent's local TCP/WebSocket port
- Optional UI automation: UI Automation / `uiautomation` + `pywin32`
- Optional GUI automation: PyAutoGUI

### MacBook Pro Mid-2012 — macOS Catalina
- macOS 10.15 Catalina
- Python 3.9.x is the safest baseline for Catalina compatibility
- Local Wi-Fi/LAN access to the tablet
- Python packages from `requirements-macos.txt`
- Accessibility permission for the agent/Terminal in System Preferences → Security & Privacy → Privacy → Accessibility
- Optional Screen Recording permission if screen capture is added later
- Optional GUI automation: PyAutoGUI + PyObjC

## Planned architecture

```text
Tablet wake word / Alexa
        |
        | authenticated LAN command
        v
Desktop Agent (Windows/macOS)
        |
        +-- app launch / close
        +-- volume / media controls
        +-- keyboard shortcuts
        +-- mouse / GUI actions
        +-- screenshots (optional, permission-gated)
        +-- file/system actions (allow-listed)
```

## Security requirements

- Pair each laptop with an explicit one-time code/PIN.
- Use a per-device random authentication token after pairing.
- Bind to the local network only by default.
- Never expose the agent directly to the public internet.
- Keep dangerous shell/system operations disabled by default and use an allow-list for supported commands.
- Add confirmation for destructive operations such as shutdown, reboot, file deletion, or package installation.

## Network defaults

- Transport: WebSocket over the local network.
- Default port: `8765`.
- Tablet discovers the laptop by local IP/manual pairing first; automatic discovery can be added later.
- TLS can be added for networks that are not trusted.

The first implementation should focus on safe media/app/keyboard commands before adding unrestricted desktop control.
