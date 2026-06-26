"""Pairing over HTTP + token-gated WebSocket connect."""
from __future__ import annotations

import asyncio
import json
import urllib.error
import urllib.request

import pytest
import websockets

from claude_remote_daemon.auth import AuthStore
from claude_remote_daemon.hooks import HookBridge
from claude_remote_daemon.protocol import Hello, Welcome, decode, encode
from claude_remote_daemon.server import WsServer
from claude_remote_daemon.session import SessionManager


def _http_get(url: str):
    try:
        with urllib.request.urlopen(url, timeout=5) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read())


async def _serve(server: WsServer):
    return await websockets.serve(
        server._handle, "127.0.0.1", 0, process_request=server._http_request
    )


def _make_server(tmp_path, require_auth: bool):
    auth = AuthStore(tmp_path / "devices.json")
    sessions = SessionManager(claude_cmd=["bash", "-c", "echo hi; cat"])
    hooks = HookBridge(str(tmp_path / "h.sock"), sessions)
    return WsServer("127.0.0.1", 0, sessions, hooks, auth, require_auth=require_auth), auth


async def test_pair_then_connect_with_token(tmp_path):
    server, auth = _make_server(tmp_path, require_auth=True)
    code = auth.current_code()

    async with await _serve(server) as wss:
        port = wss.sockets[0].getsockname()[1]

        status, body = await asyncio.to_thread(
            _http_get, f"http://127.0.0.1:{port}/pair?code={code}&device=Pixel"
        )
        assert status == 200
        token = body["token"]
        assert token.startswith("dev_")

        # The freshly paired token is accepted.
        async with websockets.connect(f"ws://127.0.0.1:{port}/") as ws:
            await ws.send(encode(Hello(token=token)))
            assert isinstance(decode(await ws.recv()), Welcome)


async def test_bad_code_returns_400(tmp_path):
    server, auth = _make_server(tmp_path, require_auth=True)
    auth.current_code()
    async with await _serve(server) as wss:
        port = wss.sockets[0].getsockname()[1]
        status, body = await asyncio.to_thread(
            _http_get, f"http://127.0.0.1:{port}/pair?code=000000&device=x"
        )
        assert status == 400
        assert "error" in body


async def test_unknown_token_rejected_when_auth_required(tmp_path):
    server, _auth = _make_server(tmp_path, require_auth=True)
    async with await _serve(server) as wss:
        port = wss.sockets[0].getsockname()[1]
        async with websockets.connect(f"ws://127.0.0.1:{port}/") as ws:
            await ws.send(encode(Hello(token="dev_bogus")))
            err = decode(await ws.recv())
            assert err.type == "error"
            assert err.code == "bad_token"
            # Server then closes the connection.
            with pytest.raises(websockets.ConnectionClosed):
                await ws.recv()


async def test_unknown_token_allowed_when_auth_not_required(tmp_path):
    server, _auth = _make_server(tmp_path, require_auth=False)
    async with await _serve(server) as wss:
        port = wss.sockets[0].getsockname()[1]
        async with websockets.connect(f"ws://127.0.0.1:{port}/") as ws:
            await ws.send(encode(Hello(token="anything")))
            assert isinstance(decode(await ws.recv()), Welcome)
