package io.github.trufelek124.weddingplanner.widget

import io.github.trufelek124.weddingplanner.data.Budget
import io.github.trufelek124.weddingplanner.data.Entry
import io.github.trufelek124.weddingplanner.data.Kind
import io.github.trufelek124.weddingplanner.data.Planner
import io.github.trufelek124.weddingplanner.data.Priority
import io.github.trufelek124.weddingplanner.data.Status
import java.time.Instant
import java.time.LocalDate

/** The 28th of November 2026, in the made-up plans of the widget's tests. */
val WIDGET_TODAY: LocalDate = LocalDate.of(2026, 11, 28)

fun anEntry(
    title: String,
    due: String?,
    status: Status = Status.NOT_STARTED,
    kind: Kind = Kind.TASK,
    amount: Long? = null,
    id: String = title,
) = Entry(
    id = id, kind = kind, title = title, notes = "", categoryId = null, startDate = null, dueDate = due?.let(LocalDate::parse),
    status = status, priority = Priority.NORMAL, priorityReason = "NO_DATE", parentId = null, vendorId = null, amount = amount,
    paymentKind = null, paidOn = null, assigneeIds = emptyList(), taskCount = 0, version = 0,
)

fun aPlanner(vararg entries: Entry, currency: String = "PLN") = Planner(
    loadedAt = Instant.EPOCH, weddingDate = null, currency = currency, timeZone = "Europe/Warsaw", daysLeft = null,
    today = WIDGET_TODAY, budget = Budget(null, 0, 0, 0), entries = entries.toList(), categories = emptyMap(), vendors = emptyMap(),
    members = emptyList(), overdue = emptyList(), next = emptyList(), nextPayments = emptyList(),
)
