package io.github.trufelek124.weddingplanner.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trufelek124.weddingplanner.ServerAddress
import io.github.trufelek124.weddingplanner.api.Api
import io.github.trufelek124.weddingplanner.api.StandInServer
import io.github.trufelek124.weddingplanner.api.respondJson
import io.github.trufelek124.weddingplanner.api.respondText
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
        // the households, the head count, the inspirations and the suggestions: one request each.
        assertEquals(12, server.calls.size)
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

    // ---------------------------------------------------------------- the inspirations

    private val lace = StandIn.inspiration(
        "i1", url = "https://www.instagram.com/p/Cabc123/", boardId = "b1", note = "Soft, with a train", pictures = 1, previews = 1, source = "INSTAGRAM",
        title = "Lace sleeves", previewTitle = "A page title", previewStatus = "FETCHED", tags = listOf("boho", "lace"), planItemId = "p1", vendorId = "v1",
        addedById = StandIn.MAREK, version = 4,
    )
    private val bare = StandIn.inspiration("i2", addedById = null)

    @Test
    fun `holds the inspirations as they were loaded, in the server's order, with their pictures`() = runBlocking {
        server.planner(inspirations = listOf(lace, bare))

        assertEquals(Load.LOADED, planner.load())

        val held = planner.planner.first()!!
        assertEquals(listOf("i1", "i2"), held.inspirations.map { it.id })
        val idea = held.inspiration("i1")!!
        assertEquals("https://www.instagram.com/p/Cabc123/", idea.url)
        assertEquals(InspirationSource.INSTAGRAM, idea.source)
        assertEquals("Lace sleeves", idea.title)
        assertEquals("A page title", idea.previewTitle)
        assertFalse(idea.previewPending)
        assertEquals("Soft, with a train", idea.note)
        assertEquals(listOf("boho", "lace"), idea.tags)
        assertEquals("b1", idea.boardId)
        assertEquals("p1", idea.planItemId)
        assertEquals("v1", idea.vendorId)
        assertEquals(StandIn.MAREK, idea.addedById)
        // The pictures come in the order the server named them: the one a person sent after the one fetched from the link.
        assertEquals(listOf(true, false), idea.pictures.map { it.preview })
        assertEquals(listOf(800, 800), idea.pictures.map { it.width })
        assertEquals(600, idea.pictures.first().height)
        val other = held.inspiration("i2")!!
        assertNull(other.url)
        assertEquals(InspirationSource.PHOTO, other.source)
        assertNull(other.addedById)
        assertTrue(other.pictures.isEmpty())
        assertTrue(other.tags.isEmpty())
        assertNull(held.inspiration("nobody"))
    }

    @Test
    fun `a preview that is still being fetched is pending`() = runBlocking {
        server.planner(inspirations = listOf(StandIn.inspiration("i1", url = "https://www.instagram.com/p/Cabc123/"), StandIn.inspiration("i2", url = "https://www.instagram.com/p/Cdef456/", previewStatus = "FETCHED")))

        planner.load()

        val held = planner.planner.first()!!
        assertTrue(held.inspiration("i1")!!.previewPending)
        assertFalse(held.inspiration("i2")!!.previewPending)
    }

    @Test
    fun `a source a newer server knows is OTHER, and a field it adds breaks nothing`() = runBlocking {
        server.planner(inspirations = listOf(StandIn.inspiration("i1", url = "https://www.example.com/x", source = "BLUESKY").dropLast(1) + ""","colour":"red"}"""))

        assertEquals(Load.LOADED, planner.load())

        assertEquals(InspirationSource.OTHER, planner.planner.first()!!.inspiration("i1")!!.source)
    }

    @Test
    fun `the inspirations of a newer load replace those of the last, and what waits to be sent stays`() = runBlocking {
        server.planner(inspirations = listOf(lace, bare))
        planner.load()
        val waiting = (planner.keepShare(Share(link = "https://vm.tiktok.com/ZMabc/", boardId = null, note = ""), emptyList()) as Kept.Yes).inspirationId
        server.planner(inspirations = listOf(StandIn.inspiration("i3", url = "https://www.instagram.com/p/Cdef456/", note = "Later", tags = listOf("silk"))))

        assertEquals(Load.LOADED, planner.load())

        val held = planner.planner.first()!!
        assertEquals(listOf("i3"), held.inspirations.map { it.id })
        assertEquals(listOf("silk"), held.inspirations.single().tags)
        assertTrue(held.pictureCountsAre(0))
        assertEquals(listOf(waiting), held.shares.map { it.id })
        // The pictures of an idea that is gone are gone with it.
        val rows = database.planner().stored()!!
        assertEquals(emptyList<String>(), rows.inspirationPictures.map { it.inspirationId })
    }

    private fun Planner.pictureCountsAre(count: Int) = inspirations.all { it.pictures.size == count }

    @Test
    fun `an idea kept for later prints neither its link, its title, its note nor its tags`() = runBlocking {
        server.planner(inspirations = listOf(lace))
        planner.load()
        val held = planner.planner.first()!!

        val printed = "$held ${held.inspirations} ${held.inspirations.single().pictures} ${database.planner().stored()!!.inspirations} ${database.planner().stored()!!.inspirationPictures}"

        for (private in listOf("instagram", "Lace", "page title", "Soft", "train", "boho")) {
            assertFalse(private, printed.contains(private, ignoreCase = true))
        }
        assertFalse(WaitingShare("s1", "https://secret.example/x", 0, null).toString().contains("secret"))
    }

    @Test
    fun `forgetting leaves nothing of an inspiration in the phone's files`() = runBlocking {
        database.openHelper.writableDatabase.query("PRAGMA secure_delete = OFF").use { it.moveToFirst() }
        server.planner(inspirations = listOf(lace))
        planner.load()
        val before = folder.root.listFiles()!!.filter { it.isFile }.joinToString("") { it.readBytes().toString(Charsets.ISO_8859_1) }
        for (private in listOf("Cabc123", "Lace sleeves", "A page title", "Soft, with a train", "boho")) {
            assertTrue("the phone does not keep \"$private\" to begin with", before.contains(private))
        }

        planner.forget()

        val onDisk = folder.root.listFiles()!!.filter { it.isFile }.joinToString("") { it.readBytes().toString(Charsets.ISO_8859_1) }
        for (private in listOf("Cabc123", "instagram.com", "Lace sleeves", "A page title", "Soft, with a train", "train", "boho", "lace")) {
            assertFalse(private, onDisk.contains(private))
        }
        assertTrue(database.planner().stored() == null)
    }

    // ---------------------------------------------------------------- the suggestions

    private val deposit1 = StandIn.suggestion(
        "s1", "Pay the deposit", kind = "PAYMENT", notes = "By bank transfer", dueDate = "2026-12-01", amount = 250000, paymentKind = "DEPOSIT", categoryId = "c1",
        vendorId = "v1", senderName = "Jan from Palace on the Hill", senderAddress = "jan@palace.example", subject = "Deposit for the hall",
        receivedAt = "2026-11-18T13:05:00Z", inboxMemberId = StandIn.MAREK, passage = "Please pay the deposit by 1 December.", confidence = 62,
    )
    private val studio = StandIn.suggestion(
        "s2", "Studio Light", kind = "VENDOR", vendorName = "Studio Light", vendorEmail = "hello@studio.example", vendorPhone = "+48 600 000 001",
        vendorWebsite = "https://studio.example", senderName = "", senderAddress = "hello@studio.example", subject = "", confidence = 90,
    )
    private val steps = StandIn.suggestion("s3", "Rehearsal dinner", kind = "PLAN_ITEM", startDate = "2026-12-01", dueDate = "2026-12-15")

    @Test
    fun `holds the suggestions as they were loaded, in the server's order`() = runBlocking {
        server.planner(suggestions = listOf(deposit1, studio, steps))

        assertEquals(Load.LOADED, planner.load())

        val held = planner.planner.first()!!
        assertEquals(listOf("s1", "s2", "s3"), held.suggestions.map { it.id })
        val payment = held.suggestions.first()
        assertEquals(SuggestionKind.PAYMENT, payment.kind)
        assertEquals("Pay the deposit", payment.title)
        assertEquals("By bank transfer", payment.notes)
        assertEquals(LocalDate.of(2026, 12, 1), payment.dueDate)
        assertNull(payment.startDate)
        assertEquals(250000L, payment.amount)
        assertEquals("DEPOSIT", payment.paymentKind)
        assertEquals("c1", payment.categoryId)
        assertEquals("v1", payment.vendorId)
        assertNull(payment.vendorName)
        assertEquals("Jan from Palace on the Hill", payment.senderName)
        assertEquals("jan@palace.example", payment.senderAddress)
        assertEquals("Deposit for the hall", payment.subject)
        assertEquals(Instant.parse("2026-11-18T13:05:00Z"), payment.receivedAt)
        assertEquals(StandIn.MAREK, payment.inboxMemberId)
        assertEquals("https://mail.google.com/mail/u/0/#all/18c0ffee", payment.mailLink)
        assertEquals("Please pay the deposit by 1 December.", payment.passage)
        assertEquals(62, payment.confidence)
        val vendor = held.suggestions[1]
        assertEquals(SuggestionKind.VENDOR, vendor.kind)
        assertEquals("Studio Light", vendor.vendorName)
        assertEquals("hello@studio.example", vendor.vendorEmail)
        assertEquals("+48 600 000 001", vendor.vendorPhone)
        assertEquals("https://studio.example", vendor.vendorWebsite)
        assertNull(vendor.vendorId)
        assertNull(vendor.amount)
        assertEquals(LocalDate.of(2026, 12, 1), held.suggestions[2].startDate)
        assertEquals(SuggestionKind.PLAN_ITEM, held.suggestions[2].kind)
    }

    @Test
    fun `a suggestion says whether its passage stands in a document, and one from a server that does not say is not from one`() = runBlocking {
        val withoutTheField = StandIn.suggestion("s2", "Book the band").replace(",\"fromDocument\":false", "")
        assertFalse(withoutTheField.contains("fromDocument"))
        server.planner(
            suggestions = listOf(
                StandIn.suggestion("s1", "Pay the deposit", kind = "PAYMENT", amount = 250000, dueDate = "2026-12-01", fromDocument = true),
                withoutTheField,
                StandIn.suggestion("s3", "Send the guest list"),
            ),
        )

        assertEquals(Load.LOADED, planner.load())

        val held = planner.planner.first()!!.suggestions
        assertEquals(listOf(true, false, false), held.map { it.fromDocument })
        assertEquals(listOf(true, false, false), database.planner().stored()!!.suggestions.map { it.fromDocument })
    }

    @Test
    fun `a kind a newer server knows is OTHER, and a field it adds breaks nothing`() = runBlocking {
        server.planner(suggestions = listOf(StandIn.suggestion("s1", "Book the band", kind = "EVENT", more = """"colour":"red"""")))

        assertEquals(Load.LOADED, planner.load())

        assertEquals(SuggestionKind.OTHER, planner.planner.first()!!.suggestions.single().kind)
    }

    @Test
    fun `the suggestions of a newer load replace those of the last, and none left is none`() = runBlocking {
        server.planner(suggestions = listOf(deposit1, studio))
        planner.load()
        server.planner(suggestions = listOf(steps))

        assertEquals(Load.LOADED, planner.load())
        assertEquals(listOf("s3"), planner.planner.first()!!.suggestions.map { it.id })

        server.planner()
        assertEquals(Load.LOADED, planner.load())
        assertTrue(planner.planner.first()!!.suggestions.isEmpty())
    }

    @Test
    fun `a server that does not know the suggestions yet has none waiting, and the rest of the planner is loaded`() = runBlocking {
        aWedding()
        planner.load()
        server.problem("GET /api/suggestions", 404, "not-found")

        assertEquals(Load.LOADED, planner.load())

        val held = planner.planner.first()!!
        assertTrue(held.suggestions.isEmpty())
        assertEquals(4, held.entries.size)
        assertEquals(2, held.guests.size)
    }

    @Test
    fun `an older server whose 404 is no problem document is still an older server`() = runBlocking {
        aWedding()
        server.answer("GET /api/suggestions") { respondText("<html>Not Found</html>", 404) }

        assertEquals(Load.LOADED, planner.load())

        assertTrue(planner.planner.first()!!.suggestions.isEmpty())
        assertEquals(4, planner.planner.first()!!.entries.size)
    }

    @Test
    fun `any other failure of the suggestions fails the load, and nothing is replaced`() = runBlocking {
        aWedding()
        planner.load()
        server.planner(entries = listOf(rings), suggestions = listOf(deposit1))

        server.problem("GET /api/suggestions", 500, "internal")
        assertEquals(Load.FAILED, planner.load())
        server.problem("GET /api/suggestions", 403, "account-not-allowed")
        assertEquals(Load.FAILED, planner.load())
        server.answer("GET /api/suggestions") { respondText("<html>sorry</html>", 200) }
        assertEquals(Load.FAILED, planner.load())
        server.unreachable("GET /api/suggestions")
        assertEquals(Load.UNREACHABLE, planner.load())

        val held = planner.planner.first()!!
        assertEquals(4, held.entries.size)
        assertTrue(held.suggestions.isEmpty())
    }

    @Test
    fun `a suggestion prints neither its sender, its subject, its passage nor its title`() = runBlocking {
        server.planner(suggestions = listOf(deposit1, studio))
        planner.load()
        val held = planner.planner.first()!!
        val stored = database.planner().stored()!!

        val printed = "$held ${held.suggestions} ${held.suggestions.first()} ${stored.suggestions} ${stored.suggestions.first()} " +
            "${SuggestionWire("s9", "TASK", "Pay the deposit", "n", null, null, null, null, null, null, null, null, null, null, "Jan", "jan@palace.example", "Deposit", "2026-11-18T13:05:00Z", "m1", "https://mail.google.com/", "Please pay", 50)}"

        for (theirs in listOf("Jan", "palace.example", "Deposit", "deposit", "Please pay", "bank transfer", "Studio", "studio.example", "600 000", "mail.google")) {
            assertFalse("\"$theirs\" is printed", printed.contains(theirs, ignoreCase = true))
        }
    }

    @Test
    fun `forgetting leaves nothing of a suggestion in the phone's files`() = runBlocking {
        database.openHelper.writableDatabase.query("PRAGMA secure_delete = OFF").use { it.moveToFirst() }
        server.planner(suggestions = listOf(deposit1, studio))
        planner.load()
        val before = folder.root.listFiles()!!.filter { it.isFile }.joinToString("") { it.readBytes().toString(Charsets.ISO_8859_1) }
        val theirs = listOf(
            "Jan from Palace on the Hill", "jan@palace.example", "Deposit for the hall", "Please pay the deposit by 1 December.", "Pay the deposit",
            "By bank transfer", "Studio Light", "hello@studio.example", "mail.google.com/mail/u/0/#all/18c0ffee",
        )
        for (private in theirs) {
            assertTrue("the phone does not keep \"$private\" to begin with", before.contains(private))
        }

        planner.forget()

        val onDisk = folder.root.listFiles()!!.filter { it.isFile }.joinToString("") { it.readBytes().toString(Charsets.ISO_8859_1) }
        for (private in theirs + listOf("Palace on the Hill", "palace.example", "studio.example", "18c0ffee")) {
            assertFalse("\"$private\" is still in the phone's files", onDisk.contains(private))
        }
        assertTrue(database.planner().stored() == null)
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
