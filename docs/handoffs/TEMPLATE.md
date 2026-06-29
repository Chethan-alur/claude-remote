# Handoff: <title>

- **Status:** pending <!-- pending | in progress | passed | failed | partially verified -->
- **Date:** YYYY-MM-DD
- **From:** <originating session / environment, e.g. "Linux session, Opus 4.8">
- **To:** <target environment, e.g. "Windows session with Android Studio emulator">
- **Branch:** <git branch holding the changes>

## Context

What was changed and why, in two or three sentences. Link the spec/docs that
describe the feature.

## Changes in this handoff

List the files touched and what each change does. Note anything that crosses the
three-way protocol sync (`protocol/messages.md`, `daemon/.../protocol.py`,
`android/.../model/Messages.kt`).

## Already verified by the originating session

What was tested before handoff and the result (e.g. daemon `pytest`, `ruff`,
protocol round-trips). Be explicit about what could **not** be verified locally
and why.

## Environment / setup for the receiving session

Exact steps to get a runnable system in the target environment. For Android +
daemon this usually means:

- Where and how to run the daemon (Linux/WSL): `claude-remote-daemon -v`.
- How the emulator reaches it (`10.0.2.2:8765`; with the daemon in WSL2,
  `localhostForwarding` makes Windows `localhost:8765` reachable).
- Build/install the app: `cd android && ./gradlew installDebug` (needs
  `JAVA_HOME`; Android Studio's bundled JBR works).

## Verification checklist

Concrete, observable pass/fail items. Each should describe what to do and the
expected result.

- [ ] Item 1 — action → expected outcome
- [ ] Item 2 — …

## Known risks / things to watch

Assumptions, fragile points, values that may need tuning, and fallback
behaviour.

## Results

> Filled in by the receiving session.

- Outcome per checklist item (pass/fail + notes).
- Logs, screenshots, logcat / daemon output for any failure.
- New bugs or follow-ups (link issues/PRs).
