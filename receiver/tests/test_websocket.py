"""Real WebSocket checks; run after installing dependencies outside the sandbox."""

import asyncio
import json
import socket

import pytest

pytest.importorskip("websockets", minversion="15")
from websockets.asyncio.client import connect  # noqa: E402
from websockets.exceptions import ConnectionClosed, InvalidStatus  # noqa: E402

from lanpad.receiver import Receiver  # noqa: E402
from lanpad.server import serve  # noqa: E402

PIN = "01234"


@pytest.fixture
def port():
    try:
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            return listener.getsockname()[1]
    except PermissionError:
        pytest.skip("This environment does not permit network sockets")


async def eventually(predicate):
    async with asyncio.timeout(2):
        while not predicate():
            await asyncio.sleep(0.01)


async def start(receiver, port, stop):
    task = asyncio.create_task(serve(receiver, "127.0.0.1", port, stop))
    async with asyncio.timeout(2):
        while True:
            if task.done():
                task.result()
            try:
                socket = await connect(f"ws://127.0.0.1:{port}", proxy=None)
                await socket.close()
                return task
            except ConnectionRefusedError:
                await asyncio.sleep(0.01)


async def pair(socket, pin=PIN):
    await socket.send(json.dumps({"type": "pair", "version": 4, "pin": pin}))
    assert json.loads(await socket.recv()) == {"type": "paired", "version": 4}


def test_real_pairing_input_and_disconnect(adapter, port):
    async def scenario():
        receiver, stop = Receiver(adapter, PIN), asyncio.Event()
        task = await start(receiver, port, stop)
        try:
            async with connect(f"ws://127.0.0.1:{port}", proxy=None) as socket:
                await pair(socket)
                for message in [
                    {"type": "move", "dx": 9, "dy": -2},
                    {"type": "click", "button": "right"},
                    {"type": "scroll", "dx": 0, "dy": 1},
                    {"type": "key", "key": "enter", "modifiers": ["super"]},
                    {"type": "text", "text": "Caffè 😀"},
                    {"type": "button", "button": "left", "down": True},
                ]:
                    await socket.send(json.dumps(message))
                await eventually(lambda: "left" in receiver.mouse.held)
            await eventually(lambda: receiver.active is None)
            assert adapter.events == [
                ("move", 9, -2), ("button", "right", True), ("button", "right", False),
                ("scroll", 0, 1), ("key", "super", True), ("key", "enter", True),
                ("key", "enter", False), ("key", "super", False), ("text", "Caffè 😀"),
                ("button", "left", True), ("button", "left", False),
            ]
        finally:
            stop.set()
            await task
    asyncio.run(scenario())


def test_real_wrong_credential_and_browser_origin_are_rejected(adapter, port):
    async def scenario():
        receiver, stop = Receiver(adapter, PIN), asyncio.Event()
        task = await start(receiver, port, stop)
        try:
            async with connect(f"ws://127.0.0.1:{port}", proxy=None) as socket:
                with pytest.raises(ConnectionClosed) as failure:
                    await pair(socket, "99999")
                assert failure.value.rcvd.code == 4001
            with pytest.raises(InvalidStatus) as failure:
                async with connect(
                    f"ws://127.0.0.1:{port}", origin="https://example.com", proxy=None
                ):
                    pass
            assert failure.value.response.status_code == 403
            assert not adapter.events
        finally:
            stop.set()
            await task
    asyncio.run(scenario())


def test_real_shutdown_releases_drag(adapter, port):
    async def scenario():
        receiver, stop = Receiver(adapter, PIN), asyncio.Event()
        task = await start(receiver, port, stop)
        try:
            async with connect(f"ws://127.0.0.1:{port}", proxy=None) as socket:
                await pair(socket)
                await socket.send('{"type":"button","button":"left","down":true}')
                await eventually(lambda: "left" in receiver.mouse.held)
                stop.set()
                await task
                assert socket.close_code == 1001
                assert not receiver.mouse.held
                assert adapter.events[-1] == ("button", "left", False)
        finally:
            stop.set()
            await task
    asyncio.run(scenario())
