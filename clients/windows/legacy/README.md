# Legacy Windows toast bridge (retired)

These files are the **previous** Windows-toast implementation, kept for
reference only. They are **not** registered as Claude Code hooks and are not
used by the current system.

How it worked: Claude Code hooks on the Linux host invoked these shell bridges,
which POSTed over an SSH **reverse** tunnel (`RemoteForward`) to an HTTP
listener running on Windows. The listener showed a toast and the bridge polled
it for the decision.

- `notify-remote-bridge.sh` — `Notification` hook → POST `/notify` → info toast.
- `permission-approve-bridge.sh` — `PermissionRequest` hook → POST `/prompt` →
  toast with Approve/Deny, then polled `/decision?id=` for the answer.
- `claude-notify-listener.ps1.http` — the HTTP-listener version of the toast app.

This was replaced because only **one** hook backend can be active at a time
(`scripts/install-hooks.sh` replaces the per-event hook arrays). The daemon is
now the single backend and fans requests out to all clients over WebSocket; the
Windows toast app became a WebSocket client (`../claude-notify-listener.ps1`).
