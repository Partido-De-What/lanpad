"""Terminal receiver and local mouse test commands."""

import argparse
import asyncio
import ipaddress
import logging
import secrets
import sys

from .adapters import LoggingAdapter, X11Adapter
from .discovery import choose_host, is_lan_address
from .mouse import MouseSession
from .protocol import parse_command
from .receiver import Receiver
from .server import run


def bind_address(value: str) -> str:
    """Validate a loopback/private IPv4 bind address for argparse."""
    try:
        address = ipaddress.IPv4Address(value)
    except ipaddress.AddressValueError as exc:
        raise argparse.ArgumentTypeError("Use an explicit local IPv4 address") from exc
    if not address.is_loopback and not is_lan_address(value):
        raise argparse.ArgumentTypeError("Bind to loopback or a private LAN address")
    return str(address)


def port_number(value: str) -> int:
    """Convert a command-line port to an integer within the valid TCP range."""
    try:
        port = int(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("Port must be an integer") from exc
    if not 1 <= port <= 65535:
        raise argparse.ArgumentTypeError("Port must be between 1 and 65535")
    return port


def local(adapter) -> None:
    """Apply JSON commands from terminal input directly, without a network connection."""
    session = MouseSession(adapter)
    print("Local mouse test. Enter one protocol JSON command per line; Ctrl+D to stop.")
    try:
        for line in sys.stdin:
            if not line.strip():
                continue
            session.apply(parse_command(line))
    finally:
        # EOF, Ctrl+C, and invalid commands all leave through this cleanup path.
        try:
            session.release_all()
        finally:
            adapter.close()


def main() -> None:
    """Parse options, choose the input backend, and start local mode or the server."""
    parser = argparse.ArgumentParser(description="LanPad Linux/X11 mouse receiver")
    parser.add_argument("command", choices=("serve", "local"), nargs="?", default="serve")
    network = parser.add_mutually_exclusive_group()
    network.add_argument(
        "--host", type=bind_address, help="Override automatic LAN address selection",
    )
    network.add_argument("--interface", help="Use a specific LAN interface, for example wlan0")
    parser.add_argument("--no-discovery", action="store_true", help="Disable mDNS advertisement")
    parser.add_argument("--port", type=port_number, default=8765)
    parser.add_argument("--dry-run", action="store_true", help="Log input without moving the mouse")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    try:
        if args.command == "serve":
            # Check transport availability before opening an X11 connection.
            import websockets.asyncio.server  # noqa: F401
            host = args.host or choose_host(args.interface)
        adapter = LoggingAdapter() if args.dry_run else X11Adapter()
        if args.command == "local":
            local(adapter)
        else:
            # Keep the PIN as text so values such as 00042 retain all five digits.
            receiver = Receiver(adapter, f"{secrets.randbelow(100_000):05d}")
            # asyncio.run owns the event loop used by the receiver's async functions.
            asyncio.run(run(receiver, host, args.port, advertise_service=not args.no_discovery))
    except KeyboardInterrupt:
        pass
    except Exception as exc:
        parser.exit(1, f"LanPad: {exc}\n")
