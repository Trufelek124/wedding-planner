package io.github.trufelek124.weddingplanner.data

import io.github.trufelek124.weddingplanner.api.model.BudgetGlance
import io.github.trufelek124.weddingplanner.api.model.Category
import io.github.trufelek124.weddingplanner.api.model.Home
import io.github.trufelek124.weddingplanner.api.model.Vendor
import io.github.trufelek124.weddingplanner.api.model.Wedding
import io.github.trufelek124.weddingplanner.api.model.WeddingMember
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import io.github.trufelek124.weddingplanner.api.model.Entry as ContractEntry

/**
 * The phone reads the server's answers into classes of its own (Wire.kt). These tests hold each
 * of them against the model generated from the API's description: a field the phone reads must be
 * in the contract, with the same kind of value and the same answer to "may it be null". A change
 * of the backend that the phone does not follow fails here.
 */
@OptIn(ExperimentalSerializationApi::class)
class WireAgreesWithTheContractTest {

    @Test
    fun `an entry`() = agree(serializer<EntryWire>().descriptor, serializer<ContractEntry>().descriptor)

    @Test
    fun `home, with its entries and its money`() = agree(serializer<HomeWire>().descriptor, serializer<Home>().descriptor)

    @Test
    fun `the money at a glance`() = agree(serializer<BudgetWire>().descriptor, serializer<BudgetGlance>().descriptor)

    @Test
    fun `a category and a vendor by name`() {
        agree(serializer<NameWire>().descriptor, serializer<Category>().descriptor)
        agree(serializer<NameWire>().descriptor, serializer<Vendor>().descriptor)
    }

    @Test
    fun `the wedding and its two people`() {
        agree(serializer<WeddingWire>().descriptor, serializer<Wedding>().descriptor)
        agree(serializer<MemberWire>().descriptor, serializer<WeddingMember>().descriptor)
    }

    @Test
    fun `the check itself notices a field the contract does not have, and one that may be null there`() {
        val contract = serializer<Category>().descriptor
        assertTrue(runCatching { agree(serializer<MemberWire>().descriptor, contract) }.isFailure)
        assertTrue(runCatching { agree(serializer<Strict>().descriptor, serializer<ContractEntry>().descriptor) }.isFailure)
    }

    @kotlinx.serialization.Serializable
    private class Strict(val id: String, val categoryId: String)

    // ---------------------------------------------------------------- what the stand-in server sends

    /** The tests' own answers must be what the real server sends, or they prove nothing. */
    @Test
    fun `the stand-in server's answers are exactly what the contract describes`() {
        val strict = Json { ignoreUnknownKeys = false }
        val entry = StandIn.entry(
            "e1", "Pay the venue", dueDate = "2026-12-01", amount = 250000, paymentKind = "DEPOSIT", status = "DONE", paidOn = "2026-11-10",
            assigneeIds = listOf(StandIn.ANNA), categoryId = "c1", vendorId = "v1", parentId = "p1",
        )

        assertEquals("Pay the venue", strict.decodeFromString<ContractEntry>(entry).title)
        assertEquals(213L, strict.decodeFromString<Home>(StandIn.home(overdue = listOf(entry), next = listOf(entry), nextPayments = listOf(entry))).daysLeft)
        assertEquals(2, strict.decodeFromString<Wedding>(StandIn.wedding()).members.size)
        assertEquals("Venue", strict.decodeFromString<Category>(StandIn.category("c1", "Venue")).name)
        assertEquals("Palace", strict.decodeFromString<Vendor>(StandIn.vendor("v1", "Palace")).name)
    }

    private fun agree(phone: SerialDescriptor, contract: SerialDescriptor) {
        for (name in phone.elementNames) {
            val index = contract.getElementIndex(name)
            if (index < 0) {
                fail("${phone.serialName} reads \"$name\", which ${contract.serialName} does not have")
            }
            same("${phone.serialName}.$name", phone.getElementDescriptor(phone.getElementIndex(name)), contract.getElementDescriptor(index))
        }
    }

    private fun same(where: String, phone: SerialDescriptor, contract: SerialDescriptor) {
        assertEquals("$where: may it be null?", contract.isNullable, phone.isNullable)
        when (phone.kind) {
            StructureKind.LIST -> {
                assertEquals("$where: a list?", StructureKind.LIST, contract.kind)
                same("$where[]", phone.getElementDescriptor(0), contract.getElementDescriptor(0))
            }
            StructureKind.CLASS -> {
                assertEquals("$where: an object?", StructureKind.CLASS, contract.kind)
                agree(phone, contract)
            }
            // Text on the phone is text in the contract, a date, or one of a fixed set of names.
            PrimitiveKind.STRING -> assertTrue("$where: text?", contract.kind == PrimitiveKind.STRING || contract.kind == SerialKind.ENUM)
            else -> assertEquals("$where: the same kind of value?", contract.kind, phone.kind)
        }
    }
}
