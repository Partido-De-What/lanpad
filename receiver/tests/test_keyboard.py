import json
from types import SimpleNamespace

import pytest

from lanpad.adapters import LoggingAdapter, X11Adapter
from lanpad.mouse import MouseSession
from lanpad.protocol import Key, ProtocolError, Text, parse_command
from lanpad.windows import WindowsAdapter


@pytest.mark.parametrize("command", [
    Key("enter"), Key("f12", ("ctrl", "shift")), Key("volume_mute"),
    Text("Caffè ☕ 😀\n\t"), Text("a" * 128),
])
def test_keyboard_protocol_roundtrip(command):
    if isinstance(command, Key):
        payload = {"type": "key", "key": command.key, "modifiers": list(command.modifiers)}
    else:
        payload = {"type": "text", "text": command.text}
    assert parse_command(json.dumps(payload)) == command


@pytest.mark.parametrize("payload", [
    {"type": "key", "key": "enter", "modifiers": ["ctrl", "ctrl"]},
    {"type": "key", "key": "enter", "modifiers": [None]},
    {"type": "key", "key": "enter", "modifiers": [["ctrl"]]},
    {"type": "key", "key": [], "modifiers": []},
    {"type": "key", "key": "shell-command", "modifiers": []},
    {"type": "key", "key": "a", "modifiers": "ctrl"},
    {"type": "key", "key": "a", "modifiers": ["meta"]},
    {"type": "key", "key": "a"},
    *[{"type": "text", "text": value} for value in
      [None, "", "a" * 129, "\x00", "\x1b", "\x7f", "\ud800"]],
])
def test_invalid_keyboard_messages_never_reach_the_backend(payload):
    with pytest.raises(ProtocolError):
        parse_command(json.dumps(payload))


def test_chord_releases_in_reverse_order_even_after_failed_press():
    events = []

    def key(name, down):
        events.append((name, down))
        if name == "q" and down:
            raise OSError("Injection failed after press")

    session = MouseSession(SimpleNamespace(key=key))
    with pytest.raises(OSError):
        session.apply(Key("q", ("super", "shift")))
    assert events == [("super", True), ("shift", True), ("q", True),
                      ("q", False), ("shift", False), ("super", False)]
    assert session.held_keys == []


def test_failed_key_release_does_not_prevent_other_releases_and_is_retried():
    events = []
    failing = True

    def key(name, down):
        events.append((name, down))
        if name == "a" and not down and failing:
            raise OSError("Release failed")

    session = MouseSession(SimpleNamespace(key=key))
    with pytest.raises(ExceptionGroup):
        session.apply(Key("a", ("ctrl",)))
    assert session.held_keys == ["a"]
    assert events[-1] == ("ctrl", False)
    failing = False
    session.release_all()
    assert session.held_keys == []


def test_text_dispatch_and_dry_run_do_not_log_contents(caplog):
    caplog.set_level("INFO")
    MouseSession(LoggingAdapter()).apply(Text("private text"))
    assert "12 characters" in caplog.text
    assert "private text" not in caplog.text


def test_windows_chord_and_unicode_generate_keyboard_inputs():
    events = []

    def send(count, inputs, size):
        events.extend((i.type, i.ki.wVk, i.ki.wScan, i.ki.dwFlags) for i in inputs)
        return count

    session = MouseSession(WindowsAdapter(send_input=send))
    session.apply(Key("left", ("super", "shift")))
    assert events == [(1, 0x5B, 0, 1), (1, 0x10, 0, 0), (1, 0x25, 0, 1),
                      (1, 0x25, 0, 3), (1, 0x10, 0, 2), (1, 0x5B, 0, 3)]
    events.clear()
    session.apply(Text("è😀\n"))
    assert events == [(1, 0, 0xE8, 4), (1, 0, 0xE8, 6),
                      (1, 0, 0xD83D, 4), (1, 0, 0xD83D, 6),
                      (1, 0, 0xDE00, 4), (1, 0, 0xDE00, 6),
                      (1, 0x0D, 0, 0), (1, 0x0D, 0, 2)]


def test_x11_text_maps_unicode_and_restores_mapping_even_after_injection_failure(monkeypatch):
    from Xlib import XK, X
    from Xlib.ext import xtest

    from lanpad import adapters

    events, changes = [], []
    original = [(ord("a"), ord("A")), (XK.XK_Shift_L, 0), (0, 0)]
    display = SimpleNamespace(
        display=SimpleNamespace(info=SimpleNamespace(min_keycode=8, max_keycode=10)),
        get_keyboard_mapping=lambda first, count: original,
        keysym_to_keycode=lambda symbol: 9,
        change_keyboard_mapping=lambda code, symbols: changes.append((code, symbols)),
        sync=lambda: None,
        query_keymap=lambda: [0] * 32,
        get_modifier_mapping=lambda: [[]] * 8,
        screen=lambda: SimpleNamespace(root=SimpleNamespace(
            query_pointer=lambda: SimpleNamespace(mask=0))),
    )
    monkeypatch.setattr(adapters.time, "sleep", lambda seconds: None)
    monkeypatch.setattr(xtest, "fake_input", lambda display, event, code:
                        events.append((event, code)))
    adapter = object.__new__(X11Adapter)
    adapter._display, adapter._x = display, X
    adapter.text("aA😀")
    assert events == [(X.KeyPress, 8), (X.KeyRelease, 8), (X.KeyPress, 9),
                      (X.KeyPress, 8), (X.KeyRelease, 8), (X.KeyRelease, 9),
                      (X.KeyPress, 10), (X.KeyRelease, 10)]
    assert changes == [(10, [(0x0101F600,)]), (10, [(0, 0)])]
    changes.clear()

    def fail(display, event, code):
        if event == X.KeyPress:
            raise OSError("Injection failed")

    monkeypatch.setattr(xtest, "fake_input", fail)
    with pytest.raises(OSError):
        adapter.text("😀")
    assert changes[-1] == (10, [(0, 0)])


def test_x11_keys_resolve_modifiers_and_custom_i3_bindings(monkeypatch):
    from Xlib import XK, X

    events = []
    adapter = object.__new__(X11Adapter)
    adapter._x = X
    adapter._display = SimpleNamespace(
        keysym_to_keycode=lambda symbol: events.append(("symbol", symbol)) or 42,
        xtest_fake_input=lambda event, **kw: events.append((event, kw["detail"])),
        flush=lambda: None,
    )
    adapter.key("super", True)
    adapter.key("o", False)
    adapter.key("volume_up", True)
    assert events == [("symbol", XK.string_to_keysym("Super_L")), (X.KeyPress, 42),
                      ("symbol", ord("o")), (X.KeyRelease, 42),
                      ("symbol", XK.string_to_keysym("XF86_AudioRaiseVolume")), (X.KeyPress, 42)]
