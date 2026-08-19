# HerDroid architecture

## Product boundary

HerDroid is not a Termux launcher and not a WebView wrapper around a hosted chat page. It is an Android surface for Hermes with Android-native ownership of terminal, browser, files, permissions, lifecycle, and device capabilities.

The long-term boundary mirrors Hermes Desktop:

- **Android layer** owns process lifecycle, device APIs, files, foreground/background execution, and local runtime installation.
- **UI layer** owns navigation and ephemeral presentation state.
- **Hermes backend** owns agent sessions, tools, models, memory, and streaming.

## Runtime modes

HerDroid should support the same UI against three execution modes:

1. **Embedded** — Hermes runs inside the app's private storage.
2. **Remote** — HerDroid connects directly to `hermes serve` over WebSocket.
3. **Paired host** — a PC/server runs expensive tools while Android remains the control surface.

The gateway adapter is therefore a narrow interface. UI code must not depend on whether the backend is local or remote.

## Terminal

v0.1 uses `/system/bin/sh` for a real, visible proof that commands execute inside the app sandbox. It is deliberately not called a full terminal emulator.

Target implementation:

```text
Terminal UI
    ↓
TerminalSession
    ↓
Native PTY bridge
    ↓
Bundled shell/toolchain or allowed system executable
```

Termux may be studied for reusable terminal-emulation components where licensing permits, but HerDroid must not require the Termux app or spawn an external terminal window.

## Browser

The browser is a first-class HerDroid surface backed by Android WebView. The next browser milestone adds an agent capability bridge for navigation, page metadata, DOM-derived accessibility snapshots, click/type/scroll operations, screenshots, tabs, and downloads.

Agent actions must not unexpectedly steal focus. Background browsing can update task state while the user stays in chat.

## Embedded Hermes runtime

The embedded milestone will package CPython and Hermes dependencies for Android ARM64 and expose the same JSON-RPC transport currently used by the remote path. The Compose layer should not know the difference.

The runtime should execute under a foreground service while an active long-running turn needs process priority. Durable scheduled work should use Android-native schedulers rather than pretending Android allows an immortal daemon.

## Security

Device capabilities are explicit and scoped. Camera, microphone, location, notifications, and broad file access are never granted simply because an agent requested a tool. HerDroid presents the request and applies Android permission/runtime policy.

Remote endpoints default to TLS in production. Plain `ws://` remains available during local development and LAN testing.
