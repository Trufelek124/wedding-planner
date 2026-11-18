package io.github.trufelek124.weddingplanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp

/**
 * A thin bar of money: what is paid, what is still to pay, and what goes beyond the limit, on a
 * track that stands for the limit. The words beside it say the same; the bar is never the only
 * way to learn the amounts, and a screen reader is told [inWords] instead.
 *
 * @param limit what the amounts are measured against; null when nothing was planned
 */
@Composable
fun Meter(limit: Long?, committed: Long, paid: Long, inWords: String, modifier: Modifier = Modifier) {
    val whole = maxOf(limit ?: 0, committed)
    val within = if (limit == null) committed else minOf(committed, limit)
    val paidWithin = minOf(paid, within)
    val colours = MaterialTheme.colorScheme
    val parts = listOf(
        paidWithin to colours.primary,
        within - paidWithin to colours.primary.copy(alpha = 0.45f),
        committed - within to colours.error,
        whole - committed to Color.Transparent,
    ).filter { it.first > 0 }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(colours.outlineVariant)
            .clearAndSetSemantics { contentDescription = inWords },
    ) {
        for ((size, colour) in parts) {
            Box(Modifier.weight(size.toFloat()).fillMaxHeight().background(colour))
        }
    }
}
