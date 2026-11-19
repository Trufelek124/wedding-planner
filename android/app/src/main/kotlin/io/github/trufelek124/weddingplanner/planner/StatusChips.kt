package io.github.trufelek124.weddingplanner.planner

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A list narrowed down by one status: "All" and the statuses that occur, as a row that scrolls
 * sideways where it does not fit. The vendors and the guests use it.
 *
 * @param all what the chip that takes the narrowing away says
 * @param onChoose null for "All"
 */
@Composable
fun <T> StatusChips(all: String, offered: List<T>, chosen: T?, label: (T) -> String, onChoose: (T?) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = chosen == null, onClick = { onChoose(null) }, label = { Text(all) })
        for (status in offered) {
            FilterChip(selected = chosen == status, onClick = { onChoose(status) }, label = { Text(label(status)) })
        }
    }
}
