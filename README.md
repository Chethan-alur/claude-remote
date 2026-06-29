# Claude Remote

Control Claude Code on your dev machine from your Android phone. Get push notifications when Claude needs permission, approve or deny from the lock screen, and stop babysitting long-running tasks.

## Architecture (POC: local network only)

```
[Android phone]  <-- WebSocket over LAN -->  [daemon on dev machine]
                                                     |
                                                     | spawns + hooks
                                                     v
                                              [Claude Code CLI]
```

- **daemon/** — Python package that runs on your dev machine. Spawns `claude` inside a PTY, exposes an asyncio WebSocket server on the LAN, advertises itself via mDNS, and bridges Claude Code's permission hooks to/from the phone.
- **android/** — Kotlin + Jetpack Compose app. Discovers daemons via mDNS, holds a foreground-service WebSocket, shows live terminal output, and surfaces permission requests as notification action buttons (Allow / Deny / Always) that work from the lock screen.
- **protocol/** — JSON message schemas shared between the two sides.
- **docs/** — Design doc, protocol spec, hook integration notes.
- **scripts/** — Setup helpers.

## Status

Skeleton with working scaffolding. Designed so Claude Code can pick it up and fill in the implementation. See `docs/IMPLEMENTATION_PLAN.md` for the day-by-day build order, and `docs/FOR_CLAUDE_CODE.md` for instructions to hand to your Claude Code session.

## Quick start

```bash
# 1. Install the daemon (creates a venv, installs deps)
cd daemon
python3 -m venv .venv
source .venv/bin/activate
pip install -e .

# 2. Symlink the hook CLI to a stable absolute path
sudo ln -s "$(pwd)/.venv/bin/claude-remote-hook" /usr/local/bin/claude-remote-hook

# 3. Register hooks with Claude Code
../scripts/install-hooks.sh

# 4. Run the daemon (it spawns Claude Code on demand)
claude-remote-daemon -v

# 5. Build and install the Android app
cd ../android
./gradlew installDebug
```

## Desktop ↔ mobile permission handoff

By default the phone only sees sessions the **daemon spawned** (via the app's
"New session"). A `claude` you start yourself on the desktop — e.g. in the VS
Code extension or a terminal — keeps its own in-editor permission prompt and the
daemon leaves it alone. **Handoff** lets you move those desktop permission
prompts to the phone (and back), so you can step away from the desk mid-task and
still Allow/Deny from your pocket.

Two session origins show up in the app's session list:

- **`spawned`** — launched by the daemon in a PTY. Full control: live terminal,
  input, and permissions. Always routed to the phone.
- **`adopted`** — an external desktop session the daemon "adopted" for handoff.
  Marked with a **`desktop`** badge. **Permission-only**: no terminal output and
  the input box is disabled (the daemon doesn't own its PTY) — you get its
  Allow/Deny prompts and notifications, nothing more.

### One-time setup

Handoff relies on the daemon seeing the desktop session's lifecycle hooks, so
the hook set must include `SessionStart` / `SessionEnd`. Re-run the installer
(it's idempotent and backs up `~/.claude/settings.json` first):

```bash
./scripts/install-hooks.sh
# Registers: PermissionRequest, Notification, Stop, SessionStart, SessionEnd
```

Restart any `claude` you already had open so it picks up the new hooks.

### Desktop → mobile (move prompts to the phone)

1. Open the app and connect to the daemon.
2. On the daemon home screen, turn on **"Receive desktop permissions"**.
3. The next time a desktop session fires a hook, the daemon **adopts** it: it
   appears in the session list with a `desktop` badge, and from then on its
   permission prompts and notifications arrive on the phone (lock-screen
   Allow / Deny / Always, like any spawned session). `SessionStart` makes a
   freshly-started desktop session show up right away.

While the toggle is on, the daemon also adopts any *other* external `claude`
sessions on their next hook event — handoff is daemon-wide, not per-session.

### Mobile → desktop (hand control back)

Turn **"Receive desktop permissions"** off. From then on, external sessions are
left alone again: their next permission prompt falls back to the normal desktop
prompt in VS Code / the terminal. (Spawned sessions are unaffected — they always
go to the phone.) An adopted session's card lingers until the session ends, but
its prompts have already reverted to the desktop. The daemon also drops adopted
sessions automatically on `SessionEnd`, and prunes any left idle past a TTL.

### Under the hood

The toggle is the `set_handoff` message (`{ "enabled": true|false }`); the daemon
echoes the current state to every connected phone via `handoff_state` and in
`welcome.handoff_enabled` on connect, so a reconnecting phone restores its switch
and all phones stay in sync. The flag defaults to **off** and is daemon-wide
(single-user POC). See `protocol/messages.md` → *set_handoff* / *handoff_state*.

## Tech choices

- **Python (asyncio)** for the daemon: fastest path for a Python developer to iterate on. `websockets`, `ptyprocess`, and `zeroconf` are the core deps. PTY reads use `loop.add_reader()` (Linux/WSL only) for clean async integration.
- **Kotlin + Compose** for the app: modern Android, declarative UI, `NsdManager` for mDNS, `OkHttp` for WebSocket, `RemoteInput` for voice replies on lock-screen notifications.
- **JSON over WebSocket**: simplest possible wire format. See `protocol/messages.md`.
- **Claude Code hooks**: the load-bearing trick. Instead of scraping terminal output, we hook `PreToolUse`, `Stop`, and `Notification` events directly. Cleanest possible integration.
