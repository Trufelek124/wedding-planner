package io.github.trufelek124.weddingplanner.planner

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import io.github.trufelek124.weddingplanner.data.Decision
import io.github.trufelek124.weddingplanner.data.LivingPlanner
import io.github.trufelek124.weddingplanner.data.StandIn
import io.github.trufelek124.weddingplanner.ui.PHONE
import io.github.trufelek124.weddingplanner.ui.PHONE_AT_NIGHT
import io.github.trufelek124.weddingplanner.ui.picture
import kotlinx.coroutines.CompletableDeferred
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
 * Accepting and rejecting a suggestion on the phone: at once on the card, kept without a connection and taken back
 * until it is sent, sent by the app, and what the server cannot take said. All of the mail and the people here is made up.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class SuggestionDecisionsScreenTest : SignedInPhoneTest() {

    private lateinit var theirs: LivingPlanner

    private val payment = StandIn.suggestion(
        "s1", "Pay the deposit", kind = "PAYMENT", notes = "By bank transfer", dueDate = "2026-12-01", amount = 250000, paymentKind = "DEPOSIT",
        subject = "Deposit for the hall", passage = "Please pay the deposit by 1 December.", confidence = 62,
    )

    private val task = StandIn.suggestion(
        "s2", "Send the guest list", senderName = "Ewa from the Registry Office", senderAddress = "ewa@registry.example", subject = "Guest list",
        receivedAt = "2026-11-17T09:30:00Z", passage = "We need the guest list two weeks before.", confidence = 80,
    )

    private val step = StandIn.suggestion(
        "s3", "Rehearsal dinner", kind = "PLAN_ITEM", startDate = "2026-12-01", dueDate = "2026-12-15", receivedAt = "2026-11-16T08:00:00Z",
        passage = "The dinner is planned for the first half of December.", confidence = 45,
    )

    @Before
    fun theirSuggestions() {
        theirs = LivingPlanner(server, entries = emptyList(), suggestions = listOf(payment, task, step))
    }

    private fun openSuggestions() {
        goTo("More")
        compose.onNode(hasText("Suggestions") and hasClickAction()).performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Suggestions") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    /** The list is lazy: what is far down is not there until it was scrolled to. */
    private fun reveal(description: String) {
        scrollTo(hasContentDescription(description))
        compose.waitForIdle()
    }

    private fun accept(title: String) {
        press("Accept “$title”")
    }

    private fun reject(title: String) {
        press("Reject “$title”")
    }

    private fun takeBack(title: String) {
        press("Take back your decision on “$title”")
    }

    /**
     * Presses a card's button through the button's own action, not through a touch at the place it lay a moment
     * ago: after a decision the notice above the list grows or shrinks by a line ("1 change is waiting to be
     * sent"), the list moves under it, and a touch aimed before that lands beside the button. That made these
     * tests fail now and then. That a finger reaches the button is held by its height, which the tests check.
     */
    private fun press(description: String) {
        shown(description).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun shown(description: String): SemanticsNodeInteraction {
        reveal(description)
        return compose.onNodeWithContentDescription(description).performScrollTo()
    }

    /** The picture of the inbox with the button that takes the decision on the given suggestion back in view. */
    private fun pictureWithTakeBack(title: String, name: String) {
        shown("Take back your decision on “$title”")
        compose.waitForIdle()
        compose.picture(name)
    }

    /** Back with a connection: what the phone sent while there was none is not what is asked about. */
    private fun connectionBack() {
        server.calls.clear()
        server.down = false
    }

    private fun waitUntilLoadedAgain() {
        waitUntil { server.routes("GET /api/suggestions").isNotEmpty() }
    }

    /**
     * Waits until the words are on the screen: a card that has just been decided on is drawn anew, and for a
     * moment its words are in the tree without being shown yet. Looking at once failed now and then.
     */
    private fun waitUntilDisplayed(text: String) {
        waitUntil { runCatching { compose.onNodeWithText(text).performScrollTo().assertIsDisplayed() }.isSuccess }
    }

    /**
     * A decision made without a connection is tried at once, and while that try is on its way it can be neither
     * taken back nor replaced. The notice above the list counts it ("1 change is waiting to be sent.") once the
     * try is over: a test that takes a decision back waits for that first. Pressing "Take back" at once worked
     * only when the try happened to be over already, which made these tests fail now and then.
     */
    private fun waitUntilItOnlyWaits() {
        waitUntil { compose.onAllNodes(hasText("waiting to be sent.", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitUntilCardGone(title: String) {
        waitUntil { compose.onAllNodes(hasText(title)).fetchSemanticsNodes().isEmpty() }
    }

    /** Home's title is the app's name, so the place is found by its mark in the bar and by the couple's names. */
    private fun goHome() {
        compose.onNode(hasText("Home") and isSelectable()).performClick()
        waitFor("Anna & Marek")
    }

    private fun posted() = server.calls.map { it.route }.filter { it.startsWith("POST /api/suggestions") }

    private fun pressBackButton() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    // ---------------------------------------------------------------- with a connection

    @Test
    fun `every card offers Accept and Reject, each at least as high as a finger, naming the suggestion`() {
        open()
        openSuggestions()
        compose.picture("suggestions-decide")

        for (title in listOf("Pay the deposit", "Send the guest list", "Rehearsal dinner")) {
            shown("Accept “$title”").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            shown("Reject “$title”").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        }
    }

    @Test
    fun `accepting with a connection sends it as proposed, the card goes, and the task is on Tasks`() {
        open()
        openSuggestions()

        accept("Send the guest list")

        waitUntil { theirs.suggestionStateOf("s2") == "ACCEPTED" }
        waitUntilCardGone("Send the guest list")
        assertEquals(listOf("POST /api/suggestions/s2/accept"), posted())
        assertEquals("", server.routes("POST /api/suggestions/s2/accept").single().body)
        // The other cards stay.
        compose.onNodeWithContentDescription("Accept “Pay the deposit”").assertExists()
        goTo("Tasks")
        compose.onNode(hasText("Send the guest list")).assertIsDisplayed()
    }

    @Test
    fun `rejecting with a connection sends it, the card goes, and nothing is made`() {
        open()
        openSuggestions()

        reject("Send the guest list")

        waitUntil { theirs.suggestionStateOf("s2") == "REJECTED" }
        waitUntilCardGone("Send the guest list")
        assertEquals(listOf("POST /api/suggestions/s2/reject"), posted())
        goTo("Tasks")
        compose.onNode(hasText("Send the guest list")).assertDoesNotExist()
    }

    // ---------------------------------------------------------------- without a connection

    @Test
    fun `without a connection the card says what was decided and that it waits, offers Take back, and is sent once the connection is back`() {
        open()
        openSuggestions()
        server.down = true

        accept("Send the guest list")

        waitFor("Waiting to be sent")
        waitUntilDisplayed("Accepted")
        // The two buttons are replaced by the one that takes the decision back.
        compose.onNodeWithContentDescription("Accept “Send the guest list”").assertDoesNotExist()
        compose.onNodeWithContentDescription("Reject “Send the guest list”").assertDoesNotExist()
        shown("Take back your decision on “Send the guest list”").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        // The others still wait for a decision.
        compose.onNodeWithContentDescription("Accept “Pay the deposit”").assertExists()
        waitFor("No connection. This is what was loaded on 18 Nov 2026 at 14:05. 1 change is waiting to be sent.")
        // The phone was asked to send it once there is a connection, should the app be closed by then.
        assertEquals(1, phone.askedToSendLater)
        assertEquals("WAITING", theirs.suggestionStateOf("s2"))
        pictureWithTakeBack("Send the guest list", "suggestion-decided-waiting")

        connectionBack()
        compose.onNodeWithContentDescription("Load again").performClick()

        waitUntil { theirs.suggestionStateOf("s2") == "ACCEPTED" }
        waitUntilCardGone("Send the guest list")
        assertEquals(1, posted().size)
    }

    @Test
    fun `taking the decision back brings the two buttons back, and nothing is sent when the connection returns`() {
        open()
        openSuggestions()
        server.down = true
        reject("Send the guest list")
        waitFor("Waiting to be sent")
        waitUntilItOnlyWaits()

        takeBack("Send the guest list")

        waitUntil { compose.onAllNodesWithText("Waiting to be sent").fetchSemanticsNodes().isEmpty() }
        shown("Accept “Send the guest list”").assertIsDisplayed()
        compose.onNodeWithContentDescription("Reject “Send the guest list”").assertExists()
        compose.onNodeWithContentDescription("Take back your decision on “Send the guest list”").assertDoesNotExist()
        compose.onAllNodesWithText("Rejected").assertCountEquals(0)

        connectionBack()
        compose.onNodeWithContentDescription("Load again").performClick()
        waitUntilLoadedAgain()

        assertTrue(posted().isEmpty())
        assertEquals("WAITING", theirs.suggestionStateOf("s2"))
        compose.onNodeWithContentDescription("Accept “Send the guest list”").assertExists()
    }

    @Test
    fun `a decision taken while the first is waiting replaces it`() {
        open()
        openSuggestions()
        server.down = true
        accept("Send the guest list")
        waitFor("Waiting to be sent")
        waitUntilItOnlyWaits()
        takeBack("Send the guest list")
        waitUntil { compose.onAllNodesWithText("Waiting to be sent").fetchSemanticsNodes().isEmpty() }

        reject("Send the guest list")
        waitFor("Rejected")
        connectionBack()
        compose.onNodeWithContentDescription("Load again").performClick()

        waitUntil { theirs.suggestionStateOf("s2") == "REJECTED" }
        assertEquals(listOf("POST /api/suggestions/s2/reject"), posted())
    }

    @Test
    fun `a decision that is on its way cannot be taken back, the card says so, and the decision lands`() {
        open()
        openSuggestions()
        val arrived = CompletableDeferred<Unit>()
        val mayAnswer = CompletableDeferred<Unit>()
        theirs.holdUp = { request ->
            if (request.method.value == "POST" && request.url.encodedPath.startsWith("/api/suggestions/") && !arrived.isCompleted) {
                arrived.complete(Unit)
                mayAnswer.await()
            }
        }

        accept("Send the guest list")
        waitUntil { arrived.isCompleted }
        takeBack("Send the guest list")

        waitFor("It is being sent and cannot be taken back.")
        waitUntilDisplayed("Accepted")
        compose.onNodeWithContentDescription("Take back your decision on “Send the guest list”").assertExists()
        mayAnswer.complete(Unit)

        waitUntil { theirs.suggestionStateOf("s2") == "ACCEPTED" }
        waitUntilCardGone("Send the guest list")
        compose.onAllNodesWithText("It is being sent", substring = true).assertCountEquals(0)
        assertEquals(1, posted().size)
    }

    // ---------------------------------------------------------------- the counts

    @Test
    fun `Home and More do not count what was decided on the phone`() {
        open()
        compose.onNode(hasText("3 suggestions wait")).assertIsDisplayed()
        openSuggestions()
        server.down = true
        accept("Send the guest list")
        waitFor("Waiting to be sent")

        pressBackButton()
        compose.waitUntil(WAIT) { compose.onAllNodes(line("Suggestions", "2 suggestions wait") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        goHome()
        compose.onNode(hasText("2 suggestions wait")).assertIsDisplayed()

        compose.onNode(hasText("2 suggestions wait")).performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Suggestions") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        reject("Pay the deposit")
        waitUntil { runBlocking { phone.container.planner.stillToSend() } == 2 }
        accept("Rehearsal dinner")
        waitUntil { runBlocking { phone.container.planner.stillToSend() } == 3 }
        // Back from the inbox, which was opened from Home, leads to Home.
        pressBackButton()
        waitFor("Anna & Marek")
        compose.onNode(hasText("suggestion", substring = true)).assertDoesNotExist()
        goTo("More")
        compose.onNode(line("Suggestions", "Nothing waits") and hasClickAction()).assertIsDisplayed()
    }

    // ---------------------------------------------------------------- what the server cannot take

    @Test
    fun `when the other decision was made first the notice says what was decided, and letting go removes it`() {
        open()
        openSuggestions()
        server.down = true
        accept("Send the guest list")
        waitFor("Waiting to be sent")
        theirs.decideElsewhere("s2", "REJECTED")
        connectionBack()

        compose.onNodeWithContentDescription("Load again").performClick()

        waitFor("“Send the guest list” was already rejected. Your acceptance was not kept.")
        // The load took the suggestion away: only the notice is left of it.
        waitUntilCardGone("Send the guest list")
        compose.picture("suggestions-decided-otherwise")
        assertEquals(1, posted().size)

        compose.onNodeWithText("OK").performClick()

        waitUntil { compose.onAllNodesWithText("was already rejected", substring = true).fetchSemanticsNodes().isEmpty() }
        assertEquals(1, posted().size)
        assertEquals("REJECTED", theirs.suggestionStateOf("s2"))
    }

    @Test
    fun `a rejection that found the suggestion accepted says so the other way round`() {
        open()
        openSuggestions()
        server.down = true
        reject("Pay the deposit")
        waitFor("Waiting to be sent")
        theirs.decideElsewhere("s1", "ACCEPTED")
        connectionBack()

        compose.onNodeWithContentDescription("Load again").performClick()

        waitFor("“Pay the deposit” was already accepted. Your rejection was not kept.")
    }

    @Test
    fun `a suggestion that is gone is said to be, and letting go removes the notice`() {
        open()
        openSuggestions()
        server.down = true
        reject("Send the guest list")
        waitFor("Waiting to be sent")
        theirs.removeSuggestion("s2")
        connectionBack()

        compose.onNodeWithContentDescription("Load again").performClick()

        waitFor("“Send the guest list” no longer exists, so your rejection was not sent.")
        compose.onNodeWithText("OK").performClick()

        waitUntil { compose.onAllNodesWithText("no longer exists", substring = true).fetchSemanticsNodes().isEmpty() }
        assertEquals(1, posted().size)
    }

    @Test
    fun `an acceptance the server refuses points to the web, offers no buttons meanwhile, and letting go brings them back`() {
        open()
        openSuggestions()
        server.problem("POST /api/suggestions/s1/accept", 400, "bad-request")

        accept("Pay the deposit")

        waitFor("The server did not take your acceptance of “Pay the deposit”. The proposal can be changed in the web app.")
        compose.onNodeWithContentDescription("Accept “Pay the deposit”").assertDoesNotExist()
        compose.onNodeWithContentDescription("Reject “Pay the deposit”").assertDoesNotExist()
        compose.onAllNodesWithText("Waiting to be sent").assertCountEquals(0)
        // Still on the server, so still waiting for someone: the card stays, and Home still counts it.
        compose.onNode(line("Payment", "Pay the deposit")).assertExists()
        assertEquals(1, posted().size)
        compose.picture("suggestions-decision-refused")

        compose.onNodeWithText("Discard the decision").performClick()

        waitUntil { compose.onAllNodesWithText("The server did not take", substring = true).fetchSemanticsNodes().isEmpty() }
        shown("Accept “Pay the deposit”").assertIsDisplayed()
        assertEquals(1, posted().size)
    }

    // ---------------------------------------------------------------- closing the app, signing out, the log

    @Test
    fun `a decision made without a connection is still there, waiting, after the app was closed`() {
        runBlocking {
            phone.container.planner.sync()
            server.down = true
            phone.container.planner.decideSuggestion("s2", Decision.ACCEPT)
        }
        phone = phone.restarted()

        open()
        openSuggestions()

        compose.onNodeWithText("Accepted").assertExists()
        waitFor("Waiting to be sent")
        compose.onNodeWithContentDescription("Take back your decision on “Send the guest list”").assertExists()

        connectionBack()
        compose.onNodeWithContentDescription("Load again").performClick()
        waitUntil { theirs.suggestionStateOf("s2") == "ACCEPTED" }
    }

    @Test
    fun `signing out with a decision that was not sent asks first, and leaves nothing of the suggestion`() {
        open()
        openSuggestions()
        server.down = true
        accept("Send the guest list")
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
        val left = phone.everythingKept()
        for (one in listOf("Send the guest list", "Pay the deposit", "Rehearsal dinner", "ewa@registry.example", "ACCEPT")) {
            assertFalse("the phone still keeps \"$one\"", left.contains(one))
        }
        assertEquals("WAITING", theirs.suggestionStateOf("s2"))
    }

    @Test
    fun `nothing of a suggestion is written to the log when it is decided, sent, refused or let go of`() {
        ShadowLog.clear()
        open()
        openSuggestions()
        accept("Send the guest list")
        waitUntil { theirs.suggestionStateOf("s2") == "ACCEPTED" }
        server.problem("POST /api/suggestions/s1/accept", 400, "bad-request")
        accept("Pay the deposit")
        waitFor("The server did not take your acceptance of “Pay the deposit”. The proposal can be changed in the web app.")
        compose.onNodeWithText("Discard the decision").performClick()
        server.down = true
        reject("Rehearsal dinner")
        waitFor("Waiting to be sent")
        waitUntilItOnlyWaits()
        takeBack("Rehearsal dinner")
        waitUntil { compose.onAllNodesWithText("Waiting to be sent").fetchSemanticsNodes().isEmpty() }
        reject("Rehearsal dinner")
        theirs.decideElsewhere("s3", "ACCEPTED")
        connectionBack()
        compose.onNodeWithContentDescription("Load again").performClick()
        waitFor("“Rehearsal dinner” was already accepted. Your rejection was not kept.")

        val written = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable?.stackTraceToString().orEmpty()}" }
        for (theirs in listOf(
            "Jan from Palace", "jan@palace", "Ewa", "registry", "Deposit for the hall", "Guest list", "deposit by 1 December", "guest list two weeks",
            "Pay the deposit", "Send the guest list", "Rehearsal", "By bank transfer", "access-1", "ACCEPT", "REJECT",
        )) {
            assertFalse("the log holds \"$theirs\"", written.contains(theirs, ignoreCase = true))
        }
    }

    // ---------------------------------------------------------------- how it looks

    @Test
    @Config(qualifiers = PHONE_AT_NIGHT)
    fun `the inbox with decisions reads in the dark as well`() {
        open()
        openSuggestions()
        compose.picture("suggestions-decide-at-night")
        server.down = true
        accept("Send the guest list")
        waitFor("Waiting to be sent")
        pictureWithTakeBack("Send the guest list", "suggestion-decided-waiting-at-night")
        theirs.decideElsewhere("s2", "REJECTED")
        connectionBack()
        compose.onNodeWithContentDescription("Load again").performClick()
        waitFor("“Send the guest list” was already rejected. Your acceptance was not kept.")
        compose.picture("suggestions-decided-otherwise-at-night")
    }

    @Test
    fun `with the largest text nothing is cut off`() {
        open(fontScale = 2f)
        openSuggestions()
        shown("Accept “Pay the deposit”").assertIsDisplayed()
        shown("Reject “Pay the deposit”").assertIsDisplayed()
        compose.picture("suggestions-decide-big-letters")
        server.down = true
        accept("Pay the deposit")
        waitFor("Waiting to be sent")
        shown("Take back your decision on “Pay the deposit”").assertIsDisplayed()
        pictureWithTakeBack("Pay the deposit", "suggestion-decided-waiting-big-letters")
        theirs.decideElsewhere("s1", "REJECTED")
        connectionBack()
        compose.onNodeWithContentDescription("Load again").performClick()
        waitFor("“Pay the deposit” was already rejected. Your acceptance was not kept.")
        compose.picture("suggestions-decided-otherwise-big-letters")
    }
}
