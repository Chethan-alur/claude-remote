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
from claude_remote_daemon.protocol import (
    Notification,
    PermissionRequest,
    PermissionResolved,
    decode,
)
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
    assert frame.session_name == "t"  # carries the session name so clients can identify it
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
    # Answering broadcasts a dismissal for the other clients; drain it.
    assert isinstance(decode(await asyncio.wait_for(q.get(), 2)), PermissionResolved)

    # Second identical call: auto-allowed, no new prompt broadcast.
    w2 = FakeWriter()
    await asyncio.wait_for(
        bridge._handle(_reader(_frame("sess_test", PERM_PAYLOAD)), w2), 2
    )
    assert w2.response()["hookSpecificOutput"]["decision"]["behavior"] == "allow"
    assert q.empty()


async def test_no_clients_passes_through_immediately():
    """With the daemon up but no client connected, a permission request must not
    block for the client-wait — it falls straight through to the local prompt."""
    bridge, session, q = _bridge_with_session()
    bridge.has_clients = lambda: False  # nobody listening
    writer = FakeWriter()
    await asyncio.wait_for(
        bridge._handle(_reader(_frame("sess_test", PERM_PAYLOAD)), writer), 2
    )
    assert writer.response() == {"continue": True}
    assert q.empty()  # nothing was broadcast into the void
    assert session.status != Status.WAITING


async def test_resolve_broadcasts_permission_resolved():
    """First responder wins; the daemon then tells the other clients to dismiss."""
    bridge, session, q = _bridge_with_session()
    bridge.has_clients = lambda: True
    writer = FakeWriter()
    task = asyncio.create_task(
        bridge._handle(_reader(_frame("sess_test", PERM_PAYLOAD)), writer)
    )
    req = decode(await asyncio.wait_for(q.get(), 2))
    assert isinstance(req, PermissionRequest)

    bridge.resolve(req.id, "allow")
    bridge.resolve(req.id, "deny")  # second response loses — must be a no-op
    await asyncio.wait_for(task, 2)

    assert writer.response()["hookSpecificOutput"]["decision"]["behavior"] == "allow"
    resolved = decode(await asyncio.wait_for(q.get(), 2))
    assert isinstance(resolved, PermissionResolved)
    assert resolved.id == req.id
    assert resolved.reason == "answered"
    assert resolved.decision == "allow"
    assert q.empty()  # the losing response broadcast nothing


async def test_wait_expiry_dismisses_and_passes_through():
    """No client answers in time: dismiss the stale prompt and fall through to the
    local prompt (NOT auto-deny)."""
    bridge, session, q = _bridge_with_session()
    bridge.has_clients = lambda: True
    bridge.client_wait = 0.05  # expire almost immediately
    writer = FakeWriter()
    task = asyncio.create_task(
        bridge._handle(_reader(_frame("sess_test", PERM_PAYLOAD)), writer)
    )
    req = decode(await asyncio.wait_for(q.get(), 2))
    assert isinstance(req, PermissionRequest)

    await asyncio.wait_for(task, 2)  # let the client-wait lapse with no answer
    assert writer.response() == {"continue": True}  # passthrough, not deny
    resolved = decode(await asyncio.wait_for(q.get(), 2))
    assert isinstance(resolved, PermissionResolved)
    assert resolved.id == req.id
    assert resolved.reason == "expired"
    assert session.status == Status.RUNNING


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


# --- session takeover / resume ---

def test_adopt_stores_cc_id_and_pid():
    """The adopted record carries the conversation id and the external claude pid
    (the inputs an Android takeover needs to resume and SIGTERM)."""
    sessions = SessionManager()
    s = sessions.adopt("cc-1", "/tmp/proj", pid=4242)
    assert s.origin == "adopted"
    assert s.cc_session_id == "cc-1"
    assert s.claude_pid == 4242
    # A repeat hook reuses the record and refreshes the pid.
    s2 = sessions.adopt("cc-1", "/tmp/proj", pid=4243)
    assert s2 is s and s.claude_pid == 4243
    # spawned_by_cc_id only matches daemon-owned (spawned) sessions.
    assert sessions.spawned_by_cc_id("cc-1") is None


async def test_spawned_session_records_cc_id_from_payload():
    """The daemon learns a spawned session's Claude Code conversation id from the
    first hook payload, so a later desktop resume can be detected as a collision."""
    bridge, session, _q = _bridge_with_session()
    assert session.cc_session_id == ""
    payload = {"hook_event_name": "SessionStart", "session_id": "cc-rec"}
    await asyncio.wait_for(
        bridge._handle(_reader(_frame("sess_test", payload)), FakeWriter()), 2
    )
    assert session.cc_session_id == "cc-rec"


async def test_desktop_resume_kills_daemon_owned_session():
    """Direction B: an external `claude --resume` (empty env line, SessionStart)
    of a conversation the daemon owns kills the daemon's copy — even with handoff
    off — to avoid two processes writing one transcript."""
    bridge, session, _q = _bridge_with_session()
    session.cc_session_id = "cc-shared"
    payload = {"hook_event_name": "SessionStart", "session_id": "cc-shared"}
    await asyncio.wait_for(
        bridge._handle(_reader(_frame("", payload)), FakeWriter()), 2
    )
    assert bridge.sessions.get(session.id) is None  # daemon-owned copy killed


async def test_daemon_owned_resume_does_not_self_collide():
    """The daemon's own `claude --resume` carries the env session-id line, so it
    is matched as its own spawned session and must never kill itself."""
    bridge, session, _q = _bridge_with_session()
    session.cc_session_id = "cc-self"
    payload = {"hook_event_name": "SessionStart", "session_id": "cc-self"}
    await asyncio.wait_for(
        bridge._handle(_reader(_frame("sess_test", payload)), FakeWriter()), 2
    )
    assert bridge.sessions.get(session.id) is session  # survived


async def test_non_sessionstart_external_does_not_kill():
    """A late hook (not SessionStart) from a dying desktop must not trip the
    collision kill against a daemon-owned session with the same conversation id."""
    bridge, session, _q = _bridge_with_session()
    session.cc_session_id = "cc-late"
    payload = {"hook_event_name": "SessionEnd", "session_id": "cc-late", "reason": "other"}
    await asyncio.wait_for(
        bridge._handle(_reader(_frame("", payload)), FakeWriter()), 2
    )
    assert bridge.sessions.get(session.id) is session  # survived


# --- desktop->mobile handoff (adopting external sessions) ---

EXTERNAL_PERM = {
    "hook_event_name": "PermissionRequest",
    "session_id": "cc-abc",   # Claude Code's own id (no CLAUDE_REMOTE_SESSION)
    "cwd": "/tmp/proj",
    "tool_name": "Bash",
    "tool_input": {"command": "rm -rf build"},
}


def _bridge_handoff():
    """A bridge with handoff on and a daemon-wide broadcast captured to a queue."""
    sessions = SessionManager()
    bridge = HookBridge("/unused.sock", sessions)
    bridge.handoff_enabled = True
    q: asyncio.Queue = asyncio.Queue(maxsize=16)
    bridge.broadcast_all = lambda msg: q.put_nowait(msg)
    return bridge, sessions, q


async def test_handoff_adopts_and_forwards_permission():
    bridge, sessions, q = _bridge_handoff()
    writer = FakeWriter()
    # Empty env session-id line -> external session; payload carries cc id + cwd.
    task = asyncio.create_task(
        bridge._handle(_reader(_frame("", EXTERNAL_PERM)), writer)
    )

    # The prompt is fanned out daemon-wide (no phone is attached to it).
    frame = await asyncio.wait_for(q.get(), 2)
    assert isinstance(frame, PermissionRequest)
    assert "rm -rf build" in frame.summary

    # The session was adopted and is visible in the registry.
    assert len(sessions.list()) == 1
    s = sessions.list()[0]
    assert s.origin == "adopted"
    assert s.name == "proj"

    bridge.resolve(frame.id, "allow")
    await asyncio.wait_for(task, 2)
    assert writer.response()["hookSpecificOutput"]["decision"]["behavior"] == "allow"


async def test_handoff_off_leaves_external_session_alone():
    sessions = SessionManager()
    bridge = HookBridge("/unused.sock", sessions)  # handoff defaults off
    writer = FakeWriter()
    await asyncio.wait_for(
        bridge._handle(_reader(_frame("", EXTERNAL_PERM)), writer), 2
    )
    assert writer.response() == {"continue": True}  # normal desktop prompt
    assert sessions.list() == []  # nothing adopted


async def test_adopt_reuses_session_for_same_cc_id():
    bridge, sessions, _q = _bridge_handoff()
    start = {"hook_event_name": "SessionStart", "session_id": "cc-xyz", "cwd": "/tmp/proj"}
    await asyncio.wait_for(bridge._handle(_reader(_frame("", start)), FakeWriter()), 2)
    await asyncio.wait_for(bridge._handle(_reader(_frame("", start)), FakeWriter()), 2)
    assert len(sessions.list()) == 1  # second event reuses the same record


async def test_session_end_removes_adopted():
    bridge, sessions, _q = _bridge_handoff()
    start = {"hook_event_name": "SessionStart", "session_id": "cc-1", "cwd": "/tmp/proj"}
    await asyncio.wait_for(bridge._handle(_reader(_frame("", start)), FakeWriter()), 2)
    assert len(sessions.list()) == 1

    end = {"hook_event_name": "SessionEnd", "session_id": "cc-1", "cwd": "/tmp/proj", "reason": "other"}
    writer = FakeWriter()
    await asyncio.wait_for(bridge._handle(_reader(_frame("", end)), writer), 2)
    assert sessions.list() == []
    assert writer.response() == {}  # SessionEnd is observability-only


async def test_reaper_prunes_stale_adopted_only():
    from claude_remote_daemon.session import ADOPTED_TTL_SEC

    sessions = SessionManager()
    adopted = sessions.adopt("cc-stale", "/tmp/proj")
    spawned = Session(id="sess_live", name="live", cwd=Path("/tmp"))  # origin "spawned"
    sessions._sessions[spawned.id] = spawned

    # Age the adopted session past its TTL; the spawned one is untouched.
    adopted.last_active = adopted.last_active - ADOPTED_TTL_SEC - 1
    spawned.last_active = spawned.last_active - ADOPTED_TTL_SEC - 1

    assert sessions.prune_stale_adopted() == 1
    ids = [s.id for s in sessions.list()]
    assert "sess_live" in ids and adopted.id not in ids
