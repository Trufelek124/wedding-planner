package io.github.trufelek124.weddingplanner.planner

import io.github.trufelek124.weddingplanner.data.Guest
import io.github.trufelek124.weddingplanner.data.GuestCounts
import io.github.trufelek124.weddingplanner.data.GuestStatus
import io.github.trufelek124.weddingplanner.data.HeadCount
import io.github.trufelek124.weddingplanner.data.Household
import io.github.trufelek124.weddingplanner.data.SideCount
import io.github.trufelek124.weddingplanner.data.StatusCount
import io.github.trufelek124.weddingplanner.data.Waiting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The guests read on the phone as they do on the web's guests page. Everyone here is made up. */
class GuestWordsTest {

    private fun guest(
        id: String = "g1",
        name: String = "Aunt Krystyna",
        status: GuestStatus = GuestStatus.CONFIRMED,
        sideMemberId: String? = null,
        householdId: String? = null,
        plusOne: Boolean = false,
        children: Int = 0,
        dietaryNeeds: String = "",
        needsAccommodation: Boolean = false,
    ) = Guest(
        id = id, name = name, status = status, sideMemberId = sideMemberId, householdId = householdId, plusOne = plusOne, children = children,
        heads = 1 + (if (plusOne) 1 else 0) + children, dietaryNeeds = dietaryNeeds, needsAccommodation = needsAccommodation, phone = "", email = "", notes = "",
    )

    private fun head(guests: Int, plusOnes: Int = 0, children: Int = 0) = HeadCount(guests, plusOnes, children, guests + plusOnes + children)

    private val names = mapOf("anna" to "Anna", "marek" to "Marek")

    @Test
    fun `a status reads as a word, and a state this app does not know reads Other`() {
        assertEquals("Not invited yet", guestStatusLabel(GuestStatus.NOT_INVITED))
        assertEquals("Invited", guestStatusLabel(GuestStatus.INVITED))
        assertEquals("Confirmed", guestStatusLabel(GuestStatus.CONFIRMED))
        assertEquals("Declined", guestStatusLabel(GuestStatus.DECLINED))
        assertEquals("Maybe", guestStatusLabel(GuestStatus.MAYBE))
        assertEquals("Other", guestStatusLabel(GuestStatus.OTHER))
        assertEquals("Declined", guestStatusLabelOf("DECLINED"))
        assertEquals("Other", guestStatusLabelOf("ARCHIVED"))
    }

    @Test
    fun `people are one person or many people`() {
        assertEquals("1 person", people(1))
        assertEquals("2 people", people(2))
        assertEquals("0 people", people(0))
    }

    @Test
    fun `the summary in More takes its numbers from the head count`() {
        val counts = GuestCounts(
            head(20, 3, 1),
            listOf(StatusCount("CONFIRMED", head(10, 1, 1)), StatusCount("INVITED", head(10, 2))),
            emptyList(),
        )

        assertEquals("24 people on the list, 12 confirmed", guestsSummary(counts, emptyList()))
        // The list's own length is no part of it.
        assertEquals("24 people on the list, 12 confirmed", guestsSummary(counts, listOf(guest())))
    }

    @Test
    fun `the summary says when nobody is confirmed yet, or nobody is on the list`() {
        val none = GuestCounts(head(2), listOf(StatusCount("INVITED", head(2))), emptyList())
        assertEquals("2 people on the list, none confirmed yet", guestsSummary(none, listOf(guest())))
        assertEquals("1 person on the list, 1 confirmed", guestsSummary(GuestCounts(head(1), listOf(StatusCount("CONFIRMED", head(1))), emptyList()), emptyList()))
        assertEquals("No guests yet", guestsSummary(GuestCounts(head(0), emptyList(), emptyList()), emptyList()))
    }

    @Test
    fun `without a head count the summary tells no number`() {
        assertEquals("No guests yet", guestsSummary(null, emptyList()))
        assertEquals("See the guest list", guestsSummary(null, listOf(guest(), guest(id = "g2"))))
    }

    @Test
    fun `the statuses offered are those that occur, in the head count's order, and the chosen one stays`() {
        val guests = listOf(guest(status = GuestStatus.MAYBE), guest(id = "g2", status = GuestStatus.CONFIRMED), guest(id = "g3", status = GuestStatus.OTHER))

        assertEquals(listOf(GuestStatus.CONFIRMED, GuestStatus.MAYBE, GuestStatus.OTHER), guestStatusesOf(guests))
        assertEquals(listOf(GuestStatus.CONFIRMED, GuestStatus.DECLINED, GuestStatus.MAYBE, GuestStatus.OTHER), guestStatusesOf(guests, GuestStatus.DECLINED))
        assertTrue(guestStatusesOf(emptyList()).isEmpty())
    }

    @Test
    fun `narrowing down keeps the server's order`() {
        val guests = listOf(guest("g1", "Ada", GuestStatus.MAYBE), guest("g2", "Bea", GuestStatus.CONFIRMED), guest("g3", "Cyd", GuestStatus.MAYBE))

        assertEquals(listOf("g1", "g2", "g3"), guestsShown(guests, null).map { it.id })
        assertEquals(listOf("g1", "g3"), guestsShown(guests, GuestStatus.MAYBE).map { it.id })
        assertTrue(guestsShown(guests, GuestStatus.DECLINED).isEmpty())
    }

    @Test
    fun `the guests are grouped by household in the server's order, then those on their own`() {
        val households = listOf(Household("h2", "The Zielinski family"), Household("h1", "The Nowak family"), Household("h3", "Empty household"))
        val shown = listOf(
            guest("g1", "Ada", householdId = "h1"), guest("g2", "Bea"), guest("g3", "Cyd", householdId = "h2", plusOne = true),
            guest("g4", "Dan", householdId = "h2"), guest("g5", "Eve", householdId = "h-unknown"),
        )

        val groups = guestGroups(shown, households)

        assertEquals(listOf("The Zielinski family", "The Nowak family", null), groups.map { it.household?.name })
        assertEquals(listOf(listOf("g3", "g4"), listOf("g1"), listOf("g2", "g5")), groups.map { group -> group.guests.map { it.id } })
        assertEquals(listOf(3, 1, 2), groups.map { it.heads })
    }

    @Test
    fun `a household none of whose guests is shown is left out, and without households everybody is on their own`() {
        val households = listOf(Household("h1", "The Nowak family"))

        assertEquals(listOf(null), guestGroups(listOf(guest("g2")), households).map { it.household })
        assertEquals(listOf(null), guestGroups(listOf(guest("g2", householdId = "h1")), emptyList()).map { it.household })
        assertTrue(guestGroups(emptyList(), households).isEmpty())
    }

    @Test
    fun `whose side a guest is on`() {
        assertEquals("Both sides", guestSide(guest(), names::get))
        assertEquals("Anna", guestSide(guest(sideMemberId = "anna"), names::get))
        // A member this phone does not know is not named.
        assertNull(guestSide(guest(sideMemberId = "stranger"), names::get))
    }

    @Test
    fun `what comes along, as the web says it`() {
        assertTrue(whatComesAlong(guest()).isEmpty())
        assertEquals(listOf("with a plus-one"), whatComesAlong(guest(plusOne = true)))
        assertEquals(listOf("1 child"), whatComesAlong(guest(children = 1)))
        assertEquals(listOf("3 children"), whatComesAlong(guest(children = 3)))
        assertEquals(
            listOf("with a plus-one", "2 children", "needs a room", "vegetarian"),
            whatComesAlong(guest(plusOne = true, children = 2, needsAccommodation = true, dietaryNeeds = "vegetarian")),
        )
        assertEquals(listOf("needs a room"), whatComesAlong(guest(needsAccommodation = true, dietaryNeeds = "vegan"), withDietary = false))
    }

    @Test
    fun `a line of the list says the status, how many people, the side and what comes along`() {
        val alone = guest(status = GuestStatus.INVITED, sideMemberId = "marek")
        assertEquals(listOf("Invited", "Marek"), guestFacts(alone, "Marek").map { it.text })
        assertEquals("Aunt Krystyna. Invited. Marek", guestLineInWords(alone, "Marek"))

        val family = guest(plusOne = true, children = 2, needsAccommodation = true, dietaryNeeds = "vegetarian")
        val facts = guestFacts(family, "Both sides")
        assertEquals(listOf("Confirmed", "4 people", "Both sides", "with a plus-one", "2 children", "needs a room", "vegetarian"), facts.map { it.text })
        assertEquals(listOf(false, true, false, false, false, false, false), facts.map { it.strong })
        assertEquals(
            "Aunt Krystyna. Confirmed. 4 people. Both sides. with a plus-one. 2 children. needs a room. vegetarian",
            guestLineInWords(family, "Both sides"),
        )
        // A side that is not known is left out.
        assertEquals(listOf("Confirmed"), guestFacts(guest(), null).map { it.text })
    }

    @Test
    fun `a household heading is one statement`() {
        assertEquals("The Nowak family, 3 people", householdInWords("The Nowak family", 3))
        assertEquals("Solo, 1 person", householdInWords("Solo", 1))
    }

    @Test
    fun `the lead sentence tells the confirmed people and what they are made of`() {
        fun lead(confirmed: HeadCount) = headCountLead(GuestCounts(confirmed, listOf(StatusCount("CONFIRMED", confirmed)), emptyList()))

        assertEquals("12 people confirmed — 8 guests, 3 plus-ones, 1 child", lead(head(8, 3, 1)))
        assertEquals("2 people confirmed — 1 guest, 1 plus-one", lead(head(1, 1)))
        assertEquals("1 person confirmed — 1 guest", lead(head(1)))
        assertEquals("3 people confirmed — 1 guest, 2 children", lead(head(1, 0, 2)))
        assertEquals("0 people confirmed", lead(head(0)))
        assertNull(headCountLead(GuestCounts(head(2), listOf(StatusCount("INVITED", head(2))), emptyList())))
    }

    private val counts = GuestCounts(
        total = head(5, 1, 2),
        byStatus = listOf(StatusCount("CONFIRMED", head(3, 1, 1)), StatusCount("INVITED", head(2, 0, 1)), StatusCount("ARCHIVED", head(0))),
        bySide = listOf(
            SideCount("anna", head(2, 1), listOf(StatusCount("CONFIRMED", head(2, 1)), StatusCount("INVITED", head(0)), StatusCount("ARCHIVED", head(0)))),
            SideCount("stranger", head(1, 0, 1), listOf(StatusCount("CONFIRMED", head(0)), StatusCount("INVITED", head(1, 0, 1)), StatusCount("ARCHIVED", head(0)))),
            SideCount(null, head(2, 0, 1), listOf(StatusCount("CONFIRMED", head(1, 0, 1)), StatusCount("INVITED", head(1)), StatusCount("ARCHIVED", head(0)))),
        ),
    )

    @Test
    fun `the head count lists every status, then everyone, with the server's numbers per side`() {
        val lines = headCountLines(counts, names::get)

        assertEquals(listOf("Confirmed", "Invited", "Other", "On the list"), lines.map { it.label })
        assertEquals(listOf(5, 3, 0, 8), lines.map { it.heads })
        assertEquals(listOf("Anna" to 3, "Other" to 0, "Both sides" to 2), lines[0].sides.map { it.side to it.heads })
        assertEquals(listOf("Anna" to 3, "Other" to 2, "Both sides" to 3), lines.last().sides.map { it.side to it.heads })
    }

    @Test
    fun `everyone on the list is told from the server's total`() {
        assertEquals("8 people on the list", headCountEveryone(counts))
        assertEquals("1 person on the list", headCountEveryone(GuestCounts(head(1), emptyList(), emptyList())))
    }

    @Test
    fun `a line of the head count is one statement`() {
        val lines = headCountLines(counts, names::get)

        assertEquals("Confirmed: 5 people. Anna 3, Other 0, both sides 2.", headLineInWords(lines[0]))
        assertEquals("On the list: 8 people. Anna 3, Other 2, both sides 3.", headLineInWords(lines.last()))
        assertEquals("Invited: 1 person.", headLineInWords(HeadLine("Invited", 1, emptyList())))
    }

    @Test
    fun `a status that waits is said in words on the guest's line, and a refused one is not saved`() {
        assertNull(guestWaitingInWords(null))
        assertEquals("Waiting to be sent", guestWaitingInWords(Waiting.TO_BE_SENT))
        assertEquals("Not saved: the server did not take it", guestWaitingInWords(Waiting.REFUSED))

        val waiting = guest().chosen(GuestStatus.DECLINED, Waiting.TO_BE_SENT)
        assertEquals(listOf("Waiting to be sent", "Declined"), guestFacts(waiting, null).map { it.text }.take(2))
        assertEquals("Aunt Krystyna. Waiting to be sent. Declined", guestLineInWords(waiting, null))
        // A guest nothing waits for reads as before.
        assertEquals("Aunt Krystyna. Confirmed", guestLineInWords(guest(), null))
    }

    @Test
    fun `under the head count the phone says that it is as it was loaded and how many changes are not sent`() {
        assertNull(headCountAsLoaded(0))
        assertEquals("The head count is as it was loaded: 1 change is not sent yet.", headCountAsLoaded(1))
        assertEquals("The head count is as it was loaded: 3 changes are not sent yet.", headCountAsLoaded(3))
    }
}
