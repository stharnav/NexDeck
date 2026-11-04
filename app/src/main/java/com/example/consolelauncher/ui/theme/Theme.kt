package com.example.consolelauncher.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color

val ConsoleBackground = Color(0xFF0E1116)
val ConsoleSurface = Color(0xFF22262E)
val ConsoleSurfaceHigh = Color(0xFF2B303A)
val ConsoleStroke = Color(0xFF343A45)
val ConsoleTextPrimary = Color(0xFFE8EBF0)
val ConsoleTextSecondary = Color(0xFF9AA3B0)
val ConsoleOnAccent = Color(0xFF06101F)

/**
 * The accent color choices offered by the THEMES tab of the SETTINGS screen. The launcher boots
 * with [BLUE]; every other option re-colors the highlights without touching the dark surfaces.
 */
enum class AccentOption(
    val id: String,
    val label: String,
    val accent: Color,
    val accentSoft: Color,
) {
    BLUE("blue", "BLUE", Color(0xFF4FA3FF), Color(0xFF66C0F4)),
    PURPLE("purple", "PURPLE", Color(0xFFB57BFF), Color(0xFFD0A3FF)),
    GREEN("green", "GREEN", Color(0xFF4ADE80), Color(0xFF86EFAC)),
    RED("red", "RED", Color(0xFFFF5C5C), Color(0xFFFFA6A6)),
    ORANGE("orange", "ORANGE", Color(0xFFFFA940), Color(0xFFFFC986)),
}

/** Holds the accent the whole UI reads through the [ConsoleAccent] getters below. */
private var accentOptionState = mutableStateOf(AccentOption.BLUE)

/**
 * The highlight color of the launcher. This is a getter over snapshot state instead of a plain
 * val so that every composable reads (and therefore observes) the live value and re-colors
 * itself the moment the user picks another accent in the settings.
 */
val ConsoleAccent: Color get() = accentOptionState.value.accent
val ConsoleAccentSoft: Color get() = accentOptionState.value.accentSoft

/** The accent option currently applied to the theme. */
fun currentAccentOption(): AccentOption = accentOptionState.value

/** Applies [option] to every screen immediately; persistence lives in [LauncherSettingsStore]. */
fun setAccentOption(option: AccentOption) {
    accentOptionState.value = option
}

/** Maps a stored accent id (or an empty / unknown one) onto its option, defaulting to blue. */
fun accentOptionById(id: String?): AccentOption =
    AccentOption.entries.firstOrNull { it.id == id } ?: AccentOption.BLUE

private fun consoleColorScheme(option: AccentOption) = darkColorScheme(
    primary = option.accent,
    onPrimary = ConsoleOnAccent,
    secondary = option.accentSoft,
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
    // Reading the state here rebuilds the whole scheme when the accent changes.
    val option = accentOptionState.value
    MaterialTheme(
        colorScheme = consoleColorScheme(option),
        content = content,
    )
}
