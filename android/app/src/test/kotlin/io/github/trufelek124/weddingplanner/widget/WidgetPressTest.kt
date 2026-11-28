package io.github.trufelek124.weddingplanner.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import io.github.trufelek124.weddingplanner.StandInPhone
import io.github.trufelek124.weddingplanner.WeddingPlannerApp
import io.github.trufelek124.weddingplanner.api.StandInServer
import io.github.trufelek124.weddingplanner.data.LivingPlanner
import io.github.trufelek124.weddingplanner.data.SendWorker
import io.github.trufelek124.weddingplanner.data.StandIn
import io.github.trufelek124.weddingplanner.data.Status
import io.github.trufelek124.weddingplanner.data.Waiting
import io.github.trufelek124.weddingplanner.data.planner
import io.github.trufelek124.weddingplanner.reminders.Reminded
import io.github.trufelek124.weddingplanner.reminders.ReminderWorker
import java.time.LocalTime
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
import org.robolectric.shadows.ShadowLog

/**
 * What a press on the widget changes, and when it is drawn anew, against a stand-in phone and a server that keeps what it
 * is sent. The phone's clock stands on 18 November 2026 in Warsaw. Everything here is made up.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetPressTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var phone: StandInPhone
    private lateinit var theirs: LivingPlanner
    private val server get() = phone.server
    private val container get() = phone.container

    private val band = StandIn.entry("t1", "Book the band", dueDate = "2026-11-12")
    private val deposit = StandIn.entry("t2", "Pay the venue's deposit", dueDate = "2026-11-18", amount = 250000)
    private val venue = StandIn.entry("p1", "Find the venue", kind = "PLAN_ITEM", dueDate = "2026-11-25")
    private val far = StandIn.entry("t3", "Print the programme", dueDate = "2027-03-01")

    @Before
    fun aSignedInPhone() {
        phone = StandInPhone(folder.root)
        (context as WeddingPlannerApp).container = phone.container
        server.json("POST /api/auth/dev", StandInServer.session())
        server.json("POST /api/auth/refresh", StandInServer.session(accessToken = "access-2", refreshToken = "refresh-2"))
        server.nothing("POST /api/auth/sign-out")
        server.planner()
        theirs = LivingPlanner(server, entries = listOf(band, deposit, venue, far))
        runBlocking {
            container.session.signInForDevelopment("anna@example.com")
            container.planner.sync()
        }
        server.calls.clear()
    }

    @After
    fun off() {
        phone.off()
    }

    private fun current(): WidgetModel.Current = runBlocking { container.widgetModels.now() } as WidgetModel.Current

    private fun listed() = current().rows.map { it.name }

    private fun tick(id: String) = runBlocking { container.widgetActions.tick(id) }

    private fun sendWorker(): ListenableWorker.Result = runBlocking { TestListenableWorkerBuilder<SendWorker>(context).build().doWork() }

    private fun dailyWorker(): ListenableWorker.Result = runBlocking { TestListenableWorkerBuilder<ReminderWorker>(context).build().doWork() }

    private fun waiting(id: String): Waiting? = runBlocking { container.planner.planner.first()!!.entry(id)?.waiting }

    // ---------------------------------------------------------------- what the widget lists

    @Test
    fun `the widget lists what is current, the overdue first, and says how many`() {
        assertEquals(listOf("Book the band", "Pay the venue's deposit", "Find the venue"), listed())
        assertEquals("3 things", current().count)
        assertEquals(listOf("overdue since 12 Nov", "2,500.00 zł — today", "Step — on 25 Nov"), current().rows.map { it.detail })
        assertEquals(listOf("Done: Book the band", "Paid: Pay the venue's deposit", null), current().rows.map { it.tick })
    }

    // ---------------------------------------------------------------- ticking a task off

    @Test
    fun `a tick is kept on the phone at once, the row leaves, and the phone is asked to send it when there is a connection`() {
        val asked = phone.askedToSendLater

        assertTrue(tick("t1"))

        assertEquals(Waiting.TO_BE_SENT, waiting("t1"))
        assertEquals(listOf("Pay the venue's deposit", "Find the venue"), listed())
        assertEquals("2 things", current().count)
        assertEquals(asked + 1, phone.askedToSendLater)
        // Nothing is sent by the press itself: the work the phone was asked for does it.
        assertTrue(server.routes("PUT /api/entries/t1/status").isEmpty())
    }

    @Test
    fun `the work sends the tick once there is a connection, and the server then has it`() {
        tick("t1")

        assertEquals(ListenableWorker.Result.success(), sendWorker())

        assertEquals("""{"status":"DONE"}""", server.routes("PUT /api/entries/t1/status").single().body)
        assertEquals("DONE", theirs.statusOf("t1"))
        assertNull(waiting("t1"))
        assertEquals(listOf("Pay the venue's deposit", "Find the venue"), listed())
        // Once the work has done it, there is nothing left for another run to send.
        assertEquals(ListenableWorker.Result.success(), sendWorker())
        assertEquals(1, server.routes("PUT /api/entries/t1/status").size)
    }

    @Test
    fun `after the app was closed the tick is still sent, by the work alone`() {
        tick("t1")
        phone = phone.restarted()
        (context as WeddingPlannerApp).container = phone.container
        server.calls.clear()

        assertEquals(ListenableWorker.Result.success(), sendWorker())

        assertEquals(1, server.routes("PUT /api/entries/t1/status").size)
        assertEquals("DONE", theirs.statusOf("t1"))
    }

    @Test
    fun `a payment is marked paid the way the app's own tick marks it`() {
        assertTrue(tick("t2"))

        assertEquals(ListenableWorker.Result.success(), sendWorker())

        assertEquals("""{"status":"DONE"}""", server.routes("PUT /api/entries/t2/status").single().body)
        assertEquals(Status.DONE, runBlocking { container.planner.planner.first()!!.entry("t2")!!.status })
        assertFalse(listed().contains("Pay the venue's deposit"))
    }

    @Test
    fun `without a connection the tick is kept, the row is gone, and it goes out when the connection is back`() {
        server.down = true

        assertTrue(tick("t1"))

        assertEquals(ListenableWorker.Result.retry(), sendWorker())
        assertEquals(Waiting.TO_BE_SENT, waiting("t1"))
        assertFalse(listed().contains("Book the band"))
        server.down = false
        assertEquals(ListenableWorker.Result.success(), sendWorker())
        assertEquals("DONE", theirs.statusOf("t1"))
    }

    @Test
    fun `pressing the box again does nothing more, and one tick is one request`() {
        assertTrue(tick("t1"))
        assertFalse(tick("t1"))

        sendWorker()

        assertEquals(1, server.routes("PUT /api/entries/t1/status").size)
    }

    @Test
    fun `a step has no box, and a press that names one changes nothing`() {
        assertFalse(tick("p1"))

        assertNull(waiting("p1"))
        assertEquals(0, runBlocking { container.planner.stillToSend() })
        assertTrue(listed().contains("Find the venue"))
    }

    @Test
    fun `an entry that is not there, or is not open any more, is not ticked`() {
        theirs.put(StandIn.entry("t4", "Done already", dueDate = "2026-11-18", status = "DONE"))
        runBlocking { container.planner.sync() }

        assertFalse(tick("nothing-of-that-name"))
        assertFalse(tick("t4"))

        assertEquals(0, runBlocking { container.planner.stillToSend() })
    }

    @Test
    fun `nobody signed in, nothing is ticked`() {
        runBlocking { container.session.signOut() }
        val asked = phone.askedToSendLater

        assertFalse(tick("t1"))

        assertEquals(asked, phone.askedToSendLater)
        assertEquals(0, runBlocking { container.planner.stillToSend() })
    }

    // ---------------------------------------------------------------- what the widget says when nobody is signed in

    @Test
    fun `after signing out the widget says one sentence and nothing of the plan`() {
        runBlocking { container.session.signOut() }

        val model = runBlocking { container.widgetModels.now() }

        assertEquals(WidgetModel.SignedOut("Open the planner to sign in."), model)
        assertEquals("SignedOut(sentence=Open the planner to sign in.)", model.toString())
    }

    @Test
    fun `a phone that is signed in and holds no copy yet says so, and nothing of a plan`() {
        runBlocking { container.planner.forget() }

        assertEquals(WidgetModel.NoCopy("Open the planner to load your plan."), runBlocking { container.widgetModels.now() })
    }

    @Test
    fun `the widget is found signed in by the app that was not open`() {
        phone = phone.restarted()
        (context as WeddingPlannerApp).container = phone.container

        assertEquals(listOf("Book the band", "Pay the venue's deposit", "Find the venue"), listed())
    }

    // ---------------------------------------------------------------- drawn anew

    @Test
    fun `it is drawn anew after a load`() {
        val before = phone.widget.redraws

        runBlocking { container.planner.sync() }

        assertTrue(phone.widget.redraws > before)
    }

    @Test
    fun `it is drawn anew after a change made in the app`() {
        val before = phone.widget.redraws

        runBlocking { container.planner.setStatus("t1", Status.DONE) }

        assertTrue(phone.widget.redraws > before)
    }

    @Test
    fun `it is drawn anew after its own tick`() {
        val before = phone.widget.redraws

        tick("t1")

        assertTrue(phone.widget.redraws > before)
    }

    @Test
    fun `it is drawn anew by the daily work, whether or not reminders are on, and the copy is brought up to date first`() {
        assertFalse(runBlocking { container.reminding.settings.first().on })
        theirs.put(StandIn.entry("t1", "Book the band", dueDate = "2026-11-12", status = "DONE", version = 1))
        val before = phone.widget.redraws

        assertEquals(ListenableWorker.Result.success(), dailyWorker())

        assertTrue(phone.widget.redraws > before)
        assertTrue(server.routes("GET /api/entries").isNotEmpty())
        assertFalse(listed().contains("Book the band"))
        assertEquals(0, phone.notifications.shownEver.size)
        assertEquals(Reminded.OFF, runBlocking { container.reminding.remind() })
    }

    @Test
    fun `the daily work draws it for a new day even where the server cannot be reached`() {
        server.down = true
        val before = phone.widget.redraws

        dailyWorker()

        assertTrue(phone.widget.redraws > before)
    }

    @Test
    fun `signing out draws it anew and empties it`() {
        val before = phone.widget.redraws

        runBlocking { container.session.signOut() }

        assertTrue(phone.widget.redraws > before)
        assertTrue(runBlocking { container.widgetModels.now() } is WidgetModel.SignedOut)
    }

    @Test
    fun `while somebody is signed in the daily work is set, reminders or not`() {
        runBlocking {
            // The sign-in of the setup has been heard of by the container: the work is kept for the time of the settings.
            val until = System.currentTimeMillis() + 5_000
            while (phone.reminderWork.keptFor.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(10)
        }

        assertEquals(listOf(LocalTime.of(9, 0)), phone.reminderWork.keptFor.distinct())
        assertFalse(runBlocking { container.reminding.settings.first().on })
    }

    // ---------------------------------------------------------------- the log

    @Test
    fun `nothing of a task is written to the log by ticking, drawing or the daily work`() {
        ShadowLog.clear()

        tick("t1")
        tick("t2")
        sendWorker()
        dailyWorker()
        runBlocking {
            container.widgetModels.now()
            container.session.signOut()
        }

        val written = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable?.stackTraceToString().orEmpty()}" }
        for (theirs in listOf("band", "deposit", "venue", "programme", "2,500", "Anna", "access-1", "refresh-1")) {
            assertFalse("the log holds \"$theirs\"", written.contains(theirs, ignoreCase = true))
        }
    }
}
