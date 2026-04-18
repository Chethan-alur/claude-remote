"""Hook bridge.

Listens on a Unix socket. The `claude-remote-hook` CLI connects per
hook invocation, sends the hook payload as JSON, blocks until we reply
with the user's decision (which we got from the phone).

Wire format on the Unix socket (one request per connection, NDJSON):

  request:  {"event": "PreToolUse", "session_id": "sess_xxx",
             "tool_name": "Edit", "tool_input": {...}}
  response: {"decision": "approve"} | {"decision": "block", "reason": "..."}

(Roughly the format Claude Code's hook protocol uses on stdin/stdout —
hook-bin shuttles it through unchanged.)

TODO(claude-code): implement.
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
import uuid
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from .session import SessionManager

logger = logging.getLogger(__name__)

PERMISSION_TIMEOUT_SEC = 300  # 5 minutes


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
        """Per-connection handler. Reads one request, writes one response.

        TODO(claude-code):
          1. Read one line of JSON from `reader` (the hook payload)
          2. Parse out session_id (from CLAUDE_REMOTE_SESSION env propagated
             by the hook), tool name, tool input
          3. Find the Session via self.sessions.get(...)
          4. Build a PermissionRequest; assign a request id; install a
             Future in session.pending_perms[req_id]
          5. Send the request to all subscribers of that session
          6. await asyncio.wait_for(future, timeout=PERMISSION_TIMEOUT_SEC)
          7. Map the user's decision string to the hook-protocol response:
               allow / allow_always       -> {"decision": "approve"}
               deny  / deny_always        -> {"decision": "block",
                                              "reason": "user denied"}
          8. For *_always: also store a per-session preference so we
             auto-respond next time without bothering the user
          9. writer.write(response + b"\n"); await writer.drain(); close
         10. On timeout: respond with block, emit a Notification frame
        """
        try:
            payload_line = await reader.readline()
            if not payload_line:
                return
            payload = json.loads(payload_line)
            logger.debug("hook payload: %s", payload)

            # Placeholder — auto-approve everything until implementation lands.
            response = {"decision": "approve"}
            writer.write((json.dumps(response) + "\n").encode())
            await writer.drain()
        except Exception as exc:
            logger.exception("hook handler failed: %s", exc)
            # Fail-open: don't block claude on hook bugs.
            writer.write(b'{"decision":"approve"}\n')
            try:
                await writer.drain()
            except Exception:
                pass
        finally:
            writer.close()
            try:
                await writer.wait_closed()
            except Exception:
                pass

    def resolve(self, request_id: str, decision: str) -> None:
        """Called by the WS handler when a permission_response arrives.

        TODO(claude-code): find the Future in the relevant session's
        pending_perms and set its result. If no such id is pending,
        log and ignore (the request may have already timed out).
        """
        _ = request_id, decision

    @staticmethod
    def _new_request_id() -> str:
        return "req_" + uuid.uuid4().hex[:8]
