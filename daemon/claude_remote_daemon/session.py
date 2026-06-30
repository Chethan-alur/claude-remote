"""Session lifecycle.

A Session owns one Claude Code PTY and the in-memory state for one
project directory. SessionManager is the daemon's session registry.

We use `asyncio.add_reader()` on the PTY's master fd. This is Linux/WSL
only (which is fine for the POC). For Windows native we'd switch to
`ptyprocess.PtyProcess` reads in a thread-pool executor.

Fan-out model: each attached WebSocket client owns one `asyncio.Queue[str]`
holding *encoded protocol frames* (JSON strings) ready to send. PTY output
is wrapped into `Output` frames by `append_output`; the hook bridge pushes
`PermissionRequest` / `Notification` frames through the same `broadcast`
path, so a client's forward task is a simple "get a string, send it" loop.
"""
from __future__ import annotations

import asyncio
import logging
import os
import uuid
from collections import deque
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from time import time
from typing import Callable, Optional

import ptyprocess

from .protocol import Notification, Output, encode

logger = logging.getLogger(__name__)


class Status(str, Enum):
    RUNNING = "running"
    WAITING = "waiting"   # permission pending
    IDLE = "idle"         # claude finished, awaiting input
    DEAD = "dead"


# Last ~1MB of PTY output, replayed to clients on reconnect.
OUTPUT_BUFFER_BYTES = 1_000_000

# Read at most this many bytes per readable event.
READ_CHUNK = 65536

# Per-subscriber queue depth before we start dropping (slow consumer).
SUBSCRIBER_QUEUE_MAX = 512

# Adopted (external) sessions have no process to watchdog, so we prune them
# after this long with no hook activity, in case a SessionEnd never arrives.
ADOPTED_TTL_SEC = 1800  # 30 minutes
ADOPTED_REAP_INTERVAL_SEC = 60


@dataclass
class Session:
    id: str
    name: str
    cwd: Path
    status: Status = Status.RUNNING
    created_at: float = field(default_factory=time)
    last_active: float = field(default_factory=time)

    # "spawned" — the daemon launched this in a PTY (full control).
    # "adopted" — an external session (e.g. VSCode) we only forward permissions
    # for; it has no PTY, so output/input are unavailable.
    origin: str = "spawned"

    # Claude Code's own session_id for the conversation this session drives.
    # For spawned sessions it is learned from the first hook payload (or set
    # eagerly to the resume id); for adopted sessions it is the adoption key.
    # Used to detect a takeover collision and to resume the same conversation.
    cc_session_id: str = ""

    # Best-effort OS pid of the external claude process (adopted sessions only),
    # resolved from the hook socket's peer credentials. Lets an Android takeover
    # SIGTERM the desktop copy. None when unknown / for spawned sessions.
    claude_pid: Optional[int] = None

    # Set by SessionManager.create()
    proc: Optional[ptyprocess.PtyProcess] = None

    # Bounded ring buffer of raw output bytes (for reconnect replay).
    output_buffer: deque[bytes] = field(default_factory=deque)
    output_buffer_size: int = 0

    # asyncio Queues fanned-out to subscribed WebSocket clients. Each holds
    # encoded protocol frames (JSON strings).
    subscribers: list[asyncio.Queue[str]] = field(default_factory=list)

    # Pending permission requests indexed by request id.
    # HookBridge populates these; the WS handler resolves them.
    pending_perms: dict[str, asyncio.Future] = field(default_factory=dict)

    # Remembered allow/deny-always decisions, keyed by an input signature.
    # Values are "allow" | "deny". Consulted before bothering the user again.
    perm_prefs: dict[str, str] = field(default_factory=dict)

    # In-progress file uploads, keyed by upload_id. Chunks are appended in
    # arrival order; flushed to disk and removed once the last chunk lands.
    pending_uploads: dict[str, bytearray] = field(default_factory=dict)

    # Background watchdog task (set by SessionManager).
    watchdog: Optional[asyncio.Task] = None

    def write(self, data: bytes) -> None:
        """Send bytes to the running claude process."""
        if self.proc is None or not self.proc.isalive():
            return
        try:
            self.proc.write(data)
        except (OSError, ptyprocess.PtyProcessError) as exc:
            logger.warning("write to session %s failed: %s", self.id, exc)
            return
        self.last_active = time()

    def setwinsize(self, rows: int, cols: int) -> None:
        """Resize the PTY so claude's TUI redraws to the client's dimensions.

        The phone reports how many character cells fit its screen; matching the
        PTY width means claude wraps at the same column the phone displays, so
        lines fit without horizontal scrolling. Sends SIGWINCH to claude.
        """
        if self.proc is None or not self.proc.isalive():
            return
        # Guard against absurd values from a mis-measuring client.
        rows = max(1, min(rows, 300))
        cols = max(2, min(cols, 500))
        try:
            self.proc.setwinsize(rows, cols)
        except (OSError, ptyprocess.PtyProcessError) as exc:
            logger.warning("resize of session %s failed: %s", self.id, exc)

    def broadcast(self, frame: str) -> None:
        """Fan a ready-to-send protocol frame out to every subscriber."""
        for q in list(self.subscribers):
            try:
                q.put_nowait(frame)
            except asyncio.QueueFull:
                # Slow consumer — drop. Better than blocking the pump.
                logger.warning("subscriber queue full on session %s", self.id)

    def append_output(self, chunk: bytes) -> None:
        """Append to the replay ring buffer and fan out as an Output frame."""
        self.output_buffer.append(chunk)
        self.output_buffer_size += len(chunk)
        while self.output_buffer_size > OUTPUT_BUFFER_BYTES and self.output_buffer:
            dropped = self.output_buffer.popleft()
            self.output_buffer_size -= len(dropped)
        self.last_active = time()
        self.broadcast(
            encode(
                Output(
                    session=self.id,
                    data=chunk.decode("utf-8", errors="replace"),
                    stream="stdout",
                )
            )
        )

    def replay(self, max_bytes: int) -> str | None:
        """Return up to the last `max_bytes` of buffered output as text."""
        if max_bytes <= 0 or not self.output_buffer:
            return None
        collected: list[bytes] = []
        size = 0
        for chunk in reversed(self.output_buffer):
            collected.append(chunk)
            size += len(chunk)
            if size >= max_bytes:
                break
        data = b"".join(reversed(collected))[-max_bytes:]
        return data.decode("utf-8", errors="replace")

    def close(self) -> None:
        """Kill the claude process and free the PTY."""
        if self.watchdog is not None:
            self.watchdog.cancel()
        if self.proc is not None and self.proc.isalive():
            try:
                self.proc.terminate(force=True)
            except Exception:
                pass
        self.status = Status.DEAD


class SessionManager:
    def __init__(
        self,
        hook_socket: str | None = None,
        claude_cmd: list[str] | None = None,
    ) -> None:
        self._sessions: dict[str, Session] = {}
        # Claude Code's own session_id -> our daemon session id, for sessions we
        # did not spawn but adopted from a hook event (e.g. a VSCode session).
        self._adopted: dict[str, str] = {}
        self._hook_socket = hook_socket
        # The command spawned for each session. Overridable for testing
        # without the real `claude` binary.
        self._claude_cmd = claude_cmd or ["claude"]
        # Set by the server: called whenever the session set or a status changes
        # (create / finalize / remove) so it can push a SessionsUpdate.
        self.on_change: Callable[[], None] | None = None

    def _fire_change(self) -> None:
        if self.on_change is not None:
            try:
                self.on_change()
            except Exception:  # never let a notify failure break lifecycle
                logger.exception("on_change callback failed")

    async def create(self, name: str, cwd: str, resume_id: str = "") -> Session:
        """Spawn a new claude process in cwd, wrap it in a PTY, start the pump.

        When `resume_id` is given, resume that past Claude session via
        `claude --resume <id>` instead of starting a fresh conversation.
        """
        path = Path(cwd).expanduser().resolve()
        if not path.is_dir():
            raise ValueError(f"cwd does not exist or is not a directory: {path}")

        session_id = "sess_" + uuid.uuid4().hex[:8]

        env = {**os.environ, "CLAUDE_REMOTE_SESSION": session_id}
        if self._hook_socket:
            env["CLAUDE_REMOTE_SOCKET"] = self._hook_socket

        argv = list(self._claude_cmd)
        if resume_id:
            argv += ["--resume", resume_id]

        try:
            proc = ptyprocess.PtyProcess.spawn(
                argv,
                cwd=str(path),
                env=env,
                dimensions=(40, 120),
            )
        except (OSError, FileNotFoundError) as exc:
            raise ValueError(f"failed to spawn {self._claude_cmd!r}: {exc}") from exc

        # When resuming, the conversation id is known up front; otherwise it is
        # learned from the first hook payload (HookBridge sets cc_session_id).
        session = Session(
            id=session_id, name=name, cwd=path, proc=proc, cc_session_id=resume_id
        )
        self._sessions[session_id] = session

        loop = asyncio.get_running_loop()
        fd = proc.fd
        os.set_blocking(fd, False)

        def _on_readable() -> None:
            try:
                data = os.read(fd, READ_CHUNK)
            except BlockingIOError:
                return
            except OSError:
                # PTY master raises EIO when the child exits.
                data = b""
            if not data:
                self._finalize(session, loop)
                return
            session.append_output(data)

        loop.add_reader(fd, _on_readable)
        session.watchdog = loop.create_task(self._watch(session, loop))
        logger.info("spawned session %s (%s) in %s", session_id, name, path)
        self._fire_change()
        return session

    def adopt(self, cc_session_id: str, cwd: str, pid: int | None = None) -> Session:
        """Register a PTY-less session for a claude we did not spawn.

        Used when desktop->mobile handoff is enabled and a hook fires for an
        external (e.g. VSCode) session. Keyed on Claude Code's own session_id
        (from the hook payload) so repeated hooks reuse the same record. The
        session carries no process, so output/input are unavailable — it exists
        only to surface permission prompts on the phone. `pid` is the best-effort
        OS pid of the external claude (for an Android takeover to SIGTERM it).
        """
        existing_id = self._adopted.get(cc_session_id)
        if existing_id is not None:
            existing = self._sessions.get(existing_id)
            if existing is not None:
                if pid is not None:
                    existing.claude_pid = pid
                return existing
            # Mapping went stale (session removed) — fall through and re-adopt.

        session_id = "sess_" + uuid.uuid4().hex[:8]
        path = Path(cwd) if cwd else Path.home()
        name = path.name or "desktop"
        session = Session(
            id=session_id,
            name=name,
            cwd=path,
            origin="adopted",
            proc=None,
            cc_session_id=cc_session_id,
            claude_pid=pid,
        )
        self._sessions[session_id] = session
        self._adopted[cc_session_id] = session_id
        logger.info("adopted external session %s (cc=%s) in %s", session_id, cc_session_id, path)
        self._fire_change()
        return session

    def spawned_by_cc_id(self, cc_session_id: str) -> Session | None:
        """A daemon-owned (spawned) session currently driving the given Claude
        Code conversation, if any. Used to detect a desktop takeover collision:
        if the desktop resumes a conversation the daemon owns, the daemon kills
        its own copy so the transcript is not written by two processes at once."""
        if not cc_session_id:
            return None
        for s in self._sessions.values():
            if s.origin == "spawned" and s.cc_session_id == cc_session_id:
                return s
        return None

    def prune_stale_adopted(self) -> int:
        """Remove adopted sessions idle past ADOPTED_TTL_SEC. Returns the count."""
        now = time()
        stale = [
            s.id
            for s in list(self._sessions.values())
            if s.origin == "adopted" and now - s.last_active > ADOPTED_TTL_SEC
        ]
        for sid in stale:
            logger.info("reaping stale adopted session %s", sid)
            self.remove(sid)
        return len(stale)

    async def reap_adopted(self) -> None:
        """Background task: periodically prune stale adopted sessions.

        A safety net for the case where a SessionEnd hook never arrives (e.g.
        the desktop editor was killed). Spawned sessions are untouched — they
        have a watchdog. Runs until cancelled.
        """
        try:
            while True:
                await asyncio.sleep(ADOPTED_REAP_INTERVAL_SEC)
                self.prune_stale_adopted()
        except asyncio.CancelledError:
            return

    async def _watch(self, session: Session, loop: asyncio.AbstractEventLoop) -> None:
        """Poll for process exit and finalize cleanly."""
        proc = session.proc
        assert proc is not None
        try:
            while proc.isalive():
                await asyncio.sleep(1.0)
        except asyncio.CancelledError:
            return
        self._finalize(session, loop)

    def _finalize(self, session: Session, loop: asyncio.AbstractEventLoop) -> None:
        """Mark a session dead, stop reading its fd, notify subscribers. Idempotent."""
        if session.status == Status.DEAD:
            return
        session.status = Status.DEAD
        if session.proc is not None:
            try:
                loop.remove_reader(session.proc.fd)
            except (OSError, ValueError):
                pass
        if session.watchdog is not None:
            session.watchdog.cancel()
        # Fail any permission requests still awaiting a decision.
        for fut in list(session.pending_perms.values()):
            if not fut.done():
                fut.cancel()
        session.pending_perms.clear()
        session.broadcast(
            encode(
                Notification(
                    session=session.id,
                    kind="info",
                    message="session ended",
                    ts=int(time()),
                )
            )
        )
        logger.info("session %s ended", session.id)
        self._fire_change()

    def get(self, session_id: str) -> Session | None:
        return self._sessions.get(session_id)

    def adopted_session(self, cc_session_id: str) -> Session | None:
        """The adopted session for a Claude Code session_id, if one exists."""
        sid = self._adopted.get(cc_session_id)
        return self._sessions.get(sid) if sid else None

    def list(self) -> list[Session]:
        return list(self._sessions.values())

    def remove(self, session_id: str) -> None:
        s = self._sessions.pop(session_id, None)
        if s is not None:
            # Drop any adopted-session mapping pointing at this id.
            for cc_id, sid in list(self._adopted.items()):
                if sid == session_id:
                    del self._adopted[cc_id]
            s.close()
            self._fire_change()

    async def shutdown(self) -> None:
        for s in list(self._sessions.values()):
            s.close()
        self._sessions.clear()
