package io.github.trufelek124.weddingplanner.planner

import io.github.trufelek124.weddingplanner.data.Guest
import io.github.trufelek124.weddingplanner.data.GuestCounts
import io.github.trufelek124.weddingplanner.data.GuestStatus
import io.github.trufelek124.weddingplanner.data.HeadCount
import io.github.trufelek124.weddingplanner.data.Household
import io.github.trufelek124.weddingplanner.data.Waiting

/*
 * How the guests read on the phone: the same words as the web's guests page
 * (web/src/guests/GuestsPage.tsx, guestsApi.ts). Each function says what one place on the screen
 * says, so that the words are tested without a screen. The head count is the server's: nothing
 * here adds heads up, it only tells the numbers the server sent.
 */

/** What a guest of both sides is told as. */
const val BOTH_SIDES = "Both sides"

/** What a side the phone does not know a member for is told as in the head count. */
private const val OTHER_SIDE = "Other"

/** "Confirmed". A state a newer server knows and this app does not reads "Other". */
fun guestStatusLabel(status: GuestStatus): String = when (status) {
    GuestStatus.NOT_INVITED -> "Not invited yet"
    GuestStatus.INVITED -> "Invited"
    GuestStatus.CONFIRMED -> "Confirmed"
    GuestStatus.DECLINED -> "Declined"
    GuestStatus.MAYBE -> "Maybe"
    GuestStatus.OTHER -> "Other"
}

/** A status as the head count names it ("CONFIRMED"): in words, and "Other" for one this app does not know. */
fun guestStatusLabelOf(name: String): String = guestStatusLabel(GuestStatus.entries.firstOrNull { it.name == name } ?: GuestStatus.OTHER)

/** "1 person", "4 people" */
fun people(heads: Int): String = if (heads == 1) "1 person" else "$heads people"

/** "1 guest", "3 guests" */
private fun guestsIn(count: Int): String = if (count == 1) "1 guest" else "$count guests"

/** The heads the server counted for a status; null where the copy has no head count or no such status. */
private fun headsOf(counts: GuestCounts?, status: GuestStatus): HeadCount? = counts?.byStatus?.firstOrNull { it.status == status.name }?.count

/**
 * The one line under "Guests" in the list of what More leads to: "24 people on the list, 12 confirmed",
 * from the server's head count. Without a head count (a copy made before the app knew it) no number is
 * told: the guests are not counted here.
 */
fun guestsSummary(counts: GuestCounts?, guests: List<Guest>): String {
    if (counts == null) return if (guests.isEmpty()) "No guests yet" else "See the guest list"
    if (counts.total.heads == 0) return "No guests yet"
    val confirmed = headsOf(counts, GuestStatus.CONFIRMED)?.heads ?: 0
    return "${people(counts.total.heads)} on the list, " + if (confirmed == 0) "none confirmed yet" else "$confirmed confirmed"
}

/**
 * The statuses to offer: those that occur among the guests, in the order the head count lists them. The one
 * that is chosen stays offered when its last guest is gone (after a load), so that the list can say that
 * nobody matches instead of changing what was asked for.
 */
fun guestStatusesOf(guests: List<Guest>, chosen: GuestStatus? = null): List<GuestStatus> =
    GuestStatus.entries.filter { status -> status == chosen || guests.any { it.status == status } }

/** What the list shows: one status or all, in the server's order (by name). */
fun guestsShown(guests: List<Guest>, only: GuestStatus?): List<Guest> = guests.filter { only == null || it.status == only }

/** A household and those of its guests that are shown; [household] is null for the guests on their own. */
class GuestGroup(val household: Household?, val guests: List<Guest>) {
    /** How many people the shown guests of the group stand for. */
    val heads: Int get() = guests.sumOf { it.heads }
}

/**
 * The guests by household: the households in the server's order, each with its guests shown, a household
 * none of whose guests is shown left out, and then the guests on their own (no household, or one the
 * copy does not know). With no household at all there is a single group of guests on their own.
 */
fun guestGroups(shown: List<Guest>, households: List<Household>): List<GuestGroup> {
    val known = households.map { it.id }.toSet()
    val groups = households.map { household -> GuestGroup(household, shown.filter { it.householdId == household.id }) }.filter { it.guests.isNotEmpty() }
    val alone = shown.filter { it.householdId == null || it.householdId !in known }
    return if (alone.isEmpty()) groups else groups + GuestGroup(null, alone)
}

/** Whose side a guest is on: "Both sides", or the member's name; null for a member this phone does not know. */
fun guestSide(guest: Guest, nameOf: (String) -> String?): String? = guest.sideMemberId?.let(nameOf) ?: BOTH_SIDES.takeIf { guest.sideMemberId == null }

/** "with a plus-one", "2 children", "needs a room", and what the guest eats: what a guest comes along with and needs. */
fun whatComesAlong(guest: Guest, withDietary: Boolean = true): List<String> = listOfNotNull(
    "with a plus-one".takeIf { guest.plusOne },
    when (guest.children) {
        0 -> null
        1 -> "1 child"
        else -> "${guest.children} children"
    },
    "needs a room".takeIf { guest.needsAccommodation },
    guest.dietaryNeeds.takeIf { withDietary && it.isNotEmpty() },
)

/** Where a status chosen on this phone stands, for the guest's line; null when nothing waits. */
fun guestWaitingInWords(waiting: Waiting?): String? = when (waiting) {
    Waiting.TO_BE_SENT -> waitingInWords(waiting)
    Waiting.REFUSED -> "Not saved: the server did not take it"
    // Only a task is ever changed on another device.
    Waiting.CHANGED_MEANWHILE, null -> null
}

/**
 * What stands under the head count while a status chosen on the phone has not reached the server: the count is the
 * server's and is not worked out here. Null where nothing waits.
 */
fun headCountAsLoaded(changes: Int): String? = when {
    changes <= 0 -> null
    changes == 1 -> "The head count is as it was loaded: 1 change is not sent yet."
    else -> "The head count is as it was loaded: $changes changes are not sent yet."
}

/** What a line of the list says of a guest, after the name: the status, how many people, the side, and what comes along. */
fun guestFacts(guest: Guest, side: String?, withDietary: Boolean = true): List<Fact> =
    listOfNotNull(guestWaitingInWords(guest.waiting)?.let { Fact(it, strong = true) }, Fact(guestStatusLabel(guest.status))) +
        listOfNotNull(Fact(people(guest.heads), strong = true).takeIf { guest.heads > 1 }, side?.let { Fact(it) }) +
        whatComesAlong(guest, withDietary).map { Fact(it) }

/** "Aunt Krystyna. Confirmed. 2 people. Anna. with a plus-one. …": a line of the list as one statement. */
fun guestLineInWords(guest: Guest, side: String?): String = (listOf(guest.name) + guestFacts(guest, side).map { it.text }).joinToString(". ")

/** "Bartek, Kasia": what a household's name is told with how many people it holds, as one statement. */
fun householdInWords(name: String, heads: Int): String = "$name, ${people(heads)}"

// ---------------------------------------------------------------- the head count

/**
 * The sentence above the head count: "12 people confirmed — 8 guests, 3 plus-ones, 1 child". Null where the
 * head count has no line for the confirmed. Plus-ones and children are left out where there are none.
 */
fun headCountLead(counts: GuestCounts): String? {
    val confirmed = headsOf(counts, GuestStatus.CONFIRMED) ?: return null
    if (confirmed.heads == 0) return "${people(confirmed.heads)} confirmed"
    val parts = listOfNotNull(
        guestsIn(confirmed.guests),
        confirmed.plusOnes.takeIf { it > 0 }?.let { if (it == 1) "1 plus-one" else "$it plus-ones" },
        confirmed.children.takeIf { it > 0 }?.let { if (it == 1) "1 child" else "$it children" },
    )
    return "${people(confirmed.heads)} confirmed — ${parts.joinToString(", ")}"
}

/** "12 people on the list": everyone, as the server counted them. */
fun headCountEveryone(counts: GuestCounts): String = "${people(counts.total.heads)} on the list"

/** How many people one side comes to. */
class SideHeads(val side: String, val heads: Int)

/** One line of the head count: a status (or everyone), its people, and how they divide between the sides. */
class HeadLine(val label: String, val heads: Int, val sides: List<SideHeads>)

/**
 * The head count, status by status in the server's order and then everyone ("On the list"), each with its
 * people and side by side in the server's order. Every number is the server's.
 *
 * @param nameOf what a member is called; null for someone who is not one of the two
 */
fun headCountLines(counts: GuestCounts, nameOf: (String) -> String?): List<HeadLine> {
    fun side(sideMemberId: String?) = if (sideMemberId == null) BOTH_SIDES else nameOf(sideMemberId) ?: OTHER_SIDE
    val byStatus = counts.byStatus.map { line ->
        HeadLine(
            label = guestStatusLabelOf(line.status),
            heads = line.count.heads,
            sides = counts.bySide.mapNotNull { side ->
                side.byStatus.firstOrNull { it.status == line.status }?.let { SideHeads(side(side.sideMemberId), it.count.heads) }
            },
        )
    }
    val everyone = HeadLine("On the list", counts.total.heads, counts.bySide.map { SideHeads(side(it.sideMemberId), it.count.heads) })
    return byStatus + everyone
}

/** "Confirmed: 12 people. Anna 5, Marek 6, both sides 1.": a line of the head count as one statement. */
fun headLineInWords(line: HeadLine): String {
    val said = "${line.label}: ${people(line.heads)}."
    if (line.sides.isEmpty()) return said
    return said + " " + line.sides.joinToString(", ") { (if (it.side == BOTH_SIDES) "both sides" else it.side) + " ${it.heads}" } + "."
}
