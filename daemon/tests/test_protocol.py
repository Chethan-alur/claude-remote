"""Encode/decode round-trips for every wire-protocol message type.

The cheapest possible regression net: if a dataclass field or the `type`
discriminator drifts away from `protocol/messages.md`, these break.
"""
from __future__ import annotations

import pytest

from claude_remote_daemon.protocol import (
    CheckPath,
    DeleteSession,
    Error,
    FileUpload,
    FileUploaded,
    Hello,
    Input,
    KillSession,
    ListSessions,
    Notification,
    Output,
    PathChecked,
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
    SessionsUpdate,
    Welcome,
    decode,
    encode,
)

MESSAGES = [
    Hello(token="dev_abc"),
    SessionCreate(name="webapp", cwd="/home/me/code/webapp"),
    SessionCreate(name="webapp", cwd="/home/me/code/webapp", resume_id="976f-abcd"),
    ListSessions(cwd="/home/me/code/webapp"),
    DeleteSession(cwd="/home/me/code/webapp", id="976f-abcd"),
    ProjectSessions(
        cwd="/home/me/code/webapp",
        sessions=[ProjectSessionInfo(id="976f", title="Refactor", modified=1729, messages=42)],
    ),
    SessionAttach(id="sess_1", replay_bytes=65536),
    Input(session="sess_1", data="continue\n"),
    PermissionResponse(id="req_1", decision="allow"),
    FileUpload(
        session="sess_1",
        filename="shot.png",
        upload_id="u_1",
        seq=0,
        total=1,
        data="aGVsbG8=",
    ),
    FileUploaded(session="sess_1", upload_id="u_1", path="/x/uploads/shot.png"),
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
    KillSession(id="sess_1"),
    CheckPath(path="/home/me/code/webapp"),
    PathChecked(path="/home/me/code/webapp", is_dir=True),
    SessionsUpdate(
        sessions=[
            SessionInfo(
                id="sess_1", name="webapp", cwd="/x", status="running",
                started_at=1751200000, last_activity=1751200500,
            )
        ],
    ),
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


def test_sessions_update_nested_are_typed():
    original = SessionsUpdate(
        sessions=[
            SessionInfo(
                id="sess_1", name="webapp", cwd="/x", status="dead",
                started_at=1751200000, last_activity=1751200500,
            )
        ],
    )
    u = decode(encode(original))
    assert isinstance(u, SessionsUpdate)
    assert isinstance(u.sessions[0], SessionInfo)
    assert u.sessions[0].started_at == 1751200000
    assert u.sessions[0].last_activity == 1751200500


def test_session_info_timestamps_default_zero():
    # Old peers omit the new fields; decode must still succeed with defaults.
    s = decode('{"type": "welcome", "daemon_version": "0.1.0", "hostname": "h", '
               '"sessions": [{"id": "s", "name": "n", "cwd": "/x", "status": "running"}]}')
    assert s.sessions[0].started_at == 0
    assert s.sessions[0].last_activity == 0
