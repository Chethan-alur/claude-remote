# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status: core implemented, permission/pairing layers pending

The architecture, wire protocol, and class shapes are fully designed in the docs. Before writing code, read `docs/FOR_CLAUDE_CODE.md` (the brief) and `docs/IMPLEMENTATION_PLAN.md` (the four-day build order).

**Implemented and tested (daemon "Day 1" core — the app can create live sessions, stream output, and send prompts):**

- `daemon/claude_remote_daemon/session.py` — `SessionManager.create()` spawns the session command in a PTY, pumps output via `loop.add_reader`, and finalises on exit via a watchdog. Subscribers carry encoded protocol frames; `broadcast()` fans them out, `append_output()` wraps PTY bytes into `Output` frames, and the byte ring buffer backs `replay()`.
- `daemon/claude_remote_daemon/server.py` — `_dispatch` implements `SessionCreate` (spawn → `SessionCreated` → auto-attach), `SessionAttach` (subscribe + optional replay + per-client forward task with disconnect cleanup), and `Input`. Auth is still a dev passthrough.
- `daemon/claude_remote_daemon/main.py` — `run()` wires `AuthStore` + `SessionManager` + `HookBridge` + `WsServer` + `Discovery` and races them against a signal-driven stop. A `--claude-cmd` / `CLAUDE_REMOTE_CLAUDE_CMD` override lets tests spawn a fake process instead of `claude`.
- `daemon/claude_remote_daemon/hooks.py` — `HookBridge` handles the Unix-socket protocol: it parses the framed payload (session-id line + hook JSON), routes `PermissionRequest`/`PreToolUse` to a `PermissionRequest` frame (registers a Future, broadcasts daemon-wide to **all** connected clients, awaits the first decision), and `Notification`/`Stop` to `Notification` frames. **Multi-client fan-out + local fallback:** if no client is connected (`HookBridge.has_clients`) the request passes through to Claude's local prompt immediately; otherwise it waits `client_wait` seconds (`--permission-wait`, default 30) and, on timeout, broadcasts `permission_resolved{expired}` and passes through (it does **not** auto-deny). `resolve()` completes the Future on the **first** response (first-responder-wins) and broadcasts `permission_resolved{answered}` so other clients dismiss; per-session allow/deny-always preferences short-circuit repeats. Decisions map to the **Claude Code 2.1.x** schema (`hookSpecificOutput.decision.behavior` for `PermissionRequest`). Hook isolation is by session correlation: only requests whose `CLAUDE_REMOTE_SESSION` matches a daemon-spawned session are acted on; everything else passes through (`{"continue": true}`).
- `daemon/tests/` — protocol round-trips, an end-to-end WebSocket smoke test (`create → output → input`), and hook-bridge tests (allow/deny/always, timeout, passthrough, notification) driven through an in-memory reader to avoid the sandbox's Unix-socket restriction. Run with `.venv/bin/python -m pytest`.
- `scripts/install-hooks.sh` — registers `PermissionRequest` + `Notification` + `Stop` (matching 2.1.x) at `claude-remote-hook`; jq's `*` merge replaces those event arrays, so running it switches the active backend to the daemon.
- Android `SessionService` / `MainActivity` / `SessionsScreen` / `TerminalScreen` — observable session state, navigation, New Session dialog, terminal output rendering, and prompt send (the "full control" vertical slice).

- `daemon/claude_remote_daemon/discovery.py` — mDNS advertise implemented via `AsyncZeroconf` (`_claudecode._tcp`), resilient to multicast failure.
- `server.py` — serves `/pair` on the WebSocket port (code in the query string: `POST /pair?code=NNNNNN&device=<name>` → `{"token": "..."}`), and gates `hello` on `AuthStore.verify` behind `--require-auth` (default off so dev works pre-pairing).

**Still stubbed / pending, in dependency order:**

- Android — pairing flow (`PairingScreen` → `/pair` → DataStore), DataStore persistence of token/daemon address (incl. WireGuard host), `WsClient` reconnect/heartbeat, in-app permission card, xterm.js rendering.
- End-to-end verification against the real `claude` binary on a device (the daemon is unit-tested but not yet exercised through a live PTY session + phone).

`auth.py` and `hook_bin/main.py` were complete from the scaffold and are unchanged.

Two environment notes from implementation: the `websockets` legacy `WebSocketServerProtocol` API is deprecated in websockets ≥14 (works, but a migration is pending); and the hook bridge's `AF_UNIX` socket path must stay under ~108 characters (the default `/tmp/claude-remote.sock` is fine).

## What this project is

Control Claude Code running on a dev machine from an Android phone over the LAN. The phone receives push notifications when Claude needs permission and can Allow/Deny/Always from the lock screen. Three pieces:

```
[Android app]  <-- JSON over WebSocket (LAN) -->  [Python daemon]
                                                        | spawns in a PTY
                                                        v
                                                  [Claude Code CLI]
                                                        | PreToolUse / Stop / Notification hooks
                                                        v
                                          [claude-remote-hook] --Unix socket--> daemon
```

The load-bearing idea: permission detection goes through **Claude Code's official hooks**, never terminal scraping. Claude Code invokes the tiny `claude-remote-hook` CLI on each hook event; the CLI forwards the payload over a Unix socket to the daemon, which asks the phone and returns the decision. The daemon converts the phone's decision into the `{"decision": "approve"|"block"}` JSON the hook protocol expects.

## Three-way protocol sync (critical)

The wire protocol is defined in **three places that must be kept in lockstep**. Changing a message means editing all three:

1. `protocol/messages.md` — the authoritative spec (prose + JSON examples).
2. `daemon/claude_remote_daemon/protocol.py` — Python dataclasses + `encode`/`decode` (registry keyed on the `type` field).
3. `clients/android/app/src/main/java/com/claude/remote/model/Messages.kt` — Kotlin `@Serializable` sealed interface with `@JsonClassDiscriminator("type")`.

Message types: `hello`, `welcome`, `session_create`, `session_created`, `session_attach`, `input`, `output`, `permission_request`, `permission_response`, `permission_resolved`, `notification`, `set_handoff`, `handoff_state`, `ping`/`pong`, `error`. Pairing is **out of band** over HTTP (`POST /pair`), not part of the WebSocket protocol.

Session status values: `running`, `waiting` (permission pending), `idle` (Claude finished its turn), `dead`. Permission decisions: `allow`, `deny`, `allow_always`, `deny_always` (the `*_always` variants store a per-session preference keyed on tool + input shape). `SessionInfo.origin` is `spawned` (daemon-launched) or `adopted` (external — see handoff below).

## Desktop→mobile handoff (adopted sessions)

A `claude` started outside the daemon (e.g. in VSCode) carries no `CLAUDE_REMOTE_SESSION`, so its hook events arrive with an empty session-id line. By default these pass through to the normal desktop prompt. When the phone enables handoff (`set_handoff` → `HookBridge.handoff_enabled`; default off, echoed via `welcome.handoff_enabled` + `handoff_state`), the daemon **adopts** such a session on its next hook event — keyed on Claude Code's own `session_id` from the payload — so its permission prompts reach the phone.

Adopted sessions are **permission-only**: `Session.proc is None` (the daemon does not own their PTY), so there is no terminal output and input is a no-op; the Android terminal disables its input box and shows a "desktop" badge. Because no phone is *attached* to an adopted session, the hook bridge fans `permission_request`/`notification` frames out **daemon-wide** via `WsServer._broadcast_all` (wired to `HookBridge.broadcast_all`), not per-session `session.broadcast`. Lifecycle: adopted on any hook event (`SessionStart` makes them appear immediately), removed on `SessionEnd`, with `SessionManager.reap_adopted` pruning ones idle past `ADOPTED_TTL_SEC` as a safety net. This relies on `install-hooks.sh` registering `SessionStart`/`SessionEnd` (re-run it after updating).

## Daemon — commands

```bash
# One-shot setup (creates daemon/.venv, installs the package + hook CLI editable)
./scripts/setup-daemon.sh

# Manual equivalent
cd daemon && python3 -m venv .venv && source .venv/bin/activate && pip install -e .

# Install the dev extras (pytest, pytest-asyncio, ruff)
pip install -e '.[dev]'

# Run the daemon (-v / -vv raise log verbosity)
claude-remote-daemon -v --port 8770

# Lint
ruff check daemon/

# Tests (none exist yet; start with protocol.py encode/decode round-trips)
pytest                      # all
pytest daemon/tests/test_protocol.py::test_name   # a single test

# Register the hooks in ~/.claude/settings.json (idempotent, backs up first; requires jq).
# Expects the hook CLI symlinked to a stable path first:
sudo ln -sf "$(pwd)/daemon/.venv/bin/claude-remote-hook" /usr/local/bin/claude-remote-hook
./scripts/install-hooks.sh
```

For reference, `install-hooks.sh` merges this into `~/.claude/settings.json` (one `command` per event, all pointing at the hook CLI):

```json
{
  "hooks": {
    "PermissionRequest": [{ "matcher": "*", "hooks": [{ "type": "command", "command": "/usr/local/bin/claude-remote-hook" }] }],
    "Notification":  [{ "hooks": [{ "type": "command", "command": "/usr/local/bin/claude-remote-hook" }] }],
    "Stop":          [{ "hooks": [{ "type": "command", "command": "/usr/local/bin/claude-remote-hook" }] }],
    "SessionStart":  [{ "hooks": [{ "type": "command", "command": "/usr/local/bin/claude-remote-hook" }] }],
    "SessionEnd":    [{ "hooks": [{ "type": "command", "command": "/usr/local/bin/claude-remote-hook" }] }]
  }
}
```


### Testing the daemon without the app

```bash
# Drive the WebSocket by hand
websocat ws://localhost:8770
# then send: {"type":"hello","token":"x"}
#            {"type":"session_create","name":"test","cwd":"/tmp"}
#            {"type":"input","session":"sess_xxx","data":"hi\n"}

# Test the hook CLI standalone (fails open to approve when no daemon is running)
echo '{"event":"PreToolUse","tool_name":"Bash"}' | CLAUDE_REMOTE_SESSION=sess_test claude-remote-hook
```

## Android — commands

```bash
cd clients/android
./gradlew installDebug      # build + install on connected device/emulator
./gradlew assembleDebug     # build APK only
./gradlew lint
```

The emulator reaches the daemon on the host at `10.0.2.2:8770` (set as `DEFAULT_DAEMON_HOST` in the debug build config). Gradle 9, `compileSdk`/`targetSdk` 34, `minSdk` 28, JVM target 17. Stack: Jetpack Compose (Material3), `kotlinx.serialization`, `OkHttp` WebSocket, `NsdManager` for mDNS — no DI framework, no Rx; do not introduce one.

## Clients layout

Both clients live under `clients/`:
- `clients/android/` — the Kotlin/Compose app (moved here from the old top-level `android/`).
- `clients/windows/` — `claude-notify-listener.ps1`, a PowerShell WebSocket client that shows native WinRT toasts and answers permission requests; `clients/windows/legacy/` holds the retired HTTP-over-SSH-tunnel bridges for reference only. See `clients/windows/README.md`. It is verified manually on Windows (cannot run from the Linux daemon's CI). Run with:

```powershell
powershell -ExecutionPolicy Bypass -File clients\windows\claude-notify-listener.ps1 -DaemonUrl ws://127.0.0.1:8770 -Token <device-token>
```

## Daemon architecture notes

- **asyncio + `loop.add_reader(proc.fd, ...)`** for PTY reads — Linux/WSL only, which is intentional for the POC. Do not switch to thread-pool reads unless the user explicitly asks for Windows-native support. `ptyprocess` is the chosen PTY library; do not replace it with `pexpect` or `pty.fork()`.
- One `Session` owns one `ptyprocess.PtyProcess` running `claude`, plus a bounded ~1 MB output ring buffer (`deque`) for reconnect replay, a list of subscriber `asyncio.Queue`s (drop on slow consumers rather than block the PTY pump), and a `pending_perms` dict of request-id → `Future`.
- The session id is passed to the spawned `claude` via the `CLAUDE_REMOTE_SESSION` env var; the hook CLI reads it and prepends it as a header line so the daemon can correlate the hook payload to a session without parsing every payload variant.
- The hook bridge **fails open**: any error or unreachable daemon yields `{"decision":"approve"}` so a daemon crash never bricks Claude Code. With the daemon up, a permission request with **no connected client** passes through to the local prompt immediately; with clients connected it waits `client_wait` seconds (default 30) and, on timeout, **passes through to the local prompt** (it no longer auto-denies). Dead clients are evicted by the WebSocket heartbeat (`ping_interval`/`ping_timeout`) and on failed send.
- State (device tokens) lives at `~/.claude-remote/devices.json`; the hook Unix socket defaults to `/tmp/claude-remote.sock`.

## Constraints the user has set (from docs/FOR_CLAUDE_CODE.md)

- **Python (asyncio) for the daemon** — chosen deliberately; do not rewrite in another language.
- **Local-network only** for the POC. No cloud relay, no FCM, no telemetry — these are v2.
- **Single user.** No multi-tenancy or per-user encryption.
- **No new Python dependencies** beyond `websockets`, `ptyprocess`, `zeroconf` without asking first.
- **Do not modify `~/.claude/settings.json` directly** — changes go through `scripts/install-hooks.sh` for the user to run.
- **Notifications must work from the lock screen** (the entire point): use Android `RemoteInput` for voice replies and direct action buttons.

### Python style (from the same brief)

Prefer dataclasses over plain dicts; `match/case` for message dispatch; type-annotate everything (mypy strict is realistic at this size); keep modules under ~300 lines. Build day by day per `docs/IMPLEMENTATION_PLAN.md` rather than everything at once — the protocol is validated and adjusted in the early days.

### Anti-goals (do not build)

A full IDE, chat-history sync, a project file browser, cloud conversation sync, or any LLM feature in the app itself — the app is a remote control, not an assistant.
