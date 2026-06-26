"""AuthStore pairing + token persistence (no network)."""
from __future__ import annotations

import pytest

from claude_remote_daemon.auth import AuthStore


def test_pair_with_correct_code_issues_token(tmp_path):
    store = AuthStore(tmp_path / "devices.json")
    code = store.current_code()
    token = store.pair(code, "Pixel 8")
    assert token.startswith("dev_")
    device = store.verify(token)
    assert device is not None
    assert device.name == "Pixel 8"


def test_wrong_code_is_rejected(tmp_path):
    store = AuthStore(tmp_path / "devices.json")
    store.current_code()
    with pytest.raises(ValueError):
        store.pair("000000", "attacker")


def test_code_is_single_use(tmp_path):
    store = AuthStore(tmp_path / "devices.json")
    code = store.current_code()
    store.pair(code, "first")
    with pytest.raises(ValueError):
        store.pair(code, "second")  # code consumed by the first pair


def test_unknown_token_does_not_verify(tmp_path):
    store = AuthStore(tmp_path / "devices.json")
    assert store.verify("dev_nope") is None


def test_tokens_persist_across_restart(tmp_path):
    path = tmp_path / "devices.json"
    store = AuthStore(path)
    token = store.pair(store.current_code(), "Pixel 8")

    reloaded = AuthStore(path)
    assert reloaded.verify(token) is not None
