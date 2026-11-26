package io.github.trufelek124.weddingplanner.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trufelek124.weddingplanner.ServerAddress
import io.github.trufelek124.weddingplanner.api.Api
import io.github.trufelek124.weddingplanner.api.StandInServer
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Steps of the plan added and changed on the phone (S10): kept, shown at once, and sent as the changes of a task are,
 * in the order they were made in. A stand-in server that keeps what it is sent, a real database.
 */
@RunWith(RobolectricTestRunner::class)
class StepChangesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val server = StandInServer()
    private val clock = Clock.fixed(Instant.parse("2026-11-18T13:05:00Z"), ZoneId.of("Europe/Warsaw"))
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val tokens = object : Api.AccessTokens {
        override suspend fun current(): String? = "access-1"
        override suspend fun renewed(refused: String): String? = null
    }
    private val api = Api(server.engine, ServerAddress.parse("https://planner.example"), tokens)
    private lateinit var database: PlannerDatabase
    private lateinit var planner: PlannerRepository
    private lateinit var theirs: LivingPlanner

    private val venue = StandIn.entry("p1", "Book the venue", kind = "PLAN_ITEM", startDate = "2026-11-01", dueDate = "2026-12-15", version = 4)

    @Before
    fun aPhoneThatLoaded() = runBlocking {
        database = Room.databaseBuilder(context, PlannerDatabase::class.java, folder.root.resolve("planner.db").path).build()
        planner = PlannerRepository({ api }, database, clock, folder.root.resolve("shares"))
        theirs = LivingPlanner(server, entries = listOf(venue))
        assertEquals(Load.LOADED, planner.sync())
        server.calls.clear()
    }

    @After
    fun close() {
        database.close()
    }

    private suspend fun held(): Planner = planner.planner.first()!!

    private fun posts() = server.routes("POST /api/entries").map { Json.parseToJsonElement(it.body).jsonObject }

    private val invitations = TaskFields(title = "Send the invitations", startDate = LocalDate.of(2026, 12, 1), dueDate = LocalDate.of(2026, 12, 20), status = Status.IN_PROGRESS)

    @Test
    fun `a new step stands first, waits, and has its days`() = runBlocking {
        val id = planner.add(invitations, Kind.PLAN_ITEM)

        val step = held().entry(id)!!
        assertEquals(Kind.PLAN_ITEM, step.kind)
        assertEquals(Waiting.TO_BE_SENT, step.waiting)
        assertEquals(LocalDate.of(2026, 12, 1), step.startDate)
        assertEquals(LocalDate.of(2026, 12, 20), step.dueDate)
        assertEquals(listOf(id, "p1"), held().planItems.map { it.id })
        assertTrue(held().tasks.isEmpty())
        assertEquals(1, held().unsent)
        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun `a new step is sent as a step with the id the phone gave it, and is there once`() = runBlocking {
        val id = planner.add(invitations, Kind.PLAN_ITEM)

        assertEquals(Load.LOADED, planner.sync())
        assertEquals(Load.LOADED, planner.sync())

        val sent = posts().single()
        assertEquals("PLAN_ITEM", sent.getValue("kind").jsonPrimitive.content)
        assertEquals(id, sent.getValue("id").jsonPrimitive.content)
        assertEquals("2026-12-01", sent.getValue("startDate").jsonPrimitive.content)
        assertEquals("2026-12-20", sent.getValue("dueDate").jsonPrimitive.content)
        assertEquals(1, theirs.entries.values.count { it.getValue("title").jsonPrimitive.content == "Send the invitations" })
        assertNull(held().entry(id)!!.waiting)
        assertEquals(Kind.PLAN_ITEM, held().entry(id)!!.kind)
    }

    @Test
    fun `a task of a step that waits is sent after the step, whichever way the plan is read`() = runBlocking {
        val step = planner.add(TaskFields(title = "Plan the honeymoon"), Kind.PLAN_ITEM)
        val first = planner.add(TaskFields(title = "Book the flights", parentId = step))
        val second = planner.add(TaskFields(title = "Book the hotel", parentId = step))
        assertEquals(listOf(first, second), held().tasksOf(held().entry(step)!!).map { it.id }.sortedBy { listOf(first, second).indexOf(it) })

        assertEquals(Load.LOADED, planner.sync())

        assertEquals(listOf("PLAN_ITEM", "TASK", "TASK"), posts().map { it.getValue("kind").jsonPrimitive.content })
        assertEquals(listOf(step, first, second), posts().map { it.getValue("id").jsonPrimitive.content })
        assertEquals(listOf(null, step, step), posts().map { it.getValue("parentId").let { parent -> if (parent is kotlinx.serialization.json.JsonNull) null else parent.jsonPrimitive.content } })
    }

    @Test
    fun `changing a step that waits keeps it a step and one request`() = runBlocking {
        val id = planner.add(TaskFields(title = "Plan the honeymoon"), Kind.PLAN_ITEM)
        planner.edit(id, TaskFields(title = "Plan the honeymoon in Portugal", startDate = LocalDate.of(2027, 3, 1), dueDate = LocalDate.of(2027, 3, 15)))

        assertEquals(Kind.PLAN_ITEM, held().entry(id)!!.kind)
        planner.sync()

        val sent = posts().single()
        assertEquals("PLAN_ITEM", sent.getValue("kind").jsonPrimitive.content)
        assertEquals("Plan the honeymoon in Portugal", sent.getValue("title").jsonPrimitive.content)
        assertEquals("2027-03-01", sent.getValue("startDate").jsonPrimitive.content)
    }

    @Test
    fun `a change of a step is sent with the version it was made on, and with its days`() = runBlocking {
        val before = held().entry("p1")!!
        planner.edit("p1", before.fields.copy(title = "Book the venue and the band", dueDate = LocalDate.of(2026, 12, 20)))

        val shown = held().entry("p1")!!
        assertEquals("Book the venue and the band", shown.title)
        assertEquals(LocalDate.of(2026, 11, 1), shown.startDate)
        assertEquals(LocalDate.of(2026, 12, 20), shown.dueDate)
        assertEquals(Waiting.TO_BE_SENT, shown.waiting)

        planner.sync()

        val sent = Json.parseToJsonElement(server.routes("PUT /api/entries/p1").single().body).jsonObject
        assertEquals("4", sent.getValue("version").toString())
        assertEquals("2026-11-01", sent.getValue("startDate").jsonPrimitive.content)
        assertEquals("2026-12-20", sent.getValue("dueDate").jsonPrimitive.content)
        assertNull(held().entry("p1")!!.waiting)
        assertEquals(5, held().entry("p1")!!.version)
    }

    @Test
    fun `a step that was changed elsewhere meanwhile is not overwritten`() = runBlocking {
        planner.edit("p1", held().entry("p1")!!.fields.copy(title = "Book the venue on Saturday"))
        theirs.put(StandIn.entry("p1", "Book the venue together", kind = "PLAN_ITEM", startDate = "2026-11-02", dueDate = "2026-12-30", version = 5))

        planner.sync()

        assertEquals("Book the venue together", theirs.titleOf("p1"))
        val shown = held().entry("p1")!!
        assertEquals("Book the venue together", shown.title)
        assertEquals(Waiting.CHANGED_MEANWHILE, shown.waiting)
        assertEquals(LocalDate.of(2026, 11, 2), shown.startDate)
    }

    @Test
    fun `a step that is gone from the server is said to be a step`() = runBlocking {
        server.down = true
        planner.edit("p1", held().entry("p1")!!.fields.copy(title = "Book the venue and the band"))
        server.down = false
        theirs.entries.remove("p1")

        planner.sync()

        val gone = held().gone.single()
        assertEquals("Book the venue and the band", gone.title)
        assertTrue(gone.step)
        assertEquals("Gone[entryId=p1]", gone.toString())
    }

    @Test
    fun `a task that is gone is not said to be a step`() = runBlocking {
        theirs.put(StandIn.entry("t9", "Choose the rings"))
        planner.sync()
        planner.setStatus("t9", Status.DONE)
        theirs.entries.remove("t9")

        planner.sync()

        assertFalse(held().gone.single().step)
    }

    @Test
    fun `a step that waits is still there after the app was closed`() = runBlocking {
        server.down = true
        val id = planner.add(invitations, Kind.PLAN_ITEM)
        planner.sync()

        database.close()
        database = Room.databaseBuilder(context, PlannerDatabase::class.java, folder.root.resolve("planner.db").path).build()
        planner = PlannerRepository({ api }, database, clock, folder.root.resolve("shares"))

        val step = held().entry(id)!!
        assertEquals(Kind.PLAN_ITEM, step.kind)
        assertEquals(Waiting.TO_BE_SENT, step.waiting)
        assertEquals(1, planner.stillToSend())
    }

    @Test
    fun `a step is not printed with its title`() = runBlocking {
        planner.add(TaskFields(title = "Surprise honeymoon", notes = "Do not tell Marek."), Kind.PLAN_ITEM)

        val stored = database.planner().stored()!!
        assertFalse(stored.changes.single().toString().contains("honeymoon"))
        assertFalse(stored.changes.single().toString().contains("Marek"))
        assertFalse(plannerOf(stored).toString().contains("honeymoon"))
    }
}
