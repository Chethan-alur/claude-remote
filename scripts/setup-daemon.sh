#!/usr/bin/env bash
# Sets up the daemon's Python venv and installs the package + hook CLI.
# Idempotent: safe to run multiple times.
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
DAEMON_DIR="$HERE/daemon"
VENV_DIR="$DAEMON_DIR/.venv"

if ! command -v python3 >/dev/null 2>&1; then
  echo "error: python3 not found. Install with: sudo apt install python3 python3-venv" >&2
  exit 1
fi

# Need 3.10+ for the match/case statements and modern type hints we use.
PY_VER=$(python3 -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}")')
PY_MAJOR=${PY_VER%.*}; PY_MINOR=${PY_VER#*.}
if [[ "$PY_MAJOR" -lt 3 ]] || { [[ "$PY_MAJOR" -eq 3 ]] && [[ "$PY_MINOR" -lt 10 ]]; }; then
  echo "error: need Python 3.10+, found $PY_VER" >&2
  exit 1
fi

if [[ ! -d "$VENV_DIR" ]]; then
  echo "Creating venv at $VENV_DIR..."
  python3 -m venv "$VENV_DIR"
fi

# shellcheck source=/dev/null
source "$VENV_DIR/bin/activate"
pip install --upgrade pip setuptools wheel
pip install -e "$DAEMON_DIR"

echo
echo "Installed."
echo
echo "Next step — symlink the hook CLI to a stable absolute path so"
echo "Claude Code can find it from any cwd:"
echo
echo "  sudo ln -sf '$VENV_DIR/bin/claude-remote-hook' /usr/local/bin/claude-remote-hook"
echo
echo "Then register the hooks:"
echo
echo "  $HERE/scripts/install-hooks.sh"
echo
echo "Then run the daemon:"
echo
echo "  source '$VENV_DIR/bin/activate'"
echo "  claude-remote-daemon -v"
