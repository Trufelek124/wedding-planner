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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A guest's status chosen while the app is not open: the work the phone asked for sends it, by hand against a stand-in server. */
@RunWith(RobolectricTestRunner::class)
class GuestStatusSendLaterTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var phone: StandInPhone
    private lateinit var theirs: LivingPlanner
    private val server get() = phone.server

    @Before
    fun aSignedInPhoneWithAStatusWaiting() = runBlocking {
        phone = StandInPhone(folder.root)
        (context as WeddingPlannerApp).container = phone.container
        server.json("POST /api/auth/dev", StandInServer.session())
        server.json("POST /api/auth/refresh", StandInServer.session(accessToken = "access-2", refreshToken = "refresh-2"))
        server.nothing("POST /api/auth/sign-out")
        theirs = LivingPlanner(server, entries = emptyList(), guests = listOf(StandIn.guest("g1", "Aunt Krystyna", status = "INVITED")))
        phone.container.session.signInForDevelopment("anna@example.com")
        phone.container.planner.sync()
        server.down = true
        phone.container.planner.setGuestStatus("g1", GuestStatus.CONFIRMED)
        phone.container.planner.sync()
        server.calls.clear()
    }

    @After
    fun off() {
        phone.off()
    }

    private fun work(): ListenableWorker.Result = runBlocking { TestListenableWorkerBuilder<SendWorker>(context).build().doWork() }

    private fun waiting(): Waiting? = runBlocking { phone.container.planner.planner.first()!!.guest("g1")!!.waiting }

    @Test
    fun `with a connection the work sends the status and is done`() {
        server.down = false

        assertEquals(ListenableWorker.Result.success(), work())

        assertEquals("""{"status":"CONFIRMED"}""", server.routes("PUT /api/guests/g1/status").single().body)
        assertEquals("CONFIRMED", theirs.guestStatusOf("g1"))
        assertNull(waiting())
    }

    @Test
    fun `without one it asks to be run again, and the status goes on waiting`() {
        assertEquals(ListenableWorker.Result.retry(), work())

        assertEquals(Waiting.TO_BE_SENT, waiting())
    }

    @Test
    fun `a status the server cannot take does not keep the work coming back`() {
        server.down = false
        theirs.removeGuest("g1")

        assertEquals(ListenableWorker.Result.success(), work())

        assertEquals(0, runBlocking { phone.container.planner.stillToSend() })
    }

    @Test
    fun `the work also finds a status that waited while the app was closed`() {
        phone = phone.restarted()
        (context as WeddingPlannerApp).container = phone.container
        server.down = false

        assertEquals(ListenableWorker.Result.success(), work())

        assertEquals(1, server.routes("PUT /api/guests/g1/status").size)
        assertEquals("CONFIRMED", theirs.guestStatusOf("g1"))
    }
}
