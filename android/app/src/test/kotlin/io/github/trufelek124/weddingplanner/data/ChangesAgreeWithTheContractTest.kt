package io.github.trufelek124.weddingplanner.data

import io.github.trufelek124.weddingplanner.api.model.EntryChange
import io.github.trufelek124.weddingplanner.api.model.EntryStatusChange
import io.github.trufelek124.weddingplanner.api.model.NewEntry
import io.github.trufelek124.weddingplanner.api.model.NewInspiration
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the phone sends is JSON put together by hand (Changes.kt). These tests read it with the
 * request models generated from the API's description, strictly: a field the contract does not
 * have, a missing one, or a value outside a fixed set fails here.
 */
class ChangesAgreeWithTheContractTest {

    private val strict = Json { ignoreUnknownKeys = false }
    private val id = "33333333-3333-4333-8333-333333333333"

    private val plain = TaskFields(title = "Call the florist")
    private val full = TaskFields(
        title = "Pay the band", notes = "By transfer.", categoryId = "c1", dueDate = LocalDate.of(2027, 5, 2), status = Status.DONE,
        assigneeIds = listOf(StandIn.ANNA, StandIn.MAREK), priorityByHand = Priority.CRITICAL, parentId = "p1", vendorId = "v1",
        amount = 120050, paymentKind = "FINAL", paidOn = LocalDate.of(2026, 11, 17),
    )

    private fun change(kind: String, fields: TaskFields, baseVersion: Int = 0) =
        ChangeRow(entryId = id, kind = kind, baseVersion = baseVersion, body = fields.toJson().toString(), title = fields.title)

    @Test
    fun `a new task is a NewEntry of the contract, with the id the phone gave it`() {
        val read = strict.decodeFromJsonElement<NewEntry>(requestOf(change(ChangeKind.ADD, full)))

        assertEquals(id, read.id)
        assertEquals(NewEntry.Kind.TASK, read.kind)
        assertEquals("Pay the band", read.title)
        assertEquals("By transfer.", read.notes)
        assertEquals("c1", read.categoryId)
        assertNull(read.startDate)
        assertEquals("2027-05-02", read.dueDate.toString())
        assertEquals(NewEntry.Status.DONE, read.status)
        assertEquals(listOf(StandIn.ANNA, StandIn.MAREK), read.assigneeIds)
        assertEquals(NewEntry.PriorityMode.MANUAL, read.priorityMode)
        assertEquals(NewEntry.Priority.CRITICAL, read.priority)
        assertEquals("p1", read.parentId)
        assertEquals("v1", read.vendorId)
        assertEquals(120050L, read.amount)
        assertEquals(NewEntry.PaymentKind.FINAL, read.paymentKind)
        assertEquals("2026-11-17", read.paidOn.toString())
    }

    @Test
    fun `a task with nothing but a title is one too`() {
        val read = strict.decodeFromJsonElement<NewEntry>(requestOf(change(ChangeKind.ADD, plain)))

        assertEquals(NewEntry.PriorityMode.AUTO, read.priorityMode)
        assertNull(read.priority)
        assertNull(read.amount)
        assertNull(read.paymentKind)
        assertNull(read.paidOn)
        assertEquals(NewEntry.Status.NOT_STARTED, read.status)
    }

    @Test
    fun `a changed task is an EntryChange of the contract, with the version it was made on`() {
        val read = strict.decodeFromJsonElement<EntryChange>(requestOf(change(ChangeKind.EDIT, full, baseVersion = 7)))

        assertEquals(7, read.version)
        assertEquals("Pay the band", read.title)
        assertEquals(EntryChange.Status.DONE, read.status)
        assertEquals(EntryChange.Priority.CRITICAL, read.priority)
        assertEquals(120050L, read.amount)
        strict.decodeFromJsonElement<EntryChange>(requestOf(change(ChangeKind.EDIT, plain)))
    }

    @Test
    fun `a tick is an EntryStatusChange of the contract, whichever way it goes`() {
        for (status in listOf(Status.DONE, Status.NOT_STARTED, Status.IN_PROGRESS, Status.DROPPED)) {
            val tick = ChangeRow(entryId = id, kind = ChangeKind.STATUS, baseVersion = 1, body = statusBody(status).toString(), title = "Call the florist")
            assertEquals(status.name, strict.decodeFromJsonElement<EntryStatusChange>(requestOf(tick)).status.value)
        }
    }

    @Test
    fun `every state a task can be sent in is one the contract knows`() {
        for (status in Status.entries) {
            strict.decodeFromJsonElement<NewEntry>(requestOf(change(ChangeKind.ADD, plain.copy(status = status))))
        }
        for (priority in Priority.entries) {
            strict.decodeFromJsonElement<NewEntry>(requestOf(change(ChangeKind.ADD, plain.copy(priorityByHand = priority))))
        }
        for (part in listOf("DEPOSIT", "INSTALMENT", "FINAL")) {
            strict.decodeFromJsonElement<NewEntry>(requestOf(change(ChangeKind.ADD, plain.copy(amount = 100, paymentKind = part))))
        }
    }

    @Test
    fun `money and its particulars are only sent with a payment, and the paid day only once it is paid`() {
        val notAPayment = fieldsOf(requestOf(change(ChangeKind.ADD, full.copy(amount = null))))
        assertNull(notAPayment.paymentKind)
        assertNull(notAPayment.paidOn)

        val notPaidYet = fieldsOf(requestOf(change(ChangeKind.ADD, full.copy(status = Status.IN_PROGRESS))))
        assertEquals("FINAL", notPaidYet.paymentKind)
        assertNull(notPaidYet.paidOn)
    }

    @Test
    fun `a share is a NewInspiration of the contract, with the id the phone gave it`() {
        val link = ShareRow(inspirationId = id, url = "https://www.instagram.com/p/Cabc123/", boardId = "b1", note = "Lace sleeves", pictures = 0)
        val read = strict.decodeFromJsonElement<NewInspiration>(requestOf(link))

        assertEquals(id, read.id)
        assertEquals("https://www.instagram.com/p/Cabc123/", read.url)
        assertEquals("b1", read.boardId)
        assertEquals("Lace sleeves", read.note)
        assertEquals("", read.title)
        assertEquals(emptyList<String>(), read.tags)
        assertNull(read.planItemId)
        assertNull(read.vendorId)

        val pictures = strict.decodeFromJsonElement<NewInspiration>(requestOf(link.copy(url = null, boardId = null, pictures = 3)))
        assertNull(pictures.url)
        assertNull(pictures.boardId)
    }

    @Test
    fun `what is written can be read back as it was`() {
        assertEquals(full, fieldsOf(full.toJson()))
        assertEquals(plain, fieldsOf(plain.toJson()))
    }
}
