package io.github.trufelek124.weddingplanner.data

import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class Kind { PLAN_ITEM, TASK }

/** [OTHER] is a state a newer server knows and this app does not: it counts as still to do. */
enum class Status {
    NOT_STARTED,
    IN_PROGRESS,
    DONE,
    DROPPED,
    OTHER;

    /** Still to do. */
    val open: Boolean get() = this != DONE && this != DROPPED
}

enum class Priority { CRITICAL, HIGH, NORMAL, LOW }

/** Where a change made on the phone stands with the server. */
enum class Waiting {
    /** Not sent yet: there was no connection, or it is on its way. */
    TO_BE_SENT,

    /** Not sent: someone changed the entry elsewhere in the meantime. What is shown is the latest version, without this change. */
    CHANGED_MEANWHILE,

    /** The server did not take it as it is; [Entry.refusals] says what is wrong. */
    REFUSED,
}

/**
 * What a person may set on a task. All of it is sent with every save, as the server asks.
 *
 * @param priorityByHand the level chosen by hand; null when the server works it out
 * @param amount for a payment: what it costs, in the smallest unit of the wedding's currency
 */
data class TaskFields(
    val title: String,
    val notes: String = "",
    val categoryId: String? = null,
    val dueDate: LocalDate? = null,
    val status: Status = Status.NOT_STARTED,
    val assigneeIds: List<String> = emptyList(),
    val priorityByHand: Priority? = null,
    val parentId: String? = null,
    val vendorId: String? = null,
    val amount: Long? = null,
    val paymentKind: String? = null,
    val paidOn: LocalDate? = null,
)

/** A task that was let go of on the server while a change to it was waiting on the phone. */
data class Gone(val entryId: String, val title: String)

/** A plan item or a task, as the screens show it. */
data class Entry(
    val id: String,
    val kind: Kind,
    val title: String,
    val notes: String,
    val categoryId: String?,
    val startDate: LocalDate?,
    val dueDate: LocalDate?,
    val status: Status,
    /** The level as shown today: worked out by the server or set by hand. */
    val priority: Priority,
    /** Why it has that level, as the server names it: "DUE_WITHIN_3_DAYS". */
    val priorityReason: String,
    /** Tasks only: the plan item it belongs to. */
    val parentId: String?,
    val vendorId: String?,
    /** For a payment: what it costs, in the smallest unit of the wedding's currency. */
    val amount: Long?,
    /** For a payment: which part of the price, as the server names it; null when that was not said. */
    val paymentKind: String?,
    val paidOn: LocalDate?,
    val assigneeIds: List<String>,
    val taskCount: Int,
    val version: Int,
    /** Whether the level was chosen by hand, as opposed to worked out by the server. */
    val byHand: Boolean = false,
    /** Set while a change made on this phone has not reached the server. */
    val waiting: Waiting? = null,
    /** For a change the server refused: what is wrong, by field, in words for the person. */
    val refusals: Map<String, String> = emptyMap(),
) {
    /** A payment is a task with an amount (D-004). */
    val isPayment: Boolean get() = amount != null

    /** What a form starts from when this task is edited. */
    val fields: TaskFields
        get() = TaskFields(
            title = title, notes = notes, categoryId = categoryId, dueDate = dueDate, status = status, assigneeIds = assigneeIds,
            priorityByHand = priority.takeIf { byHand }, parentId = parentId, vendorId = vendorId, amount = amount,
            paymentKind = paymentKind, paidOn = paidOn,
        )

    companion object {
        /** The reason of a level the server has not worked out yet: the entry was changed on the phone and not sent. */
        const val NOT_WORKED_OUT = ""
    }
}

/** One of the two people. */
class Member(val id: String, val displayName: String, val you: Boolean) {

    /** A name is personal data: what gets printed is only which member it is. */
    override fun toString(): String = "Member[id=$id]"
}

/** The money at a glance, in the smallest unit of the wedding's currency. */
data class Budget(val total: Long?, val planned: Long, val committed: Long, val paid: Long)

/**
 * Everything the phone holds of the planner, as the server said it at [loadedAt].
 *
 * @param today the date where the wedding takes place, at the moment of loading
 * @param daysLeft the days to the wedding on [today]; null while the day is not fixed
 * @param entries every plan item and task, in the server's order
 * @param categories what each category is called, by its id, in the plan's order
 * @param vendors what each vendor is called, by its id
 */
class Planner(
    val loadedAt: Instant,
    val weddingDate: LocalDate?,
    val currency: String,
    val timeZone: String,
    val today: LocalDate,
    val daysLeft: Long?,
    val members: List<Member>,
    val entries: List<Entry>,
    val categories: Map<String, String>,
    val vendors: Map<String, String>,
    val overdue: List<Entry>,
    val next: List<Entry>,
    val nextPayments: List<Entry>,
    val budget: Budget,
    /** Changes whose task is no longer on the server: they can only be let go of. */
    val gone: List<Gone> = emptyList(),
) {
    private val byId = entries.associateBy { it.id }

    val planItems: List<Entry> get() = entries.filter { it.kind == Kind.PLAN_ITEM }

    val tasks: List<Entry> get() = entries.filter { it.kind == Kind.TASK }

    fun tasksOf(planItem: Entry): List<Entry> = entries.filter { it.kind == Kind.TASK && it.parentId == planItem.id }

    fun entry(id: String): Entry? = byId[id]

    /** How many changes made on this phone are on their way to the server. */
    val waiting: Int get() = entries.count { it.waiting == Waiting.TO_BE_SENT }

    /** How many changes exist only on this phone, those that could not be sent included: what a sign-out would lose. */
    val unsent: Int get() = entries.count { it.waiting != null } + gone.size

    /** "Anna & Marek": the members who do it, in the wedding's order; null when nobody is named. */
    fun peopleOf(entry: Entry): String? =
        members.filter { it.id in entry.assigneeIds }.joinToString(" & ") { it.displayName }.ifEmpty { null }

    /**
     * The days to the wedding on [day]. On the day of loading it is what the server counted; a copy
     * that is older is counted on from there, so that the number is right without a connection too.
     */
    fun daysLeftOn(day: LocalDate): Long? {
        val wedding = weddingDate ?: return null
        return if (day == today && daysLeft != null) daysLeft else ChronoUnit.DAYS.between(day, wedding)
    }

    /** Names, titles and notes are the couple's own: nothing of them gets printed. */
    override fun toString(): String = "Planner[${entries.size} entries, loaded $loadedAt]"
}
