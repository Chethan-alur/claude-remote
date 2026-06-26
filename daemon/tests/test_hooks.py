"""Hook-bridge logic tests.

These drive HookBridge._handle through an in-memory StreamReader + a fake
writer, so they exercise the framing, decision mapping, timeout/always logic
and fan-out WITHOUT opening a real Unix socket (which the sandbox blocks).
"""
from __future__ import annotations

import asyncio
import json
from pathlib import Path

from claude_remote_daemon.hooks import HookBridge
from claude_remote_daemon.protocol import Notification, PermissionRequest, decode
from claude_remote_daemon.session import Session, SessionManager, Status


class FakeWriter:
    def __init__(self) -> None:
        self.buf = bytearray()
        self.closed = False

    def write(self, b: bytes) -> None:
        self.buf.extend(b)

    async def drain(self) -> None:
        pass

    def close(self) -> None:
        self.closed = True

    async def wait_closed(self) -> None:
        pass

    def response(self) -> dict:
        return json.loads(self.buf.decode())


def _reader(framed: bytes) -> asyncio.StreamReader:
    r = asyncio.StreamReader()
    r.feed_data(framed)
    r.feed_eof()
    return r


def _frame(session_id: str, payload: dict) -> bytes:
    return f"{session_id}\n".encode() + json.dumps(payload).encode()


def _bridge_with_session(session_id: str = "sess_test"):
    sessions = SessionManager()
    session = Session(id=session_id, name="t", cwd=Path("/tmp"))
    sessions._sessions[session_id] = session
    q: asyncio.Queue[str] = asyncio.Queue(maxsize=16)
    session.subscribers.append(q)
    return HookBridge("/unused.sock", sessions), session, q


PERM_PAYLOAD = {
    "hook_event_name": "PermissionRequest",
    "tool_name": "Bash",
    "tool_input": {"command": "ls -la", "description": "list files"},
}


async def test_permission_allow_round_trip():
    bridge, session, q = _bridge_with_session()
    writer = FakeWriter()
    task = asyncio.create_task(
        bridge._handle(_reader(_frame("sess_test", PERM_PAYLOAD)), writer)
    )

    # The bridge should broadcast a permission_request while it awaits us.
    frame = decode(await asyncio.wait_for(q.get(), 2))
    assert isinstance(frame, PermissionRequest)
    assert frame.tool == "Bash"
    assert "ls -la" in frame.summary
    assert session.status == Status.WAITING

    bridge.resolve(frame.id, "allow")
    await asyncio.wait_for(task, 2)

    resp = writer.response()
    assert resp["hookSpecificOutput"]["hookEventName"] == "PermissionRequest"
    assert resp["hookSpecificOutput"]["decision"]["behavior"] == "allow"
    assert session.status == Status.RUNNING


async def test_permission_deny_round_trip():
    bridge, session, q = _bridge_with_session()
    writer = FakeWriter()
    task = asyncio.create_task(
        bridge._handle(_reader(_frame("sess_test", PERM_PAYLOAD)), writer)
    )
    frame = decode(await asyncio.wait_for(q.get(), 2))
    bridge.resolve(frame.id, "deny")
    await asyncio.wait_for(task, 2)
    assert writer.response()["hookSpecificOutput"]["decision"]["behavior"] == "deny"


async def test_allow_always_is_remembered():
    bridge, session, q = _bridge_with_session()

    # First call: user picks allow_always.
    w1 = FakeWriter()
    t1 = asyncio.create_task(
        bridge._handle(_reader(_frame("sess_test", PERM_PAYLOAD)), w1)
    )
    frame = decode(await asyncio.wait_for(q.get(), 2))
    bridge.resolve(frame.id, "allow_always")
    await asyncio.wait_for(t1, 2)
    assert w1.response()["hookSpecificOutput"]["decision"]["behavior"] == "allow"
    assert session.perm_prefs  # a preference was stored

    # Second identical call: auto-allowed, no new prompt broadcast.
    w2 = FakeWriter()
    await asyncio.wait_for(
        bridge._handle(_reader(_frame("sess_test", PERM_PAYLOAD)), w2), 2
    )
    assert w2.response()["hookSpecificOutput"]["decision"]["behavior"] == "allow"
    assert q.empty()


async def test_unknown_session_passes_through():
    bridge, _session, _q = _bridge_with_session()
    writer = FakeWriter()
    # Empty session-id line -> not a daemon session.
    await asyncio.wait_for(
        bridge._handle(_reader(_frame("", PERM_PAYLOAD)), writer), 2
    )
    assert writer.response() == {"continue": True}


async def test_notification_is_broadcast():
    bridge, session, q = _bridge_with_session()
    writer = FakeWriter()
    payload = {
        "hook_event_name": "Notification",
        "notification_type": "idle_prompt",
        "message": "Claude is waiting for input",
    }
    await asyncio.wait_for(
        bridge._handle(_reader(_frame("sess_test", payload)), writer), 2
    )
    frame = decode(await asyncio.wait_for(q.get(), 2))
    assert isinstance(frame, Notification)
    assert frame.message == "Claude is waiting for input"
    assert writer.response() == {}


async def test_permission_prompt_notification_is_skipped():
    bridge, session, q = _bridge_with_session()
    writer = FakeWriter()
    payload = {
        "hook_event_name": "Notification",
        "notification_type": "permission_prompt",
        "message": "would duplicate the PermissionRequest",
    }
    await asyncio.wait_for(
        bridge._handle(_reader(_frame("sess_test", payload)), writer), 2
    )
    assert q.empty()  # suppressed in favour of the PermissionRequest event
