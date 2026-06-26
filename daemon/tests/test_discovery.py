"""Discovery helpers (no multicast — we only build/inspect the ServiceInfo)."""
from __future__ import annotations

import socket

from claude_remote_daemon.discovery import SERVICE_TYPE, _build_service_info, _local_ip


def test_local_ip_returns_an_address():
    ip = _local_ip()
    assert isinstance(ip, str)
    socket.inet_aton(ip)  # raises if not a valid IPv4 dotted-quad


def test_service_info_fields():
    info = _build_service_info("claude-remote", 8765, ip="192.168.1.5")
    assert info.type == SERVICE_TYPE
    assert info.name == f"claude-remote.{SERVICE_TYPE}"
    assert info.port == 8765
    assert socket.inet_ntoa(info.addresses[0]) == "192.168.1.5"
