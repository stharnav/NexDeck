package com.example.consolelauncher.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val ConsoleBackground = Color(0xFF0E1116)
val ConsoleSurface = Color(0xFF22262E)
val ConsoleSurfaceHigh = Color(0xFF2B303A)
val ConsoleStroke = Color(0xFF343A45)
val ConsoleAccent = Color(0xFF4FA3FF)
val ConsoleAccentSoft = Color(0xFF66C0F4)
val ConsoleTextPrimary = Color(0xFFE8EBF0)
val ConsoleTextSecondary = Color(0xFF9AA3B0)
val ConsoleOnAccent = Color(0xFF06101F)

private val ConsoleColorScheme = darkColorScheme(
    primary = ConsoleAccent,
    onPrimary = ConsoleOnAccent,
    secondary = ConsoleAccentSoft,
    onSecondary = ConsoleOnAccent,
    background = ConsoleBackground,
    onBackground = ConsoleTextPrimary,
    surface = ConsoleSurface,
    onSurface = ConsoleTextPrimary,
    surfaceVariant = ConsoleSurfaceHigh,
    onSurfaceVariant = ConsoleTextSecondary,
    outline = ConsoleStroke,
)

@Composable
fun ConsoleLauncherTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ConsoleColorScheme,
        content = content,
    )
}
