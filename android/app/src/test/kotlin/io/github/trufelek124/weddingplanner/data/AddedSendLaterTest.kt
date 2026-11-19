package io.github.trufelek124.weddingplanner.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import io.github.trufelek124.weddingplanner.StandInPhone
import io.github.trufelek124.weddingplanner.WeddingPlannerApp
import io.github.trufelek124.weddingplanner.api.StandInServer
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

/** An undo made while the app is not open: the work the phone asked for sends it, by hand against a stand-in server. All of the mail and the people here is made up. */
@RunWith(RobolectricTestRunner::class)
class AddedSendLaterTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var phone: StandInPhone
    private lateinit var theirs: LivingPlanner
    private val server get() = phone.server

    @Before
    fun aSignedInPhoneWithADecisionWaiting() = runBlocking {
        phone = StandInPhone(folder.root)
        (context as WeddingPlannerApp).container = phone.container
        server.json("POST /api/auth/dev", StandInServer.session())
        server.json("POST /api/auth/refresh", StandInServer.session(accessToken = "access-2", refreshToken = "refresh-2"))
        server.nothing("POST /api/auth/sign-out")
        theirs = LivingPlanner(
            server, entries = listOf(StandIn.entry("e1", "Send the guest list", fromSuggestionId = "s1")),
            suggestions = listOf(StandIn.added("s1", "Send the guest list", "e1")),
        )
        phone.container.session.signInForDevelopment("anna@example.com")
        phone.container.planner.sync()
        server.down = true
        phone.container.planner.decideSuggestion("s1", Decision.UNDO)
        phone.container.planner.sync()
        server.calls.clear()
    }

    @After
    fun off() {
        phone.off()
    }

    private fun work(): ListenableWorker.Result = runBlocking { TestListenableWorkerBuilder<SendWorker>(context).build().doWork() }

    private fun decided(): Decision? = runBlocking { phone.container.planner.planner.first()!!.added.firstOrNull { it.id == "s1" }?.decided }

    private fun entryShown(): Boolean = runBlocking { phone.container.planner.planner.first()!!.entry("e1") != null }

    @Test
    fun `with a connection the work sends the undo and is done`() {
        server.down = false

        assertEquals(ListenableWorker.Result.success(), work())

        assertEquals("", server.routes("POST /api/suggestions/s1/undo").single().body)
        assertEquals("REJECTED", theirs.suggestionStateOf("s1"))
        assertNull(theirs.titleOf("e1"))
        assertNull(decided())
        assertFalse(entryShown())
        assertEquals(0, runBlocking { phone.container.planner.stillToSend() })
    }

    @Test
    fun `without one it asks to be run again, and the undo goes on waiting with the entry out of the lists`() {
        assertEquals(ListenableWorker.Result.retry(), work())

        assertEquals(Decision.UNDO, decided())
        assertFalse(entryShown())
    }

    @Test
    fun `an undo the server cannot take because the entry changed does not keep the work coming back`() {
        server.down = false
        theirs.put(StandIn.entry("e1", "Send the guest list", status = "DONE", version = 1, fromSuggestionId = "s1"))

        assertEquals(ListenableWorker.Result.success(), work())

        assertEquals(0, runBlocking { phone.container.planner.stillToSend() })
        assertEquals(SuggestionTroubleKind.ENTRY_CHANGED, runBlocking { phone.container.planner.planner.first()!!.suggestionTroubles.single().kind })
        assertTrue(entryShown())
    }

    @Test
    fun `the work also finds an undo that waited while the app was closed`() {
        phone = phone.restarted()
        (context as WeddingPlannerApp).container = phone.container
        server.down = false

        assertEquals(ListenableWorker.Result.success(), work())

        assertEquals(1, server.routes("POST /api/suggestions/s1/undo").size)
        assertEquals("REJECTED", theirs.suggestionStateOf("s1"))
    }
}
