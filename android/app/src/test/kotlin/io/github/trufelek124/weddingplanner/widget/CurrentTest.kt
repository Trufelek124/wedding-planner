package io.github.trufelek124.weddingplanner.widget

import androidx.test.core.app.ApplicationProvider
import io.github.trufelek124.weddingplanner.data.Kind
import io.github.trufelek124.weddingplanner.data.Status
import io.github.trufelek124.weddingplanner.reminders.Day
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What is current and how a row says it, worked out from a copy of the planner with no phone and no server (S12 section 2).
 * Everything here is made up. The words come from the app's resources, which is all that Android is in this.
 */
@RunWith(RobolectricTestRunner::class)
class CurrentTest {

    private val today = WIDGET_TODAY
    private val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    private fun names(vararg entries: io.github.trufelek124.weddingplanner.data.Entry) = currentThings(aPlanner(*entries), today).map { it.name }

    private fun inDays(days: Long) = today.plusDays(days).toString()

    @Test
    fun `what is overdue is current, however long ago`() {
        assertEquals(listOf("Old", "Yesterday"), names(anEntry("Yesterday", inDays(-1)), anEntry("Old", "2025-01-10")))
    }

    @Test
    fun `what is due within fourteen days is current, today included, and the fifteenth day is not`() {
        val every = (0..16).map { anEntry("In $it", inDays(it.toLong())) }.toTypedArray()

        assertEquals((0..14).map { "In $it" }, names(*every))
        assertFalse(names(*every).contains("In 15"))
    }

    @Test
    fun `what is in progress is current whatever its day, with or without one`() {
        val things = names(
            anEntry("Far off", inDays(200), status = Status.IN_PROGRESS),
            anEntry("No day", null, status = Status.IN_PROGRESS),
            anEntry("Not begun, far off", inDays(200)),
            anEntry("Not begun, no day", null),
        )

        assertEquals(listOf("Far off", "No day"), things)
    }

    @Test
    fun `done and dropped are left out, however soon or late they are due, in progress or not`() {
        assertTrue(
            names(
                anEntry("Done late", inDays(-3), status = Status.DONE),
                anEntry("Done soon", inDays(2), status = Status.DONE),
                anEntry("Dropped late", inDays(-3), status = Status.DROPPED),
                anEntry("Dropped soon", inDays(2), status = Status.DROPPED),
            ).isEmpty(),
        )
    }

    @Test
    fun `a status this app does not know counts as still to do`() {
        assertEquals(listOf("Newer status"), names(anEntry("Newer status", inDays(1), status = Status.OTHER)))
    }

    @Test
    fun `a step is current on its last day, and a task and a step are listed together`() {
        val things = currentThings(
            aPlanner(anEntry("Venue", inDays(3), kind = Kind.PLAN_ITEM), anEntry("Rings", inDays(1)), anEntry("Far step", inDays(30), kind = Kind.PLAN_ITEM)),
            today,
        )

        assertEquals(listOf("Rings", "Venue"), things.map { it.name })
        assertEquals(listOf(false, true), things.map { it.isStep })
    }

    @Test
    fun `the overdue come first, then the nearest day, what has no day last, and equal days by name`() {
        val things = names(
            anEntry("No day, in progress", null, status = Status.IN_PROGRESS),
            anEntry("later", inDays(5)),
            anEntry("Zebra", inDays(2)),
            anEntry("apple", inDays(2)),
            anEntry("Today", inDays(0)),
            anEntry("Late", inDays(-1)),
            anEntry("Later still", inDays(9), status = Status.IN_PROGRESS),
            anEntry("Very late", inDays(-20)),
        )

        assertEquals(listOf("Very late", "Late", "Today", "apple", "Zebra", "later", "Later still", "No day, in progress"), things)
    }

    @Test
    fun `a task ticked off on the phone and not sent yet is not listed, because the copy already shows it done`() {
        // What the phone's copy holds with the waiting tick laid over it: the status is DONE, and it waits to be sent.
        val ticked = anEntry("Rings", inDays(1), status = Status.DONE).copy(waiting = io.github.trufelek124.weddingplanner.data.Waiting.TO_BE_SENT)

        assertEquals(listOf("Cake"), names(ticked, anEntry("Cake", inDays(1))))
    }

    @Test
    fun `the day of a row is told as a reminder tells it`() {
        val things = currentThings(
            aPlanner(anEntry("Late", "2026-11-22"), anEntry("Now", inDays(0)), anEntry("Soon", inDays(1)), anEntry("Later", "2026-12-03"), anEntry("Idle", null, status = Status.IN_PROGRESS)),
            today,
        )

        assertEquals(
            listOf(Day.Overdue(LocalDate.of(2026, 11, 22)), Day.Today, Day.Tomorrow, Day.On(LocalDate.of(2026, 12, 3)), null),
            things.map { it.day },
        )
    }

    @Test
    fun `a payment says its amount in the wedding's currency`() {
        val thing = currentThings(aPlanner(anEntry("Pay the venue's deposit", inDays(0), amount = 250000)), today).single()

        assertEquals("2,500.00\u00a0zł", thing.amount)
    }

    @Test
    fun `the words of a task, a payment, a step, and of each day`() {
        fun row(entry: io.github.trufelek124.weddingplanner.data.Entry) = rowOf(currentThings(aPlanner(entry), today).single(), resources)

        assertEquals("overdue since 22 Nov", row(anEntry("Book the band", "2026-11-22")).detail)
        assertEquals("today", row(anEntry("Rings", inDays(0))).detail)
        assertEquals("tomorrow", row(anEntry("Cake", inDays(1))).detail)
        assertEquals("on 3 Dec", row(anEntry("Flowers", "2026-12-03")).detail)
        assertEquals("on 3 Jan 2027", row(anEntry("Next year", "2027-01-03", status = Status.IN_PROGRESS)).detail)
        assertEquals("in progress", row(anEntry("No day", null, status = Status.IN_PROGRESS)).detail)
        assertEquals("2,500.00\u00a0zł — today", row(anEntry("Deposit", inDays(0), amount = 250000)).detail)
        assertEquals("Step — on 3 Dec", row(anEntry("Venue", "2026-12-03", kind = Kind.PLAN_ITEM)).detail)
        assertEquals("Step — in progress", row(anEntry("Venue", null, kind = Kind.PLAN_ITEM, status = Status.IN_PROGRESS)).detail)
    }

    @Test
    fun `a task has a box that says done, a payment says paid, and a step has no box`() {
        fun tick(entry: io.github.trufelek124.weddingplanner.data.Entry) = rowOf(currentThings(aPlanner(entry), today).single(), resources).tick

        assertEquals("Done: Rings", tick(anEntry("Rings", inDays(0))))
        assertEquals("Paid: Deposit", tick(anEntry("Deposit", inDays(0), amount = 100)))
        assertNull(tick(anEntry("Venue", inDays(0), kind = Kind.PLAN_ITEM)))
    }

    @Test
    fun `the head counts what is listed, with a sentence of its own for none`() {
        val none = currentModelOf(currentThings(aPlanner(anEntry("Far", inDays(40))), today), resources)
        val one = currentModelOf(currentThings(aPlanner(anEntry("Rings", inDays(1))), today), resources)
        val two = currentModelOf(currentThings(aPlanner(anEntry("Rings", inDays(1)), anEntry("Cake", inDays(2))), today), resources)

        assertEquals("Wedding planner", two.name)
        assertEquals("Nothing current", none.count)
        assertEquals("Nothing is current. What is overdue, due within 14 days or in progress shows here.", none.nothing)
        assertEquals("1 thing", one.count)
        assertNull(one.nothing)
        assertEquals("2 things", two.count)
        assertEquals("Add a task", two.addTask)
        assertEquals("Add a step", two.addStep)
    }

    @Test
    fun `nothing of a task is printed by what holds it`() {
        val thing = currentThings(aPlanner(anEntry("The florist's secret name", inDays(0), id = "e1")), today).single()
        val row = rowOf(thing, resources)
        val model = currentModelOf(listOf(thing), resources)

        for (printed in listOf(thing.toString(), row.toString(), model.toString())) {
            assertFalse(printed, printed.contains("florist"))
        }
        assertEquals("CurrentThing[entryId=e1]", thing.toString())
    }
}
