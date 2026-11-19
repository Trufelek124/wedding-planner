package io.github.trufelek124.weddingplanner.planner

import io.github.trufelek124.weddingplanner.data.Budget
import io.github.trufelek124.weddingplanner.data.Entry
import io.github.trufelek124.weddingplanner.data.Kind
import io.github.trufelek124.weddingplanner.data.Planner
import io.github.trufelek124.weddingplanner.data.Priority
import io.github.trufelek124.weddingplanner.data.Status
import io.github.trufelek124.weddingplanner.data.Waiting
import io.github.trufelek124.weddingplanner.money.money
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * How the planner's facts read on the phone: the same words as in the web app
 * (web/src/planning/labels.ts, web/src/wedding/dates.ts). The interface is in English whatever the
 * phone's language.
 */

private val SHORT = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
private val LONG = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH)
private val MOMENT = DateTimeFormatter.ofPattern("d MMM yyyy 'at' HH:mm", Locale.ENGLISH)

/** "19 Jun 2027" */
fun shortDate(date: LocalDate): String = date.format(SHORT)

/** "Saturday 19 June 2027" */
fun longDate(date: LocalDate): String = date.format(LONG)

/** "18 Nov 2026 at 14:05", on the phone's own clock. */
fun moment(at: Instant, zone: ZoneId): String = at.atZone(zone).format(MOMENT)

/** "214 days to go", "Tomorrow", "Today is the day", "3 days ago" */
fun countdown(days: Long): String = when {
    days > 1 -> "$days days to go"
    days == 1L -> "Tomorrow"
    days == 0L -> "Today is the day"
    days == -1L -> "Yesterday"
    else -> "${-days} days ago"
}

fun statusLabel(status: Status): String? = when (status) {
    Status.NOT_STARTED -> "Not started"
    Status.IN_PROGRESS -> "In progress"
    Status.DONE -> "Done"
    Status.DROPPED -> "Dropped"
    Status.OTHER -> null
}

fun priorityLabel(priority: Priority): String = when (priority) {
    Priority.CRITICAL -> "Critical"
    Priority.HIGH -> "High"
    Priority.NORMAL -> "Normal"
    Priority.LOW -> "Low"
}

/** Why an entry has its level; `{due}` is "due" for a task and "ends" for a plan item. */
private val REASONS = mapOf(
    "OVERDUE" to "overdue",
    "DUE_WITHIN_3_DAYS" to "{due} within 3 days",
    "PAYMENT_DUE_WITHIN_7_DAYS" to "a payment due within 7 days",
    "DUE_WITHIN_14_DAYS" to "{due} within 14 days",
    "NEEDED_BY_ANOTHER" to "something due soon waits for it",
    "DUE_WITHIN_60_DAYS" to "{due} within 60 days",
    "DUE_LATER" to "{due} in more than 60 days",
    "NO_DATE" to "no date",
    "SHOULD_HAVE_STARTED" to "should have started",
    "FINISHED" to "needs no more attention",
    "SET_BY_HAND" to "set by hand",
)

/** "Critical — due within 3 days"; only the level for a reason this version of the app does not know. */
fun priorityInWords(entry: Entry): String {
    val reason = REASONS[entry.priorityReason]?.replace("{due}", if (entry.kind == Kind.TASK) "due" else "ends")
    return if (reason == null) priorityLabel(entry.priority) else "${priorityLabel(entry.priority)} — $reason"
}

private val PAYMENT_KINDS = mapOf("DEPOSIT" to "deposit", "INSTALMENT" to "instalment", "FINAL" to "final payment")

/** "2,500.00 zł, deposit, paid 10 Nov 2026" or "… to pay"; null for an entry that carries no money. */
fun moneyInWords(entry: Entry, currency: String): String? {
    val amount = entry.amount ?: return null
    val words = mutableListOf(money(amount, currency))
    if (entry.paymentKind != null) {
        words += PAYMENT_KINDS[entry.paymentKind] ?: "payment"
    }
    words += when (entry.status) {
        Status.DONE -> if (entry.paidOn == null) "paid" else "paid ${shortDate(entry.paidOn)}"
        Status.DROPPED -> "dropped, not counted"
        else -> "to pay"
    }
    return words.joinToString(", ")
}

/** When an entry happens: "1 Dec 2026 – 31 Jan 2027", "by 31 Jan 2027", "due 15 Jan 2027", or nothing. */
fun whenInWords(entry: Entry): String? {
    val due = entry.dueDate ?: return null
    return when {
        entry.kind == Kind.TASK -> "due ${shortDate(due)}"
        entry.startDate == null -> "by ${shortDate(due)}"
        else -> "${shortDate(entry.startDate)} – ${shortDate(due)}"
    }
}

/** Where a change made on this phone stands, for the line of the entry; null when nothing waits. */
fun waitingInWords(waiting: Waiting?): String? = when (waiting) {
    Waiting.TO_BE_SENT -> "Waiting to be sent"
    Waiting.CHANGED_MEANWHILE -> "Not sent: changed on another device"
    Waiting.REFUSED -> "Not saved: open it to see why"
    null -> null
}

/** A few words about an entry. [strong] marks the one to see first: a level that asks for attention. */
data class Fact(val text: String, val strong: Boolean = false)

/**
 * The line under an entry's title, fact by fact: its state where that is not plain from the list,
 * how urgent and why, its money, when, the plan item a task belongs to, the category, the vendor
 * and who does it.
 *
 * @param withPlanItem false where the task already stands under its plan item
 * @param withVendor false where the entry already stands under its vendor
 */
fun factsOf(entry: Entry, planner: Planner, withPlanItem: Boolean = true, withVendor: Boolean = true): List<Fact> = listOfNotNull(
    waitingInWords(entry.waiting)?.let { Fact(it, strong = true) },
    statusLabel(entry.status).takeIf { entry.kind == Kind.PLAN_ITEM || entry.status != Status.NOT_STARTED }?.let(::Fact),
    // The level is a word and says why; the weight only helps the eye find what is urgent. A task that
    // exists only on the phone has no level yet: the server works it out.
    Fact(priorityInWords(entry), strong = entry.status.open && (entry.priority == Priority.CRITICAL || entry.priority == Priority.HIGH))
        .takeUnless { entry.priorityReason == Entry.NOT_WORKED_OUT },
    moneyInWords(entry, planner.currency)?.let(::Fact),
    whenInWords(entry)?.let(::Fact),
    entry.parentId.takeIf { withPlanItem }?.let { planner.entry(it)?.title }?.let(::Fact),
    entry.categoryId?.let { planner.categories[it] }?.let(::Fact),
    entry.vendorId.takeIf { withVendor }?.let { planner.vendors[it] }?.let(::Fact),
    planner.peopleOf(entry)?.let(::Fact),
)

/** "3 tasks, 1 done" */
fun tasksInWords(tasks: List<Entry>): String {
    val done = tasks.count { it.status == Status.DONE }
    return (if (tasks.size == 1) "1 task" else "${tasks.size} tasks") + (if (done > 0) ", $done done" else "")
}

/** What the payments are measured against: the couple's own limit, or else what they planned; null without either. */
fun limitOf(budget: Budget): Long? = budget.total ?: budget.planned.takeIf { it > 0 }

fun limitName(budget: Budget): String = if (budget.total == null) "planned" else "budget"

/** Whether there is any money in the planner to talk about. */
fun hasMoney(budget: Budget): Boolean = budget.total != null || budget.planned != 0L || budget.committed != 0L

/** "2,500.00 zł paid, 7,500.00 zł still to pay — 40,000.00 zł of the 50,000.00 zł budget not committed." */
fun budgetInWords(budget: Budget, currency: String): String {
    val spent = "${money(budget.paid, currency)} paid, ${money(budget.committed - budget.paid, currency)} still to pay"
    val limit = limitOf(budget) ?: return "$spent."
    return if (budget.committed > limit) {
        "$spent — ${money(budget.committed - limit, currency)} more than the ${money(limit, currency)} ${limitName(budget)}."
    } else {
        "$spent — ${money(limit - budget.committed, currency)} of the ${money(limit, currency)} ${limitName(budget)} not committed."
    }
}

/** What a screen reader is told instead of the bar. */
fun meterInWords(budget: Budget, currency: String): String =
    meterSentence("The whole budget", limitOf(budget), budget.committed, budget.paid, limitName(budget), currency)

/** Today's date where the wedding takes place, by the phone's clock. */
fun todayAt(planner: Planner, now: Instant): LocalDate =
    try {
        now.atZone(ZoneId.of(planner.timeZone)).toLocalDate()
    } catch (unknownZone: java.time.DateTimeException) {
        planner.today
    }
