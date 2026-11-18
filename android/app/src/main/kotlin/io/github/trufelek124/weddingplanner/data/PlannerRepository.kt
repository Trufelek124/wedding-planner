package io.github.trufelek124.weddingplanner.data

import androidx.room.InvalidationTracker
import io.github.trufelek124.weddingplanner.api.Api
import io.github.trufelek124.weddingplanner.api.ApiFailure
import io.ktor.http.HttpMethod
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
 * database, and hands out what is kept. A change made on the phone is kept as well, shown at once,
 * and sent when the server can be reached.
 */
class PlannerRepository(private val api: () -> Api, private val database: PlannerDatabase, private val clock: Clock) {

    private val dao = database.planner()

    /** Held for every piece of writing to the database, never while waiting for the server. */
    private val writing = Mutex()

    /** Held while talking to the server, so that sending and loading take turns. */
    private val talking = Mutex()

    /** How often the planner was removed from the phone; what the server answers to a question from before is not kept. */
    @Volatile
    private var forgotten = 0

    /**
     * What the phone holds, with the waiting changes already in it, again whenever it changes;
     * null while nothing was loaded for whoever is signed in.
     */
    val planner: Flow<Planner?> = database.invalidationTracker.changesOf(PlannerDatabase.TABLES).map { dao.stored()?.let(::plannerOf) }

    /**
     * Brings the phone and the server together: first sends what waits on the phone, then loads the
     * planner again. What the load says therefore already has the phone's own changes in it.
     */
    suspend fun sync(): Load = talking.withLock {
        if (send() == Sending.SIGNED_OUT) Load.SIGNED_OUT else load()
    }

    // ---------------------------------------------------------------- changing a task

    /** Ticks a task off, or takes the tick away again. Shown at once; sent with the next [sync]. */
    suspend fun setStatus(entryId: String, status: Status) = writing.withLock {
        val row = dao.entry(entryId)
        val waiting = dao.changeOf(entryId)
        when {
            waiting == null -> {
                if (row != null && row.status != status.name) {
                    dao.putChange(ChangeRow(entryId = entryId, kind = ChangeKind.STATUS, baseVersion = row.version, body = statusBody(status).toString(), title = row.title))
                }
            }
            // The entry has to be looked at first: see [letGo].
            waiting.trouble == Trouble.CHANGED_MEANWHILE || waiting.trouble == Trouble.GONE -> Unit
            waiting.kind == ChangeKind.STATUS ->
                // Ticked and unticked again before it was sent: nothing is left to send.
                if (row != null && row.status == status.name) {
                    dao.deleteChange(waiting.seq)
                } else {
                    dao.putChange(waiting.copy(body = statusBody(status).toString(), revision = waiting.revision + 1))
                }
            else -> dao.putChange(
                waiting.copy(
                    body = JsonObject(bodyOf(waiting) + ("status" to JsonPrimitive(status.name))).toString(),
                    revision = waiting.revision + 1,
                    trouble = null,
                    refusals = null,
                ),
            )
        }
    }

    /**
     * Adds a task. It gets its id here, on the phone, so that sending it twice adds it once.
     *
     * @return the new task's id
     */
    suspend fun add(fields: TaskFields): String = writing.withLock {
        val id = UUID.randomUUID().toString()
        dao.putChange(ChangeRow(entryId = id, kind = ChangeKind.ADD, baseVersion = 0, body = fields.toJson().toString(), title = fields.title))
        id
    }

    /** Changes a task. A change that is already waiting for the same task is replaced by this one. */
    suspend fun edit(entryId: String, fields: TaskFields) = writing.withLock {
        val row = dao.entry(entryId)
        val waiting = dao.changeOf(entryId)
        val body = fields.toJson().toString()
        when {
            waiting == null -> {
                if (row != null && fields.copy(assigneeIds = fields.assigneeIds.sorted()) != fieldsOf(row).let { it.copy(assigneeIds = it.assigneeIds.sorted()) }) {
                    dao.putChange(ChangeRow(entryId = entryId, kind = ChangeKind.EDIT, baseVersion = row.version, body = body, title = fields.title))
                }
            }
            waiting.kind == ChangeKind.ADD ->
                dao.putChange(waiting.copy(body = body, title = fields.title, revision = waiting.revision + 1, trouble = null, refusals = null))
            // The form showed the latest version: what is saved from it is a change of that version.
            waiting.trouble == Trouble.CHANGED_MEANWHILE -> if (row != null) {
                dao.putChange(
                    waiting.copy(kind = ChangeKind.EDIT, baseVersion = row.version, body = body, title = fields.title, revision = waiting.revision + 1, trouble = null),
                )
            }
            else -> dao.putChange(
                waiting.copy(kind = ChangeKind.EDIT, body = body, title = fields.title, revision = waiting.revision + 1, trouble = null, refusals = null),
            )
        }
    }

    /** How many changes are waiting to be sent, not counting those the person has to look at first. */
    suspend fun stillToSend(): Int = dao.changes().count { it.trouble == null }

    /** Gives up the change that waits for this task: what the server has stands. */
    suspend fun letGo(entryId: String) = writing.withLock {
        dao.changeOf(entryId)?.let { dao.deleteChange(it.seq) }
    }

    // ---------------------------------------------------------------- talking to the server

    /**
     * Sends the waiting changes, the oldest first. A change the server cannot take is marked and
     * left for the person; without a connection the rest waits for the next time.
     */
    internal suspend fun send(): Sending {
        val since = forgotten
        for (queued in dao.changes()) {
            val change = dao.change(queued.seq) ?: continue
            if (change.trouble != null) {
                continue
            }
            val body = bodyOf(change)
            val id = change.entryId
            try {
                val answer: EntryWire = when (change.kind) {
                    ChangeKind.ADD -> api().send(HttpMethod.Post, "/api/entries", requestOf(change))
                    ChangeKind.EDIT -> api().send(HttpMethod.Put, "/api/entries/$id", requestOf(change))
                    else -> api().send(HttpMethod.Put, "/api/entries/$id/status", requestOf(change))
                }
                taken(since, change, answer)
            } catch (failure: ApiFailure) {
                when {
                    failure is ApiFailure.Unreachable -> return Sending.UNREACHABLE
                    failure is ApiFailure.SignedOut -> return Sending.SIGNED_OUT
                    failure is ApiFailure.Problem && failure.isOf(ApiFailure.CHANGED_MEANWHILE) -> {
                        val latest = try {
                            api().get<EntryWire>("/api/entries/$id")
                        } catch (noAnswer: ApiFailure) {
                            // Not known yet what the latest version says: the change waits and is tried again.
                            return if (noAnswer is ApiFailure.SignedOut) Sending.SIGNED_OUT else Sending.UNREACHABLE
                        }
                        if (fieldsOf(body).sameAs(fieldsOf(rowOf(latest, 0)))) {
                            // The server already says what this change wanted: it arrived once before, and only the answer was lost.
                            taken(since, change, latest)
                        } else {
                            troubled(since, change, Trouble.CHANGED_MEANWHILE, latest = latest)
                        }
                    }
                    failure is ApiFailure.Problem && failure.status == 404 -> troubled(since, change, Trouble.GONE)
                    failure is ApiFailure.Problem && failure.isOf(ApiFailure.BAD_REQUEST) ->
                        troubled(since, change, Trouble.REFUSED, refusals = failure.fieldMessages.ifEmpty { mapOf("" to failure.detail.orEmpty()) })
                    // Something is wrong on the server's side: the change waits and is tried again.
                    else -> return Sending.FAILED
                }
            }
        }
        return Sending.ALL_SENT
    }

    /** The server took the change as it was read before sending; what was changed again meanwhile goes on waiting. */
    private suspend fun taken(since: Int, sent: ChangeRow, answer: EntryWire) = writing.withLock {
        if (forgotten != since) {
            return@withLock
        }
        val row = rowOf(answer, dao.entry(sent.entryId)?.position ?: ((dao.lastPosition() ?: -1) + 1))
        val now = dao.change(sent.seq)
        when {
            // Let go of while it was on its way: the server has it all the same.
            now == null -> dao.putEntry(row)
            now.revision == sent.revision -> dao.sent(now, row)
            else -> {
                // Changed again while it was on its way: the rest is a change of what the server now has.
                dao.putEntry(row)
                dao.putChange(now.copy(kind = if (now.kind == ChangeKind.ADD) ChangeKind.EDIT else now.kind, baseVersion = row.version))
            }
        }
    }

    private suspend fun troubled(since: Int, sent: ChangeRow, trouble: String, latest: EntryWire? = null, refusals: Map<String, String>? = null) =
        writing.withLock {
            if (forgotten != since) {
                return@withLock
            }
            if (latest != null) {
                dao.putEntry(rowOf(latest, dao.entry(sent.entryId)?.position ?: ((dao.lastPosition() ?: -1) + 1)))
            }
            dao.change(sent.seq)?.let { now -> dao.putChange(now.copy(trouble = trouble, refusals = refusals?.let(::refusalsJson))) }
        }

    /**
     * Asks the server for everything the screens show and puts it in the place of the last load.
     * The answers belong together: unless all of them came, nothing is replaced.
     */
    internal suspend fun load(): Load {
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
     * Removes the planner from the phone, the waiting changes included: for a sign-out. Rows that
     * were deleted stay readable in the database's files until something is written over them, so
     * the files are cleaned out too.
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

/** What sending the waiting changes came to. */
internal enum class Sending {
    /** Nothing is waiting any more, except what the person has to look at. */
    ALL_SENT,
    UNREACHABLE,
    SIGNED_OUT,
    FAILED,
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
    entries = (entries + home.overdue + home.next + home.nextPayments).distinctBy { it.id }.mapIndexed { position, entry -> rowOf(entry, position) },
    categories = categories.mapIndexed { position, category -> CategoryRow(category.id, position, category.name) },
    vendors = vendors.map { VendorRow(it.id, it.name) },
    members = wedding.members.mapIndexed { position, member -> MemberRow(member.id, position, member.displayName, member.you) },
)

/** An entry as the server sent it, for the phone's table. */
internal fun rowOf(entry: EntryWire, position: Int): EntryRow = EntryRow(
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

/** What the screens show: the last load with the phone's own waiting changes laid over it. */
internal fun plannerOf(stored: Stored): Planner {
    val entries = LinkedHashMap<String, Entry>()
    stored.entries.mapNotNull(::entryOf).forEach { entries[it.id] = it }
    val added = mutableListOf<Entry>()
    val gone = mutableListOf<Gone>()
    for (change in stored.changes) {
        val there = entries[change.entryId]
        val waiting = if (change.trouble == Trouble.REFUSED) Waiting.REFUSED else Waiting.TO_BE_SENT
        when {
            change.kind == ChangeKind.ADD -> added += added(change, waiting)
            // The task is no longer on the server: the change can only be let go of.
            there == null || change.trouble == Trouble.GONE -> gone += Gone(change.entryId, change.title)
            // What is shown is the latest version; the change stays aside until the person has looked.
            change.trouble == Trouble.CHANGED_MEANWHILE -> entries[there.id] = there.copy(waiting = Waiting.CHANGED_MEANWHILE)
            change.kind == ChangeKind.STATUS -> entries[there.id] = there.copy(status = statusOf(bodyOf(change)), waiting = waiting)
            else -> entries[there.id] = changed(there, fieldsOf(bodyOf(change)), waiting, refusalsOf(change))
        }
    }
    // What was just added on the phone stands first, where it is seen.
    val all = added + entries.values
    val byId = all.associateBy { it.id }
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
        entries = all,
        categories = stored.categories.associate { it.id to it.name },
        vendors = stored.vendors.associate { it.id to it.name },
        overdue = listed(planner.overdueIds),
        next = listed(planner.nextIds),
        nextPayments = listed(planner.nextPaymentIds),
        budget = Budget(planner.budgetTotal, planner.budgetPlanned, planner.budgetCommitted, planner.budgetPaid),
        gone = gone,
    )
}

/** A task that so far exists only on the phone. */
private fun added(change: ChangeRow, waiting: Waiting): Entry {
    val fields = fieldsOf(bodyOf(change))
    return Entry(
        id = change.entryId, kind = Kind.TASK, title = fields.title, notes = fields.notes, categoryId = fields.categoryId, startDate = null,
        dueDate = fields.dueDate, status = fields.status, priority = fields.priorityByHand ?: Priority.NORMAL,
        // How urgent it is the server works out once it has it.
        priorityReason = if (fields.priorityByHand == null) Entry.NOT_WORKED_OUT else "SET_BY_HAND",
        parentId = fields.parentId, vendorId = fields.vendorId, amount = fields.amount, paymentKind = fields.paymentKind, paidOn = fields.paidOn,
        assigneeIds = fields.assigneeIds, taskCount = 0, version = 0, byHand = fields.priorityByHand != null, waiting = waiting,
        refusals = refusalsOf(change),
    )
}

private fun changed(entry: Entry, fields: TaskFields, waiting: Waiting, refusals: Map<String, String>): Entry = entry.copy(
    title = fields.title, notes = fields.notes, categoryId = fields.categoryId, dueDate = fields.dueDate, status = fields.status,
    assigneeIds = fields.assigneeIds, parentId = fields.parentId, vendorId = fields.vendorId, amount = fields.amount,
    paymentKind = fields.paymentKind, paidOn = fields.paidOn, byHand = fields.priorityByHand != null,
    priority = fields.priorityByHand ?: entry.priority,
    priorityReason = when {
        fields.priorityByHand != null -> "SET_BY_HAND"
        entry.byHand -> Entry.NOT_WORKED_OUT
        else -> entry.priorityReason
    },
    waiting = waiting, refusals = refusals,
)

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
        byHand = row.priorityMode == "MANUAL",
    )
}

private fun dateOf(text: String?): LocalDate? =
    try {
        text?.let(LocalDate::parse)
    } catch (notADate: DateTimeParseException) {
        null
    }
