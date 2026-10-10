"""WebSocket transport and process shutdown."""

import asyncio
import signal

from .discovery import advertise
from .protocol import MAX_MESSAGE_BYTES
from .receiver import Receiver


async def serve(
    receiver: Receiver, host: str, port: int, stop: asyncio.Event,
    *, advertise_service: bool = False,
) -> None:
    """Listen for WebSocket clients until stop is set, then close their connections."""
    from websockets.asyncio.server import serve as websocket_serve
    from websockets.exceptions import ConnectionClosed

    class WebSocketConnection:
        """Adapt the WebSocket library to the receiver's small Connection interface."""

        def __init__(self, socket) -> None:
            """Keep the library connection that carries this client's messages."""
            self.socket = socket

        async def recv(self) -> str | bytes | None:
            """Receive a frame, translating library disconnection exceptions into None."""
            try:
                return await self.socket.recv()
            except ConnectionClosed:
                return None

        async def send(self, message: str) -> None:
            """Forward a receiver response through the WebSocket."""
            await self.socket.send(message)

        async def close(self, code: int, reason: str) -> None:
            """Perform the WebSocket closing handshake with the supplied reason."""
            await self.socket.close(code=code, reason=reason)

    async def handle(socket) -> None:
        """Give each newly connected WebSocket its own receiver session handler."""
        await receiver.handle(WebSocketConnection(socket))

    # The async context manager closes the listener and client connections on exit.
    # Queue and message limits keep buffered input small; only clients without a
    # browser Origin header are accepted.
    async with websocket_serve(
        handle,
        host,
        port,
        origins=[None],
        compression=None,
        max_size=MAX_MESSAGE_BYTES,
        max_queue=8,
        open_timeout=5,
        ping_interval=5,
        ping_timeout=5,
        close_timeout=1,
        server_header=None,
    ) as server:
        listening_port = server.sockets[0].getsockname()[1]
        async with advertise(host, listening_port, advertise_service):
            print(f"LanPad listening on ws://{host}:{listening_port}", flush=True)
            print(f"Pairing PIN: {receiver.pin}", flush=True)
            print("Press Ctrl+C to stop and release held buttons.", flush=True)
            await stop.wait()
            receiver.stop()


async def run(receiver: Receiver, host: str, port: int, *, advertise_service: bool = True) -> None:
    """Connect process stop signals to graceful server shutdown and backend cleanup."""
    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for signum in (signal.SIGINT, signal.SIGTERM):
        # Ctrl+C or termination wakes serve's stop.wait() instead of skipping cleanup.
        loop.add_signal_handler(signum, stop.set)
    try:
        await serve(receiver, host, port, stop, advertise_service=advertise_service)
    finally:
        # Nested finally blocks close the adapter even if releasing buttons fails.
        try:
            receiver.stop()
        finally:
            receiver.mouse.adapter.close()
            for signum in (signal.SIGINT, signal.SIGTERM):
                loop.remove_signal_handler(signum)
