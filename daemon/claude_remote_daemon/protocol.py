"""Wire-protocol message types. Mirrors `protocol/messages.md`.

We use plain dataclasses + a small encode/decode pair instead of pydantic
to keep dependencies thin. If you find yourself wanting validation, swap
to pydantic — these classes will translate 1:1.
"""
from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field
from typing import Any


# --- phone -> daemon ------------------------------------------------------

@dataclass
class Hello:
    token: str
    type: str = "hello"


@dataclass
class SessionCreate:
    name: str
    cwd: str
    resume_id: str = ""  # when set, resume that past session (claude --resume)
    type: str = "session_create"


@dataclass
class ListSessions:
    cwd: str
    type: str = "list_sessions"


@dataclass
class SessionAttach:
    id: str
    replay_bytes: int = 0
    type: str = "session_attach"


@dataclass
class Input:
    session: str
    data: str
    type: str = "input"


@dataclass
class PermissionResponse:
    id: str
    decision: str  # allow | deny | allow_always | deny_always
    type: str = "permission_response"


# --- daemon -> phone ------------------------------------------------------

@dataclass
class SessionInfo:
    id: str
    name: str
    cwd: str
    status: str  # running | waiting | idle | dead


@dataclass
class Welcome:
    daemon_version: str
    hostname: str
    sessions: list[SessionInfo] = field(default_factory=list)
    type: str = "welcome"


@dataclass
class SessionCreated:
    id: str
    name: str
    cwd: str
    type: str = "session_created"


@dataclass
class ProjectSessionInfo:
    id: str
    title: str
    modified: int  # epoch seconds
    messages: int


@dataclass
class ProjectSessions:
    cwd: str
    sessions: list[ProjectSessionInfo] = field(default_factory=list)
    type: str = "project_sessions"


@dataclass
class Output:
    session: str
    data: str
    stream: str  # stdout | stderr
    type: str = "output"


@dataclass
class PermissionRequest:
    id: str
    session: str
    tool: str
    input: dict[str, Any]
    summary: str
    received_at: int
    type: str = "permission_request"


@dataclass
class Notification:
    session: str
    kind: str  # task_complete | error | permission_timeout | info
    message: str
    ts: int
    type: str = "notification"


@dataclass
class Error:
    code: str
    message: str
    type: str = "error"


# --- both directions ------------------------------------------------------

@dataclass
class Ping:
    ts: int
    type: str = "ping"


@dataclass
class Pong:
    ts: int
    type: str = "pong"


# --- (de)serialization ----------------------------------------------------

# Map "type" -> dataclass, used by `decode`.
_TYPE_REGISTRY: dict[str, type] = {
    cls().__class__.__dict__.get("type", None) or cls.__name__: cls  # type: ignore[call-arg]
    for cls in []
}
# Hand-rolled because dataclasses without defaults can't be instantiated empty.
_TYPE_REGISTRY = {
    "hello": Hello,
    "session_create": SessionCreate,
    "session_attach": SessionAttach,
    "list_sessions": ListSessions,
    "input": Input,
    "permission_response": PermissionResponse,
    "welcome": Welcome,
    "session_created": SessionCreated,
    "project_sessions": ProjectSessions,
    "output": Output,
    "permission_request": PermissionRequest,
    "notification": Notification,
    "error": Error,
    "ping": Ping,
    "pong": Pong,
}


def encode(msg: Any) -> str:
    """Dataclass -> JSON string."""
    return json.dumps(asdict(msg))


def decode(raw: str) -> Any:
    """JSON string -> dataclass.

    Raises ValueError on unknown type or malformed JSON.
    """
    data = json.loads(raw)
    t = data.get("type")
    cls = _TYPE_REGISTRY.get(t)
    if cls is None:
        raise ValueError(f"unknown message type: {t!r}")
    # Strip "type" before passing to constructor; it'll be set by the default.
    payload = {k: v for k, v in data.items() if k != "type"}
    # Nested dataclass lists — special-case.
    if cls is Welcome and "sessions" in payload:
        payload["sessions"] = [SessionInfo(**s) for s in payload["sessions"]]
    if cls is ProjectSessions and "sessions" in payload:
        payload["sessions"] = [ProjectSessionInfo(**s) for s in payload["sessions"]]
    return cls(**payload)
