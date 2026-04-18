# claude-remote-daemon

Python daemon that bridges Claude Code (running on this machine) to the
Claude Remote Android app over WebSocket.

## Setup (WSL Ubuntu)

```bash
cd daemon

# Create a venv
python3 -m venv .venv
source .venv/bin/activate

# Install in editable mode
pip install -e .

# Run
claude-remote-daemon --port 8765
```

## Install the hook bridge

Claude Code calls a small CLI on every permission-relevant event. After
`pip install -e .` the `claude-remote-hook` script is on your venv's PATH,
but Claude Code needs it at a stable absolute path:

```bash
# Symlink to /usr/local/bin so Claude Code finds it from any cwd
sudo ln -s "$(pwd)/.venv/bin/claude-remote-hook" /usr/local/bin/claude-remote-hook

# Register hooks in ~/.claude/settings.json
../scripts/install-hooks.sh
```

## Run as a systemd user service (optional)

See `systemd/claude-remote.service` (TODO: claude-code can generate this
if you ask). The daemon should be run as your normal user, not root —
it spawns Claude Code which uses your `~/.claude/` config.

## Layout

```
daemon/
├── pyproject.toml
├── claude_remote_daemon/        Main package
│   ├── main.py                 Entry point, flag parsing, startup
│   ├── server.py               WebSocket server
│   ├── session.py              SessionManager + Session (PTY wrapper)
│   ├── hooks.py                Unix socket bridge for hook callbacks
│   ├── protocol.py             Wire-protocol dataclasses
│   ├── discovery.py            mDNS advertise
│   └── auth.py                 Pairing codes + device tokens
└── hook_bin/                   Tiny CLI installed as claude-remote-hook
    └── main.py
```
