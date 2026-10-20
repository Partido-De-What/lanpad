"""Strict, versioned messages shared by the receiver and future Android app."""

import json
import math
import re
from dataclasses import dataclass
from typing import Literal

VERSION = 4
MAX_MESSAGE_BYTES = 1024
ButtonName = Literal["left", "right", "middle"]


class ProtocolError(ValueError):
    """A message cannot safely be interpreted as input."""


# Dataclasses carry validated command data. frozen prevents later mutation;
# slots restrict each instance to its declared fields.
@dataclass(frozen=True, slots=True)
class Move:
    """Move by relative pixels: positive dx is right, positive dy is down."""

    dx: float
    dy: float


@dataclass(frozen=True, slots=True)
class Scroll:
    """Scroll by wheel steps, allowing fractions to accumulate between messages."""

    dx: float
    dy: float


@dataclass(frozen=True, slots=True)
class Button:
    """Hold a button when down is True, or release it when False."""

    button: ButtonName
    down: bool


@dataclass(frozen=True, slots=True)
class Click:
    """Request one button press followed by a release."""

    button: ButtonName


@dataclass(frozen=True, slots=True)
class ReleaseAll:
    """Request release of every button held by the current controller."""

    pass


@dataclass(frozen=True, slots=True)
class Heartbeat:
    """Keep the session alive without requesting mouse input."""

    pass


MODIFIERS = frozenset({"ctrl", "alt", "shift", "super"})
KEYS = frozenset(
    list("abcdefghijklmnopqrstuvwxyz0123456789")
    + ["enter", "tab", "escape", "backspace", "delete", "space", "left", "right",
       "up", "down", "home", "end", "page_up", "page_down", "insert"]
    + [f"f{i}" for i in range(1, 13)]
    + ["volume_up", "volume_down", "volume_mute", "mic_mute",
       "brightness_up", "brightness_down"]
)


@dataclass(frozen=True, slots=True)
class Key:
    """An atomic key tap with optional modifiers; no persistent keyboard holds."""

    key: str
    modifiers: tuple[str, ...] = ()


@dataclass(frozen=True, slots=True)
class Text:
    """Type a bounded Unicode text fragment, independent of shortcut modifiers."""

    text: str


type Command = Move | Scroll | Button | Click | Key | Text | ReleaseAll | Heartbeat


def _object(pairs: list[tuple[str, object]]) -> dict:
    """Build each JSON object while rejecting repeated field names."""
    # Ordinary JSON decoding overwrites duplicate keys; checking pairs preserves
    # the chance to reject an ambiguous message before that information is lost.
    result = {}
    for key, value in pairs:
        if key in result:
            raise ProtocolError("Duplicate fields are not allowed")
        result[key] = value
    return result


def _decode(raw: str | bytes) -> dict:
    """Decode a bounded JSON text message, raising ProtocolError for invalid input."""
    if not isinstance(raw, str):
        raise ProtocolError("Send JSON in a text frame")
    try:
        if len(raw.encode("utf-8")) > MAX_MESSAGE_BYTES:
            raise ProtocolError("Message is too large")
        message = json.loads(raw, object_pairs_hook=_object)
    except (ValueError, UnicodeError, RecursionError) as exc:
        raise ProtocolError("Invalid JSON message") from exc
    if not isinstance(message, dict):
        raise ProtocolError("Message must be an object")
    return message


def _fields(message: dict, *fields: str) -> None:
    """Require exactly 'type' and the listed fields, with no missing or extra keys."""
    if set(message) != {"type", *fields}:
        raise ProtocolError("Missing or unexpected fields")


def _number(value: object, limit: int) -> float:
    """Return a finite coordinate within the command's permitted range."""
    # bool is a subclass of int in Python, so an exact type check excludes it.
    if type(value) not in (int, float) or not -limit <= value <= limit:
        raise ProtocolError(f"Coordinates must be finite numbers within +/-{limit}")
    number = float(value)
    if not math.isfinite(number):
        raise ProtocolError("Coordinates must be finite")
    return number


def _button(value: object) -> ButtonName:
    """Return a supported button name or reject the message."""
    if value not in ("left", "right", "middle"):
        raise ProtocolError("Unknown mouse button")
    return value


def parse_pair(raw: str | bytes) -> str:
    """Validate pairing and return a five-digit ASCII PIN, preserving leading zeros."""
    message = _decode(raw)
    _fields(message, "version", "pin")
    if message["type"] != "pair" or type(message["version"]) is not int:
        raise ProtocolError("Pairing must be the first message")
    if message["version"] != VERSION:
        raise ProtocolError("Unsupported protocol version")
    pin = message["pin"]
    if not isinstance(pin, str) or not re.fullmatch(r"[0-9]{5}", pin):
        raise ProtocolError("PIN must contain exactly five digits")
    return pin


def parse_command(raw: str | bytes) -> Command:
    """Validate the message's fields and convert it into a typed mouse command."""
    message = _decode(raw)
    match message.get("type"):
        case "move" | "scroll" as kind:
            _fields(message, "dx", "dy")
            limit = 2048 if kind == "move" else 10
            dx, dy = (_number(message[key], limit) for key in ("dx", "dy"))
            return Move(dx, dy) if kind == "move" else Scroll(dx, dy)
        case "button":
            _fields(message, "button", "down")
            if type(message["down"]) is not bool:
                raise ProtocolError("down must be a boolean")
            return Button(_button(message["button"]), message["down"])
        case "click":
            _fields(message, "button")
            return Click(_button(message["button"]))
        case "key":
            _fields(message, "key", "modifiers")
            key, modifiers = message["key"], message["modifiers"]
            if not isinstance(key, str) or key not in KEYS:
                raise ProtocolError("Unknown key")
            if (not isinstance(modifiers, list) or len(modifiers) > 4
                    or any(not isinstance(m, str) or m not in MODIFIERS for m in modifiers)
                    or len(set(modifiers)) != len(modifiers)):
                raise ProtocolError("Invalid modifiers")
            return Key(key, tuple(modifiers))
        case "text":
            _fields(message, "text")
            text = message["text"]
            if (not isinstance(text, str) or not 1 <= len(text) <= 128
                    or any((ord(c) < 32 and c not in "\n\t") or ord(c) == 127
                           or 0xD800 <= ord(c) <= 0xDFFF for c in text)):
                raise ProtocolError("Text must contain 1–128 characters without control codes")
            return Text(text)
        case "release_all" | "heartbeat" as kind:
            _fields(message)
            return ReleaseAll() if kind == "release_all" else Heartbeat()
        case _:
            raise ProtocolError("Unknown command")
