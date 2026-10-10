"""A terminal controller to test pairing and input before the Android UI exists."""

import argparse
import asyncio
import getpass
import json
import sys

from .protocol import VERSION, parse_command


async def control(url: str, pin: str) -> None:
    """Pair with the receiver, send terminal input, and keep the idle session alive."""
    from websockets.asyncio.client import connect

    async with connect(url, compression=None, max_size=1024, proxy=None) as socket:
        # Complete pairing before starting tasks that can send mouse commands.
        await socket.send(json.dumps({"type": "pair", "version": VERSION, "pin": pin}))
        async with asyncio.timeout(5):
            response = json.loads(await socket.recv())
        if response != {"type": "paired", "version": VERSION}:
            raise RuntimeError("Receiver did not confirm pairing")
        print("Paired. Enter protocol JSON commands; Ctrl+D or Ctrl+C to disconnect.")

        async def heartbeat() -> None:
            """Send a keepalive every second while the user is not entering commands."""
            while True:
                await asyncio.sleep(1)
                await socket.send('{"type":"heartbeat"}')

        async def watch_close() -> None:
            """Notice receiver disconnection and report an abnormal close reason."""
            await socket.wait_closed()
            if socket.close_code not in (1000, 1001):
                raise RuntimeError(f"Receiver closed: {socket.close_code} {socket.close_reason}")

        async def send_input() -> None:
            """Read terminal lines asynchronously, validate them, and send them in order."""
            # Pipe-driven async stdin avoids a blocking input thread on shutdown.
            reader = asyncio.StreamReader()
            protocol = asyncio.StreamReaderProtocol(reader)
            transport, _ = await asyncio.get_running_loop().connect_read_pipe(
                lambda: protocol, sys.stdin
            )
            try:
                while line := await reader.readline():
                    # readline returns bytes; an empty result means end of input.
                    raw = line.decode().strip()
                    if not raw:
                        continue
                    parse_command(raw)
                    await socket.send(raw)
                # Ask for immediate release on EOF before leaving the connection.
                await socket.send('{"type":"release_all"}')
            finally:
                transport.close()

        # Run input, keepalives, and disconnect monitoring concurrently on one loop.
        tasks = [asyncio.create_task(coro()) for coro in (heartbeat, watch_close, send_input)]
        try:
            done, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
            for task in done:
                # Propagate any error from the task that ended the session.
                task.result()
        finally:
            # Stop the other tasks and wait for their cleanup before closing the socket.
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)


def main() -> None:
    """Read the receiver address and hidden PIN, then run the async client."""
    parser = argparse.ArgumentParser(description="LanPad paired test controller")
    parser.add_argument("url", nargs="?", default="ws://127.0.0.1:8765")
    args = parser.parse_args()
    try:
        pin = getpass.getpass("Pairing PIN: ")
        asyncio.run(control(args.url, pin))
    except KeyboardInterrupt:
        pass
    except Exception as exc:
        parser.exit(1, f"LanPad client: {exc}\n")
