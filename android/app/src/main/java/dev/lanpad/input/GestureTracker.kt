package dev.lanpad.input

import kotlin.math.hypot

data class Finger(
    val id: Long,
    val pressed: Boolean,
    val previouslyPressed: Boolean,
    val dx: Float,
    val dy: Float,
)

/** Pure gesture logic, so taps and two-finger transitions can be tested without Compose. */
class GestureTracker(private val touchSlop: Float, private val scrollPixelsPerStep: Float) {
    private var started = 0L
    private var distance = 0f
    private var usedMultipleFingers = false
    private var previousCount = 1

    fun begin(time: Long) {
        started = time
        distance = 0f
        usedMultipleFingers = false
        previousCount = 1
    }

    /** Emit relative motion for one finger and centroid scrolling for two or more. */
    fun update(fingers: List<Finger>, time: Long): Command? {
        val down = fingers.filter { it.pressed }
        if (down.isEmpty()) {
            val released = fingers.firstOrNull { it.previouslyPressed }
            val dx = released?.dx ?: 0f
            val dy = released?.dy ?: 0f
            distance += hypot(dx, dy)
            val click = !usedMultipleFingers && distance <= touchSlop && time - started <= 250
            val finalMove = !usedMultipleFingers && (dx != 0f || dy != 0f)
            cancel()
            return when {
                click -> Command.Click("left")
                finalMove -> Command.Move(dx, dy)
                else -> null
            }
        }
        val count = down.size
        if (count >= 2) usedMultipleFingers = true
        // Ignore the transition event: newly added fingers have no useful delta.
        // Once scrolling begins, the remaining finger cannot suddenly move the cursor.
        val changedCount = count != previousCount
        previousCount = count
        if (changedCount) return null
        if (count == 1 && !usedMultipleFingers) {
            val finger = down.first()
            distance += hypot(finger.dx, finger.dy)
            return if (finger.dx != 0f || finger.dy != 0f) Command.Move(finger.dx, finger.dy) else null
        }
        if (count >= 2 && down.all { it.previouslyPressed }) {
            val dx = down.sumOf { it.dx.toDouble() }.toFloat() / count
            val dy = down.sumOf { it.dy.toDouble() }.toFloat() / count
            return if (dx != 0f || dy != 0f) {
                // Natural scrolling: fingers up scroll the content down.
                Command.Scroll(-dx / scrollPixelsPerStep, -dy / scrollPixelsPerStep)
            } else null
        }
        return null
    }

    /** Cancellation discards tap eligibility and never produces a click. */
    fun cancel() {
        usedMultipleFingers = true
        distance = 0f
        previousCount = 0
    }
}
