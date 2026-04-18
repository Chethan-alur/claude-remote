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
import signal
import sys
from pathlib import Path

logger = logging.getLogger("claude_remote_daemon")


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(prog="claude-remote-daemon")
    p.add_argument("--port", type=int, default=8765, help="WebSocket port")
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
    p.add_argument("-v", "--verbose", action="count", default=0)
    return p.parse_args(argv)


async def run(args: argparse.Namespace) -> None:
    args.state_dir.mkdir(parents=True, exist_ok=True)

    # TODO(claude-code):
    #   auth = AuthStore(args.state_dir / "devices.json")
    #   sessions = SessionManager()
    #   hooks = HookBridge(args.hook_socket, sessions)
    #   server = WsServer(args.bind, args.port, sessions, hooks, auth)
    #   discovery = Discovery("claude-remote", args.port)
    #
    #   print(f"Pairing code: {auth.current_code()}", flush=True)
    #
    #   await asyncio.gather(
    #       hooks.serve(),
    #       server.serve(),
    #       discovery.advertise(),
    #   )

    # Placeholder so `claude-remote-daemon` runs without crashing during dev.
    logger.info("daemon up on %s:%d (placeholder — implement run())", args.bind, args.port)
    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, stop.set)
    await stop.wait()
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
