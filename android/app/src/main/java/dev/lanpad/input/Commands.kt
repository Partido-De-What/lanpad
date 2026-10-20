package dev.lanpad.input

import org.json.JSONObject
import org.json.JSONArray

/** Typed commands mirror the receiver's protocol; UI code never builds raw JSON. */
sealed interface Command {
    data class Move(val dx: Float, val dy: Float) : Command
    data class Scroll(val dx: Float, val dy: Float) : Command
    data class Button(val name: String, val down: Boolean) : Command
    data class Click(val name: String) : Command
    data class Key(val key: String, val modifiers: List<String> = emptyList()) : Command
    data class Text(val text: String) : Command
    data object ReleaseAll : Command
    data object Heartbeat : Command
}

/** Encode exactly the input fields accepted by receiver protocol version 4. */
fun Command.toJson(): String = JSONObject().apply {
    when (val command = this@toJson) {
        is Command.Move -> { put("type", "move"); put("dx", command.dx); put("dy", command.dy) }
        is Command.Scroll -> { put("type", "scroll"); put("dx", command.dx); put("dy", command.dy) }
        is Command.Button -> {
            put("type", "button"); put("button", command.name); put("down", command.down)
        }
        is Command.Click -> { put("type", "click"); put("button", command.name) }
        is Command.Key -> { put("type", "key"); put("key", command.key); put("modifiers", JSONArray(command.modifiers)) }
        is Command.Text -> { put("type", "text"); put("text", command.text) }
        Command.ReleaseAll -> put("type", "release_all")
        Command.Heartbeat -> put("type", "heartbeat")
    }
}.toString()

/** Coalesce adjacent motion while preserving its order relative to button events. */
class InputBuffer(private val capacity: Int = 64) {
    private val queue = ArrayDeque<Command>()

    @Synchronized
    fun offer(command: Command): Boolean {
        val previous = queue.lastOrNull()
        val merged = when {
            previous is Command.Move && command is Command.Move -> Command.Move(
                (previous.dx + command.dx).coerceIn(-2048f, 2048f),
                (previous.dy + command.dy).coerceIn(-2048f, 2048f),
            )
            previous is Command.Scroll && command is Command.Scroll -> Command.Scroll(
                (previous.dx + command.dx).coerceIn(-10f, 10f),
                (previous.dy + command.dy).coerceIn(-10f, 10f),
            )
            else -> null
        }
        if (merged != null) {
            queue.removeLast()
            queue.addLast(merged)
        } else {
            if (queue.size >= capacity) return false
            queue.addLast(command)
        }
        return true
    }

    /** Take the next event; one background sender owns consumption. */
    @Synchronized
    fun poll(): Command? = queue.removeFirstOrNull()

    /** Discard stale gestures before a new session or an emergency release. */
    @Synchronized
    fun clear() = queue.clear()
}
