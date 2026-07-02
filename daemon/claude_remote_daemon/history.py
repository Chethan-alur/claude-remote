"""Read Claude Code's on-disk session history for a project.

Claude Code stores each conversation as a JSONL transcript under
`~/.claude/projects/<encoded-cwd>/<session-id>.jsonl`, where the project
directory name is the absolute cwd with every non-alphanumeric character
replaced by '-'. The file stem is the session id used by `claude --resume`.

We surface, per project folder, the list of past sessions with a title
(the latest `ai-title` entry, falling back to the first human message),
the last-modified time, and a rough message count — the same shape the
VS Code extension / `claude --resume` picker shows.
"""
from __future__ import annotations

import json
import logging
import re
from dataclasses import dataclass
from pathlib import Path

logger = logging.getLogger(__name__)

PROJECTS_ROOT = Path.home() / ".claude" / "projects"

# Tags Claude injects into the first user turn that we strip for a clean title.
_TAG_RE = re.compile(r"<[^>]+>")


@dataclass
class ProjectSession:
    id: str
    title: str
    modified: int  # epoch seconds
    messages: int


@dataclass
class TranscriptMessage:
    role: str  # user | assistant
    text: str
    ts: int  # epoch seconds, 0 if unknown


def encode_project_dir(cwd: str) -> str:
    """Map an absolute cwd to Claude's project-directory name."""
    resolved = str(Path(cwd).expanduser().resolve())
    return re.sub(r"[^A-Za-z0-9]", "-", resolved)


def list_project_sessions(
    cwd: str, projects_root: Path = PROJECTS_ROOT
) -> list[ProjectSession]:
    """Return past Claude sessions for `cwd`, newest first. Empty if none."""
    project_dir = projects_root / encode_project_dir(cwd)
    if not project_dir.is_dir():
        return []

    sessions: list[ProjectSession] = []
    for path in project_dir.glob("*.jsonl"):
        try:
            sessions.append(_read_session(path))
        except Exception as exc:  # one bad transcript shouldn't sink the list
            logger.warning("skipping transcript %s: %s", path.name, exc)
    sessions.sort(key=lambda s: s.modified, reverse=True)
    return sessions


def delete_project_session(
    cwd: str, session_id: str, projects_root: Path = PROJECTS_ROOT
) -> bool:
    """Delete a past session's transcript. Returns True if a file was removed.

    `session_id` must be a bare file stem (no path separators) so deletion can
    never escape the project directory. Raises ValueError on a bad id.
    """
    if (
        not session_id
        or "/" in session_id
        or "\\" in session_id
        or session_id in (".", "..")
    ):
        raise ValueError(f"invalid session id: {session_id!r}")
    project_dir = (projects_root / encode_project_dir(cwd)).resolve()
    target = (project_dir / f"{session_id}.jsonl").resolve()
    if target.parent != project_dir:
        raise ValueError("refusing to delete outside the project directory")
    if not target.is_file():
        return False
    target.unlink()
    return True


def read_transcript(
    cwd: str,
    session_id: str = "",
    limit: int = 0,
    projects_root: Path = PROJECTS_ROOT,
) -> list[TranscriptMessage]:
    """Return the user/assistant messages of a conversation, oldest first.

    `session_id` is the transcript file stem (Claude's own session id). When it
    is empty — e.g. a freshly spawned session that has not yet revealed its id
    via a hook — the newest transcript in the project directory is used, which
    is the one Claude is actively writing. `limit` keeps only the last N
    messages (0 = all). Returns [] if no transcript is found.
    """
    if session_id and (
        "/" in session_id or "\\" in session_id or session_id in (".", "..")
    ):
        raise ValueError(f"invalid session id: {session_id!r}")

    project_dir = (projects_root / encode_project_dir(cwd)).resolve()
    if not project_dir.is_dir():
        return []

    path: Path | None = None
    if session_id:
        candidate = (project_dir / f"{session_id}.jsonl").resolve()
        if candidate.parent == project_dir and candidate.is_file():
            path = candidate
    if path is None:  # fall back to the newest transcript in the project
        transcripts = sorted(
            project_dir.glob("*.jsonl"),
            key=lambda p: p.stat().st_mtime,
            reverse=True,
        )
        if not transcripts:
            return []
        path = transcripts[0]

    messages: list[TranscriptMessage] = []
    with path.open(encoding="utf-8", errors="replace") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                continue
            role = obj.get("type")
            if role not in ("user", "assistant"):
                continue
            text = _clean_body(_extract_text(obj))
            if not text:
                continue  # tool-only turns carry no readable text
            messages.append(TranscriptMessage(role=role, text=text, ts=_ts(obj)))

    if limit and len(messages) > limit:
        messages = messages[-limit:]
    return messages


def _ts(entry: dict) -> int:
    """Best-effort epoch seconds from an entry's ISO 'timestamp'; 0 if absent."""
    raw = entry.get("timestamp")
    if not isinstance(raw, str) or not raw:
        return 0
    try:
        from datetime import datetime

        return int(datetime.fromisoformat(raw.replace("Z", "+00:00")).timestamp())
    except ValueError:
        return 0


def _clean_body(text: str | None) -> str:
    """Strip Claude's injected <tags> but keep the message body intact."""
    if not text:
        return ""
    return _TAG_RE.sub("", text).strip()


def _read_session(path: Path) -> ProjectSession:
    title: str | None = None
    first_user: str | None = None
    messages = 0

    with path.open(encoding="utf-8", errors="replace") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                continue
            t = obj.get("type")
            if t == "ai-title":
                # Latest title wins (entries appended over the session's life).
                title = obj.get("aiTitle") or title
            elif t == "user":
                messages += 1
                if first_user is None:
                    first_user = _extract_text(obj)
            elif t == "assistant":
                messages += 1

    return ProjectSession(
        id=path.stem,
        title=title or _clean(first_user) or "(untitled session)",
        modified=int(path.stat().st_mtime),
        messages=messages,
    )


def _extract_text(entry: dict) -> str | None:
    msg = entry.get("message")
    if not isinstance(msg, dict):
        return None
    content = msg.get("content")
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        for block in content:
            if isinstance(block, dict) and isinstance(block.get("text"), str):
                return block["text"]
    return None


def _clean(text: str | None) -> str | None:
    """Strip injected tags/whitespace from a user message for use as a title."""
    if not text:
        return None
    cleaned = _TAG_RE.sub(" ", text).strip()
    cleaned = re.sub(r"\s+", " ", cleaned)
    return cleaned[:80] or None
