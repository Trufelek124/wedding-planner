package io.github.trufelek124.weddingplanner.data

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.trufelek124.weddingplanner.StandInPhone
import io.github.trufelek124.weddingplanner.WeddingPlannerApp
import io.github.trufelek124.weddingplanner.api.StandInServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Sending what waits when the app is not open: the work the phone is asked to do later, run here
 * by hand against a stand-in server.
 */
@RunWith(RobolectricTestRunner::class)
class SendLaterTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var phone: StandInPhone
    private val server get() = phone.server

    private fun rings(status: String = "NOT_STARTED", version: Int = 0) = StandIn.entry("t2", "Choose the rings", status = status, version = version)

    @Before
    fun aSignedInPhoneWithATickWaiting() = runBlocking {
        phone = StandInPhone(folder.root)
        (context as WeddingPlannerApp).container = phone.container
        server.json("POST /api/auth/dev", StandInServer.session())
        server.json("POST /api/auth/refresh", StandInServer.session(accessToken = "access-2", refreshToken = "refresh-2"))
        server.nothing("POST /api/auth/sign-out")
        server.planner(entries = listOf(rings()))
        phone.container.session.signInForDevelopment("anna@example.com")
        phone.container.planner.sync()
        server.plannerUnreachable()
        phone.container.planner.setStatus("t2", Status.DONE)
        phone.container.planner.sync()
        server.calls.clear()
    }

    @After
    fun off() {
        phone.off()
    }

    private fun work(): ListenableWorker.Result = runBlocking { TestListenableWorkerBuilder<SendWorker>(context).build().doWork() }

    private fun waiting(): Waiting? = runBlocking { phone.container.planner.planner.first()!!.entry("t2")!!.waiting }

    @Test
    fun `with a connection the work sends what waits and is done`() {
        server.json("PUT /api/entries/t2/status", rings(status = "DONE", version = 1))
        server.planner(entries = listOf(rings(status = "DONE", version = 1)))

        assertEquals(ListenableWorker.Result.success(), work())

        assertEquals("""{"status":"DONE"}""", server.routes("PUT /api/entries/t2/status").single().body)
        assertNull(waiting())
    }

    @Test
    fun `without one it asks to be run again, and the change goes on waiting`() {
        assertEquals(ListenableWorker.Result.retry(), work())

        assertEquals(Waiting.TO_BE_SENT, waiting())
    }

    @Test
    fun `a change the person has to look at first does not keep the work coming back`() {
        server.problem("PUT /api/entries/t2/status", 404, "not-found")
        server.planner()

        assertEquals(ListenableWorker.Result.success(), work())
    }

    @Test
    fun `after a sign-out there is nothing to send, and nothing is asked of the server`() = runBlocking {
        phone.container.session.signOut()
        server.calls.clear()

        assertEquals(ListenableWorker.Result.success(), work())

        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun `the work also finds who is signed in when the app was not open`() {
        phone = phone.restarted()
        (context as WeddingPlannerApp).container = phone.container
        server.json("PUT /api/entries/t2/status", rings(status = "DONE", version = 1))
        server.planner(entries = listOf(rings(status = "DONE", version = 1)))

        assertEquals(ListenableWorker.Result.success(), work())

        assertEquals(1, server.routes("PUT /api/entries/t2/status").size)
        // The session was renewed with what the phone had kept.
        assertEquals(1, server.routes("POST /api/auth/refresh").size)
    }

    @Test
    fun `the phone is asked for one piece of work that waits for a connection, and runs it once there is one`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val later = SendWithWorkManager(context)

        later.whenConnected()
        later.whenConnected()

        val manager = WorkManager.getInstance(context)
        val asked = manager.getWorkInfosForUniqueWork(SendWorker.NAME).get().filter { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(1, asked.size)
        assertEquals(NetworkType.CONNECTED, asked.single().constraints.requiredNetworkType)
        // Named, so that a failure says what was asked for.
        assertEquals(emptyList<String>(), server.calls.map { it.route })

        // The connection is back.
        server.json("PUT /api/entries/t2/status", rings(status = "DONE", version = 1))
        server.planner(entries = listOf(rings(status = "DONE", version = 1)))
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(asked.single().id)

        // The work talks to the server on threads of its own: wait until it has finished.
        val until = System.currentTimeMillis() + 10_000
        while (!manager.getWorkInfoById(asked.single().id).get()!!.state.isFinished && System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertEquals(WorkInfo.State.SUCCEEDED, manager.getWorkInfoById(asked.single().id).get()!!.state)
        assertNull(waiting())
    }
}
