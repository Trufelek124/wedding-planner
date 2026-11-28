package io.github.trufelek124.weddingplanner.reminders

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import io.github.trufelek124.weddingplanner.StandInPhone
import io.github.trufelek124.weddingplanner.WeddingPlannerApp
import io.github.trufelek124.weddingplanner.api.StandInServer
import io.github.trufelek124.weddingplanner.data.LivingPlanner
import io.github.trufelek124.weddingplanner.data.StandIn
import io.github.trufelek124.weddingplanner.data.Status
import io.github.trufelek124.weddingplanner.data.planner
import java.time.Duration
import java.time.LocalDate
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
 * The daily reminder against a stand-in phone and a stand-in server: what it shows and when, what it brings up to date
 * first, and what switches it off. The phone's clock stands on 18 November 2026 in Warsaw. Everything here is made up.
 */
@RunWith(RobolectricTestRunner::class)
class RemindingTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var phone: StandInPhone
    private lateinit var theirs: LivingPlanner
    private val server get() = phone.server
    private val reminding get() = phone.container.reminding
    private val notifications get() = phone.notifications

    private val deposit = StandIn.entry("t1", "Pay the venue's deposit", dueDate = "2026-11-18", amount = 250000)
    private val band = StandIn.entry("t2", "Book the band", dueDate = "2026-11-12")
    private val cake = StandIn.entry("t3", "Choose the cake", dueDate = "2026-11-19")
    private val flowers = StandIn.entry("t4", "Order the flowers", dueDate = "2026-11-18", status = "DONE")
    private val later = StandIn.entry("t5", "Print the programme", dueDate = "2026-11-25")

    @Before
    fun aSignedInPhoneWithRemindersOn() {
        phone = StandInPhone(folder.root)
        (context as WeddingPlannerApp).container = phone.container
        server.json("POST /api/auth/dev", StandInServer.session())
        server.json("POST /api/auth/refresh", StandInServer.session(accessToken = "access-2", refreshToken = "refresh-2"))
        server.nothing("POST /api/auth/sign-out")
        server.planner()
        theirs = LivingPlanner(server, entries = listOf(deposit, band, cake, flowers, later))
        runBlocking {
            phone.container.session.signInForDevelopment("anna@example.com")
            phone.container.planner.sync()
            assertTrue(reminding.switchOn())
        }
        server.calls.clear()
    }

    @After
    fun off() {
        phone.off()
    }

    private fun remind() = runBlocking { reminding.remind() }

    private fun work(): ListenableWorker.Result = runBlocking { TestListenableWorkerBuilder<ReminderWorker>(context).build().doWork() }

    private fun nextDay() {
        phone.clock.now = phone.clock.now.plus(Duration.ofDays(1))
    }

    @Test
    fun `with something due one notification says what, the overdue first, and today's takes yesterday's place`() {
        assertEquals(Reminded.SHOWN, remind())

        val shown = notifications.shown!!
        assertEquals("3 things are due", shown.title)
        assertEquals(
            listOf("Book the band — overdue since 12 Nov", "Pay the venue's deposit, 2,500.00 zł — today", "Choose the cake — tomorrow"),
            shown.lines,
        )
        assertEquals(1, notifications.shownEver.size)
    }

    @Test
    fun `one thing due is named in the title`() {
        theirs.entries.keys.filter { it != "t3" }.forEach { theirs.entries.remove(it) }

        assertEquals(Reminded.SHOWN, remind())

        assertEquals("Choose the cake is due tomorrow", notifications.shown!!.title)
    }

    @Test
    fun `nothing due shows nothing, and takes away one that is still there`() {
        assertEquals(Reminded.SHOWN, remind())
        assertEquals(3, notifications.shown!!.count)
        nextDay()
        for (id in listOf("t1", "t2", "t3")) {
            theirs.put(StandIn.entry(id, "Done by now", status = "DONE", version = 1))
        }

        assertEquals(Reminded.NOTHING_DUE, remind())

        assertNull(notifications.shown)
        assertEquals(1, notifications.shownEver.size)
    }

    @Test
    fun `the copy is brought up to date first, so what the partner ticked off is not named`() {
        theirs.put(StandIn.entry("t2", "Book the band", dueDate = "2026-11-12", status = "DONE", version = 1))

        assertEquals(Reminded.SHOWN, remind())

        assertTrue(server.routes("GET /api/entries").isNotEmpty())
        assertEquals("2 things are due", notifications.shown!!.title)
        assertFalse(notifications.shown!!.lines.any { it.contains("band") })
    }

    @Test
    fun `without a connection it still reminds, from the copy it has`() {
        server.down = true

        assertEquals(Reminded.SHOWN, remind())

        assertEquals("3 things are due", notifications.shown!!.title)
    }

    @Test
    fun `a task ticked off on the phone and not sent yet is not named`() {
        server.down = true
        runBlocking { phone.container.planner.setStatus("t2", Status.DONE) }

        assertEquals(Reminded.SHOWN, remind())

        assertEquals("2 things are due", notifications.shown!!.title)
    }

    @Test
    fun `a second run on the same day shows nothing again, and the next day it does`() {
        assertEquals(Reminded.SHOWN, remind())
        assertEquals(Reminded.ALREADY_SHOWN, remind())
        assertEquals(Reminded.ALREADY_SHOWN, remind())
        assertEquals(1, notifications.shownEver.size)

        nextDay()
        assertEquals(Reminded.SHOWN, remind())

        assertEquals(2, notifications.shownEver.size)
        assertEquals(LocalDate.of(2026, 11, 19), runBlocking { reminding.settings.first().lastShown })
        // Two days' worth was shown in the same place: the phone holds one.
        assertEquals("3 things are due", notifications.shown!!.title)
    }

    @Test
    fun `how far ahead is the owner's choice`() {
        runBlocking { reminding.setAhead(Ahead.TODAY) }
        assertEquals(Reminded.SHOWN, remind())
        assertEquals("2 things are due", notifications.shown!!.title)

        nextDay()
        runBlocking { reminding.setAhead(Ahead.SEVEN_DAYS) }
        assertEquals(Reminded.SHOWN, remind())
        // Now the 19th: the 25th is six days off.
        assertEquals("4 things are due", notifications.shown!!.title)
    }

    @Test
    fun `nothing is shown while reminders are off`() {
        runBlocking { reminding.switchOff() }
        val redrawsBefore = phone.widget.redraws

        assertEquals(Reminded.OFF, remind())

        assertEquals(0, notifications.shownEver.size)
        // The daily work also keeps the widget up to date, so the copy is still brought up to date, and the widget drawn.
        assertTrue(server.routes("GET /api/entries").isNotEmpty())
        assertTrue(phone.widget.redraws > redrawsBefore)
    }

    @Test
    fun `nothing is shown while nobody is signed in, and the server is not asked`() {
        runBlocking {
            phone.container.session.signOut()
            // Switched on again after the sign-out: whatever comes of the work, nobody's plan is shown.
            reminding.switchOn()
        }
        server.calls.clear()

        assertEquals(Reminded.SIGNED_OUT, remind())

        assertEquals(0, notifications.shownEver.size)
        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun `nothing is shown where the phone does not allow notifications`() {
        notifications.allowed = false

        assertEquals(Reminded.NOT_ALLOWED, remind())

        assertEquals(0, notifications.shownEver.size)
        // Not allowed to show anything, but the widget still follows the plan.
        assertTrue(server.routes("GET /api/entries").isNotEmpty())
    }

    @Test
    fun `the work finds who is signed in when the app was not open, and reminds`() {
        phone = phone.restarted()
        (context as WeddingPlannerApp).container = phone.container
        server.down = true

        assertEquals(ListenableWorker.Result.success(), work())

        // From what the phone kept: no copy was lost with the app, and the settings stayed.
        assertEquals("3 things are due", notifications.shown!!.title)
    }

    @Test
    fun `a day with nothing to say, or no connection, is a successful run`() {
        server.down = true
        assertEquals(ListenableWorker.Result.success(), work())
        runBlocking { reminding.switchOff() }
        assertEquals(ListenableWorker.Result.success(), work())
    }

    @Test
    fun `a phone that holds nothing of the planner yet has nothing to say`() {
        phone = phone.restarted()
        phone.container.reminding.let { runBlocking { it.switchOn() } }
        // A new, empty phone folder: signed in, but nothing was ever loaded and there is no connection.
        runBlocking { phone.container.planner.forget() }
        phone.server.down = true

        assertEquals(Reminded.NO_COPY, runBlocking { phone.container.reminding.remind() })

        assertEquals(0, notifications.shownEver.size)
    }

    @Test
    fun `switching on sets the work for the chosen time, and off takes the reminder away but leaves the work for the widget`() {
        assertEquals(listOf(LocalTime.of(9, 0)), phone.reminderWork.setFor)
        assertTrue(phone.reminderWork.running)
        assertEquals(Reminded.SHOWN, remind())

        runBlocking { reminding.switchOff() }

        assertTrue(phone.reminderWork.running)
        assertEquals(0, phone.reminderWork.stops)
        assertNull(notifications.shown)
        assertFalse(runBlocking { reminding.settings.first().on })
    }

    @Test
    fun `it begins off, at nine, for today and tomorrow, and cannot be switched on where the phone does not allow it`() {
        val fresh = StandInPhone(TemporaryFolder().apply { create() }.root)
        try {
            val begin = runBlocking { fresh.container.reminding.settings.first() }
            assertFalse(begin.on)
            assertEquals(LocalTime.of(9, 0), begin.at)
            assertEquals(Ahead.TOMORROW, begin.ahead)
            assertNull(begin.lastShown)
            assertTrue(fresh.reminderWork.setFor.isEmpty())

            fresh.notifications.allowed = false
            assertFalse(runBlocking { fresh.container.reminding.switchOn() })

            assertFalse(runBlocking { fresh.container.reminding.settings.first().on })
            assertTrue(fresh.reminderWork.setFor.isEmpty())
        } finally {
            fresh.off()
        }
    }

    @Test
    fun `a new time sets the work again, and what is chosen is still there after a restart`() {
        runBlocking {
            reminding.setTime(LocalTime.of(18, 30))
            reminding.setAhead(Ahead.THREE_DAYS)
        }
        assertEquals(listOf(LocalTime.of(9, 0), LocalTime.of(18, 30)), phone.reminderWork.setFor)

        phone = phone.restarted()

        val kept = runBlocking { phone.container.reminding.settings.first() }
        assertTrue(kept.on)
        assertEquals(LocalTime.of(18, 30), kept.at)
        assertEquals(Ahead.THREE_DAYS, kept.ahead)
    }

    @Test
    fun `a time chosen while reminders are off still sets the work, which keeps the widget up to date`() {
        runBlocking {
            reminding.switchOff()
            reminding.setTime(LocalTime.of(7, 15))
        }

        assertEquals(listOf(LocalTime.of(9, 0), LocalTime.of(7, 15)), phone.reminderWork.setFor)
        assertTrue(phone.reminderWork.running)
        assertEquals(LocalTime.of(7, 15), runBlocking { reminding.settings.first().at })
    }

    @Test
    fun `signing out stops the work, takes the reminder away and removes the settings`() {
        runBlocking { reminding.setTime(LocalTime.of(18, 30)) }
        assertEquals(Reminded.SHOWN, remind())
        val stopsBefore = phone.reminderWork.stops

        runBlocking { phone.container.session.signOut() }

        assertFalse(phone.reminderWork.running)
        assertTrue(phone.reminderWork.stops > stopsBefore)
        assertNull(notifications.shown)
        val after = runBlocking { reminding.settings.first() }
        assertFalse(after.on)
        assertEquals(LocalTime.of(9, 0), after.at)
        assertNull(after.lastShown)
        val kept = phone.everythingKept()
        assertFalse(kept.contains("last_shown"))
        assertFalse(kept.contains("minute_of_day"))
    }

    @Test
    fun `nothing of a task or of the plan is written to the log, only that a reminder was shown and for how many`() {
        ShadowLog.clear()
        server.down = true

        assertEquals(Reminded.SHOWN, remind())
        nextDay()
        assertEquals(Reminded.SHOWN, remind())

        val written = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable?.stackTraceToString().orEmpty()}" }
        assertTrue(written, written.contains("A reminder was shown for 3 things"))
        for (theirs in listOf("deposit", "band", "cake", "flowers", "2,500", "Anna", "access-1", "refresh-1")) {
            assertFalse("the log holds \"$theirs\"", written.contains(theirs, ignoreCase = true))
        }
    }
}
