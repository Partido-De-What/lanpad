import asyncio
import sys
from types import SimpleNamespace

import pytest

from lanpad import discovery


def address(name, host, *, active=True):
    return {"ifname": name, "flags": ["UP"] if active else [],
            "operstate": "UP" if active else "DOWN", "addr_info": [{"local": host}]}


def test_auto_host_prefers_default_lan_route_and_ignores_vpn(monkeypatch):
    interfaces = [address("wg0", "10.0.0.1"), address("eth0", "192.168.1.2"),
                  address("wlan0", "192.168.2.3")]
    routes = [{"dev": "wg0", "metric": 1}, {"dev": "eth0", "metric": 100},
              {"dev": "wlan0", "metric": 50}]
    monkeypatch.setattr(
        discovery, "_ip", lambda *args: interfaces if args[0] == "address" else routes,
    )
    assert discovery.choose_host() == "192.168.2.3"
    assert discovery.choose_host("eth0") == "192.168.1.2"


def test_auto_host_works_on_a_subnet_without_a_default_route(monkeypatch):
    monkeypatch.setattr(discovery, "_ip", lambda *args:
                        [address("wlan0", "10.1.2.3")] if args[0] == "address" else [])
    assert discovery.choose_host() == "10.1.2.3"


@pytest.mark.parametrize("interfaces", [[], [address("eth0", "8.8.8.8")],
                                          [address("eth0", "192.168.1.2", active=False)]])
def test_no_usable_lan_address_requires_an_override(monkeypatch, interfaces):
    monkeypatch.setattr(discovery, "_ip", lambda *args: interfaces if args[0] == "address" else [])
    with pytest.raises(RuntimeError, match="No active private LAN"):
        discovery.choose_host()


def test_announcement_uses_bound_address_port_version_and_always_closes(monkeypatch):
    events = []

    class Zeroconf:
        def __init__(self, **options):
            events.append(options)

        async def async_register_service(self, info, **options):
            events.append(info)

        async def async_close(self):
            events.append("closed")

    monkeypatch.setitem(sys.modules, "zeroconf", SimpleNamespace(
        IPVersion=SimpleNamespace(V4Only="ipv4"),
        ServiceInfo=lambda kind, name, **values: {"type": kind, "name": name, **values},
    ))
    monkeypatch.setitem(
        sys.modules, "zeroconf.asyncio", SimpleNamespace(AsyncZeroconf=Zeroconf),
    )

    async def scenario():
        with pytest.raises(RuntimeError, match="stop"):
            async with discovery.advertise("192.168.1.2", 9876, True):
                raise RuntimeError("stop")
    asyncio.run(scenario())
    assert events[0] == {"interfaces": ["192.168.1.2"], "ip_version": "ipv4"}
    assert events[1]["port"] == 9876
    assert events[1]["properties"] == {"version": "2"}
    assert events[-1] == "closed"


def test_loopback_skips_discovery_without_loading_zeroconf():
    async def scenario():
        async with discovery.advertise("127.0.0.1", 8765, True):
            pass
    asyncio.run(scenario())
