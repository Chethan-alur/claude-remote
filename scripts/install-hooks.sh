#!/usr/bin/env bash
# Adds claude-remote-hook entries to ~/.claude/settings.json
#
# Idempotent: safe to run multiple times. Backs up the existing settings
# file once with a .bak suffix.
#
# Requires: jq

set -euo pipefail

SETTINGS="${HOME}/.claude/settings.json"
HOOK_BIN="${HOOK_BIN:-/usr/local/bin/claude-remote-hook}"

if ! command -v jq >/dev/null 2>&1; then
  echo "error: jq is required. Install with: sudo apt install jq" >&2
  exit 1
fi

if [[ ! -x "$HOOK_BIN" ]]; then
  echo "error: $HOOK_BIN not found or not executable." >&2
  echo "Install the daemon, then symlink the hook CLI to a stable path:" >&2
  echo "  ./scripts/setup-daemon.sh" >&2
  echo "  sudo ln -sf \"\$(pwd)/daemon/.venv/bin/claude-remote-hook\" /usr/local/bin/claude-remote-hook" >&2
  exit 1
fi

mkdir -p "$(dirname "$SETTINGS")"
[[ -f "$SETTINGS" ]] || echo '{}' > "$SETTINGS"
cp "$SETTINGS" "${SETTINGS}.bak.$(date +%s)"

# Build the hook config we want to merge in.
#
# Targets Claude Code 2.1.x, which surfaces permission decisions through the
# `PermissionRequest` event (not `PreToolUse`). Registering this points those
# events at the daemon; because jq's `*` replaces the per-event arrays, this
# also REPLACES any previous handlers (e.g. a Windows-toast bridge) — i.e. it
# switches the active remote-control backend to the daemon.
HOOK_CONFIG=$(cat <<JSON
{
  "hooks": {
    "PermissionRequest": [{
      "matcher": "*",
      "hooks": [{"type": "command", "command": "${HOOK_BIN}"}]
    }],
    "Notification": [{
      "hooks": [{"type": "command", "command": "${HOOK_BIN}"}]
    }],
    "Stop": [{
      "hooks": [{"type": "command", "command": "${HOOK_BIN}"}]
    }]
  }
}
JSON
)

# Merge with existing settings (our keys win; per-event arrays are replaced).
TMP=$(mktemp)
jq --argjson new "$HOOK_CONFIG" '. * $new' "$SETTINGS" > "$TMP"
mv "$TMP" "$SETTINGS"

echo "Installed hooks pointing at: $HOOK_BIN"
echo "Settings file: $SETTINGS"
echo "Backup saved alongside with .bak.<timestamp> suffix."
echo
echo "Registered events: PermissionRequest, Notification, Stop."
echo "Only sessions spawned by the daemon (CLAUDE_REMOTE_SESSION set) are"
echo "routed to the phone; other claude sessions pass through untouched."
