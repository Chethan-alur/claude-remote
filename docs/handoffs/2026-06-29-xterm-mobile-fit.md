# Handoff: xterm terminal fit-to-screen on mobile

- **Status:** pending
- **Date:** 2026-06-29
- **From:** Linux session (Opus 4.8) — cannot build the Android app (no `JAVA_HOME`/SDK in sandbox)
- **To:** Windows session with Android Studio emulator
- **Code location:** uncommitted working-tree changes on the dev machine at the
  time of writing (the shared protocol/session files were simultaneously being
  edited by another session building the unrelated `set_handoff` permission
  feature, so the xterm code was *not* isolated onto its own branch). Sync the
  full repo state from the dev machine, or have the originating session commit
  and push once that concurrent work settles, before running the steps below.

## Context

Previously the terminal was hard-coded to a 120×40 grid, so on a phone the
rendered line was far wider than the screen and required horizontal scrolling to
read a full line. This change makes the phone **fit the terminal grid to the
WebView width** and tells the daemon to **resize the PTY** to match, so claude's
TUI reflows (via SIGWINCH) and a full line fits on screen — no horizontal scroll.

A new `resize` message was added to the wire protocol. See
[`protocol/messages.md`](../../protocol/messages.md) → *resize (phone → daemon)*.

## Changes in this handoff

Three-way protocol sync (new `resize` message, `{session, cols, rows}`):

- `protocol/messages.md` — documents `resize`.
- `daemon/claude_remote_daemon/protocol.py` — `Resize` dataclass + registry entry.
- `android/app/src/main/java/com/claude/remote/model/Messages.kt` — `Resize`.

Daemon:

- `daemon/claude_remote_daemon/session.py` — `Session.setwinsize(rows, cols)`
  calls `ptyprocess.setwinsize` (clamped cols 2–500, rows 1–300); no-op for
  dead sessions.
- `daemon/claude_remote_daemon/server.py` — dispatches `Resize` → `setwinsize`.

Android:

- `android/.../assets/term/term.html` — removes the fixed 120×40 grid; fits the
  grid to the WebView (mirrors xterm's FitAddon using the renderer's measured
  cell size, as the addon is not bundled), refits on `ResizeObserver` /
  `resize` / `orientationchange`, hides horizontal overflow, and reports the
  grid to native via `AndroidInput.resize(cols, rows)`. `fontSize` is now 12.
  Adds `window.termRefit()` to force a resize report for a reused WebView.
- `android/.../ui/TerminalScreen.kt` — new `onResize` param + `resize` JS-bridge
  method; a `LaunchedEffect(sessionId, ready)` calls `termRefit` so a newly
  opened session (same reused WebView) re-reports its dimensions.
- `android/.../ui/MainActivity.kt` — wires `onResize` → `service.resizePty`.
- `android/.../service/SessionService.kt` — `resizePty()` sends the `Resize`.

## Already verified by the originating session

- Daemon `pytest`: **59 passed**.
- `ruff check daemon/`: **clean**.
- Protocol round-trip for `Resize` encode/decode: **OK**.
- Android Kotlin compile: **NOT verified** — the sandbox has no `JAVA_HOME`, so
  `./gradlew compileDebugKotlin` could not run. Compilation of the Kotlin/JS
  changes is the first thing to confirm on the target.

## Environment / setup for the receiving session

1. Sync the repo to include the xterm changes (see *Code location* above).
2. **Daemon (WSL/Linux):** from `daemon/`, `./scripts/setup-daemon.sh` if not
   already set up, then `claude-remote-daemon -v --port 8770`. With the daemon in
   WSL2, Windows `localhost:8770` reaches it (default `localhostForwarding`).
3. **Emulator:** the debug build's `DEFAULT_DAEMON_HOST` is `10.0.2.2`, which is
   the Windows host loopback from inside the emulator → reaches the daemon.
4. **Build/install:** `cd android && ./gradlew installDebug` (set `JAVA_HOME`;
   Android Studio's bundled JBR works). Pair/connect to the daemon, then create
   or open a session to reach the terminal screen.

## Verification checklist

- [ ] App compiles and installs (`./gradlew installDebug` succeeds).
- [ ] Open a session terminal — a full claude output line fits the screen width
      with **no horizontal scrolling**.
- [ ] claude's TUI (input box, permission menus, boxes) is reflowed to the
      narrower width and not clipped on the right edge.
- [ ] Rotate to landscape and back — the terminal **refits** each time; no
      horizontal scroll in either orientation.
- [ ] Toggle the control-key row (keyboard icon in the top bar) — terminal
      refits to the new height; columns unchanged, no layout breakage.
- [ ] Show/hide the soft keyboard — refit works, no broken or stuck layout.
- [ ] Open a *different* session in the same app run — its terminal also fits
      (PTY is not stuck at the daemon's default 120 cols). Verify with a wide
      claude output: it wraps at screen width, not at 120.
- [ ] Confirm the daemon actually resized: claude redraws at the narrower width
      shortly after the terminal opens (SIGWINCH took effect).
- [ ] Reconnect (stop/start the daemon or toggle connectivity) — after reconnect
      the terminal still fits the screen.
- [ ] `fontSize: 12` is legible and the resulting column count is workable for
      claude's TUI on the test device.

## Known risks / things to watch

- **Column count vs. legibility:** `fontSize` is 12. On a small/narrow phone
  this may yield too few columns and a cramped claude TUI. If so, lower to 11 in
  `term.html` (`new Terminal({ fontSize: ... })`) and re-test.
- **xterm internals:** `proposeGeometry()` reads
  `term._core._renderService.dimensions` (the same path FitAddon uses) because
  the FitAddon is not bundled. It is wrapped in try/catch and **fails safe** —
  on error it sends no resize, leaving the PTY at the default 120×40. If fitting
  silently does nothing, this is the place to check (and consider bundling the
  real `xterm-addon-fit`).
- **Early measurement:** before the renderer measures a cell, geometry is null;
  handled by `setTimeout(fit, 0)` + `setTimeout(fit, 250)` and the
  `ResizeObserver`. If the first fit is wrong, increase the delay.
- **Replay garbling:** on attach, the daemon replays buffered 120-col output,
  which looks wrapped until claude redraws after the resize. The live view
  self-heals on the next claude redraw; only old scrollback stays wrapped.

## Results

> Filled in by the receiving (Windows) session.

- _Pending._
