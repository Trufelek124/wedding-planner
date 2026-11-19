package io.github.trufelek124.weddingplanner.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A newer version of the app opens what an older one stored. The older database is built from its
 * layout as it was committed (app/schemas), so this is the real step a phone takes when the app
 * is updated.
 */
@RunWith(RobolectricTestRunner::class)
class DatabaseCarriesOverTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Makes a database file exactly as the given version of the app made it. */
    private fun aDatabaseOfVersion(version: Int, file: File, fill: (SQLiteDatabase) -> Unit) {
        val layout = Json.parseToJsonElement(File("schemas/${PlannerDatabase::class.java.name}/$version.json").readText()).jsonObject.getValue("database").jsonObject
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            for (entity in layout.getValue("entities").jsonArray) {
                val table = entity.jsonObject.getValue("tableName").jsonPrimitive.content
                old.execSQL(entity.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                // What makes a column's values unique belongs to the table as the app made it.
                for (index in entity.jsonObject["indices"]?.jsonArray.orEmpty()) {
                    old.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            for (setup in layout.getValue("setupQueries").jsonArray) {
                old.execSQL(setup.jsonPrimitive.content)
            }
            fill(old)
            old.version = version
        }
    }

    @Test
    fun `what version 1 loaded is still there, and changes can wait beside it`() = runBlocking {
        val file = folder.root.resolve("planner.db")
        aDatabaseOfVersion(1, file) { old ->
            old.execSQL(
                """INSERT INTO planner (id, loadedAt, weddingDate, currency, timeZone, daysLeft, today, budgetTotal, budgetPlanned, budgetCommitted,
                   budgetPaid, overdueIds, nextIds, nextPaymentIds)
                   VALUES (1, 1795093500000, '2027-06-19', 'PLN', 'Europe/Warsaw', 213, '2026-11-18', NULL, 0, 250000, 0, 't1', '', 't1')""",
            )
            old.execSQL(
                """INSERT INTO entry (id, position, kind, title, notes, categoryId, startDate, dueDate, status, priorityMode, priority, priorityReason,
                   parentId, vendorId, amount, paymentKind, paidOn, assigneeIds, taskCount, version)
                   VALUES ('t1', 0, 'TASK', 'Pay the deposit', '', NULL, NULL, '2026-11-10', 'NOT_STARTED', 'AUTO', 'CRITICAL', 'OVERDUE',
                   NULL, NULL, 250000, 'DEPOSIT', NULL, '', 0, 3)""",
            )
            old.execSQL("INSERT INTO member (id, position, displayName, you) VALUES ('m1', 0, 'Anna', 1)")
        }

        val database = Room.databaseBuilder(context, PlannerDatabase::class.java, file.path).build()
        try {
            val stored = database.planner().stored()!!

            assertEquals(listOf("Pay the deposit"), stored.entries.map { it.title })
            assertEquals(3, stored.entries.single().version)
            assertEquals("t1", stored.planner.overdueIds)
            assertEquals(listOf("Anna"), stored.members.map { it.displayName })
            assertTrue(stored.changes.isEmpty())

            database.planner().putChange(ChangeRow(entryId = "t1", kind = ChangeKind.STATUS, baseVersion = 3, body = statusBody(Status.DONE).toString(), title = "Pay the deposit"))
            val shown = plannerOf(database.planner().stored()!!)
            assertEquals(Status.DONE, shown.entry("t1")!!.status)
            assertEquals(Waiting.TO_BE_SENT, shown.entry("t1")!!.waiting)
        } finally {
            database.close()
        }
    }

    @Test
    fun `a change that was waiting in version 2 is still waiting, and shares can wait beside it`() = runBlocking {
        val file = folder.root.resolve("planner.db")
        aDatabaseOfVersion(2, file) { old ->
            old.execSQL(
                """INSERT INTO planner (id, loadedAt, weddingDate, currency, timeZone, daysLeft, today, budgetTotal, budgetPlanned, budgetCommitted,
                   budgetPaid, overdueIds, nextIds, nextPaymentIds)
                   VALUES (1, 1795093500000, '2027-06-19', 'PLN', 'Europe/Warsaw', 213, '2026-11-18', NULL, 0, 0, 0, '', 't1', '')""",
            )
            old.execSQL(
                """INSERT INTO entry (id, position, kind, title, notes, categoryId, startDate, dueDate, status, priorityMode, priority, priorityReason,
                   parentId, vendorId, amount, paymentKind, paidOn, assigneeIds, taskCount, version)
                   VALUES ('t1', 0, 'TASK', 'Choose the rings', '', NULL, NULL, NULL, 'NOT_STARTED', 'AUTO', 'NORMAL', 'NO_DATE',
                   NULL, NULL, NULL, NULL, NULL, '', 0, 0)""",
            )
            old.execSQL(
                """INSERT INTO waiting_change (entryId, kind, baseVersion, body, title, revision, trouble, refusals)
                   VALUES ('t1', 'STATUS', 0, '{"status":"DONE"}', 'Choose the rings', 0, NULL, NULL)""",
            )
        }

        val database = Room.databaseBuilder(context, PlannerDatabase::class.java, file.path).build()
        try {
            val shown = plannerOf(database.planner().stored()!!)

            assertEquals(Status.DONE, shown.entry("t1")!!.status)
            assertEquals(Waiting.TO_BE_SENT, shown.entry("t1")!!.waiting)
            assertTrue(shown.boards.isEmpty())
            assertTrue(shown.shares.isEmpty())

            database.planner().putShare(ShareRow(inspirationId = "i1", url = "https://www.instagram.com/p/Cabc123/", boardId = null, note = "", pictures = 0))
            assertEquals(1, plannerOf(database.planner().stored()!!).shares.size)
        } finally {
            database.close()
        }
    }

    @Test
    fun `a vendor, a change and a share of version 3 are still there, the new tables are empty and can be written`() = runBlocking {
        val file = folder.root.resolve("planner.db")
        aDatabaseOfVersion(3, file) { old ->
            old.execSQL(
                """INSERT INTO planner (id, loadedAt, weddingDate, currency, timeZone, daysLeft, today, budgetTotal, budgetPlanned, budgetCommitted,
                   budgetPaid, overdueIds, nextIds, nextPaymentIds)
                   VALUES (1, 1795093500000, '2027-06-19', 'PLN', 'Europe/Warsaw', 213, '2026-11-18', 6000000, 5000000, 250000, 0, '', 't1', '')""",
            )
            old.execSQL(
                """INSERT INTO entry (id, position, kind, title, notes, categoryId, startDate, dueDate, status, priorityMode, priority, priorityReason,
                   parentId, vendorId, amount, paymentKind, paidOn, assigneeIds, taskCount, version)
                   VALUES ('t1', 0, 'TASK', 'Choose the rings', '', NULL, NULL, NULL, 'NOT_STARTED', 'AUTO', 'NORMAL', 'NO_DATE',
                   NULL, 'v1', NULL, NULL, NULL, '', 0, 0)""",
            )
            old.execSQL("INSERT INTO vendor (id, name) VALUES ('v1', 'Palace on the Hill')")
            old.execSQL("INSERT INTO member (id, position, displayName, you) VALUES ('m1', 0, 'Anna', 1)")
            old.execSQL(
                """INSERT INTO waiting_change (entryId, kind, baseVersion, body, title, revision, trouble, refusals)
                   VALUES ('t1', 'STATUS', 0, '{"status":"DONE"}', 'Choose the rings', 0, NULL, NULL)""",
            )
            old.execSQL("INSERT INTO waiting_share (inspirationId, url, boardId, note, pictures, trouble) VALUES ('i1', 'https://www.instagram.com/p/Cabc123/', NULL, '', 0, NULL)")
        }

        val database = Room.databaseBuilder(context, PlannerDatabase::class.java, file.path).build()
        try {
            val shown = plannerOf(database.planner().stored()!!)

            // The vendor is there under its name, with empty details, until the next load fills them.
            assertEquals(mapOf("v1" to "Palace on the Hill"), shown.vendors)
            val vendor = shown.vendorList.single()
            assertEquals("", vendor.phone)
            assertEquals("", vendor.email)
            assertEquals(null, vendor.agreedPrice)
            assertEquals(0L, vendor.committed)
            // What waited, waits.
            assertEquals(Status.DONE, shown.entry("t1")!!.status)
            assertEquals(Waiting.TO_BE_SENT, shown.entry("t1")!!.waiting)
            assertEquals(1, shown.shares.size)
            // What came with version 4 is not there yet, and the screens can tell.
            assertTrue(shown.budgetLines.isEmpty())
            assertTrue(shown.guests.isEmpty())
            assertTrue(shown.households.isEmpty())
            assertEquals(null, shown.guestCounts)
            assertEquals(BudgetRest(0, 0, 0), shown.uncategorised)
            assertEquals(6000000L, shown.budget.total)

            // The new tables can be written and read.
            val before = database.planner().stored()!!
            database.planner().replace(
                Stored(
                    planner = before.planner,
                    entries = before.entries,
                    categories = before.categories,
                    vendors = before.vendors,
                    members = before.members,
                    budgetLines = listOf(BudgetLineRow("c1", 0, "Venue", 5000000, 250000, 0, 1)),
                    guests = listOf(
                        GuestRow("g1", 0, "Jan Nowak", "CONFIRMED", null, "h1", true, 1, 3, "vegetarian", false, "", "", ""),
                    ),
                    households = listOf(HouseholdRow("h1", 0, "The Nowak family")),
                ),
            )
            val after = plannerOf(database.planner().stored()!!)
            assertEquals(listOf("Venue"), after.budgetLines.map { it.name })
            assertEquals(3, after.guests.single().heads)
            assertEquals(listOf("The Nowak family"), after.households.map { it.name })
            assertEquals(Status.DONE, after.entry("t1")!!.status)
        } finally {
            database.close()
        }
    }

    @Test
    fun `a guest of version 4 is still there, nothing waits for them, and a status can wait beside it`() = runBlocking {
        val file = folder.root.resolve("planner.db")
        aDatabaseOfVersion(4, file) { old ->
            old.execSQL(
                """INSERT INTO planner (id, loadedAt, weddingDate, currency, timeZone, daysLeft, today, budgetTotal, budgetPlanned, budgetCommitted,
                   budgetPaid, overdueIds, nextIds, nextPaymentIds)
                   VALUES (1, 1795093500000, '2027-06-19', 'PLN', 'Europe/Warsaw', 213, '2026-11-18', NULL, 0, 0, 0, '', '', '')""",
            )
            old.execSQL(
                """INSERT INTO guest (id, position, name, status, sideMemberId, householdId, plusOne, children, heads, dietaryNeeds,
                   needsAccommodation, phone, email, notes)
                   VALUES ('g1', 0, 'Aunt Krystyna', 'INVITED', NULL, NULL, 1, 0, 2, '', 0, '', '', '')""",
            )
            old.execSQL(
                """INSERT INTO waiting_change (entryId, kind, baseVersion, body, title, revision, trouble, refusals)
                   VALUES ('t1', 'ADD', 0, '{"title":"Choose the rings"}', 'Choose the rings', 0, NULL, NULL)""",
            )
        }

        val database = Room.databaseBuilder(context, PlannerDatabase::class.java, file.path).build()
        try {
            val stored = database.planner().stored()!!

            assertEquals(listOf("Aunt Krystyna"), stored.guests.map { it.name })
            assertEquals(1, stored.changes.size)
            assertTrue(stored.guestStatuses.isEmpty())
            assertTrue(plannerOf(stored).guestTroubles.isEmpty())
            assertEquals(0, plannerOf(stored).guestsChanged)

            database.planner().putGuestStatus(GuestStatusRow(guestId = "g1", status = "CONFIRMED", name = "Aunt Krystyna"))
            val shown = plannerOf(database.planner().stored()!!)
            assertEquals(GuestStatus.CONFIRMED, shown.guest("g1")!!.status)
            assertEquals(Waiting.TO_BE_SENT, shown.guest("g1")!!.waiting)
            // What waited before still waits.
            assertEquals(1, database.planner().stored()!!.changes.size)
        } finally {
            database.close()
        }
    }
}
