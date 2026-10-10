package dev.lanpad.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.lanpad.input.Command
import dev.lanpad.input.Finger
import dev.lanpad.input.GestureTracker

/** One-finger movement/tap and two-finger scrolling on a quiet dotted surface. */
@Composable
fun Touchpad(enabled: Boolean, onInput: (Command) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    var contact by remember { mutableStateOf<Offset?>(null) }
    val input by rememberUpdatedState(onInput)
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier.clip(shape).background(colors.surfaceContainerLow)
            .border(1.dp, colors.outlineVariant, shape)
            .semantics {
                contentDescription = "Touchpad. Move with one finger, tap to click, scroll with two fingers."
                if (!enabled) disabled()
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val tracker = GestureTracker(viewConfiguration.touchSlop, 48.dp.toPx())
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    first.consume()
                    tracker.begin(first.uptimeMillis)
                    contact = first.position
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.any { it.isConsumed }) break
                            val fingers = event.changes.map { change ->
                                val delta = change.positionChange()
                                Finger(change.id.value, change.pressed, change.previousPressed, delta.x, delta.y)
                            }
                            tracker.update(fingers, event.changes.first().uptimeMillis)?.let(input)
                            event.changes.forEach { it.consume() }
                            contact = event.changes.firstOrNull { it.pressed }?.position
                            if (event.changes.none { it.pressed }) break
                        }
                    } finally {
                        contact = null
                        tracker.cancel()
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val step = 22.dp.toPx()
            var x = step
            while (x < size.width) {
                var y = step
                while (y < size.height) {
                    drawCircle(colors.onSurfaceVariant.copy(alpha = 0.13f), 0.8.dp.toPx(), Offset(x, y))
                    y += step
                }
                x += step
            }
            contact?.let {
                drawCircle(colors.primary.copy(alpha = 0.10f), 30.dp.toPx(), it)
                drawCircle(colors.primary.copy(alpha = 0.5f), 4.dp.toPx(), it)
            }
        }
        Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PadIcon(Symbol.PAD, color = if (enabled) colors.primary else colors.onSurfaceVariant)
            Text(if (enabled) "Space to move" else "Your touchpad awaits", Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) colors.onSurface else colors.onSurfaceVariant)
            Text(if (enabled) "One finger to move · two to scroll" else "Connect your computer to begin",
                Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant)
        }
    }
}

/** A held mouse button enables dragging with another finger on the touchpad. */
@Composable
fun MouseButton(name: String, enabled: Boolean, onInput: (Command) -> Unit, modifier: Modifier = Modifier) {
    var pressed by remember { mutableStateOf(false) }
    val input by rememberUpdatedState(onInput)
    val colors = MaterialTheme.colorScheme
    val background by animateColorAsState(
        if (pressed) colors.primaryContainer else colors.surfaceContainer, label = "mouse button"
    )
    val shape = RoundedCornerShape(18.dp)
    DisposableEffect(enabled) {
        onDispose {
            if (pressed) input(Command.Button(name, false))
            pressed = false
        }
    }
    Box(
        modifier.clip(shape).background(background)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (!enabled) disabled()
                onClick(label = "${name.replaceFirstChar { it.uppercase() }} click") {
                    if (enabled) input(Command.Click(name))
                    enabled
                }
            }
            .onKeyEvent {
                if (enabled && it.type == KeyEventType.KeyUp && (it.key == Key.Enter || it.key == Key.Spacebar)) {
                    input(Command.Click(name)); true
                } else false
            }.focusable(enabled)
            .pointerInput(enabled, name) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    pressed = true
                    input(Command.Button(name, true))
                    try { waitForUpOrCancellation() } finally {
                        input(Command.Button(name, false))
                        pressed = false
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(name.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelLarge,
            color = colors.onSurface.copy(alpha = if (enabled) 1f else 0.45f))
    }
}
