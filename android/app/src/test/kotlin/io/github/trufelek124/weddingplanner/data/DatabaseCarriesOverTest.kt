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
}
