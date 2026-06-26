# Implementation Plan

Four working days to a usable POC. Each day produces something testable end-to-end at its own level of the stack. Resist the urge to leap ahead — protocol assumptions get validated in the early days and often need adjustment.

## Progress tracker

**This document is the canonical progress tracker for the build.** Update the checkboxes here as work lands.

Legend: `[x]` done · `[ ]` not started · `[~]` partial (see note). A trailing **⚠ unverified** means the work is implemented in code but has not yet been exercised end-to-end on a real device or against the real `claude` binary.

**Last updated: 2026-06-26.** Daemon Days 1, 2 and the daemon-side of Day 4 (mDNS advertise, `/pair`, token auth) plus a project session browser are implemented and unit-tested (`cd daemon && .venv/bin/python -m pytest` → **42 passing**). The Android app builds (toolchain set up via `scripts/setup-android-toolchain.sh`) and has been **verified live**: a Pixel 8 over the corporate VPN connected to the daemon, created/resumed a session, and delivered prompts to a real `claude` process. Added since the original plan: a **configurable daemon address** (in-app, SharedPreferences), connection/error feedback, and a **VS Code-style session browser** (`ProjectScreen` → `list_sessions`/resume). Remaining: a **readable terminal** (xterm.js renderer — output is currently raw ANSI), plus pairing/DataStore/reconnect and real auth. To test on Windows via an emulator, see **[`EMULATOR_TESTING.md`](EMULATOR_TESTING.md)**. See also the "Cross-cutting work" section below.

## Day 1 — Daemon, PTY, raw WebSocket echo  ✅ complete

Goal: Run the daemon, connect with `websocat`, see Claude Code's output stream into the terminal, send keystrokes back.

Tasks:
- [x] Confirm `pip install -e .` works in the venv; `claude-remote-daemon -v` runs without crashing
- [x] `claude_remote_daemon/session.py` — `SessionManager.create()` spawns via `ptyprocess.PtyProcess.spawn`, pumps output with `loop.add_reader(proc.fd, ...)` into `append_output()`, watchdog flips status to DEAD on exit. Subscribers now carry encoded protocol frames (`broadcast`); the byte ring buffer backs `replay()`.
- [x] `claude_remote_daemon/server.py` — `_dispatch` implements `SessionAttach` (subscribe + optional replay + per-client forward task with cleanup), `Input` (write to PTY), `SessionCreate` (spawn → `SessionCreated` → auto-attach)
- [x] `claude_remote_daemon/main.py` — `run()` wires SessionManager + WsServer + HookBridge + AuthStore + Discovery and races them against a signal-driven stop. Added a `--claude-cmd` / `CLAUDE_REMOTE_CLAUDE_CMD` override for testing without the real `claude`.
- [x] Skip auth checks for Day 1 — accept any token in `Hello` (still a dev passthrough; real check is a Day 4 item)

Test: covered by an automated end-to-end test (`tests/test_integration.py`) that drives a real WebSocket through `hello → welcome → session_create → output → input` using a `bash` echo loop in place of `claude`. The manual `websocat` flow from the original plan still works against `claude-remote-daemon -v`.

## Day 2 — Hook bridge, permission round-trip  ✅ implemented (live verification pending)

Goal: When Claude Code asks for permission, the phone receives a JSON `permission_request`, sends back a `permission_response`, and Claude Code proceeds.

Note: the hook I/O schema was verified against the installed **Claude Code 2.1.185**, which uses the `PermissionRequest` and `Notification` events (not the scaffold's assumed `PreToolUse` + `{"decision":"approve"}`). Decisions map to `hookSpecificOutput.decision.behavior`.

Tasks:
- [x] `claude_remote_daemon/hooks.py` — `HookBridge._handle` parses the framed payload (first line = session id, rest = hook JSON), builds a `PermissionRequest`, registers a Future in `session.pending_perms`, fans it out to subscribers, awaits with a 5-min auto-deny timeout
- [x] `claude_remote_daemon/hooks.py` — `HookBridge.resolve()` finds and completes the Future
- [x] `claude_remote_daemon/server.py` — `PermissionResponse` dispatch calls `hooks.resolve()`
- [x] `claude_remote_daemon/session.py` — per-session "always allow/deny" preferences (`perm_prefs`, keyed by tool + input signature); the bridge consults them before prompting again
- [x] `scripts/install-hooks.sh` — corrected to register `PermissionRequest` + `Notification` + `Stop` at `claude-remote-hook` (was wrongly `PreToolUse`); jq `*` merge replaces those event arrays. **The user still needs to run it** to activate the daemon backend.
- [~] `hook_bin/main.py` — unchanged and complete; bridge logic unit-tested via an in-memory reader (`tests/test_hooks.py`). **⚠ unverified** end-to-end against the real `claude` binary.

Hook isolation (added requirement): solved by session correlation — the bridge acts only on requests whose `CLAUDE_REMOTE_SESSION` matches a daemon-spawned session; any other `claude` (e.g. an interactive VSCode session) passes through with `{"continue": true}` and is untouched.

Test: ⚠ Not yet run live. Pending: run the daemon, run the hook installer, create a session, ask Claude to run a Bash command, confirm `permission_request` reaches the phone and a decision flows back.

## Day 3 — Android app, single session, hardcoded IP  🚧 code written, device build pending

Goal: Open the app on a phone (or emulator), see live terminal output from the daemon, send input, create sessions, see permission requests.

Tasks:
- [ ] Open `android/` in Android Studio; sync Gradle; run on emulator — **⚠ not done in this environment** (no Android SDK/Gradle here; the app must be built in Android Studio). A latent compile error in the scaffold was fixed: `SessionsScreen`/`TerminalScreen` now carry `@OptIn(ExperimentalMaterial3Api::class)`.
- [~] `WsClient.kt` connects to `10.0.2.2:8765` and decodes `welcome` — code present and unchanged from scaffold; **⚠ unverified** on a device
- [x] `SessionService.kt` — connects on start, exposes observable state (`sessions`, `conn`, per-session `outputFor`) and actions (`createSession`/`attach`/`sendInput`); attaches when a session is opened
- [x] `MainActivity.kt` + `SessionsScreen.kt` — populated from real `welcome.sessions`; navigation list ↔ terminal; "New session" dialog (name + cwd); connection-state indicator
- [x] `TerminalScreen.kt` — scrolling monospace `Text` fed by the output buffer, auto-scroll, input field + Send wired to `Input` frames (xterm.js deferred as polish)
- [ ] In-app `PermissionCard` Composable — **deferred**: permissions currently surface via notifications (Day 4 path); an in-app card is optional polish

Test: ⚠ Not yet run. Pending a device/emulator build pointed at a running daemon.

## Day 4 — Notifications, mDNS, polish  🚧 daemon side done; Android client + pairing UI pending

Goal: Receive permission requests as Android notifications even when the app is closed and the screen is off. Auto-discover the daemon.

Tasks:
- [~] Verify `NotifBuilder.kt` posts notifications correctly; tweak importance + category if heads-up doesn't show — scaffold complete (channels, lock-screen actions, voice reply); **⚠ unverified** on a device. `SessionService` now drives it (permission/notification/task-complete + connection status).
- [ ] Test `PermissionActionReceiver.kt` end-to-end: lock phone, trigger permission, tap Allow on lock screen, verify decision reaches the daemon — **⚠ unverified**
- [ ] Test voice reply: long-press notification, tap mic, say "allow", verify mapping — **⚠ unverified**
- [x] `claude_remote_daemon/discovery.py` — implemented with `zeroconf.asyncio.AsyncZeroconf`; resilient to multicast-socket failure (logs a warning and idles rather than crashing)
- [ ] `DaemonBrowser.kt` — already implemented; verify it finds the daemon **⚠ unverified**
- [ ] `PairingScreen.kt` — wire pairing: POST to `http://<daemon>:<port>/pair?code=NNNNNN&device=<name>`, store returned token in DataStore. **Note:** the daemon serves the code via the query string, not a JSON body (the websockets HTTP hook exposes no body).
- [x] Added a `/pair` HTTP endpoint to `server.py` on the same port as the WebSocket (websockets asyncio `process_request` hook). Tested in `tests/test_pair.py`.
- [x] `auth.py` `verify(token)` wired into `WsServer._handle`'s Hello check, behind a `--require-auth` flag (default off so dev works before the pairing UI lands; on → unknown tokens get an Error + close 4001)

Test: Lock phone. From laptop, ask Claude to run a Bash command. Phone wakes with notification. Tap Allow on lock screen. See Claude proceed in the laptop terminal.

## Cross-cutting work (added after the original plan)

Items that emerged from the design discussion about a configurable Windows/Android remote control over WireGuard. See `CLAUDE.md` for the architecture.

Configurable backend switch (`windows` | `android`, one active at a time):
- [x] `install-hooks.sh` switches the permission/notification backend to the daemon (registers `PermissionRequest`/`Notification`/`Stop` → `claude-remote-hook`, replacing prior handlers)
- [ ] A single `REMOTE_BACKEND` / `CLAUDE_NOTIFY_HOST` config knob read by the two existing shell hooks (`permission-approve-bridge.sh`, `notify-remote-bridge.sh`) so the Windows-toast path can be re-selected without hand-editing
- [ ] A one-command toggle between the Windows and Android backends

Hook isolation:
- [x] Daemon-spawned sessions correlate via `CLAUDE_REMOTE_SESSION`; non-daemon `claude` sessions pass through untouched (no `--settings`/config-dir juggling needed)

WireGuard transport:
- [ ] Confirm phone ↔ daemon reachability over WireGuard (or a WireGuard-based mesh such as Tailscale); ensure one stable endpoint
- [ ] App setting to point the daemon address at the WireGuard IP (ties into the DataStore work in Day 4)

Schema / version:
- [x] Hook input/output schema verified against Claude Code 2.1.185 (`PermissionRequest` + `Notification` events)

App features added during live bring-up:
- [x] Configurable daemon address in-app (host/port, SharedPreferences) + connection/error feedback (`Settings.kt`, `DaemonDialog`)
- [x] VS Code-style project session browser: enter a folder → list past sessions (titles/timestamps) → resume or start new (`ProjectScreen`, `list_sessions`/`project_sessions`, `resume_id`)
- [x] Build toolchain bootstrap script (`scripts/setup-android-toolchain.sh`) and Windows emulator runbook (`docs/EMULATOR_TESTING.md`)
- [ ] **Readable terminal**: xterm.js renderer in the WebView so `claude`'s ANSI/TUI output is legible (currently raw escape codes)

## After the POC (v2 backlog)

Roughly in order of value:

- [ ] Multiple concurrent sessions in the app (sessions list with status badges, switch between terminals)
- [ ] Native terminal rendering (drop xterm.js, draw chars directly with Canvas) for better scrollback and selection
- [ ] Voice reply path: parse the RemoteInput transcript more flexibly; route freeform text as input to Claude
- [ ] Cloud relay server (small Python ASGI service on a $5 VPS) so the phone reaches the daemon from anywhere
- [ ] FCM push (so the foreground service can sleep when nothing is happening)
- [ ] mTLS or Tailscale-based transport security
- [ ] Session output persistence (SQLite on the phone for history scrollback after disconnect)
- [ ] Wear OS companion for permission approvals from the wrist
- [ ] Settings: per-tool default decisions (e.g. always allow `Read`, always prompt for `Bash`)
- [ ] systemd user service unit for the daemon (so it survives logout)

## Anti-goals (do not build)

- Don't build a full IDE
- Don't build a chat history sync
- Don't build a project file browser (that's what Claude Code is for)
- Don't build cloud sync of conversations (security mess)
- Don't add LLM features in the app itself — the app is a remote control, not an assistant
