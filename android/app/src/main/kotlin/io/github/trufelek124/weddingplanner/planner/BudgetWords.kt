package io.github.trufelek124.weddingplanner.planner

import io.github.trufelek124.weddingplanner.data.Budget
import io.github.trufelek124.weddingplanner.data.BudgetLine
import io.github.trufelek124.weddingplanner.data.Planner
import io.github.trufelek124.weddingplanner.money.money

/*
 * How the budget reads on the phone: the same words as the web's budget page
 * (web/src/budget/BudgetPage.tsx, Meter.tsx). Each function says what one place on the screen says,
 * so that the words are tested without a screen.
 */

/** "1 payment", "6 payments" */
fun paymentCount(payments: Int): String = if (payments == 1) "1 payment" else "$payments payments"

/**
 * What a copy of the planner knows of the categories. A copy made by the previous version of the
 * app holds the totals but no category lines until it is loaded again: money without a single
 * payment filed anywhere cannot be, so that is how such a copy is told.
 */
fun categoriesKnown(planner: Planner): Boolean =
    planner.budgetLines.isNotEmpty() || planner.uncategorised.payments > 0 || planner.budget.committed == 0L

/** The payments of the whole budget; null where the copy does not know the categories. */
fun paymentsOf(planner: Planner): Int? =
    if (categoriesKnown(planner)) planner.budgetLines.sumOf { it.payments } + planner.uncategorised.payments else null

/** The one line under "Budget" in the list of what More leads to. */
fun budgetSummary(budget: Budget, currency: String): String = if (hasMoney(budget)) budgetInWords(budget, currency) else "Not set yet."

/** Nothing is set and nothing is paid: the page invites instead of showing zeros. */
fun budgetEmpty(budget: Budget): Boolean = !hasMoney(budget)

/** The four figures of the page. */
enum class FigureName { BUDGET, PLANNED, COMMITTED, PAID }

/**
 * One figure: its amount, and the sentence under it, if there is one.
 *
 * @param over the sentence is about going beyond a limit: the screen marks it, besides the words
 */
class Figure(val name: FigureName, val amount: String, val note: String?, val over: Boolean = false)

/** @param payments how many payments there are; null where that is not known */
fun figuresOf(budget: Budget, payments: Int?, currency: String): List<Figure> {
    val total = budget.total
    val toPay = budget.committed - budget.paid
    return listOf(
        Figure(
            FigureName.BUDGET,
            amount = if (total == null) "Not set" else money(total, currency),
            note = when {
                total == null -> null
                budget.committed > total -> "Over by ${money(budget.committed - total, currency)}"
                else -> "${money(total - budget.committed, currency)} not committed yet"
            },
            over = total != null && budget.committed > total,
        ),
        Figure(
            FigureName.PLANNED,
            amount = money(budget.planned, currency),
            note = when {
                total == null || budget.planned == 0L || budget.planned == total -> null
                budget.planned > total -> "${money(budget.planned - total, currency)} more than the budget"
                else -> "${money(total - budget.planned, currency)} of the budget not planned"
            },
            over = total != null && budget.planned > total,
        ),
        Figure(
            FigureName.COMMITTED,
            amount = money(budget.committed, currency),
            note = when (payments) {
                null -> null
                0 -> "No payments yet"
                else -> paymentCount(payments)
            },
        ),
        Figure(
            FigureName.PAID,
            amount = money(budget.paid, currency),
            note = if (toPay == 0L) "Nothing left to pay" else "${money(toPay, currency)} still to pay",
        ),
    )
}

/** What a screen reader is told of a figure: "Budget: 60,000.00 zł. 33,000.00 zł not committed yet". */
fun Figure.inWords(label: String): String = if (note == null) "$label: $amount" else "$label: $amount. $note"

/**
 * What a bar says in words, which is also what a screen reader is told instead of it.
 *
 * @param about what it is about: "The whole budget", "Venue"
 * @param limit what the amounts are measured against; null where nothing is planned
 * @param limitName what the limit is called in a sentence: "budget", "planned"
 */
fun meterSentence(about: String, limit: Long?, committed: Long, paid: Long, limitName: String, currency: String): String {
    val spent = "${money(paid, currency)} paid, ${money(committed - paid, currency)} still to pay"
    return when {
        limit == null -> "$about: $spent"
        committed > limit -> "$about: $spent: ${money(committed - limit, currency)} more than the ${money(limit, currency)} $limitName"
        else -> "$about: $spent, of ${money(limit, currency)} $limitName"
    }
}

/** A category that has a plan or a payment gets a line of its own; the rest are only named. */
fun hasLine(line: BudgetLine): Boolean = line.planned != null || line.payments > 0

/** "Nothing planned or paid yet for: Music, Flowers." — null when every category has a line, or none has. */
fun quietCategories(lines: List<BudgetLine>): String? {
    val quiet = lines.filterNot(::hasLine)
    return if (quiet.isEmpty() || quiet.size == lines.size) null else "Nothing planned or paid yet for: ${quiet.joinToString(", ") { it.name }}."
}

/** Where a category stands against its plan, in words. Over the plan is only ever said in words first. */
fun planState(planned: Long?, committed: Long, currency: String): String = when {
    planned == null -> "No amount planned"
    committed > planned -> "Over the plan by ${money(committed - planned, currency)}"
    committed == planned -> "Exactly as planned"
    else -> "${money(planned - committed, currency)} left"
}

/** "6,200.00 zł of 5,000.00 zł", or "800.00 zł committed" where nothing is planned. */
fun categoryFigures(planned: Long?, committed: Long, currency: String): String =
    if (planned == null) "${money(committed, currency)} committed" else "${money(committed, currency)} of ${money(planned, currency)}"

/** "5,000.00 zł paid" */
fun paidInWords(paid: Long, currency: String): String = "${money(paid, currency)} paid"

/** "3 payments", or "No payments yet" */
fun paymentsInWords(payments: Int): String = if (payments > 0) paymentCount(payments) else "No payments yet"

/** A category line as one sentence for a screen reader: its name, its figures, where it stands, what is paid, how many payments. */
fun categoryInWords(line: BudgetLine, currency: String): String =
    listOf(
        line.name,
        categoryFigures(line.planned, line.committed, currency),
        planState(line.planned, line.committed, currency),
        paidInWords(line.paid, currency),
        paymentsInWords(line.payments),
    ).joinToString(". ")

/** The same for the payments under no category. */
fun restInWords(committed: Long, paid: Long, payments: Int, currency: String, hint: String): String =
    listOf("No category", categoryFigures(null, committed, currency), hint.trimEnd('.'), paidInWords(paid, currency), paymentCount(payments)).joinToString(". ")

/** Whether a category's bar is drawn: where it has a plan or money. */
fun hasMeter(line: BudgetLine): Boolean = line.committed > 0 || line.planned != null
