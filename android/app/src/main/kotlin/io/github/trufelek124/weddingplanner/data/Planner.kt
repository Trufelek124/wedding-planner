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

/** A category of the budget against its plan, in the smallest unit of the wedding's currency. */
data class BudgetLine(val categoryId: String, val name: String, val planned: Long?, val committed: Long, val paid: Long, val payments: Int)

/** The payments filed under no category. */
data class BudgetRest(val committed: Long, val paid: Long, val payments: Int)

/** [OTHER] is a state a newer server knows and this app does not. */
enum class VendorStatus { CONSIDERING, BOOKED, DROPPED, OTHER }

/**
 * Someone the wedding is made with. Not a data class: its name and contact details are not to be printed.
 *
 * @param agreedPrice what was agreed; null while nothing is
 * @param committed what its payments that are not dropped come to
 */
class Vendor(
    val id: String,
    val name: String,
    val status: VendorStatus,
    val categoryId: String?,
    val contactPerson: String,
    val phone: String,
    val email: String,
    val website: String,
    val address: String,
    val notes: String,
    val agreedPrice: Long?,
    val committed: Long,
    val paid: Long,
    val inspirations: Int,
) {
    override fun toString(): String = "Vendor[id=$id]"
}

/** [OTHER] is a state a newer server knows and this app does not. */
enum class GuestStatus { NOT_INVITED, INVITED, CONFIRMED, DECLINED, MAYBE, OTHER }

/**
 * A guest. Not a data class: everything about a guest is personal and not to be printed.
 *
 * @param sideMemberId the member whose guest this is; null for a guest of both
 * @param heads how many people this entry stands for: the guest, the plus-one and the children
 */
class Guest(
    val id: String,
    val name: String,
    val status: GuestStatus,
    val sideMemberId: String?,
    val householdId: String?,
    val plusOne: Boolean,
    val children: Int,
    val heads: Int,
    val dietaryNeeds: String,
    val needsAccommodation: Boolean,
    val phone: String,
    val email: String,
    val notes: String,
) {
    override fun toString(): String = "Guest[id=$id]"
}

/** A household guests are invited with. */
class Household(val id: String, val name: String) {
    override fun toString(): String = "Household[id=$id]"
}

/** How many people: the guests on the list by name, their plus-ones, the children, and all three together. */
data class HeadCount(val guests: Int, val plusOnes: Int, val children: Int, val heads: Int)

/** @param status as the server names it: "CONFIRMED" */
data class StatusCount(val status: String, val count: HeadCount)

/** @param sideMemberId the member whose guests these are; null for the guests of both */
data class SideCount(val sideMemberId: String?, val count: HeadCount, val byStatus: List<StatusCount>)

/** The head count as the server counted it. */
data class GuestCounts(val total: HeadCount, val byStatus: List<StatusCount>, val bySide: List<SideCount>)

/** A board inspirations are collected on. */
data class Board(val id: String, val name: String)

/**
 * Something shared to the planner that the server does not have yet.
 *
 * @param link the link that was shared; null when pictures were
 * @param pictures how many pictures were shared
 * @param trouble in words, why the server did not take it; null while it waits to be sent
 */
data class WaitingShare(val id: String, val link: String?, val pictures: Int, val trouble: String?)

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
    /** The boards, in the server's order. */
    val boards: List<Board> = emptyList(),
    /** What was shared to the planner on this phone and is not on the server yet. */
    val shares: List<WaitingShare> = emptyList(),
    /** The budget's categories, in the couple's order, with or without money. */
    val budgetLines: List<BudgetLine> = emptyList(),
    val uncategorised: BudgetRest = BudgetRest(0, 0, 0),
    /** The vendors with their details, by name. */
    val vendorList: List<Vendor> = emptyList(),
    val guests: List<Guest> = emptyList(),
    val households: List<Household> = emptyList(),
    /** Null while the head count was not loaded: a copy made by an older version of the app has none. */
    val guestCounts: GuestCounts? = null,
) {
    private val byId = entries.associateBy { it.id }

    val planItems: List<Entry> get() = entries.filter { it.kind == Kind.PLAN_ITEM }

    val tasks: List<Entry> get() = entries.filter { it.kind == Kind.TASK }

    fun tasksOf(planItem: Entry): List<Entry> = entries.filter { it.kind == Kind.TASK && it.parentId == planItem.id }

    fun entry(id: String): Entry? = byId[id]

    fun vendor(id: String): Vendor? = vendorList.firstOrNull { it.id == id }

    fun guest(id: String): Guest? = guests.firstOrNull { it.id == id }

    /** The plan items, tasks and payments that concern a vendor, in the list's order. */
    fun entriesOf(vendor: Vendor): List<Entry> = entries.filter { it.vendorId == vendor.id }

    /** What a member is called; null for someone who is not one of the two. */
    fun memberName(id: String): String? = members.firstOrNull { it.id == id }?.displayName

    /** How many changes made on this phone are on their way to the server. */
    val waiting: Int get() = entries.count { it.waiting == Waiting.TO_BE_SENT }

    /** How many changes exist only on this phone, those that could not be sent included: what a sign-out would lose. */
    val unsent: Int get() = entries.count { it.waiting != null } + gone.size + shares.size

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
