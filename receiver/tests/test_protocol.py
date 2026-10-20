import json

import pytest

from lanpad.protocol import (
    Button,
    Click,
    Heartbeat,
    Move,
    ProtocolError,
    ReleaseAll,
    Scroll,
    parse_command,
    parse_pair,
)

PIN = "01234"


@pytest.mark.parametrize(("message", "command"), [
    ({"type": "move", "dx": 1.25, "dy": -2}, Move(1.25, -2)),
    ({"type": "scroll", "dx": 0, "dy": 0.5}, Scroll(0, 0.5)),
    ({"type": "button", "button": "right", "down": True}, Button("right", True)),
    ({"type": "click", "button": "middle"}, Click("middle")),
    ({"type": "release_all"}, ReleaseAll()),
    ({"type": "heartbeat"}, Heartbeat()),
])
def test_commands(message, command):
    assert parse_command(json.dumps(message)) == command


@pytest.mark.parametrize("raw", [
    b'{"type":"heartbeat"}',
    "null", "[]", "1", "broken", "{" * 1000,
    '{"type":"heartbeat","extra":1}',
    '{"type":"heartbeat","type":"release_all"}',
    '{"type":"move","dx":1}',
    '{"type":"move","dx":true,"dy":0}',
    '{"type":"move","dx":"1","dy":0}',
    '{"type":"move","dx":NaN,"dy":0}',
    '{"type":"move","dx":Infinity,"dy":0}',
    '{"type":"move","dx":2049,"dy":0}',
    '{"type":"move","dx":1e999,"dy":0}',
    '{"type":"scroll","dx":0,"dy":11}',
    '{"type":"button","button":"left","down":1}',
    '{"type":"click","button":"unknown"}',
    '{"type":"click","button":[]}',
    '{"type":"pair","version":4,"pin":"' + PIN + '"}',
    '{"type":[]}',
    " " * 1025,
    "\ud800",
])
def test_rejects_invalid_commands(raw):
    with pytest.raises(ProtocolError):
        parse_command(raw)


def test_pairing():
    assert parse_pair(json.dumps({"type": "pair", "version": 4, "pin": PIN})) == PIN


@pytest.mark.parametrize("changes", [
    {"version": 1}, {"version": True}, {"version": 4.0},
    {"type": "move"}, {"pin": "short"}, {"pin": "１２３４５"},
    {"pin": "123456"}, {"pin": "1234"}, {"pin": 12345},
    {"token": "a" * 43}, {"extra": True},
])
def test_rejects_invalid_pairing(changes):
    message = {"type": "pair", "version": 4, "pin": PIN} | changes
    with pytest.raises(ProtocolError):
        parse_pair(json.dumps(message))
