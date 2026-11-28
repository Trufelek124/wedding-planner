package io.github.trufelek124.weddingplanner.planner

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.trufelek124.weddingplanner.R
import io.github.trufelek124.weddingplanner.data.Planner

/** Where More leads to: a title, one line on what is there, and what happens when it is pressed. */
private class MoreRow(val key: String, @StringRes val title: Int, val summary: String, val open: () -> Unit)

/**
 * What does not have a place of its own in the bar below: a short list, each row one button.
 * A place that comes later is one more row in [rows].
 *
 * @param onReminders leads to the reminders; null where there is no model for them
 * @param foot what stands under the list: when this was loaded, who is signed in, the way out
 */
@Composable
fun MoreContent(planner: Planner, onBudget: () -> Unit, onVendors: () -> Unit, onGuests: () -> Unit, onInspirations: () -> Unit, onSuggestions: () -> Unit, modifier: Modifier = Modifier, onReminders: (() -> Unit)? = null, remindersSummary: String = "", foot: @Composable () -> Unit = {}) {
    val rows = listOf(
        MoreRow("budget", R.string.more_budget, budgetSummary(planner.budget, planner.currency), onBudget),
        MoreRow("vendors", R.string.more_vendors, vendorsSummary(planner.vendorList), onVendors),
        MoreRow("guests", R.string.more_guests, guestsSummary(planner.guestCounts, planner.guests), onGuests),
        MoreRow("inspirations", R.string.more_inspirations, inspirationsSummary(planner.inspirations, planner.shares.count { it.trouble == null }), onInspirations),
        MoreRow("suggestions", R.string.more_suggestions, suggestionsOnMore(planner.suggestionsToDecide, planner.addedToLookAt), onSuggestions),
    ) + listOfNotNull(onReminders?.let { MoreRow("reminders", R.string.more_reminders, remindersSummary, it) })
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        items(rows, key = { it.key }) { row ->
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClick = row.open)
                    .padding(vertical = 14.dp)
                    // One statement for a screen reader: the title, then what is there.
                    .semantics(mergeDescendants = true) {},
            ) {
                Text(stringResource(row.title), style = MaterialTheme.typography.titleMedium)
                Text(row.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        item(key = "foot") { Column(modifier = Modifier.padding(top = 8.dp)) { foot() } }
    }
}
