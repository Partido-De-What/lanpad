import ctypes
import os
import sys
from types import SimpleNamespace

import pytest

from lanpad.windows import (
    INPUT,
    MOUSEEVENTF_HWHEEL,
    MOUSEEVENTF_LEFTDOWN,
    MOUSEEVENTF_LEFTUP,
    MOUSEEVENTF_MIDDLEDOWN,
    MOUSEEVENTF_MIDDLEUP,
    MOUSEEVENTF_MOVE,
    MOUSEEVENTF_RIGHTDOWN,
    MOUSEEVENTF_RIGHTUP,
    MOUSEEVENTF_WHEEL,
    SendInputError,
    WindowsAdapter,
    _load_native,
    make_input_types,
)


def signed(value):
    return value - 2**32 if value >= 2**31 else value


def snapshot(slot):
    mi = slot.mi
    return (slot.type, mi.dx, mi.dy, mi.mouseData, mi.dwFlags, mi.time, mi.dwExtraInfo)


@pytest.fixture
def win():
    calls = []
    state = SimpleNamespace(result=None, error=0)

    def send(count, inputs, size):
        calls.append((count, size, [snapshot(slot) for slot in inputs]))
        return count if state.result is None else state.result

    adapter = WindowsAdapter(send_input=send, get_last_error=lambda: state.error)
    return SimpleNamespace(adapter=adapter, calls=calls, state=state)


def events(win):
    """Flatten the recorded calls to (dx, dy, signed mouseData, dwFlags) tuples."""
    return [
        (dx, dy, signed(data), flags)
        for _, _, slots in win.calls
        for _, dx, dy, data, flags, _, _ in slots
    ]


# --- ABI -------------------------------------------------------------------------------


@pytest.mark.skipif(ctypes.sizeof(ctypes.c_void_p) != 8, reason="needs a 64-bit host")
def test_input_layout_matches_win64():
    mouse, keyboard, hardware, inp = make_input_types(ctypes.c_uint64)
    assert ctypes.sizeof(mouse) == 32
    assert ctypes.sizeof(keyboard) == 24
    assert ctypes.sizeof(hardware) == 8
    assert ctypes.sizeof(inp) == 40
    assert inp.type.offset == 0
    assert inp.mi.offset == 8
    assert (mouse.mouseData.offset, mouse.dwFlags.offset, mouse.time.offset) == (8, 12, 16)
    assert mouse.dwExtraInfo.offset == 24
    assert keyboard.dwExtraInfo.offset == 16


def test_input_layout_matches_win32():
    mouse, keyboard, hardware, inp = make_input_types(ctypes.c_uint32)
    assert ctypes.sizeof(mouse) == 24
    assert ctypes.sizeof(keyboard) == 16
    assert ctypes.sizeof(hardware) == 8
    assert ctypes.sizeof(inp) == 28
    assert inp.mi.offset == 4
    assert mouse.dwExtraInfo.offset == 20
    assert keyboard.dwExtraInfo.offset == 12


def test_native_input_uses_the_pointer_width_of_the_host():
    assert ctypes.sizeof(INPUT) == (40 if ctypes.sizeof(ctypes.c_void_p) == 8 else 28)


def test_native_binding_sets_the_call_signature_and_clears_stale_errors(monkeypatch):
    calls = []

    def native(count, inputs, size):
        calls.append((count, size))
        return count

    def load(name, **options):
        assert name == "user32" and options == {"use_last_error": True}
        return SimpleNamespace(SendInput=native)

    monkeypatch.setattr(ctypes, "WinDLL", load, raising=False)
    monkeypatch.setattr(ctypes, "set_last_error", lambda code: calls.append(("error", code)),
                        raising=False)
    monkeypatch.setattr(ctypes, "get_last_error", lambda: 0, raising=False)
    send, error = _load_native()
    assert native.argtypes == (ctypes.c_uint, ctypes.POINTER(INPUT), ctypes.c_int)
    assert native.restype is ctypes.c_uint
    assert send(1, (INPUT * 1)(), ctypes.sizeof(INPUT)) == 1
    assert calls == [("error", 0), (1, ctypes.sizeof(INPUT))]
    assert error() == 0


def test_union_members_share_storage():
    inp = INPUT()
    inp.mi.dx = 7
    assert bytes(inp)[INPUT.mi.offset] == 7  # dx is the first little-endian byte of the union


# --- events ----------------------------------------------------------------------------


def test_move_is_one_relative_event(win):
    win.adapter.move(5, -3)
    assert win.calls == [(1, ctypes.sizeof(INPUT), [(0, 5, -3, 0, MOUSEEVENTF_MOVE, 0, 0)])]


def test_zero_move_and_zero_scroll_send_nothing(win):
    win.adapter.move(0, 0)
    win.adapter.scroll(0, 0)
    assert win.calls == []


@pytest.mark.parametrize(
    ("name", "down", "up"),
    [
        ("left", MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP),
        ("right", MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP),
        ("middle", MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP),
    ],
)
def test_buttons_map_to_down_and_up_flags(win, name, down, up):
    win.adapter.button(name, True)
    win.adapter.button(name, False)
    assert events(win) == [(0, 0, 0, down), (0, 0, 0, up)]


def test_unknown_button_is_rejected(win):
    with pytest.raises(ValueError, match="Unknown mouse button"):
        win.adapter.button("back", True)
    assert win.calls == []


def test_scroll_down_is_negative_wheel_data(win):
    win.adapter.scroll(0, 1)
    assert events(win) == [(0, 0, -120, MOUSEEVENTF_WHEEL)]
    # mouseData is a DWORD: -120 travels as two's complement
    assert win.calls[0][2][0][3] == 0xFFFFFF88


def test_scroll_up_is_positive_wheel_data_in_whole_notches(win):
    win.adapter.scroll(0, -2)
    assert events(win) == [(0, 0, 240, MOUSEEVENTF_WHEEL)]


def test_horizontal_scroll_keeps_the_protocol_sign(win):
    win.adapter.scroll(1, 0)
    win.adapter.scroll(-3, 0)
    assert events(win) == [(0, 0, 120, MOUSEEVENTF_HWHEEL), (0, 0, -360, MOUSEEVENTF_HWHEEL)]


def test_both_scroll_axes_go_out_in_one_call(win):
    win.adapter.scroll(2, 3)
    assert len(win.calls) == 1
    assert win.calls[0][0] == 2
    assert events(win) == [(0, 0, -360, MOUSEEVENTF_WHEEL), (0, 0, 240, MOUSEEVENTF_HWHEEL)]


def test_values_that_overflow_int32_are_rejected_not_wrapped(win):
    with pytest.raises(ValueError, match="32-bit"):
        win.adapter.move(2**31, 0)
    with pytest.raises(ValueError, match="out of range"):
        win.adapter.scroll(0, 2**30)
    assert win.calls == []


def test_non_integers_are_rejected(win):
    with pytest.raises(TypeError):
        win.adapter.move(1.5, 0)
    with pytest.raises(TypeError):
        win.adapter.scroll(0, 1.5)
    assert win.calls == []


# --- errors ----------------------------------------------------------------------------


def test_zero_events_without_error_code_explains_blocked_input(win):
    win.state.result = 0
    with pytest.raises(SendInputError, match=r"0 of 1.*UIPI") as caught:
        win.adapter.move(1, 1)
    assert (caught.value.sent, caught.value.requested, caught.value.winerror) == (0, 1, 0)


def test_access_denied_is_reported_with_its_code(win):
    win.state.result, win.state.error = 0, 5
    with pytest.raises(SendInputError, match=r"Win32 error 5") as caught:
        win.adapter.button("left", True)
    assert caught.value.winerror == 5


def test_invalid_parameter_error_code_is_reported(win):
    win.state.result, win.state.error = 0, 87
    with pytest.raises(SendInputError, match=r"Win32 error 87"):
        win.adapter.move(1, 0)


def test_unknown_error_code_is_still_reported(win):
    win.state.result, win.state.error = 0, 6
    with pytest.raises(SendInputError, match=r"Win32 error 6$"):
        win.adapter.move(1, 0)


def test_partial_insertion_reports_how_many_went_in(win):
    win.state.result = 1
    with pytest.raises(SendInputError, match="1 of 2"):
        win.adapter.scroll(1, 1)


def test_send_input_error_is_a_runtime_error(win):
    win.state.result = 0
    with pytest.raises(RuntimeError):
        win.adapter.move(1, 0)


# --- lifecycle -------------------------------------------------------------------------


def test_close_is_idempotent_and_stops_injection(win):
    win.adapter.close()
    win.adapter.close()
    with pytest.raises(RuntimeError, match="closed"):
        win.adapter.move(1, 0)
    assert win.calls == []


def test_adapter_refuses_to_build_off_windows(monkeypatch):
    monkeypatch.setattr(sys, "platform", "linux")
    with pytest.raises(RuntimeError, match="Windows only"):
        WindowsAdapter()


# --- real user32 (opt in: LANPAD_WIN_LIVE=1 on a Windows desktop session) ---------------


@pytest.mark.skipif(
    sys.platform != "win32" or os.environ.get("LANPAD_WIN_LIVE") != "1",
    reason="needs Windows and LANPAD_WIN_LIVE=1",
)
def test_real_sendinput_accepts_our_layout_with_a_zero_move():
    send, last_error = _load_native()
    inputs = (INPUT * 1)()
    inputs[0].mi.dwFlags = MOUSEEVENTF_MOVE  # dx = dy = 0: the cursor does not move
    assert send(1, inputs, ctypes.sizeof(INPUT)) == 1, last_error()
    assert send(1, inputs, ctypes.sizeof(INPUT) + 1) == 0  # wrong cbSize must fail
