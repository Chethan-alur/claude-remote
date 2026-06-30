#!/usr/bin/env bash
# Claude Code PermissionRequest -> interactive Approve/Deny via Windows toast.
#
# Runs on the REMOTE host when Claude needs permission for a tool call. It asks
# the local Windows listener (over the SSH reverse tunnel) to show a toast with
# Approve/Deny buttons, then blocks polling for the user's choice and returns
# the decision to Claude Code.
#
# SAFETY: every failure path (listener down, bad response, timeout) falls
# through to the normal in-VSCode permission prompt -- it can never lock you out.

set -o pipefail

PORT="${CLAUDE_NOTIFY_PORT:-58737}"
BASE="http://127.0.0.1:${PORT}"
TIMEOUT_SECS="${CLAUDE_APPROVE_TIMEOUT:-55}"   # keep < the hook's configured timeout

# Fall through to the normal permission UI (no decision from us).
passthrough() { printf '{"continue": true}\n'; exit 0; }

payload=$(cat)

tool=$(jq -r '.tool_name // "tool"' <<<"$payload" 2>/dev/null) || tool="tool"

# Lead with Claude's human-readable description (same text the in-VSCode prompt
# shows, e.g. "Confirm shogunservice ... post-rebase"), then the command/target.
# Toasts truncate long text, so the useful summary must come first.
summary=$(jq -r '
  ((.tool_input.description // "") | gsub("\n"; " ")) as $desc
  | (if .tool_name == "Bash" then (.tool_input.command // "")
     elif (.tool_input.file_path? // empty) != "" then (.tool_input.file_path)
     elif (.tool_input.url? // empty) != "" then (.tool_input.url)
     else (.tool_input | tostring) end) as $detail
  | if $desc != "" then ($desc + " — " + $detail) else $detail end' \
  <<<"$payload" 2>/dev/null)
summary=${summary:0:300}
msg="${tool}: ${summary}"

id=$(cat /proc/sys/kernel/random/uuid 2>/dev/null || echo "req-$$-${RANDOM}")

req=$(jq -nc --arg id "$id" --arg message "$msg" '{id:$id, message:$message}' 2>/dev/null) || passthrough

# Show the toast. If the listener/tunnel is unreachable, fall through.
curl -s -m 3 -X POST "${BASE}/prompt" -H "Content-Type: application/json" \
  --data-binary "$req" >/dev/null 2>&1 || passthrough

# Poll for the user's choice.
deadline=$(( $(date +%s) + TIMEOUT_SECS ))
while [ "$(date +%s)" -lt "$deadline" ]; do
  d=$(curl -s -m 3 "${BASE}/decision?id=${id}" 2>/dev/null)
  case "$d" in
    allow)
      printf '{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"allow"}}}\n'
      exit 0 ;;
    deny)
      printf '{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"deny"}}}\n'
      exit 0 ;;
  esac
  sleep 1
done

# Timed out waiting for a click -> hand off to the normal prompt.
passthrough
