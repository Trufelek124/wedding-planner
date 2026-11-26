package io.github.trufelek124.weddingplanner.planner.timeline

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import io.github.trufelek124.weddingplanner.data.StandIn
import io.github.trufelek124.weddingplanner.data.planner
import io.github.trufelek124.weddingplanner.data.plannerUnreachable
import io.github.trufelek124.weddingplanner.planner.SignedInPhoneTest
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

/**
 * The plan as a timeline on the phone: to be scrolled and read, never changed. The wedding is made
 * up; it is 18 Nov 2026 where it takes place.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class TimelineScreenTest : SignedInPhoneTest() {

    private val venue = StandIn.entry(
        "p1", "Book the venue", kind = "PLAN_ITEM", startDate = "2026-11-01", dueDate = "2026-12-15", status = "IN_PROGRESS",
        priority = "HIGH", priorityReason = "DUE_WITHIN_60_DAYS",
    )
    private val music = StandIn.entry("p2", "Choose the music", kind = "PLAN_ITEM")
    private val saveTheDates = StandIn.entry(
        "p3", "Send the save-the-dates", kind = "PLAN_ITEM", dueDate = "2026-11-01", status = "DONE", priority = "LOW", priorityReason = "FINISHED",
    )
    private val invitations = StandIn.entry("p4", "Send the invitations", kind = "PLAN_ITEM", startDate = "2027-03-01", dueDate = "2027-04-15")
    private val menu = StandIn.entry("p5", "Agree on the menu", kind = "PLAN_ITEM", dueDate = "2027-05-20")
    private val deposit = StandIn.entry("t1", "Pay the deposit", dueDate = "2026-11-10", parentId = "p1")

    @Before
    fun theirPlan() {
        server.planner(entries = listOf(saveTheDates, deposit, venue, invitations, menu, music))
    }

    private fun openTheTimeline(fontScale: Float = 1f) {
        open(fontScale)
        goTo("Plan")
        compose.onNodeWithText("Timeline").performClick()
        compose.waitForIdle()
    }

    private fun showing(): String =
        compose.onAllNodes(hasText("Showing", substring = true)).fetchSemanticsNodes().single().config
            .first { it.key.name == "Text" }.value.toString()

    @Test
    fun `the dated steps are bars under their names, with their dates and state in words`() {
        openTheTimeline()

        compose.onNodeWithText("Timeline").assertIsSelected()
        compose.onNode(line("Book the venue", "1 Nov 2026 – 15 Dec 2026 · In progress")).assertIsDisplayed()
        compose.onNode(line("Send the invitations", "1 Mar 2027 – 15 Apr 2027 · Not started")).assertIsDisplayed()
        compose.onNode(line("Agree on the menu", "by 20 May 2027 · Not started")).assertIsDisplayed()
        // Tasks are not steps of the plan, and a step without dates has no place on a line of days.
        compose.onNodeWithText("Pay the deposit").assertDoesNotExist()
        compose.onNodeWithText("Choose the music").assertDoesNotExist()
        compose.onNodeWithText("1 step without dates is only in the list.").assertIsDisplayed()
        compose.picture("timeline")
    }

    @Test
    fun `today and the wedding day are marked, and said in words`() {
        openTheTimeline()

        compose.onNodeWithContentDescription("Today is 18 Nov 2026. The wedding day is 19 Jun 2027.").assertIsDisplayed()
        // It opens around today: a little of what was, more of what comes.
        compose.onNodeWithText("Showing 21 Oct 2026 – 8 Feb 2027").assertIsDisplayed()
    }

    @Test
    fun `a wedding without a day marks only today`() {
        server.planner(
            entries = listOf(venue), wedding = StandIn.wedding(date = null), home = StandIn.home(daysLeft = null, weddingDate = null),
        )
        openTheTimeline()

        compose.onNodeWithContentDescription("Today is 18 Nov 2026.").assertIsDisplayed()
        compose.picture("timeline-without-a-wedding-day")
    }

    @Test
    fun `what is done comes onto the timeline when everything is asked for`() {
        openTheTimeline()
        compose.onNodeWithText("Send the save-the-dates").assertDoesNotExist()

        compose.onNodeWithText("Everything").performClick()

        compose.onNode(line("Send the save-the-dates", "by 1 Nov 2026 · Done")).assertIsDisplayed()
        compose.onNodeWithText("Timeline").assertIsSelected()
        compose.picture("timeline-everything")
    }

    @Test
    fun `the buttons move through time, for whoever cannot drag`() {
        openTheTimeline()
        compose.onNodeWithText("Earlier").assertIsEnabled()

        compose.onNodeWithText("Later").performClick()
        compose.onNodeWithText("Showing 11 Jan 2027 – 1 May 2027").assertIsDisplayed()
        compose.picture("timeline-later")

        compose.onNodeWithText("Later").performClick()
        compose.onNodeWithText("Later").performClick()
        // The end of the frame: the month after the wedding.
        compose.onNodeWithText("Showing 13 Apr 2027 – 31 Jul 2027").assertIsDisplayed()
        compose.onNodeWithText("Later").assertIsNotEnabled()
        compose.picture("timeline-around-the-wedding-day")

        compose.onNodeWithText("To today").performClick()
        compose.onNodeWithText("Showing 21 Oct 2026 – 8 Feb 2027").assertIsDisplayed()

        compose.onNodeWithText("Earlier").performClick()
        compose.onNodeWithText("Showing 1 Oct 2026 – 18 Jan 2027").assertIsDisplayed()
        compose.onNodeWithText("Earlier").assertIsNotEnabled()
    }

    @Test
    fun `dragging sideways moves through time and changes nothing in the plan`() {
        openTheTimeline()
        val before = showing()
        val callsBefore = server.calls.size

        compose.onNode(line("Book the venue", "1 Nov 2026 – 15 Dec 2026 · In progress")).performTouchInput { swipeLeft() }
        compose.waitForIdle()

        val after = showing()
        assertTrue("dragging did not move the timeline: $after", after != before)
        compose.onNode(line("Book the venue", "1 Nov 2026 – 15 Dec 2026 · In progress")).assertIsDisplayed()

        compose.onNode(line("Agree on the menu", "by 20 May 2027 · Not started")).performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertTrue(showing() != after)
        // Nothing was sent to the server, and a step offers nothing to press.
        assertTrue(server.calls.size == callsBefore)
        val step = compose.onNode(line("Agree on the menu", "by 20 May 2027 · Not started")).fetchSemanticsNode()
        assertFalse(step.config.contains(SemanticsActions.OnClick))
        assertFalse(step.config.contains(SemanticsActions.OnLongClick))
    }

    @Test
    fun `a screen reader can scroll the steps up and down, and time sideways, each on its own`() {
        openTheTimeline()

        val steps = compose.onNode(hasScrollToNodeAction()).fetchSemanticsNode()
        assertTrue(steps.config.contains(SemanticsProperties.VerticalScrollAxisRange))
        assertFalse(steps.config.contains(SemanticsProperties.HorizontalScrollAxisRange))
        // The node that moves time is the one around the steps; the row of chips scrolls sideways too.
        val time = SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange) and hasAnyDescendant(hasScrollToNodeAction())
        assertTrue(compose.onNode(time).fetchSemanticsNode().config.contains(SemanticsActions.ScrollBy))

        // Scrolling the time by its action, as a screen reader does, moves what is shown.
        val before = showing()
        compose.onNode(time).performSemanticsAction(SemanticsActions.ScrollBy) { it(300f, 0f) }
        compose.waitForIdle()
        assertTrue(showing() != before)
    }

    @Test
    fun `going back to the list keeps what was asked for`() {
        openTheTimeline()
        compose.onNodeWithText("Everything").performClick()

        compose.onNodeWithText("List").performClick()

        compose.onNodeWithText("List").assertIsSelected()
        compose.onNode(line("Send the save-the-dates", "Done", "by 1 Nov 2026")).assertIsDisplayed()
        scrollTo(hasText("Choose the music"))
        compose.onNode(line("Choose the music", "Not started", "Normal — no date")).assertIsDisplayed()
    }

    @Test
    fun `a plan without dates says where dates are given`() {
        server.planner(entries = listOf(music))
        openTheTimeline()

        compose.onNodeWithText("Nothing with dates to show here. Give a step its first and last day in the list, and it comes here.").assertIsDisplayed()
        compose.onNodeWithText("1 step without dates is only in the list.").assertIsDisplayed()
        compose.onNodeWithText("Later").assertDoesNotExist()
    }

    @Test
    fun `without a connection the timeline shows what was loaded last`() {
        aPhoneThatLoadedEarlier()
        server.plannerUnreachable()

        openTheTimeline()

        compose.onNodeWithText("No connection. This is what was loaded on 18 Nov 2026 at 14:05.").assertIsDisplayed()
        compose.onNode(line("Book the venue", "1 Nov 2026 – 15 Dec 2026 · In progress")).assertIsDisplayed()
    }

    @Test
    fun `two hundred steps can be scrolled to the last one`() {
        val steps = (1..200).map { number ->
            val start = java.time.LocalDate.of(2026, 9, 1).plusDays(number * 2L)
            StandIn.entry(
                "s$number", "Step $number", kind = "PLAN_ITEM", startDate = start.toString(), dueDate = start.plusDays(20L + (number % 5) * 12).toString(),
                status = if (number % 3 == 0) "IN_PROGRESS" else "NOT_STARTED",
            )
        }
        server.planner(entries = steps)
        openTheTimeline()

        compose.onNodeWithText("Step 1").assertIsDisplayed()
        compose.picture("timeline-200-steps")

        scrollTo(hasText("Step 200"))
        compose.onNode(line("Step 200", "6 Oct 2027 – 26 Oct 2027 · Not started")).assertIsDisplayed()
        compose.onNodeWithText("Later").assertDoesNotExist()
        compose.picture("timeline-200-steps-the-last")
    }

    @Test
    @Config(qualifiers = PHONE_AT_NIGHT)
    fun `the timeline reads in the dark as well`() {
        openTheTimeline()

        compose.onNode(line("Book the venue", "1 Nov 2026 – 15 Dec 2026 · In progress")).assertIsDisplayed()
        compose.picture("timeline-dark")
    }

    @Test
    fun `with the largest text the names and dates are still whole`() {
        openTheTimeline(fontScale = 2f)

        scrollTo(hasText("Book the venue"))
        compose.onNode(line("Book the venue", "1 Nov 2026 – 15 Dec 2026 · In progress")).assertIsDisplayed()
        compose.picture("timeline-large-text")
    }
}
