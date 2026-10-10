package dev.lanpad.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// A fixed teal accent gives LanPad a consistent identity across Android versions.
private val LightColors = lightColorScheme(
    primary = Color(0xFF0D766E), onPrimary = Color.White,
    primaryContainer = Color(0xFFD9EEE8), onPrimaryContainer = Color(0xFF135A51),
    background = Color(0xFFF6F7F3), onBackground = Color(0xFF202A28),
    surface = Color(0xFFFEFFFC), onSurface = Color(0xFF202A28),
    surfaceVariant = Color(0xFFECF0EB), onSurfaceVariant = Color(0xFF586761),
    surfaceContainerLow = Color(0xFFFEFFFC), surfaceContainer = Color(0xFFF0F3EE),
    surfaceContainerHigh = Color(0xFFE8EDE7),
    outline = Color(0xFF728078), outlineVariant = Color(0xFFD7DED6),
    error = Color(0xFFAB3C32), onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8CD5C5), onPrimary = Color(0xFF103C34),
    primaryContainer = Color(0xFF1F443B), onPrimaryContainer = Color(0xFFB8EDDF),
    background = Color(0xFF111715), onBackground = Color(0xFFE4ECE6),
    surface = Color(0xFF19211D), onSurface = Color(0xFFE4ECE6),
    surfaceVariant = Color(0xFF26312B), onSurfaceVariant = Color(0xFFAABBB1),
    surfaceContainerLow = Color(0xFF19211D), surfaceContainer = Color(0xFF202923),
    surfaceContainerHigh = Color(0xFF29352D),
    outline = Color(0xFF85998C), outlineVariant = Color(0xFF35453A),
    error = Color(0xFFFFB4A8), onError = Color(0xFF5F1711),
)

private val PadTypography = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
).let { typography ->
    typography.copy(
        headlineLarge = typography.headlineLarge.copy(fontFamily = FontFamily.SansSerif),
        titleLarge = typography.titleLarge.copy(fontFamily = FontFamily.SansSerif),
    )
}

/** Follow system light/dark mode while retaining the restrained teal accent. */
@Composable
fun LanPadTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = PadTypography, content = content)
}
