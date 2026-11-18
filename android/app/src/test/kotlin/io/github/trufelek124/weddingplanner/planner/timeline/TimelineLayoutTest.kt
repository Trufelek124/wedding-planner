package io.github.trufelek124.weddingplanner.planner.timeline

import io.github.trufelek124.weddingplanner.data.Entry
import io.github.trufelek124.weddingplanner.data.Kind
import io.github.trufelek124.weddingplanner.data.Priority
import io.github.trufelek124.weddingplanner.data.Status
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where things go on the timeline is arithmetic on days; these are its sums. */
class TimelineLayoutTest {

    private fun step(id: String, start: String?, due: String?) = Entry(
        id = id, kind = Kind.PLAN_ITEM, title = "Step $id", notes = "", categoryId = null,
        startDate = start?.let(LocalDate::parse), dueDate = due?.let(LocalDate::parse), status = Status.NOT_STARTED,
        priority = Priority.NORMAL, priorityReason = "NO_DATE", parentId = null, vendorId = null, amount = null,
        paymentKind = null, paidOn = null, assigneeIds = emptyList(), taskCount = 0, version = 0,
    )

    private fun day(text: String) = LocalDate.parse(text)

    @Test
    fun `a step covers its days, both ends included`() {
        assertEquals(Span(day("2026-11-01"), day("2026-12-15")), spanOf(step("a", "2026-11-01", "2026-12-15")))
    }

    @Test
    fun `a step with only an end covers that day, and one without an end is not on the timeline`() {
        assertEquals(Span(day("2026-12-15"), day("2026-12-15")), spanOf(step("a", null, "2026-12-15")))
        assertNull(spanOf(step("a", "2026-11-01", null)))
        assertNull(spanOf(step("a", null, null)))
    }

    @Test
    fun `a start after the end is taken as the end`() {
        assertEquals(Span(day("2026-12-15"), day("2026-12-15")), spanOf(step("a", "2026-12-20", "2026-12-15")))
    }

    @Test
    fun `the frame is whole months, with two weeks of room at both ends`() {
        val frame = Frame.around(listOf(day("2026-11-10"), day("2027-06-19")))

        // 10 Nov less two weeks is in October; 19 Jun and two weeks is in July.
        assertEquals(day("2026-10-01"), frame.first)
        assertEquals(304, frame.days)
        assertEquals(day("2027-07-31"), frame.dateOf(frame.days - 1))
    }

    @Test
    fun `room that reaches into the month before or after takes that month in`() {
        val frame = Frame.around(listOf(day("2026-11-20"), day("2026-11-25")))

        assertEquals(day("2026-11-01"), frame.first)
        assertEquals(61, frame.days)
    }

    @Test
    fun `days are counted from the frame's first day, and back`() {
        val frame = Frame.around(listOf(day("2026-11-10"), day("2027-06-19")))

        assertEquals(0, frame.dayOf(day("2026-10-01")))
        assertEquals(48, frame.dayOf(day("2026-11-18")))
        assertEquals(261, frame.dayOf(day("2027-06-19")))
        assertEquals(day("2026-11-18"), frame.dateOf(48))
        // What lies outside is the nearest end.
        assertEquals(day("2026-10-01"), frame.dateOf(-5))
        assertEquals(day("2027-07-31"), frame.dateOf(10_000))
    }

    @Test
    fun `the months are named, and placed by the days before them`() {
        val frame = Frame.around(listOf(day("2026-11-10"), day("2027-02-10")))

        assertEquals(listOf("Oct", "Nov", "Dec", "Jan", "Feb"), frame.months.map { it.label })
        assertEquals(listOf(0, 31, 61, 92, 123), frame.months.map { it.day })
        assertEquals(listOf(31, 30, 31, 31, 28), frame.months.map { it.days })
    }

    @Test
    fun `a leap year's February has its day`() {
        val frame = Frame.around(listOf(day("2028-02-10"), day("2028-03-01")))

        assertEquals(listOf("Jan", "Feb", "Mar"), frame.months.map { it.label })
        assertEquals(listOf(31, 29, 31), frame.months.map { it.days })
    }

    @Test
    fun `the timeline places each dated step and counts those without dates`() {
        val timeline = Timeline(
            items = listOf(step("a", "2026-11-01", "2026-12-15"), step("b", null, null), step("c", null, "2027-01-31"), step("d", "2027-03-01", null)),
            today = day("2026-11-18"),
            weddingDate = day("2027-06-19"),
        )

        assertEquals(listOf("a", "c"), timeline.bars.map { it.entry.id })
        assertEquals(2, timeline.withoutDates)
        assertEquals(day("2026-10-01"), timeline.frame.first)
        // 1 Nov is the 32nd day of the frame; the step lasts 45 days.
        assertEquals(31, timeline.bars[0].startDay)
        assertEquals(45, timeline.bars[0].days)
        assertEquals(1, timeline.bars[1].days)
        assertEquals(48, timeline.todayDay)
        assertEquals(261, timeline.weddingDay)
    }

    @Test
    fun `today and the wedding day are on the timeline even when no step reaches them`() {
        val timeline = Timeline(items = listOf(step("a", "2026-01-10", "2026-01-20")), today = day("2026-11-18"), weddingDate = day("2027-06-19"))

        assertEquals(day("2025-12-01"), timeline.frame.first)
        assertEquals(day("2027-07-31"), timeline.frame.dateOf(timeline.frame.days - 1))
    }

    @Test
    fun `without a wedding day and without steps there is still today`() {
        val timeline = Timeline(items = emptyList(), today = day("2026-11-18"), weddingDate = null)

        assertEquals(emptyList<Bar>(), timeline.bars)
        assertNull(timeline.weddingDay)
        assertEquals(day("2026-11-01"), timeline.frame.first)
        assertEquals(17, timeline.todayDay)
    }
}
