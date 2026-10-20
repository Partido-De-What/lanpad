"""Linux X11 input and a logging adapter for development without cursor control."""

import logging
import os
import sys
import time

from .protocol import ButtonName

log = logging.getLogger(__name__)


class X11Adapter:
    """Inject mouse events into the current X11 desktop using its XTEST extension."""

    def __init__(self) -> None:
        """Check the desktop session and open an X11 connection with XTEST support."""
        if sys.platform != "linux" or os.environ.get("XDG_SESSION_TYPE") == "wayland":
            raise RuntimeError("The receiver currently supports Linux with X11 only")
        if not os.environ.get("DISPLAY"):
            raise RuntimeError("DISPLAY is unset; run the receiver inside your X11 session")
        # Import only for real input; dry runs do not need Xlib or a display.
        from Xlib import X, display

        self._x = X
        self._display = display.Display()
        try:
            if not self._display.has_extension("XTEST"):
                raise RuntimeError("The X11 server does not provide the XTEST extension")
            self._display.xtest_get_version(2, 2)
        except Exception:
            self._display.close()
            raise

    def move(self, dx: int, dy: int) -> None:
        """Send a relative movement event and flush it to the X server."""
        # XTEST relative motion avoids querying or caching the pointer position.
        self._display.xtest_fake_input(self._x.MotionNotify, detail=1, x=dx, y=dy)
        # flush sends queued requests now instead of waiting for another X call.
        self._display.flush()

    def button(self, button: ButtonName, down: bool) -> None:
        """Convert the readable button name to an X11 button number and inject it."""
        number = {"left": 1, "middle": 2, "right": 3}[button]
        self._button(number, down)
        self._display.flush()

    def _button(self, number: int, down: bool) -> None:
        """Queue a numbered X11 press/release; the caller decides when to flush."""
        event = self._x.ButtonPress if down else self._x.ButtonRelease
        self._display.xtest_fake_input(event, detail=number)

    def scroll(self, dx: int, dy: int) -> None:
        """Represent each wheel step as a press/release of an X11 wheel button."""
        # X11 wheel buttons: up/down = 4/5, left/right = 6/7.
        for steps, negative, positive in ((dy, 4, 5), (dx, 6, 7)):
            number = positive if steps > 0 else negative
            for _ in range(abs(steps)):
                self._button(number, True)
                self._button(number, False)
        self._display.flush()

    def key(self, key: str, down: bool) -> None:
        """Resolve a protocol key against the active X11 keymap."""
        from Xlib import XK
        names = {
            "ctrl": "Control_L", "alt": "Alt_L", "shift": "Shift_L", "super": "Super_L",
            "enter": "Return", "escape": "Escape", "tab": "Tab", "space": "space",
            "backspace": "BackSpace", "delete": "Delete", "insert": "Insert",
            "page_up": "Prior", "page_down": "Next",
            "volume_up": "XF86_AudioRaiseVolume", "volume_down": "XF86_AudioLowerVolume",
            "volume_mute": "XF86_AudioMute", "mic_mute": "XF86_AudioMicMute",
            "brightness_up": "XF86_MonBrightnessUp", "brightness_down": "XF86_MonBrightnessDown",
        }
        XK.load_keysym_group("xf86")
        name = names.get(key, key.capitalize() if len(key) > 1 else key)
        code = self._display.keysym_to_keycode(XK.string_to_keysym(name))
        if not code:
            raise RuntimeError(f"Key is absent from the X11 keymap: {key}")
        self._display.xtest_fake_input(self._x.KeyPress if down else self._x.KeyRelease,
                                      detail=code)
        self._display.flush()

    def text(self, text: str) -> None:
        """Type using the current keymap, temporarily allocating unused Unicode keycodes."""
        from Xlib import XK
        from Xlib.ext import xtest

        first = self._display.display.info.min_keycode
        count = self._display.display.info.max_keycode - first + 1
        mapping = self._display.get_keyboard_mapping(first, count)
        # Use only ordinary base/Shift levels. AltGr and other groups are not assumed.
        lookup = {}
        unused = []
        for code, symbols in enumerate(mapping, first):
            if not any(symbols):
                unused.append(code)
            for level, symbol in enumerate(symbols[:2]):
                if symbol:
                    lookup.setdefault(symbol, (code, level == 1))
        allocations = {}
        sequence = []
        for char in text:
            symbol = {"\n": XK.XK_Return, "\t": XK.XK_Tab}.get(char)
            if symbol is None:
                symbol = ord(char) if ord(char) <= 255 else 0x01000000 | ord(char)
            if symbol not in lookup:
                if not unused:
                    raise RuntimeError("No unused X11 keycodes available for Unicode text")
                code = unused.pop()
                allocations[code] = symbol
                lookup[symbol] = (code, False)
            sequence.append(lookup[symbol])
        shift = self._display.keysym_to_keycode(XK.XK_Shift_L)
        if any(shifted for _, shifted in sequence) and not shift:
            raise RuntimeError("Shift key is absent from the X11 keymap")
        active = self._display.query_keymap()
        modifier_codes = {code for group in self._display.get_modifier_mapping() for code in group
                          if code and active[code // 8] & (1 << (code % 8))}
        caps = self._display.keysym_to_keycode(XK.XK_Caps_Lock)
        caps_on = bool(self._display.screen().root.query_pointer().mask & self._x.LockMask)
        held = []
        released = []
        caps_toggled = False
        try:
            for code in modifier_codes:
                released.append(code)
                xtest.fake_input(self._display, self._x.KeyRelease, code)
            if caps_on and caps:
                caps_toggled = True
                xtest.fake_input(self._display, self._x.KeyPress, caps)
                xtest.fake_input(self._display, self._x.KeyRelease, caps)
            for code, symbol in allocations.items():
                self._display.change_keyboard_mapping(code, [(symbol,)])
            self._display.sync()
            if allocations:
                time.sleep(0.03)  # Let clients process MappingNotify before key events.
            for code, shifted in sequence:
                try:
                    for pressed in ((shift, code) if shifted else (code,)):
                        held.append(pressed)
                        xtest.fake_input(self._display, self._x.KeyPress, pressed)
                    self._display.sync()
                finally:
                    for pressed in held[::-1]:
                        xtest.fake_input(self._display, self._x.KeyRelease, pressed)
                        held.remove(pressed)
                    self._display.sync()
                time.sleep(0.012)
            if allocations:
                time.sleep(0.03)  # Allow applications to translate the final key events.
        finally:
            try:
                for pressed in held[::-1]:
                    xtest.fake_input(self._display, self._x.KeyRelease, pressed)
            finally:
                try:
                    for code in allocations:
                        self._display.change_keyboard_mapping(code, [mapping[code - first]])
                finally:
                    if caps_toggled:
                        xtest.fake_input(self._display, self._x.KeyPress, caps)
                        xtest.fake_input(self._display, self._x.KeyRelease, caps)
                    for code in released:
                        xtest.fake_input(self._display, self._x.KeyPress, code)
                    self._display.sync()

    def close(self) -> None:
        """Close the X11 connection; MouseSession handles button release beforehand."""
        self._display.close()


class LoggingAdapter:
    """Dry-run backend: report requested input without controlling the desktop."""

    def move(self, dx: int, dy: int) -> None:
        """Log the requested relative movement."""
        log.info("Move: %s, %s", dx, dy)

    def scroll(self, dx: int, dy: int) -> None:
        """Log the requested wheel steps."""
        log.info("Scroll: %s, %s", dx, dy)

    def button(self, button: ButtonName, down: bool) -> None:
        """Log the button name and whether it would be pressed or released."""
        log.info("Button: %s %s", button, "down" if down else "up")

    def key(self, key: str, down: bool) -> None:
        """Log an atomic chord's keyboard events."""
        log.info("Key: %s %s", key, "down" if down else "up")

    def text(self, text: str) -> None:
        """Log text length without exposing potentially sensitive content."""
        log.info("Text: %s characters", len(text))

    def close(self) -> None:
        """Do nothing because the logging backend owns no display resources."""
        pass
