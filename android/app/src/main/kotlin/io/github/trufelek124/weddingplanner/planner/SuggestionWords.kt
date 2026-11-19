package io.github.trufelek124.weddingplanner.planner

import io.github.trufelek124.weddingplanner.data.Decision
import io.github.trufelek124.weddingplanner.data.Planner
import io.github.trufelek124.weddingplanner.data.Suggestion
import io.github.trufelek124.weddingplanner.data.SuggestionKind
import io.github.trufelek124.weddingplanner.data.SuggestionTrouble
import io.github.trufelek124.weddingplanner.data.SuggestionTroubleKind
import io.github.trufelek124.weddingplanner.money.money
import java.time.ZoneId

/*
 * How the suggestions of the e-mail import read on the phone. Each function says what one place on the screen says,
 * so that the words are tested without a screen. What came out of a mail (the title, the notes, the sender, the
 * subject, the passage, a vendor's name and contact details) is somebody else's text: it stands in what a person
 * reads, as it is, and is never taken for anything but plain text.
 */

/** What accepting the suggestion would make. */
fun suggestionKindLabel(kind: SuggestionKind): String = when (kind) {
    SuggestionKind.TASK -> "Task"
    SuggestionKind.PAYMENT -> "Payment"
    SuggestionKind.PLAN_ITEM -> "Step of the plan"
    SuggestionKind.VENDOR -> "Vendor"
    SuggestionKind.OTHER -> "Other"
}

/** What is proposed, by its title; "Suggestion without a title" where the mail gave none. */
fun suggestionName(suggestion: Suggestion): String = suggestion.title.trim().ifEmpty { "Suggestion without a title" }

/** "due 1 Dec 2026", "from 1 Dec 2026 to 15 Dec 2026" for a step with both days, "from 1 Dec 2026" for a step with its first day only; null with no day. */
fun suggestionDays(suggestion: Suggestion): String? {
    val start = suggestion.startDate
    val due = suggestion.dueDate
    return when {
        suggestion.kind == SuggestionKind.PLAN_ITEM && start != null && due != null -> "from ${shortDate(start)} to ${shortDate(due)}"
        suggestion.kind == SuggestionKind.PLAN_ITEM && start != null -> "from ${shortDate(start)}"
        due != null -> "due ${shortDate(due)}"
        else -> null
    }
}

/** "2,500.00 zł, deposit": the amount and the kind of payment; null where no amount was read. */
fun suggestionAmount(suggestion: Suggestion, currency: String): String? {
    val amount = suggestion.amount ?: return null
    val kind = suggestion.paymentKind?.let { PAYMENT_KINDS[it] ?: "payment" }
    return if (kind == null) money(amount, currency) else "${money(amount, currency)}, $kind"
}

/** The category's name; null where none was read or the planner does not have it any more (an id is never shown). */
fun suggestionCategory(suggestion: Suggestion, planner: Planner): String? =
    suggestion.categoryId?.let { planner.categories[it] }?.let { "Category: $it" }

/**
 * The vendor: "Vendor: Palace on the Hill" where the planner has it, and "Vendor: Palace on the Hill (not in the planner
 * yet)" for a name that matched none. Null with neither.
 */
fun suggestionVendor(suggestion: Suggestion, planner: Planner): String? {
    val known = suggestion.vendorId?.let { planner.vendors[it] }
    if (known != null) return "Vendor: $known"
    val named = suggestion.vendorName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return "Vendor: $named (not in the planner yet)"
}

/** What was read of a vendor's contact details, each as a line of its own: shown, never opened. */
fun suggestionContact(suggestion: Suggestion): List<String> = listOfNotNull(
    suggestion.vendorEmail?.takeIf { it.isNotBlank() }?.let { "E-mail: $it" },
    suggestion.vendorPhone?.takeIf { it.isNotBlank() }?.let { "Phone: $it" },
    suggestion.vendorWebsite?.takeIf { it.isNotBlank() }?.let { "Website: $it" },
)

/** Every fact of what is proposed, in the order a person reads them. */
fun suggestionFacts(suggestion: Suggestion, planner: Planner): List<String> = listOfNotNull(
    suggestionDays(suggestion),
    suggestionAmount(suggestion, planner.currency),
    suggestionCategory(suggestion, planner),
    suggestionVendor(suggestion, planner),
) + suggestionContact(suggestion)

/** "Jan from Palace on the Hill (jan@palace.example)"; just the address or the name where only one is known. */
fun suggestionSender(suggestion: Suggestion): String {
    val name = suggestion.senderName.trim()
    val address = suggestion.senderAddress.trim()
    return when {
        name.isNotEmpty() && address.isNotEmpty() -> "$name ($address)"
        name.isNotEmpty() -> name
        address.isNotEmpty() -> address
        else -> "Unknown sender"
    }
}

/** "Subject: Deposit for the hall", "No subject". */
fun suggestionSubject(suggestion: Suggestion): String =
    suggestion.subject.trim().takeIf { it.isNotEmpty() }?.let { "Subject: $it" } ?: "No subject"

/** "Arrived 18 Nov 2026 at 14:05" on the phone's own clock; null where the time cannot be read. */
fun suggestionArrived(suggestion: Suggestion, zone: ZoneId): String? =
    suggestion.receivedAt?.let { "Arrived ${moment(it, zone)}" }

/** "in Anna's inbox", as it stands after when the message arrived; null for a member the planner does not know. */
fun suggestionInbox(suggestion: Suggestion, planner: Planner): String? =
    planner.memberName(suggestion.inboxMemberId)?.let { "in $it's inbox" }

/** Where it comes from, line by line: who wrote, the subject, when, and whose inbox. */
fun suggestionSource(suggestion: Suggestion, planner: Planner, zone: ZoneId): List<String> = listOfNotNull(
    "From ${suggestionSender(suggestion)}",
    suggestionSubject(suggestion),
    // Where the time could not be read the inbox stands alone, and begins the line.
    listOfNotNull(suggestionArrived(suggestion, zone), suggestionInbox(suggestion, planner)).joinToString(", ")
        .replaceFirstChar { it.uppercase() }.ifEmpty { null },
)

/** "62% sure"; the import's own whole percentage, kept between 0 and 100. */
fun confidenceInWords(percent: Int): String = "${percent.coerceIn(0, 100)}% sure"

/** What a screen reader says before the passage. */
const val QUOTATION_LEAD = "Quotation from the e-mail"

/** "3 suggestions wait", "1 suggestion waits", "Nothing waits". */
fun suggestionsSummary(count: Int): String = when {
    count <= 0 -> "Nothing waits"
    count == 1 -> "1 suggestion waits"
    else -> "$count suggestions wait"
}

/** What Home says: how many wait; null when none do, and Home then says nothing about them. */
fun suggestionsOnHome(count: Int): String? = suggestionsSummary(count).takeIf { count > 0 }

/** The card as one statement for a screen reader: what is proposed, where it comes from, the passage as a quotation, how sure. */
fun suggestionInWords(suggestion: Suggestion, planner: Planner, zone: ZoneId): String {
    val parts = mutableListOf("${suggestionKindLabel(suggestion.kind)}: ${suggestionName(suggestion)}")
    parts += suggestionFacts(suggestion, planner)
    if (suggestion.notes.isNotBlank()) parts += "Notes: ${suggestion.notes.trim()}"
    parts += suggestionSource(suggestion, planner, zone)
    if (suggestion.passage.isNotBlank()) parts += "$QUOTATION_LEAD: “${suggestion.passage.trim()}”"
    parts += confidenceInWords(suggestion.confidence)
    return parts.joinToString(". ")
}

/** What the buttons of a card say: the same for every card, and what each does to which suggestion is in its description. */
fun decideButton(decision: Decision): String = if (decision == Decision.ACCEPT) "Accept" else "Reject"

/** What a screen reader says for a button of a card: it names the suggestion, so that the buttons of two cards differ. */
fun decideDescription(decision: Decision, suggestion: Suggestion): String = "${decideButton(decision)} “${suggestionName(suggestion)}”"

/** What a decision made on the phone is called on the card, before it is sent. */
fun decidedInWords(decision: Decision): String = if (decision == Decision.ACCEPT) "Accepted" else "Rejected"

const val TAKE_BACK = "Take back"

fun takeBackDescription(suggestion: Suggestion): String = "Take back your decision on “${suggestionName(suggestion)}”"

/** Said under a decision that cannot be taken back any more: it is being sent. */
const val ON_ITS_WAY = "It is being sent and cannot be taken back."

/**
 * What a decision that could not be sent says, with the suggestion's title: what was decided otherwise, that the
 * suggestion is gone, or that the server did not take it and where the proposal can be changed.
 */
fun suggestionTroubleInWords(trouble: SuggestionTrouble): String {
    val title = trouble.title.trim().ifEmpty { "Suggestion without a title" }
    val accepted = trouble.decision == Decision.ACCEPT
    return when (trouble.kind) {
        // The phone sent the opposite of what the server holds.
        SuggestionTroubleKind.DECIDED_OTHERWISE ->
            "“$title” was already ${if (accepted) "rejected" else "accepted"}. Your ${if (accepted) "acceptance" else "rejection"} was not kept."
        SuggestionTroubleKind.GONE -> "“$title” no longer exists, so your ${if (accepted) "acceptance" else "rejection"} was not sent."
        SuggestionTroubleKind.REFUSED ->
            if (accepted) {
                "The server did not take your acceptance of “$title”. The proposal can be changed in the web app."
            } else {
                "The server did not take your rejection of “$title”."
            }
    }
}

/** The button of a notice that lets go of the phone's decision. */
fun suggestionTroubleButton(trouble: SuggestionTrouble): String =
    if (trouble.kind == SuggestionTroubleKind.REFUSED) "Discard the decision" else "OK"
