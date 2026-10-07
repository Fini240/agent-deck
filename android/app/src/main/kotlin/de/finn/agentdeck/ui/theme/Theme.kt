package de.finn.agentdeck.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import de.finn.agentdeck.core.model.SessionStatus

// Neutral, structured palette. System font throughout (Material default typography).
private val Light = lightColorScheme(
    primary = Color(0xFF2B5BB8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6FA),
    onPrimaryContainer = Color(0xFF0E2A5C),
    secondary = Color(0xFF52606D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4E8EE),
    onSecondaryContainer = Color(0xFF1F2933),
    background = Color(0xFFF8F9FB),
    onBackground = Color(0xFF1A1D21),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1D21),
    surfaceVariant = Color(0xFFEEF1F5),
    onSurfaceVariant = Color(0xFF4A5560),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F8FA),
    surfaceContainer = Color(0xFFF1F3F6),
    surfaceContainerHigh = Color(0xFFEBEEF2),
    surfaceContainerHighest = Color(0xFFE5E8ED),
    outline = Color(0xFF8A96A3),
    outlineVariant = Color(0xFFD5DBE2),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9DBBF5),
    onPrimary = Color(0xFF0E2A5C),
    primaryContainer = Color(0xFF1F3F7A),
    onPrimaryContainer = Color(0xFFDCE6FA),
    secondary = Color(0xFFB5C0CC),
    onSecondary = Color(0xFF1F2933),
    secondaryContainer = Color(0xFF2E3742),
    onSecondaryContainer = Color(0xFFE4E8EE),
    background = Color(0xFF111418),
    onBackground = Color(0xFFE3E6EA),
    surface = Color(0xFF171A1F),
    onSurface = Color(0xFFE3E6EA),
    surfaceVariant = Color(0xFF242930),
    onSurfaceVariant = Color(0xFFB5BDC6),
    surfaceContainerLowest = Color(0xFF0D1014),
    surfaceContainerLow = Color(0xFF15181D),
    surfaceContainer = Color(0xFF1B1F25),
    surfaceContainerHigh = Color(0xFF22272E),
    surfaceContainerHighest = Color(0xFF2A3038),
    outline = Color(0xFF7D8894),
    outlineVariant = Color(0xFF353C45),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

/** Status colours. Always paired with a text label so meaning never depends on hue alone. */
@Immutable
data class StatusColors(
    val working: Color,
    val attention: Color,
    val error: Color,
    val done: Color,
    val neutral: Color,
) {
    fun forStatus(s: SessionStatus) = when (s) {
        SessionStatus.WORKING -> working
        SessionStatus.NEEDS_INPUT -> attention
        SessionStatus.ERROR -> error
        SessionStatus.COMPLETED -> done
        SessionStatus.IDLE, SessionStatus.UNKNOWN, SessionStatus.OFFLINE -> neutral
    }
}

private val LightStatus = StatusColors(Color(0xFF2B5BB8), Color(0xFF9A5B00), Color(0xFFB3261E), Color(0xFF2E7D32), Color(0xFF6B7682))
private val DarkStatus = StatusColors(Color(0xFF9DBBF5), Color(0xFFF2C46B), Color(0xFFF2B8B5), Color(0xFF8FD694), Color(0xFF9AA5B1))

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

@Composable
fun AgentDeckTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme: ColorScheme = if (darkTheme) Dark else Light
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides if (darkTheme) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = scheme) {
            // Root content colour so text outside a Surface is never default black in dark mode.
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides scheme.onBackground, content = content)
        }
    }
}
