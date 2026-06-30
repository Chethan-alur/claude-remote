"""Daemon entry point.

Wires together all the moving parts and runs the asyncio event loop:
  - SessionManager (session.py): registry of running Claude Code PTYs
  - HookBridge   (hooks.py):   Unix socket listener for hook callbacks
  - WsServer     (server.py):  WebSocket server for the phone
  - Discovery    (discovery.py): mDNS advertise
  - AuthStore    (auth.py):    pairing codes + device tokens

TODO(claude-code): implement.
"""
from __future__ import annotations

import argparse
import asyncio
import logging
import os
import shlex
import signal
import sys
from pathlib import Path

from .auth import AuthStore
from .discovery import Discovery
from .hooks import HookBridge
from .server import WsServer
from .session import SessionManager

logger = logging.getLogger("claude_remote_daemon")


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(prog="claude-remote-daemon")
    p.add_argument("--port", type=int, default=8770, help="WebSocket port")
    p.add_argument("--bind", default="0.0.0.0", help="Bind address")
    p.add_argument(
        "--hook-socket",
        default="/tmp/claude-remote.sock",
        help="Unix socket for the hook bridge",
    )
    p.add_argument(
        "--state-dir",
        type=Path,
        default=Path.home() / ".claude-remote",
        help="Directory for tokens, session metadata",
    )
    p.add_argument(
        "--claude-cmd",
        default=os.environ.get("CLAUDE_REMOTE_CLAUDE_CMD", "claude"),
        help="Command to spawn per session (override for testing without claude)",
    )
    p.add_argument(
        "--require-auth",
        action="store_true",
        help="Reject WebSocket clients whose token is not a paired device "
        "(default: accept any token with a warning, for early dev)",
    )
    p.add_argument(
        "--permission-wait",
        type=float,
        default=float(os.environ.get("CLAUDE_REMOTE_PERMISSION_WAIT", "30")),
        help="Seconds to wait for a client to answer a permission request "
        "before falling through to Claude's local prompt (default: 30; "
        "must stay below the hook CLI's 310s read timeout)",
    )
    p.add_argument("-v", "--verbose", action="count", default=0)
    return p.parse_args(argv)


async def run(args: argparse.Namespace) -> None:
    args.state_dir.mkdir(parents=True, exist_ok=True)

    claude_cmd = shlex.split(args.claude_cmd) if args.claude_cmd else ["claude"]

    auth = AuthStore(args.state_dir / "devices.json")
    sessions = SessionManager(hook_socket=args.hook_socket, claude_cmd=claude_cmd)
    hooks = HookBridge(args.hook_socket, sessions, client_wait=args.permission_wait)
    server = WsServer(
        args.bind, args.port, sessions, hooks, auth, require_auth=args.require_auth
    )
    discovery = Discovery("claude-remote", args.port)

    print(f"Pairing code: {auth.current_code()}", flush=True)
    logger.info("daemon up on %s:%d (spawn cmd: %s)", args.bind, args.port, claude_cmd)

    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, stop.set)

    stop_task = asyncio.create_task(stop.wait())
    work = [
        asyncio.create_task(hooks.serve(), name="hooks"),
        asyncio.create_task(server.serve(), name="server"),
        asyncio.create_task(discovery.advertise(), name="discovery"),
        asyncio.create_task(sessions.reap_adopted(), name="reaper"),
    ]
    try:
        done, pending = await asyncio.wait(
            {stop_task, *work}, return_when=asyncio.FIRST_COMPLETED
        )
        # If a component crashed (rather than a signal), surface it.
        for t in done:
            if t is not stop_task and not t.cancelled() and t.exception() is not None:
                logger.error("component %s crashed", t.get_name(), exc_info=t.exception())
    finally:
        for t in (stop_task, *work):
            t.cancel()
        await sessions.shutdown()
        logger.info("shutting down")


def main() -> None:
    args = parse_args()
    level = logging.WARNING - 10 * args.verbose
    logging.basicConfig(
        level=max(level, logging.DEBUG),
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    try:
        asyncio.run(run(args))
    except KeyboardInterrupt:
        sys.exit(0)


if __name__ == "__main__":
    main()
