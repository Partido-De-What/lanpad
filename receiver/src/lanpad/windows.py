"""Windows mouse backend: relative input through user32!SendInput (ctypes only).

Nothing touches user32 at import time, so the module imports (and its tests run) on any OS.
`WindowsAdapter(send_input=...)` injects a fake instead of the real DLL call.

Microsoft docs:
https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-sendinput
https://learn.microsoft.com/en-us/windows/win32/api/winuser/ns-winuser-input
https://learn.microsoft.com/en-us/windows/win32/api/winuser/ns-winuser-mouseinput
"""

import ctypes
import operator
import sys
from collections.abc import Callable, Sequence
from typing import Any

from .protocol import ButtonName

INPUT_MOUSE = 0
MOUSEEVENTF_MOVE = 0x0001
MOUSEEVENTF_LEFTDOWN = 0x0002
MOUSEEVENTF_LEFTUP = 0x0004
MOUSEEVENTF_RIGHTDOWN = 0x0008
MOUSEEVENTF_RIGHTUP = 0x0010
MOUSEEVENTF_MIDDLEDOWN = 0x0020
MOUSEEVENTF_MIDDLEUP = 0x0040
MOUSEEVENTF_WHEEL = 0x0800
MOUSEEVENTF_HWHEEL = 0x1000
WHEEL_DELTA = 120

_BUTTON_FLAGS = {  # (down, up)
    "left": (MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP),
    "right": (MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP),
    "middle": (MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP),
}
_INT32 = range(-(2**31), 2**31)

_NO_CODE_HINT = (
    "no Win32 error code; injection may be blocked by UIPI or an inaccessible input desktop. "
    "SendInput does not identify UIPI as the cause of failure; target applications must run "
    "at the same or a lower integrity level than the receiver"
)


def make_input_types(ulong_ptr: type) -> tuple[type, type, type, type]:
    """Return (MOUSEINPUT, KEYBDINPUT, HARDWAREINPUT, INPUT) for one ULONG_PTR width.

    c_uint32 gives the x86 layout (INPUT is 28 bytes), c_uint64 the x64 one (40 bytes).
    DWORD/LONG/WORD are fixed width on Windows, so they are spelled c_uint32/c_int32/c_uint16
    (c_ulong would be 64 bit on Linux and break the layout tests).
    """

    class MOUSEINPUT(ctypes.Structure):
        _fields_ = [
            ("dx", ctypes.c_int32),
            ("dy", ctypes.c_int32),
            ("mouseData", ctypes.c_uint32),
            ("dwFlags", ctypes.c_uint32),
            ("time", ctypes.c_uint32),
            ("dwExtraInfo", ulong_ptr),
        ]

    class KEYBDINPUT(ctypes.Structure):
        _fields_ = [
            ("wVk", ctypes.c_uint16),
            ("wScan", ctypes.c_uint16),
            ("dwFlags", ctypes.c_uint32),
            ("time", ctypes.c_uint32),
            ("dwExtraInfo", ulong_ptr),
        ]

    class HARDWAREINPUT(ctypes.Structure):
        _fields_ = [
            ("uMsg", ctypes.c_uint32),
            ("wParamL", ctypes.c_uint16),
            ("wParamH", ctypes.c_uint16),
        ]

    class _InputUnion(ctypes.Union):
        _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT), ("hi", HARDWAREINPUT)]

    class INPUT(ctypes.Structure):
        _anonymous_ = ("u",)  # must be set before _fields_
        _fields_ = [("type", ctypes.c_uint32), ("u", _InputUnion)]

    return MOUSEINPUT, KEYBDINPUT, HARDWAREINPUT, INPUT


# Native layout: c_size_t has the width of ULONG_PTR (4 bytes on x86, 8 on x64/ARM64).
MOUSEINPUT, KEYBDINPUT, HARDWAREINPUT, INPUT = make_input_types(ctypes.c_size_t)

SendInputFn = Callable[[int, Any, int], int]


class SendInputError(RuntimeError):
    """SendInput inserted fewer events than requested."""

    def __init__(self, sent: int, requested: int, winerror: int) -> None:
        self.sent = sent
        self.requested = requested
        self.winerror = winerror
        hint = _NO_CODE_HINT
        if winerror:
            hint = f"Win32 error {winerror}"
        super().__init__(f"SendInput inserted {sent} of {requested} mouse events, {hint}")


def _load_native() -> tuple[SendInputFn, Callable[[], int]]:
    user32 = ctypes.WinDLL("user32", use_last_error=True)
    fn = user32.SendInput
    fn.argtypes = (ctypes.c_uint, ctypes.POINTER(INPUT), ctypes.c_int)
    fn.restype = ctypes.c_uint

    def send(count: int, inputs: Any, size: int) -> int:
        # Clear the ctypes-private copy first: a failure without a code must not
        # report the error of an earlier call.
        ctypes.set_last_error(0)
        return fn(count, inputs, size)

    return send, ctypes.get_last_error


def _i32(value: int, name: str) -> int:
    value = operator.index(value)
    if value not in _INT32:
        raise ValueError(f"{name} does not fit a 32-bit LONG: {value}")
    return value


def _wheel(steps: int) -> int:
    """Whole notches -> mouseData (DWORD, two's complement for negative values)."""
    data = steps * WHEEL_DELTA
    if data not in _INT32:
        raise ValueError(f"Scroll of {steps} steps is out of range")
    return data & 0xFFFFFFFF


class WindowsAdapter:
    """InputAdapter for Windows 10/11: relative mouse through SendInput.

    Relative moves go through the user's pointer speed / acceleration settings
    (MOUSEINPUT remarks: up to 4x), exactly like a physical mouse.
    """

    def __init__(
        self,
        *,
        send_input: SendInputFn | None = None,
        get_last_error: Callable[[], int] | None = None,
    ) -> None:
        if send_input is None:
            if sys.platform != "win32":
                raise RuntimeError("The Windows backend works on Windows only")
            send_input, get_last_error = _load_native()
        self._send_input = send_input
        self._get_last_error = get_last_error or (lambda: 0)
        self._closed = False

    def move(self, dx: int, dy: int) -> None:
        dx, dy = _i32(dx, "dx"), _i32(dy, "dy")
        if dx or dy:
            self._send([(dx, dy, 0, MOUSEEVENTF_MOVE)])

    def button(self, button: ButtonName, down: bool) -> None:
        try:
            flags = _BUTTON_FLAGS[button][0 if down else 1]
        except KeyError:
            raise ValueError(f"Unknown mouse button: {button!r}") from None
        self._send([(0, 0, 0, flags)])

    def scroll(self, dx: int, dy: int) -> None:
        dx, dy = operator.index(dx), operator.index(dy)
        events = []
        if dy:
            # Protocol: dy > 0 scrolls down. Windows: positive wheel data = forward (up).
            events.append((0, 0, _wheel(-dy), MOUSEEVENTF_WHEEL))
        if dx:
            # Protocol and Windows agree: positive = right.
            events.append((0, 0, _wheel(dx), MOUSEEVENTF_HWHEEL))
        if events:
            self._send(events)

    def close(self) -> None:
        # SendInput holds no handle; this only stops further injection. Idempotent.
        self._closed = True

    def _send(self, events: Sequence[tuple[int, int, int, int]]) -> None:
        if self._closed:
            raise RuntimeError("The Windows mouse adapter is closed")
        inputs = (INPUT * len(events))()
        for slot, (dx, dy, data, flags) in zip(inputs, events, strict=True):
            slot.type = INPUT_MOUSE
            slot.mi.dx = dx
            slot.mi.dy = dy
            slot.mi.mouseData = data
            slot.mi.dwFlags = flags  # time = 0: the system stamps it; dwExtraInfo = 0
        sent = self._send_input(len(events), inputs, ctypes.sizeof(INPUT))
        if sent != len(events):
            raise SendInputError(sent, len(events), self._get_last_error())
