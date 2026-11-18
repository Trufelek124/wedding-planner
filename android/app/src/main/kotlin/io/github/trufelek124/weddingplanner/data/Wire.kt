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

/** An inspiration as far as the phone needs it after saving one: which it is, and what pictures it already holds. */
@Serializable
class InspirationWire(val id: String, val pictures: List<PictureWire>)

/** @param preview true when the server fetched it from the link; false when a person sent it */
@Serializable
class PictureWire(val preview: Boolean)
