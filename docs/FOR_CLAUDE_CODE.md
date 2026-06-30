# Instructions for Claude Code

You are taking over the build of "Claude Remote" — a system that lets the user control Claude Code on this dev machine from their Android phone, with push notifications for permission prompts.

The daemon is Python (asyncio). The Android app is Kotlin + Compose. The user is a Python developer; respect their language preference for any backend or scripting work.

## Read first

1. `README.md` at the project root — overall architecture
2. `docs/DESIGN.md` — full design rationale (Python-specific notes on asyncio + PTY)
3. `docs/IMPLEMENTATION_PLAN.md` — the day-by-day build order
4. `protocol/messages.md` — wire protocol spec
5. `daemon/README.md` — daemon-specific setup

## Build order

Stick to the four-day plan in `docs/IMPLEMENTATION_PLAN.md`. Each day produces something testable. Do NOT try to build everything in parallel — the protocol gets validated in Day 1-2 and changes; locking it in too early forces rework.

## Constraints you should respect

- **Python (asyncio) for the daemon, NOT a different language.** The user picked Python deliberately.
- **Use `loop.add_reader()` for PTY integration on Linux/WSL.** Don't fall back to thread-pool reads unless the user explicitly says they want Windows-native support.
- **Local-network-only for the POC.** No relay server, no cloud auth, no FCM. The phone reaches the daemon directly over WiFi via mDNS-discovered IP. Cloud relay is v2.
- **Single-user assumption.** No multi-tenancy, no per-user encryption. The daemon listens on the LAN; if the user is on a hostile WiFi, that's a v2 problem (mTLS).
- **Hook-based, not scraping.** Permission detection MUST go through Claude Code's official hooks (`PreToolUse`, `Stop`, `Notification`). Do NOT parse terminal output looking for permission prompts — it's fragile and breaks on every Claude Code release.
- **One PTY per session.** A "session" is one running `claude` process bound to one project directory. The daemon manages a dict of session_id -> Session.
- **Notifications must work from the lock screen.** This is the whole point of the project. Use Android `RemoteInput` for voice replies and direct action buttons for Allow/Deny/Always.
- **No new Python dependencies without asking.** The current set is `websockets`, `ptyprocess`, `zeroconf`. If you want pydantic, httpx, structlog, etc., propose it first.

## Things you should ASK the user before doing

- Which Python version they have (`python3 --version`) — should be 3.10+; if older, update `pyproject.toml`
- Their Android Studio / SDK version — adjust `compileSdk` and `minSdk` if needed
- Whether they want the daemon to run as a systemd user service — if yes, generate `daemon/systemd/claude-remote.service` and add install instructions
- Whether they want a small CLI on the dev machine itself (e.g. `claude-remote ls`, `claude-remote attach <session>`) for local management without the phone
- Whether their installed Claude Code version supports the hook events listed in `scripts/install-hooks.sh` — run `claude --version` and check; the hook schema has shifted across Claude Code releases

## Things you should NOT do without asking

- Add any cloud dependency (FCM, a relay server, telemetry)
- Store the user's API keys or auth tokens anywhere — Claude Code already manages those
- Modify the user's `~/.claude/settings.json` directly — provide a script (`scripts/install-hooks.sh`) and let the user run it
- Add authentication beyond the pairing-code flow already scaffolded in `auth.py`
- Replace `ptyprocess` with `pexpect`, `pty.fork()`, or a custom solution — `ptyprocess` is the right tool

## Test as you go

- Use `websocat ws://localhost:8770` from the dev machine to manually drive the daemon before the Android app exists. Send hand-written JSON; observe responses.
- The hook script can be tested standalone: `echo '{"event":"PreToolUse","tool_name":"Bash"}' | CLAUDE_REMOTE_SESSION=sess_test claude-remote-hook`. With no daemon running it should fail-open and print `{"decision":"approve"}`.
- For the Android app, use the Android Emulator with host networking — it can reach the daemon at `10.0.2.2:8770`.
- Add `pytest` tests for `protocol.py` first — encode/decode round-trips for every message type. Cheapest possible regression net.

## Done state for the POC

After day 4, the user should be able to:

1. Run `claude-remote-daemon` on their dev machine
2. Open the Android app and see the daemon appear in a list
3. Tap to create a new session in `~/some-project`
4. Start chatting with Claude through the phone
5. When Claude requests Bash/Edit permission, get a notification on the phone (even when the screen is off)
6. Tap Allow/Deny/Always on the lock screen and see Claude continue
7. Get a "task complete" notification when Claude finishes

Anything beyond that — multiple concurrent sessions polished, voice reply via Android Assistant, the relay server, mTLS, a settings screen — is v2.

## Style notes

- Prefer dataclasses over plain dicts for any structured data
- Prefer `match/case` for message dispatch in Python 3.10+
- Type-annotate everything; the codebase is small enough that mypy strict mode is realistic
- Keep modules under ~300 lines; split when they grow
- For the Kotlin side: follow the existing patterns in `Messages.kt` and `WsClient.kt`. Don't introduce a new DI framework or reactive library.
