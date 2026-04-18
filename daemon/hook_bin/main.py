"""claude-remote-hook

Tiny CLI Claude Code invokes for every hook event. Reads the hook
payload as JSON on stdin, forwards to the daemon's Unix socket, writes
the daemon's decision JSON to stdout.

Keep this stupid: all logic lives in the daemon. Hook script only
shuttles bytes. That way we don't have to redeploy the hook binary
when daemon logic changes.

Fail-open by default: if the daemon is unreachable, write
{"decision":"approve"} so a crashed daemon never bricks Claude Code.
"""
from __future__ import annotations

import os
import socket
import sys
import time

DEFAULT_SOCKET = "/tmp/claude-remote.sock"
DEFAULT_ALLOW = b'{"decision":"approve"}\n'
TIMEOUT_SEC = 310  # daemon times out at 300s; give it slack


def main() -> int:
    socket_path = os.environ.get("CLAUDE_REMOTE_SOCKET", DEFAULT_SOCKET)

    try:
        payload = sys.stdin.buffer.read()
    except Exception:
        sys.stdout.buffer.write(DEFAULT_ALLOW)
        return 0

    # Inject the session id (set by the daemon when spawning claude).
    # We pass it as a header line so the daemon can correlate without
    # parsing every hook payload variant.
    session_id = os.environ.get("CLAUDE_REMOTE_SESSION", "")
    framed = f"{session_id}\n".encode() + payload
    if not framed.endswith(b"\n"):
        framed += b"\n"

    try:
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as sock:
            sock.settimeout(TIMEOUT_SEC)
            sock.connect(socket_path)
            sock.sendall(framed)
            sock.shutdown(socket.SHUT_WR)
            chunks: list[bytes] = []
            deadline = time.monotonic() + TIMEOUT_SEC
            while True:
                if time.monotonic() > deadline:
                    raise TimeoutError("daemon read timeout")
                chunk = sock.recv(4096)
                if not chunk:
                    break
                chunks.append(chunk)
            response = b"".join(chunks)
            if not response:
                response = DEFAULT_ALLOW
            sys.stdout.buffer.write(response)
            return 0
    except (FileNotFoundError, ConnectionRefusedError):
        # Daemon not running — fail open.
        print(
            "claude-remote-hook: daemon not reachable at "
            f"{socket_path} — auto-approving",
            file=sys.stderr,
        )
        sys.stdout.buffer.write(DEFAULT_ALLOW)
        return 0
    except Exception as exc:
        print(f"claude-remote-hook: error {exc!r} — auto-approving", file=sys.stderr)
        sys.stdout.buffer.write(DEFAULT_ALLOW)
        return 0


if __name__ == "__main__":
    sys.exit(main())
