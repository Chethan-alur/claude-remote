"""Hook bridge.

Listens on a Unix socket. The `claude-remote-hook` CLI connects once per
hook invocation, sends the framed hook payload, and blocks until we reply
with the decision the phone gave us.

Wire format on the Unix socket (one request per connection):

  line 1:  the daemon-assigned session id (the value of CLAUDE_REMOTE_SESSION
           that we set when spawning claude; empty for a claude we did not
           spawn, e.g. an interactive VSCode session)
  rest:    the raw hook JSON Claude Code wrote to the hook's stdin

The reply is the JSON Claude Code expects on the hook's stdout. We target the
schema of the installed Claude Code (2.1.x), which uses the `PermissionRequest`
and `Notification` hook events:

  allow:        {"hookSpecificOutput": {"hookEventName": "PermissionRequest",
                                         "decision": {"behavior": "allow"}}}
  deny:         {"hookSpecificOutput": {"hookEventName": "PermissionRequest",
                                         "decision": {"behavior": "deny"}}}
  passthrough:  {"continue": true}   (defer to the normal interactive prompt)

Isolation: we only act on requests whose session id matches a session we
spawned. Anything else passes through, so the daemon never interferes with a
claude the user is driving themselves.
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
import socket
import struct
import uuid
from time import time
from typing import TYPE_CHECKING, Any, Callable

from .protocol import Notification, PermissionRequest, PermissionResolved, encode
from .session import Status

if TYPE_CHECKING:
    from .session import Session, SessionManager

logger = logging.getLogger(__name__)

PERMISSION_TIMEOUT_SEC = 300  # legacy ceiling; hook_bin (310s) allows a little more
DEFAULT_CLIENT_WAIT_SEC = 30  # how long to wait for a client before local fallback

# Hook events that ask us for an allow/deny decision.
PERMISSION_EVENTS = ("PermissionRequest", "PreToolUse")

PASSTHROUGH: dict[str, Any] = {"continue": True}


# --- process helpers (Linux /proc) ----------------------------------------
# Used to find and identify the external `claude` process behind a hook call so
# an Android takeover can SIGTERM it. Linux-only by design (the daemon is
# Linux/WSL, and the hook connects to its local Unix socket, so the claude
# process is co-located and its pid is meaningful to us).


def _proc_cmdline(pid: int) -> str:
    try:
        with open(f"/proc/{pid}/cmdline", "rb") as f:
            return f.read().replace(b"\0", b" ").decode("utf-8", "replace").strip()
    except OSError:
        return ""


def _proc_ppid(pid: int) -> int | None:
    """Parent pid from /proc/<pid>/stat. The comm field is parenthesised and may
    contain spaces/parens, so read the fields after the last ')'."""
    try:
        with open(f"/proc/{pid}/stat", "rb") as f:
            data = f.read()
        fields = data[data.rfind(b")") + 2:].split()
        return int(fields[1])  # fields: state, ppid, ...
    except (OSError, ValueError, IndexError):
        return None


def _is_claude_cmdline(cmd: str) -> bool:
    """True for the real claude CLI, but not our own hook/daemon processes."""
    low = cmd.lower()
    return "claude" in low and "claude-remote" not in low and "claude_remote" not in low


def pid_is_claude(pid: int | None) -> bool:
    """Best-effort check that `pid` is still a live claude process (guards a
    takeover SIGTERM against a reused pid)."""
    return pid is not None and pid > 1 and _is_claude_cmdline(_proc_cmdline(pid))


def peer_pid(sock: "socket.socket | None") -> int | None:
    """Pid of the process on the other end of a Unix socket (SO_PEERCRED)."""
    so_peercred = getattr(socket, "SO_PEERCRED", None)
    if sock is None or so_peercred is None:
        return None
    try:
        data = sock.getsockopt(socket.SOL_SOCKET, so_peercred, struct.calcsize("3i"))
        pid, _uid, _gid = struct.unpack("3i", data)
        return pid or None
    except OSError:
        return None


def resolve_claude_pid(hook_pid: int | None) -> int | None:
    """Walk up from the hook process to the claude process that invoked it."""
    if hook_pid is None:
        return None
    pid = _proc_ppid(hook_pid)  # skip the hook process itself
    for _ in range(8):
        if not pid or pid <= 1:
            return None
        if _is_claude_cmdline(_proc_cmdline(pid)):
            return pid
        pid = _proc_ppid(pid)
    return None


class HookBridge:
    def __init__(
        self,
        socket_path: str,
        sessions: "SessionManager",
        client_wait: float = DEFAULT_CLIENT_WAIT_SEC,
    ) -> None:
        self.socket_path = socket_path
        self.sessions = sessions
        # How long a permission request waits for a remote client to answer
        # before falling through to Claude Code's local terminal prompt.
        self.client_wait = client_wait
        # Desktop->mobile handoff. When True, hooks from a claude we did NOT
        # spawn (empty CLAUDE_REMOTE_SESSION) are adopted and forwarded to
        # phones; when False they pass through to the normal desktop prompt.
        self.handoff_enabled = False
        # Set by the server to fan a control frame (PermissionRequest /
        # Notification dataclass) out to every connected client. Adopted
        # sessions have no attached subscriber, so per-session broadcast would
        # never reach the phone — these frames must go daemon-wide.
        self.broadcast_all: Callable[[object], None] | None = None
        # Set by the server: True when at least one client is connected. When no
        # client is listening we fall straight through to the local prompt rather
        # than block until the wait expires.
        self.has_clients: Callable[[], bool] | None = None

    async def serve(self) -> None:
        """Listen forever for hook-bin connections."""
        # Clean up stale socket from a previous run.
        try:
            os.unlink(self.socket_path)
        except FileNotFoundError:
            pass
        server = await asyncio.start_unix_server(self._handle, path=self.socket_path)
        os.chmod(self.socket_path, 0o600)
        logger.info("hook bridge listening on %s", self.socket_path)
        async with server:
            await server.serve_forever()

    async def _handle(
        self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter
    ) -> None:
        """Per-connection handler. Reads one framed request, writes one reply."""
        response: dict[str, Any] = PASSTHROUGH
        # Resolve the external claude's pid from the socket peer credentials, so
        # an Android takeover can later SIGTERM it. Keeps the hook CLI dumb.
        # Never let this break hook handling (fail-open principle).
        claude_pid: int | None = None
        try:
            claude_pid = resolve_claude_pid(peer_pid(writer.get_extra_info("socket")))
        except Exception:
            claude_pid = None
        try:
            session_line = await reader.readline()
            session_id = session_line.decode("utf-8", "replace").strip()
            body = await reader.read()  # hook_bin half-closes, so this hits EOF
            payload = json.loads(body) if body.strip() else {}
            event = payload.get("hook_event_name") or payload.get("event") or ""
            logger.debug("hook %s for session %r", event, session_id)
            response = await self._route(session_id, event, payload, claude_pid)
        except Exception as exc:
            logger.exception("hook handler failed: %s", exc)
            response = PASSTHROUGH  # fail-open: never block claude on our bugs
        finally:
            try:
                writer.write((json.dumps(response) + "\n").encode())
                await writer.drain()
            except Exception:
                pass
            writer.close()
            try:
                await writer.wait_closed()
            except Exception:
                pass

    async def _route(
        self,
        session_id: str,
        event: str,
        payload: dict[str, Any],
        claude_pid: int | None = None,
    ) -> dict[str, Any]:
        session = self.sessions.get(session_id) if session_id else None

        # A claude we did not spawn (empty env session-id line) carries its own
        # session_id in the hook payload.
        if session is None and not session_id:
            cc_id = str(payload.get("session_id") or "")
            if cc_id:
                # Takeover collision: the desktop is *starting* a resume of a
                # conversation the daemon currently owns. Kill our own copy so the
                # transcript is not written by two processes at once. Runs
                # regardless of handoff — it is a correctness guard, not a handoff
                # feature. Gated to SessionStart so a late hook from a dying
                # desktop (or our own resume) cannot kill the wrong session.
                if event == "SessionStart":
                    owned = self.sessions.spawned_by_cc_id(cc_id)
                    if owned is not None:
                        logger.info(
                            "desktop resumed cc=%s; killing daemon-owned session %s",
                            cc_id, owned.id,
                        )
                        self.sessions.remove(owned.id)

                # Desktop->mobile handoff: adopt the external session so its
                # permissions reach the phone.
                if self.handoff_enabled:
                    if event == "SessionEnd":
                        adopted = self.sessions.adopted_session(cc_id)
                        if adopted is not None:
                            self.sessions.remove(adopted.id)
                        return {}
                    session = self.sessions.adopt(
                        cc_id, str(payload.get("cwd") or ""), claude_pid
                    )

        # Record the conversation id (and refresh the external pid) on the
        # matched session so a takeover can resume/kill the right conversation.
        if session is not None:
            cc_id = str(payload.get("session_id") or "")
            if cc_id and not session.cc_session_id:
                session.cc_session_id = cc_id
            if session.origin == "adopted":
                session.last_active = time()
                if claude_pid is not None:
                    session.claude_pid = claude_pid

        if event in PERMISSION_EVENTS:
            if session is None:
                return self._passthrough(event)
            return await self._handle_permission(session, event, payload)

        if event == "Notification" and session is not None:
            self._emit_notification(session, payload)
            return {}

        if event == "Stop" and session is not None:
            session.status = Status.IDLE
            self._broadcast(
                session,
                Notification(
                    session=session.id,
                    kind="task_complete",
                    message="Claude finished its turn",
                    ts=int(time()),
                    session_name=session.name,
                ),
            )
            return {}

        # SessionStart (and anything else) needs no decision; adoption above
        # has already surfaced the session to phones.
        return self._passthrough(event)

    async def _handle_permission(
        self, session: "Session", event: str, payload: dict[str, Any]
    ) -> dict[str, Any]:
        tool = payload.get("tool_name", "tool")
        tool_input = payload.get("tool_input", {}) or {}
        sig = self._input_signature(tool, tool_input)

        # Honour a remembered always-decision without bothering the user.
        remembered = session.perm_prefs.get(sig)
        if remembered in ("allow", "deny"):
            return self._decision_response(event, remembered)

        # No client is listening — don't broadcast into the void and block. Fall
        # straight through so Claude Code's own local terminal prompt handles it.
        if self.has_clients is not None and not self.has_clients():
            return self._passthrough(event)

        req_id = self._new_request_id()
        loop = asyncio.get_running_loop()
        fut: asyncio.Future = loop.create_future()
        session.pending_perms[req_id] = fut
        session.status = Status.WAITING
        self._broadcast(
            session,
            PermissionRequest(
                id=req_id,
                session=session.id,
                tool=tool,
                input=tool_input,
                summary=self._summarize(tool, tool_input),
                received_at=int(time()),
                session_name=session.name,
            ),
        )

        try:
            decision = await asyncio.wait_for(fut, timeout=self.client_wait)
        except asyncio.TimeoutError:
            # No client answered in time. Tell clients to dismiss the now-stale
            # prompt, then fall through to the local terminal prompt rather than
            # auto-denying — local stays authoritative.
            self._broadcast(session, PermissionResolved(id=req_id, reason="expired"))
            return self._passthrough(event)
        except asyncio.CancelledError:
            # Session died while waiting — let claude fall back to its own prompt.
            return self._passthrough(event)
        finally:
            session.pending_perms.pop(req_id, None)
            if session.status == Status.WAITING:
                session.status = Status.RUNNING

        if decision in ("allow_always", "deny_always"):
            session.perm_prefs[sig] = "allow" if decision == "allow_always" else "deny"

        behavior = "allow" if decision in ("allow", "allow_always") else "deny"
        return self._decision_response(event, behavior)

    def resolve(self, request_id: str, decision: str) -> None:
        """Complete the Future a permission request is waiting on.

        Called by the WS handler when a `permission_response` arrives. If no
        such request is pending (it may have already timed out), log and ignore.
        """
        for session in self.sessions.list():
            fut = session.pending_perms.get(request_id)
            if fut is not None and not fut.done():
                fut.set_result(decision)
                # First responder wins; tell the other clients to dismiss their
                # prompt for this request so it doesn't linger after the answer.
                self._broadcast(
                    session,
                    PermissionResolved(
                        id=request_id, reason="answered", decision=decision
                    ),
                )
                return
        logger.info("no pending permission %s (already resolved or timed out)", request_id)

    # --- helpers ---

    def _broadcast(self, session: "Session", msg: object) -> None:
        """Fan a control frame (permission / notification) out to phones.

        Routes daemon-wide when a broadcaster is wired (so adopted sessions,
        which have no attached subscriber, still reach the phone; the Android
        client keys notifications by id, so this is duplicate-free for spawned
        sessions too). Falls back to per-session fan-out otherwise (tests)."""
        if self.broadcast_all is not None:
            self.broadcast_all(msg)
        else:
            session.broadcast(encode(msg))

    def _emit_notification(self, session: "Session", payload: dict[str, Any]) -> None:
        # permission_prompt is handled by the PermissionRequest event; skip it
        # here so the phone does not get a duplicate, button-less notification.
        if payload.get("notification_type") == "permission_prompt":
            return
        message = payload.get("message") or "Claude Code needs your attention"
        self._broadcast(
            session,
            Notification(
                session=session.id, kind="info", message=message, ts=int(time()),
                session_name=session.name,
            ),
        )

    def _decision_response(self, event: str, behavior: str) -> dict[str, Any]:
        """Map allow/deny to the hook-output schema for the given event."""
        if event == "PermissionRequest":
            return {
                "hookSpecificOutput": {
                    "hookEventName": "PermissionRequest",
                    "decision": {"behavior": behavior},
                }
            }
        # PreToolUse fallback (older configs): permissionDecision is allow|deny.
        return {
            "hookSpecificOutput": {
                "hookEventName": "PreToolUse",
                "permissionDecision": behavior,
            }
        }

    def _passthrough(self, event: str) -> dict[str, Any]:
        return dict(PASSTHROUGH)

    @staticmethod
    def _input_signature(tool: str, tool_input: dict[str, Any]) -> str:
        """A stable key so 'always' decisions match identical future calls."""
        try:
            body = json.dumps(tool_input, sort_keys=True, default=str)
        except (TypeError, ValueError):
            body = repr(tool_input)
        return f"{tool}:{body}"

    @staticmethod
    def _summarize(tool: str, tool_input: dict[str, Any]) -> str:
        """One-line description suitable for a notification body."""
        desc = str(tool_input.get("description") or "").replace("\n", " ").strip()
        if tool == "Bash":
            detail = str(tool_input.get("command", ""))
        elif tool_input.get("file_path"):
            detail = str(tool_input["file_path"])
        elif tool_input.get("url"):
            detail = str(tool_input["url"])
        else:
            detail = json.dumps(tool_input, default=str)[:200]
        body = f"{desc} — {detail}" if desc else detail
        return f"{tool}: {body}"[:300]

    @staticmethod
    def _new_request_id() -> str:
        return "req_" + uuid.uuid4().hex[:8]
