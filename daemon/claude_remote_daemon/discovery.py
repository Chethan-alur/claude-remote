"""mDNS / Bonjour advertiser.

Android's NsdManager browses for "_claudecode._tcp." and finds us.

TODO(claude-code): implement using `zeroconf`. Sketch:

    from zeroconf.asyncio import AsyncZeroconf
    from zeroconf import ServiceInfo
    import socket

    info = ServiceInfo(
        type_="_claudecode._tcp.local.",
        name=f"{instance_name}._claudecode._tcp.local.",
        addresses=[socket.inet_aton(local_ip)],
        port=port,
        properties={"version": "0.1.0"},
        server=f"{socket.gethostname()}.local.",
    )
    zc = AsyncZeroconf()
    await zc.async_register_service(info)
    try:
        await asyncio.Future()  # serve forever
    finally:
        await zc.async_unregister_service(info)
        await zc.async_close()

Picking `local_ip`: iterate interfaces, prefer the one on the LAN
subnet that the phone will use. `psutil` makes this easier but adds a
dep — for the POC, binding to all interfaces and letting zeroconf pick
is fine.
"""
from __future__ import annotations

import asyncio
import logging

logger = logging.getLogger(__name__)


class Discovery:
    def __init__(self, instance_name: str, port: int) -> None:
        self.instance_name = instance_name
        self.port = port

    async def advertise(self) -> None:
        # TODO(claude-code): real implementation
        logger.info("mDNS advertise placeholder for %s on port %d",
                    self.instance_name, self.port)
        await asyncio.Future()  # block forever
