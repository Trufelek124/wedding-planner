package io.github.trufelek124.weddingplanner.planner

import io.github.trufelek124.weddingplanner.data.Budget
import io.github.trufelek124.weddingplanner.data.Entry
import io.github.trufelek124.weddingplanner.data.Kind
import io.github.trufelek124.weddingplanner.data.Priority
import io.github.trufelek124.weddingplanner.data.Status
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The planner's facts read on the phone as they do in the web app. */
@RunWith(RobolectricTestRunner::class)
class WordsTest {

    private fun entry(
        kind: Kind = Kind.TASK,
        status: Status = Status.NOT_STARTED,
        startDate: LocalDate? = null,
        dueDate: LocalDate? = null,
        priority: Priority = Priority.NORMAL,
        priorityReason: String = "NO_DATE",
        amount: Long? = null,
        paymentKind: String? = null,
        paidOn: LocalDate? = null,
    ) = Entry(
        id = "e1", kind = kind, title = "Something", notes = "", categoryId = null, startDate = startDate, dueDate = dueDate,
        status = status, priority = priority, priorityReason = priorityReason, parentId = null, vendorId = null, amount = amount,
        paymentKind = paymentKind, paidOn = paidOn, assigneeIds = emptyList(), taskCount = 0, version = 0,
    )

    @Test
    fun `dates are written the short and the long way`() {
        assertEquals("19 Jun 2027", shortDate(LocalDate.of(2027, 6, 19)))
        assertEquals("1 Dec 2026", shortDate(LocalDate.of(2026, 12, 1)))
        assertEquals("Saturday 19 June 2027", longDate(LocalDate.of(2027, 6, 19)))
        assertEquals("18 Nov 2026 at 14:05", moment(Instant.parse("2026-11-18T13:05:00Z"), ZoneId.of("Europe/Warsaw")))
        assertEquals("1 Jul 2027 at 00:30", moment(Instant.parse("2027-06-30T22:30:00Z"), ZoneId.of("Europe/Warsaw")))
    }

    @Test
    fun `the countdown reads as a person would say it`() {
        assertEquals("214 days to go", countdown(214))
        assertEquals("2 days to go", countdown(2))
        assertEquals("Tomorrow", countdown(1))
        assertEquals("Today is the day", countdown(0))
        assertEquals("Yesterday", countdown(-1))
        assertEquals("3 days ago", countdown(-3))
    }

    @Test
    fun `when an entry happens`() {
        val due = LocalDate.of(2027, 1, 31)
        assertEquals("due 31 Jan 2027", whenInWords(entry(dueDate = due)))
        assertEquals("by 31 Jan 2027", whenInWords(entry(kind = Kind.PLAN_ITEM, dueDate = due)))
        assertEquals("1 Dec 2026 – 31 Jan 2027", whenInWords(entry(kind = Kind.PLAN_ITEM, startDate = LocalDate.of(2026, 12, 1), dueDate = due)))
        assertNull(whenInWords(entry()))
    }

    @Test
    fun `the level says why, in the words of the entry's kind`() {
        assertEquals("Critical — overdue", priorityInWords(entry(priority = Priority.CRITICAL, priorityReason = "OVERDUE")))
        assertEquals("High — due within 3 days", priorityInWords(entry(priority = Priority.HIGH, priorityReason = "DUE_WITHIN_3_DAYS")))
        assertEquals("High — ends within 3 days", priorityInWords(entry(kind = Kind.PLAN_ITEM, priority = Priority.HIGH, priorityReason = "DUE_WITHIN_3_DAYS")))
        assertEquals("High — a payment due within 7 days", priorityInWords(entry(priority = Priority.HIGH, priorityReason = "PAYMENT_DUE_WITHIN_7_DAYS")))
        assertEquals("Low — set by hand", priorityInWords(entry(priority = Priority.LOW, priorityReason = "SET_BY_HAND")))
        assertEquals("Normal — no date", priorityInWords(entry()))
        assertEquals("Low — needs no more attention", priorityInWords(entry(priority = Priority.LOW, priorityReason = "FINISHED")))
    }

    @Test
    fun `a reason this version does not know leaves the level alone`() {
        assertEquals("High", priorityInWords(entry(priority = Priority.HIGH, priorityReason = "ADDED_LATER")))
    }

    @Test
    fun `a payment says what it costs, which part it is and whether it is paid`() {
        assertEquals("2,500.00\u00a0zł, deposit, to pay", moneyInWords(entry(amount = 250_000, paymentKind = "DEPOSIT"), "PLN"))
        assertEquals("400.00\u00a0zł, instalment, to pay", moneyInWords(entry(amount = 40_000, paymentKind = "INSTALMENT", status = Status.IN_PROGRESS), "PLN"))
        assertEquals(
            "4,000.00\u00a0zł, final payment, paid 10 Nov 2026",
            moneyInWords(entry(amount = 400_000, paymentKind = "FINAL", status = Status.DONE, paidOn = LocalDate.of(2026, 11, 10)), "PLN"),
        )
        assertEquals("300.00\u00a0zł, paid", moneyInWords(entry(amount = 30_000, status = Status.DONE), "PLN"))
        assertEquals("300.00\u00a0zł, dropped, not counted", moneyInWords(entry(amount = 30_000, status = Status.DROPPED), "PLN"))
        assertEquals("300.00\u00a0€, payment, to pay", moneyInWords(entry(amount = 30_000, paymentKind = "ADDED_LATER"), "EUR"))
        assertNull(moneyInWords(entry(), "PLN"))
    }

    @Test
    fun `a plan item's tasks are counted, with how many are done`() {
        assertEquals("1 task", tasksInWords(listOf(entry())))
        assertEquals("3 tasks, 1 done", tasksInWords(listOf(entry(), entry(status = Status.DONE), entry(status = Status.DROPPED))))
    }

    @Test
    fun `the budget in one sentence - within the limit, over it, and without one`() {
        assertEquals(
            "300.00\u00a0zł paid, 6,500.00\u00a0zł still to pay — 53,200.00\u00a0zł of the 60,000.00\u00a0zł budget not committed.",
            budgetInWords(Budget(total = 6_000_000, planned = 5_000_000, committed = 680_000, paid = 30_000), "PLN"),
        )
        assertEquals(
            "0.00\u00a0zł paid, 7,000.00\u00a0zł still to pay — 2,000.00\u00a0zł more than the 5,000.00\u00a0zł budget.",
            budgetInWords(Budget(total = 500_000, planned = 0, committed = 700_000, paid = 0), "PLN"),
        )
        // Without a limit of their own, what they planned for the categories is what counts.
        assertEquals(
            "0.00\u00a0zł paid, 1,000.00\u00a0zł still to pay — 3,000.00\u00a0zł of the 4,000.00\u00a0zł planned not committed.",
            budgetInWords(Budget(total = null, planned = 400_000, committed = 100_000, paid = 0), "PLN"),
        )
        assertEquals("100.00\u00a0zł paid, 0.00\u00a0zł still to pay.", budgetInWords(Budget(total = null, planned = 0, committed = 10_000, paid = 10_000), "PLN"))
    }

    @Test
    fun `the bar of money says the same to a screen reader`() {
        assertEquals(
            "The whole budget: 300.00\u00a0zł paid, 6,500.00\u00a0zł still to pay, of 60,000.00\u00a0zł budget",
            meterInWords(Budget(total = 6_000_000, planned = 5_000_000, committed = 680_000, paid = 30_000), "PLN"),
        )
        assertEquals(
            "The whole budget: 0.00\u00a0zł paid, 7,000.00\u00a0zł still to pay: 2,000.00\u00a0zł more than the 5,000.00\u00a0zł budget",
            meterInWords(Budget(total = 500_000, planned = 0, committed = 700_000, paid = 0), "PLN"),
        )
    }

    @Test
    fun `there is money to talk about once anything is planned or to be paid`() {
        assertFalse(hasMoney(Budget(total = null, planned = 0, committed = 0, paid = 0)))
        assertTrue(hasMoney(Budget(total = 100, planned = 0, committed = 0, paid = 0)))
        assertTrue(hasMoney(Budget(total = null, planned = 100, committed = 0, paid = 0)))
        assertTrue(hasMoney(Budget(total = null, planned = 0, committed = 100, paid = 0)))
    }
}
