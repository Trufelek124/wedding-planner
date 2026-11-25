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
    /** Set while a status chosen on this phone has not reached the server; [status] is then the one chosen. */
    val waiting: Waiting? = null,
) {
    override fun toString(): String = "Guest[id=$id]"

    /** The same guest with the status that was chosen on the phone and where that stands. */
    fun chosen(status: GuestStatus, waiting: Waiting): Guest = Guest(
        id, name, status, sideMemberId, householdId, plusOne, children, heads, dietaryNeeds, needsAccommodation, phone, email, notes, waiting,
    )
}

/**
 * A guest's status chosen on the phone that cannot be sent as it is: the guest is gone from the server
 * ([gone]), or the server did not take it. Not a data class: it holds a guest's name.
 */
class GuestTrouble(val guestId: String, val name: String, val gone: Boolean) {
    override fun toString(): String = "GuestTrouble[guestId=$guestId]"
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

/** Where an idea comes from. [OTHER] is a source a newer server knows and this app does not. */
enum class InspirationSource { INSTAGRAM, TIKTOK, PINTEREST, WEB, PHOTO, OTHER }

/** A picture of an idea. Its file is not on the phone: only what the card needs to make room for it. */
class Picture(val id: String, val width: Int?, val height: Int?, val preview: Boolean) {
    override fun toString(): String = "Picture[id=$id]"
}

/**
 * An idea kept on a board. Not a data class: its link, title, note and tags are the couple's own and are not
 * to be printed.
 *
 * @param url the link it was saved from; null for an idea made of pictures
 * @param title the title a person gave it; empty when none
 * @param previewTitle the title of the page the link leads to, once the server has fetched it; empty before
 * @param previewPending the server is still fetching the link's picture and title
 * @param pictures the pictures it holds, the one the card shows first
 */
class Inspiration(
    val id: String,
    val url: String?,
    val source: InspirationSource,
    val title: String,
    val previewTitle: String,
    val previewPending: Boolean,
    val note: String,
    val tags: List<String>,
    val boardId: String?,
    val planItemId: String?,
    val vendorId: String?,
    val addedById: String?,
    val pictures: List<Picture>,
) {
    override fun toString(): String = "Inspiration[id=$id]"
}

/** What accepting a suggestion makes. [OTHER] is a kind a newer server knows and this app does not. */
enum class SuggestionKind { TASK, PAYMENT, PLAN_ITEM, VENDOR, OTHER }

/**
 * Where a suggestion that the phone holds stands. One that was rejected is not held, and neither is a state a newer
 * server adds.
 */
enum class SuggestionState {
    /** Nobody has decided it: accept or reject. */
    WAITING,

    /** The import was sure enough and made the task or the step by itself: keep it or undo it. */
    ADDED,

    /** Accepted by a person, kept, or counted as kept after thirty days: what an entry's "From e-mail" leads to. */
    ACCEPTED,
}

/**
 * What the e-mail import proposes. Not a data class: the sender, the subject, the passage and the title came out of
 * someone's mail and are not to be printed. All of it is plain text, whatever it looks like.
 *
 * @param startDate a plan item's first day
 * @param amount in the smallest unit of the wedding's currency
 * @param paymentKind DEPOSIT, INSTALMENT or FINAL as the server names it; null when none was read
 * @param vendorId a vendor the planner has, found by the sender's address
 * @param vendorName a vendor's name where none matched
 * @param receivedAt when the message arrived; null where it cannot be read
 * @param inboxMemberId the member whose inbox it was in
 * @param mailLink the server's link to the message in Gmail; whether it may be opened is `mailLinkAddress` in planner/Contact.kt's to say
 * @param passage the quotation the proposal rests on
 * @param confidence how sure the import is, as a whole percentage
 * @param state where it stands on the server
 * @param madeEntryId the task or step it made, or that was added by itself
 * @param decidedBy the member who decided it; null while nobody did, and for one that counts as kept after thirty days
 * @param decidedAt when it was decided
 * @param decided what was decided on this phone and the server does not have yet; null while nobody decided
 * @param waiting where the decision stands: set with [decided]
 * @param fromDocument the passage stands in a document that came with the mail, not in the mail itself; false for a copy made
 *   before the app knew
 * @param needsLook a decision made on this phone cannot be sent as it is: the card says nothing of it and offers none
 *   until the notice has been dealt with
 */
class Suggestion(
    val id: String,
    val kind: SuggestionKind,
    val title: String,
    val notes: String,
    val startDate: LocalDate?,
    val dueDate: LocalDate?,
    val amount: Long?,
    val paymentKind: String?,
    val categoryId: String?,
    val vendorId: String?,
    val vendorName: String?,
    val vendorEmail: String?,
    val vendorPhone: String?,
    val vendorWebsite: String?,
    val senderName: String,
    val senderAddress: String,
    val subject: String,
    val receivedAt: Instant?,
    val inboxMemberId: String,
    val mailLink: String,
    val passage: String,
    val confidence: Int,
    val state: SuggestionState = SuggestionState.WAITING,
    val madeEntryId: String? = null,
    val decidedBy: String? = null,
    val decidedAt: Instant? = null,
    val decided: Decision? = null,
    val waiting: Waiting? = null,
    val needsLook: Boolean = false,
    val fromDocument: Boolean = false,
) {
    override fun toString(): String = "Suggestion[id=$id]"
}

/**
 * What a person decides about a suggestion: accept or reject one that waits, keep or undo one that was added by itself.
 */
enum class Decision {
    ACCEPT,
    REJECT,
    KEEP,
    UNDO;

    /** The decision the other owner would have made. */
    val other: Decision
        get() = when (this) {
            ACCEPT -> REJECT
            REJECT -> ACCEPT
            KEEP -> UNDO
            UNDO -> KEEP
        }

    /** Whether this is for a suggestion that was added by itself. */
    val ofAdded: Boolean get() = this == KEEP || this == UNDO
}

/** How a suggestion that was added by itself stands on the server when a keep or an undo of the phone's met it decided. */
enum class DecidedAs { KEPT, UNDONE, KEPT_AFTER_THIRTY_DAYS }

/** Why a decision made on the phone could not be sent as it is. */
enum class SuggestionTroubleKind {
    /** The other decision was made first: the phone's is let go of. */
    DECIDED_OTHERWISE,

    /** The suggestion no longer exists on the server. */
    GONE,

    /** The server did not take an acceptance: a vendor that is gone, an amount that is not valid. */
    REFUSED,

    /** An undo of an entry that is no longer what was added: it stays in the plan. The server counts the suggestion as kept. */
    ENTRY_CHANGED,
}

/**
 * A decision made on the phone that cannot be sent and has to be looked at: it can only be let go of. Not a data
 * class: it holds the title of a suggestion, which came out of someone's mail.
 *
 * @param decision what the phone sent, or was to send
 * @param decidedAs for a keep or an undo that was decided otherwise: how it stands on the server; null where that is not known
 * @param decidedBy the name of the member who decided it; null where nobody did or the member is not known
 */
class SuggestionTrouble(
    val suggestionId: String,
    val title: String,
    val kind: SuggestionTroubleKind,
    val decision: Decision,
    val decidedAs: DecidedAs? = null,
    val decidedBy: String? = null,
) {
    override fun toString(): String = "SuggestionTrouble[suggestionId=$suggestionId]"
}

/**
 * Something shared to the planner that the server does not have yet.
 *
 * @param link the link that was shared; null when pictures were
 * @param pictures how many pictures were shared
 * @param trouble in words, why the server did not take it; null while it waits to be sent
 * @param boardId the board it was shared to; null for none
 * @param newBoard the name of a board that is to be made for it, as it was typed; null for none
 */
data class WaitingShare(val id: String, val link: String?, val pictures: Int, val trouble: String?, val boardId: String? = null, val newBoard: String? = null) {
    /** A link and a board's name are the couple's own: nothing of them gets printed. */
    override fun toString(): String = "WaitingShare[id=$id]"
}

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
    /** The suggestion of the e-mail import that made it; null for an entry made by hand, and while the copy does not know. */
    val fromSuggestionId: String? = null,
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
    /** Statuses chosen on the phone that the server did not take, or whose guest it no longer has. */
    val guestTroubles: List<GuestTrouble> = emptyList(),
    /** The ideas on the boards, in the server's order: the newest first. */
    val inspirations: List<Inspiration> = emptyList(),
    /** What the e-mail import proposes and nobody has decided, the newest message first. */
    val suggestions: List<Suggestion> = emptyList(),
    /** What the import added by itself and nobody has kept or undone, the newest first: those with a decision made on the phone included. */
    val added: List<Suggestion> = emptyList(),
    /** What was accepted or kept and an entry says it came from: nothing else of the accepted is held. */
    val accepted: List<Suggestion> = emptyList(),
    /** Decisions made on the phone that cannot be sent as they are. */
    val suggestionTroubles: List<SuggestionTrouble> = emptyList(),
    /** How many decisions of suggestions exist only on this phone, those that could not be sent included. */
    val suggestionDecisions: Int = 0,
) {
    private val byId = entries.associateBy { it.id }

    val planItems: List<Entry> get() = entries.filter { it.kind == Kind.PLAN_ITEM }

    val tasks: List<Entry> get() = entries.filter { it.kind == Kind.TASK }

    fun tasksOf(planItem: Entry): List<Entry> = entries.filter { it.kind == Kind.TASK && it.parentId == planItem.id }

    fun entry(id: String): Entry? = byId[id]

    fun vendor(id: String): Vendor? = vendorList.firstOrNull { it.id == id }

    fun guest(id: String): Guest? = guests.firstOrNull { it.id == id }

    fun inspiration(id: String): Inspiration? = inspirations.firstOrNull { it.id == id }

    /** A suggestion the phone holds, whether it waits, was added by itself or was accepted; null for one it does not have. */
    fun suggestion(id: String): Suggestion? = (suggestions + added + accepted).firstOrNull { it.id == id }

    /** The member who is signed in on this phone; null while the copy has none. */
    val you: Member? get() = members.firstOrNull { it.you }

    /**
     * Whether the message is in the signed-in person's own mailbox. A message in the other owner's cannot be opened
     * from here.
     */
    fun inYourInbox(suggestion: Suggestion): Boolean = you?.id == suggestion.inboxMemberId

    /** The plan items, tasks and payments that concern a vendor, in the list's order. */
    fun entriesOf(vendor: Vendor): List<Entry> = entries.filter { it.vendorId == vendor.id }

    /** What a member is called; null for someone who is not one of the two. */
    fun memberName(id: String): String? = members.firstOrNull { it.id == id }?.displayName

    /** How many changes made on this phone are on their way to the server. */
    val waiting: Int
        get() = entries.count { it.waiting == Waiting.TO_BE_SENT } + guests.count { it.waiting == Waiting.TO_BE_SENT } +
            (suggestions + added).count { it.waiting == Waiting.TO_BE_SENT }

    /** How many suggestions still wait for a decision: those decided on this phone are not counted, they are as good as decided. */
    val suggestionsToDecide: Int get() = suggestions.count { it.decided == null }

    /**
     * How many were added by themselves and nobody has looked at: the number beside [suggestionsToDecide], never added to
     * it, because the two ask different things of a person. One with a decision made on this phone is not counted.
     */
    val addedToLookAt: Int get() = added.count { it.decided == null }

    /** How many guests show a status chosen on this phone that the server does not have: the head count does not know of them. */
    val guestsChanged: Int get() = guests.count { it.waiting != null }

    /** How many changes exist only on this phone, those that could not be sent included: what a sign-out would lose. */
    val unsent: Int get() = entries.count { it.waiting != null } + gone.size + shares.size + guestsChanged + guestTroubles.count { it.gone } + suggestionDecisions

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
