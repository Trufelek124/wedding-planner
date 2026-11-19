package io.github.trufelek124.weddingplanner.planner

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import io.github.trufelek124.weddingplanner.data.StandIn
import io.github.trufelek124.weddingplanner.data.planner
import io.github.trufelek124.weddingplanner.data.plannerUnreachable
import io.github.trufelek124.weddingplanner.ui.PHONE
import io.github.trufelek124.weddingplanner.ui.PHONE_AT_NIGHT
import io.github.trufelek124.weddingplanner.ui.picture
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog

/**
 * More, and the budget it leads to: the same facts and words as the web's budget page, kept on the
 * phone and shown without a connection. The wedding and its money are made up.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class BudgetScreenTest : SignedInPhoneTest() {

    /** A zloty amount as the screen writes it: the sign after a space that does not break. */
    private fun zl(amount: String) = "$amount\u00a0zł"

    private val venue = StandIn.Line("c1", "Venue", planned = 2000000, committed = 2000000, paid = 500000, payments = 3)
    private val flowers = StandIn.Line("c2", "Flowers and decor", planned = 500000, committed = 620000, paid = 0, payments = 2)
    private val musicLine = StandIn.Line("c3", "Music")
    private val lines = listOf(venue, flowers, musicLine)
    private val noCategory = StandIn.Rest(committed = 80000, paid = 0, payments = 1)

    private val categories = listOf(
        StandIn.category("c1", "Venue"), StandIn.category("c2", "Flowers and decor"), StandIn.category("c3", "Music"),
    )

    /** 60,000 in all; the venue exactly as planned, the flowers over their plan, one payment without a category. */
    private fun aBudgetInUse(total: Long? = 6000000, lines: List<StandIn.Line> = this.lines, rest: StandIn.Rest = noCategory) {
        server.planner(
            home = StandIn.home(budget = StandIn.glanceOf(total, lines, rest)),
            categories = categories,
            budget = StandIn.wholeBudget(total, lines, rest),
        )
    }

    @Before
    fun theirMoney() {
        aBudgetInUse()
    }

    private val sayingOpenTheBudget = SemanticsMatcher("leads to the budget") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == "Open the budget"
    }

    private fun openBudgetFromMore() {
        goTo("More")
        compose.onNode(hasText("Budget") and hasClickAction()).performClick()
        waitForTheBudget()
    }

    private fun waitForTheBudget() {
        // The top bar's arrow is there once the budget is: Home and More have none.
        compose.waitUntil(WAIT) { compose.onAllNodes(hasContentDescription("Back")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun pressBackButton() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    // ---------------------------------------------------------------- More

    @Test
    fun `More is a fourth place in the bar, and its one row says what the budget comes to`() {
        open()

        goTo("More")

        compose.onNode(hasText("More") and isSelectable()).assertIsSelected()
        compose.onNode(hasText("More") and isHeading()).assertIsDisplayed()
        compose.onNode(
            line("Budget", "${zl("5,000.00")} paid, ${zl("22,000.00")} still to pay — ${zl("33,000.00")} of the ${zl("60,000.00")} budget not committed."),
        ).assertIsDisplayed()
        compose.onNodeWithText("Loaded on 18 Nov 2026 at 14:05").assertIsDisplayed()
        compose.onNodeWithText("Signed in as Anna").assertIsDisplayed()
        // One row only: the places that come later come with their own.
        assertTrue(compose.onAllNodes(hasClickAction() and hasText("Budget")).fetchSemanticsNodes().size == 1)
        compose.picture("more")
    }

    @Test
    fun `More says that no budget is set while there is no money at all`() {
        server.planner()
        open()

        goTo("More")

        compose.onNode(line("Budget", "Not set yet.")).assertIsDisplayed()
    }

    @Test
    fun `Back from More leads to Home`() {
        open()
        goTo("More")

        pressBackButton()

        waitFor("213 days to go")
        compose.onNode(hasText("Home") and isSelectable()).assertIsSelected()
    }

    // ---------------------------------------------------------------- the figures

    @Test
    fun `the budget shows its four figures, each with its note`() {
        open()

        openBudgetFromMore()

        compose.onNode(hasText("More") and isSelectable()).assertIsSelected()
        compose.onNode(line("Budget", zl("60,000.00"), "${zl("33,000.00")} not committed yet") and hasContentDescription("Budget: ${zl("60,000.00")}. ${zl("33,000.00")} not committed yet")).assertIsDisplayed()
        compose.onNode(line("Planned", zl("25,000.00"), "${zl("35,000.00")} of the budget not planned")).assertIsDisplayed()
        compose.onNode(line("Committed", zl("27,000.00"), "6 payments")).assertIsDisplayed()
        compose.onNode(line("Paid", zl("5,000.00"), "${zl("22,000.00")} still to pay")).assertIsDisplayed()
        // The bar says the same in words to whoever does not see it.
        compose.onNode(
            hasContentDescription("The whole budget: ${zl("5,000.00")} paid, ${zl("22,000.00")} still to pay, of ${zl("60,000.00")} budget"),
        ).assertExists()
        compose.picture("budget")
    }

    @Test
    fun `the headings of the page are headings`() {
        open()

        openBudgetFromMore()

        compose.onNode(hasText("Budget") and isHeading()).assertIsDisplayed()
        compose.onNode(hasText("By category") and isHeading()).assertIsDisplayed()
    }

    @Test
    fun `the categories are told one by one, over a plan in words, and a category with nothing is only named`() {
        open()
        openBudgetFromMore()

        compose.onNode(line("Venue", "${zl("20,000.00")} of ${zl("20,000.00")}", "Exactly as planned", "${zl("5,000.00")} paid, 3 payments")).assertIsDisplayed()
        compose.onNode(hasContentDescription("Venue: ${zl("5,000.00")} paid, ${zl("15,000.00")} still to pay, of ${zl("20,000.00")} planned")).assertExists()
        compose.onNode(
            line("Flowers and decor", "${zl("6,200.00")} of ${zl("5,000.00")}", "Over the plan by ${zl("1,200.00")}", "${zl("0.00")} paid, 2 payments"),
        ).assertExists()
        compose.onNode(
            hasContentDescription("Flowers and decor: ${zl("0.00")} paid, ${zl("6,200.00")} still to pay: ${zl("1,200.00")} more than the ${zl("5,000.00")} planned"),
        ).assertExists()
        scrollTo(hasText("Nothing planned or paid yet for: Music."))
        compose.onNodeWithText("Nothing planned or paid yet for: Music.").assertIsDisplayed()
        compose.picture("budget-lower")
        // A category with nothing in it gets no line of its own.
        compose.onNode(line("Music", "No amount planned")).assertDoesNotExist()
    }

    @Test
    fun `a category with a plan and no payment, and one with payments and no plan, say so`() {
        aBudgetInUse(
            lines = listOf(
                StandIn.Line("c1", "Venue", planned = 2000000),
                StandIn.Line("c2", "Flowers and decor", committed = 40000, paid = 40000, payments = 1),
            ),
            rest = StandIn.Rest(),
        )
        open()
        openBudgetFromMore()

        compose.onNode(line("Venue", "${zl("0.00")} of ${zl("20,000.00")}", "${zl("20,000.00")} left", "${zl("0.00")} paid, No payments yet")).assertExists()
        compose.onNode(line("Flowers and decor", "${zl("400.00")} committed", "No amount planned", "${zl("400.00")} paid, 1 payment")).assertExists()
        compose.onNode(line("Committed", zl("400.00"), "1 payment")).assertIsDisplayed()
        compose.onNode(line("Paid", zl("400.00"), "Nothing left to pay")).assertIsDisplayed()
    }

    @Test
    fun `over the budget, and a plan beyond it, are said in words`() {
        aBudgetInUse(total = 2000000)
        open()

        openBudgetFromMore()

        compose.onNode(line("Budget", zl("20,000.00"), "Over by ${zl("7,000.00")}")).assertIsDisplayed()
        compose.onNode(line("Planned", zl("25,000.00"), "${zl("5,000.00")} more than the budget")).assertIsDisplayed()
        compose.onNode(
            hasContentDescription("The whole budget: ${zl("5,000.00")} paid, ${zl("22,000.00")} still to pay: ${zl("7,000.00")} more than the ${zl("20,000.00")} budget"),
        ).assertExists()
    }

    @Test
    fun `without a total the plan is the measure`() {
        aBudgetInUse(total = null)
        open()

        openBudgetFromMore()

        compose.onNode(line("Budget", "Not set")).assertIsDisplayed()
        compose.onNode(line("Planned", zl("25,000.00"))).assertIsDisplayed()
        compose.onNode(
            hasContentDescription("The whole budget: ${zl("5,000.00")} paid, ${zl("22,000.00")} still to pay: ${zl("2,000.00")} more than the ${zl("25,000.00")} planned"),
        ).assertExists()
    }

    @Test
    fun `payments under no category are shown, with what to do to count them against a plan`() {
        aBudgetInUse(lines = emptyList(), rest = StandIn.Rest(committed = 80000, paid = 20000, payments = 2))
        open()

        openBudgetFromMore()

        compose.onNode(
            line("No category", "${zl("800.00")} committed", "Give these a category in the web app to count them against a plan.", "${zl("200.00")} paid, 2 payments"),
        ).assertIsDisplayed()
        compose.onNode(hasContentDescription("No category: ${zl("200.00")} paid, ${zl("600.00")} still to pay", substring = true)).assertExists()
        compose.onNode(line("Committed", zl("800.00"), "2 payments")).assertIsDisplayed()
        compose.onNodeWithText("Nothing is planned or paid in any category yet.").assertDoesNotExist()
    }

    @Test
    fun `a plan with no payments anywhere says that nothing is paid in any category`() {
        server.planner(
            home = StandIn.home(budget = StandIn.budget(total = 6000000)),
            categories = listOf(StandIn.category("c1", "Venue")),
            budget = StandIn.wholeBudget(total = 6000000, lines = listOf(StandIn.Line("c1", "Venue"))),
        )
        open()

        openBudgetFromMore()

        compose.onNode(line("Committed", zl("0.00"), "No payments yet")).assertIsDisplayed()
        compose.onNodeWithText("Nothing is planned or paid in any category yet.").assertIsDisplayed()
        compose.onNode(hasContentDescription("The whole budget", substring = true)).assertDoesNotExist()
    }

    @Test
    fun `with no money at all it says the budget is set in the web app, and offers nothing to press`() {
        server.planner()
        open()

        openBudgetFromMore()

        compose.onNode(hasText("No budget yet.", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("Say what you mean to spend in the web app", substring = true).assertIsDisplayed()
        compose.onNodeWithText("By category").assertDoesNotExist()
        compose.onNodeWithText("Planned").assertDoesNotExist()
        compose.onNode(hasContentDescription("The whole budget", substring = true)).assertDoesNotExist()
        assertTrue(compose.onAllNodes(hasClickAction() and hasText("Plan the budget", substring = true)).fetchSemanticsNodes().isEmpty())
        compose.picture("budget-empty")
    }

    @Test
    fun `a copy made before the categories were kept shows the totals and leaves the categories out`() {
        // What the previous version of the app held: Home's totals, and nothing of the categories.
        server.planner(
            home = StandIn.home(budget = StandIn.budget(total = 6000000, planned = 2500000, committed = 2700000, paid = 500000)),
            categories = categories,
        )
        open()

        openBudgetFromMore()

        compose.onNode(line("Budget", zl("60,000.00"))).assertIsDisplayed()
        compose.onNode(line("Committed", zl("27,000.00"))).assertIsDisplayed()
        compose.onNodeWithText("No payments yet").assertDoesNotExist()
        compose.onNodeWithText("By category").assertDoesNotExist()
        compose.onNodeWithText("Nothing is planned or paid in any category yet.").assertDoesNotExist()
        compose.onNode(hasContentDescription("The whole budget", substring = true)).assertExists()
    }

    // ---------------------------------------------------------------- getting there and back

    @Test
    fun `Home's glance at the money leads to the budget, and Back returns to Home`() {
        open()
        scrollTo(sayingOpenTheBudget)

        compose.onNode(sayingOpenTheBudget).performClick()

        waitForTheBudget()
        compose.onNode(hasText("More") and isSelectable()).assertIsSelected()
        compose.onNode(line("Planned", zl("25,000.00"))).assertIsDisplayed()

        pressBackButton()

        waitFor("213 days to go")
        compose.onNode(hasText("Home") and isSelectable()).assertIsSelected()
    }

    @Test
    fun `the arrow at the top leads back to where the person came from`() {
        open()
        openBudgetFromMore()

        compose.onNodeWithContentDescription("Back").performClick()

        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("More") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Budget") and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun `pressing More while on the budget leads to the list`() {
        open()
        openBudgetFromMore()

        compose.onNode(hasText("More") and isSelectable()).performClick()

        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("More") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("By category").assertDoesNotExist()
        compose.onNode(hasText("Budget") and hasClickAction()).assertIsDisplayed()
    }

    // ---------------------------------------------------------------- loading again, and without a connection

    @Test
    fun `pulling the budget down loads it again`() {
        open()
        openBudgetFromMore()
        aBudgetInUse(total = 7000000)

        compose.onNode(hasScrollToNodeAction()).performTouchInput { swipeDown() }

        waitFor(zl("70,000.00"))
        compose.onNode(line("Budget", zl("70,000.00"), "${zl("43,000.00")} not committed yet")).assertIsDisplayed()
    }

    @Test
    fun `the button at the top loads the budget again too`() {
        open()
        openBudgetFromMore()
        aBudgetInUse(total = 7000000)

        compose.onNodeWithContentDescription("Load again").performClick()

        waitFor(zl("70,000.00"))
    }

    @Test
    fun `without a connection the budget is shown from the last load, with from when it is`() {
        aPhoneThatLoadedEarlier()
        server.plannerUnreachable()
        phone.clock.now = phone.clock.now.plusSeconds(3 * 3600)
        open()
        waitFor("No connection. This is what was loaded on 18 Nov 2026 at 14:05.")

        openBudgetFromMore()

        compose.onNodeWithText("No connection. This is what was loaded on 18 Nov 2026 at 14:05.").assertIsDisplayed()
        compose.onNode(line("Committed", zl("27,000.00"), "6 payments")).assertIsDisplayed()
        compose.onNode(line("Venue", "Exactly as planned")).assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    // ---------------------------------------------------------------- how it looks

    @Test
    @Config(qualifiers = PHONE_AT_NIGHT)
    fun `the budget reads in the dark as well`() {
        open()
        openBudgetFromMore()

        compose.onNode(line("Budget", zl("60,000.00"))).assertIsDisplayed()
        compose.picture("budget-at-night")
    }

    @Test
    fun `with the largest text nothing is cut off and the bar still has four places`() {
        open(fontScale = 2f)

        goTo("More")
        compose.picture("more-large-text")
        compose.onNode(hasText("Budget") and hasClickAction()).performClick()
        waitForTheBudget()

        compose.onNode(line("Budget", zl("60,000.00"))).assertIsDisplayed()
        compose.picture("budget-big-letters")
        scrollTo(hasText("Nothing planned or paid yet for: Music."))
        compose.onNodeWithText("Nothing planned or paid yet for: Music.").assertIsDisplayed()
    }

    // ---------------------------------------------------------------- what is kept, and what is logged

    @Test
    fun `nothing of the budget or the people is written to the log`() {
        ShadowLog.clear()
        open()
        openBudgetFromMore()
        server.plannerUnreachable()
        compose.onNodeWithContentDescription("Load again").performClick()
        waitFor("No connection. This is what was loaded on 18 Nov 2026 at 14:05.")

        val written = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable?.stackTraceToString().orEmpty()}" }
        for (theirs in listOf("Anna", "Marek", "Venue", "Flowers", "Music", "60,000", "6000000", "2700000", "zł", "access-1", "example.com")) {
            assertFalse("the log holds \"$theirs\"", written.contains(theirs, ignoreCase = true))
        }
    }

    @Test
    fun `signing out leaves no category of the budget on the phone`() {
        open()
        assertTrue(phone.everythingKept().contains("Flowers and decor"))
        goTo("More")
        scrollTo(hasText("Sign out"))

        compose.onNodeWithText("Sign out").performClick()

        waitFor("Sign in to see your plan.")
        val kept = phone.everythingKept()
        for (theirs in listOf("Flowers and decor", "Venue", "Music")) {
            assertFalse("the phone still keeps \"$theirs\"", kept.contains(theirs))
        }
    }
}
