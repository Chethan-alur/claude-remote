# Implementation Plan

Four working days to a usable POC. Each day produces something testable end-to-end at its own level of the stack. Resist the urge to leap ahead — protocol assumptions get validated in the early days and often need adjustment.

## Day 1 — Daemon, PTY, raw WebSocket echo

Goal: Run the daemon, connect with `websocat`, see Claude Code's output stream into the terminal, send keystrokes back.

Tasks:
- [ ] Confirm `pip install -e .` works in the venv; `claude-remote-daemon -v` runs without crashing
- [ ] `claude_remote_daemon/session.py` — implement `SessionManager.create()`: spawn `claude` via `ptyprocess.PtyProcess.spawn`, wire `loop.add_reader(proc.fd, ...)` to call `session.append_output()`, watchdog task that flips status to DEAD on exit
- [ ] `claude_remote_daemon/server.py` — flesh out `_dispatch`: implement `SessionAttach` (subscribe a queue, optional replay), `Input` (write bytes to PTY), `SessionCreate` (spawn + auto-attach)
- [ ] `claude_remote_daemon/main.py` — replace placeholder `run()` with the full wiring (SessionManager + WsServer + auth + discovery), `asyncio.gather` them all
- [ ] Skip auth checks for Day 1 — accept any token in `Hello`

Test: `claude-remote-daemon -v` then in another shell `websocat ws://localhost:8765`. Send `{"type":"hello","token":"x"}`, then `{"type":"session_create","name":"test","cwd":"/tmp"}`, then `{"type":"input","session":"sess_xxx","data":"hi\n"}`. See Claude Code's output stream back as `output` frames.

## Day 2 — Hook bridge, permission round-trip

Goal: When Claude Code asks for permission, your `websocat` session receives a JSON `permission_request`, you type back a JSON `permission_response`, and Claude Code proceeds.

Tasks:
- [ ] `claude_remote_daemon/hooks.py` — implement `HookBridge._handle`: parse the framed payload (first line = session id, rest = hook JSON), build a `PermissionRequest`, register a Future in `session.pending_perms`, fan out the request to subscribers, await the Future with timeout
- [ ] `claude_remote_daemon/hooks.py` — implement `HookBridge.resolve()` to find and complete the Future
- [ ] `claude_remote_daemon/server.py` — wire `PermissionResponse` dispatch to call `hooks.resolve()`
- [ ] `claude_remote_daemon/session.py` — store per-session "always allow/deny" preferences keyed by `(tool_name, input_signature)`; `HookBridge` consults them before bothering the user
- [ ] `scripts/install-hooks.sh` — verify it generates the right entries; run it
- [ ] `hook_bin/main.py` — already complete, test end-to-end

Test: Run daemon. Run hook installer. Open `websocat` session, create a session, ask Claude to run a Bash command. See `permission_request` arrive. Send `{"type":"permission_response","id":"req_xxx","decision":"allow"}`. Watch Claude execute.

## Day 3 — Android app, single session, hardcoded IP

Goal: Open the app on a phone (or emulator), see live terminal output from the daemon, send input, see permission requests as in-app cards.

Tasks:
- [ ] Open `android/` in Android Studio; sync Gradle; run on emulator
- [ ] Verify `WsClient.kt` connects to the daemon at `10.0.2.2:8765` (emulator's host alias) and decodes the `welcome` frame
- [ ] `SessionService.kt` — connect to daemon on start, expose messages flow to UI, auto-attach to first session for Day 3 simplicity
- [ ] `MainActivity.kt` + `SessionsScreen.kt` — populate from real `welcome.sessions`; add "New session" flow that prompts for name + cwd
- [ ] `TerminalScreen.kt` — for Day 3, skip xterm.js. Render output as a scrolling `Text` in a `LazyColumn` (ANSI codes will look ugly; that's fine for now). Wire the input field to send `Input` messages.
- [ ] In-app `PermissionCard` Composable that shows when a `PermissionRequest` arrives in the messages flow

Test: Start daemon on dev machine. Launch app on emulator (same WiFi). See terminal output. Trigger a permission request in Claude. See the card appear. Tap Allow. Watch Claude continue.

## Day 4 — Notifications, mDNS, polish

Goal: Receive permission requests as Android notifications even when the app is closed and the screen is off. Auto-discover the daemon.

Tasks:
- [ ] Verify `NotifBuilder.kt` posts notifications correctly; tweak importance + category if heads-up doesn't show
- [ ] Test `PermissionActionReceiver.kt` end-to-end: lock phone, trigger permission, tap Allow on lock screen, verify decision reaches the daemon
- [ ] Test voice reply: long-press notification, tap mic, say "allow", verify mapping
- [ ] `claude_remote_daemon/discovery.py` — implement with `zeroconf.asyncio.AsyncZeroconf`; pick a sane local IP (iterate interfaces or bind to 0.0.0.0 and let zeroconf advertise all)
- [ ] `DaemonBrowser.kt` — already implemented; verify it finds the daemon
- [ ] `PairingScreen.kt` — wire pairing: POST to `http://<daemon>:<port>/pair` with the 6-digit code, store returned token in DataStore
- [ ] Add a `/pair` HTTP endpoint to `server.py` (it can share the same port using `websockets`'s ability to handle HTTP)
- [ ] `auth.py` is already complete; just hook `verify(token)` into `WsServer._handle`'s Hello check

Test: Lock phone. From laptop, ask Claude to run a Bash command. Phone wakes with notification. Tap Allow on lock screen. See Claude proceed in the laptop terminal.

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
