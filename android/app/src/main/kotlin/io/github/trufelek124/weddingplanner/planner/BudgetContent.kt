package io.github.trufelek124.weddingplanner.planner

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.trufelek124.weddingplanner.R
import io.github.trufelek124.weddingplanner.data.BudgetLine
import io.github.trufelek124.weddingplanner.data.Planner
import io.github.trufelek124.weddingplanner.ui.Meter

/**
 * The money: what the couple means to spend, what their payments come to, what is paid, and every
 * category against its plan. The same facts and words as the web's budget page; it is only shown
 * here, the budget is set in the web app.
 *
 * @param foot what stands under everything: when this was loaded
 */
@Composable
fun BudgetContent(planner: Planner, modifier: Modifier = Modifier, foot: @Composable () -> Unit = {}) {
    val budget = planner.budget
    val currency = planner.currency
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)) {
        if (budgetEmpty(budget)) {
            item(key = "empty") { NothingHere(stringResource(R.string.budget_empty)) }
        } else {
            val figures = figuresOf(budget, paymentsOf(planner), currency)
            item(key = "figures") { Figures(figures) }
            if (budget.committed > 0) {
                item(key = "whole") {
                    Meter(
                        limit = limitOf(budget),
                        committed = budget.committed,
                        paid = budget.paid,
                        inWords = meterInWords(budget, currency),
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
            // A copy made by the previous version of the app has no categories until it is loaded again:
            // saying "nothing planned" then would be untrue.
            if (categoriesKnown(planner)) {
                item(key = "by-category") { GroupTitle(stringResource(R.string.budget_by_category)) }
                categories(planner)
            }
        }
        item(key = "foot") { foot() }
    }
}

private fun LazyListScope.categories(planner: Planner) {
    val currency = planner.currency
    val shown = planner.budgetLines.filter(::hasLine)
    val rest = planner.uncategorised
    if (shown.isEmpty() && rest.payments == 0) {
        item(key = "nothing-in-categories") { NothingHere(stringResource(R.string.budget_nothing_in_categories)) }
    }
    items(shown.size, key = { "line-${shown[it].categoryId}" }) { at ->
        CategoryLine(shown[at], currency)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
    if (rest.payments > 0) {
        item(key = "no-category") {
            val hint = stringResource(R.string.budget_no_category_hint)
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .semantics(mergeDescendants = true) { contentDescription = restInWords(rest.committed, rest.paid, rest.payments, currency, hint) },
            ) {
                Text(stringResource(R.string.budget_no_category), style = MaterialTheme.typography.titleMedium)
                Meter(
                    limit = null,
                    committed = rest.committed,
                    paid = rest.paid,
                    inWords = meterSentence("No category", null, rest.committed, rest.paid, "planned", currency),
                )
                Text(categoryFigures(null, rest.committed, currency), style = MaterialTheme.typography.bodyLarge)
                Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "${paidInWords(rest.paid, currency)}, ${paymentCount(rest.payments)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    quietCategories(planner.budgetLines)?.let { hint ->
        item(key = "quiet") { Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp)) }
    }
}

@StringRes
private fun labelOf(name: FigureName): Int = when (name) {
    FigureName.BUDGET -> R.string.budget_figure_budget
    FigureName.PLANNED -> R.string.budget_figure_planned
    FigureName.COMMITTED -> R.string.budget_figure_committed
    FigureName.PAID -> R.string.budget_figure_paid
}

/** Four figures: two by two, or one under the other where the letters are big. */
@Composable
private fun Figures(figures: List<Figure>) {
    val big = LocalDensity.current.fontScale > 1.3f
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in figures.chunked(if (big) 1 else 2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(IntrinsicSize.Max)) {
                for (figure in row) {
                    FigureTile(figure, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun FigureTile(figure: Figure, modifier: Modifier) {
    val label = stringResource(labelOf(figure.name))
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .padding(12.dp)
            .semantics(mergeDescendants = true) { contentDescription = figure.inWords(label) },
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(figure.amount, style = MaterialTheme.typography.titleLarge)
        figure.note?.let { note ->
            // Over a limit is said in words; the colour and the weight only go with them.
            Text(
                text = note,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (figure.over) FontWeight.SemiBold else null,
                color = if (figure.over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One category: its name, a bar where it has a plan or money, and in words what it comes to and where it stands. */
@Composable
private fun CategoryLine(line: BudgetLine, currency: String) {
    val over = line.planned != null && line.committed > line.planned
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = categoryInWords(line, currency) },
    ) {
        Text(line.name, style = MaterialTheme.typography.titleMedium)
        if (hasMeter(line)) {
            Meter(
                limit = line.planned,
                committed = line.committed,
                paid = line.paid,
                inWords = meterSentence(line.name, line.planned, line.committed, line.paid, "planned", currency),
            )
        }
        Text(categoryFigures(line.planned, line.committed, currency), style = MaterialTheme.typography.bodyLarge)
        Text(
            text = planState(line.planned, line.committed, currency),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (over) FontWeight.SemiBold else null,
            color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "${paidInWords(line.paid, currency)}, ${paymentsInWords(line.payments)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
