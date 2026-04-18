"""Pairing codes + per-device tokens.

On first run we mint a 6-digit pairing code, print it to stdout, and
hold it in memory. POST /pair with the code returns a long-lived
device token. Code rotates if unused for 10 minutes.

Tokens persist to {state_dir}/devices.json so the daemon can restart
without re-pairing every device.

TODO(claude-code): implement.
"""
from __future__ import annotations

import json
import logging
import secrets
from dataclasses import asdict, dataclass, field
from pathlib import Path
from time import time

logger = logging.getLogger(__name__)

CODE_TTL_SEC = 600  # 10 min


@dataclass
class Device:
    token: str
    name: str
    paired_at: float
    last_seen: float = field(default_factory=time)


class AuthStore:
    def __init__(self, store_path: Path) -> None:
        self.store_path = store_path
        self.devices: dict[str, Device] = {}  # token -> device
        self._current_code: str | None = None
        self._code_issued_at: float = 0.0
        self._load()

    # --- pairing ---

    def current_code(self) -> str:
        """Return the active pairing code, rotating if expired."""
        if self._current_code is None or time() - self._code_issued_at > CODE_TTL_SEC:
            self._current_code = f"{secrets.randbelow(1_000_000):06d}"
            self._code_issued_at = time()
            logger.info("new pairing code minted")
        return self._current_code

    def pair(self, code: str, device_name: str) -> str:
        """Consume `code` and issue a device token. Raises ValueError on bad code."""
        if self._current_code is None or code != self._current_code:
            raise ValueError("bad code")
        if time() - self._code_issued_at > CODE_TTL_SEC:
            self._current_code = None
            raise ValueError("code expired")

        token = "dev_" + secrets.token_urlsafe(24)
        self.devices[token] = Device(
            token=token, name=device_name, paired_at=time()
        )
        # Invalidate the code after one successful pair.
        self._current_code = None
        self._save()
        return token

    # --- token verification ---

    def verify(self, token: str) -> Device | None:
        d = self.devices.get(token)
        if d is not None:
            d.last_seen = time()
        return d

    # --- persistence ---

    def _load(self) -> None:
        if not self.store_path.exists():
            return
        try:
            data = json.loads(self.store_path.read_text())
            self.devices = {
                t: Device(**d) for t, d in data.get("devices", {}).items()
            }
        except Exception:
            logger.exception("failed to load %s — starting empty", self.store_path)

    def _save(self) -> None:
        data = {"devices": {t: asdict(d) for t, d in self.devices.items()}}
        self.store_path.parent.mkdir(parents=True, exist_ok=True)
        # Atomic write.
        tmp = self.store_path.with_suffix(".json.tmp")
        tmp.write_text(json.dumps(data, indent=2))
        tmp.replace(self.store_path)
