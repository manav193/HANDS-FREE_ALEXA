# Hands-Free Alexa Android Hub

Android implementation of openWakeWord in Kotlin, extended into a DIY hands-free Alexa hub.

## Current features

- Local `Alexa` wake-word detection using TFLite + ONNX Runtime.
- Amazon Alexa voice activity handoff.
- Wake-word detector reset before and after Alexa handoff to prevent repeated triggers.
- Foreground microphone service so the detector can remain active while the tablet is on the Android home screen.
- Persistent low-priority notification while hands-free mode is active.
- Dashboard for wake-word status/confidence and detection count.

## Home-screen hands-free architecture

```text
Android Home Screen
       |
       v
WakeWordService (foreground microphone service)
       |
       v
openWakeWord detector
       |
   "Alexa"
       |
       +--> release detector microphone
       |
       v
Amazon Alexa VoiceHandsFreeSearchActivity
       |
       +--> user presses Back
       |
       v
Android app resumes the wake-word service
```

The microphone service is deliberately started while the app is visible and microphone permission has already been granted. This is important for newer Android versions because foreground microphone services have while-in-use and background-start restrictions. Android requires the microphone foreground-service type for long-running microphone capture on Android 11+ and requires the additional foreground-service microphone permission for apps targeting Android 14+. cite-placeholder

## Build

The project is designed to build on the user's Android/Termux environment using the Android SDK/NDK and Gradle 8.7. See the existing project configuration for the ARM64 + Box64 build workaround.

## Laptop integration

A local-network desktop agent is planned for:

- Acer Nitro laptop running Windows 11.
- MacBook Pro Mid-2012 running macOS Catalina.

The tablet will pair with a laptop agent over an authenticated WebSocket connection on the local network. Initial commands should be limited to safe media/app/keyboard actions; destructive system operations must require confirmation.

Desktop-agent requirements are in:

- `desktop-agent/requirements-windows.txt`
- `desktop-agent/requirements-macos.txt`
- `desktop-agent/README.md`

The Windows agent uses Python, WebSockets, PyAutoGUI, PyWin32 and UI Automation. The Catalina agent uses Python 3.9.x, WebSockets, PyAutoGUI and PyObjC. macOS Accessibility permission is required for GUI automation.
