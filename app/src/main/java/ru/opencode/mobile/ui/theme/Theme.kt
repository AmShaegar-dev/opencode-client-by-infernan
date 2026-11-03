package ru.opencode.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.Color

// Тёмная палитра в духе OpenCode: почти чёрный фон и янтарный акцент.
val Bg = Color(0xFF0A0A0B)
val Surface1 = Color(0xFF131316)
val Surface2 = Color(0xFF1B1B20)
val Border = Color(0xFF2A2A31)
val TextMain = Color(0xFFF2F2F5)
val TextMuted = Color(0xFF9A9AA5)
val TextDim = Color(0xFF6B6B75)
val Accent = Color(0xFFF5A524)
val AccentSoft = Color(0xFF3A2A10)
val Green = Color(0xFF4ADE80)
val Red = Color(0xFFF87171)
val Blue = Color(0xFF60A5FA)

private val colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF1A1205),
    primaryContainer = AccentSoft,
    onPrimaryContainer = Accent,
    secondary = Blue,
    onSecondary = Color(0xFF07131F),
    secondaryContainer = Surface2,
    onSecondaryContainer = TextMain,
    tertiary = Green,
    onTertiary = Color(0xFF04140A),
    background = Bg,
    onBackground = TextMain,
    surface = Surface1,
    onSurface = TextMain,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextMuted,
    surfaceContainer = Surface1,
    surfaceContainerHigh = Surface2,
    surfaceContainerHighest = Surface2,
    outline = Border,
    outlineVariant = Border,
    error = Red,
    onError = Color(0xFF1A0505)
)

@Composable
fun OpenCodeTheme(content: @Composable () -> Unit) {
    // key: не даём MaterialTheme пересоздавать своё состояние на каждом кадре.
    key(Unit) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}
