import asyncio
import json
import subprocess
import sys
from types import SimpleNamespace

import pytest

from lanpad import discovery


@pytest.fixture(autouse=True)
def linux_platform(monkeypatch):
    monkeypatch.setattr(discovery.sys, "platform", "linux")


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


def test_windows_uses_aliases_route_metrics_and_ignores_virtual_adapters(monkeypatch):
    monkeypatch.setattr(discovery.sys, "platform", "win32")
    virtual = {**address("vEthernet (Default Switch)", "172.20.0.1"), "virtual": True}
    interfaces = [virtual, address("Local Area Connection", "192.168.1.2"),
                  address("Wi-Fi", "192.168.2.3")]
    routes = [{"dev": virtual["ifname"], "metric": 1},
              {"dev": "Local Area Connection", "metric": 60},
              {"dev": "Wi-Fi", "metric": 50}]
    monkeypatch.setattr(discovery, "_windows_network", lambda: (interfaces, routes))
    assert discovery.choose_host() == "192.168.2.3"
    assert discovery.choose_host("Local Area Connection") == "192.168.1.2"
    assert discovery.choose_host(virtual["ifname"]) == "172.20.0.1"
    with pytest.raises(RuntimeError, match="No active private LAN"):
        discovery.choose_host("missing")


def test_windows_without_default_route_uses_active_private_interface(monkeypatch):
    monkeypatch.setattr(discovery.sys, "platform", "win32")
    monkeypatch.setattr(discovery, "_windows_network", lambda: (
        [address("Ethernet", "192.168.1.3", active=False),
         address("Wi-Fi", "10.0.0.2")], [],
    ))
    assert discovery.choose_host() == "10.0.0.2"


def test_windows_powershell_decodes_utf8_arrays_without_running_a_shell(monkeypatch):
    calls = []
    network = {"addresses": [address("Réseau local", "192.168.1.2")], "routes": []}

    def run(command, **kwargs):
        calls.append((command, kwargs))
        return SimpleNamespace(stdout="\ufeff" + json.dumps(network, ensure_ascii=False))

    monkeypatch.setattr(discovery.subprocess, "run", run)
    assert discovery._windows_network() == (network["addresses"], [])
    command, options = calls[0]
    assert command[:5] == [
        "powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command",
    ]
    assert options == {"capture_output": True, "encoding": "utf-8", "check": True,
                       "timeout": 10}


@pytest.mark.parametrize("output", ["not json", "{}", '{"addresses":{},"routes":[]}'])
def test_invalid_windows_metadata_explains_manual_override(monkeypatch, output):
    monkeypatch.setattr(discovery.subprocess, "run", lambda *args, **kwargs:
                        SimpleNamespace(stdout=output))
    with pytest.raises(RuntimeError, match="PowerShell or use --host"):
        discovery._windows_network()


@pytest.mark.parametrize("error", [FileNotFoundError(), subprocess.TimeoutExpired("ps", 10)])
def test_windows_powershell_failure_explains_manual_override(monkeypatch, error):
    def fail(*args, **kwargs):
        raise error

    monkeypatch.setattr(discovery.subprocess, "run", fail)
    with pytest.raises(RuntimeError, match="PowerShell or use --host"):
        discovery._windows_network()
