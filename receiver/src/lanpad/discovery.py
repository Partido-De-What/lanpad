"""Choose a LAN interface and advertise the receiver using DNS-SD/mDNS."""

import ipaddress
import json
import logging
import re
import socket
import subprocess
from contextlib import asynccontextmanager

from .protocol import VERSION

SERVICE_TYPE = "_lanpad._tcp.local."
LAN_NETWORKS = tuple(map(ipaddress.ip_network, ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")))
VIRTUAL_PREFIXES = ("lo", "tun", "tap", "wg", "docker", "veth", "br-", "virbr", "tailscale", "zt")
log = logging.getLogger(__name__)


def is_lan_address(value: str) -> bool:
    """Accept RFC 1918 IPv4 addresses, excluding public, loopback, and link-local IPs."""
    try:
        return any(ipaddress.IPv4Address(value) in network for network in LAN_NETWORKS)
    except ipaddress.AddressValueError:
        return False


def _ip(*arguments: str) -> list[dict]:
    """Read Linux interface metadata without contacting an external host."""
    try:
        result = subprocess.run(
            ["ip", "-j", "-4", *arguments], capture_output=True, text=True, check=True, timeout=3,
        )
        return json.loads(result.stdout)
    except (OSError, subprocess.SubprocessError, ValueError) as error:
        raise RuntimeError(
            "Cannot inspect network interfaces. Install iproute2 or use --host.",
        ) from error


def choose_host(interface: str | None = None) -> str:
    """Prefer the active default-route LAN interface; allow an explicit interface override."""
    addresses = _ip("address", "show", "scope", "global")
    candidates = {}
    for item in addresses:
        name = item["ifname"]
        if interface is not None and name != interface:
            continue
        if interface is None and name.startswith(VIRTUAL_PREFIXES):
            continue
        if "UP" not in item.get("flags", []) or item.get("operstate") == "DOWN":
            continue
        for address in item.get("addr_info", []):
            host = address.get("local", "")
            if is_lan_address(host):
                candidates.setdefault(name, host)
    if interface is None:
        routes = sorted(_ip("route", "show", "default"), key=lambda route: route.get("metric", 0))
        for route in routes:
            if route.get("dev") in candidates:
                return candidates[route["dev"]]
    if candidates:
        return next(iter(candidates.values()))
    raise RuntimeError(
        "No active private LAN address found. Connect to the LAN or use --interface/--host.",
    )


@asynccontextmanager
async def advertise(host: str, port: int, enabled: bool):
    """Publish the listening interface and withdraw it on shutdown; never publish the PIN."""
    if not enabled or not is_lan_address(host):
        yield
        return
    try:
        from zeroconf import IPVersion, ServiceInfo
        from zeroconf.asyncio import AsyncZeroconf
    except ImportError as error:
        raise RuntimeError(
            "Install receiver dependencies for discovery, or use --no-discovery.",
        ) from error
    label = re.sub(r"[^A-Za-z0-9_-]", "-", socket.gethostname().split(".")[0])[:40] or "computer"
    info = ServiceInfo(
        SERVICE_TYPE, f"LanPad {label}.{SERVICE_TYPE}",
        addresses=[socket.inet_aton(host)], port=port,
        properties={"version": str(VERSION)}, server=f"{label}.local.",
    )
    zeroconf = None
    try:
        try:
            zeroconf = AsyncZeroconf(interfaces=[host], ip_version=IPVersion.V4Only)
            await zeroconf.async_register_service(info, allow_name_change=True)
        except OSError as error:
            log.warning("Discovery unavailable; connect manually to %s:%s: %s", host, port, error)
        yield
    finally:
        if zeroconf is not None:
            await zeroconf.async_close()
