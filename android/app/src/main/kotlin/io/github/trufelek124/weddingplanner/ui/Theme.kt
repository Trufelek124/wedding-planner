package io.github.trufelek124.weddingplanner.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The planner's colours, the same as the web app's tokens (web/src/index.css), for light and dark.
private val Light = lightColorScheme(
    primary = Color(0xFF2F5D50),
    onPrimary = Color(0xFFFFFFFF),
    // What is chosen at the moment: a chip, the place in the bar at the bottom.
    secondary = Color(0xFF2F5D50),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD8E8E2),
    onSecondaryContainer = Color(0xFF12332A),
    // A second accent, for the wedding day on the timeline.
    tertiary = Color(0xFF9A4F2B),
    onTertiary = Color(0xFFFFFFFF),
    surfaceTint = Color(0xFF2F5D50),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF4EEE6),
    surfaceContainerHighest = Color(0xFFEFE7DD),
    background = Color(0xFFFAF6F1),
    onBackground = Color(0xFF2B2622),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF2B2622),
    surfaceVariant = Color(0xFFFAF6F1),
    onSurfaceVariant = Color(0xFF675E55),
    outline = Color(0xFF8A7F74),
    outlineVariant = Color(0xFFE3D9CD),
    error = Color(0xFFA32920),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFBECEB),
    onErrorContainer = Color(0xFFA32920),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9AD0BD),
    onPrimary = Color(0xFF10231D),
    secondary = Color(0xFF9AD0BD),
    onSecondary = Color(0xFF10231D),
    secondaryContainer = Color(0xFF2C4A41),
    onSecondaryContainer = Color(0xFFD3EDE4),
    tertiary = Color(0xFFF2B8A0),
    onTertiary = Color(0xFF3A1D10),
    surfaceTint = Color(0xFF9AD0BD),
    surfaceContainerLowest = Color(0xFF171513),
    surfaceContainerLow = Color(0xFF211E1B),
    surfaceContainer = Color(0xFF211E1B),
    surfaceContainerHigh = Color(0xFF2A2622),
    surfaceContainerHighest = Color(0xFF332E29),
    background = Color(0xFF171513),
    onBackground = Color(0xFFF1ECE6),
    surface = Color(0xFF211E1B),
    onSurface = Color(0xFFF1ECE6),
    surfaceVariant = Color(0xFF171513),
    onSurfaceVariant = Color(0xFFB8AEA3),
    outline = Color(0xFF8A7F74),
    outlineVariant = Color(0xFF3A342E),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF3B1A17),
    errorContainer = Color(0xFF3B1A17),
    onErrorContainer = Color(0xFFFFB4AB),
)

@Composable
fun PlannerTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
