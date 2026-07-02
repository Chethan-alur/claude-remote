# Handoff: Android xterm terminal — display fixed, scroll pending

- **Status:** partially verified <!-- display/geometry verified on device; scroll not fixed -->
- **Date:** 2026-07-01
- **From:** Linux session (daemon host `10.141.1.136`), Opus 4.8
- **To:** Windows PC session driving the physical phone over USB (adb)
- **Branch:** `feat/unified-notification-fanout`

## Context

The Android app's in-terminal (xterm.js in a WebView) was showing **no output**
and swallowing input on new sessions. Investigation over a live phone found and
fixed a chain of bugs. Two are **verified fixed on device**; one (scrollback) is
diagnosed but **not yet fixed** and needs a decision + implementation, which is
the main job for this handoff.

The phone is on a **VPN** (address `10.212.134.151`), so wireless adb from the
Linux host to the phone does not work (inbound to the phone is NAT'd/blocked).
Hence driving from a Windows PC with the phone on **USB**.

## Changes in this handoff (working tree, not yet committed)

**Daemon — `daemon/claude_remote_daemon/server.py`:**
1. **Idempotent per-connection attach.** Added a per-connection `attached_ids`
   set threaded through `_handle → _dispatch → _attach`/`_take_over`. A session
   is auto-attached on `session_create`; the app then also sends an explicit
   `session_attach`, which previously created a *second* fan-out queue so every
   `Output` frame was delivered (and appended) twice, corrupting the render.
   `_attach` now no-ops if the connection is already subscribed.
2. **TEMP DEBUG logging (REMOVE after verification):** `logger.info("DBG recv …")`
   at the top of `_dispatch`, and `logger.info("DBG input …")` in the `Input`
   branch. These were the instrumentation used to prove input reaches the PTY.

**Daemon test — `daemon/tests/test_integration.py`:** added
`test_explicit_attach_after_create_does_not_double_subscribe` (asserts one
subscriber after create + explicit attach). 85 tests pass, ruff clean.

**Android — `clients/android/app/src/main/assets/term/term.html`:**
- **Scrollback 5000 → 20000** lines.
- **Reliable geometry negotiation:** cell size is only measurable after xterm's
  first render, so the old load/`setTimeout` fits saw null geometry and never
  reported a size (→ PTY stuck at spawn 120 cols → garbled). Now fits on
  `term.onRender` plus a bounded `ensureFit()` retry loop; `termRefit` resets the
  retry state for a reused WebView.

> **Note on diagnostics:** the verbose `term.html` `console.log`s (BUILD-3 marker,
> `fit:`, `ensureFit #`, `diagSize`) and the daemon's `DBG` logging used during
> diagnosis were **removed from source** — the committed code is clean. The
> **prebuilt `app-debug.apk` referenced below still contains them** (it was built
> from the diagnostic version), so installing that APK will still emit `BUILD-3`
> and `TermJS: fit:` lines. A **rebuild from current source will not** — re-add a
> `console.log` if you need terminal-side tracing (it will surface via the
> `TermJS` WebChromeClient, which is retained).

**Android — `clients/android/app/src/main/java/com/claude/remote/ui/TerminalScreen.kt`:**
- **WebView height fix (the display bug):** set
  `layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)` in the
  factory. Without it the WebView self-measured its empty content to **0 px
  tall** inside the Compose `Column(weight(1f))` — the terminal was `411×0`, so
  nothing rendered and no resize was possible.
- **`WebChromeClient`** forwarding JS `console` → logcat tag **`TermJS`** (plus
  imports `Log`, `ConsoleMessage`, `WebChromeClient`).

No protocol messages changed; three-way sync (`protocol/messages.md`,
`protocol.py`, `Messages.kt`) is untouched.

## Already verified by the originating session (on the physical phone)

Confirmed from device logcat + daemon log:

- **Display fixed:** `TermJS: term.html BUILD-3 loaded; el=411x697` (was `411x0`).
- **Geometry fixed:** `TermJS: fit: resized to 36x34`; PTY resized accordingly.
- **Data pipeline healthy both directions:** input reaches Claude, 616 `Output`
  frames received, permission requests delivered and answered.
- Daemon suite: `pytest` 85 passed, `ruff` clean.

**Could NOT be verified locally:** anything requiring the phone — wireless adb is
blocked by the phone's VPN, and the Linux host has no attached device, no AVD,
and no `/dev/kvm` (emulator not viable here).

## The open problem — scrollback does not scroll

**Symptom:** one-finger drag does not scroll to earlier output at all, even
though the session has more than one screen of history.

**Root cause (diagnosed, not a bug in our handler):** Claude Code's TUI switches
to the **alternate screen buffer** — `?1049h` appears in the captured output.
xterm.js keeps **no scrollback in the alt-buffer**, so the custom touch handler's
`term.scrollLines()` (in `term.html`, ~lines 112–144) is a **no-op**. The logcat
also shows repeated `Ignored attempt to cancel a touchmove event with
cancelable=false …`, i.e. the passive `touchstart` lets the WebView start a
native scroll that our `preventDefault()` can't cancel — but even fixing that
won't help, because there is no scrollback to scroll.

**Options to implement/verify (pick one):**
- **A — forward drag as keys:** translate the drag into `↑/↓` or PageUp/PageDown
  sent to the PTY so Claude scrolls *its own* view. **Uncertain** Claude Code
  responds to these for conversation scrollback; verify empirically.
- **B — accept the limitation:** terminal shows only the current view; document
  it and remove/disable the custom touch handler (also silences the
  `cancelable=false` warnings).
- If A is pursued and works, add `touch-action: none` to `#term`'s CSS so the
  gesture stays cancelable and the handler owns it cleanly.

## Environment / setup for the receiving (Windows) session

- **Daemon:** already running on the Linux host `10.141.1.136:8770` (launched
  manually, **not** systemd; logs to `/home/calur/github/claude-remote/daemon/daemon.log`).
  The phone connects to it over the LAN/VPN as configured in the app. The
  Windows PC does **not** run the daemon; it only drives the phone via adb.
- **APK (already built, BUILD-3, display+geometry fixed):**
  `/home/calur/github/claude-remote/clients/android/app/build/outputs/apk/debug/app-debug.apk`
  Copy it to the Windows PC (Windows 10+ has `scp`):
  ```powershell
  scp calur@10.141.1.136:/home/calur/github/claude-remote/clients/android/app/build/outputs/apk/debug/app-debug.apk .
  adb install -r app-debug.apk    # or: adb uninstall com.claude.remote; adb install app-debug.apk
  ```
- **Capture logcat (include the `TermJS` tag — the earlier `*:S`-only filter hid it):**
  ```powershell
  adb logcat -c
  adb logcat -v time TermJS:V SessionService:V WsClient:V AndroidRuntime:E chromium:W "*:S" > logcat.txt
  ```
- **Rebuilding after a scroll-fix edit:** needs the Android SDK + JDK 17. If the
  Windows PC has Android Studio, build there (`gradlew.bat assembleDebug`).
  Otherwise edit on the Linux host and rebuild there
  (`source /home/calur/Android/env.sh && cd clients/android && ./gradlew assembleDebug`),
  then re-copy the APK. The app package id is `com.claude.remote`.

## Verification checklist

- [ ] Install BUILD-3; **create a new session** → terminal renders readable,
      correctly-wrapped output (logcat: `BUILD-3 loaded; el=411x<non-zero>` and
      `fit: resized to <cols>x<rows>`).
- [ ] Type in both the terminal and the bottom "Send to Claude…" field → Claude
      receives it and responds.
- [ ] **Attach** an existing session from the list → same render + a `fit:
      resized` line (confirms the WebView-reuse path re-fits).
- [ ] Decide scroll approach (A/B). If A: implement drag→keys, rebuild, install,
      confirm the conversation actually scrolls.
- [ ] After scroll is settled: **remove the TEMP DEBUG daemon logging** in
      `server.py` (`DBG recv`, `DBG input`) and restart the daemon; decide
      whether to keep the `term.html` diagnostics + `TermJS` forwarding or strip
      them.

## Known risks / things to watch

- **Sessions do not survive a daemon restart** (in-memory registry) — always
  create/attach a *fresh* session after restarting the daemon; attaching a stale
  id returns `session_not_found` and looks like "nothing works."
- **Alt-screen replay:** on attach, replayed scrollback was produced at the old
  PTY width and won't rewrap; only the live screen reflows after the resize.
- **Option A may simply not scroll** the Claude conversation — treat it as an
  experiment, not a guaranteed fix.
- The `DBG` daemon logging is verbose; leaving it in ships noise to `daemon.log`.

## Results

Filled in by the receiving Windows/adb session (2026-07-01):

- **Display + geometry:** confirmed fixed on device; already merged to `main`
  (`127d106`) and reconciled with a parallel host's fixes (`d150bce`). `main`'s
  `term.html` uses `position:fixed; inset:0` + a `requestAnimationFrame` fit
  retry; `TerminalScreen.kt` sets `MATCH_PARENT` layout params and a
  `WebChromeClient`. Also folded in: an IME fix (`VISIBLE_PASSWORD |
  NO_SUGGESTIONS`) so Gboard stops composing — per-key input and Backspace now
  reach the PTY (verified: each key fires `onData`; Backspace emits DEL `0x7f`) —
  and an `onData` filter that swallows mouse-tracking reports so a tap focuses
  instead of clicking in the TUI. Scrollback raised to 50000.
- **Scroll — resolved, not via terminal scrollback.** Confirmed the diagnosis:
  Claude's alt-screen buffer holds no scrollback and scrolled-off turns are
  never re-emitted, so neither forwarding keys (Option A) nor buffering the byte
  stream can recover history. Instead added an **Android-side conversation
  history view** sourced from Claude's on-disk `.jsonl`: new `get_history` /
  `history` protocol messages (three-way sync done), daemon `read_transcript`
  (validated against a real 1.9 MB transcript → 143 msgs parsed), and a
  read-only scrollable `HistoryScreen` reached from a history icon in the
  terminal top bar. Daemon suite 90 passed, ruff clean; Android builds.
- **Pending:** live app→daemon→transcript round-trip needs the updated daemon
  deployed on the VM (its running copy predates `get_history`).

**Status → resolved** once the live round-trip is verified; safe to delete this
note thereafter.
