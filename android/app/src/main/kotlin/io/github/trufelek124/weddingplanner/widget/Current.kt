package io.github.trufelek124.weddingplanner.widget

import io.github.trufelek124.weddingplanner.data.Entry
import io.github.trufelek124.weddingplanner.data.Kind
import io.github.trufelek124.weddingplanner.data.Planner
import io.github.trufelek124.weddingplanner.data.Status
import io.github.trufelek124.weddingplanner.money.money
import io.github.trufelek124.weddingplanner.reminders.Day
import io.github.trufelek124.weddingplanner.reminders.dayOf
import java.time.LocalDate

/*
 * What is current (S12 section 2), worked out from the planner's copy as the screens see it. Nothing of the phone is in
 * this file: the copy and today's date are all it needs. What the reminders share with it (the way a day is told apart
 * from today, and its words) is in the reminders' package and is not written a second time.
 */

/** How many days after today a day still counts as current: "within 14 days" is today and the fourteen days after it. */
const val CURRENT_WITHIN_DAYS = 14L

/**
 * A task or a step that is current. Not a data class: the name is the couple's own and is not to be printed.
 *
 * @param amount a payment's amount, written as the app writes money; null for what is not a payment
 * @param day where its day stands against today; null for what is in progress and has no day
 * @param isStep a step of the plan: it has no box to tick
 */
class CurrentThing(
    val entryId: String,
    val name: String,
    val isStep: Boolean,
    val amount: String?,
    val day: Day?,
    val today: LocalDate,
) {

    override fun toString(): String = "CurrentThing[entryId=$entryId]"
}

/**
 * The open tasks and steps that are overdue, due within [CURRENT_WITHIN_DAYS] days of [today] (a step: on its last day),
 * or in progress whatever their day. Open is "not done, not dropped"; what waits on the phone to be sent counts as the
 * copy shows it, so a task ticked off here is not listed. The overdue come first, then by day, what has no day last,
 * then by name.
 */
fun currentThings(planner: Planner, today: LocalDate): List<CurrentThing> {
    val last = today.plusDays(CURRENT_WITHIN_DAYS)
    return planner.entries
        .filter { it.status.open && (it.status == Status.IN_PROGRESS || it.dueDate?.isAfter(last) == false) }
        .sortedWith(
            compareBy<Entry, LocalDate?>(nullsLast()) { it.dueDate }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                .thenBy { it.id },
        )
        .map { entry ->
            CurrentThing(
                entryId = entry.id,
                name = entry.title,
                isStep = entry.kind == Kind.PLAN_ITEM,
                amount = entry.amount?.let { money(it, planner.currency) },
                day = entry.dueDate?.let { dayOf(it, today) },
                today = today,
            )
        }
}
