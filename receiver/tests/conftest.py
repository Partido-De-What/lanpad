import pytest


class RecordingAdapter:
    def __init__(self):
        self.events = []
        self.closed = False

    def move(self, dx, dy):
        self.events.append(("move", dx, dy))

    def scroll(self, dx, dy):
        self.events.append(("scroll", dx, dy))

    def button(self, button, down):
        self.events.append(("button", button, down))

    def close(self):
        self.closed = True


@pytest.fixture
def adapter():
    return RecordingAdapter()
