package io.github.trufelek124.weddingplanner.data

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
)

@Entity(tableName = "category")
data class CategoryRow(@PrimaryKey val id: String, val position: Int, val name: String)

@Entity(tableName = "vendor")
class VendorRow(@PrimaryKey val id: String, val name: String)

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

/** Everything the phone holds, read as it was at one moment: the last load, and the changes waiting to be sent. */
class Stored(
    val planner: PlannerRow,
    val entries: List<EntryRow>,
    val categories: List<CategoryRow>,
    val vendors: List<VendorRow>,
    val members: List<MemberRow>,
    val changes: List<ChangeRow> = emptyList(),
)
