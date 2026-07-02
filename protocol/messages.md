# Wire Protocol

JSON over WebSocket. One message per frame. UTF-8.

Every frame is a JSON object with a required `type` field. No envelope, no version field for the POC — we'll add `v` if we ever break compatibility.

## Connection lifecycle

1. Phone opens WebSocket to `ws://<daemon-host>:8770/`
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
  "handoff_enabled": false,
  "sessions": [
    { "id": "sess_8a3f", "name": "webapp-refactor", "cwd": "/home/josh/code/webapp", "status": "waiting", "origin": "spawned" },
    { "id": "sess_2c1d", "name": "api-server",      "cwd": "/home/josh/code/api",    "status": "running", "origin": "spawned" }
  ]
}
```

Status values: `running`, `waiting` (permission pending), `idle` (Claude finished, awaiting next prompt), `dead` (process exited).

`handoff_enabled` reports the current desktop→mobile handoff state (see `set_handoff`), so a reconnecting phone restores its toggle. `origin` is `spawned` (a session the daemon launched in a PTY) or `adopted` (an external session, e.g. one started in VSCode, whose permission prompts the daemon is forwarding — see `set_handoff`).

### session_create (phone → daemon)

```json
{ "type": "session_create", "name": "new-feature", "cwd": "/home/josh/code/webapp", "resume_id": "" }
```

Daemon spawns a `claude` process in the given cwd. Replies with `session_created` on success, `error` on failure. If `resume_id` is non-empty, the daemon resumes that past session (`claude --resume <resume_id>`) instead of starting fresh; `name` is then just a label (use the session's title).

### list_sessions (phone → daemon)

```json
{ "type": "list_sessions", "cwd": "/home/josh/code/webapp" }
```

Asks for the past Claude Code sessions stored on disk for that project folder (what `claude --resume` / the VS Code extension lists). Daemon replies with `project_sessions`.

### delete_session (phone → daemon)

```json
{ "type": "delete_session", "cwd": "/home/josh/code/webapp", "id": "976f4811-..." }
```

Deletes a past session's transcript on disk (the `<id>.jsonl` under the project's
history dir) — the same action as the VS Code extension's delete. `id` must be a bare
session id (file stem); the daemon refuses ids containing path separators. After deleting,
the daemon replies with a refreshed `project_sessions` for that `cwd` (so the picker
updates), or `error` (`bad_message`) on an invalid id.

### project_sessions (daemon → phone)

```json
{
  "type": "project_sessions",
  "cwd": "/home/josh/code/webapp",
  "sessions": [
    { "id": "976f4811-...", "title": "Refactor auth module", "modified": 1729267200, "messages": 42 }
  ]
}
```

`id` is the Claude session id (pass it back as `resume_id` in `session_create` to resume). `title` is the session's `ai-title` (or its first message). `modified` is epoch seconds of the transcript's last write; `messages` is a rough user+assistant turn count. Newest first.

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

### resize (phone → daemon)

```json
{ "type": "resize", "session": "sess_8a3f", "cols": 56, "rows": 38 }
```

Resizes the session's PTY to `cols` × `rows` character cells and sends SIGWINCH, so claude's TUI redraws to the client's width. The phone fits its terminal grid to the screen and reports the result, which is how a full line fits without horizontal scrolling. The daemon clamps to sane bounds (cols 2–500, rows 1–300) and ignores resizes for unknown or dead sessions.

### file_upload (phone → daemon)

```json
{ "type": "file_upload", "session": "sess_8a3f", "filename": "screenshot.png", "upload_id": "u_1a2b", "seq": 0, "total": 1, "data": "iVBORw0KGgo..." }
```

Uploads a file (image or any binary) to the session's project so Claude can read it. The file is split into chunks to keep each WS frame bounded: `upload_id` groups the chunks, `seq` is the 0-based index, `total` the chunk count, and `data` is the base64 of this chunk. The daemon reassembles the chunks, writes the file under `<cwd>/uploads/` (basename only — path components in `filename` are stripped; colliding names get a `-1`, `-2`, … suffix), and replies with `file_uploaded` once the last chunk (`seq == total - 1`) arrives. On failure it replies with `error` (`upload_failed`). An empty file is sent as a single chunk with `total: 1` and empty `data`.

### file_uploaded (daemon → phone)

```json
{ "type": "file_uploaded", "session": "sess_8a3f", "upload_id": "u_1a2b", "path": "/home/josh/code/webapp/uploads/screenshot.png" }
```

Acknowledges a completed `file_upload`. `path` is the saved absolute path on the daemon host; the phone inserts it into the prompt draft so the file becomes part of the next message to Claude.

### permission_request (daemon → phone)

```json
{
  "type": "permission_request",
  "id": "req_8a3f01",
  "session": "sess_8a3f",
  "tool": "Edit",
  "input": { "file_path": "src/auth.ts", "old_string": "...", "new_string": "..." },
  "summary": "Edit src/auth.ts (3 changes)",
  "received_at": 1729267200,
  "session_name": "webapp"
}
```

`session_name` is the session's human name/project (the daemon's `Session.name`), so a client that keeps no session list — e.g. the Windows toast — can show *which* session/project the request is for.

Daemon emits this when a `PermissionRequest` hook fires for a tool the user has not pre-approved. It is **broadcast to every connected client** (phone and desktop), each of which shows a notification and/or in-app card. `summary` is a single-line human description suitable for a notification body. `input` is the raw tool input — a client can show it expanded if the user taps for detail.

Fan-out and fallback behaviour:
- If **no client is connected** when the request arrives, the daemon does not broadcast and immediately returns passthrough so Claude Code's own local terminal prompt handles it.
- If clients are connected, the daemon broadcasts and waits up to a **configurable client-wait timeout** (`--permission-wait`, default 30 s — kept well below Claude's 600 s hook timeout). The **first** client to answer wins; the daemon then emits `permission_resolved` so the other clients dismiss the prompt.
- If the wait expires with no answer, the daemon emits `permission_resolved` (`reason: "expired"`) and returns passthrough — it does **not** auto-deny — so the local terminal prompt takes over.

### permission_response (phone → daemon)

```json
{ "type": "permission_response", "id": "req_8a3f01", "decision": "allow" }
```

Decision values:
- `allow` — this one tool call only
- `deny` — this one tool call only, with reason "user denied"
- `allow_always` — allow all future calls of this tool with the same input shape (per-session)
- `deny_always` — deny all future calls (per-session)

The first response for a given `id` wins; later responses (e.g. from a slower client) are ignored. The daemon converts the winning decision to the JSON Claude Code expects from a `PermissionRequest` hook (`hookSpecificOutput.decision.behavior` = `allow` | `deny`) and writes it to the hook script's stdout via the Unix socket reply.

### permission_resolved (daemon → phone)

```json
{ "type": "permission_resolved", "id": "req_8a3f01", "reason": "answered", "decision": "allow" }
```

Broadcast daemon-wide to tell every client that a pending `permission_request` is no longer actionable, so each client dismisses its prompt/notification for that `id`.

- `reason: "answered"` — another client responded first; `decision` carries the winning value.
- `reason: "expired"` — the client-wait timed out and the request fell through to the local terminal prompt; `decision` is empty.

Note: a request answered at the **local** terminal cannot be detected by the daemon (hooks do not report the outcome of the interactive prompt), so no `permission_resolved` is emitted in that case — clients dismiss those stale prompts on their own (e.g. manually, or when the daemon later expires them).

### notification (daemon → phone)

```json
{
  "type": "notification",
  "session": "sess_8a3f",
  "kind": "task_complete",
  "message": "Refactor complete · 3 files changed",
  "ts": 1729267260,
  "session_name": "webapp"
}
```

Kinds:
- `task_complete` — fired when `Stop` hook runs (Claude finished its turn)
- `error` — Claude or the daemon hit an error worth surfacing
- `permission_timeout` — auto-denied a permission after 5 min
- `warning` — a non-fatal warning worth surfacing (e.g. a takeover could not stop the desktop session)
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
- `upload_failed` — a `file_upload` could not be written (bad path, I/O error)
- `internal` — daemon bug

### kill_session (phone → daemon)

```json
{ "type": "kill_session", "id": "sess_8a3f" }
```

Terminates the live session's PTY process. The daemon then pushes a `sessions_update` to every connected client. Unknown ids are ignored. (Distinct from `delete_session`, which only removes an on-disk transcript.)

### take_over (phone → daemon)

```json
{ "type": "take_over", "id": "sess_8a3f" }
```

Takes control of an **adopted** (desktop/VSCode) session so the app can drive its prompts. The daemon: (1) best-effort **kills the desktop `claude`** process (SIGTERM via the pid it resolved from the hook socket's peer credentials); (2) spawns a new daemon-owned PTY running `claude --resume <that conversation's session_id>`; (3) drops the adopted record. A `sessions_update` then shows the conversation as `origin: "spawned"`, so the app's terminal input becomes available. Only one `claude` may drive a conversation at a time — resuming the same conversation on the desktop would otherwise interleave/corrupt the transcript.

If the desktop process cannot be stopped (pid unknown or already gone), the daemon still resumes and sends a warning `notification` (`kind: "warning"`) so the user knows to exit the desktop session manually.

The reverse direction needs no message: when the desktop runs `claude --resume <id>` for a conversation the daemon currently owns, its `SessionStart` hook lets the daemon detect the collision and kill **its own** copy.

### sessions_update (daemon → phone)

```json
{
  "type": "sessions_update",
  "sessions": [
    { "id": "sess_8a3f", "name": "webapp", "cwd": "/home/josh/code/webapp", "status": "running", "started_at": 1751200000, "last_activity": 1751200500 }
  ]
}
```

The full live-session list, pushed whenever the set or a status changes (create, kill, or a session dying). The phone replaces its session list from this. Same `SessionInfo` shape as `welcome.sessions`.

`SessionInfo` now also carries `started_at` and `last_activity` (epoch seconds) for showing uptime / last activity. Both default to `0` when unknown. It also carries `origin` (`spawned` | `adopted`, default `spawned`) so the phone can badge adopted desktop sessions and disable their input.

### set_handoff (phone → daemon)

```json
{ "type": "set_handoff", "enabled": true }
```

Toggles desktop→mobile handoff. While **enabled**, the daemon adopts `claude` sessions it did **not** spawn (e.g. one running in desktop VSCode) the next time they fire a hook, surfaces them in the session list with `origin: "adopted"`, and forwards their permission prompts and notifications to connected phones. While **disabled** (the default), such sessions are left alone and keep their normal in-editor prompt. The daemon broadcasts a `handoff_state` to every client when the flag changes.

Adopted sessions are **permission-only**: the daemon does not own their PTY, so it cannot stream their terminal output or accept input for them.

### handoff_state (daemon → phone)

```json
{ "type": "handoff_state", "enabled": true }
```

Broadcast to all connected clients whenever the handoff toggle changes (and reflected in `welcome.handoff_enabled` on connect), so every phone keeps its switch in sync.

### check_path (phone → daemon)

```json
{ "type": "check_path", "path": "/home/josh/code/webapp" }
```

Asks the daemon whether a folder exists on the host. Daemon replies with `path_checked`.

### path_checked (daemon → phone)

```json
{ "type": "path_checked", "path": "/home/josh/code/webapp", "is_dir": true }
```

`is_dir` is true only if the path resolves to an existing directory on the daemon host.

### list_dir (phone → daemon)

```json
{ "type": "list_dir", "path": "/home/josh/code" }
```

Asks the daemon for the immediate **sub-directories** (folders only — never files) of
a folder on the host, so the phone can offer a directory picker for choosing a project
cwd. An empty or blank `path` starts at the daemon user's home directory. Daemon replies
with `dir_listing`, or `error` (`not_a_dir`) when the path is not a directory.

### dir_listing (daemon → phone)

```json
{
  "type": "dir_listing",
  "path": "/home/josh/code",
  "parent": "/home/josh",
  "entries": ["webapp", "scratch", ".config"]
}
```

`path` is the resolved absolute path that was listed. `parent` is its parent directory
(for navigating "up"), or `""` when `path` is a filesystem root. `entries` are the
immediate sub-directory names, sorted case-insensitively; hidden folders (leading `.`)
are included, and the phone filters them behind a "show hidden" toggle. An unreadable
folder yields an empty `entries` rather than an error, so navigation never dead-ends.

## Pairing (out of band)

Pairing is not part of the WS protocol — it happens once over a separate HTTP endpoint.

```
POST http://<daemon>:8770/pair
Body: { "code": "123456", "device_name": "Pixel 8" }
Returns: { "token": "dev_a1b2c3d4..." }
```

The 6-digit `code` is printed by the daemon at startup and rotates if unused for 10 minutes. The returned `token` is what the phone sends in `hello` for all subsequent connections.
