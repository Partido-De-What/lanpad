"""Shutdown works without POSIX event-loop signal support, including cleanup failures."""

import asyncio
import signal

import pytest

from lanpad import server
from lanpad.receiver import Receiver


@pytest.fixture
def handlers(monkeypatch):
    original = {signum: signal.getsignal(signum) for signum in
                (signal.SIGINT, signal.SIGTERM)}
    if hasattr(signal, "SIGBREAK"):
        original[signal.SIGBREAK] = signal.getsignal(signal.SIGBREAK)
    current = original.copy()
    monkeypatch.setattr(server.signal, "getsignal", current.__getitem__)
    monkeypatch.setattr(server.signal, "signal", lambda number, handler:
                        current.__setitem__(number, handler))
    return original, current


def test_ctrl_c_stops_and_releases_buttons_then_restores_handlers(adapter, handlers, monkeypatch):
    original, current = handlers
    receiver = Receiver(adapter, "01234")
    receiver.mouse._button("left", True)

    async def serve(receiver, host, port, stop, **kwargs):
        current[signal.SIGINT](signal.SIGINT, None)
        await asyncio.wait_for(stop.wait(), timeout=1)

    monkeypatch.setattr(server, "serve", serve)
    asyncio.run(server.run(receiver, "127.0.0.1", 8765))
    assert receiver.stopping and not receiver.mouse.held
    assert adapter.events == [("button", "left", True), ("button", "left", False)]
    assert adapter.closed
    assert current == original


def test_startup_failure_cleans_up_and_restores_handlers(adapter, handlers, monkeypatch):
    original, current = handlers
    receiver = Receiver(adapter, "01234")
    receiver.mouse._button("right", True)

    async def fail(*args, **kwargs):
        raise OSError("port occupied")

    monkeypatch.setattr(server, "serve", fail)
    with pytest.raises(OSError, match="port occupied"):
        asyncio.run(server.run(receiver, "127.0.0.1", 8765))
    assert not receiver.mouse.held and adapter.closed
    assert current == original


def test_backend_close_failure_still_restores_handlers(adapter, handlers, monkeypatch):
    original, current = handlers

    async def serve(*args, **kwargs):
        pass

    def close():
        raise OSError("backend close failed")

    monkeypatch.setattr(server, "serve", serve)
    monkeypatch.setattr(adapter, "close", close)
    with pytest.raises(OSError, match="backend close failed"):
        asyncio.run(server.run(Receiver(adapter, "01234"), "127.0.0.1", 8765))
    assert current == original


def test_signal_registration_failure_closes_backend_and_restores_prior_handler(
    adapter, handlers, monkeypatch,
):
    original, current = handlers

    def register(number, handler):
        if number == signal.SIGTERM and handler is not original[number]:
            raise ValueError("signal registration failed")
        current[number] = handler

    monkeypatch.setattr(server.signal, "signal", register)
    with pytest.raises(ValueError, match="signal registration failed"):
        asyncio.run(server.run(Receiver(adapter, "01234"), "127.0.0.1", 8765))
    assert adapter.closed
    assert current == original


def test_task_cancellation_releases_held_buttons_and_restores_handlers(
    adapter, handlers, monkeypatch,
):
    original, current = handlers
    receiver = Receiver(adapter, "01234")
    receiver.mouse._button("left", True)

    async def scenario():
        started = asyncio.Event()

        async def serve(*args, **kwargs):
            started.set()
            await asyncio.Future()

        monkeypatch.setattr(server, "serve", serve)
        task = asyncio.create_task(server.run(receiver, "127.0.0.1", 8765))
        await started.wait()
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task

    asyncio.run(scenario())
    assert adapter.closed and not receiver.mouse.held
    assert current == original
