package io.github.trufelek124.weddingplanner.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A button for what is not the main thing to do on a screen. Its outline is drawn in a colour that
 * stands out from the background, and it is tall enough for a finger.
 */
@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = if (enabled) 1f else 0.4f)),
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        Text(text)
    }
}
