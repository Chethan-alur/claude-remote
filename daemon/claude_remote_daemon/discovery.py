"""mDNS / Bonjour advertiser.

Android's NsdManager browses for "_claudecode._tcp." and finds us, so the
user does not have to type an IP. We advertise the WebSocket port plus a TXT
record with the daemon version.

Best-effort: if the multicast socket cannot be opened (locked-down network,
sandbox), we log a warning and idle rather than crashing the daemon — manual
IP entry in the app is the fallback.
"""
from __future__ import annotations

import asyncio
import logging
import socket

logger = logging.getLogger(__name__)

SERVICE_TYPE = "_claudecode._tcp.local."


def _local_ip() -> str:
    """Best guess at the LAN address the phone will reach us on.

    Uses the "connect a UDP socket and read its local address" trick — no
    packets are actually sent. Falls back to loopback if that fails.
    """
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def _build_service_info(instance_name: str, port: int, ip: str | None = None):
    """Construct the zeroconf ServiceInfo we advertise. Factored out for tests."""
    from zeroconf import ServiceInfo

    ip = ip or _local_ip()
    hostname = socket.gethostname()
    return ServiceInfo(
        type_=SERVICE_TYPE,
        name=f"{instance_name}.{SERVICE_TYPE}",
        addresses=[socket.inet_aton(ip)],
        port=port,
        properties={"version": "0.1.0", "host": hostname},
        server=f"{hostname}.local.",
    )


class Discovery:
    def __init__(self, instance_name: str, port: int) -> None:
        self.instance_name = instance_name
        self.port = port

    async def advertise(self) -> None:
        try:
            from zeroconf.asyncio import AsyncZeroconf
        except Exception as exc:  # pragma: no cover - import guard
            logger.warning("zeroconf unavailable, mDNS disabled: %s", exc)
            await asyncio.Future()
            return

        try:
            azc = AsyncZeroconf()
        except OSError as exc:
            logger.warning("mDNS disabled (cannot open multicast socket): %s", exc)
            await asyncio.Future()
            return

        info = _build_service_info(self.instance_name, self.port)
        try:
            await azc.async_register_service(info)
            logger.info(
                "advertising %s on %s:%d via mDNS",
                info.name,
                socket.inet_ntoa(info.addresses[0]),
                self.port,
            )
            await asyncio.Future()  # serve until cancelled
        except asyncio.CancelledError:
            pass
        except Exception as exc:
            logger.warning("mDNS advertise failed: %s", exc)
            await asyncio.Future()
        finally:
            try:
                await azc.async_unregister_service(info)
            except Exception:
                pass
            await azc.async_close()
