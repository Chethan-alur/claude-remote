# Windows toast client

`claude-notify-listener.ps1` is a **WebSocket client of the claude-remote daemon**.
It registers as a notification client, receives permission requests and
notifications, shows them as native Windows toasts (WinRT, no module install),
and sends your Approve/Deny decision back over the same socket.

It is one of several equal clients: the daemon fans each permission request out
to **all** connected clients (this listener and the Android app). The first to
answer wins; the daemon then broadcasts `permission_resolved` and every other
client dismisses its toast automatically.

## Prerequisites

- Windows 10/11 with PowerShell 5.1+ (uses built-in `System.Net.WebSockets`).
- The daemon running on the dev machine (Linux/WSL) with the hooks installed
  (`scripts/install-hooks.sh`).
- A reachable daemon WebSocket port (default 8770) — see Connectivity.

## Connectivity

The listener dials **out** to the daemon (the opposite of the retired
HTTP/SSH-reverse-tunnel design). Pick one:

- **SSH LocalForward** — in the Windows `~/.ssh/config` host block for the dev
  machine, add:

  ```
  LocalForward 8770 127.0.0.1:8770
  ```

  Then connect with `-DaemonUrl ws://127.0.0.1:8770`.

- **LAN / WireGuard** — connect directly: `-DaemonUrl ws://<daemon-host-or-wg-ip>:8770`.

## Running

```powershell
powershell -ExecutionPolicy Bypass -File clients\windows\claude-notify-listener.ps1 `
    -DaemonUrl ws://127.0.0.1:8770 `
    -Token <paired-device-token>
```

Parameters (all optional; env-var fallbacks in parentheses):

| Param           | Default                        | Meaning |
| --------------- | ------------------------------ | ------- |
| `-DaemonUrl`    | `ws://127.0.0.1:8770` (`CLAUDE_REMOTE_DAEMON_URL`) | Daemon WebSocket URL |
| `-Token`        | `windows-toast` (`CLAUDE_REMOTE_TOKEN`) | Device token; only enforced when the daemon runs with `--require-auth` |
| `-CallbackPort` | `58737`                        | Loopback port for the toast-button callback (see below) |
| `-FocusProcess` | `Code, WindowsTerminal, …`     | Window types eligible to be raised when you click a toast |

To run hidden at login, point a Startup shortcut at the same command with
`-WindowStyle Hidden`.

## How a button click reaches the daemon

WinRT toast buttons activate a **separate** process via a private
`claudenotify:` URI scheme (registered in HKCU on first run, no admin) and a
hidden VBScript shim (`claude-decide.vbs`, generated next to the script). That
shim cannot touch this process's WebSocket, so the listener also runs a tiny
loopback `HttpListener` on `127.0.0.1:<CallbackPort>`: the shim POSTs the
decision to `/decide`, and the listener forwards it to the daemon as a
`permission_response`. The main loop multiplexes the WebSocket receive and the
HTTP accept on a single thread, so all socket sends happen from one thread
(`ClientWebSocket` forbids concurrent sends).

`claude-decide.vbs` is generated at runtime (it embeds machine-specific paths),
so it is intentionally **not** committed.

## Notes / limitations

- A permission answered at the **local terminal** is invisible to the daemon
  (hooks do not report the interactive prompt's outcome), so no
  `permission_resolved` is emitted for that case; the toast is dismissed when
  the request later expires, or you can dismiss it manually.
- This client is verified manually on Windows (it cannot be exercised from the
  Linux daemon's CI). See `docs/handoffs/` for the cross-session test process.

The previous HTTP-over-SSH-reverse-tunnel implementation (driven by two `.sh`
hook bridges) is kept for reference under [`legacy/`](legacy/).
