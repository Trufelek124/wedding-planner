package io.github.trufelek124.weddingplanner.planner

import io.github.trufelek124.weddingplanner.data.DecidedAs
import io.github.trufelek124.weddingplanner.data.Decision
import io.github.trufelek124.weddingplanner.data.Planner
import io.github.trufelek124.weddingplanner.data.Suggestion
import io.github.trufelek124.weddingplanner.data.SuggestionState
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

/** The part of the inbox that holds what the import added by itself. */
const val ADDED_PART = "Added by itself"

/** The part of the inbox that holds what waits, said only where the other part is there as well. */
const val WAITING_PART = "Waiting for a decision"

/** Said once above the cards of [ADDED_PART]. */
const val ADDED_INTRO =
    "These are already in your plan. Undo what should not be there; what nobody undoes counts as kept after thirty days."

/** What Home says about what was added by itself and nobody has looked at; null when there is none. It is a second line, never added to the first. */
fun addedOnHome(count: Int): String? = when {
    count <= 0 -> null
    count == 1 -> "1 entry was added from your e-mail"
    else -> "$count entries were added from your e-mail"
}

/** The second mark beside the suggestions, for a screen reader: "2 added by themselves". */
fun addedMark(count: Int): String = "$count added by themselves"

/** What More says under "Suggestions": how many wait and, beside it, how many were added by themselves and not looked at. */
fun suggestionsOnMore(waiting: Int, added: Int): String =
    if (added > 0) "${suggestionsSummary(waiting)} \u00b7 ${addedMark(added)}" else suggestionsSummary(waiting)

/** The mark on a task or a step of the plan that a suggestion made. */
const val FROM_EMAIL = "From e-mail"

/** What a screen reader says for the mark where it leads to the source: it names what came from the mail. */
fun fromEmailDescription(suggestion: Suggestion): String = "$FROM_EMAIL: where “${suggestionName(suggestion)}” came from"

/**
 * How a suggestion stands, for the source page: "Added by itself", "Accepted by Marek, 18 Nov 2026 at 14:05", "Added by itself;
 * kept after thirty days". The server does not say whether an accepted one was kept or accepted, so it is always "Accepted by".
 * Null for one that still waits.
 */
fun suggestionStanding(suggestion: Suggestion, planner: Planner, zone: ZoneId): String? = when (suggestion.state) {
    SuggestionState.WAITING -> null
    SuggestionState.ADDED -> "Added by itself"
    SuggestionState.ACCEPTED -> {
        val by = suggestion.decidedBy
        if (by == null) {
            "Added by itself; kept after thirty days"
        } else {
            val who = if (planner.you?.id == by) "you" else planner.memberName(by) ?: "someone"
            listOfNotNull("Accepted by $who", suggestion.decidedAt?.let { moment(it, zone) }).joinToString(", ")
        }
    }
}

/** The card as one statement for a screen reader: what is proposed, where it comes from, the passage as a quotation, how sure. */
fun suggestionInWords(suggestion: Suggestion, planner: Planner, zone: ZoneId, standing: String? = null): String {
    val parts = mutableListOf("${suggestionKindLabel(suggestion.kind)}: ${suggestionName(suggestion)}")
    if (standing != null) parts += standing
    parts += suggestionFacts(suggestion, planner)
    if (suggestion.notes.isNotBlank()) parts += "Notes: ${suggestion.notes.trim()}"
    parts += suggestionSource(suggestion, planner, zone)
    if (suggestion.passage.isNotBlank()) parts += "$QUOTATION_LEAD: “${suggestion.passage.trim()}”"
    parts += confidenceInWords(suggestion.confidence)
    return parts.joinToString(". ")
}

/** What the buttons of a card say: the same for every card, and what each does to which suggestion is in its description. */
fun decideButton(decision: Decision): String = when (decision) {
    Decision.ACCEPT -> "Accept"
    Decision.REJECT -> "Reject"
    Decision.KEEP -> "Keep"
    Decision.UNDO -> "Undo"
}

/** What a screen reader says for a button of a card: it names the suggestion, so that the buttons of two cards differ. */
fun decideDescription(decision: Decision, suggestion: Suggestion): String = "${decideButton(decision)} “${suggestionName(suggestion)}”"

/** What a decision made on the phone is called on the card, before it is sent. */
fun decidedInWords(decision: Decision): String = when (decision) {
    Decision.ACCEPT -> "Accepted"
    Decision.REJECT -> "Rejected"
    Decision.KEEP -> "Kept"
    Decision.UNDO -> "Undone"
}

/** What the card of a suggestion says of a decision made on the phone: for a keep and an undo it names the suggestion and says what it came to. */
fun decidedOnCard(decision: Decision, suggestion: Suggestion): String = when (decision) {
    Decision.KEEP -> "Kept: “${suggestionName(suggestion)}”."
    Decision.UNDO -> "Undone: “${suggestionName(suggestion)}”. It was removed from your plan."
    else -> decidedInWords(decision)
}

const val TAKE_BACK = "Take back"

fun takeBackDescription(suggestion: Suggestion): String = "Take back your decision on “${suggestionName(suggestion)}”"

/** Said under a decision that cannot be taken back any more: it is being sent. */
const val ON_ITS_WAY = "It is being sent and cannot be taken back."

/** What the person asked for, as it is said after "your": "acceptance", "undo". */
private fun asked(decision: Decision): String = when (decision) {
    Decision.ACCEPT -> "acceptance"
    Decision.REJECT -> "rejection"
    Decision.KEEP -> "decision to keep it"
    Decision.UNDO -> "undo"
}

/**
 * What a decision that could not be sent says, with the suggestion's title: what was decided otherwise, that the
 * suggestion is gone, that the server did not take it and where the proposal can be changed, or that an undone entry was
 * changed after it was added and so stays.
 */
fun suggestionTroubleInWords(trouble: SuggestionTrouble): String {
    val title = trouble.title.trim().ifEmpty { "Suggestion without a title" }
    val accepted = trouble.decision == Decision.ACCEPT
    return when (trouble.kind) {
        SuggestionTroubleKind.ENTRY_CHANGED -> "“$title” was changed after it was added, so it stays in your plan."
        SuggestionTroubleKind.DECIDED_OTHERWISE ->
            if (trouble.decision.ofAdded) decidedOtherwiseOfAdded(trouble, title) else
                // The phone sent the opposite of what the server holds.
                "“$title” was already ${if (accepted) "rejected" else "accepted"}. Your ${if (accepted) "acceptance" else "rejection"} was not kept."
        SuggestionTroubleKind.GONE -> "“$title” no longer exists, so your ${asked(trouble.decision)} was not sent."
        SuggestionTroubleKind.REFUSED ->
            when (trouble.decision) {
                Decision.ACCEPT -> "The server did not take your acceptance of “$title”. The proposal can be changed in the web app."
                Decision.REJECT -> "The server did not take your rejection of “$title”."
                Decision.KEEP -> "The server did not take your decision to keep “$title”."
                Decision.UNDO -> "The server did not take your undo of “$title”."
            }
    }
}

/** "“t” was already kept by Marek.", "“t” was already undone by Marek.", "“t” counted as kept after thirty days." */
private fun decidedOtherwiseOfAdded(trouble: SuggestionTrouble, title: String): String {
    val by = trouble.decidedBy?.let { " by $it" }.orEmpty()
    return when (trouble.decidedAs) {
        DecidedAs.KEPT_AFTER_THIRTY_DAYS -> "“$title” counted as kept after thirty days."
        DecidedAs.KEPT -> "“$title” was already kept$by."
        DecidedAs.UNDONE -> "“$title” was already undone$by."
        null -> "The server did not take your ${asked(trouble.decision)} of “$title”."
    }
}

/** The button of a notice that lets go of the phone's decision. */
fun suggestionTroubleButton(trouble: SuggestionTrouble): String =
    if (trouble.kind == SuggestionTroubleKind.REFUSED) "Discard the decision" else "OK"
