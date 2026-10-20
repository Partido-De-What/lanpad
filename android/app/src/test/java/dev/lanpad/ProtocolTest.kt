package dev.lanpad

import dev.lanpad.input.Command
import dev.lanpad.input.toJson
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @Test fun commandEncodingMatchesReceiverVersionThree() {
        val commands = listOf(
            Command.Move(1.25f, -2f) to "move",
            Command.Scroll(0f, 0.5f) to "scroll",
            Command.Button("left", true) to "button",
            Command.Click("right") to "click",
            Command.Key("enter", listOf("super", "shift")) to "key",
            Command.Text("Caffè 😀\n") to "text",
            Command.ReleaseAll to "release_all",
            Command.Heartbeat to "heartbeat",
        )
        for ((command, kind) in commands) {
            val json = JSONObject(command.toJson())
            assertEquals(kind, json.getString("type"))
            assertTrue(json.length() in 1..3)
            if (command is Command.Move) assertEquals(1.25, json.getDouble("dx"), 0.0001)
            if (command is Command.Key) {
                assertEquals("enter", json.getString("key"))
                assertEquals("super", json.getJSONArray("modifiers").getString(0))
            }
            if (command is Command.Text) assertEquals(command.text, json.getString("text"))
            if (command is Command.Button) assertTrue(json.getBoolean("down"))
        }
    }
}
