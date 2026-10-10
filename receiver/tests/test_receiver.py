import asyncio
import json

import pytest

from lanpad import receiver as module
from lanpad.receiver import Receiver

PIN = "01234"


class Connection:
    """In-memory duplex connection; the production receiver runs unchanged."""

    def __init__(self, *messages):
        self.incoming = asyncio.Queue()
        for message in messages:
            self.incoming.put_nowait(message)
        self.outgoing = asyncio.Queue()
        self.closed = None

    async def recv(self):
        return await self.incoming.get()

    async def send(self, message):
        self.outgoing.put_nowait(json.loads(message))

    async def close(self, code, reason):
        self.closed = (code, reason)

    def input(self, message):
        self.incoming.put_nowait(json.dumps(message) if message is not None else None)


def pair(pin=PIN):
    return json.dumps({"type": "pair", "version": 2, "pin": pin})


def run(coro):
    async def bounded():
        async with asyncio.timeout(2):
            return await coro
    return asyncio.run(bounded())


def test_paired_controller_can_move_click_scroll_and_disconnect(adapter):
    async def scenario():
        receiver = Receiver(adapter, PIN)
        connection = Connection(pair())
        for message in [
            {"type": "move", "dx": 12, "dy": -3},
            {"type": "click", "button": "left"},
            {"type": "scroll", "dx": 1, "dy": -1},
            {"type": "button", "button": "right", "down": True},
            None,
        ]:
            connection.input(message)
        await receiver.handle(connection)
        assert await connection.outgoing.get() == {"type": "paired", "version": 2}
        assert connection.closed[0] == 1000
        assert receiver.active is None
        assert adapter.events == [
            ("move", 12, -3), ("button", "left", True), ("button", "left", False),
            ("scroll", 1, -1), ("button", "right", True), ("button", "right", False),
        ]
    run(scenario())


@pytest.mark.parametrize("first", [pair("99999"), '{"type":"move","dx":1,"dy":1}', "{}"])
def test_unpaired_clients_cannot_inject_input(adapter, first):
    async def scenario():
        receiver = Receiver(adapter, PIN)
        connection = Connection(first)
        await receiver.handle(connection)
        assert connection.closed[0] == 4001
        assert not adapter.events
        assert not receiver.pending_pairs
    run(scenario())


def test_second_controller_cannot_release_first_controllers_buttons(adapter):
    async def scenario():
        receiver = Receiver(adapter, PIN)
        first = Connection(pair())
        task = asyncio.create_task(receiver.handle(first))
        await first.outgoing.get()
        first.input({"type": "button", "button": "left", "down": True})
        await asyncio.sleep(0)
        second = Connection(pair())
        await receiver.handle(second)
        assert second.closed[0] == 4009
        assert receiver.mouse.held == {"left"}
        first.input(None)
        await task
        third = Connection(pair(), None)
        await receiver.handle(third)
        assert third.closed[0] == 1000
        assert adapter.events == [("button", "left", True), ("button", "left", False)]
    run(scenario())


@pytest.mark.parametrize(("tail", "expected"), [("{}", 4002), (b"binary", 4002)])
def test_invalid_input_releases_buttons_before_closing(adapter, tail, expected, monkeypatch):
    async def scenario():
        receiver = Receiver(adapter, PIN)
        connection = Connection(pair(), '{"type":"button","button":"left","down":true}', tail)
        real_close = connection.close

        async def close(code, reason):
            assert not receiver.mouse.held
            await real_close(code, reason)

        monkeypatch.setattr(connection, "close", close)
        await receiver.handle(connection)
        assert connection.closed[0] == expected
        assert adapter.events[-1] == ("button", "left", False)
    run(scenario())


def test_idle_timeout_releases_drag(adapter, monkeypatch):
    monkeypatch.setattr(module, "IDLE_TIMEOUT", 0.01)

    async def scenario():
        receiver = Receiver(adapter, PIN)
        connection = Connection(pair(), '{"type":"button","button":"left","down":true}')
        await receiver.handle(connection)
        assert connection.closed[0] == 4000
        assert adapter.events[-1] == ("button", "left", False)
    run(scenario())


def test_pairing_timeout_does_not_claim_mouse(adapter, monkeypatch):
    monkeypatch.setattr(module, "AUTH_TIMEOUT", 0.01)

    async def scenario():
        receiver = Receiver(adapter, PIN)
        connection = Connection()
        await receiver.handle(connection)
        assert connection.closed[0] == 4001
        assert not receiver.pending_pairs
        assert not adapter.events
    run(scenario())


def test_excess_pending_pairs_are_rejected(adapter):
    async def scenario():
        receiver = Receiver(adapter, PIN)
        receiver.pending_pairs = module.MAX_PENDING_PAIRS
        connection = Connection(pair())
        await receiver.handle(connection)
        assert connection.closed[0] == 4009
        assert not adapter.events
    run(scenario())


def test_flood_releases_buttons(adapter, monkeypatch):
    monkeypatch.setattr(module.time, "monotonic", lambda: 100.0)

    async def scenario():
        receiver = Receiver(adapter, PIN)
        connection = Connection(pair(), '{"type":"button","button":"left","down":true}')
        for _ in range(60):
            connection.input({"type": "heartbeat"})
        await receiver.handle(connection)
        assert connection.closed[0] == 4008
        assert adapter.events[-1] == ("button", "left", False)
    run(scenario())


def test_cancellation_releases_buttons(adapter):
    async def scenario():
        receiver = Receiver(adapter, PIN)
        connection = Connection(pair(), '{"type":"button","button":"left","down":true}')
        task = asyncio.create_task(receiver.handle(connection))
        await connection.outgoing.get()
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert receiver.active is None
        assert adapter.events[-1] == ("button", "left", False)
    run(scenario())


def test_input_failure_releases_buttons(adapter, monkeypatch):
    def fail_move(dx, dy):
        raise OSError("Display lost")
    monkeypatch.setattr(adapter, "move", fail_move)

    async def scenario():
        receiver = Receiver(adapter, PIN)
        connection = Connection(
            pair(), '{"type":"button","button":"left","down":true}',
            '{"type":"move","dx":1,"dy":0}',
        )
        await receiver.handle(connection)
        assert connection.closed[0] == 1011
        assert adapter.events[-1] == ("button", "left", False)
    run(scenario())


def test_rate_limit_recovers_after_time_passes(monkeypatch):
    clock = [100.0]
    monkeypatch.setattr(module.time, "monotonic", lambda: clock[0])
    rate = module.RateLimit()
    assert all(rate.allow() for _ in range(60))
    assert not rate.allow()
    clock[0] += 1
    assert rate.allow()


def test_stop_releases_and_blocks_new_input(adapter):
    async def scenario():
        receiver = Receiver(adapter, PIN)
        connection = Connection(pair(), '{"type":"button","button":"left","down":true}')
        task = asyncio.create_task(receiver.handle(connection))
        await connection.outgoing.get()
        receiver.stop()
        connection.input({"type": "move", "dx": 1, "dy": 0})
        await task
        second = Connection(pair())
        await receiver.handle(second)
        assert second.closed[0] == 1001
        assert adapter.events == [("button", "left", True), ("button", "left", False)]
    run(scenario())


def test_failed_cleanup_blocks_next_controller(adapter, monkeypatch):
    async def scenario():
        receiver = Receiver(adapter, PIN)
        real_button = adapter.button

        def fail_release(button, down):
            if not down:
                raise OSError("Display unavailable")
            real_button(button, down)

        monkeypatch.setattr(adapter, "button", fail_release)
        first = Connection(pair(), '{"type":"button","button":"left","down":true}', None)
        await receiver.handle(first)
        assert first.closed[0] == 1011
        second = Connection(pair())
        await receiver.handle(second)
        assert second.closed[0] == 1001
        assert receiver.stopping
        monkeypatch.setattr(adapter, "button", real_button)
        receiver.stop()
        assert not receiver.mouse.held
    run(scenario())


def test_pin_failures_are_limited_across_connections_and_expire(adapter, monkeypatch):
    clock = [100.0]
    monkeypatch.setattr(module.time, "monotonic", lambda: clock[0])

    async def scenario():
        receiver = Receiver(adapter, PIN)
        for _ in range(module.MAX_FAILED_PAIRS):
            attempt = Connection(pair("99999"))
            await receiver.handle(attempt)
            assert attempt.closed[0] == 4001
        blocked = Connection(pair(), None)
        await receiver.handle(blocked)
        assert blocked.closed[0] == 4003
        assert len(receiver.failed_pairs) == module.MAX_FAILED_PAIRS
        assert not adapter.events
        clock[0] += module.PAIR_FAILURE_WINDOW
        accepted = Connection(pair(), None)
        await receiver.handle(accepted)
        assert await accepted.outgoing.get() == {"type": "paired", "version": 2}
        assert not receiver.failed_pairs
    run(scenario())


def test_pending_connections_cannot_bypass_pin_failure_limit(adapter, monkeypatch):
    monkeypatch.setattr(module.time, "monotonic", lambda: 100.0)

    async def scenario():
        receiver = Receiver(adapter, PIN)
        pending = [Connection() for _ in range(module.MAX_PENDING_PAIRS)]
        tasks = [asyncio.create_task(receiver.handle(connection)) for connection in pending]
        await asyncio.sleep(0)
        for connection in pending:
            connection.input({"type": "pair", "version": 2, "pin": "99999"})
        await asyncio.gather(*tasks)
        failures = sum(connection.closed[0] == 4001 for connection in pending)
        assert failures == module.MAX_FAILED_PAIRS
        assert all(connection.closed[0] in (4001, 4003) for connection in pending)
        assert len(receiver.failed_pairs) == module.MAX_FAILED_PAIRS
        assert receiver.pending_pairs == 0
        assert not adapter.events
    run(scenario())
