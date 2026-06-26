"""Encode/decode round-trips for every wire-protocol message type.

The cheapest possible regression net: if a dataclass field or the `type`
discriminator drifts away from `protocol/messages.md`, these break.
"""
from __future__ import annotations

import pytest

from claude_remote_daemon.protocol import (
    Error,
    Hello,
    Input,
    ListSessions,
    Notification,
    Output,
    PermissionRequest,
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

MESSAGES = [
    Hello(token="dev_abc"),
    SessionCreate(name="webapp", cwd="/home/me/code/webapp"),
    SessionCreate(name="webapp", cwd="/home/me/code/webapp", resume_id="976f-abcd"),
    ListSessions(cwd="/home/me/code/webapp"),
    ProjectSessions(
        cwd="/home/me/code/webapp",
        sessions=[ProjectSessionInfo(id="976f", title="Refactor", modified=1729, messages=42)],
    ),
    SessionAttach(id="sess_1", replay_bytes=65536),
    Input(session="sess_1", data="continue\n"),
    PermissionResponse(id="req_1", decision="allow"),
    Welcome(
        daemon_version="0.1.0",
        hostname="dev-box",
        sessions=[SessionInfo(id="sess_1", name="webapp", cwd="/x", status="running")],
    ),
    SessionCreated(id="sess_2", name="api", cwd="/home/me/code/api"),
    Output(session="sess_1", data="hello\r\n", stream="stdout"),
    PermissionRequest(
        id="req_1",
        session="sess_1",
        tool="Bash",
        input={"command": "ls -la"},
        summary="Bash: ls -la",
        received_at=1729267200,
    ),
    Notification(session="sess_1", kind="task_complete", message="done", ts=1729267260),
    Error(code="session_not_found", message="sess_x"),
    Ping(ts=1),
    Pong(ts=1),
]


@pytest.mark.parametrize("msg", MESSAGES, ids=lambda m: type(m).__name__)
def test_round_trip(msg):
    assert decode(encode(msg)) == msg


def test_unknown_type_raises():
    with pytest.raises(ValueError):
        decode('{"type": "no_such_message"}')


def test_welcome_nested_sessions_are_typed():
    original = Welcome(
        daemon_version="0.1.0",
        hostname="dev-box",
        sessions=[SessionInfo(id="sess_1", name="webapp", cwd="/x", status="running")],
    )
    w = decode(encode(original))
    assert isinstance(w, Welcome)
    assert isinstance(w.sessions[0], SessionInfo)
    assert w.sessions[0].status == "running"


def test_project_sessions_nested_are_typed():
    original = ProjectSessions(
        cwd="/x",
        sessions=[ProjectSessionInfo(id="976f", title="T", modified=1, messages=2)],
    )
    p = decode(encode(original))
    assert isinstance(p, ProjectSessions)
    assert isinstance(p.sessions[0], ProjectSessionInfo)
    assert p.sessions[0].title == "T"
