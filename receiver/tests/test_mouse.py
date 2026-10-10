import pytest

from lanpad.mouse import MouseSession
from lanpad.protocol import Button, Click, Move, ReleaseAll, Scroll


def test_fractional_input_is_accumulated_and_axes_stay_independent(adapter):
    session = MouseSession(adapter)
    for _ in range(4):
        session.apply(Move(0.3, -0.3))
        session.apply(Scroll(0.3, 0.3))
    assert adapter.events == [("move", 1, -1), ("scroll", 1, 1)]


def test_buttons_are_idempotent_and_taps_preserve_drags(adapter):
    session = MouseSession(adapter)
    session.apply(Button("left", True))
    session.apply(Button("left", True))
    session.apply(Click("left"))
    session.apply(Click("right"))
    session.apply(Button("middle", False))
    session.release_all()
    session.release_all()
    assert adapter.events == [
        ("button", "left", True),
        ("button", "right", True),
        ("button", "right", False),
        ("button", "left", False),
    ]
    assert not session.held


def test_release_clears_fractional_input_between_controllers(adapter):
    session = MouseSession(adapter)
    session.apply(Move(0.75, 0))
    session.apply(Scroll(0, 0.75))
    session.apply(ReleaseAll())
    session.apply(Move(0.75, 0))
    session.apply(Scroll(0, 0.75))
    assert not adapter.events


def test_failed_press_is_still_released(adapter, monkeypatch):
    session = MouseSession(adapter)
    real_button = adapter.button

    def fail_after_press(button, down):
        real_button(button, down)
        if down:
            raise OSError("X11 flush failed")

    monkeypatch.setattr(adapter, "button", fail_after_press)
    with pytest.raises(OSError):
        session.apply(Button("left", True))
    session.release_all()
    assert adapter.events == [("button", "left", True), ("button", "left", False)]


def test_failed_release_does_not_prevent_other_button_releases(adapter, monkeypatch):
    session = MouseSession(adapter)
    session.apply(Button("left", True))
    session.apply(Button("right", True))
    real_button = adapter.button

    def fail_left(button, down):
        if button == "left" and not down:
            raise OSError("Release failed")
        real_button(button, down)

    monkeypatch.setattr(adapter, "button", fail_left)
    with pytest.raises(ExceptionGroup):
        session.release_all()
    assert session.held == {"left"}
    assert adapter.events[-1] == ("button", "right", False)
    monkeypatch.setattr(adapter, "button", real_button)
    session.release_all()
    assert not session.held
