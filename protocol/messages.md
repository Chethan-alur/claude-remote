# Wire Protocol

JSON over WebSocket. One message per frame. UTF-8.

Every frame is a JSON object with a required `type` field. No envelope, no version field for the POC — we'll add `v` if we ever break compatibility.

## Connection lifecycle

1. Phone opens WebSocket to `ws://<daemon-host>:8765/`
2. Phone sends `hello` with its device token
3. Daemon replies with `welcome` listing existing sessions, or `error` if the token is unknown
4. Phone sends `session_attach` for the session it wants, or `session_create` for a new one
5. Daemon starts streaming `output`, `permission_request`, `notification` frames
6. Phone sends `input`, `permission_response` frames as needed
7. Either side sends `ping` every ~20s to keep the connection alive

## Messages

### hello (phone → daemon)

```json
{ "type": "hello", "token": "dev_a1b2c3d4..." }
```

First frame after connection. If `token` is missing or unrecognized, daemon closes with code 4001.

### welcome (daemon → phone)

```json
{
  "type": "welcome",
  "daemon_version": "0.1.0",
  "hostname": "mac-studio",
  "sessions": [
    { "id": "sess_8a3f", "name": "webapp-refactor", "cwd": "/home/josh/code/webapp", "status": "waiting" },
    { "id": "sess_2c1d", "name": "api-server",      "cwd": "/home/josh/code/api",    "status": "running" }
  ]
}
```

Status values: `running`, `waiting` (permission pending), `idle` (Claude finished, awaiting next prompt), `dead` (process exited).

### session_create (phone → daemon)

```json
{ "type": "session_create", "name": "new-feature", "cwd": "/home/josh/code/webapp" }
```

Daemon spawns a `claude` process in the given cwd. Replies with `session_created` on success, `error` on failure.

### session_created (daemon → phone)

```json
{ "type": "session_created", "id": "sess_4f2a", "name": "new-feature", "cwd": "/home/josh/code/webapp" }
```

### session_attach (phone → daemon)

```json
{ "type": "session_attach", "id": "sess_8a3f", "replay_bytes": 65536 }
```

Phone wants to start receiving frames for this session. Optional `replay_bytes` asks the daemon to send the last N bytes of buffered output first (for terminal restoration after reconnect).

### output (daemon → phone)

```json
{ "type": "output", "session": "sess_8a3f", "data": "● Reading auth.ts...\n", "stream": "stdout" }
```

Raw bytes from the PTY. Daemon should batch — one frame per ~16ms of output is fine. `stream` is `stdout` or `stderr`. ANSI escape codes are passed through unmodified; the phone's terminal renderer interprets them.

### input (phone → daemon)

```json
{ "type": "input", "session": "sess_8a3f", "data": "continue\n" }
```

Bytes to write to the PTY. Include trailing newline if you want the line submitted.

### permission_request (daemon → phone)

```json
{
  "type": "permission_request",
  "id": "req_8a3f01",
  "session": "sess_8a3f",
  "tool": "Edit",
  "input": { "file_path": "src/auth.ts", "old_string": "...", "new_string": "..." },
  "summary": "Edit src/auth.ts (3 changes)",
  "received_at": 1729267200
}
```

Daemon emits this when a `PreToolUse` hook fires for a tool the user has not pre-approved. The phone shows a notification and/or in-app card. `summary` is a single-line human description suitable for a notification body. `input` is the raw tool input — phone can show it expanded if user taps for detail.

The daemon waits up to 5 minutes for a response. After timeout, it auto-denies and emits a `notification` explaining why.

### permission_response (phone → daemon)

```json
{ "type": "permission_response", "id": "req_8a3f01", "decision": "allow" }
```

Decision values:
- `allow` — this one tool call only
- `deny` — this one tool call only, with reason "user denied"
- `allow_always` — allow all future calls of this tool with the same input shape (per-session)
- `deny_always` — deny all future calls (per-session)

The daemon converts this to the JSON Claude Code expects from a hook (`{"decision": "approve"}` or `{"decision": "block", "reason": "..."}`) and writes it to the hook script's stdout via the Unix socket reply.

### notification (daemon → phone)

```json
{
  "type": "notification",
  "session": "sess_8a3f",
  "kind": "task_complete",
  "message": "Refactor complete · 3 files changed",
  "ts": 1729267260
}
```

Kinds:
- `task_complete` — fired when `Stop` hook runs (Claude finished its turn)
- `error` — Claude or the daemon hit an error worth surfacing
- `permission_timeout` — auto-denied a permission after 5 min
- `info` — generic info (used sparingly)

The phone should turn this into a heads-up notification.

### ping / pong (either direction)

```json
{ "type": "ping", "ts": 1729267200 }
{ "type": "pong", "ts": 1729267200 }
```

Heartbeat. Phone sends `ping` every 20s; daemon replies with `pong`. If daemon doesn't receive a `ping` for 60s, it assumes the phone is gone and closes the WS — but keeps the session and its PTY alive so the phone can reattach.

### error (either direction)

```json
{ "type": "error", "code": "session_not_found", "message": "No session with id sess_xxx" }
```

Sent in response to a malformed or invalid request. Connection stays open.

Error codes:
- `bad_token` — auth failed (sent before close)
- `session_not_found`
- `session_creation_failed` — bad cwd, claude not on PATH, etc.
- `bad_message` — couldn't parse JSON or unknown type
- `internal` — daemon bug

## Pairing (out of band)

Pairing is not part of the WS protocol — it happens once over a separate HTTP endpoint.

```
POST http://<daemon>:8765/pair
Body: { "code": "123456", "device_name": "Pixel 8" }
Returns: { "token": "dev_a1b2c3d4..." }
```

The 6-digit `code` is printed by the daemon at startup and rotates if unused for 10 minutes. The returned `token` is what the phone sends in `hello` for all subsequent connections.
