package io.github.trufelek124.weddingplanner.data

import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/*
 * A change as it waits on the phone is already what will be sent: the JSON of the request. These
 * functions turn a task's fields into it and back. ChangesAgreeWithTheContractTest holds what they
 * write against the request models generated from the API's description.
 */

internal object ChangeKind {
    const val ADD = "ADD"
    const val EDIT = "EDIT"
    const val STATUS = "STATUS"
}

internal object Trouble {
    const val CHANGED_MEANWHILE = "CHANGED_MEANWHILE"
    const val GONE = "GONE"
    const val REFUSED = "REFUSED"
}

private fun text(value: String?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)

/** Every field the server asks for when a task is added or changed, as it names them. */
internal fun TaskFields.toJson(): JsonObject = buildJsonObject {
    put("title", title)
    put("notes", notes)
    put("categoryId", text(categoryId))
    // A task has no first day; only a step of the plan has one.
    put("startDate", JsonNull)
    put("dueDate", text(dueDate?.toString()))
    put("status", if (status == Status.OTHER) Status.NOT_STARTED.name else status.name)
    put("assigneeIds", JsonArray(assigneeIds.map(::JsonPrimitive)))
    put("priorityMode", if (priorityByHand == null) "AUTO" else "MANUAL")
    put("priority", text(priorityByHand?.name))
    put("parentId", text(parentId))
    put("vendorId", text(vendorId))
    put("amount", if (amount == null) JsonNull else JsonPrimitive(amount))
    put("paymentKind", text(paymentKind.takeIf { amount != null }))
    // A paid day belongs to a payment that is done; left out, the server takes the day it has, or today.
    put("paidOn", text(paidOn?.takeIf { amount != null && status == Status.DONE }?.toString()))
}

private fun JsonObject.textOf(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

private fun day(text: String?): LocalDate? =
    try {
        text?.let(LocalDate::parse)
    } catch (notADate: DateTimeParseException) {
        null
    }

internal fun fieldsOf(json: JsonObject): TaskFields = TaskFields(
    title = json.textOf("title").orEmpty(),
    notes = json.textOf("notes").orEmpty(),
    categoryId = json.textOf("categoryId"),
    dueDate = day(json.textOf("dueDate")),
    status = Status.entries.firstOrNull { it.name == json.textOf("status") } ?: Status.NOT_STARTED,
    assigneeIds = (json["assigneeIds"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
    priorityByHand = Priority.entries.firstOrNull { it.name == json.textOf("priority") }.takeIf { json.textOf("priorityMode") == "MANUAL" },
    parentId = json.textOf("parentId"),
    vendorId = json.textOf("vendorId"),
    amount = (json["amount"] as? JsonPrimitive)?.longOrNull,
    paymentKind = json.textOf("paymentKind"),
    paidOn = day(json.textOf("paidOn")),
)

internal fun bodyOf(change: ChangeRow): JsonObject = Json.parseToJsonElement(change.body).jsonObject

internal fun statusBody(status: Status): JsonObject = buildJsonObject { put("status", status.name) }

/**
 * What is sent to the server for a change, as the API describes it: a new task with the id the
 * phone gave it, a changed one with the version the change was made on, or only the new status.
 */
internal fun requestOf(change: ChangeRow): JsonObject = when (change.kind) {
    ChangeKind.ADD -> JsonObject(bodyOf(change) + ("id" to JsonPrimitive(change.entryId)) + ("kind" to JsonPrimitive(Kind.TASK.name)))
    ChangeKind.EDIT -> JsonObject(bodyOf(change) + ("version" to JsonPrimitive(change.baseVersion)))
    else -> bodyOf(change)
}

/** What is sent to make a board: only its name, as the API describes a new board. */
internal fun newBoardBody(name: String): JsonObject = buildJsonObject { put("name", name) }

/** What is sent for a guest's status: only the status, as the API describes it. */
internal fun guestStatusBody(change: GuestStatusRow): JsonObject = buildJsonObject { put("status", change.status) }

internal fun statusOf(body: JsonObject): Status = Status.entries.firstOrNull { it.name == body.textOf("status") } ?: Status.NOT_STARTED

internal fun refusalsOf(change: ChangeRow): Map<String, String> =
    change.refusals?.let { stored ->
        runCatching { Json.parseToJsonElement(stored).jsonObject.mapValues { it.value.jsonPrimitive.content } }.getOrNull()
    }.orEmpty()

internal fun refusalsJson(messages: Map<String, String>): String = JsonObject(messages.mapValues { JsonPrimitive(it.value) }).toString()

/** The fields of an entry as the server holds them, to tell whether it already says what a change wanted. */
internal fun fieldsOf(row: EntryRow): TaskFields = TaskFields(
    title = row.title,
    notes = row.notes,
    categoryId = row.categoryId,
    dueDate = day(row.dueDate),
    status = Status.entries.firstOrNull { it.name == row.status } ?: Status.OTHER,
    assigneeIds = if (row.assigneeIds.isEmpty()) emptyList() else row.assigneeIds.split(","),
    priorityByHand = Priority.entries.firstOrNull { it.name == row.priority }.takeIf { row.priorityMode == "MANUAL" },
    parentId = row.parentId,
    vendorId = row.vendorId,
    amount = row.amount,
    paymentKind = row.paymentKind,
    paidOn = day(row.paidOn),
)

/** Whether the server's entry already says what the change wanted. The paid day is the server's to set and is left out. */
internal fun TaskFields.sameAs(server: TaskFields): Boolean =
    copy(assigneeIds = assigneeIds.sorted(), paidOn = null) == server.copy(assigneeIds = server.assigneeIds.sorted(), paidOn = null)
