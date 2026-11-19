package io.github.trufelek124.weddingplanner.planner

import android.content.Intent
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.trufelek124.weddingplanner.data.StandIn
import io.github.trufelek124.weddingplanner.data.planner
import io.github.trufelek124.weddingplanner.data.plannerUnreachable
import io.github.trufelek124.weddingplanner.ui.PHONE
import io.github.trufelek124.weddingplanner.ui.PHONE_AT_NIGHT
import io.github.trufelek124.weddingplanner.ui.PlannerTheme
import io.github.trufelek124.weddingplanner.ui.picture
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * The inbox of what the e-mail import proposes: kept on the phone with the rest of the planner, read from More and
 * from Home, a card for each. Accepting and rejecting are in `SuggestionDecisionsScreenTest`. All of the mail, the
 * senders and the people here is made up.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class SuggestionsScreenTest : SignedInPhoneTest() {

    private val link = "https://mail.google.com/mail/u/0/#all/18c0ffee"

    /** A payment with everything: a day, an amount and its kind, a category, a vendor the planner has, notes, and Marek's inbox. */
    private val payment = StandIn.suggestion(
        "s1", "Pay the deposit", kind = "PAYMENT", notes = "By bank transfer", dueDate = "2026-12-01", amount = 250000, paymentKind = "DEPOSIT",
        categoryId = "c1", vendorId = "v1", subject = "Deposit for the hall", passage = "Please pay the deposit by 1 December.", confidence = 62,
        inboxMemberId = StandIn.MAREK, mailLink = link,
    )

    /** A task with no day and no amount, in Anna's inbox. */
    private val task = StandIn.suggestion(
        "s2", "Send the guest list", senderName = "Ewa from the Registry Office", senderAddress = "ewa@registry.example", subject = "Guest list",
        receivedAt = "2026-11-17T09:30:00Z", passage = "We need the guest list two weeks before.", confidence = 80, mailLink = "https://mail.google.com/mail/u/0/#all/18c0ffef",
    )

    /** A step of the plan with both days. */
    private val step = StandIn.suggestion(
        "s3", "Rehearsal dinner", kind = "PLAN_ITEM", startDate = "2026-12-01", dueDate = "2026-12-15", receivedAt = "2026-11-16T08:00:00Z",
        passage = "The dinner is planned for the first half of December.", confidence = 45, mailLink = "https://mail.google.com/mail/u/0/#all/18c0fff0",
    )

    /** A vendor the planner has not got, with its contact details. */
    private val studio = StandIn.suggestion(
        "s4", "Studio Light", kind = "VENDOR", vendorName = "Studio Light", vendorEmail = "hello@studio.example", vendorPhone = "+48 600 000 001",
        vendorWebsite = "https://studio.example", senderName = "", senderAddress = "hello@studio.example", subject = "", receivedAt = "2026-11-15T08:00:00Z",
        passage = "We would be glad to photograph your wedding.", confidence = 90, mailLink = "https://mail.google.com/mail/u/0/#all/18c0fff1",
    )

    /** A kind a newer server knows. */
    private val strange = StandIn.suggestion(
        "s5", "Book the band", kind = "EVENT", receivedAt = "2026-11-14T08:00:00Z", passage = "The band is free that day.", mailLink = "https://mail.google.com/mail/u/0/#all/18c0fff2",
    )

    private val suggestions = listOf(payment, task, step, studio, strange)

    @Before
    fun theirSuggestions() {
        server.planner(
            categories = listOf(StandIn.category("c1", "Venue")),
            vendors = listOf(StandIn.vendor("v1", "Palace on the Hill")),
            suggestions = suggestions,
        )
    }

    private fun openSuggestions() {
        goTo("More")
        compose.onNode(hasText("Suggestions") and hasClickAction()).performClick()
        waitForTheInbox()
    }

    private fun waitForTheInbox() {
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Suggestions") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun started(): Intent? = shadowOf(compose.activity).nextStartedActivity

    private val openTheEmail = hasText("Open the e-mail") and hasClickAction()

    /** The statements of the cards that are in view, top to bottom. */
    private fun statements(): List<String> =
        compose.onAllNodes(hasContentDescription("% sure", substring = true)).fetchSemanticsNodes().map {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull().orEmpty()
        }

    // ---------------------------------------------------------------- More and Home

    @Test
    fun `More has a row for the suggestions that says how many wait`() {
        open()

        goTo("More")

        compose.onNode(line("Suggestions", "5 suggestions wait") and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun `More says that nothing waits, and one suggestion is not many`() {
        server.planner()
        open()
        goTo("More")
        compose.onNode(line("Suggestions", "Nothing waits") and hasClickAction()).assertIsDisplayed()

        server.planner(suggestions = listOf(payment))
        compose.onNodeWithContentDescription("Load again").performClick()

        waitFor("1 suggestion waits")
    }

    @Test
    fun `Home says how many suggestions wait and leads to the inbox, and says nothing where none wait`() {
        open()

        compose.onNode(hasText("5 suggestions wait") and hasClickAction()).assertIsDisplayed()
        compose.onNode(SemanticsMatcher("says what pressing it does") {
            it.config.getOrNull(SemanticsActions.OnClick)?.label == "Open the suggestions"
        }).assertIsDisplayed()
        compose.onNode(hasText("5 suggestions wait")).performClick()

        waitForTheInbox()
        compose.onNode(hasText("More") and isSelectable()).assertIsSelected()
        compose.onNode(line("Payment", "Pay the deposit")).assertIsDisplayed()
    }

    @Test
    fun `Home says nothing about the suggestions where none wait`() {
        server.planner()
        open()

        compose.onNode(hasText("suggestion", substring = true)).assertDoesNotExist()
        compose.onNode(hasText("Nothing waits", substring = true)).assertDoesNotExist()
    }

    // ---------------------------------------------------------------- the cards

    @Test
    fun `a payment says what is proposed, where it comes from and how sure the import is, as one statement`() {
        open()
        openSuggestions()

        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        compose.onNode(hasText("More") and isSelectable()).assertIsSelected()
        compose.onNode(
            line(
                "Payment", "Pay the deposit", "due 1 Dec 2026", "2,500.00\u00a0zł, deposit", "Category: Venue", "Vendor: Palace on the Hill", "By bank transfer",
                "From Jan from Palace on the Hill (jan@palace.example)", "Subject: Deposit for the hall", "Arrived 18 Nov 2026 at 14:05, in Marek's inbox",
                "Please pay the deposit by 1 December.", "62% sure",
            ),
        ).assertIsDisplayed()
        assertEquals(
            "Payment: Pay the deposit. due 1 Dec 2026. 2,500.00\u00a0zł, deposit. Category: Venue. Vendor: Palace on the Hill. Notes: By bank transfer. " +
                "From Jan from Palace on the Hill (jan@palace.example). Subject: Deposit for the hall. Arrived 18 Nov 2026 at 14:05, in Marek's inbox. " +
                "Quotation from the e-mail: “Please pay the deposit by 1 December.”. 62% sure",
            statements().first(),
        )
    }

    @Test
    fun `the newest message comes first, as the server sent them`() {
        server.planner(suggestions = listOf(task, payment))
        open()
        openSuggestions()

        val said = statements()

        assertEquals(2, said.size)
        assertTrue(said[0].startsWith("Task: Send the guest list"))
        assertTrue(said[1].startsWith("Payment: Pay the deposit"))
    }

    @Test
    fun `a task with nothing but its title says only what it has, and a step has its days`() {
        open()
        openSuggestions()

        compose.onNode(
            line(
                "Task", "Send the guest list", "From Ewa from the Registry Office (ewa@registry.example)", "Subject: Guest list",
                "Arrived 17 Nov 2026 at 10:30, in Anna's inbox", "We need the guest list two weeks before.", "80% sure",
            ),
        ).assertIsDisplayed()
        scrollTo(line("Step of the plan"))
        compose.onNode(line("Step of the plan", "Rehearsal dinner", "from 1 Dec 2026 to 15 Dec 2026", "45% sure")).assertIsDisplayed()
    }

    @Test
    fun `a vendor the planner has not got is named with its contact details as text, and one it has is named`() {
        open()
        openSuggestions()

        scrollTo(line("Studio Light", "Vendor: Studio Light (not in the planner yet)"))
        compose.onNode(
            line(
                "Vendor", "Studio Light", "Vendor: Studio Light (not in the planner yet)", "E-mail: hello@studio.example", "Phone: +48 600 000 001",
                "Website: https://studio.example", "From hello@studio.example", "No subject", "We would be glad to photograph your wedding.", "90% sure",
            ),
        ).assertIsDisplayed()
        // What was read of a vendor is shown, and nothing of it can be pressed.
        compose.onNode(hasText("E-mail: hello@studio.example") and hasClickAction()).assertDoesNotExist()
        compose.onNode(hasText("Phone: +48 600 000 001") and hasClickAction()).assertDoesNotExist()
        compose.onNode(hasText("Website: https://studio.example") and hasClickAction()).assertDoesNotExist()
        compose.onNode(line("Vendor: Palace on the Hill")).assertDoesNotExist()
    }

    @Test
    fun `a kind this app does not know reads Other`() {
        open()
        openSuggestions()

        scrollTo(line("Other", "Book the band"))

        compose.onNode(line("Other", "Book the band", "The band is free that day.")).assertIsDisplayed()
    }

    @Test
    fun `the passage is set apart as a quotation, for the eye and for a screen reader`() {
        open()
        openSuggestions()

        compose.onNode(hasText("Please pay the deposit by 1 December.")).assertIsDisplayed()
        val statement = statements().first()
        assertTrue(statement.contains("Quotation from the e-mail: “Please pay the deposit by 1 December.”"))
    }

    // ---------------------------------------------------------------- what came out of a mail is plain text

    @Test
    fun `markup, a link and a line break in a mail are shown letter for letter and nothing in them can be pressed`() {
        val title = "<b>Book</b> the [band](y) http://evil.example"
        val passage = "Click <a href=\"http://evil.example\">here</a>, [x](y) or http://evil.example\\nthen **pay**"
        server.planner(
            suggestions = listOf(
                StandIn.suggestion(
                    "s9", title.replace("\"", "\\\""), notes = "<i>soon</i> http://evil.example/notes", senderName = "<b>Jan</b> [x](y)",
                    senderAddress = "jan@palace.example", subject = "<u>Hello</u> http://evil.example/subject", passage = passage.replace("\"", "\\\""),
                ),
            ),
        )
        open()
        openSuggestions()

        compose.onNode(hasText(title)).assertIsDisplayed()
        compose.onNode(hasText("<i>soon</i> http://evil.example/notes")).assertIsDisplayed()
        compose.onNode(hasText("From <b>Jan</b> [x](y) (jan@palace.example)")).assertIsDisplayed()
        compose.onNode(hasText("Subject: <u>Hello</u> http://evil.example/subject")).assertIsDisplayed()
        compose.onNode(hasText("Click <a href=\"http://evil.example\">here</a>, [x](y) or http://evil.example\nthen **pay**")).assertIsDisplayed()
        // The only things that can be pressed in a card are the button that opens the message and the two that decide.
        compose.onAllNodes(hasText("evil.example", substring = true) and hasClickAction()).assertCountEquals(0)
        compose.onAllNodes(hasText("<", substring = true) and hasClickAction()).assertCountEquals(0)
        compose.onAllNodes(
            hasClickAction() and !hasText("Open the e-mail") and !hasText("Accept") and !hasText("Reject") and !isSelectable() and !hasContentDescription("Back") and
                !hasContentDescription("Load again"),
        ).assertCountEquals(0)
    }

    // ---------------------------------------------------------------- Open the e-mail

    @Test
    fun `Open the e-mail starts the message in Gmail`() {
        server.planner(suggestions = listOf(payment))
        open()
        openSuggestions()

        compose.onAllNodes(openTheEmail).onFirst().performClick()

        val intent = started()!!
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(link, intent.data.toString())
        compose.onNodeWithText("No app on this phone can open that.").assertDoesNotExist()
    }

    private fun String.withLink(address: String): String = replace(Regex("\"mailLink\":\"[^\"]*\""), "\"mailLink\":\"$address\"")

    @Test
    fun `a link that is not an https address at mail_google_com draws no button, whatever else the card says`() {
        open()
        openSuggestions()
        // The same card, one after the other with each link that must not pass; the title says which.
        val bad = listOf(
            "http://mail.google.com/mail/u/0/#all/1", "https://mail.google.com.evil.example/mail/", "https://evil.example/mail.google.com",
            "https://user@mail.google.com/mail/", "javascript:alert(1)", "intent://mail.google.com/#Intent;scheme=https;end", "",
        )
        for ((number, address) in bad.withIndex()) {
            server.planner(suggestions = listOf(StandIn.suggestion("c$number", "Case $number", mailLink = address, passage = "A quotation $number")))
            compose.onNodeWithContentDescription("Load again").performClick()
            waitFor("Case $number")

            compose.onNode(line("Task", "Case $number", "A quotation $number", "62% sure")).assertIsDisplayed()
            compose.onNode(hasText("Open the e-mail")).assertDoesNotExist()
        }
        assertNull(started())

        server.planner(suggestions = listOf(StandIn.suggestion("good", "Case good", mailLink = link)))
        compose.onNodeWithContentDescription("Load again").performClick()
        waitFor("Case good")
        compose.onNode(openTheEmail).assertIsDisplayed()
    }

    @Test
    fun `where only some cards have a link that passes only those have the button`() {
        server.planner(suggestions = listOf(payment, task.withLink("http://mail.google.com/mail/u/0/#all/1")))
        open()
        openSuggestions()

        compose.onNode(line("Payment", "Pay the deposit")).assertIsDisplayed()
        compose.onAllNodes(openTheEmail).assertCountEquals(1)
        scrollTo(line("Task", "Send the guest list"))
        compose.onNode(line("Task", "Send the guest list")).assertIsDisplayed()
    }

    @Test
    fun `with no app to take the message the screen stays and says so, and the next press that works takes the message away`() {
        server.planner(suggestions = listOf(payment))
        open()
        openSuggestions()
        shadowOf(compose.activity.application).checkActivities(true)

        compose.onAllNodes(openTheEmail).onFirst().performClick()

        scrollTo(hasText("No app on this phone can open that."))
        compose.onNodeWithText("No app on this phone can open that.").assertIsDisplayed()
        compose.onNode(hasText("Suggestions") and isHeading()).assertIsDisplayed()
        assertNull(started())

        shadowOf(compose.activity.application).checkActivities(false)
        compose.onAllNodes(openTheEmail).onFirst().performClick()

        compose.onNodeWithText("No app on this phone can open that.").assertDoesNotExist()
        assertNotNull(started())
    }

    // ---------------------------------------------------------------- the place for what can be done

    @Test
    fun `a card has a place for what can be done with it`() {
        server.planner(suggestions = listOf(payment, task))
        runBlocking { phone.container.planner.load() }
        val held = runBlocking { phone.container.planner.planner.first()!! }
        compose.setContent {
            PlannerTheme { SuggestionsContent(held, zone = ZoneId.of("Europe/Warsaw"), actions = { Text("Actions for ${it.id}") }) }
        }

        compose.onNodeWithText("Actions for s1").assertIsDisplayed()
        compose.onNodeWithText("Actions for s2").assertIsDisplayed()
    }

    // ---------------------------------------------------------------- empty, back, without a connection, an older server

    @Test
    fun `with nothing waiting the inbox says so`() {
        server.planner()
        open()
        openSuggestions()

        compose.onNodeWithText("Nothing waits for you.").assertIsDisplayed()
        compose.onNode(hasText("Loaded on", substring = true)).assertIsDisplayed()
        compose.picture("suggestions-empty")
    }

    @Test
    fun `Back goes from the inbox to More, and More on the inbox leads to the list of what More holds`() {
        open()
        openSuggestions()

        compose.onNodeWithContentDescription("Back").performClick()

        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("More") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Suggestions") and hasClickAction()).performClick()
        waitForTheInbox()

        compose.onNode(hasText("More") and isSelectable()).performClick()

        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("More") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Suggestions") and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun `a pull down or Load again reads the inbox again`() {
        server.planner()
        open()
        openSuggestions()
        compose.onNodeWithText("Nothing waits for you.").assertIsDisplayed()

        server.planner(suggestions = listOf(payment))
        compose.onNodeWithContentDescription("Load again").performClick()

        waitFor("Pay the deposit")
        compose.onNodeWithText("Nothing waits for you.").assertDoesNotExist()
    }

    @Test
    fun `without a connection the suggestions are shown from the last load, with from when it is`() {
        aPhoneThatLoadedEarlier()
        server.plannerUnreachable()
        open()
        waitFor("No connection. This is what was loaded on 18 Nov 2026 at 14:05.")

        openSuggestions()

        compose.onNodeWithText("No connection. This is what was loaded on 18 Nov 2026 at 14:05.").assertIsDisplayed()
        compose.onNode(line("Payment", "Pay the deposit", "62% sure")).assertIsDisplayed()
        compose.onAllNodes(openTheEmail).onFirst().assertIsDisplayed()
    }

    @Test
    fun `an older server that does not know the suggestions shows the rest of the planner and an empty inbox`() {
        val rings = StandIn.entry("t1", "Choose the rings")
        server.planner(entries = listOf(rings), home = StandIn.home(next = listOf(rings)))
        server.problem("GET /api/suggestions", 404, "not-found")
        open()

        compose.onNode(hasText("suggestion", substring = true)).assertDoesNotExist()
        waitFor("Choose the rings")
        openSuggestions()

        compose.onNodeWithText("Nothing waits for you.").assertIsDisplayed()
        goTo("More")
        compose.onNode(line("Suggestions", "Nothing waits")).assertIsDisplayed()
    }

    // ---------------------------------------------------------------- pictures, the log, the files

    @Test
    fun `the inbox is written as a picture`() {
        open()
        openSuggestions()
        compose.onNode(line("Payment", "Pay the deposit")).assertIsDisplayed()
        compose.picture("suggestions")
        scrollTo(line("Vendor", "Studio Light"))
        compose.picture("suggestions-list")
    }

    @Test
    fun `Home with suggestions waiting is written as a picture`() {
        open()

        compose.onNode(hasText("5 suggestions wait")).assertIsDisplayed()
        compose.picture("home-with-suggestions")
    }

    @Test
    @Config(qualifiers = PHONE_AT_NIGHT)
    fun `the inbox reads in the dark as well`() {
        open()
        openSuggestions()

        compose.onNode(line("Payment", "Pay the deposit")).assertIsDisplayed()
        compose.picture("suggestions-at-night")
    }

    @Test
    fun `with the largest text nothing is cut off`() {
        open(fontScale = 2f)
        goTo("More")
        compose.onNode(hasText("Suggestions") and hasClickAction()).performClick()
        waitForTheInbox()
        compose.onNode(line("Payment", "Pay the deposit")).assertIsDisplayed()
        compose.picture("suggestions-big-letters")
        scrollTo(line("Step of the plan"))
        compose.picture("suggestions-big-letters-list")
    }

    @Test
    fun `nothing of a suggestion is written to the log`() {
        ShadowLog.clear()
        open()
        openSuggestions()
        compose.onAllNodes(openTheEmail).onFirst().performClick()
        shadowOf(compose.activity.application).checkActivities(true)
        compose.onAllNodes(openTheEmail).onFirst().performClick()
        server.plannerUnreachable()
        compose.onNodeWithContentDescription("Load again").performClick()
        waitFor("No connection. This is what was loaded on 18 Nov 2026 at 14:05.")

        val written = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable?.stackTraceToString().orEmpty()}" }
        for (theirs in listOf(
            "Jan from Palace", "jan@palace", "Ewa", "registry", "ewa@", "Deposit for the hall", "Guest list", "deposit by 1 December", "guest list two weeks",
            "Pay the deposit", "Send the guest list", "Rehearsal", "Studio", "studio.example", "600 000", "Book the band", "mail.google.com", "18c0ff", "evil",
            "By bank transfer", "access-1",
        )) {
            assertFalse("the log holds \"$theirs\"", written.contains(theirs, ignoreCase = true))
        }
    }

    @Test
    fun `signing out leaves nothing of the suggestions on the phone`() {
        open()
        val kept = phone.everythingKept()
        val theirs = listOf(
            "Jan from Palace on the Hill", "jan@palace.example", "Ewa from the Registry Office", "ewa@registry.example", "Deposit for the hall", "Guest list",
            "Please pay the deposit by 1 December.", "We need the guest list two weeks before.", "Pay the deposit", "Send the guest list", "Rehearsal dinner",
            "Studio Light", "hello@studio.example", "By bank transfer", "mail/u/0/#all/18c0ffee", "18c0fff1",
        )
        for (one in theirs) {
            assertTrue("the phone does not keep \"$one\" to begin with", kept.contains(one))
        }
        goTo("More")
        scrollTo(hasText("Sign out"))

        compose.onNodeWithText("Sign out").performClick()

        waitFor("Sign in to see your plan.")
        val left = phone.everythingKept()
        for (one in theirs + listOf("Palace on the Hill", "palace.example", "registry.example", "studio.example", "photograph your wedding", "band is free")) {
            assertFalse("the phone still keeps \"$one\"", left.contains(one))
        }
    }
}
