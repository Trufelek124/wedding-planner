package io.github.trufelek124.weddingplanner.planner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.trufelek124.weddingplanner.data.Entry
import io.github.trufelek124.weddingplanner.data.Planner

/**
 * A plan item or a task in a list: its title, and under it its facts, each a few words. A screen
 * reader reads the whole line as one: the title, then the facts.
 */
@Composable
fun EntryLine(entry: Entry, planner: Planner, modifier: Modifier = Modifier, withPlanItem: Boolean = true) {
    EntryLine(title = entry.title, facts = factsOf(entry, planner, withPlanItem), closed = !entry.status.open, modifier = modifier)
}

/** @param closed done or dropped: the title is struck through, and the facts say which */
@Composable
fun EntryLine(title: String, facts: List<Fact>, closed: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = if (closed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            textDecoration = if (closed) TextDecoration.LineThrough else null,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (fact in facts) {
                Text(
                    text = fact.text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (fact.strong) FontWeight.SemiBold else null,
                    color = if (fact.strong) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The name of a group of lines: "Overdue", "Scheduled". */
@Composable
fun GroupTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 20.dp, bottom = 4.dp).semantics { heading() },
    )
}

/** What a list says when it has nothing to show. */
@Composable
fun NothingHere(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(vertical = 24.dp),
    )
}
