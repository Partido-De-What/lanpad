import argparse
import io

import pytest

from lanpad import cli
from lanpad.cli import bind_address, local, port_number


@pytest.mark.parametrize("address", ["127.0.0.1", "10.0.0.2", "172.16.0.2", "192.168.1.2"])
def test_local_bind_addresses(address):
    assert bind_address(address) == address


@pytest.mark.parametrize("address", ["0.0.0.0", "8.8.8.8", "localhost", "::1", "172.32.0.1"])
def test_rejects_wildcard_and_public_bind_addresses(address):
    with pytest.raises(argparse.ArgumentTypeError):
        bind_address(address)


@pytest.mark.parametrize("value", ["0", "65536", "abc"])
def test_rejects_invalid_ports(value):
    with pytest.raises(argparse.ArgumentTypeError):
        port_number(value)


def test_local_test_releases_on_eof(adapter, monkeypatch):
    monkeypatch.setattr("sys.stdin", io.StringIO('{"type":"button","button":"left","down":true}\n'))
    local(adapter)
    assert adapter.events == [("button", "left", True), ("button", "left", False)]
    assert adapter.closed


@pytest.mark.parametrize(("arguments", "expected", "interfaces"), [
    ([], "192.168.1.2", [None]),
    (["--interface", "eth0"], "192.168.1.2", ["eth0"]),
    (["--host", "127.0.0.1"], "127.0.0.1", []),
])
def test_server_selects_host_and_printable_five_digit_pin(adapter, monkeypatch, arguments,
                                                        expected, interfaces):
    selected = []
    calls = []

    def choose(interface):
        selected.append(interface)
        return "192.168.1.2"

    async def serve(receiver, host, port, **options):
        calls.append((receiver.pin, host, port, options))

    monkeypatch.setattr("sys.argv", ["lanpad", "--dry-run", *arguments])
    monkeypatch.setattr(cli, "choose_host", choose)
    monkeypatch.setattr(cli, "LoggingAdapter", lambda: adapter)
    monkeypatch.setattr(cli.secrets, "randbelow", lambda maximum: 42)
    monkeypatch.setattr(cli, "run", serve)
    cli.main()
    assert selected == interfaces
    assert calls == [("00042", expected, 8765, {"advertise_service": True})]
