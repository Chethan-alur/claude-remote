"""WebSocket server.

Per-client coroutine, dispatches incoming JSON messages by type, fans
out outgoing frames from sessions to attached clients, and serves the
out-of-band `/pair` HTTP endpoint on the same port.
"""
from __future__ import annotations

import asyncio
import http
import json
import logging
import socket
from typing import TYPE_CHECKING
from urllib.parse import parse_qs, urlparse

import websockets
from websockets.asyncio.server import ServerConnection

from . import __version__
from .history import list_project_sessions
from .protocol import (
    Error,
    Hello,
    Input,
    ListSessions,
    Output,
    PermissionResponse,
    Ping,
    Pong,
    ProjectSessionInfo,
    ProjectSessions,
    SessionAttach,
    SessionCreate,
    SessionCreated,
    SessionInfo,
    Welcome,
    decode,
    encode,
)

# Per-subscriber queue depth before a slow client starts dropping frames.
SUBSCRIBER_QUEUE_MAX = 512

if TYPE_CHECKING:
    from .auth import AuthStore
    from .hooks import HookBridge
    from .session import SessionManager

logger = logging.getLogger(__name__)


class WsServer:
    def __init__(
        self,
        bind: str,
        port: int,
        sessions: "SessionManager",
        hooks: "HookBridge",
        auth: "AuthStore",
        require_auth: bool = False,
    ) -> None:
        self.bind = bind
        self.port = port
        self.sessions = sessions
        self.hooks = hooks
        self.auth = auth
        # When False (POC default), unknown tokens are accepted with a warning
        # so development works before the pairing UI is wired. Flip to enforce.
        self.require_auth = require_auth

    async def serve(self) -> None:
        async with websockets.serve(
            self._handle, self.bind, self.port, process_request=self._http_request
        ):
            logger.info("websocket server on ws://%s:%d", self.bind, self.port)
            await asyncio.Future()  # serve forever

    async def _http_request(self, connection, request):
        """Handle out-of-band HTTP on the WebSocket port (websockets asyncio API).

        Only `/pair` is served; anything else returns None so the WebSocket
        handshake proceeds normally. Pairing carries the 6-digit code in the
        query string — `POST /pair?code=NNNNNN&device=<name>` — because the
        HTTP hook does not expose a request body. Returns `{"token": "..."}`
        on success or `{"error": "..."}` with HTTP 400.
        """
        path = request.path
        if not path.startswith("/pair"):
            return None  # not ours — let the WS upgrade happen

        query = parse_qs(urlparse(path).query)
        code = (query.get("code") or [""])[0]
        device = (query.get("device") or ["unknown device"])[0]
        try:
            token = self.auth.pair(code, device)
        except ValueError as exc:
            return connection.respond(
                http.HTTPStatus.BAD_REQUEST, json.dumps({"error": str(exc)}) + "\n"
            )
        logger.info("paired new device: %s", device)
        return connection.respond(
            http.HTTPStatus.OK, json.dumps({"token": token}) + "\n"
        )

    async def _handle(self, ws: ServerConnection) -> None:
        """Per-connection coroutine.

        Sketch:
          1. Read first frame, must be Hello with a valid token. If not,
             send Error and close with code 4001.
          2. Send Welcome with current session list.
          3. Loop: read frame, dispatch by type.
          4. Forward output from any attached session to this client
             (separate task per session subscription).
        """
        peer = ws.remote_address
        logger.info("client connected: %s", peer)

        try:
            # 1. Hello / auth
            try:
                first = await asyncio.wait_for(ws.recv(), timeout=10)
            except asyncio.TimeoutError:
                await self._send(ws, Error(code="bad_message", message="hello timeout"))
                return
            try:
                msg = decode(first)
            except Exception as e:
                await self._send(ws, Error(code="bad_message", message=str(e)))
                return
            if not isinstance(msg, Hello):
                await self._send(
                    ws, Error(code="bad_token", message="first frame must be hello")
                )
                return

            device = self.auth.verify(msg.token)
            if device is None:
                if self.require_auth:
                    await self._send(
                        ws, Error(code="bad_token", message="unknown device token")
                    )
                    await ws.close(code=4001, reason="bad token")
                    return
                logger.warning(
                    "accepting unverified token %r (auth not required)", msg.token
                )

            # 2. Welcome
            sessions_info = [
                SessionInfo(id=s.id, name=s.name, cwd=str(s.cwd), status=s.status.value)
                for s in self.sessions.list()
            ]
            await self._send(
                ws,
                Welcome(
                    daemon_version=__version__,
                    hostname=socket.gethostname(),
                    sessions=sessions_info,
                ),
            )

            # 3. Main loop
            attached_tasks: list[asyncio.Task] = []
            try:
                async for raw in ws:
                    try:
                        msg = decode(raw)
                    except Exception as e:
                        await self._send(ws, Error(code="bad_message", message=str(e)))
                        continue
                    await self._dispatch(ws, msg, attached_tasks)
            finally:
                for t in attached_tasks:
                    t.cancel()
        except websockets.ConnectionClosed:
            pass
        finally:
            logger.info("client disconnected: %s", peer)

    async def _dispatch(
        self,
        ws: ServerConnection,
        msg,
        attached_tasks: list[asyncio.Task],
    ) -> None:
        """Route one incoming message to the right handler.

        The actual work lives in SessionManager / HookBridge; this just
        translates message types into those calls.
        """
        if isinstance(msg, Ping):
            await self._send(ws, Pong(ts=msg.ts))

        elif isinstance(msg, SessionCreate):
            try:
                s = await self.sessions.create(msg.name, msg.cwd, resume_id=msg.resume_id)
            except Exception as e:
                await self._send(
                    ws, Error(code="session_creation_failed", message=str(e))
                )
                return
            await self._send(ws, SessionCreated(id=s.id, name=s.name, cwd=str(s.cwd)))
            await self._attach(ws, s, replay_bytes=0, attached_tasks=attached_tasks)

        elif isinstance(msg, ListSessions):
            found = list_project_sessions(msg.cwd)
            await self._send(
                ws,
                ProjectSessions(
                    cwd=msg.cwd,
                    sessions=[
                        ProjectSessionInfo(
                            id=p.id,
                            title=p.title,
                            modified=p.modified,
                            messages=p.messages,
                        )
                        for p in found
                    ],
                ),
            )

        elif isinstance(msg, SessionAttach):
            session = self.sessions.get(msg.id)
            if session is None:
                await self._send(
                    ws, Error(code="session_not_found", message=msg.id)
                )
                return
            await self._attach(
                ws, session, replay_bytes=msg.replay_bytes, attached_tasks=attached_tasks
            )

        elif isinstance(msg, Input):
            session = self.sessions.get(msg.session)
            if session is None:
                await self._send(
                    ws, Error(code="session_not_found", message=msg.session)
                )
                return
            session.write(msg.data.encode())

        elif isinstance(msg, PermissionResponse):
            self.hooks.resolve(msg.id, msg.decision)

        else:
            await self._send(
                ws,
                Error(
                    code="bad_message",
                    message=f"unexpected message type: {type(msg).__name__}",
                ),
            )

    async def _attach(
        self,
        ws: ServerConnection,
        session,
        replay_bytes: int,
        attached_tasks: list[asyncio.Task],
    ) -> None:
        """Subscribe this client to a session's frame stream.

        Creates a per-client queue, optionally replays buffered output, then
        spawns a task that forwards every queued frame to the socket. The task
        removes its queue on disconnect so the session stops fanning out to a
        dead client.
        """
        q: asyncio.Queue[str] = asyncio.Queue(maxsize=SUBSCRIBER_QUEUE_MAX)
        session.subscribers.append(q)

        if replay_bytes:
            text = session.replay(replay_bytes)
            if text:
                await self._send(
                    ws, Output(session=session.id, data=text, stream="stdout")
                )

        async def pump() -> None:
            try:
                while True:
                    frame = await q.get()
                    await ws.send(frame)
            except (asyncio.CancelledError, websockets.ConnectionClosed):
                pass
            finally:
                if q in session.subscribers:
                    session.subscribers.remove(q)

        attached_tasks.append(asyncio.create_task(pump()))

    async def _send(self, ws: ServerConnection, msg) -> None:
        await ws.send(encode(msg))
