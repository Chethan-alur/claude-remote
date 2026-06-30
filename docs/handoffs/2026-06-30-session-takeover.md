# Handoff: session takeover / resume (Android ⇄ desktop)

- **Status:** pending
- **Date:** 2026-06-30
- **From:** Linux session, Opus 4.8 (no Java/Android SDK)
- **To:** Windows session with Android Studio emulator + a real desktop `claude`
- **Branch:** `feat/unified-notification-fanout`

## Context

Adopted (desktop/VSCode) sessions were permission-only. This adds **takeover**:
the phone can resume a desktop conversation as a daemon-owned session (gaining
full prompt control), and each side **kills the other's copy** so one transcript
is never written by two processes. See `docs/notification-flow.md`,
`protocol/messages.md` (`take_over`), and the plan
`~/.claude/plans/okay-i-think-we-radiant-island.md`.

## Changes in this handoff

- **Protocol (three-way sync):** new `take_over { id }` (phone→daemon) in
  `protocol/messages.md`, `daemon/.../protocol.py`, `clients/android/.../Messages.kt`;
  plus a `warning` notification kind.
- **Daemon `session.py`:** `Session.cc_session_id` + `Session.claude_pid`;
  `adopt(cc_id, cwd, pid)`; `spawned_by_cc_id()`; resume sets `cc_session_id`.
- **Daemon `hooks.py`:** resolves the external claude pid from the hook socket's
  peer credentials (`SO_PEERCRED`) + `/proc` walk (hook CLI unchanged); records
  `cc_session_id`/`claude_pid`; **Direction B** — an external `SessionStart` for a
  conversation the daemon owns kills the daemon's copy (regardless of handoff).
- **Daemon `server.py`:** `take_over` dispatch → best-effort `SIGTERM` the desktop
  claude → `claude --resume <cc_id>` as a new spawned session → drop the adopted
  record → warn if the desktop could not be stopped.
- **Android:** `SessionService.takeOver(id)`; "Take over" item in the session-card
  menu for adopted sessions with a **confirm dialog**; daemon `warning`/`error`
  notifications surfaced via the existing transient-message channel; terminal
  input auto-enables when `origin` flips to `spawned`.

## Already verified by the originating session

- Daemon `pytest`: **84 passed** (new: `take_over` round-trip; `adopt` stores
  cc_id+pid; spawned session records cc_id from first hook; Direction B kills the
  daemon-owned copy; daemon-owned resume does **not** self-collide; a non-
  SessionStart external hook does **not** trip the kill). `ruff` clean.
- **Could NOT verify locally:** Android build (no SDK) and real desktop behaviour
  (SO_PEERCRED PID resolution, actual SIGTERM, real `claude --resume`).

## Environment / setup

1. Daemon on Linux/WSL: `claude-remote-daemon -v`; hooks installed
   (`scripts/install-hooks.sh`, incl. `SessionStart`/`SessionEnd`). Enable
   desktop→mobile handoff from the app so desktop sessions are adopted.
2. Android: `cd clients/android && ./gradlew installDebug`.
3. Have a real `claude` running on the daemon host (terminal or VS Code) in a
   project dir, so it is adopted.

## Verification checklist

- [ ] Android build succeeds; "Take over" appears **only** on desktop (adopted)
      sessions and shows the confirm dialog.
- [ ] Tapping "Take over" → the desktop `claude` process **exits** (SIGTERM
      worked) and a new session appears with terminal input **enabled**; typing a
      prompt drives Claude.
- [ ] If the desktop process cannot be stopped (e.g. kill the daemon's ability to
      see it), takeover still resumes and a **warning** is surfaced in the app.
- [ ] **Direction B:** with the daemon driving a session, run `claude --resume
      <same id>` on the desktop → the daemon-owned session **disappears** from the
      app (killed) and the desktop drives it; with handoff on, permissions still
      reach the phone.
- [ ] A daemon-spawned session is **not** killed by its own startup hooks.
- [ ] No transcript corruption: after a takeover in either direction, only one
      `claude` is writing the conversation.

## Known risks / things to watch

- **Best-effort desktop kill** via `SO_PEERCRED` + `/proc` (Linux-only; assumes
  claude runs on the daemon host — true, since the hook hits the daemon's local
  socket). On WSL/VS Code, confirm the pid walk finds the real `claude` (the kill
  re-checks `/proc/<pid>/cmdline` before `SIGTERM`). If it can't, takeover
  proceeds + warns (by design).
- SIGTERM ends the desktop session; unsaved REPL state there is lost (hence the
  confirm dialog).
- `--resume` runs in the adopted session's stored `cwd`; verify the conversation
  is found (must be the project dir the transcript belongs to).
- The server-level `take_over` orchestration is covered by manual test here (not
  unit-tested) — exercise both the success and kill-failure paths.

## Results

> Filled in by the receiving session.
