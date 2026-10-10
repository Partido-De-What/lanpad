package dev.lanpad

import dev.lanpad.input.Command
import dev.lanpad.input.Finger
import dev.lanpad.input.GestureTracker
import org.junit.Assert.*
import org.junit.Test

class GestureTrackerTest {
    private fun finger(id: Long = 1, dx: Float = 0f, dy: Float = 0f) =
        Finger(id, pressed = true, previouslyPressed = true, dx = dx, dy = dy)

    @Test fun shortStationaryTouchIsALeftClick() {
        val gestures = GestureTracker(8f, 48f)
        gestures.begin(100)
        assertNull(gestures.update(listOf(finger()), 150))
        assertEquals(Command.Click("left"), gestures.update(emptyList(), 200))
    }

    @Test fun movementAndLongPressDoNotProduceClicks() {
        val gestures = GestureTracker(8f, 48f)
        gestures.begin(100)
        assertEquals(Command.Move(20f, -4f), gestures.update(listOf(finger(dx = 20f, dy = -4f)), 120))
        assertNull(gestures.update(emptyList(), 180))
        gestures.begin(200)
        assertNull(gestures.update(emptyList(), 600))
    }

    @Test fun twoFingerScrollNeverTurnsIntoMovementOrATap() {
        val gestures = GestureTracker(8f, 48f)
        gestures.begin(100)
        assertNull(gestures.update(listOf(finger(1), finger(2)), 120))
        val scroll = gestures.update(
            listOf(finger(1, dy = -12f), finger(2, dy = -12f)), 140) as Command.Scroll
        // Natural scrolling can negate zero; compare axis values numerically.
        assertEquals(0f, scroll.dx, 0.0001f)
        assertEquals(0.25f, scroll.dy, 0.0001f)
        assertNull(gestures.update(listOf(finger(dx = 50f)), 160))
        assertNull(gestures.update(listOf(finger(dx = 50f)), 180))
        assertNull(gestures.update(emptyList(), 200))
    }

    @Test fun cancellingAGestureNeverClicks() {
        val gestures = GestureTracker(8f, 48f)
        gestures.begin(100)
        gestures.cancel()
        assertNull(gestures.update(emptyList(), 150))
    }

    @Test fun movementReportedOnlyOnLiftDoesNotBecomeAnAccidentalClick() {
        val gestures = GestureTracker(8f, 48f)
        gestures.begin(100)
        assertEquals(Command.Move(30f, 0f), gestures.update(
            listOf(Finger(1, pressed = false, previouslyPressed = true, dx = 30f, dy = 0f)), 150))
    }
}
