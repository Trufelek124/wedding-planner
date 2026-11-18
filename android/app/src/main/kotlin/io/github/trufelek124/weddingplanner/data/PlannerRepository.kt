package io.github.trufelek124.weddingplanner.data

import androidx.room.InvalidationTracker
import io.github.trufelek124.weddingplanner.api.Api
import io.github.trufelek124.weddingplanner.api.ApiFailure
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What asking the server for the planner came to. */
enum class Load {
    LOADED,

    /** No connection, or no answer. What the phone holds stays as it is. */
    UNREACHABLE,

    /** Nobody has set the wedding up yet. */
    NOT_SET_UP,

    /** The planner belongs to two other people. */
    NOT_A_MEMBER,

    /** The session ended while asking; the sign-in takes over. */
    SIGNED_OUT,

    FAILED,
}

/**
 * The planner on the phone: loads it from the server as a whole, keeps the last load in the
 * database, and hands out what is kept.
 */
class PlannerRepository(private val api: () -> Api, private val database: PlannerDatabase, private val clock: Clock) {

    private val dao = database.planner()
    private val writing = Mutex()

    /** How often the planner was removed from the phone; a load that began before the last time is not kept. */
    @Volatile
    private var forgotten = 0

    /** What the phone holds, again whenever it changes; null while nothing was loaded for whoever is signed in. */
    val planner: Flow<Planner?> = database.invalidationTracker.changesOf(PlannerDatabase.TABLES).map { dao.stored()?.let(::plannerOf) }

    /**
     * Asks the server for everything the screens show and puts it in the place of the last load.
     * The answers belong together: unless all of them came, nothing is replaced.
     */
    suspend fun load(): Load {
        val since = forgotten
        val loaded = try {
            coroutineScope {
                val wedding = async { api().get<WeddingWire>("/api/wedding") }
                val home = async { api().get<HomeWire>("/api/home") }
                val entries = async { api().get<List<EntryWire>>("/api/entries") }
                val categories = async { api().get<List<NameWire>>("/api/categories") }
                val vendors = async { api().get<List<NameWire>>("/api/vendors") }
                storedOf(clock.instant(), wedding.await(), home.await(), entries.await(), categories.await(), vendors.await())
            }
        } catch (failure: ApiFailure) {
            return when {
                failure is ApiFailure.Unreachable -> Load.UNREACHABLE
                failure is ApiFailure.SignedOut -> Load.SIGNED_OUT
                failure is ApiFailure.Problem && failure.isOf(ApiFailure.WEDDING_NOT_SET_UP) -> Load.NOT_SET_UP
                failure is ApiFailure.Problem && failure.isOf(ApiFailure.WEDDING_FULL) -> Load.NOT_A_MEMBER
                else -> Load.FAILED
            }
        }
        return writing.withLock {
            // A sign-out while the server was answering: what came back is nobody's any more.
            if (forgotten != since) {
                Load.SIGNED_OUT
            } else {
                dao.replace(loaded)
                Load.LOADED
            }
        }
    }

    /**
     * Removes the planner from the phone: for a sign-out. Rows that were deleted stay readable in
     * the database's files until something is written over them, so the files are cleaned out too.
     */
    suspend fun forget() {
        writing.withLock {
            forgotten += 1
            dao.forget()
            withContext(Dispatchers.IO) {
                val files = database.openHelper.writableDatabase
                files.execSQL("VACUUM")
                files.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            }
        }
    }
}

private fun InvalidationTracker.changesOf(tables: Array<String>): Flow<Set<String>> = createFlow(*tables)

private const val SEPARATOR = ","

internal fun storedOf(
    at: Instant,
    wedding: WeddingWire,
    home: HomeWire,
    entries: List<EntryWire>,
    categories: List<NameWire>,
    vendors: List<NameWire>,
): Stored = Stored(
    planner = PlannerRow(
        loadedAt = at.toEpochMilli(),
        weddingDate = wedding.date,
        currency = wedding.currency,
        timeZone = wedding.timeZone,
        daysLeft = home.daysLeft,
        today = home.today,
        budgetTotal = home.budget.total,
        budgetPlanned = home.budget.planned,
        budgetCommitted = home.budget.committed,
        budgetPaid = home.budget.paid,
        overdueIds = home.overdue.joinToString(SEPARATOR) { it.id },
        nextIds = home.next.joinToString(SEPARATOR) { it.id },
        nextPaymentIds = home.nextPayments.joinToString(SEPARATOR) { it.id },
    ),
    // Home's lists are read a moment apart from the whole list: an entry only Home knows of is kept too.
    entries = (entries + home.overdue + home.next + home.nextPayments).distinctBy { it.id }.mapIndexed { position, entry ->
        EntryRow(
            id = entry.id,
            position = position,
            kind = entry.kind,
            title = entry.title,
            notes = entry.notes,
            categoryId = entry.categoryId,
            startDate = entry.startDate,
            dueDate = entry.dueDate,
            status = entry.status,
            priorityMode = entry.priorityMode,
            priority = entry.priority,
            priorityReason = entry.priorityReason,
            parentId = entry.parentId,
            vendorId = entry.vendorId,
            amount = entry.amount,
            paymentKind = entry.paymentKind,
            paidOn = entry.paidOn,
            assigneeIds = entry.assigneeIds.joinToString(SEPARATOR),
            taskCount = entry.taskCount,
            version = entry.version,
        )
    },
    categories = categories.mapIndexed { position, category -> CategoryRow(category.id, position, category.name) },
    vendors = vendors.map { VendorRow(it.id, it.name) },
    members = wedding.members.mapIndexed { position, member -> MemberRow(member.id, position, member.displayName, member.you) },
)

internal fun plannerOf(stored: Stored): Planner {
    val entries = stored.entries.mapNotNull(::entryOf)
    val byId = entries.associateBy { it.id }
    fun listed(ids: String): List<Entry> = ids.split(SEPARATOR).mapNotNull { byId[it] }
    val planner = stored.planner
    return Planner(
        loadedAt = Instant.ofEpochMilli(planner.loadedAt),
        weddingDate = dateOf(planner.weddingDate),
        currency = planner.currency,
        timeZone = planner.timeZone,
        today = dateOf(planner.today) ?: LocalDate.ofEpochDay(0),
        daysLeft = planner.daysLeft,
        members = stored.members.map { Member(it.id, it.displayName, it.you) },
        entries = entries,
        categories = stored.categories.associate { it.id to it.name },
        vendors = stored.vendors.associate { it.id to it.name },
        overdue = listed(planner.overdueIds),
        next = listed(planner.nextIds),
        nextPayments = listed(planner.nextPaymentIds),
        budget = Budget(planner.budgetTotal, planner.budgetPlanned, planner.budgetCommitted, planner.budgetPaid),
    )
}

/** An entry of a kind this version of the app does not know belongs in none of its lists and is left out. */
private fun entryOf(row: EntryRow): Entry? {
    val kind = Kind.entries.firstOrNull { it.name == row.kind } ?: return null
    return Entry(
        id = row.id,
        kind = kind,
        title = row.title,
        notes = row.notes,
        categoryId = row.categoryId,
        startDate = dateOf(row.startDate),
        dueDate = dateOf(row.dueDate),
        status = Status.entries.firstOrNull { it.name == row.status } ?: Status.OTHER,
        priority = Priority.entries.firstOrNull { it.name == row.priority } ?: Priority.NORMAL,
        priorityReason = row.priorityReason,
        parentId = row.parentId,
        vendorId = row.vendorId,
        amount = row.amount,
        paymentKind = row.paymentKind,
        paidOn = dateOf(row.paidOn),
        assigneeIds = if (row.assigneeIds.isEmpty()) emptyList() else row.assigneeIds.split(SEPARATOR),
        taskCount = row.taskCount,
        version = row.version,
    )
}

private fun dateOf(text: String?): LocalDate? =
    try {
        text?.let(LocalDate::parse)
    } catch (notADate: DateTimeParseException) {
        null
    }
