package io.github.trufelek124.weddingplanner.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * The tables of the phone's database. They hold a copy of what the server said last, for when
 * there is no connection. Dates are kept as the server writes them ("2027-06-19"), and values
 * from a fixed set as their names: a value a newer server adds is kept as it came.
 */

/** A plan item or a task. */
@Entity(tableName = "entry")
data class EntryRow(
    @PrimaryKey val id: String,
    /** Its place in the server's own order: by date, then by priority, then by title. */
    val position: Int,
    val kind: String,
    val title: String,
    val notes: String,
    val categoryId: String?,
    val startDate: String?,
    val dueDate: String?,
    val status: String,
    val priorityMode: String,
    val priority: String,
    val priorityReason: String,
    val parentId: String?,
    val vendorId: String?,
    /** In the smallest unit of the wedding's currency; null for an entry that carries no money. */
    val amount: Long?,
    val paymentKind: String?,
    val paidOn: String?,
    /** The members' ids, separated by commas. */
    val assigneeIds: String,
    val taskCount: Int,
    val version: Int,
    /** The suggestion of the e-mail import that made it; null for an entry made by hand, and in a copy made before version 9. */
    val fromSuggestionId: String? = null,
)

@Entity(tableName = "category")
data class CategoryRow(@PrimaryKey val id: String, val position: Int, val name: String)

/**
 * A vendor, with its contact details and what its payments come to. The columns that came with
 * version 4 say what they hold in a copy an older version of the app made, until it is loaded again.
 */
@Entity(tableName = "vendor")
class VendorRow(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(defaultValue = "0") val position: Int = 0,
    @ColumnInfo(defaultValue = "") val status: String = "",
    val categoryId: String? = null,
    @ColumnInfo(defaultValue = "") val contactPerson: String = "",
    @ColumnInfo(defaultValue = "") val phone: String = "",
    @ColumnInfo(defaultValue = "") val email: String = "",
    @ColumnInfo(defaultValue = "") val website: String = "",
    @ColumnInfo(defaultValue = "") val address: String = "",
    @ColumnInfo(defaultValue = "") val notes: String = "",
    /** What was agreed, in the smallest unit of the wedding's currency; null while nothing is agreed. */
    val agreedPrice: Long? = null,
    @ColumnInfo(defaultValue = "0") val committed: Long = 0,
    @ColumnInfo(defaultValue = "0") val paid: Long = 0,
    @ColumnInfo(defaultValue = "0") val planItems: Int = 0,
    @ColumnInfo(defaultValue = "0") val tasks: Int = 0,
    @ColumnInfo(defaultValue = "0") val payments: Int = 0,
    @ColumnInfo(defaultValue = "0") val inspirations: Int = 0,
)

/** A category of the budget: what is planned for it, and what its payments come to. */
@Entity(tableName = "budget_line")
data class BudgetLineRow(
    @PrimaryKey val categoryId: String,
    val position: Int,
    val name: String,
    val planned: Long?,
    val committed: Long,
    val paid: Long,
    val payments: Int,
)

/** A guest. Not a data class: what it holds is about a person and is not to be printed. */
@Entity(tableName = "guest")
class GuestRow(
    @PrimaryKey val id: String,
    val position: Int,
    val name: String,
    val status: String,
    /** The member whose guest this is; null for a guest of both. */
    val sideMemberId: String?,
    val householdId: String?,
    val plusOne: Boolean,
    val children: Int,
    /** How many people this entry stands for: the guest, the plus-one and the children. */
    val heads: Int,
    val dietaryNeeds: String,
    val needsAccommodation: Boolean,
    val phone: String,
    val email: String,
    val notes: String,
)

@Entity(tableName = "household")
class HouseholdRow(@PrimaryKey val id: String, val position: Int, val name: String)

@Entity(tableName = "member")
class MemberRow(@PrimaryKey val id: String, val position: Int, val displayName: String, val you: Boolean)

/**
 * The one row that says what the copy is of and when it was made: the wedding, the countdown and
 * the money as Home shows them, and which entries Home lists.
 */
@Entity(tableName = "planner")
data class PlannerRow(
    @PrimaryKey val id: Int = ONLY,
    /** When the server was asked, in milliseconds since 1970. */
    val loadedAt: Long,
    val weddingDate: String?,
    val currency: String,
    val timeZone: String,
    /** Days to the wedding on [today]; null while the day is not fixed. */
    val daysLeft: Long?,
    /** The date where the wedding takes place, at the moment of loading. */
    val today: String,
    val budgetTotal: Long?,
    val budgetPlanned: Long,
    val budgetCommitted: Long,
    val budgetPaid: Long,
    /** Ids separated by commas, in Home's order. */
    val overdueIds: String,
    val nextIds: String,
    val nextPaymentIds: String,
    /** The payments filed under no category: what they come to, what of it is paid, and how many they are. */
    @ColumnInfo(defaultValue = "0") val uncategorisedCommitted: Long = 0,
    @ColumnInfo(defaultValue = "0") val uncategorisedPaid: Long = 0,
    @ColumnInfo(defaultValue = "0") val uncategorisedPayments: Int = 0,
    /** The head count as the server counted it, as JSON; empty while it was not loaded. */
    @ColumnInfo(defaultValue = "") val guestCounts: String = "",
) {
    companion object {
        const val ONLY = 1
    }
}

/**
 * A change made on the phone that the server does not have yet. This is the one thing the phone
 * holds that exists nowhere else: it stays until the server took it or the person let go of it.
 * There is at most one per entry; a further change to the same entry is folded into it.
 */
@Entity(tableName = "waiting_change", indices = [Index(value = ["entryId"], unique = true)])
data class ChangeRow(
    /** The order the changes were made in, which is the order they are sent in. */
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val entryId: String,
    /** ADD, EDIT or STATUS. */
    val kind: String,
    /** The version of the server's copy the change was made on; an edit is refused unless it is still the latest. */
    val baseVersion: Int,
    /** What is sent, as JSON: every field of the task for ADD and EDIT, only the status for STATUS. */
    val body: String,
    /** The task's title when the change was made, to name it should it be gone from the server. */
    val title: String,
    /** Counts the times a further change was folded in, to notice one that arrives while this is being sent. */
    val revision: Int = 0,
    /** Why the server did not take it: CHANGED_MEANWHILE, GONE or REFUSED. Null while it waits to be sent. */
    val trouble: String? = null,
    /** For REFUSED: what is wrong with which field, as JSON. */
    val refusals: String? = null,
)

/** A board inspirations are collected on, for choosing one when something is shared to the planner. */
@Entity(tableName = "board")
data class BoardRow(@PrimaryKey val id: String, val position: Int, val name: String)

/**
 * Something shared to the planner from another app that the server does not have yet: a link, or
 * pictures. Like a waiting change it exists nowhere else until it is sent. Its pictures are copied
 * into the app's own folder when it is shared, under [inspirationId]: the other app only lends them.
 */
@Entity(tableName = "waiting_share", indices = [Index(value = ["inspirationId"], unique = true)])
data class ShareRow(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    /** The id the inspiration gets on the server, chosen here, so that sending it twice saves it once. */
    val inspirationId: String,
    /** The link that was shared; null when pictures were. */
    val url: String?,
    val boardId: String?,
    val note: String,
    /** How many pictures belong to it; their files are numbered from 0. */
    val pictures: Int,
    /** In words for the person, why the server did not take it; null while it waits to be sent. */
    val trouble: String? = null,
) {
    /** A link and a note are the couple's own: nothing of them gets printed. */
    override fun toString(): String = "ShareRow[inspirationId=$inspirationId]"
}

/**
 * A guest's status changed on the phone that the server does not have yet. Like a waiting change it
 * exists nowhere else until it is sent. At most one per guest: a further choice replaces it. Not a
 * data class: it holds a guest's name, which is not to be printed.
 */
@Entity(tableName = "waiting_guest_status")
class GuestStatusRow(
    @PrimaryKey val guestId: String,
    /** The status that was chosen, by its name. */
    val status: String,
    /** The guest's name when the status was chosen, to name the guest should they be gone from the server. */
    val name: String,
    /** Counts the times a further choice replaced this one, to notice one that arrives while this is being sent. */
    val revision: Int = 0,
    /** Why the server did not take it: GONE or REFUSED. Null while it waits to be sent. */
    val trouble: String? = null,
) {
    fun copy(status: String = this.status, revision: Int = this.revision, trouble: String? = this.trouble): GuestStatusRow =
        GuestStatusRow(guestId, status, name, revision, trouble)

    override fun toString(): String = "GuestStatusRow[guestId=$guestId]"
}

/**
 * An idea kept on a board, as the server said it: a link, pictures, a note, tags. Not a data class:
 * the link, the title, the note and the tags are the couple's own and are not to be printed. It belongs
 * to the copy, as the vendors do, and goes with the next load and with a sign-out.
 *
 * @param position its place in the server's own order, newest first
 * @param source INSTAGRAM, TIKTOK, PINTEREST, WEB or PHOTO as the server names it; a newer server may add one
 * @param previewStatus NONE, PENDING or FETCHED
 * @param tags a JSON array of text
 */
@Entity(tableName = "inspiration")
class InspirationRow(
    @PrimaryKey val id: String,
    val position: Int,
    val url: String?,
    val source: String,
    val title: String,
    val previewTitle: String,
    val previewStatus: String,
    val note: String,
    val tags: String,
    val boardId: String?,
    val planItemId: String?,
    val vendorId: String?,
    val addedById: String?,
) {
    override fun toString(): String = "InspirationRow[id=$id]"
}

/** A picture of an idea: what the phone needs to make room for it. Its file is fetched when it is shown. */
@Entity(tableName = "inspiration_picture", primaryKeys = ["inspirationId", "id"])
class InspirationPictureRow(
    val id: String,
    val inspirationId: String,
    /** Its place among the idea's pictures, the first being the one the card shows. */
    val position: Int,
    val width: Int?,
    val height: Int?,
    /** True when the server fetched it from the link; false when a person sent it. */
    val preview: Boolean,
) {
    override fun toString(): String = "InspirationPictureRow[id=$id]"
}

/**
 * What the e-mail import proposes, as the server said it: it holds a sender, a subject and a quotation of someone's
 * mail. Not a data class, and it prints only its id. It belongs to the copy: the next load and a sign-out remove it.
 *
 * @param position its place in the server's own order, the newest message first
 * @param kind TASK, PAYMENT, PLAN_ITEM or VENDOR as the server names it; a newer server may add one
 * @param paymentKind DEPOSIT, INSTALMENT or FINAL as the server names it
 * @param receivedAt when the message arrived, as the server writes it
 * @param state WAITING, ADDED or ACCEPTED as the server names it: one that was rejected is not kept. A copy made before
 *   version 9 held only what waited.
 * @param madeEntryId the task or step it made, or that was added by itself
 * @param decidedBy the member who decided it; null for one that waits or was added, and for one that counts as kept
 *   after thirty days
 */
@Entity(tableName = "suggestion")
class SuggestionRow(
    @PrimaryKey val id: String,
    val position: Int,
    val kind: String,
    val title: String,
    val notes: String,
    val startDate: String?,
    val dueDate: String?,
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
    val receivedAt: String,
    val inboxMemberId: String,
    val mailLink: String,
    val passage: String,
    val confidence: Int,
    @ColumnInfo(defaultValue = "'WAITING'") val state: String = "WAITING",
    val madeEntryId: String? = null,
    val decidedBy: String? = null,
    val decidedAt: String? = null,
    @ColumnInfo(defaultValue = "0") val fromDocument: Boolean = false,
) {
    override fun toString(): String = "SuggestionRow[id=$id]"
}

/**
 * A suggestion accepted, rejected, kept or undone on the phone that the server does not have yet. Like a waiting change it exists
 * nowhere else until it is sent. At most one per suggestion: the other decision replaces it while it waits. Not a data
 * class: it holds a title that came out of someone's mail, which is not to be printed.
 */
@Entity(tableName = "waiting_suggestion_decision")
class SuggestionDecisionRow(
    @PrimaryKey val suggestionId: String,
    /** ACCEPT or REJECT for one that waits, KEEP or UNDO for one that was added by itself, by its name. */
    val decision: String,
    /** The suggestion's title when it was decided, to name it should it be gone from the copy. */
    val title: String,
    /** Counts the times the other decision replaced this one. */
    val revision: Int = 0,
    /** Why the server did not take it: DECIDED_OTHERWISE, GONE, REFUSED or ENTRY_CHANGED. Null while it waits to be sent. */
    val trouble: String? = null,
    /** For DECIDED_OTHERWISE of a keep or an undo: how the server says it stands, ACCEPTED or REJECTED; null where that is not known. */
    val heldState: String? = null,
    /** For [heldState]: the member who decided it; null where nobody did (thirty days) or it is not known. */
    val heldBy: String? = null,
) {
    fun copy(
        decision: String = this.decision,
        revision: Int = this.revision,
        trouble: String? = this.trouble,
        heldState: String? = this.heldState,
        heldBy: String? = this.heldBy,
    ): SuggestionDecisionRow = SuggestionDecisionRow(suggestionId, decision, title, revision, trouble, heldState, heldBy)

    override fun toString(): String = "SuggestionDecisionRow[suggestionId=$suggestionId]"
}

/** Everything the phone holds, read as it was at one moment: the last load, and what waits to be sent. */
class Stored(
    val planner: PlannerRow,
    val entries: List<EntryRow>,
    val categories: List<CategoryRow>,
    val vendors: List<VendorRow>,
    val members: List<MemberRow>,
    val changes: List<ChangeRow> = emptyList(),
    val boards: List<BoardRow> = emptyList(),
    val shares: List<ShareRow> = emptyList(),
    val budgetLines: List<BudgetLineRow> = emptyList(),
    val guests: List<GuestRow> = emptyList(),
    val households: List<HouseholdRow> = emptyList(),
    val guestStatuses: List<GuestStatusRow> = emptyList(),
    val inspirations: List<InspirationRow> = emptyList(),
    val inspirationPictures: List<InspirationPictureRow> = emptyList(),
    val suggestions: List<SuggestionRow> = emptyList(),
    val suggestionDecisions: List<SuggestionDecisionRow> = emptyList(),
)
