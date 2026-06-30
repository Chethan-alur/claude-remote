#!/usr/bin/env bash
# Claude Code Notification -> local Windows desktop toast, via SSH reverse tunnel.
#
# This runs on the REMOTE Linux host (where Claude Code executes). It posts the
# notification message to 127.0.0.1:$CLAUDE_NOTIFY_PORT, which a RemoteForward
# directive in the Windows-side ~/.ssh/config tunnels back to a listener
# (claude-notify-listener.ps1) running on the local Windows machine.
#
# It is best-effort: any failure (tunnel down, listener not running) is swallowed
# so a missing notification never blocks or breaks the Claude session.

set -o pipefail

PORT="${CLAUDE_NOTIFY_PORT:-58737}"

payload=$(cat)

# permission_prompt events are handled (with Approve/Deny buttons) by the
# PermissionRequest hook -> permission-approve-bridge.sh. Skip them here so the
# user does not get a duplicate, button-less toast for the same event.
ntype=$(jq -r '.notification_type // ""' <<<"$payload" 2>/dev/null)
[ "$ntype" = "permission_prompt" ] && exit 0

message=$(jq -r '.message // "Claude Code needs your attention"' <<<"$payload" 2>/dev/null)
[ -z "$message" ] && message="Claude Code needs your attention"

curl -s -m 3 -X POST "http://127.0.0.1:${PORT}/notify" \
  -H "Content-Type: text/plain; charset=utf-8" \
  --data-binary "$message" >/dev/null 2>&1 || true

exit 0
