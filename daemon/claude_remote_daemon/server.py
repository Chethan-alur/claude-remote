"""WebSocket server.

Per-client coroutine, dispatches incoming JSON messages by type, fans
out outgoing frames from sessions to attached clients, and serves the
out-of-band `/pair` HTTP endpoint on the same port.
"""
from __future__ import annotations

import asyncio
import base64
import http
import json
import logging
import os
import signal
import socket
from pathlib import Path
from time import time
from typing import TYPE_CHECKING
from urllib.parse import parse_qs, urlparse

import websockets
from websockets.asyncio.server import ServerConnection

from . import __version__
from .history import delete_project_session, list_project_sessions, read_transcript
from .protocol import (
    CheckPath,
    DeleteSession,
    DirListing,
    Error,
    FileUpload,
    FileUploaded,
    HandoffState,
    Hello,
    GetHistory,
    History,
    HistoryMessage,
    Input,
    KillSession,
    ListDir,
    Resize,
    ListSessions,
    Notification,
    Output,
    PathChecked,
    PermissionResponse,
    Ping,
    Pong,
    ProjectSessionInfo,
    ProjectSessions,
    SessionAttach,
    SessionCreate,
    SessionCreated,
    SessionInfo,
    SessionsUpdate,
    SetHandoff,
    TakeOver,
    Welcome,
    decode,
    encode,
)
from .hooks import pid_is_claude

# Per-subscriber queue depth before a slow client starts dropping frames.
SUBSCRIBER_QUEUE_MAX = 512

if TYPE_CHECKING:
    from .auth import AuthStore
    from .hooks import HookBridge
    from .session import SessionManager

logger = logging.getLogger(__name__)


def _save_upload(cwd: Path, filename: str, data: bytes) -> Path:
    """Write an uploaded file under `<cwd>/uploads/`, confined to the project.

    The filename is reduced to its basename (path components stripped) so an
    upload can never escape the uploads dir. Colliding names get a `-1`, `-2`,
    … suffix rather than clobbering an existing file. Returns the saved path.
    """
    safe_name = Path(filename).name or "upload.bin"
    uploads = (cwd / "uploads").resolve()
    uploads.mkdir(parents=True, exist_ok=True)
    # Defence in depth: the basename above already prevents traversal, but
    # re-check the resolved parent is the uploads dir.
    dest = (uploads / safe_name).resolve()
    if dest.parent != uploads:
        raise ValueError(f"refusing to write outside uploads dir: {dest}")
    if dest.exists():
        stem, suffix = dest.stem, dest.suffix
        i = 1
        while (uploads / f"{stem}-{i}{suffix}").exists():
            i += 1
        dest = uploads / f"{stem}-{i}{suffix}"
    dest.write_bytes(data)
    return dest


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
        # All currently-connected clients, for pushing SessionsUpdate to everyone
        # when a session is created / killed / dies.
        self._clients: set[ServerConnection] = set()
        self.sessions.on_change = self._on_sessions_changed
        # Let the hook bridge fan permission/notification frames out to every
        # connected client (adopted sessions have no attached subscriber).
        self.hooks.broadcast_all = self._schedule_broadcast_all
        # Let the hook bridge check whether any client is connected, so a
        # permission request with no listener falls straight through to the
        # local terminal prompt instead of blocking until the wait expires.
        self.hooks.has_clients = self._has_clients

    def _has_clients(self) -> bool:
        return len(self._clients) > 0

    def _sessions_info(self) -> list[SessionInfo]:
        return [
            SessionInfo(
                id=s.id,
                name=s.name,
                cwd=str(s.cwd),
                status=s.status.value,
                started_at=int(s.created_at),
                last_activity=int(s.last_active),
                origin=s.origin,
            )
            for s in self.sessions.list()
        ]

    def _on_sessions_changed(self) -> None:
        """SessionManager hook — schedule a SessionsUpdate to all clients."""
        try:
            loop = asyncio.get_running_loop()
        except RuntimeError:
            return
        loop.create_task(self._broadcast_all(SessionsUpdate(sessions=self._sessions_info())))

    def _schedule_broadcast_all(self, msg) -> None:
        """Sync entry point for the hook bridge to fan a frame to all clients."""
        try:
            loop = asyncio.get_running_loop()
        except RuntimeError:
            return
        loop.create_task(self._broadcast_all(msg))

    async def _broadcast_all(self, msg) -> None:
        frame = encode(msg)
        for ws in list(self._clients):
            try:
                await ws.send(frame)
            except Exception:
                # Send failed — the socket is dead. Prune it now so the live
                # set stays accurate and we don't keep forwarding to it (the
                # client's own handler also discards, but may not have run yet).
                self._clients.discard(ws)

    async def serve(self) -> None:
        async with websockets.serve(
            self._handle,
            self.bind,
            self.port,
            process_request=self._http_request,
            # Active heartbeat: the library sends a WS PING every 20s and closes
            # the connection if no PONG arrives within 20s, so dead clients are
            # detected and evicted (via _handle's finally) without us polling.
            ping_interval=20,
            ping_timeout=20,
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
            await self._send(
                ws,
                Welcome(
                    daemon_version=__version__,
                    hostname=socket.gethostname(),
                    sessions=self._sessions_info(),
                    handoff_enabled=self.hooks.handoff_enabled,
                ),
            )
            self._clients.add(ws)

            # 3. Main loop
            attached_tasks: list[asyncio.Task] = []
            # Session ids this connection is already subscribed to, so a second
            # attach (e.g. the app's explicit session_attach after the daemon
            # auto-attaches it on session_create) does not create a duplicate
            # fan-out queue — which would deliver every Output frame twice and
            # corrupt the client's terminal render.
            attached_ids: set[str] = set()
            try:
                async for raw in ws:
                    try:
                        msg = decode(raw)
                    except Exception as e:
                        await self._send(ws, Error(code="bad_message", message=str(e)))
                        continue
                    await self._dispatch(ws, msg, attached_tasks, attached_ids)
            finally:
                self._clients.discard(ws)
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
        attached_ids: set[str],
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
            await self._attach(
                ws, s, replay_bytes=0,
                attached_tasks=attached_tasks, attached_ids=attached_ids,
            )

        elif isinstance(msg, ListSessions):
            await self._send_project_sessions(ws, msg.cwd)

        elif isinstance(msg, GetHistory):
            await self._send_history(ws, msg)

        elif isinstance(msg, DeleteSession):
            try:
                delete_project_session(msg.cwd, msg.id)
            except Exception as e:
                await self._send(ws, Error(code="bad_message", message=str(e)))
                return
            # Reply with the refreshed list so the picker updates.
            await self._send_project_sessions(ws, msg.cwd)

        elif isinstance(msg, SessionAttach):
            session = self.sessions.get(msg.id)
            if session is None:
                await self._send(
                    ws, Error(code="session_not_found", message=msg.id)
                )
                return
            await self._attach(
                ws, session, replay_bytes=msg.replay_bytes,
                attached_tasks=attached_tasks, attached_ids=attached_ids,
            )

        elif isinstance(msg, Input):
            session = self.sessions.get(msg.session)
            if session is None:
                await self._send(
                    ws, Error(code="session_not_found", message=msg.session)
                )
                return
            session.write(msg.data.encode())

        elif isinstance(msg, Resize):
            session = self.sessions.get(msg.session)
            if session is None:
                await self._send(
                    ws, Error(code="session_not_found", message=msg.session)
                )
                return
            session.setwinsize(msg.rows, msg.cols)

        elif isinstance(msg, FileUpload):
            session = self.sessions.get(msg.session)
            if session is None:
                await self._send(
                    ws, Error(code="session_not_found", message=msg.session)
                )
                return
            buf = session.pending_uploads.setdefault(msg.upload_id, bytearray())
            try:
                buf.extend(base64.b64decode(msg.data))
            except Exception as e:
                session.pending_uploads.pop(msg.upload_id, None)
                await self._send(ws, Error(code="upload_failed", message=str(e)))
                return
            if msg.seq >= msg.total - 1:  # last chunk
                session.pending_uploads.pop(msg.upload_id, None)
                try:
                    dest = _save_upload(session.cwd, msg.filename, bytes(buf))
                except Exception as e:
                    await self._send(ws, Error(code="upload_failed", message=str(e)))
                    return
                await self._send(
                    ws,
                    FileUploaded(
                        session=session.id, upload_id=msg.upload_id, path=str(dest)
                    ),
                )

        elif isinstance(msg, KillSession):
            # Terminate the live PTY. SessionManager.on_change then pushes a
            # SessionsUpdate to every client so lists stay in sync.
            self.sessions.remove(msg.id)

        elif isinstance(msg, TakeOver):
            await self._take_over(ws, msg.id, attached_tasks, attached_ids)

        elif isinstance(msg, CheckPath):
            p = Path(msg.path).expanduser()
            await self._send(ws, PathChecked(path=msg.path, is_dir=p.is_dir()))

        elif isinstance(msg, ListDir):
            await self._send_dir_listing(ws, msg.path)

        elif isinstance(msg, PermissionResponse):
            self.hooks.resolve(msg.id, msg.decision)

        elif isinstance(msg, SetHandoff):
            # Toggle desktop->mobile handoff and let every client's switch sync.
            self.hooks.handoff_enabled = msg.enabled
            logger.info("handoff %s", "enabled" if msg.enabled else "disabled")
            await self._broadcast_all(HandoffState(enabled=msg.enabled))

        else:
            await self._send(
                ws,
                Error(
                    code="bad_message",
                    message=f"unexpected message type: {type(msg).__name__}",
                ),
            )

    async def _take_over(
        self,
        ws: ServerConnection,
        session_id: str,
        attached_tasks: list[asyncio.Task],
        attached_ids: set[str],
    ) -> None:
        """Take control of an adopted (desktop) session from the app.

        Best-effort SIGTERM the desktop claude, then spawn a daemon-owned
        `claude --resume <conversation id>` so the app can drive it. If the
        desktop copy could not be stopped, we still resume and warn — so the
        user knows to exit it (two processes on one transcript interleave it).
        """
        session = self.sessions.get(session_id)
        if session is None or session.origin != "adopted":
            await self._send(
                ws, Error(code="not_adopted", message=session_id)
            )
            return

        cc_id = session.cc_session_id
        cwd = str(session.cwd)
        name = session.name
        pid = session.claude_pid

        if not cc_id:
            await self._send(
                ws,
                Error(code="takeover_failed", message="unknown conversation id"),
            )
            return

        # Best-effort: stop the desktop claude so only one process drives the
        # conversation. Re-check the pid still maps to claude to avoid killing a
        # reused pid.
        killed = False
        if pid_is_claude(pid):
            try:
                os.kill(pid, signal.SIGTERM)
                killed = True
                logger.info("takeover: SIGTERM desktop claude pid %s", pid)
            except OSError as e:
                logger.warning("takeover: SIGTERM pid %s failed: %s", pid, e)

        # Drop the adopted record (frees the cc-id mapping) before resuming.
        self.sessions.remove(session.id)

        try:
            s = await self.sessions.create(name, cwd, resume_id=cc_id)
        except Exception as e:
            await self._send(ws, Error(code="takeover_failed", message=str(e)))
            return

        await self._send(ws, SessionCreated(id=s.id, name=s.name, cwd=str(s.cwd)))
        await self._attach(
            ws, s, replay_bytes=0,
            attached_tasks=attached_tasks, attached_ids=attached_ids,
        )

        if not killed:
            await self._send(
                ws,
                Notification(
                    session=s.id,
                    kind="warning",
                    message=(
                        "Resumed on the app, but the desktop session may still be "
                        "running — exit it there to avoid conflicting edits."
                    ),
                    ts=int(time()),
                ),
            )

    async def _attach(
        self,
        ws: ServerConnection,
        session,
        replay_bytes: int,
        attached_tasks: list[asyncio.Task],
        attached_ids: set[str],
    ) -> None:
        """Subscribe this client to a session's frame stream.

        Creates a per-client queue, optionally replays buffered output, then
        spawns a task that forwards every queued frame to the socket. The task
        removes its queue on disconnect so the session stops fanning out to a
        dead client.

        Idempotent per connection: if this socket is already subscribed to the
        session, do nothing. The app sends an explicit `session_attach` after
        opening a terminal, but a freshly created (or taken-over) session is
        already auto-attached on `session_create`; without this guard the
        second attach would add a second fan-out queue, so every Output frame
        would arrive twice and corrupt the client's terminal render.
        """
        if session.id in attached_ids:
            return
        attached_ids.add(session.id)

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
                attached_ids.discard(session.id)

        attached_tasks.append(asyncio.create_task(pump()))

    async def _send_project_sessions(self, ws: ServerConnection, cwd: str) -> None:
        """Send the current past-session list for a project folder."""
        found = list_project_sessions(cwd)
        await self._send(
            ws,
            ProjectSessions(
                cwd=cwd,
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

    async def _send_history(self, ws: ServerConnection, msg: GetHistory) -> None:
        """Send a session's conversation transcript for the phone's scroll-back
        view. Prefer the session's known Claude conversation id; fall back to the
        newest transcript in the cwd (a fresh session Claude is writing live)."""
        session = self.sessions.get(msg.session)
        cc_id = session.cc_session_id if session is not None else ""
        cwd = str(session.cwd) if session is not None and session.cwd else msg.cwd
        try:
            found = read_transcript(cwd, cc_id, limit=msg.limit)
        except Exception as e:
            await self._send(ws, Error(code="bad_message", message=str(e)))
            return
        await self._send(
            ws,
            History(
                session=msg.session,
                messages=[
                    HistoryMessage(role=m.role, text=m.text, ts=m.ts) for m in found
                ],
            ),
        )

    async def _send_dir_listing(self, ws: ServerConnection, path: str) -> None:
        """List a folder's immediate sub-directories for the phone's browser.

        Folders only — never files (the app is a remote control, not a file
        manager). An empty/blank `path` starts at the daemon user's home. The
        phone already has full control of this host (it can spawn `claude` in
        any cwd and upload files), so directory listing introduces no new trust
        boundary; we therefore do not sandbox the traversal. Unreadable folders
        yield an empty `entries` rather than an error so navigation never dead-ends.
        """
        raw = path.strip()
        base = Path(raw).expanduser() if raw else Path.home()
        try:
            base = base.resolve()
        except Exception:
            base = Path.home()
        if not base.is_dir():
            await self._send(
                ws, Error(code="not_a_dir", message=f"not a directory: {base}")
            )
            return
        try:
            entries = sorted(
                (p.name for p in base.iterdir() if p.is_dir()),
                key=str.lower,
            )
        except (PermissionError, OSError):
            entries = []
        # At a filesystem root, `.parent` is the path itself — report "" so the
        # phone knows there is no "up" to offer.
        parent = "" if base.parent == base else str(base.parent)
        await self._send(
            ws, DirListing(path=str(base), parent=parent, entries=entries)
        )

    async def _send(self, ws: ServerConnection, msg) -> None:
        await ws.send(encode(msg))
