package io.github.trufelek124.weddingplanner.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trufelek124.weddingplanner.ServerAddress
import io.github.trufelek124.weddingplanner.api.Api
import io.github.trufelek124.weddingplanner.api.StandInServer
import io.github.trufelek124.weddingplanner.api.respondJson
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * Changes made on the phone: kept, shown at once, sent when the server can be reached, and never
 * sent over what someone else changed in the meantime. A stand-in server, a real database.
 */
@RunWith(RobolectricTestRunner::class)
class ChangesTest {

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

    private fun open(): PlannerDatabase =
        Room.databaseBuilder(context, PlannerDatabase::class.java, folder.root.resolve("planner.db").path).build()

    private fun deposit(status: String = "NOT_STARTED", version: Int = 3, title: String = "Pay the deposit") = StandIn.entry(
        "t1", title, dueDate = "2026-11-10", status = status, priority = "CRITICAL", priorityReason = "OVERDUE", parentId = "p1",
        categoryId = "c1", vendorId = "v1", amount = 250000, paymentKind = "DEPOSIT", assigneeIds = listOf(StandIn.MAREK), version = version,
        paidOn = if (status == "DONE") "2026-11-18" else null,
    )

    private fun rings(status: String = "NOT_STARTED", version: Int = 0) = StandIn.entry("t2", "Choose the rings", status = status, version = version)

    private val venue = StandIn.entry("p1", "Book the venue", kind = "PLAN_ITEM", dueDate = "2026-12-15", taskCount = 1)

    private fun theServerHas(vararg entries: String) {
        server.planner(
            entries = entries.toList(),
            categories = listOf(StandIn.category("c1", "Venue")),
            vendors = listOf(StandIn.vendor("v1", "Palace on the Hill")),
        )
    }

    @Before
    fun aPhoneThatLoaded() = runBlocking {
        database = open()
        planner = PlannerRepository({ api }, database, clock, folder.root.resolve("shares"))
        theServerHas(deposit(), rings(), venue)
        assertEquals(Load.LOADED, planner.sync())
        server.calls.clear()
    }

    @After
    fun close() {
        database.close()
    }

    private suspend fun held(): Planner = planner.planner.first()!!

    /** The server refuses the change of t1 as out of date and has this newer version of it, wherever it is asked. */
    private fun theServerHasANewer(deposit: String) {
        server.problem("PUT /api/entries/t1", 409, "changed-meanwhile")
        server.json("GET /api/entries/t1", deposit)
        theServerHas(deposit, rings(), venue)
    }

    private fun sent(): List<String> = server.calls.map { it.route }.filterNot { it.startsWith("GET") }

    private fun bodyOf(route: String) = Json.parseToJsonElement(server.routes(route).last().body).jsonObject

    // ---------------------------------------------------------------- ticking off

    @Test
    fun `a tick shows at once and waits to be sent`() = runBlocking {
        planner.setStatus("t1", Status.DONE)

        val task = held().entry("t1")!!
        assertEquals(Status.DONE, task.status)
        assertEquals(Waiting.TO_BE_SENT, task.waiting)
        assertEquals(1, held().waiting)
        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun `the next time the server is reached the tick is sent, before the planner is loaded again`() = runBlocking {
        planner.setStatus("t1", Status.DONE)
        server.json("PUT /api/entries/t1/status", deposit(status = "DONE", version = 4))
        theServerHas(deposit(status = "DONE", version = 4), rings(), venue)

        assertEquals(Load.LOADED, planner.sync())

        assertEquals("PUT /api/entries/t1/status", server.calls.first().route)
        assertEquals("""{"status":"DONE"}""", server.calls.first().body)
        assertEquals("Bearer access-1", server.calls.first().authorization)
        val task = held().entry("t1")!!
        assertEquals(Status.DONE, task.status)
        assertNull(task.waiting)
        assertEquals(4, task.version)
        assertEquals(LocalDate.of(2026, 11, 18), task.paidOn)
        assertEquals(0, held().waiting)
    }

    @Test
    fun `ticked and unticked again before it was sent, nothing is sent`() = runBlocking {
        planner.setStatus("t1", Status.DONE)
        planner.setStatus("t1", Status.NOT_STARTED)

        assertNull(held().entry("t1")!!.waiting)
        planner.sync()
        assertTrue(sent().isEmpty())
    }

    @Test
    fun `without a connection the tick stays, also after the app was closed, and is sent later`() = runBlocking {
        server.plannerUnreachable()
        planner.setStatus("t1", Status.DONE)

        assertEquals(Load.UNREACHABLE, planner.sync())
        assertEquals(Waiting.TO_BE_SENT, held().entry("t1")!!.waiting)

        database.close()
        database = open()
        planner = PlannerRepository({ api }, database, clock, folder.root.resolve("shares"))
        assertEquals(Status.DONE, held().entry("t1")!!.status)
        assertEquals(Waiting.TO_BE_SENT, held().entry("t1")!!.waiting)

        server.json("PUT /api/entries/t1/status", deposit(status = "DONE", version = 4))
        theServerHas(deposit(status = "DONE", version = 4), rings(), venue)
        assertEquals(Load.LOADED, planner.sync())
        assertEquals(2, server.routes("PUT /api/entries/t1/status").size)
        assertNull(held().entry("t1")!!.waiting)
    }

    @Test
    fun `a load does not take a waiting tick away`() = runBlocking {
        planner.setStatus("t2", Status.DONE)
        // The tick cannot be sent, but the planner can be read: what is shown is the server's with the tick over it.
        server.problem("PUT /api/entries/t2/status", 500, "something-went-wrong")
        theServerHas(deposit(), rings(), venue)

        assertEquals(Load.LOADED, planner.sync())

        assertEquals(Status.DONE, held().entry("t2")!!.status)
        assertEquals(Waiting.TO_BE_SENT, held().entry("t2")!!.waiting)
    }

    // ---------------------------------------------------------------- adding

    @Test
    fun `a new task stands first, waits, and is sent with the id the phone gave it`() = runBlocking {
        val id = planner.add(
            TaskFields(title = "Order the flowers", notes = "Peonies if they have them.", categoryId = "c1", dueDate = LocalDate.of(2027, 5, 2), assigneeIds = listOf(StandIn.ANNA), parentId = "p1", vendorId = "v1"),
        )

        val task = held().tasks.first()
        assertEquals(id, task.id)
        assertEquals("Order the flowers", task.title)
        assertEquals(Waiting.TO_BE_SENT, task.waiting)
        assertEquals("Anna", held().peopleOf(task))

        val taken = StandIn.entry(id, "Order the flowers", dueDate = "2027-05-02", categoryId = "c1", parentId = "p1", vendorId = "v1", assigneeIds = listOf(StandIn.ANNA), notes = "Peonies if they have them.")
        server.json("POST /api/entries", taken)
        theServerHas(deposit(), rings(), venue, taken)
        assertEquals(Load.LOADED, planner.sync())

        assertEquals(
            Json.parseToJsonElement(
                """{"title":"Order the flowers","notes":"Peonies if they have them.","categoryId":"c1","startDate":null,"dueDate":"2027-05-02",
                   "status":"NOT_STARTED","assigneeIds":["${StandIn.ANNA}"],"priorityMode":"AUTO","priority":null,"parentId":"p1","vendorId":"v1",
                   "amount":null,"paymentKind":null,"paidOn":null,"id":"$id","kind":"TASK"}""",
            ),
            bodyOf("POST /api/entries"),
        )
        assertEquals(1, held().tasks.count { it.title == "Order the flowers" })
        assertNull(held().entry(id)!!.waiting)
    }

    @Test
    fun `a new task whose answer was lost is sent again with the same id`() = runBlocking {
        val id = planner.add(TaskFields(title = "Order the flowers"))
        // The server took it, but no answer came back.
        server.unreachable("POST /api/entries")
        planner.sync()
        planner.sync()

        server.json("POST /api/entries", StandIn.entry(id, "Order the flowers"))
        planner.sync()

        val ids = server.routes("POST /api/entries").map { Json.parseToJsonElement(it.body).jsonObject.getValue("id").jsonPrimitive.content }
        assertEquals(3, ids.size)
        assertEquals(setOf(id), ids.toSet())
    }

    @Test
    fun `a payment is added with its amount in whole grosze, its part of the price and who pays`() = runBlocking {
        val id = planner.add(
            TaskFields(title = "Pay the band", amount = 120050, paymentKind = "INSTALMENT", priorityByHand = Priority.HIGH, status = Status.DONE, paidOn = LocalDate.of(2026, 11, 17)),
        )
        server.json("POST /api/entries", StandIn.entry(id, "Pay the band", amount = 120050, paymentKind = "INSTALMENT", status = "DONE", paidOn = "2026-11-17"))

        planner.sync()

        val body = bodyOf("POST /api/entries")
        assertEquals("120050", body.getValue("amount").toString())
        assertEquals("\"INSTALMENT\"", body.getValue("paymentKind").toString())
        assertEquals("\"MANUAL\"", body.getValue("priorityMode").toString())
        assertEquals("\"HIGH\"", body.getValue("priority").toString())
        assertEquals("\"2026-11-17\"", body.getValue("paidOn").toString())
    }

    // ---------------------------------------------------------------- editing

    @Test
    fun `an edit shows at once and is sent with the version it was made on`() = runBlocking {
        val before = held().entry("t1")!!
        planner.edit("t1", before.fields.copy(title = "Pay the deposit to the palace", dueDate = LocalDate.of(2026, 11, 20), amount = 300000))

        val shown = held().entry("t1")!!
        assertEquals("Pay the deposit to the palace", shown.title)
        assertEquals(300000L, shown.amount)
        assertEquals(Waiting.TO_BE_SENT, shown.waiting)
        // What the server worked out stays on the screen until it says otherwise.
        assertEquals(Priority.CRITICAL, shown.priority)

        server.json("PUT /api/entries/t1", deposit(version = 4, title = "Pay the deposit to the palace"))
        planner.sync()

        val body = bodyOf("PUT /api/entries/t1")
        assertEquals("3", body.getValue("version").toString())
        assertEquals("\"Pay the deposit to the palace\"", body.getValue("title").toString())
        assertEquals("\"2026-11-20\"", body.getValue("dueDate").toString())
        assertEquals("300000", body.getValue("amount").toString())
        assertEquals("\"DEPOSIT\"", body.getValue("paymentKind").toString())
        assertEquals("[\"${StandIn.MAREK}\"]", body.getValue("assigneeIds").toString())
        assertNull(held().entry("t1")!!.waiting)
    }

    @Test
    fun `saving a task as it is sends nothing`() = runBlocking {
        planner.edit("t1", held().entry("t1")!!.fields)

        assertNull(held().entry("t1")!!.waiting)
        planner.sync()
        assertTrue(sent().isEmpty())
    }

    @Test
    fun `changes to one task are folded into one - the last word counts`() = runBlocking {
        // A new task, changed before it was sent: one request, with the latest words.
        val id = planner.add(TaskFields(title = "Order the flowers"))
        planner.edit(id, TaskFields(title = "Order the flowers and the arch"))
        planner.setStatus(id, Status.DONE)
        // A tick, then an edit of the same task: one full change, made on the version the tick was made on.
        planner.setStatus("t1", Status.DONE)
        planner.edit("t1", held().entry("t1")!!.fields.copy(notes = "Paid by transfer."))
        // An edit, then a tick: the edit carries the tick.
        planner.edit("t2", held().entry("t2")!!.fields.copy(title = "Choose and order the rings"))
        planner.setStatus("t2", Status.DONE)
        server.json("POST /api/entries", StandIn.entry(id, "Order the flowers and the arch", status = "DONE"))
        server.json("PUT /api/entries/t1", deposit(status = "DONE", version = 4))
        server.json("PUT /api/entries/t2", rings(status = "DONE", version = 1))

        planner.sync()

        assertEquals(listOf("POST /api/entries", "PUT /api/entries/t1", "PUT /api/entries/t2"), sent())
        assertEquals("\"Order the flowers and the arch\"", bodyOf("POST /api/entries").getValue("title").toString())
        assertEquals("\"DONE\"", bodyOf("POST /api/entries").getValue("status").toString())
        assertEquals("\"DONE\"", bodyOf("PUT /api/entries/t1").getValue("status").toString())
        assertEquals("\"Paid by transfer.\"", bodyOf("PUT /api/entries/t1").getValue("notes").toString())
        assertEquals("3", bodyOf("PUT /api/entries/t1").getValue("version").toString())
        assertEquals("\"DONE\"", bodyOf("PUT /api/entries/t2").getValue("status").toString())
        assertEquals("\"Choose and order the rings\"", bodyOf("PUT /api/entries/t2").getValue("title").toString())
    }

    // ---------------------------------------------------------------- what the server may answer

    @Test
    fun `an edit of a task someone else changed meanwhile is not sent over theirs`() = runBlocking {
        planner.edit("t1", held().entry("t1")!!.fields.copy(title = "Pay the deposit today"))
        // The other owner moved the day in the meantime: the server has a newer version.
        theServerHasANewer(StandIn.entry("t1", "Pay the deposit", dueDate = "2026-11-25", amount = 250000, paymentKind = "DEPOSIT", version = 5))

        planner.sync()

        // The latest version is shown, without the change, and the task says that it has to be looked at.
        val shown = held().entry("t1")!!
        assertEquals("Pay the deposit", shown.title)
        assertEquals(LocalDate.of(2026, 11, 25), shown.dueDate)
        assertEquals(5, shown.version)
        assertEquals(Waiting.CHANGED_MEANWHILE, shown.waiting)
        assertEquals(0, held().waiting)

        // It is not tried again by itself.
        server.calls.clear()
        planner.sync()
        assertTrue(sent().isEmpty())
        assertEquals(Waiting.CHANGED_MEANWHILE, held().entry("t1")!!.waiting)
    }

    @Test
    fun `letting go of such a change leaves the latest version as it is`() = runBlocking {
        planner.edit("t1", held().entry("t1")!!.fields.copy(title = "Pay the deposit today"))
        theServerHasANewer(deposit(version = 5, title = "Pay the first deposit"))
        planner.sync()

        planner.letGo("t1")

        assertNull(held().entry("t1")!!.waiting)
        assertEquals("Pay the first deposit", held().entry("t1")!!.title)
    }

    @Test
    fun `saving again from the latest version is a change of that version`() = runBlocking {
        planner.edit("t1", held().entry("t1")!!.fields.copy(title = "Pay the deposit today"))
        theServerHasANewer(deposit(version = 5, title = "Pay the first deposit"))
        planner.sync()

        planner.edit("t1", held().entry("t1")!!.fields.copy(title = "Pay the first deposit today"))
        server.json("PUT /api/entries/t1", deposit(version = 6, title = "Pay the first deposit today"))
        theServerHas(deposit(version = 6, title = "Pay the first deposit today"), rings(), venue)
        planner.sync()

        assertEquals("5", bodyOf("PUT /api/entries/t1").getValue("version").toString())
        assertEquals("Pay the first deposit today", held().entry("t1")!!.title)
        assertNull(held().entry("t1")!!.waiting)
    }

    @Test
    fun `an edit the server already has, because only its answer was lost, is not called a clash`() = runBlocking {
        val mine = held().entry("t1")!!.fields.copy(title = "Pay the deposit today")
        planner.edit("t1", mine)
        // The first try arrived and the answer was lost; the second try is refused, because the version moved on.
        theServerHasANewer(deposit(version = 4, title = "Pay the deposit today"))

        planner.sync()

        assertNull(held().entry("t1")!!.waiting)
        assertEquals(4, held().entry("t1")!!.version)
        assertEquals("Pay the deposit today", held().entry("t1")!!.title)
    }

    @Test
    fun `a change to a task that is gone from the server is named, and can only be let go of`() = runBlocking {
        planner.setStatus("t2", Status.DONE)
        server.problem("PUT /api/entries/t2/status", 404, "not-found")
        theServerHas(deposit(), venue)

        planner.sync()

        assertEquals(listOf(Gone("t2", "Choose the rings")), held().gone)
        assertNull(held().entry("t2"))
        assertEquals(0, held().waiting)

        planner.letGo("t2")
        assertTrue(held().gone.isEmpty())
    }

    @Test
    fun `what the server refuses is kept with what is wrong, to be put right and sent again`() = runBlocking {
        val id = planner.add(TaskFields(title = "Order the flowers", parentId = "p9"))
        server.problem("POST /api/entries", 400, "bad-request", ""","fieldProblems":[{"field":"parentId","message":"Choose one of your plan items."}]""")

        planner.sync()

        val task = held().entry(id)!!
        assertEquals(Waiting.REFUSED, task.waiting)
        assertEquals(mapOf("parentId" to "Choose one of your plan items."), task.refusals)
        assertEquals("Order the flowers", task.title)
        server.calls.clear()
        planner.sync()
        assertTrue(sent().isEmpty())

        planner.edit(id, task.fields.copy(parentId = "p1"))
        assertEquals(Waiting.TO_BE_SENT, held().entry(id)!!.waiting)
        server.json("POST /api/entries", StandIn.entry(id, "Order the flowers", parentId = "p1"))
        theServerHas(deposit(), rings(), venue, StandIn.entry(id, "Order the flowers", parentId = "p1"))
        planner.sync()
        assertEquals("\"p1\"", bodyOf("POST /api/entries").getValue("parentId").toString())
        assertNull(held().entry(id)!!.waiting)
    }

    @Test
    fun `a server in trouble does not lose the change - it is tried again`() = runBlocking {
        planner.setStatus("t1", Status.DONE)
        server.problem("PUT /api/entries/t1/status", 500, "something-went-wrong")
        planner.sync()
        assertEquals(Waiting.TO_BE_SENT, held().entry("t1")!!.waiting)

        server.json("PUT /api/entries/t1/status", deposit(status = "DONE", version = 4))
        planner.sync()

        assertNull(held().entry("t1")!!.waiting)
    }

    @Test
    fun `changes are sent in the order they were made, and one the server refuses does not hold up the rest`() = runBlocking {
        val id = planner.add(TaskFields(title = "Order the flowers", parentId = "p9"))
        planner.setStatus("t2", Status.DONE)
        planner.setStatus("t1", Status.DONE)
        server.problem("POST /api/entries", 400, "bad-request", ""","fieldProblems":[{"field":"parentId","message":"Choose one of your plan items."}]""")
        server.json("PUT /api/entries/t2/status", rings(status = "DONE", version = 1))
        server.json("PUT /api/entries/t1/status", deposit(status = "DONE", version = 4))

        planner.sync()

        assertEquals(listOf("POST /api/entries", "PUT /api/entries/t2/status", "PUT /api/entries/t1/status"), sent())
        assertEquals(Waiting.REFUSED, held().entry(id)!!.waiting)
        assertNull(held().entry("t1")!!.waiting)
        assertNull(held().entry("t2")!!.waiting)
    }

    @Test
    fun `a change made while an earlier one is on its way is a change of what the server then has`() = runBlocking {
        planner.setStatus("t1", Status.DONE)
        val arrived = CompletableDeferred<Unit>()
        val mayAnswer = CompletableDeferred<Unit>()
        server.answer("PUT /api/entries/t1/status") {
            arrived.complete(Unit)
            mayAnswer.await()
            respondJson(deposit(status = "DONE", version = 4))
        }

        // Should the request never arrive, the test fails here and does not wait for ever.
        withTimeout(20_000) {
            val sending = async { planner.sync() }
            arrived.await()
            planner.edit("t1", held().entry("t1")!!.fields.copy(notes = "Paid by transfer."))
            mayAnswer.complete(Unit)
            sending.await()
        }

        // The tick is with the server; the note still waits, and is sent as a change of version 4.
        assertEquals(Waiting.TO_BE_SENT, held().entry("t1")!!.waiting)
        server.json("PUT /api/entries/t1", deposit(status = "DONE", version = 5))
        theServerHas(deposit(status = "DONE", version = 5), rings(), venue)
        planner.sync()
        assertEquals("4", bodyOf("PUT /api/entries/t1").getValue("version").toString())
        assertEquals("\"Paid by transfer.\"", bodyOf("PUT /api/entries/t1").getValue("notes").toString())
        assertNull(held().entry("t1")!!.waiting)
    }

    @Test
    fun `signing out removes the waiting changes too`() = runBlocking {
        planner.add(TaskFields(title = "Order the flowers for the secret party"))
        planner.setStatus("t1", Status.DONE)

        planner.forget()

        assertNull(planner.planner.first())
        val onDisk = folder.root.listFiles()!!.filter { it.isFile }.joinToString("") { it.readBytes().toString(Charsets.ISO_8859_1) }
        assertFalse(onDisk.contains("secret party"))
        theServerHas(deposit(), rings(), venue)
        planner.sync()
        assertTrue(sent().isEmpty())
        assertEquals(0, held().waiting)
    }
}
