package io.github.trufelek124.weddingplanner.planner

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.trufelek124.weddingplanner.R
import io.github.trufelek124.weddingplanner.data.Entry
import io.github.trufelek124.weddingplanner.data.Planner
import io.github.trufelek124.weddingplanner.planner.timeline.TimelineContent
import java.time.LocalDate

/** The two ways to narrow a list down: what is still to do, or everything. */
@Composable
private fun WhichOnes(
    everything: Boolean,
    onEverything: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    more: @Composable () -> Unit = {},
) {
    Row(modifier = modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !everything, onClick = { onEverything(false) }, label = { Text(stringResource(R.string.filter_to_do)) })
        FilterChip(selected = everything, onClick = { onEverything(true) }, label = { Text(stringResource(R.string.filter_everything)) })
        more()
    }
}

/** The two ways to look at the plan. Switching keeps what the list was narrowed down to. */
@Composable
private fun WaysToLook(asTimeline: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        SegmentedButton(
            selected = !asTimeline,
            onClick = { onChange(false) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            label = { Text(stringResource(R.string.view_list)) },
        )
        SegmentedButton(
            selected = asTimeline,
            onClick = { onChange(true) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            label = { Text(stringResource(R.string.view_timeline)) },
        )
    }
}

/**
 * Everything that has to happen, step by step, as a list or on a timeline. The list has the steps
 * with dates first, then those not yet scheduled; a step says how many tasks belong to it and
 * shows them when asked.
 *
 * @param today the date where the wedding takes place, by the phone's clock
 */
@Composable
fun PlanContent(
    planner: Planner,
    today: LocalDate,
    modifier: Modifier = Modifier,
    actions: TaskActions? = null,
    foot: @Composable () -> Unit = {},
) {
    var everything by rememberSaveable { mutableStateOf(false) }
    var asTimeline by rememberSaveable { mutableStateOf(false) }
    val all = planner.planItems
    val shown = if (everything) all else all.filter { it.status.open }
    val controls: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            WaysToLook(asTimeline, { asTimeline = it })
            WhichOnes(everything, { everything = it })
        }
    }

    if (asTimeline) {
        TimelineContent(items = shown, today = today, weddingDate = planner.weddingDate, modifier = modifier, controls = controls, foot = foot)
        return
    }

    val scheduled = shown.filter { it.dueDate != null }
    val notScheduled = shown.filter { it.dueDate == null }
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        item(key = "controls") { controls() }
        if (shown.isEmpty()) {
            item(key = "nothing") {
                NothingHere(stringResource(if (all.isEmpty()) R.string.plan_empty else R.string.plan_nothing_to_do))
            }
        }
        if (scheduled.isNotEmpty()) {
            item(key = "scheduled") { GroupTitle(stringResource(R.string.plan_scheduled)) }
            items(scheduled, key = { it.id }) { item -> PlanItem(item, planner, actions) }
        }
        if (notScheduled.isNotEmpty()) {
            item(key = "not-scheduled") { GroupTitle(stringResource(R.string.plan_not_scheduled)) }
            items(notScheduled, key = { it.id }) { item -> PlanItem(item, planner, actions) }
        }
        item(key = "foot") { foot() }
    }
}

@Composable
private fun PlanItem(item: Entry, planner: Planner, actions: TaskActions?) {
    val tasks = planner.tasksOf(item)
    var open by rememberSaveable(item.id) { mutableStateOf(false) }
    Column {
        EntryLine(item, planner)
        if (tasks.isNotEmpty()) {
            val shown = stringResource(R.string.tasks_shown)
            val hidden = stringResource(R.string.tasks_hidden)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClickLabel = stringResource(if (open) R.string.tasks_hide else R.string.tasks_show)) { open = !open }
                    .semantics { stateDescription = if (open) shown else hidden },
            ) {
                Text(
                    text = (if (open) "▾ " else "▸ ") + tasksInWords(tasks),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (open) {
                Column(modifier = Modifier.padding(start = 16.dp)) {
                    for (task in tasks) {
                        EntryLine(task, planner, withPlanItem = false, actions = actions)
                    }
                }
            }
        }
        if (actions != null && item.status.open) {
            TextButton(onClick = { actions.add(item.id) }) { Text(stringResource(R.string.task_add_to_this)) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** Single things to do, with or without a date, in the server's order: by date, those without one last. */
@Composable
fun TasksContent(planner: Planner, modifier: Modifier = Modifier, actions: TaskActions? = null, foot: @Composable () -> Unit = {}) {
    var everything by rememberSaveable { mutableStateOf(false) }
    var paymentsOnly by rememberSaveable { mutableStateOf(false) }
    val all = planner.tasks
    val shown = all.filter { (everything || it.status.open) && (!paymentsOnly || it.isPayment) }

    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        item(key = "which") {
            WhichOnes(everything, { everything = it }) {
                FilterChip(
                    selected = paymentsOnly,
                    onClick = { paymentsOnly = !paymentsOnly },
                    label = { Text(stringResource(R.string.filter_payments)) },
                )
            }
        }
        if (shown.isEmpty()) {
            item(key = "nothing") {
                NothingHere(
                    stringResource(
                        when {
                            all.isEmpty() -> R.string.tasks_empty
                            paymentsOnly -> R.string.tasks_no_payments
                            else -> R.string.tasks_nothing_to_do
                        },
                    ),
                )
            }
        }
        items(shown, key = { it.id }) { task ->
            EntryLine(task, planner, actions = actions)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        item(key = "foot") { foot() }
    }
}
