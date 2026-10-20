package dev.lanpad.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size

enum class Symbol { PAD, KEYBOARD, SETTINGS, ARROW, STOP }

/** Small vector marks drawn in code; their enclosing controls provide accessible labels. */
@Composable
fun PadIcon(symbol: Symbol, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Canvas(modifier.size(24.dp)) {
        val unit = size.width / 24f
        fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
        fun line(x: Float, y: Float, endX: Float, endY: Float) {
            drawLine(color, point(x, y), point(endX, endY), 1.6f * unit, StrokeCap.Round)
        }
        when (symbol) {
            Symbol.PAD -> {
                drawRoundRect(color, point(3f, 4f), Size(18f * unit, 16f * unit),
                    androidx.compose.ui.geometry.CornerRadius(3f * unit), style = Stroke(1.6f * unit))
                line(3f, 15f, 21f, 15f)
                line(12f, 15f, 12f, 20f)
            }
            Symbol.KEYBOARD -> {
                drawRoundRect(color, point(2f, 5f), Size(20f * unit, 14f * unit),
                    androidx.compose.ui.geometry.CornerRadius(2f * unit), style = Stroke(1.6f * unit))
                for (y in listOf(9f, 12f)) for (x in listOf(6f, 10f, 14f, 18f)) {
                    line(x, y, x + 0.5f, y)
                }
                line(8f, 16f, 16f, 16f)
            }
            Symbol.SETTINGS -> {
                for ((y, x) in listOf(6f to 9f, 12f to 16f, 18f to 7f)) {
                    line(3f, y, x - 3, y)
                    line(x + 3, y, 21f, y)
                    drawCircle(color, 2.5f * unit, point(x, y), style = Stroke(1.6f * unit))
                }
            }
            Symbol.ARROW -> {
                line(4f, 12f, 20f, 12f)
                line(14f, 6f, 20f, 12f)
                line(14f, 18f, 20f, 12f)
            }
            Symbol.STOP -> drawRoundRect(color, point(6f, 6f), Size(12f * unit, 12f * unit),
                androidx.compose.ui.geometry.CornerRadius(2f * unit))
        }
    }
}
