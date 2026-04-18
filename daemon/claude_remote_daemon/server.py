"""WebSocket server.

Per-client coroutine, dispatches incoming JSON messages by type, fans
out outgoing frames from sessions to attached clients.

TODO(claude-code): implement.
"""
from __future__ import annotations

import asyncio
import logging
import socket
from typing import TYPE_CHECKING

import websockets
from websockets.server import WebSocketServerProtocol

from . import __version__
from .protocol import (
    Error,
    Hello,
    Input,
    PermissionResponse,
    Ping,
    Pong,
    SessionAttach,
    SessionCreate,
    SessionInfo,
    Welcome,
    decode,
    encode,
)

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
    ) -> None:
        self.bind = bind
        self.port = port
        self.sessions = sessions
        self.hooks = hooks
        self.auth = auth

    async def serve(self) -> None:
        async with websockets.serve(self._handle, self.bind, self.port):
            logger.info("websocket server on ws://%s:%d", self.bind, self.port)
            await asyncio.Future()  # serve forever

    async def _handle(self, ws: WebSocketServerProtocol) -> None:
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
            # TODO(claude-code): real token check via self.auth.verify(msg.token)
            # For now accept anything during early dev.

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
        ws: WebSocketServerProtocol,
        msg,
        attached_tasks: list[asyncio.Task],
    ) -> None:
        """Route one incoming message to the right handler.

        TODO(claude-code): fill in each branch. The skeleton below shows
        the shape; the actual work is in SessionManager / HookBridge.
        """
        if isinstance(msg, Ping):
            await self._send(ws, Pong(ts=msg.ts))

        elif isinstance(msg, SessionCreate):
            try:
                s = await self.sessions.create(msg.name, msg.cwd)
            except Exception as e:
                await self._send(
                    ws, Error(code="session_creation_failed", message=str(e))
                )
                return
            # TODO: send SessionCreated, then auto-attach this client
            _ = s

        elif isinstance(msg, SessionAttach):
            session = self.sessions.get(msg.id)
            if session is None:
                await self._send(
                    ws, Error(code="session_not_found", message=msg.id)
                )
                return
            # TODO(claude-code):
            #   q = asyncio.Queue(maxsize=256)
            #   session.subscribers.append(q)
            #   if msg.replay_bytes: emit buffered output up to that many bytes
            #   spawn task: while True: data = await q.get(); send Output frame
            #   register cleanup so subscriber is removed on disconnect

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

    async def _send(self, ws: WebSocketServerProtocol, msg) -> None:
        await ws.send(encode(msg))
