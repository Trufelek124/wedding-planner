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
 * A guest's status chosen on the phone: kept, shown at once, sent when the server can be reached, never
 * sent over a later choice, and gone with a sign-out. A stand-in server that keeps the guests, a real database.
 */
@RunWith(RobolectricTestRunner::class)
class GuestStatusChangesTest {

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
            guests = listOf(
                StandIn.guest("g1", "Aunt Krystyna", status = "INVITED", sideMemberId = StandIn.ANNA, plusOne = true),
                StandIn.guest("g2", "Uncle Tomek", status = "CONFIRMED", sideMemberId = StandIn.ANNA),
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

    private suspend fun guest(id: String = "g1"): Guest = held().guest(id)!!

    private fun sent(): List<String> = server.calls.map { it.route }.filter { it.startsWith("PUT") }

    private fun bodies(): List<String> = server.routes("PUT /api/guests/g1/status").map { it.body }

    private suspend fun confirmedHeads(): Int? = held().guestCounts!!.byStatus.first { it.status == "CONFIRMED" }.count.heads

    @Test
    fun `a chosen status shows at once, waits to be sent, and the head count stays as it was loaded`() = runBlocking {
        assertEquals(1, confirmedHeads())

        planner.setGuestStatus("g1", GuestStatus.CONFIRMED)

        assertEquals(GuestStatus.CONFIRMED, guest().status)
        assertEquals(Waiting.TO_BE_SENT, guest().waiting)
        assertNull(guest("g2").waiting)
        assertEquals(1, held().waiting)
        assertEquals(1, held().guestsChanged)
        assertEquals(1, held().unsent)
        assertEquals(1, planner.stillToSend())
        // The phone never counts heads itself: the server's count is as it was loaded.
        assertEquals(1, confirmedHeads())
        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun `the next time the server is reached the status is sent, before the planner is loaded again`() = runBlocking {
        planner.setGuestStatus("g1", GuestStatus.CONFIRMED)

        assertEquals(Load.LOADED, planner.sync())

        val calls = server.calls.map { it.route }
        assertEquals("PUT /api/guests/g1/status", calls.first())
        assertTrue(calls.indexOf("GET /api/guests") > 0)
        assertEquals("""{"status":"CONFIRMED"}""", server.calls.first().body)
        assertEquals("Bearer access-1", server.calls.first().authorization)
        assertEquals("CONFIRMED", theirs.guestStatusOf("g1"))
        assertNull(guest().waiting)
        assertEquals(GuestStatus.CONFIRMED, guest().status)
        assertEquals(0, planner.stillToSend())
        assertEquals(0, held().unsent)
        // Loaded again, the head count is the server's new one.
        assertEquals(3, confirmedHeads())
        server.calls.clear()
        planner.sync()
        assertTrue(sent().isEmpty())
    }

    @Test
    fun `without a connection the status stays and shows as waiting, and the next sync sends it`() = runBlocking {
        planner.setGuestStatus("g1", GuestStatus.DECLINED)
        server.down = true

        assertEquals(Load.UNREACHABLE, planner.sync())

        assertEquals(Waiting.TO_BE_SENT, guest().waiting)
        assertEquals(GuestStatus.DECLINED, guest().status)
        assertEquals(1, planner.stillToSend())
        assertEquals("INVITED", theirs.guestStatusOf("g1"))

        server.down = false
        server.calls.clear()
        assertEquals(Load.LOADED, planner.sync())

        assertEquals(listOf("""{"status":"DECLINED"}"""), bodies())
        assertEquals("DECLINED", theirs.guestStatusOf("g1"))
        assertNull(guest().waiting)
    }

    @Test
    fun `a server in trouble does not lose the status - it is tried again`() = runBlocking {
        planner.setGuestStatus("g1", GuestStatus.CONFIRMED)
        server.problem("PUT /api/guests/g1/status", 500, "something-went-wrong")

        planner.sync()

        assertEquals(Waiting.TO_BE_SENT, guest().waiting)
        assertEquals(1, planner.stillToSend())
        server.unreachable("PUT /api/guests/g1/status")
        planner.sync()
        assertEquals("CONFIRMED", theirs.guestStatusOf("g1"))
        assertNull(guest().waiting)
    }

    @Test
    fun `a status that waits survives the app being closed`() = runBlocking {
        planner.setGuestStatus("g1", GuestStatus.MAYBE)
        server.down = true
        planner.sync()
        database.close()

        database = open()
        planner = repository()

        assertEquals(GuestStatus.MAYBE, guest().status)
        assertEquals(Waiting.TO_BE_SENT, guest().waiting)
        server.down = false
        planner.sync()
        assertEquals("MAYBE", theirs.guestStatusOf("g1"))
    }

    @Test
    fun `a second choice replaces the first, and only the last is sent`() = runBlocking {
        planner.setGuestStatus("g1", GuestStatus.CONFIRMED)
        planner.setGuestStatus("g1", GuestStatus.MAYBE)
        planner.setGuestStatus("g1", GuestStatus.DECLINED)

        assertEquals(1, held().guestsChanged)
        assertEquals(1, planner.stillToSend())
        planner.sync()

        assertEquals(listOf("""{"status":"DECLINED"}"""), bodies())
        assertEquals("DECLINED", theirs.guestStatusOf("g1"))
    }

    @Test
    fun `choosing what the server has takes the waiting status away, and nothing is sent`() = runBlocking {
        planner.setGuestStatus("g1", GuestStatus.CONFIRMED)

        planner.setGuestStatus("g1", GuestStatus.INVITED)

        assertNull(guest().waiting)
        assertEquals(GuestStatus.INVITED, guest().status)
        assertEquals(0, held().unsent)
        assertEquals(0, planner.stillToSend())
        planner.sync()
        assertTrue(sent().isEmpty())
        // And choosing what the guest has anyway waits for nothing.
        planner.setGuestStatus("g2", GuestStatus.CONFIRMED)
        assertEquals(0, planner.stillToSend())
    }

    @Test
    fun `a status this app does not know is never chosen, and a guest the phone does not have gets none`() = runBlocking {
        planner.setGuestStatus("g1", GuestStatus.OTHER)
        planner.setGuestStatus("nobody", GuestStatus.CONFIRMED)

        assertEquals(0, planner.stillToSend())
        assertEquals(0, held().unsent)
    }

    /** Holds the server's answer to the first status that is sent, until the test lets it go. */
    private class OnItsWay(theirs: LivingPlanner) {
        val arrived = CompletableDeferred<Unit>()
        val mayAnswer = CompletableDeferred<Unit>()

        init {
            theirs.holdUp = { request ->
                if (request.method.value == "PUT" && request.url.encodedPath.endsWith("/status") && !arrived.isCompleted) {
                    arrived.complete(Unit)
                    mayAnswer.await()
                }
            }
        }
    }

    @Test
    fun `a status chosen while the first is on its way is not lost, and is sent after it`() = runBlocking {
        planner.setGuestStatus("g1", GuestStatus.CONFIRMED)
        val onItsWay = OnItsWay(theirs)

        // Should the request never arrive, the test fails here and does not wait for ever.
        withTimeout(20_000) {
            val sending = async { planner.sync() }
            onItsWay.arrived.await()
            planner.setGuestStatus("g1", GuestStatus.DECLINED)
            onItsWay.mayAnswer.complete(Unit)
            sending.await()
        }

        // The server took the first; the second still waits, and shows.
        assertEquals("CONFIRMED", theirs.guestStatusOf("g1"))
        assertEquals(GuestStatus.DECLINED, guest().status)
        assertEquals(Waiting.TO_BE_SENT, guest().waiting)
        assertEquals(1, planner.stillToSend())

        planner.sync()

        assertEquals(listOf("""{"status":"CONFIRMED"}""", """{"status":"DECLINED"}"""), bodies())
        assertEquals("DECLINED", theirs.guestStatusOf("g1"))
        assertNull(guest().waiting)
        assertEquals(0, planner.stillToSend())
    }

    @Test
    fun `choosing the old status again while the first is on its way is a change, not a return to what was`() = runBlocking {
        planner.setGuestStatus("g1", GuestStatus.CONFIRMED)
        val onItsWay = OnItsWay(theirs)

        withTimeout(20_000) {
            val sending = async { planner.sync() }
            onItsWay.arrived.await()
            // The phone's copy still says Invited: this is the status the server is about to leave.
            planner.setGuestStatus("g1", GuestStatus.INVITED)
            onItsWay.mayAnswer.complete(Unit)
            sending.await()
        }

        assertEquals("CONFIRMED", theirs.guestStatusOf("g1"))
        assertEquals(GuestStatus.INVITED, guest().status)
        assertEquals(Waiting.TO_BE_SENT, guest().waiting)

        planner.sync()

        assertEquals("INVITED", theirs.guestStatusOf("g1"))
        assertNull(guest().waiting)
    }

    @Test
    fun `choosing the same status again while it is on its way leaves nothing to send afterwards`() = runBlocking {
        planner.setGuestStatus("g1", GuestStatus.CONFIRMED)
        val onItsWay = OnItsWay(theirs)

        withTimeout(20_000) {
            val sending = async { planner.sync() }
            onItsWay.arrived.await()
            planner.setGuestStatus("g1", GuestStatus.MAYBE)
            planner.setGuestStatus("g1", GuestStatus.CONFIRMED)
            onItsWay.mayAnswer.complete(Unit)
            sending.await()
        }

        assertEquals(0, planner.stillToSend())
        assertNull(guest().waiting)
        assertEquals(GuestStatus.CONFIRMED, guest().status)
        server.calls.clear()
        planner.sync()
        assertTrue(sent().isEmpty())
    }

    @Test
    fun `a guest removed on the web is said to be gone, and the status is let go of`() = runBlocking {
        planner.setGuestStatus("g1", GuestStatus.CONFIRMED)
        theirs.removeGuest("g1")

        planner.sync()

        // The load took the guest away; what was chosen for them is named, and only to be let go of.
        assertNull(held().guest("g1"))
        val trouble = held().guestTroubles.single()
        assertEquals("g1", trouble.guestId)
        assertEquals("Aunt Krystyna", trouble.name)
        assertTrue(trouble.gone)
        assertEquals(1, held().unsent)
        assertEquals(0, planner.stillToSend())
        server.calls.clear()
        planner.sync()
        assertTrue(sent().isEmpty())

        planner.letGoOfGuestChange("g1")

        assertTrue(held().guestTroubles.isEmpty())
        assertEquals(0, held().unsent)
    }

    @Test
    fun `a guest that is gone on the server but still in the copy shows as it was, with the notice`() = runBlocking {
        planner.setGuestStatus("g1", GuestStatus.CONFIRMED)
        server.problem("PUT /api/guests/g1/status", 404, "not-found")
        // The load that follows does not come: the copy still has the guest.
        server.down = false
        server.answer("GET /api/guests") { throw java.io.IOException("Failed to connect") }

        planner.sync()

        assertEquals(GuestStatus.INVITED, guest().status)
        assertNull(guest().waiting)
        assertTrue(held().guestTroubles.single().gone)
        assertEquals(0, planner.stillToSend())
        // Choosing again does nothing while the notice stands.
        planner.setGuestStatus("g1", GuestStatus.MAYBE)
        assertEquals(0, planner.stillToSend())
    }

    @Test
    fun `what the server refuses is kept as not saved, not sent again by itself, and sent again once another status is chosen`() = runBlocking {
        planner.setGuestStatus("g1", GuestStatus.CONFIRMED)
        server.problem("PUT /api/guests/g1/status", 400, "bad-request")

        planner.sync()

        assertEquals(Waiting.REFUSED, guest().waiting)
        assertEquals(GuestStatus.CONFIRMED, guest().status)
        val trouble = held().guestTroubles.single()
        assertEquals("Aunt Krystyna", trouble.name)
        assertFalse(trouble.gone)
        assertEquals(1, held().unsent)
        assertEquals(0, held().waiting)
        assertEquals(0, planner.stillToSend())
        server.calls.clear()
        planner.sync()
        assertTrue(sent().isEmpty())

        server.unreachable("PUT /api/guests/g1/status")
        planner.setGuestStatus("g1", GuestStatus.MAYBE)
        assertEquals(Waiting.TO_BE_SENT, guest().waiting)
        assertTrue(held().guestTroubles.isEmpty())
        planner.sync()
        assertEquals("MAYBE", theirs.guestStatusOf("g1"))
        assertNull(guest().waiting)
    }

    @Test
    fun `letting go of a refused status brings back what the server has`() = runBlocking {
        planner.setGuestStatus("g1", GuestStatus.CONFIRMED)
        server.problem("PUT /api/guests/g1/status", 400, "bad-request")
        planner.sync()

        planner.letGoOfGuestChange("g1")

        assertEquals(GuestStatus.INVITED, guest().status)
        assertNull(guest().waiting)
        assertTrue(held().guestTroubles.isEmpty())
        assertEquals(0, held().unsent)
    }

    @Test
    fun `signing out removes the waiting status, and nothing of the guest stays on the phone`() = runBlocking {
        planner.setGuestStatus("g1", GuestStatus.CONFIRMED)

        planner.forget()

        assertNull(planner.planner.first())
        assertEquals(0, planner.stillToSend())
        val onDisk = folder.root.listFiles()!!.filter { it.isFile }.joinToString("") { it.readBytes().toString(Charsets.ISO_8859_1) }
        assertFalse(onDisk.contains("Krystyna"))
        server.calls.clear()
        planner.sync()
        assertTrue(sent().isEmpty())
    }

    @Test
    fun `a sign-out while the status is on its way leaves nothing behind`() = runBlocking {
        planner.setGuestStatus("g1", GuestStatus.CONFIRMED)
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
        val onDisk = folder.root.listFiles()!!.filter { it.isFile }.joinToString("") { it.readBytes().toString(Charsets.ISO_8859_1) }
        assertFalse(onDisk.contains("Krystyna"))
    }

    @Test
    fun `the waiting row does not print the guest's name`() = runBlocking {
        planner.setGuestStatus("g1", GuestStatus.CONFIRMED)

        val row = database.planner().guestStatuses().single()

        assertFalse(row.toString().contains("Krystyna"))
        assertFalse(guest().toString().contains("Krystyna"))
        assertFalse(held().guestTroubles.toString().contains("Krystyna"))
    }
}
