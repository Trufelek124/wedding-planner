package io.github.trufelek124.weddingplanner.planner

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.trufelek124.weddingplanner.data.GuestStatus
import io.github.trufelek124.weddingplanner.data.LivingPlanner
import io.github.trufelek124.weddingplanner.data.StandIn
import io.github.trufelek124.weddingplanner.ui.PHONE
import io.github.trufelek124.weddingplanner.ui.PHONE_AT_NIGHT
import io.github.trufelek124.weddingplanner.ui.picture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
 * A guest's status chosen on their own screen: shown at once, kept without a connection and sent when there is one,
 * the head count left as the server counted it, and what the server cannot take said. Everyone here is made up.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class GuestStatusScreenTest : SignedInPhoneTest() {

    private lateinit var theirs: LivingPlanner

    private val krystyna = StandIn.guest("g1", "Aunt Krystyna", status = "INVITED", sideMemberId = StandIn.ANNA, householdId = "h1", dietaryNeeds = "vegetarian")
    private val tomek = StandIn.guest("g2", "Uncle Tomek", status = "CONFIRMED", sideMemberId = StandIn.ANNA, householdId = "h1")
    private val bartek = StandIn.guest("g4", "Friend Bartek", status = "MAYBE")

    /** A state that a newer server knows. */
    private val mystery = StandIn.guest("g6", "Mystery Guest", status = "ARCHIVED", sideMemberId = StandIn.MAREK)

    @Before
    fun theirGuests() {
        theirs = LivingPlanner(
            server,
            entries = emptyList(),
            guests = listOf(krystyna, tomek, bartek, mystery),
            households = listOf(StandIn.household("h1", "The Nowak family", guests = 2)),
        )
    }

    private fun openGuests() {
        goTo("More")
        compose.onNode(hasText("Guests") and hasClickAction()).performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Guests") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun openGuest(name: String) {
        scrollTo(hasText(name) and hasClickAction())
        compose.onNode(hasText(name) and hasClickAction()).performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText(name) and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun statusChip(name: String): SemanticsMatcher = hasText(name) and hasClickAction() and isSelectable()

    private fun choose(name: String) {
        compose.onNode(statusChip(name)).performScrollTo().performClick()
    }

    private fun pressBackButton() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    private fun sentStatuses() = server.routes("PUT /api/guests/g1/status").map { it.body }

    @Test
    fun `a guest's own screen offers the five statuses with theirs chosen, and choosing one sends it at once`() {
        open()
        openGuests()
        openGuest("Aunt Krystyna")

        compose.onNode(hasText("Status") and isHeading()).assertIsDisplayed()
        for (status in listOf("Not invited yet", "Confirmed", "Declined", "Maybe")) {
            compose.onNode(statusChip(status)).assertIsNotSelected()
        }
        compose.onNode(statusChip("Invited")).assertIsSelected()
        // No save button: choosing is the change.
        compose.onNodeWithText("Save").assertDoesNotExist()

        choose("Confirmed")

        // The choice is written on the phone first, on a thread of its own, and then it is on the screen.
        waitUntil { compose.onAllNodes(statusChip("Confirmed") and isSelected()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(statusChip("Invited")).assertIsNotSelected()
        waitUntil { theirs.guestStatusOf("g1") == "CONFIRMED" }
        assertEquals(listOf("""{"status":"CONFIRMED"}"""), sentStatuses())
        waitUntil { compose.onAllNodesWithText("Waiting to be sent").fetchSemanticsNodes().isEmpty() }
        compose.onNode(statusChip("Confirmed")).assertIsSelected()
        compose.picture("guest-status")

        // The list is told by the server's count, loaded again after the change.
        pressBackButton()
        waitFor("2 people confirmed — 2 guests")
        scrollTo(hasText("Aunt Krystyna") and hasClickAction())
        compose.onNode(line("Aunt Krystyna", "Confirmed")).assertIsDisplayed()
    }

    @Test
    fun `without a connection the choice waits and says so, the head count says it is as loaded, and the next connection sends it`() {
        open()
        openGuests()
        waitFor("1 person confirmed — 1 guest")
        openGuest("Aunt Krystyna")
        server.down = true

        choose("Declined")

        waitFor("Waiting to be sent")
        compose.onNode(statusChip("Declined")).assertIsSelected()
        waitFor("No connection. This is what was loaded on 18 Nov 2026 at 14:05. 1 change is waiting to be sent.")
        // The phone was asked to send it once there is a connection, should the app be closed by then.
        assertEquals(1, phone.askedToSendLater)
        assertEquals("INVITED", theirs.guestStatusOf("g1"))
        compose.picture("guest-status-waiting")

        pressBackButton()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Guests") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("The head count is as it was loaded: 1 change is not sent yet.").assertIsDisplayed()
        // The phone does not count heads: the confirmed are still the server's.
        compose.onNodeWithText("1 person confirmed — 1 guest").assertIsDisplayed()
        scrollTo(hasText("Aunt Krystyna") and hasClickAction())
        compose.onNode(line("Aunt Krystyna", "Waiting to be sent", "Declined")).assertIsDisplayed()

        server.down = false
        compose.onNodeWithContentDescription("Load again").performClick()

        waitUntil { theirs.guestStatusOf("g1") == "DECLINED" }
        waitUntil { compose.onAllNodesWithText("Waiting to be sent").fetchSemanticsNodes().isEmpty() }
        compose.onAllNodesWithText("The head count is as it was loaded", substring = true).assertCountEquals(0)
        compose.onNode(line("Aunt Krystyna", "Declined")).assertIsDisplayed()
    }

    @Test
    fun `a choice made without a connection is still there, waiting, after the app was closed`() {
        runBlocking {
            phone.container.planner.sync()
            server.down = true
            phone.container.planner.setGuestStatus("g1", GuestStatus.CONFIRMED)
        }
        phone = phone.restarted()

        open()
        openGuests()
        scrollTo(hasText("Aunt Krystyna") and hasClickAction())

        compose.onNode(line("Aunt Krystyna", "Waiting to be sent", "Confirmed")).assertIsDisplayed()
        compose.onNodeWithText("The head count is as it was loaded: 1 change is not sent yet.").assertExists()

        server.down = false
        compose.onNodeWithContentDescription("Load again").performClick()
        waitUntil { theirs.guestStatusOf("g1") == "CONFIRMED" }
    }

    @Test
    fun `a guest whose status this app does not know has none chosen and can be given one of the five`() {
        open()
        openGuests()
        openGuest("Mystery Guest")

        compose.onNode(line("Other")).assertIsDisplayed()
        for (status in listOf("Not invited yet", "Invited", "Confirmed", "Declined", "Maybe")) {
            compose.onNode(statusChip(status)).assertIsNotSelected()
        }
        compose.onAllNodesWithText("Other").assertCountEquals(1)

        choose("Maybe")

        waitUntil { theirs.guestStatusOf("g6") == "MAYBE" }
        compose.onNode(statusChip("Maybe")).assertIsSelected()
    }

    @Test
    fun `choosing what the guest already has changes nothing and sends nothing`() {
        open()
        openGuests()
        openGuest("Aunt Krystyna")

        choose("Invited")

        compose.waitForIdle()
        assertTrue(sentStatuses().isEmpty())
        compose.onAllNodesWithText("Waiting to be sent").assertCountEquals(0)
    }

    @Test
    fun `a guest removed on the web is named in a notice that can be dismissed`() {
        open()
        openGuests()
        openGuest("Aunt Krystyna")
        theirs.removeGuest("g1")

        choose("Confirmed")

        waitFor("Your change to “Aunt Krystyna” could not be sent: the guest no longer exists.")
        // The notice comes with the refusal; the guest is gone from the screen once the list was loaded again.
        waitFor("This guest is no longer there.")
        compose.onNodeWithText("This guest is no longer there.").assertIsDisplayed()

        compose.onNodeWithText("OK").performClick()

        waitUntil { compose.onAllNodesWithText("Your change to", substring = true).fetchSemanticsNodes().isEmpty() }
        assertEquals(1, sentStatuses().size)
    }

    @Test
    fun `a status the server refuses is not saved, says so with the guest's name, and can be let go of`() {
        open()
        openGuests()
        openGuest("Aunt Krystyna")
        server.problem("PUT /api/guests/g1/status", 400, "bad-request")

        choose("Confirmed")

        waitFor("The server did not take your change to “Aunt Krystyna”.")
        compose.onAllNodesWithText("Not saved: the server did not take it").assertCountEquals(1)
        pressBackButton()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Guests") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("The server did not take your change to “Aunt Krystyna”.").assertIsDisplayed()
        scrollTo(hasText("Aunt Krystyna") and hasClickAction())
        compose.onNode(line("Aunt Krystyna", "Not saved: the server did not take it", "Confirmed")).assertIsDisplayed()
        compose.picture("guests-change-not-saved")

        compose.onNodeWithText("Discard the change").performClick()

        waitUntil { compose.onAllNodesWithText("The server did not take", substring = true).fetchSemanticsNodes().isEmpty() }
        compose.onNode(line("Aunt Krystyna", "Invited")).assertIsDisplayed()
        assertEquals(1, sentStatuses().size)
    }

    @Test
    fun `signing out with a status that was not sent asks first, and leaves nothing of the guest`() {
        open()
        openGuests()
        openGuest("Aunt Krystyna")
        server.down = true
        choose("Confirmed")
        waitFor("Waiting to be sent")
        goTo("More")
        scrollTo(hasText("Sign out"))

        compose.onNodeWithText("Sign out").performClick()
        compose.onNodeWithText("Sign out?").assertIsDisplayed()
        compose.onNodeWithText("1 change made on this phone has not reached the server and will be lost.").assertIsDisplayed()
        compose.onNodeWithText("Stay signed in").performClick()
        compose.onNodeWithText("Signed in as Anna").assertIsDisplayed()

        compose.onNodeWithText("Sign out").performClick()
        compose.onAllNodesWithText("Sign out").onLast().performClick()

        waitFor("Sign in to see your plan.")
        assertFalse(phone.everythingKept().contains("Aunt Krystyna"))
        assertFalse(phone.everythingKept().contains("CONFIRMED"))
    }

    @Test
    fun `nothing of a guest is written to the log when their status is chosen, sent or refused`() {
        ShadowLog.clear()
        open()
        openGuests()
        openGuest("Aunt Krystyna")
        choose("Confirmed")
        waitUntil { theirs.guestStatusOf("g1") == "CONFIRMED" }
        server.problem("PUT /api/guests/g1/status", 400, "bad-request")
        choose("Declined")
        waitFor("The server did not take your change to “Aunt Krystyna”.")
        server.down = true
        choose("Maybe")
        waitFor("Waiting to be sent")

        val written = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable?.stackTraceToString().orEmpty()}" }
        for (theirs in listOf("Krystyna", "Tomek", "Bartek", "Mystery", "Nowak", "vegetarian", "Anna", "Marek", "access-1")) {
            assertFalse("the log holds \"$theirs\"", written.contains(theirs, ignoreCase = true))
        }
    }

    @Test
    @Config(qualifiers = PHONE_AT_NIGHT)
    fun `the status reads in the dark as well`() {
        open()
        openGuests()
        openGuest("Aunt Krystyna")
        server.down = true
        choose("Confirmed")
        waitFor("Waiting to be sent")
        compose.picture("guest-status-waiting-at-night")
        pressBackButton()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Guests") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("The head count is as it was loaded: 1 change is not sent yet.").assertIsDisplayed()
        compose.picture("guests-status-waiting-at-night")
    }

    @Test
    fun `with the largest text nothing is cut off`() {
        open(fontScale = 2f)
        openGuests()
        openGuest("Aunt Krystyna")
        server.down = true
        choose("Not invited yet")
        waitFor("Waiting to be sent")
        compose.picture("guest-status-big-letters")
        compose.onNode(statusChip("Maybe")).performScrollTo().assertIsDisplayed()
        compose.picture("guest-status-big-letters-chips")
        pressBackButton()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Guests") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("The head count is as it was loaded: 1 change is not sent yet.").assertIsDisplayed()
        compose.picture("guests-status-waiting-big-letters")
    }
}
