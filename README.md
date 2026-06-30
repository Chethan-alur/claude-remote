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

- **daemon/** — Python package that runs on your dev machine. Spawns `claude` inside a PTY, exposes an asyncio WebSocket server on the LAN, advertises itself via mDNS, and bridges Claude Code's permission hooks to/from every connected client.
- **clients/android/** — Kotlin + Jetpack Compose app. Discovers daemons via mDNS, holds a foreground-service WebSocket, shows live terminal output, and surfaces permission requests as notification action buttons (Allow / Deny / Always) that work from the lock screen.
- **clients/windows/** — PowerShell WebSocket client that shows native Windows toasts with Approve/Deny buttons (`legacy/` holds the retired HTTP-over-SSH-tunnel bridges).
- **protocol/** — JSON message schemas shared between all sides.
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
cd ../clients/android
./gradlew installDebug
```

## Tech choices

- **Python (asyncio)** for the daemon: fastest path for a Python developer to iterate on. `websockets`, `ptyprocess`, and `zeroconf` are the core deps. PTY reads use `loop.add_reader()` (Linux/WSL only) for clean async integration.
- **Kotlin + Compose** for the app: modern Android, declarative UI, `NsdManager` for mDNS, `OkHttp` for WebSocket, `RemoteInput` for voice replies on lock-screen notifications.
- **JSON over WebSocket**: simplest possible wire format. See `protocol/messages.md`.
- **Claude Code hooks**: the load-bearing trick. Instead of scraping terminal output, we hook `PreToolUse`, `Stop`, and `Notification` events directly. Cleanest possible integration.
