package io.github.trufelek124.weddingplanner.planner

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.trufelek124.weddingplanner.R
import io.github.trufelek124.weddingplanner.data.Entry
import io.github.trufelek124.weddingplanner.data.Kind
import io.github.trufelek124.weddingplanner.data.Planner
import io.github.trufelek124.weddingplanner.data.Status
import io.github.trufelek124.weddingplanner.data.Waiting

/**
 * What a person can do with a task from a list: tick it off, open it to change it, add one.
 * A list that only shows gets none.
 */
class TaskActions(
    val tick: (Entry, Boolean) -> Unit,
    val open: (Entry) -> Unit,
    /** @param parentId the step of the plan the new task is added to; null for none */
    val add: (parentId: String?) -> Unit,
)

/**
 * A plan item or a task in a list: its title, and under it its facts, each a few words. A screen
 * reader reads the whole line as one: the title, then the facts. A task has a box in front to
 * tick it off (for a payment: to mark it paid), and pressing the line opens it.
 *
 * @param actions null where the list only shows
 */
@Composable
fun EntryLine(entry: Entry, planner: Planner, modifier: Modifier = Modifier, withPlanItem: Boolean = true, actions: TaskActions? = null) {
    TaskLine(entry, factsOf(entry, planner, withPlanItem), modifier, actions)
}

/** A line with the facts given: for a list that says less about each task than the full line does. */
@Composable
fun TaskLine(entry: Entry, facts: List<Fact>, modifier: Modifier = Modifier, actions: TaskActions? = null) {
    val closed = !entry.status.open
    if (entry.kind != Kind.TASK || actions == null) {
        EntryLine(title = entry.title, facts = facts, closed = closed, modifier = modifier)
        return
    }
    // Ticking a payment off pays it: the box says so.
    val says = stringResource(if (entry.isPayment) R.string.tick_paid else R.string.tick_done, entry.title)
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Checkbox(
            checked = entry.status == Status.DONE,
            onCheckedChange = { actions.tick(entry, it) },
            // A task whose change met someone else's is looked at before anything more is done to it.
            enabled = entry.waiting != Waiting.CHANGED_MEANWHILE,
            modifier = Modifier.semantics { contentDescription = says },
        )
        EntryLine(
            title = entry.title,
            facts = facts,
            closed = closed,
            modifier = Modifier.weight(1f).clickable(onClickLabel = stringResource(R.string.task_open), role = Role.Button) { actions.open(entry) },
        )
    }
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
