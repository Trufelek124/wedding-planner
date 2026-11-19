package io.github.trufelek124.weddingplanner.planner

import io.github.trufelek124.weddingplanner.data.Decision
import io.github.trufelek124.weddingplanner.data.Member
import io.github.trufelek124.weddingplanner.data.Planner
import io.github.trufelek124.weddingplanner.data.Suggestion
import io.github.trufelek124.weddingplanner.data.SuggestionKind
import io.github.trufelek124.weddingplanner.data.SuggestionTrouble
import io.github.trufelek124.weddingplanner.data.SuggestionTroubleKind
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

/** What a suggestion of the e-mail import says on the phone. Everyone and everything here is made up. */
@RunWith(RobolectricTestRunner::class)
class SuggestionWordsTest {

    private val warsaw = ZoneId.of("Europe/Warsaw")

    private val planner = Planner(
        loadedAt = Instant.parse("2026-11-18T13:05:00Z"), weddingDate = LocalDate.of(2027, 6, 19), currency = "PLN", timeZone = "Europe/Warsaw",
        today = LocalDate.of(2026, 11, 18), daysLeft = 213, members = listOf(Member("m1", "Anna", true), Member("m2", "Marek", false)),
        entries = emptyList(), categories = mapOf("c1" to "Venue"), vendors = mapOf("v1" to "Palace on the Hill"), overdue = emptyList(), next = emptyList(),
        nextPayments = emptyList(), budget = io.github.trufelek124.weddingplanner.data.Budget(null, 0, 0, 0),
    )

    private fun suggestion(
        kind: SuggestionKind = SuggestionKind.TASK,
        title: String = "Pay the deposit",
        notes: String = "",
        startDate: LocalDate? = null,
        dueDate: LocalDate? = null,
        amount: Long? = null,
        paymentKind: String? = null,
        categoryId: String? = null,
        vendorId: String? = null,
        vendorName: String? = null,
        vendorEmail: String? = null,
        vendorPhone: String? = null,
        vendorWebsite: String? = null,
        senderName: String = "Jan from Palace on the Hill",
        senderAddress: String = "jan@palace.example",
        subject: String = "Deposit for the hall",
        receivedAt: Instant? = Instant.parse("2026-11-18T13:05:00Z"),
        inboxMemberId: String = "m1",
        passage: String = "Please pay the deposit by 1 December.",
        confidence: Int = 62,
    ) = Suggestion(
        id = "s1", kind = kind, title = title, notes = notes, startDate = startDate, dueDate = dueDate, amount = amount, paymentKind = paymentKind,
        categoryId = categoryId, vendorId = vendorId, vendorName = vendorName, vendorEmail = vendorEmail, vendorPhone = vendorPhone,
        vendorWebsite = vendorWebsite, senderName = senderName, senderAddress = senderAddress, subject = subject, receivedAt = receivedAt,
        inboxMemberId = inboxMemberId, mailLink = "https://mail.google.com/mail/u/0/#all/18c0ffee", passage = passage, confidence = confidence,
    )

    @Test
    fun `each kind has its own name, and a kind this app does not know is Other`() {
        assertEquals("Task", suggestionKindLabel(SuggestionKind.TASK))
        assertEquals("Payment", suggestionKindLabel(SuggestionKind.PAYMENT))
        assertEquals("Step of the plan", suggestionKindLabel(SuggestionKind.PLAN_ITEM))
        assertEquals("Vendor", suggestionKindLabel(SuggestionKind.VENDOR))
        assertEquals("Other", suggestionKindLabel(SuggestionKind.OTHER))
    }

    @Test
    fun `a task is due on a day, a step with both days runs from one to the other`() {
        assertEquals("due 1 Dec 2026", suggestionDays(suggestion(dueDate = LocalDate.of(2026, 12, 1))))
        assertEquals(
            "from 1 Dec 2026 to 15 Dec 2026",
            suggestionDays(suggestion(SuggestionKind.PLAN_ITEM, startDate = LocalDate.of(2026, 12, 1), dueDate = LocalDate.of(2026, 12, 15))),
        )
        assertEquals("due 15 Dec 2026", suggestionDays(suggestion(SuggestionKind.PLAN_ITEM, dueDate = LocalDate.of(2026, 12, 15))))
        assertEquals("from 1 Dec 2026", suggestionDays(suggestion(SuggestionKind.PLAN_ITEM, startDate = LocalDate.of(2026, 12, 1))))
        assertNull(suggestionDays(suggestion()))
    }

    @Test
    fun `an amount is told with money and the kind of payment`() {
        assertEquals("2,500.00 zł, deposit", suggestionAmount(suggestion(SuggestionKind.PAYMENT, amount = 250000, paymentKind = "DEPOSIT"), "PLN"))
        assertEquals("1,000.00 zł, instalment", suggestionAmount(suggestion(amount = 100000, paymentKind = "INSTALMENT"), "PLN"))
        assertEquals("500.00 zł, final payment", suggestionAmount(suggestion(amount = 50000, paymentKind = "FINAL"), "PLN"))
        assertEquals("500.00 zł, payment", suggestionAmount(suggestion(amount = 50000, paymentKind = "SOMETHING_NEW"), "PLN"))
        assertEquals("500.00 zł", suggestionAmount(suggestion(amount = 50000), "PLN"))
        assertNull(suggestionAmount(suggestion(), "PLN"))
    }

    @Test
    fun `the category is named, and one the planner does not have says nothing`() {
        assertEquals("Category: Venue", suggestionCategory(suggestion(categoryId = "c1"), planner))
        assertNull(suggestionCategory(suggestion(categoryId = "gone"), planner))
        assertNull(suggestionCategory(suggestion(), planner))
    }

    @Test
    fun `a vendor the planner has is named, and one it has not is named as not in the planner yet`() {
        assertEquals("Vendor: Palace on the Hill", suggestionVendor(suggestion(vendorId = "v1"), planner))
        assertEquals("Vendor: Palace on the Hill", suggestionVendor(suggestion(vendorId = "v1", vendorName = "Another name"), planner))
        assertEquals("Vendor: Studio Light (not in the planner yet)", suggestionVendor(suggestion(vendorName = "Studio Light"), planner))
        assertEquals("Vendor: Studio Light (not in the planner yet)", suggestionVendor(suggestion(vendorId = "gone", vendorName = "Studio Light"), planner))
        assertNull(suggestionVendor(suggestion(vendorId = "gone"), planner))
        assertNull(suggestionVendor(suggestion(vendorName = "  "), planner))
        assertNull(suggestionVendor(suggestion(), planner))
    }

    @Test
    fun `a vendor's contact details are shown one by one, as text`() {
        val vendor = suggestion(SuggestionKind.VENDOR, vendorEmail = "hello@studio.example", vendorPhone = "+48 600 000 001", vendorWebsite = "https://studio.example")

        assertEquals(listOf("E-mail: hello@studio.example", "Phone: +48 600 000 001", "Website: https://studio.example"), suggestionContact(vendor))
        assertEquals(listOf("Phone: +48 600 000 001"), suggestionContact(suggestion(vendorPhone = "+48 600 000 001", vendorEmail = "", vendorWebsite = " ")))
        assertTrue(suggestionContact(suggestion()).isEmpty())
    }

    @Test
    fun `the facts come in the order a person reads them, and a bare suggestion has none`() {
        val payment = suggestion(
            SuggestionKind.PAYMENT, dueDate = LocalDate.of(2026, 12, 1), amount = 250000, paymentKind = "DEPOSIT", categoryId = "c1", vendorId = "v1",
            vendorEmail = "jan@palace.example",
        )

        assertEquals(
            listOf("due 1 Dec 2026", "2,500.00 zł, deposit", "Category: Venue", "Vendor: Palace on the Hill", "E-mail: jan@palace.example"),
            suggestionFacts(payment, planner),
        )
        assertTrue(suggestionFacts(suggestion(), planner).isEmpty())
    }

    @Test
    fun `where it comes from names the sender with the address, the subject, when it arrived and whose inbox it was in`() {
        assertEquals("Jan from Palace on the Hill (jan@palace.example)", suggestionSender(suggestion()))
        assertEquals("jan@palace.example", suggestionSender(suggestion(senderName = " ")))
        assertEquals("Jan", suggestionSender(suggestion(senderName = "Jan", senderAddress = "")))
        assertEquals("Unknown sender", suggestionSender(suggestion(senderName = "", senderAddress = "")))
        assertEquals("Subject: Deposit for the hall", suggestionSubject(suggestion()))
        assertEquals("No subject", suggestionSubject(suggestion(subject = "")))
        assertEquals("Arrived 18 Nov 2026 at 14:05", suggestionArrived(suggestion(), warsaw))
        assertEquals("Arrived 18 Nov 2026 at 13:05", suggestionArrived(suggestion(), ZoneId.of("UTC")))
        assertNull(suggestionArrived(suggestion(receivedAt = null), warsaw))
        assertEquals("in Anna's inbox", suggestionInbox(suggestion(), planner))
        assertEquals("in Marek's inbox", suggestionInbox(suggestion(inboxMemberId = "m2"), planner))
        assertNull(suggestionInbox(suggestion(inboxMemberId = "nobody"), planner))
        assertEquals(
            listOf("From Jan from Palace on the Hill (jan@palace.example)", "Subject: Deposit for the hall", "Arrived 18 Nov 2026 at 14:05, in Anna's inbox"),
            suggestionSource(suggestion(), planner, warsaw),
        )
        assertEquals(
            listOf("From jan@palace.example", "No subject"),
            suggestionSource(suggestion(senderName = "", subject = "", receivedAt = null, inboxMemberId = "nobody"), planner, warsaw),
        )
    }

    @Test
    fun `how sure the import is, as a whole percentage kept between 0 and 100`() {
        assertEquals("62% sure", confidenceInWords(62))
        assertEquals("0% sure", confidenceInWords(0))
        assertEquals("100% sure", confidenceInWords(100))
        assertEquals("100% sure", confidenceInWords(140))
        assertEquals("0% sure", confidenceInWords(-5))
    }

    @Test
    fun `More and Home say how many suggestions wait`() {
        assertEquals("3 suggestions wait", suggestionsSummary(3))
        assertEquals("2 suggestions wait", suggestionsSummary(2))
        assertEquals("1 suggestion waits", suggestionsSummary(1))
        assertEquals("Nothing waits", suggestionsSummary(0))
        assertEquals("3 suggestions wait", suggestionsOnHome(3))
        assertEquals("1 suggestion waits", suggestionsOnHome(1))
        assertNull(suggestionsOnHome(0))
    }

    @Test
    fun `a suggestion without a title is still named`() {
        assertEquals("Pay the deposit", suggestionName(suggestion()))
        assertEquals("Suggestion without a title", suggestionName(suggestion(title = "  ")))
    }

    @Test
    fun `a card is one statement that says what it proposes, where it comes from, the passage as a quotation and how sure the import is`() {
        val card = suggestion(
            SuggestionKind.PAYMENT, dueDate = LocalDate.of(2026, 12, 1), amount = 250000, paymentKind = "DEPOSIT", vendorId = "v1", notes = "Bank transfer",
        )

        assertEquals(
            "Payment: Pay the deposit. due 1 Dec 2026. 2,500.00 zł, deposit. Vendor: Palace on the Hill. Notes: Bank transfer. " +
                "From Jan from Palace on the Hill (jan@palace.example). Subject: Deposit for the hall. Arrived 18 Nov 2026 at 14:05, in Anna's inbox. " +
                "Quotation from the e-mail: “Please pay the deposit by 1 December.”. 62% sure",
            suggestionInWords(card, planner, warsaw),
        )
        assertFalse(suggestionInWords(suggestion(passage = ""), planner, warsaw).contains("Quotation"))
    }

    @Test
    fun `what came out of a mail is told as it is, with its markup`() {
        val tricky = suggestion(title = "<b>Pay</b> now", passage = "see http://evil.example and [x](y)", senderName = "<i>Jan</i>")

        val said = suggestionInWords(tricky, planner, warsaw)

        assertTrue(said.contains("<b>Pay</b> now"))
        assertTrue(said.contains("http://evil.example and [x](y)"))
        assertTrue(said.contains("<i>Jan</i>"))
    }

    @Test
    fun `a suggestion prints only its id`() {
        val printed = suggestion(vendorName = "Studio Light", notes = "Bank transfer").toString()

        assertEquals("Suggestion[id=s1]", printed)
    }

    @Test
    fun `the buttons say what they do to which suggestion`() {
        assertEquals("Accept", decideButton(Decision.ACCEPT))
        assertEquals("Reject", decideButton(Decision.REJECT))
        assertEquals("Accept “Pay the deposit”", decideDescription(Decision.ACCEPT, suggestion()))
        assertEquals("Reject “Pay the deposit”", decideDescription(Decision.REJECT, suggestion()))
        assertEquals("Take back your decision on “Pay the deposit”", takeBackDescription(suggestion()))
        // A mail that gave no title still gives the button a name.
        assertEquals("Accept “Suggestion without a title”", decideDescription(Decision.ACCEPT, suggestion(title = " ")))
        assertEquals("Accepted", decidedInWords(Decision.ACCEPT))
        assertEquals("Rejected", decidedInWords(Decision.REJECT))
    }

    private fun trouble(kind: SuggestionTroubleKind, decision: Decision, title: String = "Pay the deposit") = SuggestionTrouble("s1", title, kind, decision)

    @Test
    fun `when the other decision was made first the notice says which, the opposite of what the phone sent`() {
        assertEquals(
            "“Pay the deposit” was already rejected. Your acceptance was not kept.",
            suggestionTroubleInWords(trouble(SuggestionTroubleKind.DECIDED_OTHERWISE, Decision.ACCEPT)),
        )
        assertEquals(
            "“Pay the deposit” was already accepted. Your rejection was not kept.",
            suggestionTroubleInWords(trouble(SuggestionTroubleKind.DECIDED_OTHERWISE, Decision.REJECT)),
        )
        assertEquals("OK", suggestionTroubleButton(trouble(SuggestionTroubleKind.DECIDED_OTHERWISE, Decision.ACCEPT)))
    }

    @Test
    fun `a suggestion that is gone is said to be, and a refused acceptance points to the web`() {
        assertEquals(
            "“Pay the deposit” no longer exists, so your acceptance was not sent.",
            suggestionTroubleInWords(trouble(SuggestionTroubleKind.GONE, Decision.ACCEPT)),
        )
        assertEquals(
            "“Pay the deposit” no longer exists, so your rejection was not sent.",
            suggestionTroubleInWords(trouble(SuggestionTroubleKind.GONE, Decision.REJECT)),
        )
        assertEquals("OK", suggestionTroubleButton(trouble(SuggestionTroubleKind.GONE, Decision.REJECT)))
        assertEquals(
            "The server did not take your acceptance of “Pay the deposit”. The proposal can be changed in the web app.",
            suggestionTroubleInWords(trouble(SuggestionTroubleKind.REFUSED, Decision.ACCEPT)),
        )
        assertEquals(
            "The server did not take your rejection of “Pay the deposit”.",
            suggestionTroubleInWords(trouble(SuggestionTroubleKind.REFUSED, Decision.REJECT)),
        )
        assertEquals("Discard the decision", suggestionTroubleButton(trouble(SuggestionTroubleKind.REFUSED, Decision.ACCEPT)))
        assertTrue(suggestionTroubleInWords(trouble(SuggestionTroubleKind.GONE, Decision.ACCEPT, title = "")).contains("“Suggestion without a title”"))
    }

    @Test
    fun `a trouble prints only the id of its suggestion`() {
        assertEquals("SuggestionTrouble[suggestionId=s1]", trouble(SuggestionTroubleKind.GONE, Decision.ACCEPT).toString())
    }
}
