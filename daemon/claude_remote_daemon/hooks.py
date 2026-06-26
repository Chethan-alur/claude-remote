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
import uuid
from time import time
from typing import TYPE_CHECKING, Any

from .protocol import Notification, PermissionRequest, encode
from .session import Status

if TYPE_CHECKING:
    from .session import Session, SessionManager

logger = logging.getLogger(__name__)

PERMISSION_TIMEOUT_SEC = 300  # 5 minutes; hook_bin allows a little more

# Hook events that ask us for an allow/deny decision.
PERMISSION_EVENTS = ("PermissionRequest", "PreToolUse")

PASSTHROUGH: dict[str, Any] = {"continue": True}


class HookBridge:
    def __init__(self, socket_path: str, sessions: "SessionManager") -> None:
        self.socket_path = socket_path
        self.sessions = sessions

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
        try:
            session_line = await reader.readline()
            session_id = session_line.decode("utf-8", "replace").strip()
            body = await reader.read()  # hook_bin half-closes, so this hits EOF
            payload = json.loads(body) if body.strip() else {}
            event = payload.get("hook_event_name") or payload.get("event") or ""
            logger.debug("hook %s for session %r", event, session_id)
            response = await self._route(session_id, event, payload)
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
        self, session_id: str, event: str, payload: dict[str, Any]
    ) -> dict[str, Any]:
        session = self.sessions.get(session_id) if session_id else None

        if event in PERMISSION_EVENTS:
            if session is None:
                return self._passthrough(event)
            return await self._handle_permission(session, event, payload)

        if event == "Notification" and session is not None:
            self._emit_notification(session, payload)
            return {}

        if event == "Stop" and session is not None:
            session.status = Status.IDLE
            session.broadcast(
                encode(
                    Notification(
                        session=session.id,
                        kind="task_complete",
                        message="Claude finished its turn",
                        ts=int(time()),
                    )
                )
            )
            return {}

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

        req_id = self._new_request_id()
        loop = asyncio.get_running_loop()
        fut: asyncio.Future = loop.create_future()
        session.pending_perms[req_id] = fut
        session.status = Status.WAITING
        session.broadcast(
            encode(
                PermissionRequest(
                    id=req_id,
                    session=session.id,
                    tool=tool,
                    input=tool_input,
                    summary=self._summarize(tool, tool_input),
                    received_at=int(time()),
                )
            )
        )

        try:
            decision = await asyncio.wait_for(fut, timeout=PERMISSION_TIMEOUT_SEC)
        except asyncio.TimeoutError:
            session.broadcast(
                encode(
                    Notification(
                        session=session.id,
                        kind="permission_timeout",
                        message=f"Auto-denied {tool} after 5 min with no response",
                        ts=int(time()),
                    )
                )
            )
            return self._decision_response(event, "deny")
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
                return
        logger.info("no pending permission %s (already resolved or timed out)", request_id)

    # --- helpers ---

    def _emit_notification(self, session: "Session", payload: dict[str, Any]) -> None:
        # permission_prompt is handled by the PermissionRequest event; skip it
        # here so the phone does not get a duplicate, button-less notification.
        if payload.get("notification_type") == "permission_prompt":
            return
        message = payload.get("message") or "Claude Code needs your attention"
        session.broadcast(
            encode(
                Notification(
                    session=session.id, kind="info", message=message, ts=int(time())
                )
            )
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
