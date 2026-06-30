# Deploying the daemon as a systemd service

This runbook explains how to run `claude-remote-daemon` permanently on a Linux
(or WSL Ubuntu) machine using systemd, so that it starts on boot, restarts on
failure, and survives logout. It assumes the daemon has already been installed
with `./scripts/setup-daemon.sh` and that the hooks are registered
(`./scripts/install-hooks.sh`); if not, complete `daemon/README.md` first.

## Why a *user* service, not a system service

The daemon must run as your normal login user, never as root, because it:

- spawns the `claude` binary, which reads your personal `~/.claude/`
  configuration and credentials, and
- stores its own state (device tokens, the hook Unix socket) under
  `~/.claude-remote/` and `/tmp`.

A root-owned system service would resolve `$HOME` to `/root` and run Claude Code
with the wrong configuration. Therefore the unit is installed as a **systemd
user service** under `~/.config/systemd/user/`, managed with the
`systemctl --user` family of commands.

One consequence: by default a user service only runs while you have an active
login session, and it stops when you log out. To keep the daemon running across
logout and reboot you must enable *lingering* for your user (covered below).

## Quick start

```bash
# From the repository root. Sets up the venv if you have not already.
./scripts/setup-daemon.sh

# Install and start the user service.
./scripts/install-service.sh

# Keep it running across logout / reboot (one time, needs sudo).
sudo loginctl enable-linger "$USER"
```

That is the whole happy path. The sections below explain each moving part.

## What `install-service.sh` does

The script is idempotent — running it again simply re-renders the unit, which is
how you pick up a moved checkout. Concretely it:

1. Verifies `systemctl` exists and that the daemon binary
   (`daemon/.venv/bin/claude-remote-daemon`) is present.
2. On the **first** run, copies `daemon/systemd/claude-remote.env.example` to
   `~/.config/claude-remote/daemon.env`. On later runs it leaves your edited
   copy untouched. Either way it then ensures `CLAUDE_REMOTE_CLAUDE_CMD` (the
   absolute path to `claude`, detected from your shell) and `PATH` (your current
   PATH) are present in that file — adding each only if absent, so your edits are
   never clobbered. This is what lets the daemon, running under systemd's minimal
   PATH, find `claude` and give the sessions it spawns a usable environment.
3. Renders `daemon/systemd/claude-remote.service` (a template containing
   `@DAEMON_BIN@`, `@WORKDIR@`, `@ENVFILE@` placeholders) into
   `~/.config/systemd/user/claude-remote.service`, substituting absolute paths.
4. Runs `systemctl --user daemon-reload` and
   `systemctl --user enable --now claude-remote.service`.

The committed `.service` file is a **template**; do not point systemd at it
directly. The rendered, path-substituted copy is what systemd actually loads.

## Configuration

All tunables live in `~/.config/claude-remote/daemon.env`, a plain
`KEY=VALUE` environment file (no shell quoting, no `export`). The keys:

| Key          | Default   | Meaning                                                        |
|--------------|-----------|----------------------------------------------------------------|
| `BIND`       | `0.0.0.0` | Interface the WebSocket server binds to. Use `127.0.0.1` to restrict the daemon to this machine (e.g. when reached only over a locally-terminated WireGuard tunnel). |
| `PORT`       | `8770`    | WebSocket port. Must match the Android app and the mDNS advert. |
| `EXTRA_ARGS` | `-v`      | Extra flags, split on whitespace. `-v`/`-vv` raise log verbosity; `--require-auth` rejects any device that has not paired. |
| `CLAUDE_REMOTE_CLAUDE_CMD` | *(auto)* | Absolute path to the `claude` binary, auto-seeded by `install-service.sh` from your shell. Needed because the systemd PATH is minimal and would not find a `claude` in `~/.local/bin`. |
| `PATH`       | *(auto)*  | PATH for the daemon **and the sessions it spawns** (they inherit it), auto-seeded from your shell's PATH at install time so `claude` and your tools resolve. |

After editing, restart the service:

```bash
systemctl --user restart claude-remote
```

`EXTRA_ARGS` is expanded *unquoted* in the unit, so several flags work as
expected, for example `EXTRA_ARGS=-vv --require-auth`.

## Day-to-day operations

```bash
# Health and the last few log lines.
systemctl --user status claude-remote

# Follow logs live (the pairing code is printed here at startup).
journalctl --user -u claude-remote -f

# Just the most recent pairing code.
journalctl --user -u claude-remote | grep -i 'pairing code' | tail -1

# Start / stop / restart.
systemctl --user start claude-remote
systemctl --user stop claude-remote
systemctl --user restart claude-remote

# Stop it starting at login (without removing the unit).
systemctl --user disable claude-remote
```

## Lingering (surviving logout and reboot)

Enable it once:

```bash
sudo loginctl enable-linger "$USER"
```

Verify:

```bash
loginctl show-user "$USER" --property=Linger     # expect Linger=yes
```

With lingering enabled, systemd starts your user manager at boot and brings up
any enabled user services — including this one — without requiring an
interactive login. To reverse it: `sudo loginctl disable-linger "$USER"`.

### WSL caveat

WSL distributions only run a systemd user manager if systemd is enabled. Ensure
`/etc/wsl.conf` contains:

```ini
[boot]
systemd=true
```

then restart the distribution from a Windows prompt with `wsl --shutdown`.
Lingering on WSL keeps the daemon alive only while the WSL VM is running; the VM
itself starts on demand when you open a shell or another process attaches to it,
so treat WSL as "running while Windows is up and WSL has been touched", not as a
true always-on host.

## Upgrading

After pulling new code or moving the checkout:

```bash
git pull                      # or relocate the repository
./scripts/setup-daemon.sh     # refresh the venv / reinstall the package
./scripts/install-service.sh  # re-render the unit with current paths
systemctl --user restart claude-remote
```

## Uninstalling

```bash
systemctl --user disable --now claude-remote
rm ~/.config/systemd/user/claude-remote.service
systemctl --user daemon-reload
sudo loginctl disable-linger "$USER"   # optional
# Configuration and state, remove if you want a clean slate:
# rm -r ~/.config/claude-remote ~/.claude-remote
```

## Troubleshooting

- **`Failed to connect to bus` / `systemctl --user` errors over SSH.** The
  user D-Bus session may be missing. Confirm `XDG_RUNTIME_DIR` is set
  (`echo $XDG_RUNTIME_DIR` → `/run/user/<uid>`). With lingering enabled this is
  provided automatically; otherwise log in on a normal session once.
- **Service is `active` but the phone cannot connect.** Check `BIND`/`PORT` in
  `daemon.env`, confirm the firewall allows the port on the LAN, and that the
  app targets the machine's real LAN IP (not `127.0.0.1`).
- **`enable --now` reports the unit is not found.** You likely edited the
  template in the repo rather than re-running `install-service.sh`; the unit
  systemd loads lives at `~/.config/systemd/user/claude-remote.service`.
- **Permission requests never reach the phone.** That is the hook layer, not
  the service — verify `./scripts/install-hooks.sh` ran and the
  `claude-remote-hook` symlink resolves (see `daemon/README.md`).
- **Creating a session fails with `failed to spawn [claude]`.** The systemd
  service's PATH is minimal and does not include `~/.local/bin` (where the
  native installer puts `claude`). Set `CLAUDE_REMOTE_CLAUDE_CMD` to claude's
  absolute path (`command -v claude`) in `daemon.env` and
  `systemctl --user restart claude-remote`. Re-running `./scripts/install-service.sh`
  also seeds this automatically. The spawned sessions inherit the daemon's
  `PATH`, so set `PATH` there too if a session cannot find your other tools.
