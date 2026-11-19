package io.github.trufelek124.weddingplanner.planner

import android.content.Intent
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog

/**
 * What the import added by itself, on the phone's screens: the part of the inbox with Undo and Keep, decided at once and kept
 * without a connection, the two numbers on Home and More, "From e-mail" on a task and a step and the page it leads to, and the
 * link only in the signed-in person's own mailbox. All of the mail and the people here is made up.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class AddedByItselfScreenTest : SignedInPhoneTest() {

    private lateinit var theirs: LivingPlanner

    private val link = "https://mail.google.com/mail/u/0/#all/18c0ffee"

    private val entries = listOf(
        StandIn.entry("e1", "Send the guest list", dueDate = "2026-12-01", fromSuggestionId = "a1"),
        StandIn.entry("e2", "Rehearsal dinner", kind = "PLAN_ITEM", startDate = "2026-12-01", dueDate = "2026-12-15", fromSuggestionId = "a2"),
        StandIn.entry("e3", "Book the band", fromSuggestionId = "acc1"),
        StandIn.entry("e4", "Choose the rings"),
        StandIn.entry("e5", "Order the cake", fromSuggestionId = "a3"),
        // Its suggestion is not in the copy: it was undone since, or the copy is older than the mail.
        StandIn.entry("e6", "Hire a photographer", fromSuggestionId = "lost"),
    )

    private val suggestions = listOf(
        StandIn.suggestion(
            "a1", "Send the guest list", state = "ADDED", madeEntryId = "e1", senderName = "Ewa from the Registry Office", senderAddress = "ewa@registry.example",
            subject = "Guest list", receivedAt = "2026-11-17T09:30:00Z", passage = "We need the guest list two weeks before.", confidence = 90, mailLink = link,
        ),
        StandIn.suggestion(
            "a2", "Rehearsal dinner", kind = "PLAN_ITEM", state = "ADDED", madeEntryId = "e2", startDate = "2026-12-01", dueDate = "2026-12-15",
            receivedAt = "2026-11-16T08:00:00Z", passage = "The dinner is planned for the first half of December.", confidence = 88, mailLink = "https://mail.google.com/mail/u/0/#all/18c0fff0",
        ),
        // In the partner's mailbox: it cannot be opened from here.
        StandIn.suggestion(
            "a3", "Order the cake", state = "ADDED", madeEntryId = "e5", inboxMemberId = StandIn.MAREK, senderName = "Studio Sweet", senderAddress = "cakes@sweet.example",
            subject = "Your cake", receivedAt = "2026-11-15T08:00:00Z", passage = "The cake has to be ordered a month ahead.", confidence = 95,
            mailLink = "https://mail.google.com/mail/u/1/#all/18c0fff1",
        ),
        StandIn.suggestion(
            "w1", "Pay the deposit", kind = "PAYMENT", amount = 250000, dueDate = "2026-12-01", paymentKind = "DEPOSIT", passage = "Please pay the deposit by 1 December.",
            mailLink = "https://mail.google.com/mail/u/0/#all/18c0fff2",
        ),
        StandIn.suggestion(
            "acc1", "Book the band", state = "ACCEPTED", madeEntryId = "e3", decidedBy = StandIn.MAREK, decidedAt = "2026-11-17T10:00:00Z",
            senderName = "Band of Brothers", senderAddress = "band@brothers.example", subject = "The band", passage = "The band is free that day.", confidence = 70,
        ),
    )

    @Before
    fun theirStuff() {
        theirs = LivingPlanner(server, entries = entries, suggestions = suggestions)
    }

    private fun openSuggestions() {
        goTo("More")
        compose.onNode(hasText("Suggestions", substring = true) and hasClickAction()).performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Suggestions") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun shown(description: String): SemanticsNodeInteraction {
        scrollTo(hasContentDescription(description))
        compose.waitForIdle()
        return compose.onNodeWithContentDescription(description).performScrollTo()
    }

    private fun undo(title: String) = shown("Undo “$title”").performClick()

    private fun keep(title: String) = shown("Keep “$title”").performClick()

    private fun takeBack(title: String) = shown("Take back your decision on “$title”").performClick()

    private fun connectionBack() {
        server.calls.clear()
        server.down = false
    }

    /** The notice that the phone could not reach the server comes a moment after the decision; what is looked at is not to move under the look. */
    private fun waitForTheNotice() {
        waitUntil { compose.onAllNodes(hasText("No connection", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun pressBackButton() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    private fun goHome() {
        compose.onNode(hasText("Home") and isSelectable()).performClick()
        waitFor("Anna & Marek")
    }

    private fun posted() = server.calls.map { it.route }.filter { it.startsWith("POST /api/suggestions") }

    private fun started(): Intent? = shadowOf(compose.activity).nextStartedActivity

    private fun loadAgain() {
        compose.onNodeWithContentDescription("Load again").performClick()
    }

    private fun onTasks(title: String): Boolean {
        goTo("Tasks")
        return compose.onAllNodes(hasText(title)).fetchSemanticsNodes().isNotEmpty()
    }

    // ---------------------------------------------------------------- the part

    @Test
    fun `the inbox has a part for what was added by itself, with its line and an Undo and a Keep for each, as high as a finger`() {
        open()
        openSuggestions()

        compose.onNode(hasText("Waiting for a decision") and isHeading()).assertIsDisplayed()
        compose.onNode(line("Payment", "Pay the deposit")).assertIsDisplayed()
        scrollTo(hasText("Added by itself") and isHeading())
        compose.onNode(hasText("Added by itself") and isHeading()).assertIsDisplayed()
        compose.onNodeWithText(
            "These are already in your plan. Undo what should not be there; what nobody undoes counts as kept after thirty days.",
        ).assertIsDisplayed()
        for (title in listOf("Send the guest list", "Rehearsal dinner", "Order the cake")) {
            shown("Undo “$title”").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            shown("Keep “$title”").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        }
        // A waiting suggestion keeps its own two buttons; an accepted one has none and is not listed.
        shown("Accept “Pay the deposit”").assertIsDisplayed()
        compose.onAllNodesWithText("Book the band").assertCountEquals(0)
        shown("Undo “Send the guest list”")
        compose.onNode(
            line(
                "Task", "Send the guest list", "From Ewa from the Registry Office (ewa@registry.example)", "Subject: Guest list",
                "Arrived 17 Nov 2026 at 10:30, in Anna's inbox", "We need the guest list two weeks before.", "90% sure",
            ),
        ).assertIsDisplayed()
    }

    @Test
    fun `where nothing was added the part is not drawn, and what waits has no title of its own`() {
        theirs = LivingPlanner(server, entries = emptyList(), suggestions = listOf(StandIn.suggestion("w1", "Pay the deposit")))
        open()
        openSuggestions()

        compose.onNode(line("Task", "Pay the deposit")).assertIsDisplayed()
        compose.onAllNodes(hasText("Added by itself")).assertCountEquals(0)
        compose.onAllNodes(hasText("Waiting for a decision")).assertCountEquals(0)
        compose.onAllNodes(hasText("These are already in your plan", substring = true)).assertCountEquals(0)
    }

    @Test
    fun `only added ones, and nothing waits - the inbox says so above the part`() {
        theirs = LivingPlanner(server, entries = entries, suggestions = suggestions.filter { it.contains("\"state\":\"ADDED\"") })
        open()
        openSuggestions()

        compose.onNodeWithText("Nothing waits for you.").assertIsDisplayed()
        compose.onNode(hasText("Added by itself") and isHeading()).assertExists()
    }

    // ---------------------------------------------------------------- undo and keep

    @Test
    fun `undo with a connection removes the entry from Tasks, the card goes, and the server is told once`() {
        open()
        assertTrue(onTasks("Send the guest list"))
        openSuggestions()

        undo("Send the guest list")

        waitUntil { theirs.suggestionStateOf("a1") == "REJECTED" }
        waitUntil { compose.onAllNodes(hasText("Send the guest list", substring = true)).fetchSemanticsNodes().isEmpty() }
        assertEquals(listOf("POST /api/suggestions/a1/undo"), posted())
        assertEquals("", server.routes("POST /api/suggestions/a1/undo").single().body)
        assertEquals(null, theirs.titleOf("e1"))
        assertFalse(onTasks("Send the guest list"))
        compose.onNodeWithText("Choose the rings").assertIsDisplayed()
    }

    @Test
    fun `undo without a connection takes the entry from Tasks and Plan at once, says so on the card, and can be taken back`() {
        open()
        openSuggestions()
        server.down = true

        undo("Rehearsal dinner")

        waitFor("Undone: “Rehearsal dinner”. It was removed from your plan.")
        waitForTheNotice()
        compose.onNodeWithText("Waiting to be sent").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Undo “Rehearsal dinner”").assertDoesNotExist()
        compose.onNodeWithContentDescription("Keep “Rehearsal dinner”").assertDoesNotExist()
        shown("Take back your decision on “Rehearsal dinner”").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        assertEquals(1, phone.askedToSendLater)
        assertEquals("ADDED", theirs.suggestionStateOf("a2"))
        // The step is gone from the plan at once; its card and its mark with it.
        goTo("Plan")
        compose.onAllNodes(hasText("Rehearsal dinner", substring = true)).assertCountEquals(0)
        // More leads back to the inbox it was left in.
        compose.onNode(hasText("More") and isSelectable()).performClick()
        waitFor("Undone: “Rehearsal dinner”. It was removed from your plan.")
        takeBack("Rehearsal dinner")
        waitUntil { compose.onAllNodesWithText("Waiting to be sent").fetchSemanticsNodes().isEmpty() }
        shown("Undo “Rehearsal dinner”").assertIsDisplayed()
        goTo("Plan")
        compose.onNode(hasText("Rehearsal dinner", substring = true)).assertIsDisplayed()
        connectionBack()
        loadAgain()
        waitUntil { server.routes("GET /api/suggestions").isNotEmpty() }
        assertTrue(posted().isEmpty())
        assertEquals("ADDED", theirs.suggestionStateOf("a2"))
    }

    @Test
    fun `an undo that waited is sent when the connection is back, once, and the entry is gone for good`() {
        open()
        openSuggestions()
        server.down = true
        undo("Send the guest list")
        waitFor("Waiting to be sent")

        connectionBack()
        loadAgain()

        waitUntil { theirs.suggestionStateOf("a1") == "REJECTED" }
        waitUntil { compose.onAllNodes(hasText("Send the guest list", substring = true)).fetchSemanticsNodes().isEmpty() }
        assertEquals(listOf("POST /api/suggestions/a1/undo"), posted())
        assertEquals(null, theirs.titleOf("e1"))
        assertFalse(onTasks("Send the guest list"))
    }

    @Test
    fun `keep with a connection leaves the entry where it is, and the card goes from the part`() {
        open()
        openSuggestions()

        keep("Send the guest list")

        waitUntil { theirs.suggestionStateOf("a1") == "ACCEPTED" }
        waitUntil { compose.onAllNodes(hasText("Send the guest list", substring = true)).fetchSemanticsNodes().isEmpty() }
        assertEquals(listOf("POST /api/suggestions/a1/keep"), posted())
        assertTrue(onTasks("Send the guest list"))
    }

    @Test
    fun `keep without a connection says Kept and that it waits, and is sent later`() {
        open()
        openSuggestions()
        server.down = true

        keep("Send the guest list")

        waitFor("Kept: “Send the guest list”.")
        waitForTheNotice()
        compose.onNodeWithText("Waiting to be sent").performScrollTo().assertIsDisplayed()
        shown("Take back your decision on “Send the guest list”").assertIsDisplayed()
        assertEquals("ADDED", theirs.suggestionStateOf("a1"))
        connectionBack()
        loadAgain()
        waitUntil { theirs.suggestionStateOf("a1") == "ACCEPTED" }
        assertEquals(1, posted().size)
    }

    @Test
    fun `an entry that was changed meanwhile stays, and the notice says so with its title, once, until it is let go of`() {
        open()
        openSuggestions()
        server.down = true
        undo("Send the guest list")
        waitFor("Waiting to be sent")
        theirs.put(StandIn.entry("e1", "Send the guest list", dueDate = "2026-12-01", notes = "Now with the caterers", version = 1, fromSuggestionId = "a1"))
        connectionBack()
        loadAgain()

        waitFor("“Send the guest list” was changed after it was added, so it stays in your plan.")
        assertEquals("ACCEPTED", theirs.suggestionStateOf("a1"))
        compose.onNodeWithText("OK").assertIsDisplayed()
        compose.onAllNodes(hasText("Send the guest list", substring = true) and hasClickAction()).assertCountEquals(0)
        // Once the load is over: the picture is not to catch the spinner.
        waitUntil { compose.onAllNodes(hasContentDescription("Load again") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.picture("added-entry-changed")
        loadAgain()
        waitUntil { server.routes("GET /api/suggestions").size >= 2 }
        compose.onNodeWithText("“Send the guest list” was changed after it was added, so it stays in your plan.").assertIsDisplayed()

        compose.onNodeWithText("OK").performClick()

        waitUntil { compose.onAllNodes(hasText("was changed after it was added", substring = true)).fetchSemanticsNodes().isEmpty() }
        assertEquals(1, posted().size)
        assertTrue(onTasks("Send the guest list"))
    }

    @Test
    fun `what the other owner decided meanwhile is said with the name`() {
        open()
        openSuggestions()
        server.down = true
        keep("Send the guest list")
        waitFor("Waiting to be sent")
        theirs.decideElsewhere("a1", "REJECTED", by = StandIn.MAREK)
        connectionBack()
        loadAgain()

        waitFor("“Send the guest list” was already undone by Marek.")
        compose.onNodeWithText("OK").performClick()
        waitUntil { compose.onAllNodes(hasText("was already undone", substring = true)).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun `an entry the phone itself changed cannot be undone, and the notice says so`() {
        open()
        goTo("Tasks")
        server.down = true
        compose.onNodeWithContentDescription("Done: Send the guest list").performClick()
        waitUntil { runBlocking { phone.container.planner.stillToSend() } == 1 }
        openSuggestions()

        undo("Send the guest list")

        waitFor("“Send the guest list” was changed after it was added, so it stays in your plan.")
        compose.onNodeWithContentDescription("Undo “Send the guest list”").assertDoesNotExist()
        connectionBack()
        loadAgain()
        waitUntil { theirs.statusOf("e1") == "DONE" }
        assertTrue(posted().isEmpty())
        assertEquals("ADDED", theirs.suggestionStateOf("a1"))
    }

    // ---------------------------------------------------------------- the two numbers

    @Test
    fun `Home and More say how many wait and, beside it, how many were added, and never one number of both`() {
        open()

        compose.onNode(hasText("1 suggestion waits")).assertIsDisplayed()
        compose.onNode(hasText("3 entries were added from your e-mail")).assertIsDisplayed()
        goTo("More")
        compose.onNode(line("Suggestions", "1 suggestion waits · 3 added by themselves") and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun `a suggestion with a decision that waits on the phone counts in neither number`() {
        open()
        openSuggestions()
        server.down = true
        undo("Send the guest list")
        waitFor("Waiting to be sent")
        shown("Accept “Pay the deposit”").performClick()
        waitFor("Accepted")

        pressBackButton()
        compose.waitUntil(WAIT) { compose.onAllNodes(line("Suggestions", "Nothing waits · 2 added by themselves") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        goHome()
        compose.onNode(hasText("2 entries were added from your e-mail")).assertIsDisplayed()
        compose.onAllNodes(hasText("suggestion", substring = true)).assertCountEquals(0)
    }

    @Test
    fun `Home says nothing of it where nothing was added, and its line leads to the part`() {
        open()
        compose.onNode(hasText("3 entries were added from your e-mail") and hasClickAction()).performClick()

        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Suggestions") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Added by itself") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Added by itself") and isHeading()).assertIsDisplayed()
        compose.onNodeWithText("More") .assertExists()

        theirs = LivingPlanner(server, entries = emptyList(), suggestions = emptyList())
        pressBackButton()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Anna & Marek")).fetchSemanticsNodes().isNotEmpty() }
        loadAgain()
        waitUntil { compose.onAllNodes(hasText("entries were added", substring = true)).fetchSemanticsNodes().isEmpty() }
        compose.onAllNodes(hasText("added from your e-mail", substring = true)).assertCountEquals(0)
    }

    // ---------------------------------------------------------------- From e-mail

    @Test
    fun `a task a suggestion made says From e-mail in its line, and one made by hand does not`() {
        open()
        goTo("Tasks")

        compose.onNode(line("Send the guest list", "From e-mail")).assertIsDisplayed()
        compose.onNode(line("Book the band", "From e-mail")).assertExists()
        compose.onNode(line("Choose the rings")).assertIsDisplayed()
        compose.onAllNodes(line("Choose the rings", "From e-mail")).assertCountEquals(0)
    }

    @Test
    fun `from the task it leads to its source, with who wrote, the subject, when, whose inbox, the passage, how it stands, Undo and Keep`() {
        open()
        goTo("Tasks")
        compose.onNode(line("Send the guest list", "From e-mail")).performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Change the task")).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithContentDescription("From e-mail: where “Send the guest list” came from").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()

        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Send the guest list") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("More") and isSelectable()).assertExists()
        compose.onNode(
            line(
                "Added by itself", "From Ewa from the Registry Office (ewa@registry.example)", "Subject: Guest list", "Arrived 17 Nov 2026 at 10:30, in Anna's inbox",
                "We need the guest list two weeks before.", "90% sure",
            ),
        ).assertIsDisplayed()
        compose.onNodeWithContentDescription("Undo “Send the guest list”").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Keep “Send the guest list”").assertIsDisplayed()
        compose.picture("added-source")
        pressBackButton()
        waitFor("Change the task")
    }

    @Test
    fun `an undo from the source page works like one from the inbox`() {
        open()
        goTo("Tasks")
        compose.onNode(line("Send the guest list", "From e-mail")).performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Change the task")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("From e-mail: where “Send the guest list” came from").performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Send the guest list") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        server.down = true

        compose.onNodeWithContentDescription("Undo “Send the guest list”").performClick()

        waitFor("Undone: “Send the guest list”. It was removed from your plan.")
        compose.onNodeWithText("Waiting to be sent").assertIsDisplayed()
        compose.onNodeWithContentDescription("Take back your decision on “Send the guest list”").assertIsDisplayed()
    }

    @Test
    fun `a step of the plan says From e-mail and leads to its source`() {
        open()
        goTo("Plan")

        compose.onNodeWithContentDescription("From e-mail: where “Rehearsal dinner” came from").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()

        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Rehearsal dinner") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(line("Added by itself", "Step of the plan", "from 1 Dec 2026 to 15 Dec 2026")).assertIsDisplayed()
        compose.onNodeWithContentDescription("Undo “Rehearsal dinner”").assertIsDisplayed()
    }

    @Test
    fun `one a person accepted leads to a source that says who accepted it and offers nothing to decide`() {
        open()
        goTo("Tasks")
        compose.onNode(line("Book the band", "From e-mail")).performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Change the task")).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithContentDescription("From e-mail: where “Book the band” came from").performClick()

        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Book the band") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Accepted by Marek, 17 Nov 2026 at 11:00")).assertIsDisplayed()
        for (button in listOf("Undo", "Keep", "Accept", "Reject")) {
            compose.onAllNodesWithContentDescription("$button “Book the band”").assertCountEquals(0)
        }
    }

    @Test
    fun `where the copy does not have the suggestion the mark stays and is not a button`() {
        open()
        goTo("Tasks")
        compose.onNode(line("Hire a photographer", "From e-mail")).performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Change the task")).fetchSemanticsNodes().isNotEmpty() }

        compose.onNode(hasText("From e-mail")).assertIsDisplayed()
        compose.onAllNodes(hasText("From e-mail") and hasClickAction()).assertCountEquals(0)
        compose.onAllNodesWithContentDescription("From e-mail", substring = true).assertCountEquals(0)
    }

    // ---------------------------------------------------------------- Open the e-mail

    @Test
    fun `the link is drawn on the cards in the signed-in person's own mailbox, and opens the message`() {
        theirs = LivingPlanner(server, entries = entries, suggestions = suggestions.filter { it.contains("\"id\":\"a1\"") || it.contains("\"id\":\"w1\"") })
        open()
        openSuggestions()

        compose.onAllNodes(hasText("Open the e-mail") and hasClickAction()).assertCountEquals(2)
        compose.onAllNodes(hasText("Open the e-mail") and hasClickAction()).onFirst().performClick()
        assertEquals("https://mail.google.com/mail/u/0/#all/18c0fff2", started()!!.data.toString())
    }

    @Test
    fun `a message in the partner's mailbox draws no link, on the card or on the source page`() {
        theirs = LivingPlanner(server, entries = entries, suggestions = suggestions.filter { it.contains("\"id\":\"a3\"") })
        open()
        openSuggestions()

        shown("Undo “Order the cake”").assertIsDisplayed()
        compose.onNode(line("Task", "Order the cake", "Arrived 15 Nov 2026 at 09:00, in Marek's inbox")).assertExists()
        compose.onAllNodes(hasText("Open the e-mail")).assertCountEquals(0)

        pressBackButton()
        goTo("Tasks")
        compose.onNode(line("Order the cake", "From e-mail")).performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Change the task")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("From e-mail: where “Order the cake” came from").performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Order the cake") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Added by itself")).assertIsDisplayed()
        compose.onAllNodes(hasText("Open the e-mail")).assertCountEquals(0)
    }

    @Test
    fun `on the source page of one in her own mailbox the link is there`() {
        open()
        goTo("Tasks")
        compose.onNode(line("Send the guest list", "From e-mail")).performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Change the task")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("From e-mail: where “Send the guest list” came from").performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Send the guest list") and isHeading()).fetchSemanticsNodes().isNotEmpty() }

        compose.onNode(hasText("Open the e-mail") and hasClickAction()).assertIsDisplayed().performClick()

        assertEquals(link, started()!!.data.toString())
    }

    // ---------------------------------------------------------------- signing out

    @Test
    fun `signing out with an undo that was not sent asks first, counts it, and leaves nothing of the mail`() {
        open()
        openSuggestions()
        server.down = true
        undo("Send the guest list")
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
        assertEquals(0, runBlocking { phone.container.planner.stillToSend() })
        val left = phone.everythingKept()
        for (one in listOf("Send the guest list", "Rehearsal dinner", "ewa@registry.example", "guest list two weeks", "UNDO")) {
            assertFalse("the phone still keeps \"$one\"", left.contains(one))
        }
        assertEquals("ADDED", theirs.suggestionStateOf("a1"))
    }

    // ---------------------------------------------------------------- the log

    @Test
    fun `nothing of what was added is written to the log, whatever happens to it`() {
        ShadowLog.clear()
        open()
        openSuggestions()
        keep("Send the guest list")
        waitUntil { theirs.suggestionStateOf("a1") == "ACCEPTED" }
        waitUntil { compose.onAllNodes(hasText("Send the guest list", substring = true)).fetchSemanticsNodes().isEmpty() }
        server.down = true
        undo("Rehearsal dinner")
        waitFor("Waiting to be sent")
        waitForTheNotice()
        takeBack("Rehearsal dinner")
        waitUntil { compose.onAllNodesWithText("Waiting to be sent").fetchSemanticsNodes().isEmpty() }
        undo("Rehearsal dinner")
        theirs.put(StandIn.entry("e2", "Rehearsal dinner", kind = "PLAN_ITEM", status = "DONE", version = 1, fromSuggestionId = "a2"))
        connectionBack()
        loadAgain()
        waitFor("“Rehearsal dinner” was changed after it was added, so it stays in your plan.")
        compose.onNodeWithText("OK").performClick()
        server.problem("POST /api/suggestions/a3/keep", 400, "bad-request")
        keep("Order the cake")
        waitFor("The server did not take your decision to keep “Order the cake”.")
        compose.onNodeWithText("Discard the decision").performClick()

        val written = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable?.stackTraceToString().orEmpty()}" }
        for (theirs in listOf(
            "Ewa", "registry", "Guest list", "guest list two weeks", "Send the guest list", "Rehearsal", "Order the cake", "Studio Sweet", "cakes@sweet", "cake has to be",
            "mail.google.com", "access-1", "KEEP", "UNDO",
        )) {
            assertFalse("the log holds \"$theirs\"", written.contains(theirs, ignoreCase = true))
        }
    }

    // ---------------------------------------------------------------- how it looks

    private fun pictures(suffix: String) {
        compose.picture("added-home$suffix")
        openSuggestions()
        scrollTo(hasText("Added by itself") and isHeading())
        compose.waitForIdle()
        compose.picture("added-inbox$suffix")
        server.down = true
        undo("Send the guest list")
        waitFor("Waiting to be sent")
        waitForTheNotice()
        shown("Take back your decision on “Send the guest list”")
        compose.waitForIdle()
        compose.picture("added-decision-waiting$suffix")
        pressBackButton()
        goTo("Tasks")
        compose.waitForIdle()
        compose.picture("added-task-from-email$suffix")
        goTo("Plan")
        compose.waitForIdle()
        compose.picture("added-plan-from-email$suffix")
    }

    @Test
    fun `the screens of what was added, by day`() {
        open()
        pictures("")
    }

    @Test
    @Config(qualifiers = PHONE_AT_NIGHT)
    fun `the screens of what was added, at night`() {
        open()
        pictures("-at-night")
        goTo("Tasks")
        compose.onNode(line("Book the band", "From e-mail")).performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Change the task")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("From e-mail: where “Book the band” came from").performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Book the band") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        compose.picture("added-source-at-night")
    }

    @Test
    fun `with the largest text nothing is cut off`() {
        open(fontScale = 2f)
        pictures("-big-letters")
        goTo("Tasks")
        compose.onNode(line("Book the band", "From e-mail")).assertExists()
    }
}
