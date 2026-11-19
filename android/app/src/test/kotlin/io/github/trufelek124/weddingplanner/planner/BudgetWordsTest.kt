package io.github.trufelek124.weddingplanner.planner

import io.github.trufelek124.weddingplanner.data.Budget
import io.github.trufelek124.weddingplanner.data.BudgetLine
import io.github.trufelek124.weddingplanner.data.BudgetRest
import io.github.trufelek124.weddingplanner.data.Planner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The budget reads on the phone as it does on the web's budget page. Amounts are in grosze, the sign after a space that does not break. */
@RunWith(RobolectricTestRunner::class)
class BudgetWordsTest {

    private val pln = "PLN"
    private fun zl(amount: String) = "$amount\u00a0zł"

    private fun figures(budget: Budget, payments: Int? = 6) = figuresOf(budget, payments, pln).associateBy { it.name }

    private val inUse = Budget(total = 6000000, planned = 2500000, committed = 2700000, paid = 500000)

    @Test
    fun `the four figures with a total`() {
        val shown = figures(inUse)

        assertEquals(zl("60,000.00"), shown.getValue(FigureName.BUDGET).amount)
        assertEquals("${zl("33,000.00")} not committed yet", shown.getValue(FigureName.BUDGET).note)
        assertEquals(zl("25,000.00"), shown.getValue(FigureName.PLANNED).amount)
        assertEquals("${zl("35,000.00")} of the budget not planned", shown.getValue(FigureName.PLANNED).note)
        assertEquals(zl("27,000.00"), shown.getValue(FigureName.COMMITTED).amount)
        assertEquals("6 payments", shown.getValue(FigureName.COMMITTED).note)
        assertEquals(zl("5,000.00"), shown.getValue(FigureName.PAID).amount)
        assertEquals("${zl("22,000.00")} still to pay", shown.getValue(FigureName.PAID).note)
        assertTrue(shown.values.none { it.over })
    }

    @Test
    fun `over the budget and a plan beyond it are said in words and marked`() {
        val shown = figures(inUse.copy(total = 2000000))

        assertEquals("Over by ${zl("7,000.00")}", shown.getValue(FigureName.BUDGET).note)
        assertTrue(shown.getValue(FigureName.BUDGET).over)
        assertEquals("${zl("5,000.00")} more than the budget", shown.getValue(FigureName.PLANNED).note)
        assertTrue(shown.getValue(FigureName.PLANNED).over)
    }

    @Test
    fun `without a total nothing is said about it, and nothing is said where the plan is the budget`() {
        val without = figures(inUse.copy(total = null))
        assertEquals("Not set", without.getValue(FigureName.BUDGET).amount)
        assertNull(without.getValue(FigureName.BUDGET).note)
        assertNull(without.getValue(FigureName.PLANNED).note)

        assertNull(figures(inUse.copy(total = 2500000)).getValue(FigureName.PLANNED).note)
        assertNull(figures(inUse.copy(planned = 0)).getValue(FigureName.PLANNED).note)
        assertEquals("Over by ${zl("0.01")}", figures(inUse.copy(total = 2699999)).getValue(FigureName.BUDGET).note)
    }

    @Test
    fun `payments are counted, and said when there are none or the copy cannot tell`() {
        assertEquals("No payments yet", figures(inUse, payments = 0).getValue(FigureName.COMMITTED).note)
        assertEquals("1 payment", figures(inUse, payments = 1).getValue(FigureName.COMMITTED).note)
        assertNull(figures(inUse, payments = null).getValue(FigureName.COMMITTED).note)
        assertEquals("Nothing left to pay", figures(inUse.copy(paid = 2700000)).getValue(FigureName.PAID).note)
    }

    @Test
    fun `a figure is one statement for a screen reader`() {
        val shown = figures(inUse)

        assertEquals("Budget: ${zl("60,000.00")}. ${zl("33,000.00")} not committed yet", shown.getValue(FigureName.BUDGET).inWords("Budget"))
        assertEquals("Budget: Not set", figures(inUse.copy(total = null)).getValue(FigureName.BUDGET).inWords("Budget"))
    }

    @Test
    fun `a bar is told in words, within its limit and beyond it`() {
        assertEquals(
            "Venue: ${zl("5,000.00")} paid, ${zl("15,000.00")} still to pay, of ${zl("20,000.00")} planned",
            meterSentence("Venue", 2000000, 2000000, 500000, "planned", pln),
        )
        assertEquals(
            "Flowers: ${zl("0.00")} paid, ${zl("6,200.00")} still to pay: ${zl("1,200.00")} more than the ${zl("5,000.00")} planned",
            meterSentence("Flowers", 500000, 620000, 0, "planned", pln),
        )
        assertEquals("No category: ${zl("0.00")} paid, ${zl("800.00")} still to pay", meterSentence("No category", null, 80000, 0, "planned", pln))
        // Home's bar says the same thing with the same words.
        assertEquals(
            "The whole budget: ${zl("5,000.00")} paid, ${zl("22,000.00")} still to pay, of ${zl("60,000.00")} budget",
            meterInWords(inUse, pln),
        )
        assertEquals(
            "The whole budget: ${zl("5,000.00")} paid, ${zl("22,000.00")} still to pay: ${zl("2,000.00")} more than the ${zl("25,000.00")} planned",
            meterInWords(inUse.copy(total = null), pln),
        )
    }

    @Test
    fun `where a category stands against its plan`() {
        assertEquals("No amount planned", planState(null, 40000, pln))
        assertEquals("Over the plan by ${zl("1,200.00")}", planState(500000, 620000, pln))
        assertEquals("Exactly as planned", planState(2000000, 2000000, pln))
        assertEquals("${zl("20,000.00")} left", planState(2000000, 0, pln))
        assertEquals("${zl("0.01")} left", planState(100, 99, pln))
    }

    @Test
    fun `a category's figures, its money paid and its payments`() {
        assertEquals("${zl("800.00")} committed", categoryFigures(null, 80000, pln))
        assertEquals("${zl("6,200.00")} of ${zl("5,000.00")}", categoryFigures(500000, 620000, pln))
        assertEquals("${zl("5,000.00")} paid", paidInWords(500000, pln))
        assertEquals("No payments yet", paymentsInWords(0))
        assertEquals("1 payment", paymentsInWords(1))
        assertEquals("3 payments", paymentsInWords(3))
    }

    private val venue = BudgetLine("c1", "Venue", planned = 2000000, committed = 2000000, paid = 500000, payments = 3)
    private val music = BudgetLine("c3", "Music", planned = null, committed = 0, paid = 0, payments = 0)
    private val photos = BudgetLine("c4", "Photos", planned = null, committed = 0, paid = 0, payments = 0)

    @Test
    fun `a category gets a line where it has a plan or a payment, and a bar where it has a plan or money`() {
        assertTrue(hasLine(venue))
        assertFalse(hasLine(music))
        assertTrue(hasLine(music.copy(planned = 0)))
        assertTrue(hasLine(music.copy(payments = 1, committed = 100)))
        assertTrue(hasMeter(music.copy(planned = 0)))
        assertFalse(hasMeter(music.copy(payments = 1)))
    }

    @Test
    fun `the categories without a line are named, unless there is no line at all`() {
        assertEquals("Nothing planned or paid yet for: Music, Photos.", quietCategories(listOf(venue, music, photos)))
        assertNull(quietCategories(listOf(venue)))
        assertNull(quietCategories(listOf(music, photos)))
        assertNull(quietCategories(emptyList()))
    }

    @Test
    fun `a category line is one statement for a screen reader`() {
        assertEquals(
            "Venue. ${zl("20,000.00")} of ${zl("20,000.00")}. Exactly as planned. ${zl("5,000.00")} paid. 3 payments",
            categoryInWords(venue, pln),
        )
        assertEquals(
            "No category. ${zl("800.00")} committed. Give these a category in the web app to count them against a plan. ${zl("0.00")} paid. 1 payment",
            restInWords(80000, 0, 1, pln, "Give these a category in the web app to count them against a plan."),
        )
    }

    private fun planner(budget: Budget, lines: List<BudgetLine> = emptyList(), rest: BudgetRest = BudgetRest(0, 0, 0)) = Planner(
        loadedAt = java.time.Instant.EPOCH, weddingDate = null, currency = pln, timeZone = "Europe/Warsaw", daysLeft = null,
        today = java.time.LocalDate.of(2026, 11, 18), budget = budget, entries = emptyList(), categories = emptyMap(), vendors = emptyMap(),
        members = emptyList(), overdue = emptyList(), next = emptyList(), nextPayments = emptyList(), budgetLines = lines, uncategorised = rest,
    )

    @Test
    fun `the categories are known unless the copy has money and no line anywhere`() {
        assertTrue(categoriesKnown(planner(inUse, listOf(venue))))
        assertTrue(categoriesKnown(planner(inUse, rest = BudgetRest(80000, 0, 1))))
        assertTrue(categoriesKnown(planner(Budget(null, 0, 0, 0))))
        assertFalse(categoriesKnown(planner(inUse)))
        assertNull(paymentsOf(planner(inUse)))
        assertEquals(4, paymentsOf(planner(inUse, listOf(venue), BudgetRest(80000, 0, 1))))
    }

    @Test
    fun `what More says of the budget`() {
        assertEquals("Not set yet.", budgetSummary(Budget(null, 0, 0, 0), pln))
        assertEquals(budgetInWords(inUse, pln), budgetSummary(inUse, pln))
        assertTrue(budgetEmpty(Budget(null, 0, 0, 0)))
        assertFalse(budgetEmpty(Budget(null, 0, 1, 0)))
    }
}
