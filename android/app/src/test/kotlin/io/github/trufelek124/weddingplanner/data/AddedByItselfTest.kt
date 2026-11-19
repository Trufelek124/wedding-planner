package io.github.trufelek124.weddingplanner.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trufelek124.weddingplanner.ServerAddress
import io.github.trufelek124.weddingplanner.api.Api
import io.github.trufelek124.weddingplanner.api.StandInServer
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * What the import added by itself, on the phone: loaded with the rest, kept or undone at once, sent when the server can be
 * reached, and every answer of the server. A stand-in server that keeps the entries and the suggestions, a real database. All
 * of the mail and the people here is made up.
 */
@RunWith(RobolectricTestRunner::class)
class AddedByItselfTest {

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

    private fun open(): PlannerDatabase =
        Room.databaseBuilder(context, PlannerDatabase::class.java, folder.root.resolve("planner.db").path).build()

    private fun repository() = PlannerRepository({ api }, database, clock, folder.root.resolve("shares"))

    /** The entries: two the import made by itself (a task and a step), one a person accepted, one made by hand. */
    private val entries = listOf(
        StandIn.entry("e1", "Send the guest list", fromSuggestionId = "a1"),
        StandIn.entry("e2", "Rehearsal dinner", kind = "PLAN_ITEM", startDate = "2026-12-01", dueDate = "2026-12-15", fromSuggestionId = "a2"),
        StandIn.entry("e3", "Book the band", fromSuggestionId = "acc1"),
        StandIn.entry("e4", "Choose the rings"),
    )

    private val suggestions = listOf(
        StandIn.added("a1", "Send the guest list", "e1"),
        StandIn.added("a2", "Rehearsal dinner", "e2", kind = "PLAN_ITEM"),
        StandIn.suggestion("w1", "Pay the deposit", kind = "PAYMENT", amount = 250000, dueDate = "2026-12-01"),
        StandIn.suggestion("acc1", "Book the band", state = "ACCEPTED", madeEntryId = "e3", decidedBy = StandIn.MAREK, decidedAt = "2026-11-17T10:00:00Z"),
        // What the phone has no use for: one that was rejected, an accepted one that no entry says it came from, a state it does not know.
        StandIn.suggestion("rej1", "Send flowers", state = "REJECTED", decidedBy = StandIn.ANNA, decidedAt = "2026-11-17T10:00:00Z"),
        StandIn.suggestion("acc2", "Old news", state = "ACCEPTED", decidedBy = StandIn.ANNA, decidedAt = "2026-10-01T10:00:00Z"),
        StandIn.suggestion("odd1", "From the future", state = "SNOOZED"),
    )

    @Before
    fun aPhoneThatLoaded() = runBlocking {
        theirs = LivingPlanner(server, entries = entries, suggestions = suggestions)
        database = open()
        planner = repository()
        assertEquals(Load.LOADED, planner.sync())
        server.calls.clear()
    }

    @After
    fun close() {
        database.close()
    }

    private suspend fun held(): Planner = planner.planner.first()!!

    private suspend fun added(id: String): Suggestion? = held().added.firstOrNull { it.id == id }

    private fun posted(): List<String> = server.calls.map { it.route }.filter { it.startsWith("POST /api/suggestions") }

    private fun onDisk(): String = folder.root.listFiles()!!.filter { it.isFile }.joinToString("") { it.readBytes().toString(Charsets.ISO_8859_1) }

    // ---------------------------------------------------------------- the copy

    @Test
    fun `the phone asks for all the suggestions at once and keeps those that wait, those added by themselves and the accepted that an entry came from`() = runBlocking {
        planner.sync()
        assertEquals("state=ALL", server.calls.first { it.route == "GET /api/suggestions" }.query)

        val now = held()

        assertEquals(listOf("w1"), now.suggestions.map { it.id })
        assertEquals(listOf("a1", "a2"), now.added.map { it.id })
        assertEquals(listOf("acc1"), now.accepted.map { it.id })
        // Neither the rejected, nor an accepted one nobody points to, nor one in a state the phone does not know.
        assertNull(now.suggestion("rej1"))
        assertNull(now.suggestion("acc2"))
        assertNull(now.suggestion("odd1"))
        assertEquals(listOf("a1", "a2", "w1", "acc1"), database.planner().suggestions().map { it.id })
        assertFalse(onDisk().contains("Send flowers"))
        assertFalse(onDisk().contains("Old news"))
        assertFalse(onDisk().contains("From the future"))
    }

    @Test
    fun `an added suggestion says which entry it made, who decided an accepted one, and the entries say where they came from`() = runBlocking {
        val a1 = added("a1")!!
        assertEquals(SuggestionState.ADDED, a1.state)
        assertEquals("e1", a1.madeEntryId)
        assertNull(a1.decidedBy)
        assertNull(a1.decided)
        val accepted = held().accepted.single()
        assertEquals(SuggestionState.ACCEPTED, accepted.state)
        assertEquals(StandIn.MAREK, accepted.decidedBy)
        assertEquals(Instant.parse("2026-11-17T10:00:00Z"), accepted.decidedAt)
        assertEquals("a1", held().entry("e1")!!.fromSuggestionId)
        assertEquals("acc1", held().entry("e3")!!.fromSuggestionId)
        assertNull(held().entry("e4")!!.fromSuggestionId)
        assertEquals(1, held().suggestionsToDecide)
        assertEquals(2, held().addedToLookAt)
    }

    @Test
    fun `a server older than this work, that knows neither added suggestions nor where an entry came from, still loads`() = runBlocking {
        val older = suggestions.take(3).map { it.replace("\"state\":\"ADDED\",", "\"state\":\"WAITING\",") }
            .map { it.replace(Regex("\"(decidedBy|decidedAt|madeEntryId|madeVendorId)\":null,"), "").replace("\"state\":\"WAITING\",", "") }
        server.json("GET /api/suggestions", older.joinToString(",", "[", "]"))
        server.json("GET /api/entries", entries.map { it.replace("\"fromSuggestionId\":", "\"unknown\":").replace(Regex("\"unknown\":(\"[^\"]*\"|null),"), "") }.joinToString(",", "[", "]"))

        assertEquals(Load.LOADED, planner.sync())

        // Everything it lists waits, as it always did; nothing says where an entry came from.
        assertEquals(listOf("a1", "a2", "w1"), held().suggestions.map { it.id })
        assertTrue(held().added.isEmpty())
        assertEquals(0, held().addedToLookAt)
        assertTrue(held().entries.all { it.fromSuggestionId == null })
    }

    @Test
    fun `a server without the suggestions at all loads the rest, and nobody says anything was added`() = runBlocking {
        server.problem("GET /api/suggestions", 404, "not-found")

        assertEquals(Load.LOADED, planner.sync())

        assertTrue(held().suggestions.isEmpty())
        assertTrue(held().added.isEmpty())
        assertTrue(held().accepted.isEmpty())
        assertEquals("Send the guest list", held().entry("e1")!!.title)
        assertTrue(database.planner().suggestions().isEmpty())
    }

    // ---------------------------------------------------------------- at once on the phone

    @Test
    fun `an undo takes the entry from every list at once, and nothing is sent yet`() = runBlocking {
        assertEquals("Send the guest list", held().tasks.first { it.id == "e1" }.title)
        assertTrue(held().next.any { it.id == "e1" })

        planner.decideSuggestion("a1", Decision.UNDO)

        val now = held()
        assertNull(now.entry("e1"))
        assertTrue(now.tasks.none { it.id == "e1" })
        assertTrue(now.next.none { it.id == "e1" })
        assertTrue(now.entries.none { it.id == "e1" })
        // The step that was added too is not touched.
        assertEquals("Rehearsal dinner", now.entry("e2")!!.title)
        assertEquals(Decision.UNDO, added("a1")!!.decided)
        assertEquals(Waiting.TO_BE_SENT, added("a1")!!.waiting)
        assertEquals(1, now.addedToLookAt)
        assertEquals(1, now.unsent)
        assertEquals(1, now.waiting)
        assertEquals(1, planner.stillToSend())
        assertTrue(server.calls.isEmpty())
        // The server's copy of the entry is untouched until the undo is sent.
        assertEquals("Send the guest list", theirs.titleOf("e1"))
    }

    @Test
    fun `taking an undo back brings the entry back, and sends nothing`() = runBlocking {
        planner.decideSuggestion("a1", Decision.UNDO)

        assertEquals(TakenBack.TAKEN_BACK, planner.takeBackSuggestionDecision("a1"))

        assertEquals("Send the guest list", held().entry("e1")!!.title)
        assertNull(added("a1")!!.decided)
        assertEquals(2, held().addedToLookAt)
        assertEquals(0, held().unsent)
        assertEquals(0, planner.stillToSend())
        planner.sync()
        assertTrue(posted().isEmpty())
        assertEquals("ADDED", theirs.suggestionStateOf("a1"))
    }

    @Test
    fun `a keep shows at once, takes the suggestion out of the count and leaves the entry where it is`() = runBlocking {
        planner.decideSuggestion("a1", Decision.KEEP)

        assertEquals(Decision.KEEP, added("a1")!!.decided)
        assertEquals(Waiting.TO_BE_SENT, added("a1")!!.waiting)
        assertEquals(1, held().addedToLookAt)
        // Two numbers, never one: what waits is the same as before.
        assertEquals(1, held().suggestionsToDecide)
        assertEquals("Send the guest list", held().entry("e1")!!.title)
        assertEquals(1, planner.stillToSend())
        assertTrue(server.calls.isEmpty())

        planner.takeBackSuggestionDecision("a1")

        assertEquals(2, held().addedToLookAt)
        assertEquals(0, planner.stillToSend())
    }

    @Test
    fun `accept and reject are for what waits, keep and undo for what was added, and the others are not taken`() = runBlocking {
        planner.decideSuggestion("a1", Decision.ACCEPT)
        planner.decideSuggestion("a2", Decision.REJECT)
        planner.decideSuggestion("w1", Decision.KEEP)
        planner.decideSuggestion("w1", Decision.UNDO)
        planner.decideSuggestion("acc1", Decision.UNDO)
        planner.decideSuggestion("acc1", Decision.KEEP)

        assertEquals(0, planner.stillToSend())
        assertEquals(0, held().unsent)
        assertNull(added("a1")!!.decided)
        assertNull(held().suggestions.single().decided)
    }

    @Test
    fun `one decision waits for each suggestion, and the other replaces it while it is not sent`() = runBlocking {
        planner.decideSuggestion("a1", Decision.KEEP)
        planner.decideSuggestion("a1", Decision.UNDO)
        planner.decideSuggestion("a1", Decision.KEEP)
        planner.decideSuggestion("a1", Decision.UNDO)
        planner.decideSuggestion("a2", Decision.KEEP)

        assertEquals(Decision.UNDO, added("a1")!!.decided)
        assertEquals(Decision.KEEP, added("a2")!!.decided)
        assertEquals(2, database.planner().suggestionDecisions().size)
        assertNull(held().entry("e1"))
        assertEquals(2, planner.stillToSend())

        planner.sync()

        assertEquals(listOf("POST /api/suggestions/a1/undo", "POST /api/suggestions/a2/keep"), posted())
    }

    // ---------------------------------------------------------------- sending

    @Test
    fun `they are sent after the tasks and the guests' statuses, before the planner is loaded again`() = runBlocking {
        planner.setStatus("e4", Status.DONE)
        planner.decideSuggestion("a2", Decision.KEEP)
        planner.decideSuggestion("a1", Decision.UNDO)

        assertEquals(Load.LOADED, planner.sync())

        val calls = server.calls.map { it.route }
        val tick = calls.indexOf("PUT /api/entries/e4/status")
        val keep = calls.indexOf("POST /api/suggestions/a2/keep")
        val undo = calls.indexOf("POST /api/suggestions/a1/undo")
        assertTrue(tick >= 0 && keep > tick && undo > tick)
        assertTrue(calls.indexOf("GET /api/suggestions") > keep)
        // As proposed: no body.
        assertEquals("", server.routes("POST /api/suggestions/a1/undo").single().body)
        assertEquals("Bearer access-1", server.routes("POST /api/suggestions/a1/undo").single().authorization)
    }

    @Test
    fun `a keep that is taken leaves the suggestion as accepted, by whoever the server says`() = runBlocking {
        planner.decideSuggestion("a1", Decision.KEEP)

        assertEquals(Load.LOADED, planner.sync())

        assertEquals("ACCEPTED", theirs.suggestionStateOf("a1"))
        assertNull(added("a1"))
        assertEquals(StandIn.ANNA, held().suggestion("a1")!!.decidedBy)
        assertEquals(SuggestionState.ACCEPTED, held().suggestion("a1")!!.state)
        assertEquals("Send the guest list", held().entry("e1")!!.title)
        assertEquals(0, planner.stillToSend())
        assertEquals(0, held().unsent)
        assertEquals(0, database.planner().suggestionDecisions().size)
        // Nothing is sent again by itself.
        server.calls.clear()
        planner.sync()
        assertTrue(posted().isEmpty())
    }

    @Test
    fun `an undo that is taken removes the entry on the server too, and the suggestion is not kept any more`() = runBlocking {
        planner.decideSuggestion("a1", Decision.UNDO)

        assertEquals(Load.LOADED, planner.sync())

        assertEquals(listOf("POST /api/suggestions/a1/undo"), posted())
        assertEquals("REJECTED", theirs.suggestionStateOf("a1"))
        assertNull(theirs.titleOf("e1"))
        assertNull(held().entry("e1"))
        assertNull(held().suggestion("a1"))
        assertEquals(1, held().addedToLookAt)
        assertEquals(0, held().unsent)
        assertEquals(0, planner.stillToSend())
        assertFalse(onDisk().contains("guest list passage"))
        server.calls.clear()
        planner.sync()
        assertTrue(posted().isEmpty())
    }

    @Test
    fun `without a connection the decision stays, shows as waiting, and the next sync sends it once`() = runBlocking {
        planner.decideSuggestion("a1", Decision.UNDO)
        server.down = true

        assertEquals(Load.UNREACHABLE, planner.sync())

        assertEquals(Waiting.TO_BE_SENT, added("a1")!!.waiting)
        assertNull(held().entry("e1"))
        assertEquals(1, planner.stillToSend())
        assertEquals("ADDED", theirs.suggestionStateOf("a1"))

        server.down = false
        server.calls.clear()
        assertEquals(Load.LOADED, planner.sync())

        assertEquals(listOf("POST /api/suggestions/a1/undo"), posted())
        assertEquals("REJECTED", theirs.suggestionStateOf("a1"))
        server.calls.clear()
        planner.sync()
        assertTrue(posted().isEmpty())
    }

    @Test
    fun `a server in trouble does not lose the decision, and it survives the app being closed`() = runBlocking {
        planner.decideSuggestion("a1", Decision.KEEP)
        server.problem("POST /api/suggestions/a1/keep", 500, "internal-error")

        planner.sync()

        assertEquals(Waiting.TO_BE_SENT, added("a1")!!.waiting)
        assertEquals(1, planner.stillToSend())
        database.close()
        database = open()
        planner = repository()
        assertEquals(Decision.KEEP, added("a1")!!.decided)
        assertEquals(1, planner.stillToSend())
        server.unreachable("POST /api/suggestions/a1/keep")
        planner.sync()
        assertEquals("ACCEPTED", theirs.suggestionStateOf("a1"))
    }

    /** Holds the server's answer to the first decision that is sent, until the test lets it go. */
    private class OnItsWay(theirs: LivingPlanner) {
        val arrived = CompletableDeferred<Unit>()
        val mayAnswer = CompletableDeferred<Unit>()

        init {
            theirs.holdUp = { request ->
                if (request.method.value == "POST" && request.url.encodedPath.startsWith("/api/suggestions/") && !arrived.isCompleted) {
                    arrived.complete(Unit)
                    mayAnswer.await()
                }
            }
        }
    }

    @Test
    fun `a decision that is on its way can be neither taken back nor replaced, and the entry stays out of the lists meanwhile`() = runBlocking {
        planner.decideSuggestion("a1", Decision.UNDO)
        val onItsWay = OnItsWay(theirs)
        var answer: TakenBack? = null

        withTimeout(20_000) {
            val sending = async { planner.sync() }
            onItsWay.arrived.await()
            answer = planner.takeBackSuggestionDecision("a1")
            planner.decideSuggestion("a1", Decision.KEEP)
            assertEquals(Decision.UNDO, added("a1")!!.decided)
            assertNull(held().entry("e1"))
            assertEquals("UNDO", database.planner().suggestionDecisions().single().decision)
            assertEquals(1, held().unsent)
            onItsWay.mayAnswer.complete(Unit)
            sending.await()
        }

        assertEquals(TakenBack.ON_ITS_WAY, answer)
        assertEquals(listOf("POST /api/suggestions/a1/undo"), posted())
        assertEquals("REJECTED", theirs.suggestionStateOf("a1"))
        assertEquals(0, planner.stillToSend())
    }

    // ---------------------------------------------------------------- what the server answers

    @Test
    fun `an entry that was changed meanwhile stays, the suggestion counts as kept, and the notice says so once`() = runBlocking {
        planner.decideSuggestion("a1", Decision.UNDO)
        // Someone ticked it off on the web.
        theirs.put(StandIn.entry("e1", "Send the guest list", status = "DONE", version = 1, fromSuggestionId = "a1"))

        assertEquals(Load.LOADED, planner.sync())

        assertEquals("Send the guest list", held().entry("e1")!!.title)
        assertEquals(Status.DONE, held().entry("e1")!!.status)
        assertEquals("ACCEPTED", theirs.suggestionStateOf("a1"))
        // The suggestion counts as kept: it has left the part, and the entry is as it is.
        assertNull(added("a1"))
        assertEquals(SuggestionState.ACCEPTED, held().suggestion("a1")!!.state)
        val trouble = held().suggestionTroubles.single()
        assertEquals(SuggestionTroubleKind.ENTRY_CHANGED, trouble.kind)
        assertEquals(Decision.UNDO, trouble.decision)
        assertEquals("Send the guest list", trouble.title)
        assertEquals(0, planner.stillToSend())
        assertEquals(1, held().unsent)
        server.calls.clear()
        planner.sync()
        assertTrue(posted().isEmpty())
        assertEquals(1, held().suggestionTroubles.size)

        planner.letGoOfSuggestionDecision("a1")

        assertTrue(held().suggestionTroubles.isEmpty())
        assertEquals(0, held().unsent)
        assertEquals("Send the guest list", held().entry("e1")!!.title)
    }

    @Test
    fun `an entry the server no longer has is the same answer, and counts as kept`() = runBlocking {
        planner.decideSuggestion("a2", Decision.UNDO)
        theirs.entries.remove("e2")

        planner.sync()

        assertEquals(SuggestionTroubleKind.ENTRY_CHANGED, held().suggestionTroubles.single().kind)
        assertEquals("ACCEPTED", theirs.suggestionStateOf("a2"))
        assertNull(added("a2"))
    }

    @Test
    fun `kept by the other owner meanwhile is said with the name, undone by the other owner too, and kept after thirty days by itself`() = runBlocking {
        planner.decideSuggestion("a1", Decision.UNDO)
        planner.decideSuggestion("a2", Decision.KEEP)
        theirs.decideElsewhere("a1", "ACCEPTED", by = StandIn.MAREK)
        theirs.decideElsewhere("a2", "REJECTED", by = StandIn.MAREK)

        planner.sync()

        val troubles = held().suggestionTroubles.associateBy { it.suggestionId }
        assertEquals(SuggestionTroubleKind.DECIDED_OTHERWISE, troubles.getValue("a1").kind)
        assertEquals(DecidedAs.KEPT, troubles.getValue("a1").decidedAs)
        assertEquals("Marek", troubles.getValue("a1").decidedBy)
        assertEquals(DecidedAs.UNDONE, troubles.getValue("a2").decidedAs)
        assertEquals("Marek", troubles.getValue("a2").decidedBy)
        // What the other owner decided stands: nothing of it is sent again, and the entry the phone hid is shown as the server has it.
        assertEquals(0, planner.stillToSend())
        assertEquals("Send the guest list", held().entry("e1")!!.title)

        planner.letGoOfSuggestionDecision("a1")
        planner.letGoOfSuggestionDecision("a2")
        planner.decideSuggestion("a1", Decision.UNDO)
        server.calls.clear()
        planner.sync()
        assertTrue(posted().isEmpty())
    }

    @Test
    fun `where nobody decided because thirty days passed it is said so`() = runBlocking {
        planner.decideSuggestion("a1", Decision.UNDO)
        theirs.decideElsewhere("a1", "ACCEPTED", by = null)

        planner.sync()

        val trouble = held().suggestionTroubles.single()
        assertEquals(DecidedAs.KEPT_AFTER_THIRTY_DAYS, trouble.decidedAs)
        assertNull(trouble.decidedBy)
    }

    @Test
    fun `a suggestion that is gone is said to be gone, and the entry the phone hid is back as the server has it`() = runBlocking {
        planner.decideSuggestion("a1", Decision.KEEP)
        theirs.removeSuggestion("a1")

        planner.sync()

        assertEquals(SuggestionTroubleKind.GONE, held().suggestionTroubles.single().kind)
        assertNull(held().suggestion("a1"))
        assertEquals(0, planner.stillToSend())
        server.calls.clear()
        planner.sync()
        assertTrue(posted().isEmpty())
        planner.letGoOfSuggestionDecision("a1")
        assertTrue(held().suggestionTroubles.isEmpty())
    }

    @Test
    fun `an undo the server does not take brings the entry back, is not sent again, and the card waits for a look`() = runBlocking {
        planner.decideSuggestion("a1", Decision.UNDO)
        server.problem("POST /api/suggestions/a1/undo", 400, "bad-request")

        planner.sync()

        assertEquals(SuggestionTroubleKind.REFUSED, held().suggestionTroubles.single().kind)
        assertEquals("Send the guest list", held().entry("e1")!!.title)
        assertTrue(added("a1")!!.needsLook)
        assertNull(added("a1")!!.decided)
        assertEquals(0, planner.stillToSend())
        server.calls.clear()
        planner.sync()
        assertTrue(posted().isEmpty())

        planner.letGoOfSuggestionDecision("a1")
        assertFalse(added("a1")!!.needsLook)
    }

    // ---------------------------------------------------------------- an entry the phone itself changed

    @Test
    fun `an undo of an entry the phone itself changed is refused on the phone with the notice, and the server is never asked`() = runBlocking {
        planner.setStatus("e1", Status.DONE)

        planner.decideSuggestion("a1", Decision.UNDO)

        val trouble = held().suggestionTroubles.single()
        assertEquals(SuggestionTroubleKind.ENTRY_CHANGED, trouble.kind)
        assertEquals(Decision.UNDO, trouble.decision)
        // The entry stays, ticked off, and the suggestion is still there to keep.
        assertEquals(Status.DONE, held().entry("e1")!!.status)
        assertTrue(added("a1")!!.needsLook)
        assertEquals(1, planner.stillToSend())
        server.calls.clear()
        planner.sync()
        // The tick is sent; the undo never is.
        assertTrue(posted().isEmpty())
        assertEquals("DONE", theirs.statusOf("e1"))
        planner.letGoOfSuggestionDecision("a1")
        assertTrue(held().suggestionTroubles.isEmpty())
        assertFalse(added("a1")!!.needsLook)
        assertEquals("ADDED", theirs.suggestionStateOf("a1"))
    }

    @Test
    fun `an entry that is no longer untouched in the copy, or has tasks, cannot be undone on the phone either`() = runBlocking {
        theirs.put(StandIn.entry("e2", "Rehearsal dinner", kind = "PLAN_ITEM", taskCount = 1, fromSuggestionId = "a2"))
        planner.sync()

        planner.decideSuggestion("a2", Decision.UNDO)

        assertEquals(SuggestionTroubleKind.ENTRY_CHANGED, held().suggestionTroubles.single().kind)
        assertEquals("Rehearsal dinner", held().entry("e2")!!.title)
        assertEquals(0, planner.stillToSend())
    }

    @Test
    fun `a keep is fine whatever was done to the entry`() = runBlocking {
        planner.setStatus("e1", Status.DONE)

        planner.decideSuggestion("a1", Decision.KEEP)

        assertTrue(held().suggestionTroubles.isEmpty())
        planner.sync()
        assertEquals("ACCEPTED", theirs.suggestionStateOf("a1"))
        assertEquals("DONE", theirs.statusOf("e1"))
    }

    // ---------------------------------------------------------------- signing out

    @Test
    fun `signing out removes them with the other waiting changes, and nothing of the mail stays on the phone`() = runBlocking {
        planner.decideSuggestion("a1", Decision.UNDO)
        planner.decideSuggestion("a2", Decision.KEEP)
        assertEquals(2, held().unsent)

        planner.forget()

        assertNull(planner.planner.first())
        assertEquals(0, planner.stillToSend())
        assertEquals(0, database.planner().suggestionDecisions().size)
        assertTrue(database.planner().suggestions().isEmpty())
        assertFalse(onDisk().contains("Send the guest list"))
        assertFalse(onDisk().contains("Rehearsal dinner"))
        server.calls.clear()
        planner.sync()
        assertTrue(posted().isEmpty())
    }

    @Test
    fun `nothing of an added suggestion is printed by what holds it`() = runBlocking {
        planner.decideSuggestion("a1", Decision.UNDO)
        theirs.put(StandIn.entry("e1", "Send the guest list", status = "DONE", version = 1, fromSuggestionId = "a1"))
        planner.sync()
        val printed = "${held()} ${held().added} ${held().accepted} ${held().suggestionTroubles} ${held().suggestionTroubles.single()} " +
            "${database.planner().suggestions()} ${database.planner().suggestionDecisions()} ${database.planner().stored()!!.suggestions} ${held().entry("e1")?.fromSuggestionId}"
        assertFalse(printed.contains("guest list", ignoreCase = true))
        assertFalse(printed.contains("jan@palace"))
        assertFalse(printed.contains("deposit"))
    }
}
