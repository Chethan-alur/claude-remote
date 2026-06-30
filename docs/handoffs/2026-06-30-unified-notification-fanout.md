# Handoff: unified multi-client notification fan-out (Android + Windows toast)

- **Status:** pending
- **Date:** 2026-06-30
- **From:** Linux session, Opus 4.8 (no Java/Android SDK, no PowerShell)
- **To:** Windows session with Android Studio emulator + native PowerShell
- **Branch:** `feat/unified-notification-fanout`

## Context

The daemon is now the single hook backend and fans every permission request out
to **all** connected clients at once (Android app + a rewritten Windows toast
client). First responder wins; others are dismissed via a new
`permission_resolved` frame. With no client connected, or when the client-wait
expires, the request passes through to Claude Code's local terminal prompt
instead of hanging/auto-denying. See `docs/notification-flow.md` and
`protocol/messages.md`.

## Changes in this handoff

- **Repo layout:** `android/` was moved to `clients/android/` (git mv); new
  `clients/windows/` holds the WebSocket toast client; `clients/windows/legacy/`
  holds the retired `.sh` bridges + old HTTP listener for reference.
- **Protocol (three-way sync):** added `permission_resolved` `{id, reason, decision}`
  to `protocol/messages.md`, `daemon/.../protocol.py`, and
  `clients/android/.../model/Messages.kt`.
- **Daemon** (`hooks.py`, `server.py`, `main.py`): no-client passthrough,
  configurable `--permission-wait` (default 30s), expired→passthrough (no more
  auto-deny), `resolve()` broadcasts `permission_resolved{answered}`, dead-client
  pruning on send failure, `ping_interval`/`ping_timeout` heartbeat.
- **Android** (`SessionService.kt`): on `PermissionResolved` →
  `NotifBuilder.cancelPermission(id)`.
- **Windows** (`clients/windows/claude-notify-listener.ps1`): full rewrite as a
  `ClientWebSocket` client; single-threaded `Task.WaitAny` loop multiplexing WS
  receive + a loopback `HttpListener` for toast-button callbacks.

## Already verified by the originating session

- Daemon `pytest`: **78 passed** (incl. new protocol round-trip + 3 hook-flow
  tests: no-client passthrough, first-wins + resolved broadcast, expiry→passthrough).
- `ruff check` on daemon + tests: clean.
- Daemon imports + `permission_resolved` encode/decode round-trip: confirmed.
- **Could NOT verify locally:** the Android build (no `JAVA_HOME`/SDK) and the
  PowerShell client (no `pwsh` on Linux). Gradle config has no stale absolute
  paths after the move, but it has not been compiled.

## Environment / setup for the receiving session

1. Run the daemon (Linux/WSL): `claude-remote-daemon -v` (optionally
   `--permission-wait 30`). Re-run `scripts/install-hooks.sh` so
   `PermissionRequest/Notification/Stop/SessionStart/SessionEnd` point at the hook.
2. **Android:** `cd clients/android && ./gradlew installDebug` (Android Studio's
   bundled JBR works as `JAVA_HOME`). Emulator reaches the daemon at `10.0.2.2:8770`.
3. **Windows toast:** reach the daemon via SSH `LocalForward 8770 127.0.0.1:8770`
   (or LAN/WireGuard), then run
   `powershell -ExecutionPolicy Bypass -File clients\windows\claude-notify-listener.ps1 -DaemonUrl ws://127.0.0.1:8770 -Token <token>`.
   See `clients/windows/README.md`.

## Verification checklist

- [ ] Android app builds and installs from `clients/android/` (confirms the move).
- [ ] Windows: `claude-notify-listener.ps1` parses and connects; "connected to …"
      is logged; the `claudenotify:` scheme registers without admin.
- [ ] Trigger a permission request with **both** clients connected → both show a
      prompt; answering on one makes the **other dismiss** automatically.
- [ ] Answer on the **phone** → Windows toast dismisses (and vice-versa).
- [ ] **No client connected** → request falls through to the local terminal prompt
      immediately (no ~30s stall, no auto-deny).
- [ ] **No one answers within 30s** → both clients' prompts dismiss and the local
      prompt takes over (not auto-denied).
- [ ] Windows toast Approve/Deny button → Claude proceeds/blocks accordingly;
      clicking focuses the VS Code / terminal window.
- [ ] Kill one client mid-wait → daemon prunes it; the other still works.

## Known risks / things to watch

- **Hook version sensitivity:** assumes a returned `PermissionRequest` decision
  is honoured (works in this user's setup). A v2.1.119 regression (#52822) was
  reported where decisions are ignored — confirm the installed Claude Code version.
- **PowerShell rewrite is untested** (no `pwsh` on the origin box). Watch the
  `Task.WaitAny` loop, the single-outstanding-`ReceiveAsync` invariant, and the
  loopback `HttpListener` on `127.0.0.1:58737`.
- `--permission-wait` must stay below the hook CLI's 310s read timeout.
- `permission_resolved` is **not** emitted for a local-terminal answer (daemon is
  blind to it); that toast clears on expiry or manual dismissal — by design.

## Results

> Filled in by the receiving session.

- Outcome per checklist item (pass/fail + notes).
- Logs, screenshots, logcat / daemon output for any failure.
- New bugs or follow-ups (link issues/PRs).
