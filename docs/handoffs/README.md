# Cross-session testing handoffs

This directory holds **handoff documents**: a way for one Claude Code (or human)
session to pass pending testing and verification work to a *different* session
that has capabilities the originating session lacks.

## Why this exists

Work on this project spans environments that no single session can fully
exercise on its own:

- The **daemon** runs on Linux/WSL only (it relies on `asyncio`'s
  `loop.add_reader`), and its unit tests run there.
- The **Android app** is best verified on an **Android Studio emulator**,
  which is most conveniently driven from a **Windows** (or macOS) session.
- A session may have edited code but be unable to build it (for example, no
  `JAVA_HOME`/Android SDK in the sandbox).

When the implementing session cannot run the build or the simulator, it writes
a handoff document describing exactly what to test, and another session picks it
up, performs the verification, and records the results.

## The process

1. **Implement and self-verify what you can.** Run whatever tests are available
   in your environment (e.g. `daemon` pytest, ruff) and note the results.
2. **Commit the code changes** on a feature branch so the receiving session can
   check them out. A handoff that references uncommitted code is useless on a
   different machine.
3. **Create a handoff file** in this directory named
   `YYYY-MM-DD-<short-slug>.md`, copied from [`TEMPLATE.md`](TEMPLATE.md). Fill
   in every section: context, the exact changes, build/run steps for the target
   environment, and a concrete pass/fail verification checklist.
4. **Commit the handoff file** (typically in the same commit as the code).
5. The **receiving session** checks out the branch, follows the build/run steps,
   works through the checklist, and records outcomes in the file's **Results**
   section (pass/fail per item, plus any logs, screenshots, or new bugs).
6. The receiving session **commits the updated Results** and reports back to the
   originating session, or opens issues/PRs for any failures.

## Conventions

- One file per handoff; never overwrite a previous one — history matters.
- Keep the **Status** line at the top current: `pending` → `in progress`
  → `passed` / `failed` / `partially verified`.
- Cross-link related handoffs and the protocol/spec files they touch.
- Anything skipped or assumed must be stated explicitly under **Known risks**.
