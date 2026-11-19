package io.github.trufelek124.weddingplanner.data

import kotlinx.serialization.Serializable

/*
 * What the phone reads of the server's answers: only the fields it uses, and every value that is
 * one of a fixed set on the server as plain text. A newer server may add fields and values; an
 * app that is already on a phone must still read the rest (see "Changing the API" in CLAUDE.md).
 *
 * WireAgreesWithTheContractTest holds each of these against the model generated from the API's
 * description, so a field the backend renames or drops fails the build here.
 */

@Serializable
data class EntryWire(
    val id: String,
    val kind: String,
    val title: String,
    val notes: String,
    val categoryId: String?,
    val startDate: String?,
    val dueDate: String?,
    val status: String,
    val priorityMode: String,
    val priority: String,
    val priorityReason: String,
    val parentId: String?,
    val vendorId: String?,
    val amount: Long?,
    val paymentKind: String?,
    val paidOn: String?,
    val assigneeIds: List<String>,
    val taskCount: Int,
    val version: Int,
)

@Serializable
data class BudgetWire(val total: Long?, val planned: Long, val committed: Long, val paid: Long)

@Serializable
data class HomeWire(
    val daysLeft: Long?,
    val today: String,
    val weddingDate: String?,
    val overdue: List<EntryWire>,
    val next: List<EntryWire>,
    val nextPayments: List<EntryWire>,
    val budget: BudgetWire,
)

/** A category or a vendor, as far as a list needs it: what it is called. */
@Serializable
class NameWire(val id: String, val name: String)

@Serializable
class MemberWire(val id: String, val displayName: String, val you: Boolean)

@Serializable
class WeddingWire(val date: String?, val currency: String, val timeZone: String, val members: List<MemberWire>)

/**
 * An idea on a board, as far as the phone reads it. The link, the title, the note and the tags are the couple's
 * own: this is no data class, so nothing of them is printed. [source] and [previewStatus] are names from a fixed set.
 */
@Serializable
class InspirationWire(
    val id: String,
    val url: String?,
    val source: String,
    val title: String,
    val previewTitle: String,
    val previewStatus: String,
    val note: String,
    val tags: List<String>,
    val boardId: String?,
    val planItemId: String?,
    val vendorId: String?,
    val addedById: String?,
    val pictures: List<PictureWire>,
)

/** @param preview true when the server fetched it from the link; false when a person sent it */
@Serializable
class PictureWire(val id: String, val width: Int?, val height: Int?, val preview: Boolean)

/** The whole budget: what the couple means to spend, and every category against its plan. */
@Serializable
class WholeBudgetWire(
    val total: Long?,
    val planned: Long,
    val committed: Long,
    val paid: Long,
    val categories: List<BudgetLineWire>,
    val uncategorised: BudgetRestWire,
)

@Serializable
class BudgetLineWire(val categoryId: String, val name: String, val planned: Long?, val committed: Long, val paid: Long, val payments: Int)

/** The payments that are filed under no category. */
@Serializable
class BudgetRestWire(val committed: Long, val paid: Long, val payments: Int)

/** A vendor with its contact details and its money. Names and contact details are personal: nothing of it gets printed. */
@Serializable
class VendorWire(
    val id: String,
    val name: String,
    val status: String,
    val categoryId: String?,
    val contactPerson: String,
    val phone: String,
    val email: String,
    val website: String,
    val address: String,
    val notes: String,
    val agreedPrice: Long?,
    val committed: Long,
    val paid: Long,
    val planItems: Int,
    val tasks: Int,
    val payments: Int,
    val inspirations: Int,
)

/** A guest. Everything here is about a person: nothing of it gets printed. */
@Serializable
class GuestWire(
    val id: String,
    val name: String,
    val status: String,
    val sideMemberId: String?,
    val householdId: String?,
    val plusOne: Boolean,
    val children: Int,
    val heads: Int,
    val dietaryNeeds: String,
    val needsAccommodation: Boolean,
    val phone: String,
    val email: String,
    val notes: String,
)

/** How many people: the guests on the list by name, their plus-ones, the children, and all three together. */
@Serializable
data class HeadCountWire(val guests: Int, val plusOnes: Int, val children: Int, val heads: Int)

@Serializable
data class StatusCountWire(val status: String, val count: HeadCountWire)

/** @param sideMemberId the member whose guests these are; null for the guests of both */
@Serializable
data class SideCountWire(val sideMemberId: String?, val count: HeadCountWire, val byStatus: List<StatusCountWire>)

/** The head count as the server counts it: every status and every side, also with nobody in it. */
@Serializable
data class GuestCountsWire(val total: HeadCountWire, val byStatus: List<StatusCountWire>, val bySide: List<SideCountWire>)

/**
 * What the e-mail import proposes, as far as the phone reads it. Everything that came out of a mail (the sender, the
 * subject, the passage, the title) is someone else's words: this is no data class, so nothing of it is printed.
 * [kind] and [paymentKind] are names from a fixed set.
 */
@Serializable
class SuggestionWire(
    val id: String,
    val kind: String,
    val title: String,
    val notes: String,
    val startDate: String?,
    val dueDate: String?,
    val amount: Long?,
    val paymentKind: String?,
    val categoryId: String?,
    val vendorId: String?,
    val vendorName: String?,
    val vendorEmail: String?,
    val vendorPhone: String?,
    val vendorWebsite: String?,
    val senderName: String,
    val senderAddress: String,
    val subject: String,
    val receivedAt: String,
    val inboxMemberId: String,
    val mailLink: String,
    val passage: String,
    val confidence: Int,
) {
    override fun toString(): String = "SuggestionWire[id=$id]"
}
