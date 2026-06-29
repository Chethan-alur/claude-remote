"""Tests for reading Claude Code's on-disk session history."""
from __future__ import annotations

import json

import pytest

from claude_remote_daemon.history import (
    delete_project_session,
    encode_project_dir,
    list_project_sessions,
)


def test_encode_project_dir():
    assert encode_project_dir("/home/me/code/webapp") == "-home-me-code-webapp"
    # dots and dashes both collapse to '-', matching Claude's scheme
    assert encode_project_dir("/home/me/.claude/hooks") == "-home-me--claude-hooks"


def _write_transcript(path, *, ai_title=None, first_user=None, turns=0):
    lines = []
    if first_user is not None:
        lines.append({"type": "user", "message": {"content": first_user}})
    for _ in range(turns):
        lines.append({"type": "assistant", "message": {"content": "ok"}})
    if ai_title is not None:
        lines.append({"type": "ai-title", "aiTitle": ai_title})
    path.write_text("\n".join(json.dumps(o) for o in lines), encoding="utf-8")


def test_lists_sessions_with_titles_newest_first(tmp_path, monkeypatch):
    cwd = "/home/me/proj"
    proj = tmp_path / encode_project_dir(cwd)
    proj.mkdir(parents=True)

    older = proj / "aaaaaaaa-0000.jsonl"
    newer = proj / "bbbbbbbb-1111.jsonl"
    _write_transcript(older, ai_title="Older work", first_user="hi", turns=2)
    _write_transcript(newer, first_user="<command>x</command> add tests", turns=4)
    # Make `newer` genuinely newer.
    import os
    os.utime(older, (1_000, 1_000))
    os.utime(newer, (2_000, 2_000))

    found = list_project_sessions(cwd, projects_root=tmp_path)
    assert [s.id for s in found] == ["bbbbbbbb-1111", "aaaaaaaa-0000"]

    # ai-title wins; otherwise the first user message (tags stripped) is the title.
    by_id = {s.id: s for s in found}
    assert by_id["aaaaaaaa-0000"].title == "Older work"
    # tags are stripped and whitespace collapsed -> "x add tests"
    assert by_id["bbbbbbbb-1111"].title == "x add tests"
    assert by_id["aaaaaaaa-0000"].messages == 3  # 1 user + 2 assistant


def test_unknown_project_returns_empty(tmp_path):
    assert list_project_sessions("/no/such/project", projects_root=tmp_path) == []


def test_delete_removes_transcript(tmp_path):
    cwd = "/home/me/proj"
    proj = tmp_path / encode_project_dir(cwd)
    proj.mkdir(parents=True)
    target = proj / "aaaaaaaa-0000.jsonl"
    _write_transcript(target, first_user="hi", turns=1)
    keep = proj / "bbbbbbbb-1111.jsonl"
    _write_transcript(keep, first_user="hey", turns=1)

    assert delete_project_session(cwd, "aaaaaaaa-0000", projects_root=tmp_path) is True
    assert not target.exists()
    assert keep.exists()
    # Gone from the listing.
    assert [s.id for s in list_project_sessions(cwd, projects_root=tmp_path)] == ["bbbbbbbb-1111"]


def test_delete_missing_returns_false(tmp_path):
    cwd = "/home/me/proj"
    (tmp_path / encode_project_dir(cwd)).mkdir(parents=True)
    assert delete_project_session(cwd, "nope", projects_root=tmp_path) is False


@pytest.mark.parametrize("bad_id", ["../../etc/passwd", "a/b", "..", ""])
def test_delete_rejects_path_traversal(tmp_path, bad_id):
    with pytest.raises(ValueError):
        delete_project_session("/home/me/proj", bad_id, projects_root=tmp_path)
