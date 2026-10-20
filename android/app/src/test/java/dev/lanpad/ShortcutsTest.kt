package dev.lanpad

import dev.lanpad.input.Command
import dev.lanpad.input.i3Shortcuts
import org.junit.Assert.assertEquals
import org.junit.Test

class ShortcutsTest {
    @Test fun personalOverridesMatchTheConfiguredI3Bindings() {
        val shortcuts = i3Shortcuts.values.flatten().associateBy { it.label }
        assertEquals(Command.Key("o", listOf("super")), shortcuts.getValue("Split horizontal").command)
        assertEquals(Command.Key("t", listOf("super", "shift")), shortcuts.getValue("Float / tile").command)
        assertEquals(Command.Key("f", listOf("super", "shift")), shortcuts.getValue("Files").command)
        assertEquals(Command.Key("enter", listOf("super")), shortcuts.getValue("Terminal").command)
        assertEquals(Command.Key("a", listOf("super", "shift")), shortcuts.getValue("Mixer").command)
    }
}
