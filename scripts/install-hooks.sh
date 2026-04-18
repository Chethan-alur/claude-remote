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
  echo "Build and install it first:" >&2
  echo "  cd daemon/hook-bin && go build -o claude-remote-hook ." >&2
  echo "  sudo mv claude-remote-hook /usr/local/bin/" >&2
  exit 1
fi

mkdir -p "$(dirname "$SETTINGS")"
[[ -f "$SETTINGS" ]] || echo '{}' > "$SETTINGS"
cp "$SETTINGS" "${SETTINGS}.bak.$(date +%s)"

# Build the hook config we want to merge in.
HOOK_CONFIG=$(cat <<JSON
{
  "hooks": {
    "PreToolUse": [{
      "matcher": "Bash|Edit|Write|WebFetch",
      "hooks": [{"type": "command", "command": "${HOOK_BIN}"}]
    }],
    "Stop": [{
      "hooks": [{"type": "command", "command": "${HOOK_BIN}"}]
    }],
    "Notification": [{
      "hooks": [{"type": "command", "command": "${HOOK_BIN}"}]
    }]
  }
}
JSON
)

# Merge with existing settings (our keys win).
TMP=$(mktemp)
jq --argjson new "$HOOK_CONFIG" '. * $new' "$SETTINGS" > "$TMP"
mv "$TMP" "$SETTINGS"

echo "Installed hooks pointing at: $HOOK_BIN"
echo "Settings file: $SETTINGS"
echo "Backup saved alongside with .bak.<timestamp> suffix."
