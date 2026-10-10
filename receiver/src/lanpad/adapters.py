"""Linux X11 input and a logging adapter for development without cursor control."""

import logging
import os
import sys

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

    def close(self) -> None:
        """Do nothing because the logging backend owns no display resources."""
        pass
