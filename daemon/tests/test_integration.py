"""End-to-end smoke test of the daemon over a real WebSocket.

Drives the WsServer with a fake spawned command (a bash echo loop) instead
of the real `claude` binary, and checks the create -> output -> input path:

  hello -> welcome
  session_create -> session_created + streamed output ("READY")
  input -> the PTY echoes it back as output ("ping")
"""
from __future__ import annotations

import asyncio

import websockets

from claude_remote_daemon.auth import AuthStore
from claude_remote_daemon.hooks import HookBridge
from claude_remote_daemon.protocol import (
    Hello,
    Input,
    ListSessions,
    Output,
    ProjectSessions,
    SessionCreate,
    SessionCreated,
    Welcome,
    decode,
    encode,
)
from claude_remote_daemon.server import WsServer
from claude_remote_daemon.session import SessionManager

# A deterministic stand-in for `claude`: announce readiness, then echo stdin.
# Terminal echo (on by default in the PTY) plus `cat` means typed input comes
# straight back as output.
FAKE_CLAUDE = ["bash", "-c", "echo READY; cat"]


async def _recv_until(ws, predicate, timeout=5.0):
    """Collect decoded frames until `predicate(frame)` is true; return them."""
    frames = []

    async def loop():
        while True:
            frame = decode(await ws.recv())
            frames.append(frame)
            if predicate(frame):
                return

    await asyncio.wait_for(loop(), timeout=timeout)
    return frames


async def test_create_output_input_round_trip(tmp_path):
    auth = AuthStore(tmp_path / "devices.json")
    sessions = SessionManager(
        hook_socket=str(tmp_path / "hook.sock"), claude_cmd=FAKE_CLAUDE
    )
    hooks = HookBridge(str(tmp_path / "hook.sock"), sessions)
    server = WsServer("127.0.0.1", 0, sessions, hooks, auth)

    async with websockets.serve(server._handle, "127.0.0.1", 0) as wss:
        port = wss.sockets[0].getsockname()[1]
        try:
            async with websockets.connect(f"ws://127.0.0.1:{port}/") as ws:
                # Handshake.
                await ws.send(encode(Hello(token="x")))
                welcome = decode(await ws.recv())
                assert isinstance(welcome, Welcome)
                assert welcome.sessions == []

                # Create a session; expect session_created then output.
                await ws.send(encode(SessionCreate(name="t", cwd=str(tmp_path))))
                frames = await _recv_until(
                    ws,
                    lambda f: isinstance(f, Output) and "READY" in f.data,
                )
                created = [f for f in frames if isinstance(f, SessionCreated)]
                assert len(created) == 1
                sid = created[0].id

                # Send a prompt; expect it echoed back through the PTY.
                await ws.send(encode(Input(session=sid, data="ping\n")))
                await _recv_until(
                    ws,
                    lambda f: isinstance(f, Output) and "ping" in f.data,
                )
        finally:
            await sessions.shutdown()


async def test_list_sessions_empty_project(tmp_path):
    auth = AuthStore(tmp_path / "devices.json")
    sessions = SessionManager(claude_cmd=FAKE_CLAUDE)
    hooks = HookBridge(str(tmp_path / "hook.sock"), sessions)
    server = WsServer("127.0.0.1", 0, sessions, hooks, auth)

    async with websockets.serve(server._handle, "127.0.0.1", 0) as wss:
        port = wss.sockets[0].getsockname()[1]
        async with websockets.connect(f"ws://127.0.0.1:{port}/") as ws:
            await ws.send(encode(Hello(token="x")))
            await ws.recv()  # welcome
            await ws.send(encode(ListSessions(cwd=str(tmp_path / "no-such-project"))))
            reply = decode(await ws.recv())
            assert isinstance(reply, ProjectSessions)
            assert reply.sessions == []


async def test_create_bad_cwd_returns_error(tmp_path):
    auth = AuthStore(tmp_path / "devices.json")
    sessions = SessionManager(claude_cmd=FAKE_CLAUDE)
    hooks = HookBridge(str(tmp_path / "hook.sock"), sessions)
    server = WsServer("127.0.0.1", 0, sessions, hooks, auth)

    async with websockets.serve(server._handle, "127.0.0.1", 0) as wss:
        port = wss.sockets[0].getsockname()[1]
        async with websockets.connect(f"ws://127.0.0.1:{port}/") as ws:
            await ws.send(encode(Hello(token="x")))
            await ws.recv()  # welcome
            await ws.send(
                encode(SessionCreate(name="t", cwd="/no/such/directory/xyz"))
            )
            err = decode(await ws.recv())
            assert err.type == "error"
            assert err.code == "session_creation_failed"
        await sessions.shutdown()
