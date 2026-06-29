#!/usr/bin/env bash
# Installs the Claude Remote daemon as a systemd *user* service.
#
# A user service (not a system service) is deliberate: the daemon spawns
# Claude Code, which reads your personal ~/.claude/ configuration, and it
# stores its own state under ~/.claude-remote/. Running it as root would use
# the wrong home directory and the wrong credentials.
#
# Idempotent: safe to run repeatedly. It re-renders the unit from the template
# every run (picking up a moved checkout), but never overwrites your edited
# environment file.
#
# Requires: systemd with user-session support (systemctl --user).
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
DAEMON_DIR="$HERE/daemon"
DAEMON_BIN="$DAEMON_DIR/.venv/bin/claude-remote-daemon"
TEMPLATE="$DAEMON_DIR/systemd/claude-remote.service"
ENV_EXAMPLE="$DAEMON_DIR/systemd/claude-remote.env.example"

UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
UNIT_FILE="$UNIT_DIR/claude-remote.service"
ENV_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/claude-remote"
ENV_FILE="$ENV_DIR/daemon.env"

if ! command -v systemctl >/dev/null 2>&1; then
  echo "error: systemctl not found. This host does not use systemd; run the" >&2
  echo "       daemon manually instead (see docs/DEPLOYMENT.md)." >&2
  exit 1
fi

if [[ ! -x "$DAEMON_BIN" ]]; then
  echo "error: $DAEMON_BIN not found or not executable." >&2
  echo "Set the daemon up first:" >&2
  echo "  ./scripts/setup-daemon.sh" >&2
  exit 1
fi

if [[ ! -f "$TEMPLATE" ]]; then
  echo "error: service template missing: $TEMPLATE" >&2
  exit 1
fi

# Seed the environment file from the example on first install only.
mkdir -p "$ENV_DIR"
if [[ ! -f "$ENV_FILE" ]]; then
  cp "$ENV_EXAMPLE" "$ENV_FILE"
  echo "Created $ENV_FILE (edit it to change port/bind/flags)."
else
  echo "Keeping existing $ENV_FILE."
fi

# Render the unit by substituting the absolute paths. sed with a non-/ delimiter
# avoids escaping the path separators.
mkdir -p "$UNIT_DIR"
sed \
  -e "s|@DAEMON_BIN@|${DAEMON_BIN}|g" \
  -e "s|@WORKDIR@|${DAEMON_DIR}|g" \
  -e "s|@ENVFILE@|${ENV_FILE}|g" \
  "$TEMPLATE" > "$UNIT_FILE"
echo "Wrote $UNIT_FILE"

systemctl --user daemon-reload
systemctl --user enable --now claude-remote.service

echo
echo "Service installed and started. Useful commands:"
echo "  systemctl --user status claude-remote      # health + recent logs"
echo "  journalctl --user -u claude-remote -f      # follow logs (pairing code is here)"
echo "  systemctl --user restart claude-remote     # after editing $ENV_FILE"
echo
echo "By default a user service stops when you log out. To keep the daemon"
echo "running across logout and reboot, enable lingering once (needs sudo):"
echo
echo "  sudo loginctl enable-linger \"$USER\""
echo
echo "The pairing code is printed to the journal at startup; retrieve it with:"
echo "  journalctl --user -u claude-remote | grep -i 'pairing code' | tail -1"
