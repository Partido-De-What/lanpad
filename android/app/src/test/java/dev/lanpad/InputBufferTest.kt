package dev.lanpad

import dev.lanpad.input.Command
import dev.lanpad.input.InputBuffer
import org.junit.Assert.*
import org.junit.Test

class InputBufferTest {
    @Test fun adjacentMotionMergesWithoutReorderingButtons() {
        val buffer = InputBuffer()
        buffer.offer(Command.Move(2f, 3f))
        buffer.offer(Command.Move(4f, -1f))
        buffer.offer(Command.Button("left", true))
        buffer.offer(Command.Move(1f, 1f))
        buffer.offer(Command.Button("left", false))
        assertEquals(Command.Move(6f, 2f), buffer.poll())
        assertEquals(Command.Button("left", true), buffer.poll())
        assertEquals(Command.Move(1f, 1f), buffer.poll())
        assertEquals(Command.Button("left", false), buffer.poll())
        assertNull(buffer.poll())
    }

    @Test fun movementAndScrollingStaySeparateAndBounded() {
        val buffer = InputBuffer()
        buffer.offer(Command.Move(2000f, -2000f))
        buffer.offer(Command.Move(2000f, -2000f))
        buffer.offer(Command.Scroll(8f, -8f))
        buffer.offer(Command.Scroll(8f, -8f))
        assertEquals(Command.Move(2048f, -2048f), buffer.poll())
        assertEquals(Command.Scroll(10f, -10f), buffer.poll())
    }

    @Test fun overflowIsReportedInsteadOfLosingButtonTransitions() {
        val buffer = InputBuffer(capacity = 2)
        assertTrue(buffer.offer(Command.Button("left", true)))
        assertTrue(buffer.offer(Command.Button("left", false)))
        assertFalse(buffer.offer(Command.Click("right")))
        buffer.clear()
        assertNull(buffer.poll())
        assertTrue(buffer.offer(Command.Click("right")))
    }
}
