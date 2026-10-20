"""Mouse state is independent of networking and the operating system."""

import math
from typing import Protocol

from .protocol import (
    Button,
    ButtonName,
    Click,
    Command,
    Heartbeat,
    Key,
    Move,
    ReleaseAll,
    Scroll,
    Text,
)


class InputAdapter(Protocol):
    """Methods an input backend must provide; implementations need not inherit this."""

    def move(self, dx: int, dy: int) -> None:
        """Apply an integer relative pointer movement."""
        ...

    def scroll(self, dx: int, dy: int) -> None:
        """Apply integer horizontal and vertical wheel steps."""
        ...

    def button(self, button: ButtonName, down: bool) -> None:
        """Inject a button press or release."""
        ...

    def key(self, key: str, down: bool) -> None:
        """Inject a named key press or release."""
        ...

    def text(self, text: str) -> None:
        """Type Unicode text."""
        ...

    def close(self) -> None:
        """Release the backend's resources after held buttons have been released."""
        ...


class MouseSession:
    """Translate commands into backend calls while tracking one controller's state."""

    def __init__(self, adapter: InputAdapter) -> None:
        """Attach the backend and start with no held buttons or fractional input."""
        self.adapter = adapter
        self.held: set[ButtonName] = set()
        self.held_keys: list[str] = []
        self._remainders = {Move: (0.0, 0.0), Scroll: (0.0, 0.0)}

    def apply(self, command: Command) -> None:
        """Dispatch one validated command and update mouse state."""
        # Pattern matching selects the command class and unpacks its fields.
        match command:
            case Move(dx, dy) | Scroll(dx, dy):
                kind = type(command)
                rx, ry = self._remainders[kind]
                x, y = dx + rx, dy + ry
                # The backend accepts whole pixels/steps. Save each leftover
                # fraction so repeated small gestures eventually produce input.
                ix, iy = math.trunc(x), math.trunc(y)
                self._remainders[kind] = (x - ix, y - iy)
                if ix or iy:
                    if kind is Move:
                        self.adapter.move(ix, iy)
                    else:
                        self.adapter.scroll(ix, iy)
            case Button(button, down):
                self._button(button, down)
            case Click(button):
                # A tap during a drag must not release that drag's button.
                if button not in self.held:
                    self._button(button, True)
                    self._button(button, False)
            case Key(key, modifiers):
                try:
                    for name in (*modifiers, key):
                        self.held_keys.append(name)
                        self.adapter.key(name, True)
                finally:
                    self._release_keys()
            case Text(text):
                self.adapter.text(text)
            case ReleaseAll():
                self.release_all()
            case Heartbeat():
                # Receiving this message refreshes the receiver's timeout;
                # it has no mouse effect.
                pass

    def _button(self, button: ButtonName, down: bool) -> None:
        """Change a button's state once; repeated presses or releases do nothing."""
        if down and button not in self.held:
            # Track before injection so a failed flush still triggers cleanup.
            self.held.add(button)
            self.adapter.button(button, True)
        elif not down and button in self.held:
            self.adapter.button(button, False)
            self.held.remove(button)

    def _release_keys(self) -> None:
        failures = []
        for key in self.held_keys[::-1]:
            try:
                self.adapter.key(key, False)
                self.held_keys.remove(key)
            except Exception as exc:
                failures.append(exc)
        if failures:
            raise ExceptionGroup("Could not release keyboard keys", failures)

    def release_all(self) -> None:
        """Release held buttons and clear fractions, collecting any backend failures."""
        failures = []
        # Continue after a failure so one stuck button does not prevent attempts
        # to release the others. Failed releases remain tracked for a later retry.
        for button in sorted(self.held):
            try:
                self._button(button, False)
            except Exception as exc:
                failures.append(exc)
        try:
            self._release_keys()
        except Exception as exc:
            failures.append(exc)
        self._remainders = {Move: (0.0, 0.0), Scroll: (0.0, 0.0)}
        if failures:
            raise ExceptionGroup("Could not release mouse buttons", failures)
