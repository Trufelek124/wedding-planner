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
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A suggestion decided while the app is not open: the work the phone asked for sends the decision, by hand against a stand-in server. */
@RunWith(RobolectricTestRunner::class)
class SuggestionSendLaterTest {

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
        theirs = LivingPlanner(server, entries = emptyList(), suggestions = listOf(StandIn.suggestion("s1", "Send the guest list")))
        phone.container.session.signInForDevelopment("anna@example.com")
        phone.container.planner.sync()
        server.down = true
        phone.container.planner.decideSuggestion("s1", Decision.ACCEPT)
        phone.container.planner.sync()
        server.calls.clear()
    }

    @After
    fun off() {
        phone.off()
    }

    private fun work(): ListenableWorker.Result = runBlocking { TestListenableWorkerBuilder<SendWorker>(context).build().doWork() }

    private fun decided(): Decision? = runBlocking { phone.container.planner.planner.first()!!.suggestions.firstOrNull { it.id == "s1" }?.decided }

    @Test
    fun `with a connection the work sends the decision and is done`() {
        server.down = false

        assertEquals(ListenableWorker.Result.success(), work())

        assertEquals("", server.routes("POST /api/suggestions/s1/accept").single().body)
        assertEquals("ACCEPTED", theirs.suggestionStateOf("s1"))
        assertEquals("Send the guest list", theirs.madeFor("s1")!!.getValue("title").jsonPrimitive.content)
        assertNull(decided())
        assertEquals(0, runBlocking { phone.container.planner.stillToSend() })
    }

    @Test
    fun `without one it asks to be run again, and the decision goes on waiting`() {
        assertEquals(ListenableWorker.Result.retry(), work())

        assertEquals(Decision.ACCEPT, decided())
    }

    @Test
    fun `a decision the server cannot take does not keep the work coming back`() {
        server.down = false
        theirs.decideElsewhere("s1", "REJECTED")

        assertEquals(ListenableWorker.Result.success(), work())

        assertEquals(0, runBlocking { phone.container.planner.stillToSend() })
        assertEquals(1, runBlocking { phone.container.planner.planner.first()!!.suggestionTroubles.size })
    }

    @Test
    fun `the work also finds a decision that waited while the app was closed`() {
        phone = phone.restarted()
        (context as WeddingPlannerApp).container = phone.container
        server.down = false

        assertEquals(ListenableWorker.Result.success(), work())

        assertEquals(1, server.routes("POST /api/suggestions/s1/accept").size)
        assertEquals("ACCEPTED", theirs.suggestionStateOf("s1"))
    }
}
