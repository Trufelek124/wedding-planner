package io.github.trufelek124.weddingplanner.data

import androidx.room.Entity
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

/** Everything one load brought, to be stored and read as a whole. */
class Stored(
    val planner: PlannerRow,
    val entries: List<EntryRow>,
    val categories: List<CategoryRow>,
    val vendors: List<VendorRow>,
    val members: List<MemberRow>,
)
