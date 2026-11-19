package io.github.trufelek124.weddingplanner.planner

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.trufelek124.weddingplanner.R
import io.github.trufelek124.weddingplanner.data.Entry
import io.github.trufelek124.weddingplanner.data.Planner
import io.github.trufelek124.weddingplanner.money.money
import io.github.trufelek124.weddingplanner.ui.Meter
import java.time.LocalDate

/**
 * Where a signed-in owner lands: how long until the wedding, what is overdue, what to turn to
 * next, and the money.
 *
 * @param today the date where the wedding takes place, by the phone's clock
 * @param onBudget what pressing the glance at the money does; null where it only shows
 * @param onSuggestions what pressing the line about the suggestions that wait does; null where it only shows
 * @param foot what stands under everything: when this was loaded, who is signed in, the way out
 */
@Composable
fun HomeContent(
    planner: Planner,
    today: LocalDate,
    modifier: Modifier = Modifier,
    actions: TaskActions? = null,
    onBudget: (() -> Unit)? = null,
    onSuggestions: (() -> Unit)? = null,
    foot: @Composable () -> Unit = {},
) {
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)) {
        item(key = "hero") { Hero(planner, today) }

        // Where nothing waits Home says nothing about suggestions.
        suggestionsOnHome(planner.suggestions.size)?.let { line ->
            item(key = "suggestions") { SuggestionsWaiting(line, onSuggestions) }
        }

        if (planner.overdue.isEmpty() && planner.next.isEmpty()) {
            item(key = "nothing") { NothingHere(stringResource(R.string.home_nothing_waiting)) }
        } else {
            entries("overdue", R.string.home_overdue, planner.overdue, planner, actions)
            entries("next", R.string.home_next, planner.next, planner, actions)
        }

        if (hasMoney(planner.budget)) {
            item(key = "budget") { BudgetAtAGlance(planner, onBudget) }
        }
        if (planner.nextPayments.isNotEmpty()) {
            item(key = "payments-title") { GroupTitle(stringResource(R.string.home_next_payments)) }
            items(planner.nextPayments, key = { "payment-${it.id}" }) { payment ->
                // The same entries may stand in full under "Next" already: here each says only what it costs and when.
                TaskLine(
                    entry = payment,
                    facts = listOfNotNull(
                        waitingInWords(payment.waiting)?.let { Fact(it, strong = true) },
                        payment.amount?.let { Fact(money(it, planner.currency)) },
                        Fact(payment.dueDate?.let { "due ${shortDate(it)}" } ?: "no date"),
                    ),
                    actions = actions,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }

        item(key = "foot") { foot() }
    }
}

private fun LazyListScope.entries(group: String, title: Int, entries: List<Entry>, planner: Planner, actions: TaskActions?) {
    if (entries.isEmpty()) {
        return
    }
    item(key = "$group-title") { GroupTitle(stringResource(title)) }
    items(entries, key = { "$group-${it.id}" }) { entry ->
        EntryLine(entry, planner, actions = actions)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** One line: how many suggestions wait; pressing it leads to the inbox. */
@Composable
private fun SuggestionsWaiting(line: String, onOpen: (() -> Unit)?) {
    val open = stringResource(R.string.home_suggestions_open)
    Text(
        text = line,
        style = MaterialTheme.typography.titleMedium,
        color = if (onOpen == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(top = 16.dp)
            .then(if (onOpen == null) Modifier else Modifier.clickable(onClickLabel = open, role = Role.Button, onClick = onOpen))
            .padding(vertical = 8.dp),
    )
}

@Composable
private fun Hero(planner: Planner, today: LocalDate) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = planner.members.joinToString(" & ") { it.displayName },
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        val day = planner.weddingDate
        if (day == null) {
            Text(stringResource(R.string.wedding_day_not_fixed), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(longDate(day), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            planner.daysLeftOn(today)?.let { days ->
                Text(countdown(days), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** The sums in one sentence and one bar; pressing it opens the budget. */
@Composable
private fun BudgetAtAGlance(planner: Planner, onOpen: (() -> Unit)?) {
    val budget = planner.budget
    val open = stringResource(R.string.home_budget_open)
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = if (onOpen == null) Modifier else Modifier.clickable(onClickLabel = open, role = Role.Button, onClick = onOpen),
    ) {
        GroupTitle(stringResource(R.string.home_budget))
        Text(budgetInWords(budget, planner.currency), style = MaterialTheme.typography.bodyLarge)
        if (budget.committed > 0) {
            Meter(
                limit = limitOf(budget),
                committed = budget.committed,
                paid = budget.paid,
                inWords = meterInWords(budget, planner.currency),
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
    }
}
