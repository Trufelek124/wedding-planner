package io.github.trufelek124.weddingplanner.planner

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.Density
import io.github.trufelek124.weddingplanner.StandInPhone
import io.github.trufelek124.weddingplanner.api.StandInServer
import io.github.trufelek124.weddingplanner.data.StandIn
import io.github.trufelek124.weddingplanner.data.planner
import io.github.trufelek124.weddingplanner.data.plannerUnreachable
import io.github.trufelek124.weddingplanner.ui.AppRoot
import io.github.trufelek124.weddingplanner.ui.PHONE
import io.github.trufelek124.weddingplanner.ui.PHONE_AT_NIGHT
import io.github.trufelek124.weddingplanner.ui.PlannerTheme
import io.github.trufelek124.weddingplanner.ui.picture
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog

/**
 * Home, the plan and the tasks as a signed-in person sees them: loaded from a stand-in server,
 * kept in the phone's database, and still there without a connection. The wedding is made up.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class PlannerScreensTest : SignedInPhoneTest() {

    private val venue = StandIn.entry(
        "p1", "Book the venue", kind = "PLAN_ITEM", startDate = "2026-11-01", dueDate = "2026-12-15", status = "IN_PROGRESS",
        priority = "HIGH", priorityReason = "DUE_WITHIN_60_DAYS", categoryId = "c1", vendorId = "v1",
        assigneeIds = listOf(StandIn.ANNA, StandIn.MAREK), taskCount = 2,
    )
    private val music = StandIn.entry("p2", "Choose the music", kind = "PLAN_ITEM", categoryId = "c2")
    private val saveTheDates = StandIn.entry(
        "p3", "Send the save-the-dates", kind = "PLAN_ITEM", dueDate = "2026-11-01", status = "DONE", priority = "LOW", priorityReason = "FINISHED",
    )
    private val deposit = StandIn.entry(
        "t1", "Pay the deposit", dueDate = "2026-11-10", priority = "CRITICAL", priorityReason = "OVERDUE", parentId = "p1",
        categoryId = "c1", vendorId = "v1", amount = 250000, paymentKind = "DEPOSIT", assigneeIds = listOf(StandIn.MAREK),
    )
    private val tasting = StandIn.entry("t2", "Go to the tasting", dueDate = "2026-11-25", parentId = "p1", priority = "HIGH", priorityReason = "DUE_WITHIN_14_DAYS")
    private val rings = StandIn.entry("t3", "Choose the rings", assigneeIds = listOf(StandIn.ANNA))
    private val invitations = StandIn.entry("t4", "Order the invitations", dueDate = "2026-11-05", status = "DONE", priority = "LOW", priorityReason = "FINISHED")
    private val photographer = StandIn.entry(
        "t5", "Pay the photographer", dueDate = "2027-06-01", amount = 400000, paymentKind = "FINAL", vendorId = "v2", priorityReason = "DUE_LATER",
    )
    private val fitting = StandIn.entry(
        "t6", "Pay for the fitting", dueDate = "2026-11-12", status = "DONE", amount = 30000, paidOn = "2026-11-12", priority = "LOW", priorityReason = "FINISHED",
    )

    private fun aWedding() {
        server.planner(
            entries = listOf(saveTheDates, invitations, deposit, fitting, tasting, venue, photographer, rings, music),
            home = StandIn.home(
                overdue = listOf(deposit),
                next = listOf(tasting, venue, rings, music, photographer),
                nextPayments = listOf(deposit, photographer),
                budget = StandIn.budget(total = 6000000, planned = 5000000, committed = 680000, paid = 30000),
            ),
            categories = listOf(StandIn.category("c1", "Venue"), StandIn.category("c2", "Music")),
            vendors = listOf(StandIn.vendor("v1", "Palace on the Hill"), StandIn.vendor("v2", "Studio Light")),
        )
    }

    @Before
    fun theirWedding() {
        aWedding()
    }

    // ---------------------------------------------------------------- Home

    @Test
    fun `Home says how long until the wedding, what is overdue and what comes next`() {
        open()

        compose.onNodeWithText("Saturday 19 June 2027").assertIsDisplayed()
        compose.onNodeWithText("213 days to go").assertIsDisplayed()
        compose.onNode(hasText("Overdue") and isHeading()).assertIsDisplayed()
        compose.onNode(
            line("Pay the deposit", "Critical — overdue", "2,500.00\u00a0zł, deposit, to pay", "due 10 Nov 2026", "Book the venue", "Venue", "Palace on the Hill", "Marek"),
        ).assertIsDisplayed()
        compose.onNode(hasText("Next") and isHeading()).assertIsDisplayed()
        compose.onNode(line("Go to the tasting", "High — due within 14 days", "due 25 Nov 2026", "Book the venue")).assertIsDisplayed()
        compose.onNode(
            line("Book the venue", "In progress", "High — ends within 60 days", "1 Nov 2026 – 15 Dec 2026", "Venue", "Palace on the Hill", "Anna & Marek"),
        ).assertIsDisplayed()
        compose.picture("home")
    }

    @Test
    fun `Home shows the money in a sentence and the payments to make next`() {
        open()

        scrollTo(hasText("Budget"))
        scrollTo(hasText("Loaded on 18 Nov 2026 at 14:05"))
        compose.onNodeWithText(
            "300.00\u00a0zł paid, 6,500.00\u00a0zł still to pay — 53,200.00\u00a0zł of the 60,000.00\u00a0zł budget not committed.",
        ).assertIsDisplayed()
        compose.onNodeWithContentDescription(
            "The whole budget: 300.00\u00a0zł paid, 6,500.00\u00a0zł still to pay, of 60,000.00\u00a0zł budget",
        ).assertIsDisplayed()
        compose.onNode(hasText("Next payments") and isHeading()).assertIsDisplayed()
        compose.onNode(line("Pay the deposit", "2,500.00\u00a0zł", "due 10 Nov 2026")).assertIsDisplayed()
        compose.onNode(line("Pay the photographer", "4,000.00\u00a0zł", "due 1 Jun 2027")).assertIsDisplayed()
        compose.onNodeWithText("Signed in as Anna").assertIsDisplayed()
        compose.picture("home-money")
    }

    @Test
    fun `Home with nothing to do and no money says so and shows no empty groups`() {
        server.planner()
        open()

        compose.onNodeWithText("Nothing is waiting for you. What has to happen is in the plan; single things to do are under tasks.").assertIsDisplayed()
        compose.onNodeWithText("Overdue").assertDoesNotExist()
        compose.onNodeWithText("Budget").assertDoesNotExist()
        compose.onNodeWithText("Next payments").assertDoesNotExist()
        compose.picture("home-empty")
    }

    @Test
    @Config(qualifiers = PHONE_AT_NIGHT)
    fun `Home reads in the dark as well`() {
        open()

        compose.onNodeWithText("213 days to go").assertIsDisplayed()
        compose.picture("home-dark")
    }

    // ---------------------------------------------------------------- the plan

    @Test
    fun `the plan lists what is still to do, the steps with dates first`() {
        open()

        goTo("Plan")

        compose.onNode(hasText("Scheduled") and isHeading()).assertIsDisplayed()
        compose.onNode(
            line("Book the venue", "In progress", "High — ends within 60 days", "1 Nov 2026 – 15 Dec 2026", "Venue", "Palace on the Hill", "Anna & Marek"),
        ).assertIsDisplayed()
        compose.onNode(hasText("Not scheduled") and isHeading()).assertIsDisplayed()
        compose.onNode(line("Choose the music", "Not started", "Normal — no date", "Music")).assertIsDisplayed()
        // What is done is not in the way until it is asked for.
        compose.onNodeWithText("Send the save-the-dates").assertDoesNotExist()
        compose.picture("plan")

        compose.onNodeWithText("Everything").performClick()
        compose.onNode(line("Send the save-the-dates", "Done", "Low — needs no more attention", "by 1 Nov 2026")).assertIsDisplayed()
    }

    @Test
    fun `a step of the plan shows its tasks when asked`() {
        open()
        goTo("Plan")
        compose.onNodeWithText("Pay the deposit").assertDoesNotExist()

        compose.onNodeWithText("▸ 2 tasks").performClick()

        compose.onNode(line("Pay the deposit", "Critical — overdue", "2,500.00\u00a0zł, deposit, to pay", "due 10 Nov 2026", "Marek")).assertIsDisplayed()
        compose.onNode(line("Go to the tasting", "due 25 Nov 2026")).assertIsDisplayed()
        // Under its own step a task does not say again which step it belongs to.
        compose.onNode(line("Go to the tasting", "Book the venue")).assertDoesNotExist()
        compose.picture("plan-with-tasks")

        compose.onNodeWithText("▾ 2 tasks").performClick()
        compose.onNodeWithText("Pay the deposit").assertDoesNotExist()
    }

    @Test
    fun `an empty plan says where the plan is made`() {
        server.planner()
        open()

        goTo("Plan")

        compose.onNodeWithText("Nothing in the plan yet. The plan is put together in the web app.").assertIsDisplayed()
    }

    // ---------------------------------------------------------------- the tasks

    @Test
    fun `the tasks say what the web says about them, money and vendor included`() {
        open()

        goTo("Tasks")

        compose.onNode(
            line("Pay the deposit", "Critical — overdue", "2,500.00\u00a0zł, deposit, to pay", "due 10 Nov 2026", "Book the venue", "Venue", "Palace on the Hill", "Marek"),
        ).assertIsDisplayed()
        compose.onNode(line("Go to the tasting", "High — due within 14 days", "due 25 Nov 2026", "Book the venue")).assertIsDisplayed()
        compose.onNode(
            line("Pay the photographer", "Normal — due in more than 60 days", "4,000.00\u00a0zł, final payment, to pay", "due 1 Jun 2027", "Studio Light"),
        ).assertIsDisplayed()
        compose.onNode(line("Choose the rings", "Normal — no date", "Anna")).assertIsDisplayed()
        compose.onNodeWithText("Order the invitations").assertDoesNotExist()
        // The plan's own steps are not tasks.
        compose.onNode(line("Book the venue", "In progress")).assertDoesNotExist()
        compose.picture("tasks")
    }

    @Test
    fun `the tasks can be narrowed down to the payments, and widened to what is done`() {
        open()
        goTo("Tasks")

        compose.onNodeWithText("Payments only").performClick()
        compose.onNodeWithText("Choose the rings").assertDoesNotExist()
        compose.onNodeWithText("Pay the deposit").assertIsDisplayed()
        compose.onNodeWithText("Pay the photographer").assertIsDisplayed()

        compose.onNodeWithText("Everything").performClick()
        compose.onNode(line("Pay for the fitting", "Done", "300.00\u00a0zł, paid 12 Nov 2026", "due 12 Nov 2026")).assertIsDisplayed()
        compose.onNodeWithText("Order the invitations").assertDoesNotExist()
        compose.picture("tasks-payments")

        compose.onNodeWithText("Payments only").performClick()
        scrollTo(hasText("Order the invitations"))
        compose.onNode(line("Order the invitations", "Done", "Low — needs no more attention", "due 5 Nov 2026")).assertIsDisplayed()
    }

    @Test
    fun `no tasks, and no payments among them, are said in words`() {
        server.planner(entries = listOf(rings))
        open()
        goTo("Tasks")

        compose.onNodeWithText("Payments only").performClick()
        compose.onNodeWithText("No payments here.").assertIsDisplayed()

        server.planner()
        compose.onNodeWithContentDescription("Load again").performClick()
        waitFor("No tasks yet.")
    }

    // ---------------------------------------------------------------- moving between them

    @Test
    fun `the bar at the bottom moves between the three, and Back leads to Home`() {
        open()
        compose.onNode(hasText("Home") and isSelectable()).assertIsSelected()
        compose.onNode(hasText("Wedding planner") and isHeading()).assertIsDisplayed()

        goTo("Tasks")
        compose.onNode(hasText("Tasks") and isSelectable()).assertIsSelected()
        compose.onNode(hasText("Home") and isSelectable()).assertIsNotSelected()

        goTo("Plan")
        compose.onNode(hasText("Plan") and isSelectable()).assertIsSelected()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitFor("213 days to go")
        compose.onNode(hasText("Home") and isSelectable()).assertIsSelected()
    }

    @Test
    fun `a list keeps how it was narrowed down while one looks at another`() {
        open()
        goTo("Tasks")
        compose.onNodeWithText("Everything").performClick()
        scrollTo(hasText("Order the invitations"))

        goTo("Plan")
        goTo("Tasks")

        scrollTo(hasText("Order the invitations"))
        compose.onNodeWithText("Order the invitations").assertIsDisplayed()
    }

    @Test
    fun `with the largest text nothing is cut off and everything is still reachable`() {
        open(fontScale = 2f)

        compose.onNodeWithText("213 days to go").assertIsDisplayed()
        compose.picture("home-large-text")
        scrollTo(hasText("Sign out"))
        compose.onNodeWithText("Sign out").assertIsDisplayed()

        goTo("Tasks")
        compose.onNode(line("Pay the deposit", "2,500.00\u00a0zł, deposit, to pay")).assertIsDisplayed()
        compose.picture("tasks-large-text")
        goTo("Plan")
        compose.onNodeWithText("▸ 2 tasks").performClick()
        compose.picture("plan-large-text")
    }

    // ---------------------------------------------------------------- loading again

    @Test
    fun `pulling a list down loads it again`() {
        open()
        goTo("Tasks")
        server.planner(entries = listOf(StandIn.entry("t9", "Taste the cake")))
        val loadsBefore = server.routes("GET /api/entries").size

        compose.onNode(hasScrollToNodeAction()).performTouchInput { swipeDown() }

        waitFor("Taste the cake")
        assertEquals(loadsBefore + 1, server.routes("GET /api/entries").size)
        compose.onNodeWithText("Pay the deposit").assertDoesNotExist()
    }

    @Test
    fun `the button at the top loads again too, for whoever cannot pull`() {
        open()
        server.planner(entries = listOf(StandIn.entry("t9", "Taste the cake")), home = StandIn.home(daysLeft = 212, today = "2026-11-19"))
        phone.clock.now = phone.clock.now.plusSeconds(24 * 3600)

        compose.onNodeWithContentDescription("Load again").performClick()

        // The new load has nothing overdue or next: once it says so, it is the one on the screen.
        waitFor("Nothing is waiting for you. What has to happen is in the plan; single things to do are under tasks.")
        compose.onNodeWithText("212 days to go").assertIsDisplayed()
        scrollTo(hasText("Loaded on 19 Nov 2026 at 14:05"))
        compose.onNodeWithText("Loaded on 19 Nov 2026 at 14:05").assertIsDisplayed()
    }

    // ---------------------------------------------------------------- without a connection

    @Test
    fun `without a connection the phone shows what it loaded last, and says from when it is`() {
        aPhoneThatLoadedEarlier()
        server.plannerUnreachable()
        phone.clock.now = phone.clock.now.plusSeconds(3 * 3600)

        open()

        waitFor("No connection. This is what was loaded on 18 Nov 2026 at 14:05.")
        compose.onNodeWithText("213 days to go").assertIsDisplayed()
        compose.onNode(line("Pay the deposit", "Critical — overdue", "Palace on the Hill")).assertIsDisplayed()
        compose.picture("home-without-a-connection")

        goTo("Plan")
        compose.onNodeWithText("No connection. This is what was loaded on 18 Nov 2026 at 14:05.").assertIsDisplayed()
        compose.onNode(line("Book the venue", "In progress", "Anna & Marek")).assertIsDisplayed()

        goTo("Tasks")
        compose.onNodeWithText("No connection. This is what was loaded on 18 Nov 2026 at 14:05.").assertIsDisplayed()
        compose.onNode(line("Pay the photographer", "4,000.00\u00a0zł, final payment, to pay", "Studio Light")).assertIsDisplayed()
        compose.picture("tasks-without-a-connection")
    }

    @Test
    fun `days later without a connection the countdown has moved on`() {
        aPhoneThatLoadedEarlier()
        server.plannerUnreachable()
        phone.clock.now = phone.clock.now.plusSeconds(3 * 24 * 3600)

        open()

        waitFor("No connection. This is what was loaded on 18 Nov 2026 at 14:05.")
        compose.onNodeWithText("210 days to go").assertIsDisplayed()
        compose.onNodeWithText("213 days to go").assertDoesNotExist()
    }

    @Test
    fun `when the connection is back, loading again brings the latest and the notice goes`() {
        open()
        server.plannerUnreachable()
        compose.onNodeWithContentDescription("Load again").performClick()
        waitFor("No connection. This is what was loaded on 18 Nov 2026 at 14:05.")

        server.planner(entries = listOf(StandIn.entry("t9", "Taste the cake")), home = StandIn.home(next = listOf(StandIn.entry("t9", "Taste the cake"))))
        compose.onNodeWithContentDescription("Load again").performClick()

        waitFor("Taste the cake")
        waitUntilGone("No connection.")
        compose.onNodeWithText("Pay the deposit").assertDoesNotExist()
    }

    @Test
    fun `a load that fails for another reason says that the latest could not be loaded`() {
        open()
        server.problem("GET /api/home", 500, "something-from-a-newer-server")

        compose.onNodeWithContentDescription("Load again").performClick()

        waitFor("The latest could not be loaded. This is what was loaded on 18 Nov 2026 at 14:05.")
        compose.onNodeWithText("213 days to go").assertIsDisplayed()
    }

    // ---------------------------------------------------------------- what a screen reader is told, and what is kept

    @Test
    fun `the names of the groups are headings, and the bar of money is described in words`() {
        open()

        for (group in listOf("Anna & Marek", "Overdue", "Next")) {
            compose.onNode(hasText(group) and isHeading()).assertExists()
        }
        scrollTo(hasText("Budget"))
        compose.onNode(hasText("Budget") and isHeading()).assertExists()
        scrollTo(hasContentDescription("The whole budget", substring = true))
        val bar = compose.onNode(hasContentDescription("The whole budget", substring = true)).fetchSemanticsNode()
        assertTrue(bar.config.contains(SemanticsProperties.ContentDescription))
    }

    @Test
    fun `signing out removes the planner from the phone`() {
        open()
        assertTrue(phone.everythingKept().contains("Palace on the Hill"))
        scrollTo(hasText("Sign out"))

        compose.onNodeWithText("Sign out").performClick()

        waitFor("Sign in to see your plan.")
        compose.onNodeWithText("Anna & Marek").assertDoesNotExist()
        val kept = phone.everythingKept()
        for (theirs in listOf("Palace on the Hill", "Studio Light", "Pay the deposit", "Book the venue", "Marek", "Anna")) {
            assertFalse("the phone still keeps \"$theirs\"", kept.contains(theirs))
        }
    }

    @Test
    fun `nothing of the plan or the people is written to the log`() {
        ShadowLog.clear()
        open()
        goTo("Plan")
        compose.onNodeWithText("▸ 2 tasks").performClick()
        goTo("Tasks")
        server.plannerUnreachable()
        compose.onNodeWithContentDescription("Load again").performClick()
        waitFor("No connection. This is what was loaded on 18 Nov 2026 at 14:05.")

        val written = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable?.stackTraceToString().orEmpty()}" }
        for (theirs in listOf("Anna", "Marek", "Palace", "Studio Light", "deposit", "venue", "access-1", "example.com")) {
            assertFalse("the log holds \"$theirs\"", written.contains(theirs, ignoreCase = true))
        }
    }
}
