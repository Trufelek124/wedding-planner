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
 * A suggestion accepted or rejected on the phone: kept, shown at once, sent when the server can be reached, taken back
 * for as long as it was not sent, never sent over the other decision, and gone with a sign-out. A stand-in server that
 * keeps the suggestions, a real database. All of the mail and the people here is made up.
 */
@RunWith(RobolectricTestRunner::class)
class SuggestionDecisionsTest {

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

    @Before
    fun aPhoneThatLoaded() = runBlocking {
        theirs = LivingPlanner(
            server,
            entries = emptyList(),
            suggestions = listOf(
                StandIn.suggestion("s1", "Pay the deposit", kind = "PAYMENT", dueDate = "2026-12-01", amount = 250000, paymentKind = "DEPOSIT"),
                StandIn.suggestion("s2", "Send the guest list"),
                StandIn.suggestion("s3", "Rehearsal dinner", kind = "PLAN_ITEM", startDate = "2026-12-01", dueDate = "2026-12-15"),
            ),
        )
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

    private suspend fun suggestion(id: String = "s2"): Suggestion? = held().suggestions.firstOrNull { it.id == id }

    private fun posted(): List<String> = server.calls.map { it.route }.filter { it.startsWith("POST /api/suggestions") }

    @Test
    fun `a decision shows at once, waits to be sent, is counted as unsent, and is not counted as waiting for a decision`() = runBlocking {
        assertEquals(3, held().suggestionsToDecide)

        planner.decideSuggestion("s2", Decision.ACCEPT)

        assertEquals(Decision.ACCEPT, suggestion()!!.decided)
        assertEquals(Waiting.TO_BE_SENT, suggestion()!!.waiting)
        assertFalse(suggestion()!!.needsLook)
        assertNull(suggestion("s1")!!.decided)
        assertEquals(3, held().suggestions.size)
        assertEquals(2, held().suggestionsToDecide)
        assertEquals(1, held().waiting)
        assertEquals(1, held().unsent)
        assertEquals(1, planner.stillToSend())
        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun `an acceptance is sent as proposed, before the planner is loaded again, and the task it made is among the entries`() = runBlocking {
        planner.decideSuggestion("s2", Decision.ACCEPT)

        assertEquals(Load.LOADED, planner.sync())

        val calls = server.calls.map { it.route }
        assertEquals("POST /api/suggestions/s2/accept", calls.first())
        assertTrue(calls.indexOf("GET /api/suggestions") > 0)
        // As proposed: nothing of the proposal travels with it.
        assertEquals("", server.calls.first().body)
        assertEquals("Bearer access-1", server.calls.first().authorization)
        assertEquals("ACCEPTED", theirs.suggestionStateOf("s2"))
        assertNull(suggestion())
        assertEquals(listOf("s1", "s3"), held().suggestions.map { it.id })
        assertEquals("Send the guest list", held().entry("made-s2")!!.title)
        assertEquals(0, planner.stillToSend())
        assertEquals(0, held().unsent)
        assertEquals(0, database.planner().suggestionDecisions().size)
        server.calls.clear()
        planner.sync()
        assertTrue(posted().isEmpty())
    }

    @Test
    fun `an accepted payment and an accepted step are made as they were proposed`() = runBlocking {
        planner.decideSuggestion("s1", Decision.ACCEPT)
        planner.decideSuggestion("s3", Decision.ACCEPT)

        planner.sync()

        val payment = held().entry("made-s1")!!
        assertEquals(250000L, payment.amount)
        assertEquals("DEPOSIT", payment.paymentKind)
        assertEquals(Kind.PLAN_ITEM, held().entry("made-s3")!!.kind)
        assertEquals(listOf("s2"), held().suggestions.map { it.id })
    }

    @Test
    fun `a rejection is sent to its own route and makes nothing`() = runBlocking {
        planner.decideSuggestion("s2", Decision.REJECT)

        planner.sync()

        assertEquals(listOf("POST /api/suggestions/s2/reject"), posted())
        assertEquals("", server.calls.first().body)
        assertEquals("REJECTED", theirs.suggestionStateOf("s2"))
        assertNull(held().entry("made-s2"))
        assertNull(suggestion())
    }

    @Test
    fun `without a connection the decision stays, shows as waiting, and the next sync sends it`() = runBlocking {
        planner.decideSuggestion("s2", Decision.ACCEPT)
        server.down = true

        assertEquals(Load.UNREACHABLE, planner.sync())

        assertEquals(Decision.ACCEPT, suggestion()!!.decided)
        assertEquals(Waiting.TO_BE_SENT, suggestion()!!.waiting)
        assertEquals(1, planner.stillToSend())
        assertEquals("WAITING", theirs.suggestionStateOf("s2"))

        server.down = false
        server.calls.clear()
        assertEquals(Load.LOADED, planner.sync())

        assertEquals(listOf("POST /api/suggestions/s2/accept"), posted())
        assertEquals("ACCEPTED", theirs.suggestionStateOf("s2"))
        assertNull(suggestion())
    }

    @Test
    fun `a server in trouble does not lose the decision - it is tried again`() = runBlocking {
        planner.decideSuggestion("s2", Decision.REJECT)
        server.problem("POST /api/suggestions/s2/reject", 500, "something-went-wrong")

        planner.sync()

        assertEquals(Waiting.TO_BE_SENT, suggestion()!!.waiting)
        assertEquals(1, planner.stillToSend())
        server.unreachable("POST /api/suggestions/s2/reject")
        planner.sync()
        assertEquals("REJECTED", theirs.suggestionStateOf("s2"))
        assertNull(suggestion())
    }

    @Test
    fun `a decision that waits survives the app being closed`() = runBlocking {
        planner.decideSuggestion("s2", Decision.ACCEPT)
        server.down = true
        planner.sync()
        database.close()

        database = open()
        planner = repository()

        assertEquals(Decision.ACCEPT, suggestion()!!.decided)
        assertEquals(Waiting.TO_BE_SENT, suggestion()!!.waiting)
        assertEquals(1, planner.stillToSend())
        server.down = false
        planner.sync()
        assertEquals("ACCEPTED", theirs.suggestionStateOf("s2"))
    }

    @Test
    fun `the other decision replaces a waiting one, and only the last is sent`() = runBlocking {
        planner.decideSuggestion("s2", Decision.ACCEPT)
        planner.decideSuggestion("s2", Decision.REJECT)
        planner.decideSuggestion("s2", Decision.ACCEPT)
        planner.decideSuggestion("s2", Decision.REJECT)

        assertEquals(Decision.REJECT, suggestion()!!.decided)
        assertEquals(1, held().unsent)
        assertEquals(1, planner.stillToSend())
        planner.sync()

        assertEquals(listOf("POST /api/suggestions/s2/reject"), posted())
        assertEquals("REJECTED", theirs.suggestionStateOf("s2"))
    }

    @Test
    fun `taking a decision back leaves nothing to send, and sends nothing`() = runBlocking {
        planner.decideSuggestion("s2", Decision.ACCEPT)

        assertEquals(TakenBack.TAKEN_BACK, planner.takeBackSuggestionDecision("s2"))

        assertNull(suggestion()!!.decided)
        assertNull(suggestion()!!.waiting)
        assertEquals(3, held().suggestionsToDecide)
        assertEquals(0, held().unsent)
        assertEquals(0, planner.stillToSend())
        planner.sync()
        assertTrue(posted().isEmpty())
        assertEquals("WAITING", theirs.suggestionStateOf("s2"))
        // And it can be decided again, the other way.
        planner.decideSuggestion("s2", Decision.REJECT)
        planner.sync()
        assertEquals("REJECTED", theirs.suggestionStateOf("s2"))
    }

    @Test
    fun `taking back what was never decided changes nothing`() = runBlocking {
        assertEquals(TakenBack.NOTHING_TO_TAKE_BACK, planner.takeBackSuggestionDecision("s2"))
        assertEquals(TakenBack.NOTHING_TO_TAKE_BACK, planner.takeBackSuggestionDecision("nobody"))
        assertEquals(0, held().unsent)
    }

    @Test
    fun `a suggestion the phone does not have gets no decision`() = runBlocking {
        planner.decideSuggestion("nobody", Decision.ACCEPT)

        assertEquals(0, planner.stillToSend())
        assertEquals(0, held().unsent)
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
    fun `taking a decision back while it is on its way changes nothing, and the decision lands`() = runBlocking {
        planner.decideSuggestion("s2", Decision.ACCEPT)
        val onItsWay = OnItsWay(theirs)
        var answer: TakenBack? = null

        withTimeout(20_000) {
            val sending = async { planner.sync() }
            onItsWay.arrived.await()
            answer = planner.takeBackSuggestionDecision("s2")
            // It still shows as decided, and still counts as something the phone alone has.
            assertEquals(Decision.ACCEPT, suggestion()!!.decided)
            assertEquals(1, held().unsent)
            onItsWay.mayAnswer.complete(Unit)
            sending.await()
        }

        assertEquals(TakenBack.ON_ITS_WAY, answer)
        assertEquals("ACCEPTED", theirs.suggestionStateOf("s2"))
        assertEquals(listOf("POST /api/suggestions/s2/accept"), posted())
        assertNull(suggestion())
        assertEquals("Send the guest list", held().entry("made-s2")!!.title)
        assertEquals(0, planner.stillToSend())
        assertEquals(0, database.planner().suggestionDecisions().size)
    }

    @Test
    fun `the other decision made while one is on its way changes nothing either`() = runBlocking {
        planner.decideSuggestion("s2", Decision.ACCEPT)
        val onItsWay = OnItsWay(theirs)

        withTimeout(20_000) {
            val sending = async { planner.sync() }
            onItsWay.arrived.await()
            planner.decideSuggestion("s2", Decision.REJECT)
            // Nothing changed: the card still says what is on its way, and no rejection waits behind it.
            assertEquals(Decision.ACCEPT, suggestion()!!.decided)
            assertEquals("ACCEPT", database.planner().suggestionDecisions().single().decision)
            onItsWay.mayAnswer.complete(Unit)
            sending.await()
        }

        // A decision stands: the rejection was never sent, nor kept.
        assertEquals("ACCEPTED", theirs.suggestionStateOf("s2"))
        assertEquals(listOf("POST /api/suggestions/s2/accept"), posted())
        assertEquals(0, planner.stillToSend())
        assertEquals(0, held().unsent)
    }

    @Test
    fun `a decision taken back while the first try had no answer is taken back, as it is not on its way any more`() = runBlocking {
        planner.decideSuggestion("s2", Decision.ACCEPT)
        server.down = true
        planner.sync()

        assertEquals(TakenBack.TAKEN_BACK, planner.takeBackSuggestionDecision("s2"))

        assertEquals(0, planner.stillToSend())
    }

    @Test
    fun `the other decision made first is said, not sent again, and let go of`() = runBlocking {
        planner.decideSuggestion("s2", Decision.ACCEPT)
        theirs.decideElsewhere("s2", "REJECTED")

        planner.sync()

        // The load took the suggestion away; what was decided for it is named, and only to be let go of.
        assertNull(suggestion())
        val trouble = held().suggestionTroubles.single()
        assertEquals("s2", trouble.suggestionId)
        assertEquals("Send the guest list", trouble.title)
        assertEquals(SuggestionTroubleKind.DECIDED_OTHERWISE, trouble.kind)
        assertEquals(Decision.ACCEPT, trouble.decision)
        assertEquals(1, held().unsent)
        assertEquals(0, planner.stillToSend())
        assertNull(held().entry("made-s2"))
        server.calls.clear()
        planner.sync()
        assertTrue(posted().isEmpty())

        planner.letGoOfSuggestionDecision("s2")

        assertTrue(held().suggestionTroubles.isEmpty())
        assertEquals(0, held().unsent)
        assertEquals("REJECTED", theirs.suggestionStateOf("s2"))
    }

    @Test
    fun `a suggestion removed on the server is said to be gone, and the decision is let go of`() = runBlocking {
        planner.decideSuggestion("s2", Decision.REJECT)
        theirs.removeSuggestion("s2")

        planner.sync()

        val trouble = held().suggestionTroubles.single()
        assertEquals(SuggestionTroubleKind.GONE, trouble.kind)
        assertEquals(Decision.REJECT, trouble.decision)
        assertEquals(0, planner.stillToSend())
        server.calls.clear()
        planner.sync()
        assertTrue(posted().isEmpty())

        planner.letGoOfSuggestionDecision("s2")
        assertTrue(held().suggestionTroubles.isEmpty())
    }

    @Test
    fun `an acceptance the server refuses is kept as not taken, not sent again by itself, and the card waits for a look`() = runBlocking {
        planner.decideSuggestion("s2", Decision.ACCEPT)
        server.problem("POST /api/suggestions/s2/accept", 400, "bad-request")

        planner.sync()

        val trouble = held().suggestionTroubles.single()
        assertEquals(SuggestionTroubleKind.REFUSED, trouble.kind)
        assertEquals(Decision.ACCEPT, trouble.decision)
        // Still on the server, still waiting for someone: the card stays, with no decision and no buttons.
        assertTrue(suggestion()!!.needsLook)
        assertNull(suggestion()!!.decided)
        assertEquals(3, held().suggestionsToDecide)
        assertEquals(0, held().waiting)
        assertEquals(1, held().unsent)
        assertEquals(0, planner.stillToSend())
        // Neither deciding again nor taking back changes it while the notice stands.
        planner.decideSuggestion("s2", Decision.REJECT)
        assertEquals(TakenBack.NOTHING_TO_TAKE_BACK, planner.takeBackSuggestionDecision("s2"))
        assertEquals(1, held().suggestionTroubles.size)
        server.calls.clear()
        planner.sync()
        assertTrue(posted().isEmpty())

        planner.letGoOfSuggestionDecision("s2")

        assertTrue(held().suggestionTroubles.isEmpty())
        assertFalse(suggestion()!!.needsLook)
        assertEquals(0, held().unsent)
        // It can be decided again: the proposal may have been changed on the web.
        server.unreachable("POST /api/suggestions/s2/accept")
        planner.decideSuggestion("s2", Decision.ACCEPT)
        planner.sync()
        assertEquals("ACCEPTED", theirs.suggestionStateOf("s2"))
    }

    @Test
    fun `letting go does not take away a decision that waits`() = runBlocking {
        planner.decideSuggestion("s2", Decision.ACCEPT)

        planner.letGoOfSuggestionDecision("s2")

        assertEquals(Decision.ACCEPT, suggestion()!!.decided)
        assertEquals(1, planner.stillToSend())
    }

    @Test
    fun `a decision that is sent again after an answer was lost does no harm`() = runBlocking {
        planner.decideSuggestion("s2", Decision.ACCEPT)
        // The server took it once already, and only the answer was lost.
        theirs.decideElsewhere("s2", "ACCEPTED")

        planner.sync()

        assertEquals(0, held().suggestionTroubles.size)
        assertEquals(0, planner.stillToSend())
        assertEquals(0, held().unsent)
    }

    @Test
    fun `signing out removes the waiting decision, and nothing of the suggestion stays on the phone`() = runBlocking {
        planner.decideSuggestion("s2", Decision.ACCEPT)

        planner.forget()

        assertNull(planner.planner.first())
        assertEquals(0, planner.stillToSend())
        assertEquals(0, database.planner().suggestionDecisions().size)
        val onDisk = folder.root.listFiles()!!.filter { it.isFile }.joinToString("") { it.readBytes().toString(Charsets.ISO_8859_1) }
        assertFalse(onDisk.contains("Send the guest list"))
        server.calls.clear()
        planner.sync()
        assertTrue(posted().isEmpty())
    }

    @Test
    fun `signing out removes a decision that could not be sent, too`() = runBlocking {
        planner.decideSuggestion("s2", Decision.ACCEPT)
        theirs.decideElsewhere("s2", "REJECTED")
        planner.sync()
        assertEquals(1, held().suggestionTroubles.size)

        planner.forget()

        assertEquals(0, database.planner().suggestionDecisions().size)
        val onDisk = folder.root.listFiles()!!.filter { it.isFile }.joinToString("") { it.readBytes().toString(Charsets.ISO_8859_1) }
        assertFalse(onDisk.contains("Send the guest list"))
    }

    @Test
    fun `a sign-out while the decision is on its way leaves nothing behind`() = runBlocking {
        planner.decideSuggestion("s2", Decision.ACCEPT)
        val onItsWay = OnItsWay(theirs)

        withTimeout(20_000) {
            val sending = async { planner.sync() }
            onItsWay.arrived.await()
            planner.forget()
            // The session is over: the server is not asked for anything more.
            server.down = true
            onItsWay.mayAnswer.complete(Unit)
            sending.await()
        }

        assertNull(planner.planner.first())
        assertEquals(0, planner.stillToSend())
        assertEquals(0, database.planner().suggestionDecisions().size)
        val onDisk = folder.root.listFiles()!!.filter { it.isFile }.joinToString("") { it.readBytes().toString(Charsets.ISO_8859_1) }
        assertFalse(onDisk.contains("Send the guest list"))
    }

    @Test
    fun `nothing of the suggestion is printed by the waiting row, the suggestion or the trouble`() = runBlocking {
        planner.decideSuggestion("s2", Decision.ACCEPT)
        val row = database.planner().suggestionDecisions().single()
        assertFalse(row.toString().contains("guest list"))
        assertFalse(suggestion().toString().contains("guest list"))
        theirs.decideElsewhere("s2", "REJECTED")
        planner.sync()

        assertFalse(held().suggestionTroubles.toString().contains("guest list"))
        assertFalse(held().suggestionTroubles.single().toString().contains("guest list"))
        assertFalse(database.planner().suggestionDecisions().single().toString().contains("guest list"))
    }
}
