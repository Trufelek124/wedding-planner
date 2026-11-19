package io.github.trufelek124.weddingplanner.data

import io.github.trufelek124.weddingplanner.api.model.Board
import io.github.trufelek124.weddingplanner.api.model.Budget
import io.github.trufelek124.weddingplanner.api.model.BudgetGlance
import io.github.trufelek124.weddingplanner.api.model.BudgetLine
import io.github.trufelek124.weddingplanner.api.model.BudgetRest
import io.github.trufelek124.weddingplanner.api.model.Category
import io.github.trufelek124.weddingplanner.api.model.Guest
import io.github.trufelek124.weddingplanner.api.model.GuestCounts
import io.github.trufelek124.weddingplanner.api.model.GuestsBySide
import io.github.trufelek124.weddingplanner.api.model.GuestsByStatus
import io.github.trufelek124.weddingplanner.api.model.HeadCount
import io.github.trufelek124.weddingplanner.api.model.Household
import io.github.trufelek124.weddingplanner.api.model.Home
import io.github.trufelek124.weddingplanner.api.model.Inspiration
import io.github.trufelek124.weddingplanner.api.model.Picture
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
    fun `a category, a vendor and a household by name`() {
        agree(serializer<NameWire>().descriptor, serializer<Category>().descriptor)
        agree(serializer<NameWire>().descriptor, serializer<Vendor>().descriptor)
        agree(serializer<NameWire>().descriptor, serializer<Household>().descriptor)
    }

    @Test
    fun `the whole budget, its categories and what is under none`() {
        agree(serializer<WholeBudgetWire>().descriptor, serializer<Budget>().descriptor)
        agree(serializer<BudgetLineWire>().descriptor, serializer<BudgetLine>().descriptor)
        agree(serializer<BudgetRestWire>().descriptor, serializer<BudgetRest>().descriptor)
    }

    @Test
    fun `a vendor with its details`() = agree(serializer<VendorWire>().descriptor, serializer<Vendor>().descriptor)

    @Test
    fun `a guest`() = agree(serializer<GuestWire>().descriptor, serializer<Guest>().descriptor)

    @Test
    fun `the head count by status and by side`() {
        agree(serializer<GuestCountsWire>().descriptor, serializer<GuestCounts>().descriptor)
        agree(serializer<HeadCountWire>().descriptor, serializer<HeadCount>().descriptor)
        agree(serializer<StatusCountWire>().descriptor, serializer<GuestsByStatus>().descriptor)
        agree(serializer<SideCountWire>().descriptor, serializer<GuestsBySide>().descriptor)
    }

    @Test
    fun `a board by name, and an inspiration with what pictures it holds`() {
        agree(serializer<NameWire>().descriptor, serializer<Board>().descriptor)
        agree(serializer<InspirationWire>().descriptor, serializer<Inspiration>().descriptor)
        agree(serializer<PictureWire>().descriptor, serializer<Picture>().descriptor)
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
        val vendor = strict.decodeFromString<Vendor>(
            StandIn.vendor("v2", "Studio Light", status = "CONSIDERING", categoryId = "c1", phone = "+48 000 000 000", agreedPrice = 100000, payments = 1),
        )
        assertEquals(Vendor.Status.CONSIDERING, vendor.status)
        val lines = listOf(StandIn.Line("c1", "Venue", planned = 500000, committed = 450000, paid = 100000, payments = 2), StandIn.Line("c2", "Music"))
        val budget = strict.decodeFromString<Budget>(StandIn.wholeBudget(total = 900000, lines = lines, rest = StandIn.Rest(committed = 20000, payments = 1)))
        assertEquals(470000L, budget.committed)
        assertEquals(null, budget.categories[1].planned)
        assertEquals(1, budget.uncategorised.payments)
        val guest = strict.decodeFromString<Guest>(StandIn.guest("g1", "Jan", plusOne = true, children = 2, sideMemberId = StandIn.ANNA, householdId = "h1"))
        assertEquals(4, guest.heads)
        assertEquals(2, strict.decodeFromString<Household>(StandIn.household("h1", "Kowalski", guests = 2)).guests)
        val counts = strict.decodeFromString<GuestCounts>(
            StandIn.guestCounts(listOf(StandIn.Counted("CONFIRMED", StandIn.ANNA, plusOne = true, children = 1), StandIn.Counted("INVITED"))),
        )
        assertEquals(4, counts.total.heads)
        assertEquals(5, counts.byStatus.size)
        assertEquals(listOf<String?>(StandIn.ANNA, StandIn.MAREK, null), counts.bySide.map { it.sideMemberId })
        assertEquals(
            GuestsByStatus.Status.entries.toList(),
            strict.decodeFromString<GuestCounts>(StandIn.guestCounts()).byStatus.map { it.status },
        )
        assertEquals("Dress", strict.decodeFromString<Board>(StandIn.board("b1", "Dress")).name)
        assertEquals(3, strict.decodeFromString<Inspiration>(StandIn.inspiration("i1", url = "https://example.com/x", boardId = "b1", pictures = 2, previews = 1)).pictures.size)
        assertEquals(0, strict.decodeFromString<Inspiration>(StandIn.inspiration("i2")).pictures.size)
        val idea = strict.decodeFromString<Inspiration>(
            StandIn.inspiration(
                "i3", url = "https://palace.example/ideas", source = "WEB", title = "Lace", previewTitle = "A page", previewStatus = "FETCHED", note = "Soft",
                tags = listOf("boho", "lace"), boardId = "b1", planItemId = "p1", vendorId = "v1", addedById = null, pictures = 1, version = 2,
            ),
        )
        assertEquals(Inspiration.Source.WEB, idea.source)
        assertEquals(Inspiration.PreviewStatus.FETCHED, idea.previewStatus)
        assertEquals(listOf("boho", "lace"), idea.tags)
        assertEquals(null, idea.addedById)
        assertEquals("p1", idea.planItemId)
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
