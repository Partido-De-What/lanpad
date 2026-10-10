import sys
from types import SimpleNamespace

import pytest

from lanpad.adapters import X11Adapter


@pytest.fixture
def display(monkeypatch):
    calls = []
    fake = SimpleNamespace(
        has_extension=lambda name: name == "XTEST",
        xtest_get_version=lambda major, minor: calls.append(("version", major, minor)),
        xtest_fake_input=lambda event, **kwargs: calls.append((event, kwargs)),
        flush=lambda: calls.append("flush"),
        close=lambda: calls.append("close"),
    )
    monkeypatch.setitem(sys.modules, "Xlib", SimpleNamespace(
        X=SimpleNamespace(MotionNotify=6, ButtonPress=4, ButtonRelease=5),
        display=SimpleNamespace(Display=lambda: fake),
    ))
    monkeypatch.setenv("XDG_SESSION_TYPE", "x11")
    monkeypatch.setenv("DISPLAY", ":99")
    return fake, calls


def test_x11_translates_input_to_relative_motion_and_wheel_events(display):
    _, calls = display
    adapter = X11Adapter()
    adapter.move(12, -3)
    adapter.button("left", True)
    adapter.button("right", False)
    adapter.scroll(-1, 1)
    adapter.close()
    assert calls == [
        ("version", 2, 2),
        (6, {"detail": 1, "x": 12, "y": -3}), "flush",
        (4, {"detail": 1}), "flush", (5, {"detail": 3}), "flush",
        (4, {"detail": 5}), (5, {"detail": 5}),
        (4, {"detail": 6}), (5, {"detail": 6}), "flush", "close",
    ]


def test_wayland_is_rejected(display, monkeypatch):
    monkeypatch.setenv("XDG_SESSION_TYPE", "wayland")
    with pytest.raises(RuntimeError, match="X11 only"):
        X11Adapter()


def test_missing_display_is_explained(display, monkeypatch):
    monkeypatch.delenv("DISPLAY")
    with pytest.raises(RuntimeError, match="DISPLAY is unset"):
        X11Adapter()


def test_missing_xtest_closes_display(display):
    fake, calls = display
    fake.has_extension = lambda name: False
    with pytest.raises(RuntimeError, match="XTEST"):
        X11Adapter()
    assert calls == ["close"]
