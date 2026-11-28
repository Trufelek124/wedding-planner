package io.github.trufelek124.weddingplanner.reminders

import androidx.test.core.app.ApplicationProvider
import io.github.trufelek124.weddingplanner.data.Budget
import io.github.trufelek124.weddingplanner.data.Entry
import io.github.trufelek124.weddingplanner.data.Kind
import io.github.trufelek124.weddingplanner.data.Planner
import io.github.trufelek124.weddingplanner.data.Priority
import io.github.trufelek124.weddingplanner.data.Status
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What is due and how a reminder says it, worked out from a copy of the planner with no phone and no server. Everything
 * here is made up. The words come from the app's resources, which is all that Android is in this.
 */
@RunWith(RobolectricTestRunner::class)
class DueTest {

    private val today = LocalDate.of(2026, 11, 28)
    private val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    private fun entry(
        title: String,
        due: String?,
        status: Status = Status.NOT_STARTED,
        kind: Kind = Kind.TASK,
        amount: Long? = null,
        id: String = title,
    ) = Entry(
        id = id, kind = kind, title = title, notes = "", categoryId = null, startDate = null, dueDate = due?.let(LocalDate::parse),
        status = status, priority = Priority.NORMAL, priorityReason = "NO_DATE", parentId = null, vendorId = null, amount = amount,
        paymentKind = null, paidOn = null, assigneeIds = emptyList(), taskCount = 0, version = 0,
    )

    private fun planner(vararg entries: Entry, currency: String = "PLN") = Planner(
        loadedAt = Instant.EPOCH, weddingDate = null, currency = currency, timeZone = "Europe/Warsaw", daysLeft = null,
        today = today, budget = Budget(null, 0, 0, 0), entries = entries.toList(), categories = emptyMap(), vendors = emptyMap(),
        members = emptyList(), overdue = emptyList(), next = emptyList(), nextPayments = emptyList(),
    )

    private fun names(ahead: Ahead, vararg entries: Entry) = dueThings(planner(*entries), today, ahead).map { it.name }

    @Test
    fun `what is overdue is due whatever the choice`() {
        val late = entry("Book the band", "2026-11-22")
        for (ahead in Ahead.entries) {
            assertEquals(listOf("Book the band"), names(ahead, late))
        }
        assertEquals(Day.Overdue(LocalDate.of(2026, 11, 22)), dueThings(planner(late), today, Ahead.TODAY).single().day)
    }

    @Test
    fun `each choice of days takes its last day and not the next`() {
        val everyDay = (0..8).map { entry("In $it", today.plusDays(it.toLong()).toString()) }.toTypedArray()

        assertEquals(listOf("In 0"), names(Ahead.TODAY, *everyDay))
        assertEquals(listOf("In 0", "In 1"), names(Ahead.TOMORROW, *everyDay))
        assertEquals(listOf("In 0", "In 1", "In 2", "In 3"), names(Ahead.THREE_DAYS, *everyDay))
        assertEquals((0..7).map { "In $it" }, names(Ahead.SEVEN_DAYS, *everyDay))
    }

    @Test
    fun `yesterday is overdue and today is not`() {
        val things = dueThings(planner(entry("Yesterday", "2026-11-27"), entry("Today", "2026-11-28")), today, Ahead.TODAY)

        assertEquals(Day.Overdue(LocalDate.of(2026, 11, 27)), things[0].day)
        assertEquals(Day.Today, things[1].day)
    }

    @Test
    fun `done and dropped are left out, a task with no day is left out`() {
        val all = arrayOf(
            entry("Open", "2026-11-20"),
            entry("Started", "2026-11-20", status = Status.IN_PROGRESS),
            entry("A state a newer server knows", "2026-11-20", status = Status.OTHER),
            entry("Done", "2026-11-20", status = Status.DONE),
            entry("Dropped", "2026-11-20", status = Status.DROPPED),
            entry("No day", null),
        )

        assertEquals(listOf("A state a newer server knows", "Open", "Started"), names(Ahead.SEVEN_DAYS, *all))
    }

    @Test
    fun `a step is due on its last day`() {
        val step = entry("Find the venue", "2026-11-29", kind = Kind.PLAN_ITEM)
            .let { it.copy(startDate = LocalDate.of(2026, 10, 1)) }

        assertEquals(listOf("Find the venue"), names(Ahead.TOMORROW, step))
        assertEquals(emptyList<String>(), names(Ahead.TODAY, step))
        // Begun long ago, and still open two days after its last day: overdue.
        assertEquals(Day.Overdue(LocalDate.of(2026, 11, 29)), dueThings(planner(step), today.plusDays(2), Ahead.TODAY).single().day)
    }

    @Test
    fun `a payment carries its amount as the app writes money`() {
        val payment = entry("Pay the venue's deposit", "2026-11-28", amount = 250000)

        val thing = dueThings(planner(payment), today, Ahead.TODAY).single()

        assertEquals("2,500.00 zł", thing.amount)
        assertNull(dueThings(planner(entry("A task", "2026-11-28")), today, Ahead.TODAY).single().amount)
    }

    @Test
    fun `the overdue come first, then by day, then by name`() {
        val things = names(
            Ahead.SEVEN_DAYS,
            entry("Zebra cake", "2026-11-30"),
            entry("apple tart", "2026-11-30"),
            entry("Today's task", "2026-11-28"),
            entry("Later", "2026-12-03"),
            entry("Overdue twice", "2026-11-20"),
            entry("Overdue once", "2026-11-26"),
            entry("Bread", "2026-11-30"),
        )

        assertEquals(listOf("Overdue twice", "Overdue once", "Today's task", "apple tart", "Bread", "Zebra cake", "Later"), things)
    }

    @Test
    fun `nothing due is nothing`() {
        assertEquals(emptyList<DueThing>(), dueThings(planner(entry("Far off", "2027-03-01")), today, Ahead.SEVEN_DAYS))
        assertEquals(emptyList<DueThing>(), dueThings(planner(), today, Ahead.SEVEN_DAYS))
        assertNull(reminderOf(emptyList(), resources))
    }

    @Test
    fun `a day is said with its month, and with its year where that is not this one`() {
        assertEquals("22 Nov", dayWords(LocalDate.of(2026, 11, 22), today))
        assertEquals("3 Dec", dayWords(LocalDate.of(2026, 12, 3), today))
        assertEquals("3 Jan 2027", dayWords(LocalDate.of(2027, 1, 3), today))
        assertEquals("30 Dec 2025", dayWords(LocalDate.of(2025, 12, 30), today))
    }

    private fun said(vararg entries: Entry, ahead: Ahead = Ahead.SEVEN_DAYS) = reminderOf(dueThings(planner(*entries), today, ahead), resources)!!

    @Test
    fun `one thing says what it is and when, in each of the four ways`() {
        assertEquals("Book the band is overdue", said(entry("Book the band", "2026-11-22")).title)
        assertEquals("Book the band is due today", said(entry("Book the band", "2026-11-28")).title)
        assertEquals("Book the band is due tomorrow", said(entry("Book the band", "2026-11-29")).title)
        assertEquals("Book the band is due on 3 Dec", said(entry("Book the band", "2026-12-03")).title)
    }

    @Test
    fun `one thing has no list, and a payment says what it comes to`() {
        val task = said(entry("Book the band", "2026-11-28"))
        assertEquals(emptyList<String>(), task.lines)
        assertNull(task.text)
        assertEquals(1, task.count)
        assertEquals("1 thing is due", task.publicTitle)

        val payment = said(entry("Pay the venue's deposit", "2026-11-28", amount = 250000))
        assertEquals("Pay the venue's deposit is due today", payment.title)
        assertEquals("Payment of 2,500.00 zł", payment.text)
    }

    @Test
    fun `several things say how many and list them with their days`() {
        val reminder = said(
            entry("Book the band", "2026-11-22"),
            entry("Pay the venue's deposit", "2026-11-28", amount = 250000),
            entry("Send the invitations", "2026-11-29"),
            entry("Choose the cake", "2026-12-03"),
        )

        assertEquals("4 things are due", reminder.title)
        assertEquals("4 things are due", reminder.publicTitle)
        assertEquals(4, reminder.count)
        assertEquals(
            listOf(
                "Book the band — overdue since 22 Nov",
                "Pay the venue's deposit, 2,500.00 zł — today",
                "Send the invitations — tomorrow",
                "Choose the cake — on 3 Dec",
            ),
            reminder.lines,
        )
        assertEquals(reminder.lines.first(), reminder.text)
    }

    @Test
    fun `five things are five lines and six are five lines and one more`() {
        val five = (1..5).map { entry("Thing $it", "2026-11-28") }
        assertEquals(5, said(*five.toTypedArray()).lines.size)
        assertTrue(said(*five.toTypedArray()).lines.none { it.startsWith("and ") })

        val six = said(*(five + entry("Thing 6", "2026-11-28")).toTypedArray())
        assertEquals("6 things are due", six.title)
        assertEquals(6, six.lines.size)
        assertEquals("Thing 5 — today", six.lines[4])
        assertEquals("and 1 more", six.lines[5])

        val eight = said(*(1..8).map { entry("Thing $it", "2026-11-28") }.toTypedArray())
        assertEquals(8, eight.count)
        assertEquals("and 3 more", eight.lines.last())
        assertEquals(6, eight.lines.size)
    }

    @Test
    fun `what is printed names nothing`() {
        val thing = dueThings(planner(entry("Book the band", "2026-11-22", id = "t1")), today, Ahead.TODAY).single()

        assertEquals("DueThing[entryId=t1]", thing.toString())
        assertEquals("Reminder[count=1]", said(entry("Book the band", "2026-11-22")).toString())
    }

    @Test
    fun `the work is set for the next time of day, today's if it is still to come`() {
        val warsaw = ZoneId.of("Europe/Warsaw")
        val morning = ZonedDateTime.of(2026, 11, 28, 7, 30, 0, 0, warsaw)

        assertEquals(90, untilNext(morning, LocalTime.of(9, 0)).toMinutes())
        // Exactly now is already past: the next one is tomorrow's.
        assertEquals(24 * 60, untilNext(morning, LocalTime.of(7, 30)).toMinutes())
        assertEquals(24 * 60 - 30, untilNext(morning, LocalTime.of(7, 0)).toMinutes())
        assertEquals(60 * 16 + 30, untilNext(morning.withHour(7).withMinute(30), LocalTime.of(0, 0)).toMinutes())
    }

    @Test
    fun `the day the clocks change is no longer or shorter than the clock says`() {
        val warsaw = ZoneId.of("Europe/Warsaw")
        // The night to 29 March 2026: 2:00 became 3:00.
        val evening = ZonedDateTime.of(2026, 3, 28, 22, 0, 0, 0, warsaw)

        // Eleven hours on an ordinary night, ten on this one.
        assertEquals(10 * 60, untilNext(evening, LocalTime.of(9, 0)).toMinutes())
    }
}
