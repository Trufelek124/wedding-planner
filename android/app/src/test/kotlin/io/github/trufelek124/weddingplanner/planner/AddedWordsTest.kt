package io.github.trufelek124.weddingplanner.planner

import io.github.trufelek124.weddingplanner.data.Budget
import io.github.trufelek124.weddingplanner.data.DecidedAs
import io.github.trufelek124.weddingplanner.data.Decision
import io.github.trufelek124.weddingplanner.data.Entry
import io.github.trufelek124.weddingplanner.data.Kind
import io.github.trufelek124.weddingplanner.data.Member
import io.github.trufelek124.weddingplanner.data.Planner
import io.github.trufelek124.weddingplanner.data.Priority
import io.github.trufelek124.weddingplanner.data.Status
import io.github.trufelek124.weddingplanner.data.Suggestion
import io.github.trufelek124.weddingplanner.data.SuggestionKind
import io.github.trufelek124.weddingplanner.data.SuggestionState
import io.github.trufelek124.weddingplanner.data.SuggestionTrouble
import io.github.trufelek124.weddingplanner.data.SuggestionTroubleKind
import io.github.trufelek124.weddingplanner.data.Waiting
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

/**
 * What the phone says of what the import added by itself: the words are the web's (the table in the task's brief), and each is
 * checked here without a screen. Everyone and everything is made up.
 */
@RunWith(RobolectricTestRunner::class)
class AddedWordsTest {

    private val warsaw = ZoneId.of("Europe/Warsaw")

    private val planner = Planner(
        loadedAt = Instant.parse("2026-11-18T13:05:00Z"), weddingDate = LocalDate.of(2027, 6, 19), currency = "PLN", timeZone = "Europe/Warsaw",
        today = LocalDate.of(2026, 11, 18), daysLeft = 213, members = listOf(Member("m1", "Anna", true), Member("m2", "Marek", false)),
        entries = emptyList(), categories = emptyMap(), vendors = emptyMap(), overdue = emptyList(), next = emptyList(), nextPayments = emptyList(),
        budget = Budget(null, 0, 0, 0),
    )

    private fun suggestion(
        state: SuggestionState = SuggestionState.ADDED,
        title: String = "Send the guest list",
        decidedBy: String? = null,
        decidedAt: Instant? = null,
        decided: Decision? = null,
        inbox: String = "m1",
    ) = Suggestion(
        id = "a1", kind = SuggestionKind.TASK, title = title, notes = "", startDate = null, dueDate = null, amount = null, paymentKind = null,
        categoryId = null, vendorId = null, vendorName = null, vendorEmail = null, vendorPhone = null, vendorWebsite = null,
        senderName = "Ewa from the Registry Office", senderAddress = "ewa@registry.example", subject = "Guest list", receivedAt = Instant.parse("2026-11-17T09:30:00Z"),
        inboxMemberId = inbox, mailLink = "https://mail.google.com/mail/u/0/#all/18c0ffee", passage = "We need the guest list.", confidence = 90,
        state = state, madeEntryId = "e1", decidedBy = decidedBy, decidedAt = decidedAt, decided = decided,
    )

    private fun trouble(kind: SuggestionTroubleKind, decision: Decision, decidedAs: DecidedAs? = null, by: String? = null, title: String = "Send the guest list") =
        SuggestionTrouble("a1", title, kind, decision, decidedAs, by)

    @Test
    fun `the part, its line and its two buttons say what the web says`() {
        assertEquals("Added by itself", ADDED_PART)
        assertEquals(
            "These are already in your plan. Undo what should not be there; what nobody undoes counts as kept after thirty days.",
            ADDED_INTRO,
        )
        assertEquals("Undo", decideButton(Decision.UNDO))
        assertEquals("Keep", decideButton(Decision.KEEP))
        assertEquals("Accept", decideButton(Decision.ACCEPT))
        assertEquals("Reject", decideButton(Decision.REJECT))
        assertEquals("Undo “Send the guest list”", decideDescription(Decision.UNDO, suggestion()))
        assertEquals("Keep “Send the guest list”", decideDescription(Decision.KEEP, suggestion()))
        assertEquals("From e-mail", FROM_EMAIL)
    }

    @Test
    fun `a decision that waits on the phone says what it came to, with the title`() {
        assertEquals("Kept: “Send the guest list”.", decidedOnCard(Decision.KEEP, suggestion()))
        assertEquals("Undone: “Send the guest list”. It was removed from your plan.", decidedOnCard(Decision.UNDO, suggestion()))
        assertEquals("Accepted", decidedOnCard(Decision.ACCEPT, suggestion()))
        assertEquals("Rejected", decidedOnCard(Decision.REJECT, suggestion()))
        assertEquals("Undone: “Suggestion without a title”. It was removed from your plan.", decidedOnCard(Decision.UNDO, suggestion(title = "  ")))
    }

    @Test
    fun `Home says how many were added in one line of its own, and says nothing where none was`() {
        assertNull(addedOnHome(0))
        assertEquals("1 entry was added from your e-mail", addedOnHome(1))
        assertEquals("2 entries were added from your e-mail", addedOnHome(2))
        assertEquals("12 entries were added from your e-mail", addedOnHome(12))
    }

    @Test
    fun `More says the two numbers beside each other and never one number made of both`() {
        assertEquals("Nothing waits", suggestionsOnMore(0, 0))
        assertEquals("3 suggestions wait", suggestionsOnMore(3, 0))
        assertEquals("1 suggestion waits · 2 added by themselves", suggestionsOnMore(1, 2))
        assertEquals("Nothing waits · 1 added by themselves", suggestionsOnMore(0, 1))
        assertEquals("2 added by themselves", addedMark(2))
    }

    @Test
    fun `how an added suggestion stands, as the source page says it`() {
        val decided = Instant.parse("2026-11-18T13:05:00Z")
        assertEquals("Added by itself", suggestionStanding(suggestion(), planner, warsaw))
        assertEquals("Accepted by Marek, 18 Nov 2026 at 14:05", suggestionStanding(suggestion(SuggestionState.ACCEPTED, decidedBy = "m2", decidedAt = decided), planner, warsaw))
        assertEquals("Accepted by you, 18 Nov 2026 at 14:05", suggestionStanding(suggestion(SuggestionState.ACCEPTED, decidedBy = "m1", decidedAt = decided), planner, warsaw))
        assertEquals("Accepted by you", suggestionStanding(suggestion(SuggestionState.ACCEPTED, decidedBy = "m1"), planner, warsaw))
        assertEquals("Added by itself; kept after thirty days", suggestionStanding(suggestion(SuggestionState.ACCEPTED), planner, warsaw))
        assertNull(suggestionStanding(suggestion(SuggestionState.WAITING), planner, warsaw))
    }

    @Test
    fun `the mark on an entry is read out with what it leads to`() {
        assertEquals("From e-mail: where “Send the guest list” came from", fromEmailDescription(suggestion()))
    }

    @Test
    fun `an undo of an entry that was changed says it stays in the plan`() {
        assertEquals(
            "“Send the guest list” was changed after it was added, so it stays in your plan.",
            suggestionTroubleInWords(trouble(SuggestionTroubleKind.ENTRY_CHANGED, Decision.UNDO)),
        )
        assertEquals("OK", suggestionTroubleButton(trouble(SuggestionTroubleKind.ENTRY_CHANGED, Decision.UNDO)))
    }

    @Test
    fun `a keep or an undo that was decided the other way says who decided`() {
        fun said(decision: Decision, decidedAs: DecidedAs?, by: String?) =
            suggestionTroubleInWords(trouble(SuggestionTroubleKind.DECIDED_OTHERWISE, decision, decidedAs, by))
        assertEquals("“Send the guest list” was already kept by Marek.", said(Decision.UNDO, DecidedAs.KEPT, "Marek"))
        assertEquals("“Send the guest list” was already undone by Marek.", said(Decision.KEEP, DecidedAs.UNDONE, "Marek"))
        assertEquals("“Send the guest list” counted as kept after thirty days.", said(Decision.UNDO, DecidedAs.KEPT_AFTER_THIRTY_DAYS, null))
        // Who it was is not known: nothing is made up.
        assertEquals("“Send the guest list” was already kept.", said(Decision.UNDO, DecidedAs.KEPT, null))
        assertEquals("The server did not take your undo of “Send the guest list”.", said(Decision.UNDO, null, null))
    }

    @Test
    fun `a keep or an undo that is gone or not taken names the suggestion`() {
        assertEquals(
            "“Send the guest list” no longer exists, so your undo was not sent.",
            suggestionTroubleInWords(trouble(SuggestionTroubleKind.GONE, Decision.UNDO)),
        )
        assertEquals(
            "“Send the guest list” no longer exists, so your decision to keep it was not sent.",
            suggestionTroubleInWords(trouble(SuggestionTroubleKind.GONE, Decision.KEEP)),
        )
        assertEquals(
            "The server did not take your undo of “Send the guest list”.",
            suggestionTroubleInWords(trouble(SuggestionTroubleKind.REFUSED, Decision.UNDO)),
        )
        assertEquals(
            "The server did not take your decision to keep “Send the guest list”.",
            suggestionTroubleInWords(trouble(SuggestionTroubleKind.REFUSED, Decision.KEEP)),
        )
        assertEquals("Discard the decision", suggestionTroubleButton(trouble(SuggestionTroubleKind.REFUSED, Decision.UNDO)))
        // The notices for accepting and rejecting are as they were.
        assertEquals(
            "“Send the guest list” was already rejected. Your acceptance was not kept.",
            suggestionTroubleInWords(trouble(SuggestionTroubleKind.DECIDED_OTHERWISE, Decision.ACCEPT)),
        )
    }

    @Test
    fun `the decisions are paired, and only keep and undo are for what was added`() {
        assertEquals(Decision.UNDO, Decision.KEEP.other)
        assertEquals(Decision.KEEP, Decision.UNDO.other)
        assertEquals(Decision.REJECT, Decision.ACCEPT.other)
        assertTrue(Decision.KEEP.ofAdded && Decision.UNDO.ofAdded)
        assertFalse(Decision.ACCEPT.ofAdded || Decision.REJECT.ofAdded)
    }

    @Test
    fun `the facts of an entry carry the mark first of the plain ones, in words and weight, and not where it is drawn on its own`() {
        val entry = Entry(
            id = "e1", kind = Kind.TASK, title = "Send the guest list", notes = "", categoryId = null, startDate = null, dueDate = LocalDate.of(2026, 12, 1),
            status = Status.NOT_STARTED, priority = Priority.NORMAL, priorityReason = "DUE_LATER", parentId = null, vendorId = null, amount = null,
            paymentKind = null, paidOn = null, assigneeIds = emptyList(), taskCount = 0, version = 0, fromSuggestionId = "a1",
        )

        assertEquals(Fact("From e-mail", strong = true), factsOf(entry, planner).first())
        assertTrue(factsOf(entry, planner, withSource = false).none { it.text == "From e-mail" })
        assertTrue(factsOf(entry.copy(fromSuggestionId = null), planner).none { it.text == "From e-mail" })
        // A change that waits still comes before it.
        assertEquals("Waiting to be sent", factsOf(entry.copy(waiting = Waiting.TO_BE_SENT), planner).first().text)
    }

    @Test
    fun `a message is opened only from the signed-in person's own mailbox`() {
        assertTrue(planner.inYourInbox(suggestion(inbox = "m1")))
        assertFalse(planner.inYourInbox(suggestion(inbox = "m2")))
        assertFalse(planner.inYourInbox(suggestion(inbox = "nobody")))
    }
}
