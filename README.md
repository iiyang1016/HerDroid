# HerDroid

HerDroid is an Android-native workstation for [Hermes Agent](https://github.com/NousResearch/hermes-agent). The goal is a full mobile port of the Hermes desktop experience: chat, projects, terminal, browser, files, tools, and eventually the Hermes runtime itself inside one Android app.

## v0.1 foundation

This first checkpoint intentionally proves the seams before embedding Python:

- Native Android shell built with Jetpack Compose.
- Hermes JSON-RPC-over-WebSocket client compatible with `session.create`, `prompt.submit`, and streaming gateway events.
- In-app shell backed by `/system/bin/sh`; it stays inside HerDroid and does not launch Termux.
- In-app WebView browser; it does not hand normal browsing off to Chrome.
- Transport/runtime boundary designed so a future embedded Hermes runtime can replace a remote gateway without replacing the UI.

The local Python/Hermes runtime, PTY terminal, packaged Unix toolchain, agent-driven WebView controls, project/files UI, and background service are the next milestones.

## Current architecture

```text
Compose UI
├── Chat ───── HermesGatewayClient ───── Hermes gateway today
│                                      └ embedded runtime later
├── Terminal ─ TerminalController ───── /system/bin/sh today
│                                      └ native PTY/toolchain later
└── Browser ── Android WebView ──────── visible in-app browser
```

Hermes' backend remains authoritative for sessions, tools, model calls, and streaming. Android owns device/runtime capabilities. The UI owns presentation state only.

## Build

### Termux (recommended for local Android builds)

Clone or extract HerDroid inside Termux home, not shared storage, then run:

```bash
cd ~/HerDroid
chmod +x build-termux.sh
./build-termux.sh
```

The script installs the Termux-native toolchain, pins JDK 17, bootstraps Android SDK Platform 37, routes AGP through Termux's ARM-native `aapt2`, and builds with a conservative one-worker configuration. The resulting debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Use `./build-termux.sh --clean` when you want a clean rebuild. The first build downloads Gradle/Maven dependencies and the Android 37 platform, so it needs an internet connection.

### Android Studio

Use JDK 17+ and Android SDK 37. The project targets AGP 9.3.1 and the stable Compose BOM `2026.06.00`.

## Connect to Hermes

The Chat tab accepts a WebSocket gateway URL and optional loopback token. For a remote Hermes instance, expose the Hermes gateway securely and enter its `ws://` or `wss://` endpoint.

HerDroid creates sessions with `source: "android"` and otherwise follows Hermes' existing gateway protocol instead of maintaining a separate agent API.

## Roadmap

1. Harden remote Hermes chat and session restore.
2. Add real PTY terminal and bundled ARM64 runtime tools.
3. Add agent-callable WebView browser actions and snapshots.
4. Embed CPython + Hermes runtime as an Android foreground service.
5. Add project files, editor/diff, git, downloads, and artifacts.
6. Add Android-native tools: camera, share sheet, clipboard, notifications, microphone, and device context with explicit permissions.
7. Add background task resume/cron using Android-native scheduling.

## License

MIT. Hermes Agent is a separate upstream project; any upstream code incorporated later must retain its applicable notices.
