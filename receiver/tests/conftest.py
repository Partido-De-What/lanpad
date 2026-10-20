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

    def key(self, key, down):
        self.events.append(("key", key, down))

    def text(self, text):
        self.events.append(("text", text))

    def close(self):
        self.closed = True


@pytest.fixture
def adapter():
    return RecordingAdapter()
