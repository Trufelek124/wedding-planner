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
import org.junit.Assert.assertSame
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
        planner = PlannerRepository({ api }, database, clock, folder.root.resolve("shares"))
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
            vendors = listOf(
                StandIn.vendor(
                    "v1", "Palace on the Hill", categoryId = "c1", contactPerson = "Jolanta", phone = "+48 555 010 020", email = "hall@palace.example",
                    website = "https://palace.example", address = "1 Hill Road", notes = "Ask about the piano", agreedPrice = 1200000,
                    planItems = 1, tasks = 1, payments = 1, committed = 250000, paid = 0, inspirations = 2,
                ),
            ),
            budget = StandIn.wholeBudget(
                total = 6000000, lines = lines, rest = StandIn.Rest(committed = 30000, paid = 10000, payments = 2),
            ),
            guests = listOf(guestAnna, guestJan),
            households = listOf(StandIn.household("h1", "The Nowak family", guests = 1)),
            guestCounts = StandIn.guestCounts(listOf(StandIn.Counted("CONFIRMED", StandIn.ANNA, plusOne = true, children = 2), StandIn.Counted("MAYBE"))),
        )
    }

    private val lines = listOf(
        StandIn.Line("c1", "Venue", planned = 5000000, committed = 250000, paid = 0, payments = 1),
        StandIn.Line("c2", "Attire", planned = null, committed = 0, paid = 0, payments = 0),
    )
    private val guestAnna = StandIn.guest(
        "g1", "Jan Nowak", status = "CONFIRMED", sideMemberId = StandIn.ANNA, householdId = "h1", plusOne = true, children = 2,
        dietaryNeeds = "vegetarian", needsAccommodation = true, phone = "+48 555 030 040", email = "jan@guests.example", notes = "Allergic to nuts",
    )
    private val guestJan = StandIn.guest("g2", "Ewa Kowal", status = "MAYBE")

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
        // The wedding, Home, the entries, the categories, the vendors, the boards, the budget, the guests,
        // the households and the head count: one request each.
        assertEquals(10, server.calls.size)
    }

    @Test
    fun `holds the budget as it was loaded, category by category`() = runBlocking {
        aWedding()
        planner.load()

        val held = planner.planner.first()!!
        assertEquals(
            listOf(BudgetLine("c1", "Venue", planned = 5000000, committed = 250000, paid = 0, payments = 1), BudgetLine("c2", "Attire", null, 0, 0, 0)),
            held.budgetLines,
        )
        assertEquals(BudgetRest(committed = 30000, paid = 10000, payments = 2), held.uncategorised)
    }

    @Test
    fun `holds the vendors with their details, their money and what points to them`() = runBlocking {
        aWedding()
        planner.load()

        val held = planner.planner.first()!!
        val palace = held.vendorList.single()
        assertEquals("Palace on the Hill", palace.name)
        assertEquals(VendorStatus.BOOKED, palace.status)
        assertEquals("c1", palace.categoryId)
        assertEquals("Jolanta", palace.contactPerson)
        assertEquals("+48 555 010 020", palace.phone)
        assertEquals("hall@palace.example", palace.email)
        assertEquals("https://palace.example", palace.website)
        assertEquals("1 Hill Road", palace.address)
        assertEquals("Ask about the piano", palace.notes)
        assertEquals(1200000L, palace.agreedPrice)
        assertEquals(250000L, palace.committed)
        assertEquals(0L, palace.paid)
        assertEquals(2, palace.inspirations)
        assertSame(palace, held.vendor("v1"))
        assertNull(held.vendor("nobody"))
        assertEquals(listOf("Pay the deposit", "Book the venue"), held.entriesOf(palace).map { it.title })
        // What the phone has long used of vendors, the names, is still there.
        assertEquals(mapOf("v1" to "Palace on the Hill"), held.vendors)
    }

    @Test
    fun `holds the guests, their households and the head count`() = runBlocking {
        aWedding()
        planner.load()

        val held = planner.planner.first()!!
        assertEquals(listOf("Jan Nowak", "Ewa Kowal"), held.guests.map { it.name })
        val jan = held.guests.first()
        assertEquals(GuestStatus.CONFIRMED, jan.status)
        assertEquals(StandIn.ANNA, jan.sideMemberId)
        assertEquals("h1", jan.householdId)
        assertTrue(jan.plusOne)
        assertEquals(2, jan.children)
        assertEquals(4, jan.heads)
        assertEquals("vegetarian", jan.dietaryNeeds)
        assertTrue(jan.needsAccommodation)
        assertEquals("+48 555 030 040", jan.phone)
        assertEquals("jan@guests.example", jan.email)
        assertEquals("Allergic to nuts", jan.notes)
        assertEquals(listOf("The Nowak family"), held.households.map { it.name })
        assertEquals("Anna", held.memberName(StandIn.ANNA))
        assertNull(held.memberName("someone-else"))

        val counts = held.guestCounts!!
        assertEquals(HeadCount(guests = 2, plusOnes = 1, children = 2, heads = 5), counts.total)
        assertEquals(listOf("NOT_INVITED", "INVITED", "CONFIRMED", "DECLINED", "MAYBE"), counts.byStatus.map { it.status })
        assertEquals(HeadCount(1, 1, 2, 4), counts.byStatus.first { it.status == "CONFIRMED" }.count)
        // The two members in the wedding's order, then both sides, also where nobody is.
        assertEquals(listOf<String?>(StandIn.ANNA, StandIn.MAREK, null), counts.bySide.map { it.sideMemberId })
        assertEquals(HeadCount(0, 0, 0, 0), counts.bySide[1].count)
        assertEquals(HeadCount(1, 0, 0, 1), counts.bySide[2].byStatus.last().count)
    }

    @Test
    fun `a vendor's or a guest's state that a newer server knows is OTHER and breaks nothing`() = runBlocking {
        server.planner(
            vendors = listOf(StandIn.vendor("v1", "Palace on the Hill", status = "ARCHIVED")),
            guests = listOf(StandIn.guest("g1", "Jan Nowak", status = "ON_THE_WAY")),
        )

        assertEquals(Load.LOADED, planner.load())

        val held = planner.planner.first()!!
        assertEquals(VendorStatus.OTHER, held.vendorList.single().status)
        assertEquals(GuestStatus.OTHER, held.guests.single().status)
    }

    @Test
    fun `reads a head count to which a newer server adds a field`() = runBlocking {
        server.planner(guestCounts = StandIn.guestCounts().dropLast(1) + ""","byDiet":[]}""")

        assertEquals(Load.LOADED, planner.load())

        assertEquals(0, planner.planner.first()!!.guestCounts!!.total.heads)
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
        planner = PlannerRepository({ api }, database, clock, folder.root.resolve("shares"))
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
        // Nor what is about vendors and guests: their names, contact details and needs.
        for (personal in listOf("Jan Nowak", "Ewa Kowal", "Nowak family", "vegetarian", "+48 555 010 020", "+48 555 030 040", "palace.example", "guests.example", "Allergic", "Jolanta", "Hill Road", "piano")) {
            assertFalse(personal, onDisk.contains(personal))
        }
        assertTrue(planner.planner.first() == null)
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

        val printed = "$held ${held.members} ${held.vendorList} ${held.guests} ${held.households} ${held.vendorList.single()} ${held.guests.first()}"
        for (personal in listOf("Anna", "Marek", "Palace", "deposit", "venue", "Nowak", "Kowal", "555", "example", "vegetarian", "nuts", "Jolanta", "piano")) {
            assertFalse(printed.contains(personal, ignoreCase = true))
        }
    }
}
