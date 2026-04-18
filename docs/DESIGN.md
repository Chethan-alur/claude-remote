# Design

## Problem

Claude Code is excellent at long-running coding tasks but blocks frequently for permission prompts (Bash, Edit, Write, web fetch). The user has to keep the terminal in view to unblock these, defeating the point of delegating work. The user wants to leave Claude running, walk away, and respond to permission prompts from their phone — including from the lock screen.

## Goals

1. Push notification on the phone when Claude needs permission, with the tool name and the specific input (e.g. "Edit src/auth.ts").
2. Allow / Deny / Always-allow buttons directly on the notification — works from the lock screen.
3. Live terminal view in the app for when the user wants more context before deciding.
4. "Task complete" notification when Claude finishes so the user knows to come back.
5. Multiple concurrent sessions (different projects, different `claude` invocations).

## Non-goals (POC)

- Remote access from outside the LAN
- Multi-user / multi-tenant
- End-to-end encryption beyond TLS
- iOS support
- Web frontend

## Architecture

Three components, two of them in this repo:

```
Android phone                       Dev machine
+----------------+                  +-----------------------+
| Compose UI     |                  | Daemon (Python)       |
| FG service     |  <-- WSS LAN --> |   - WS server         |
| NotifMgr       |                  |   - PTY manager       |
| NSD discovery  |                  |   - Hook bridge       |    +-------------+
+----------------+                  |   - mDNS advertise    | -> | Claude Code |
                                    +-----------------------+    |  (in PTY)   |
                                              ^                  +-------------+
                                              |
                                              | Unix socket
                                              |
                                    +-----------------------+
                                    | claude-remote-hook    |
                                    | (Python CLI)          |
                                    | Called by Claude Code |
                                    | as a hook command     |
                                    +-----------------------+
```

### Why hooks instead of terminal scraping

Claude Code emits permission prompts as part of its normal terminal output, formatted with ANSI escape codes. Parsing that is fragile — formatting changes between releases, prompts get buried in scrolling output, and there's no clean way to inject the user's response back. Hooks are an official Claude Code extension point: a JSON request comes in via stdin, your script returns a JSON decision via stdout. Stable, documented, designed for exactly this use case.

The hook script is intentionally tiny (a few dozen lines): it forwards the hook payload to the daemon's Unix socket and waits for a response. All the smarts live in the daemon.

### Why a PTY (not just stdin/stdout pipes)

Claude Code is a TUI — it draws a live status pane, uses cursor positioning, expects a real terminal. Plain pipes break its rendering, and some features (input editing, history) require terminal control. `ptyprocess` on the daemon side gives us a fake terminal that Claude Code is happy with, and we get the raw output stream to forward to the phone. The phone-side terminal renderer interprets the ANSI codes (xterm.js inside a WebView is the simplest path; a native renderer is a v2 polish item).

### Why asyncio + add_reader (not threads)

Each session has a PTY that produces output continuously and accepts input asynchronously. Plus we have N WebSocket clients, the hook bridge's Unix socket, and mDNS — all I/O bound, all chatty. asyncio is the right shape.

`ptyprocess` itself is synchronous, so we use `loop.add_reader(proc.fd, callback)` to integrate it with the event loop. The kernel signals readiness on the master fd, asyncio invokes our callback, we do a non-blocking read, fan the bytes out to subscriber queues. No threads, no locks, single event loop.

This is Linux/WSL only. For Windows-native or older macOS, fall back to running blocking PTY reads in `loop.run_in_executor()` — same ergonomics for the rest of the code, slightly more overhead per chunk.

### Why mDNS

So the user doesn't have to type IP addresses. The daemon advertises `_claudecode._tcp.local`; the phone's `NsdManager` discovers it and shows a friendly hostname. Works on every consumer router. Falls back to manual IP entry in settings if needed.

### Why a foreground service on Android

WebSocket connections die when Android decides to kill the app — and Android decides this aggressively. A foreground service (with a persistent low-priority notification) keeps the socket alive even when the user backgrounds the app. The persistent notification doubles as a "Claude Remote is connected to mac-studio.local" status indicator.

For the POC we run a continuous foreground service. For v2 we can layer FCM on top: the daemon stops trying to push directly when the phone goes offline, instead writing pending events to a queue and triggering an FCM push that wakes the app.

## Wire protocol

JSON over WebSocket. Six message types. See `protocol/messages.md` for the schema.

Design notes:
- Every message has a `type` field. No envelope, no versioning — keep it stupid for the POC. Add `v` field if we ever break compatibility.
- Permission requests have an `id`; responses echo it. The daemon times out after 5 minutes and auto-denies.
- The daemon sends `output` frames as fast as the PTY produces them. Throttle on the phone if needed; drop on slow subscribers (see `Session.append_output`) rather than blocking the PTY pump.
- Heartbeat: phone sends `ping` every 20s. If daemon doesn't hear one for 60s, it assumes the phone is gone but keeps the session running (the phone reconnects and resumes).

## Data model

```python
class Session:
    id: str                          # "sess_8a3f01"
    name: str                        # user-chosen, e.g. "webapp-refactor"
    cwd: Path                        # project directory
    status: Status                   # running | waiting | idle | dead
    proc: ptyprocess.PtyProcess      # the spawned `claude` process
    output_buffer: deque[bytes]      # last ~1MB of output for reconnect replay
    subscribers: list[Queue[bytes]]  # one per attached WebSocket client
    pending_perms: dict[str, Future] # request_id -> Future awaiting decision

class PermissionRequest:
    id: str                          # "req_8a3f"
    session_id: str
    tool: str                        # "Bash" | "Edit" | "Write" | "WebFetch" | ...
    input: dict                      # raw tool input from the hook
    summary: str                     # 1-line human description for the notif
    received_at: float

Decision = Literal["allow", "deny", "allow_always", "deny_always"]
```

## Security (POC)

- Daemon listens only on the LAN (bind to non-loopback interface, but reject connections from outside the local subnet).
- Pairing: on first run, daemon prints a 6-digit code. User enters it in the app. After pairing, app stores a per-device token; daemon stores a list of authorized tokens at `~/.claude-remote/devices.json`.
- WebSocket is `ws://` for the POC. Adding `wss://` requires either a self-signed cert with manual trust on the phone (annoying) or Tailscale (which gives you mTLS for free). v2.

The threat model is intentionally narrow: a trusted user on a trusted home network. If you put this on a coffee shop WiFi, a co-located attacker can MITM the WebSocket — don't do that until v2.

## What lives where

| Concern | Daemon (Python) | Android (Kotlin) |
|---|---|---|
| WebSocket transport | `websockets` | OkHttp WebSocket |
| Discovery | `zeroconf` (advertise) | NsdManager (browse) |
| Process management | `ptyprocess` + `asyncio.add_reader` | n/a |
| Hook integration | `asyncio.start_unix_server` + `claude-remote-hook` CLI | n/a |
| Session storage | in-memory + JSON file at `~/.claude-remote/devices.json` | Room (SQLite) for history |
| UI | n/a | Jetpack Compose |
| Notifications | n/a | NotificationManager + RemoteInput |
| Terminal rendering | n/a | xterm.js in WebView (POC) → native (v2) |
| Background work | n/a | ForegroundService |
| Voice reply | n/a | RemoteInput → Android Assistant transcription |

## Open questions for the user

These are in `docs/FOR_CLAUDE_CODE.md` under "Things you should ASK the user".
