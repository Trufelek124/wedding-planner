package io.github.trufelek124.weddingplanner.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trufelek124.weddingplanner.ServerAddress
import io.github.trufelek124.weddingplanner.api.Api
import io.github.trufelek124.weddingplanner.api.StandInServer
import io.github.trufelek124.weddingplanner.api.respondJson
import io.ktor.http.HttpStatusCode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

/** Loading the planner and keeping it on the phone, against a stand-in server and a real database. */
@RunWith(RobolectricTestRunner::class)
class PlannerRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val server = StandInServer()
    private var now = Instant.parse("2026-11-18T13:05:00Z")
    private val clock = object : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneId.of("Europe/Warsaw")
        override fun withZone(zone: ZoneId): Clock = this
    }
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val tokens = object : Api.AccessTokens {
        var token: String? = "access-1"
        override suspend fun current(): String? = token
        override suspend fun renewed(refused: String): String? = null
    }
    private val api = Api(server.engine, ServerAddress.parse("https://planner.example"), tokens)
    private lateinit var database: PlannerDatabase
    private lateinit var planner: PlannerRepository

    private fun open(): PlannerDatabase =
        Room.databaseBuilder(context, PlannerDatabase::class.java, folder.root.resolve("planner.db").path).build()

    @Before
    fun aPhone() {
        database = open()
        planner = PlannerRepository({ api }, database, clock)
    }

    @After
    fun close() {
        database.close()
    }

    private val venue = StandIn.entry(
        "p1", "Book the venue", kind = "PLAN_ITEM", startDate = "2026-11-01", dueDate = "2026-12-15", status = "IN_PROGRESS",
        priority = "HIGH", priorityReason = "DUE_WITHIN_60_DAYS", categoryId = "c1", vendorId = "v1", assigneeIds = listOf(StandIn.ANNA, StandIn.MAREK), taskCount = 2,
    )
    private val deposit = StandIn.entry(
        "t1", "Pay the deposit", dueDate = "2026-11-10", priority = "CRITICAL", priorityReason = "OVERDUE", parentId = "p1",
        categoryId = "c1", vendorId = "v1", amount = 250000, paymentKind = "DEPOSIT", assigneeIds = listOf(StandIn.MAREK), version = 3,
    )
    private val tasting = StandIn.entry("t2", "Go to the tasting", dueDate = "2026-11-25", parentId = "p1", priorityReason = "DUE_WITHIN_14_DAYS")
    private val rings = StandIn.entry("t3", "Choose the rings")

    private fun aWedding() {
        server.planner(
            entries = listOf(deposit, tasting, venue, rings),
            home = StandIn.home(
                overdue = listOf(deposit), next = listOf(tasting, venue), nextPayments = listOf(deposit),
                budget = StandIn.budget(total = 6000000, planned = 5000000, committed = 250000, paid = 0),
            ),
            categories = listOf(StandIn.category("c1", "Venue"), StandIn.category("c2", "Attire")),
            vendors = listOf(StandIn.vendor("v1", "Palace on the Hill")),
        )
    }

    @Test
    fun `holds nothing before the first load`() = runBlocking {
        assertNull(planner.planner.first())
    }

    @Test
    fun `loads the wedding, Home and every entry, and hands them out as they were loaded`() = runBlocking {
        aWedding()

        assertEquals(Load.LOADED, planner.load())

        val held = planner.planner.first()!!
        assertEquals(now, held.loadedAt)
        assertEquals(LocalDate.of(2027, 6, 19), held.weddingDate)
        assertEquals("PLN", held.currency)
        assertEquals(213L, held.daysLeft)
        assertEquals(LocalDate.of(2026, 11, 18), held.today)
        assertEquals(listOf("Anna", "Marek"), held.members.map { it.displayName })
        // The server's order is kept.
        assertEquals(listOf("Pay the deposit", "Go to the tasting", "Book the venue", "Choose the rings"), held.entries.map { it.title })
        assertEquals(listOf("Book the venue"), held.planItems.map { it.title })
        assertEquals(listOf("Pay the deposit"), held.overdue.map { it.title })
        assertEquals(listOf("Go to the tasting", "Book the venue"), held.next.map { it.title })
        assertEquals(listOf("Pay the deposit"), held.nextPayments.map { it.title })
        assertEquals(Budget(total = 6000000, planned = 5000000, committed = 250000, paid = 0), held.budget)
        assertEquals(mapOf("c1" to "Venue", "c2" to "Attire"), held.categories)
        assertEquals(mapOf("v1" to "Palace on the Hill"), held.vendors)

        val payment = held.entry("t1")!!
        assertEquals(Kind.TASK, payment.kind)
        assertEquals(Priority.CRITICAL, payment.priority)
        assertEquals("OVERDUE", payment.priorityReason)
        assertEquals(250000L, payment.amount)
        assertEquals("DEPOSIT", payment.paymentKind)
        assertEquals(LocalDate.of(2026, 11, 10), payment.dueDate)
        assertEquals("p1", payment.parentId)
        assertEquals(3, payment.version)
        assertEquals("Marek", held.peopleOf(payment))
        assertEquals("Anna & Marek", held.peopleOf(held.entry("p1")!!))
        assertNull(held.peopleOf(held.entry("t3")!!))
        assertEquals(listOf("Pay the deposit", "Go to the tasting"), held.tasksOf(held.entry("p1")!!).map { it.title })
        // Every call carried the access token.
        assertTrue(server.calls.all { it.authorization == "Bearer access-1" })
        assertEquals(5, server.calls.size)
    }

    @Test
    fun `without a connection the last load stays, with when it was made`() = runBlocking {
        aWedding()
        planner.load()
        val loadedAt = now
        now = now.plusSeconds(3 * 24 * 3600)
        server.plannerUnreachable()

        assertEquals(Load.UNREACHABLE, planner.load())

        val held = planner.planner.first()!!
        assertEquals(loadedAt, held.loadedAt)
        assertEquals(4, held.entries.size)
    }

    @Test
    fun `what was loaded is still there after the app was closed`() = runBlocking {
        aWedding()
        planner.load()
        database.close()

        database = open()
        planner = PlannerRepository({ api }, database, clock)
        server.plannerUnreachable()

        val held = planner.planner.first()!!
        assertEquals(listOf("Pay the deposit", "Go to the tasting", "Book the venue", "Choose the rings"), held.entries.map { it.title })
        assertEquals("Anna & Marek", held.members.joinToString(" & ") { it.displayName })
        assertEquals(Instant.parse("2026-11-18T13:05:00Z"), held.loadedAt)
    }

    @Test
    fun `a load of which one answer is missing replaces nothing`() = runBlocking {
        aWedding()
        planner.load()
        server.planner(entries = listOf(rings))
        server.unreachable("GET /api/categories")

        assertEquals(Load.UNREACHABLE, planner.load())

        assertEquals(4, planner.planner.first()!!.entries.size)
    }

    @Test
    fun `a newer load replaces the last one entirely`() = runBlocking {
        aWedding()
        planner.load()
        now = now.plusSeconds(60)
        server.planner(entries = listOf(rings), wedding = StandIn.wedding(date = null), home = StandIn.home(daysLeft = null, weddingDate = null))

        assertEquals(Load.LOADED, planner.load())

        val held = planner.planner.first()!!
        assertEquals(listOf("Choose the rings"), held.entries.map { it.title })
        assertTrue(held.overdue.isEmpty())
        assertTrue(held.categories.isEmpty())
        assertTrue(held.vendors.isEmpty())
        assertNull(held.weddingDate)
        assertNull(held.daysLeft)
        assertEquals(now, held.loadedAt)
    }

    @Test
    fun `says why when there is no wedding to load, or it is not this person's`() = runBlocking {
        server.planner()
        server.problem("GET /api/wedding", 404, "wedding-not-set-up")
        server.problem("GET /api/home", 404, "wedding-not-set-up")
        assertEquals(Load.NOT_SET_UP, planner.load())

        server.problem("GET /api/wedding", 403, "wedding-full")
        server.problem("GET /api/home", 403, "wedding-full")
        assertEquals(Load.NOT_A_MEMBER, planner.load())

        server.planner()
        server.problem("GET /api/entries", 500, "something-from-a-newer-server")
        assertEquals(Load.FAILED, planner.load())

        tokens.token = null
        assertEquals(Load.SIGNED_OUT, planner.load())
        assertNull(planner.planner.first())
    }

    @Test
    fun `an answer that cannot be read is a failed load, and what was loaded before stays`() = runBlocking {
        aWedding()
        planner.load()
        server.json("GET /api/entries", """[{"id":"e9"}]""")
        assertEquals(Load.FAILED, planner.load())

        server.json("GET /api/entries", "<html>a login page of some network</html>", HttpStatusCode.OK)
        assertEquals(Load.FAILED, planner.load())

        assertEquals(4, planner.planner.first()!!.entries.size)
    }

    @Test
    fun `reads what a newer server sends - fields and values this app does not know`() = runBlocking {
        server.planner(
            entries = listOf(
                StandIn.entry("t1", "Sign the contract", priorityReason = "WAITS_FOR_A_SIGNATURE", more = """"signedBy":["x"],"colour":"red""""),
                StandIn.entry("t2", "Pay the band", amount = 100000, paymentKind = "TIP"),
                StandIn.entry("t3", "Wait for the answer", status = "ON_HOLD", priority = "URGENT"),
                StandIn.entry("x1", "A note to self", kind = "NOTE"),
            ),
            home = StandIn.home().dropLast(1) + ""","guests":{"invited":120}}""",
        )

        assertEquals(Load.LOADED, planner.load())

        val held = planner.planner.first()!!
        // An entry of a kind this app does not know is in neither list.
        assertEquals(listOf("Sign the contract", "Pay the band", "Wait for the answer"), held.entries.map { it.title })
        assertEquals("WAITS_FOR_A_SIGNATURE", held.entry("t1")!!.priorityReason)
        assertEquals("TIP", held.entry("t2")!!.paymentKind)
        assertEquals(Status.OTHER, held.entry("t3")!!.status)
        assertTrue(held.entry("t3")!!.status.open)
        assertEquals(Priority.NORMAL, held.entry("t3")!!.priority)
    }

    @Test
    fun `forgetting removes the planner from the phone`() = runBlocking {
        // Some phones' databases blank out a deleted row by themselves; this one is told not to, as others do not.
        database.openHelper.writableDatabase.query("PRAGMA secure_delete = OFF").use { it.moveToFirst() }
        aWedding()
        planner.load()

        planner.forget()

        assertNull(planner.planner.first())
        // Not only out of the tables: gone from the files, while the app is still running.
        val files = folder.root.listFiles()!!.filter { it.isFile }
        assertTrue(files.any { it.name == "planner.db" })
        val onDisk = files.joinToString("") { it.readBytes().toString(Charsets.ISO_8859_1) }
        assertFalse(onDisk.contains("Palace on the Hill"))
        assertFalse(onDisk.contains("Pay the deposit"))
        assertFalse(onDisk.contains("Marek"))
    }

    @Test
    fun `a load that was under way when the person signed out is not kept`() = runBlocking {
        aWedding()
        val asked = CompletableDeferred<Unit>()
        val mayAnswer = CompletableDeferred<Unit>()
        server.answer("GET /api/vendors") {
            asked.complete(Unit)
            mayAnswer.await()
            respondJson("[" + StandIn.vendor("v1", "Palace on the Hill") + "]")
        }

        val load = async { planner.load() }
        asked.await()
        planner.forget()
        mayAnswer.complete(Unit)

        assertEquals(Load.SIGNED_OUT, load.await())
        assertNull(planner.planner.first())
    }

    @Test
    fun `the days to the wedding are the server's on the day of loading and counted on after it`() = runBlocking {
        aWedding()
        planner.load()
        val held = planner.planner.first()!!

        assertEquals(213L, held.daysLeftOn(LocalDate.of(2026, 11, 18)))
        assertEquals(210L, held.daysLeftOn(LocalDate.of(2026, 11, 21)))
        assertEquals(0L, held.daysLeftOn(LocalDate.of(2027, 6, 19)))
        assertEquals(-2L, held.daysLeftOn(LocalDate.of(2027, 6, 21)))
    }

    @Test
    fun `what is held prints no name, title or vendor`() = runBlocking {
        aWedding()
        planner.load()
        val held = planner.planner.first()!!

        val printed = "$held ${held.members}"
        for (personal in listOf("Anna", "Marek", "Palace", "deposit", "venue")) {
            assertFalse(printed.contains(personal, ignoreCase = true))
        }
    }
}
