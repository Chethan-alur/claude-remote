"""Session lifecycle.

A Session owns one Claude Code PTY and the in-memory state for one
project directory. SessionManager is the daemon's session registry.

We use `asyncio.add_reader()` on the PTY's master fd. This is Linux/WSL
only (which is fine for the POC). For Windows native we'd switch to
`ptyprocess.PtyProcess` reads in a thread-pool executor.

TODO(claude-code): implement.
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
from typing import Optional

import ptyprocess

logger = logging.getLogger(__name__)


class Status(str, Enum):
    RUNNING = "running"
    WAITING = "waiting"   # permission pending
    IDLE = "idle"         # claude finished, awaiting input
    DEAD = "dead"


# Last ~1MB of PTY output, replayed to clients on reconnect.
OUTPUT_BUFFER_BYTES = 1_000_000


@dataclass
class Session:
    id: str
    name: str
    cwd: Path
    status: Status = Status.RUNNING
    created_at: float = field(default_factory=time)
    last_active: float = field(default_factory=time)

    # Set by SessionManager.create()
    proc: Optional[ptyprocess.PtyProcess] = None

    # Bounded ring buffer of output bytes (for reconnect replay).
    output_buffer: deque[bytes] = field(default_factory=deque)
    output_buffer_size: int = 0

    # asyncio Queues fanned-out to subscribed WebSocket clients.
    subscribers: list[asyncio.Queue[bytes]] = field(default_factory=list)

    # Pending permission requests indexed by request id.
    # HookBridge populates these; the WS handler resolves them.
    pending_perms: dict[str, asyncio.Future] = field(default_factory=dict)

    def write(self, data: bytes) -> None:
        """Send bytes to the running claude process."""
        if self.proc is None:
            return
        # TODO(claude-code): handle short writes / EAGAIN
        self.proc.write(data)
        self.last_active = time()

    def append_output(self, chunk: bytes) -> None:
        """Append to the ring buffer and fan out to subscribers."""
        self.output_buffer.append(chunk)
        self.output_buffer_size += len(chunk)
        while self.output_buffer_size > OUTPUT_BUFFER_BYTES and self.output_buffer:
            dropped = self.output_buffer.popleft()
            self.output_buffer_size -= len(dropped)
        for q in list(self.subscribers):
            try:
                q.put_nowait(chunk)
            except asyncio.QueueFull:
                # Slow consumer — drop. Better than blocking the PTY pump.
                logger.warning("subscriber queue full on session %s", self.id)

    def close(self) -> None:
        """Kill the claude process and free the PTY."""
        if self.proc is not None and self.proc.isalive():
            self.proc.terminate(force=True)
        self.status = Status.DEAD


class SessionManager:
    def __init__(self) -> None:
        self._sessions: dict[str, Session] = {}
        self._lock = asyncio.Lock()

    async def create(self, name: str, cwd: str) -> Session:
        """Spawn a new claude process in cwd, wrap it in a PTY,
        start the read pump."""
        path = Path(cwd).expanduser().resolve()
        if not path.is_dir():
            raise ValueError(f"cwd does not exist or is not a directory: {path}")

        session_id = "sess_" + uuid.uuid4().hex[:8]

        # TODO(claude-code):
        #   1. Spawn claude with the session_id in env so the hook can
        #      identify which session it belongs to:
        #
        #        env = {**os.environ, "CLAUDE_REMOTE_SESSION": session_id}
        #        proc = ptyprocess.PtyProcess.spawn(
        #            ["claude"], cwd=str(path), env=env, dimensions=(40, 120)
        #        )
        #
        #   2. Build the Session, register it.
        #
        #   3. Start the PTY read pump as an asyncio task. Two options
        #      (pick one and stay consistent):
        #
        #      a) loop.add_reader(proc.fd, callback) — pure asyncio,
        #         Linux-only. Read in the callback, hand bytes to
        #         session.append_output. Cleanest.
        #
        #      b) loop.run_in_executor for blocking proc.read() in a
        #         thread, push bytes onto an asyncio.Queue, drain that
        #         queue in an async task. Portable, slightly noisier.
        #
        #   4. Start a watchdog task that detects process exit and
        #      flips status to DEAD.
        raise NotImplementedError

    def get(self, session_id: str) -> Session | None:
        return self._sessions.get(session_id)

    def list(self) -> list[Session]:
        return list(self._sessions.values())

    def remove(self, session_id: str) -> None:
        s = self._sessions.pop(session_id, None)
        if s is not None:
            s.close()

    async def shutdown(self) -> None:
        for s in list(self._sessions.values()):
            s.close()
        self._sessions.clear()


# Silence unused-import warnings until create() is implemented.
_ = os
