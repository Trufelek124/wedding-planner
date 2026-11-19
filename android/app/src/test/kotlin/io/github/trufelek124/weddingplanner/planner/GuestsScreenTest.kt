package io.github.trufelek124.weddingplanner.planner

import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.trufelek124.weddingplanner.data.StandIn
import io.github.trufelek124.weddingplanner.data.planner
import io.github.trufelek124.weddingplanner.data.plannerUnreachable
import io.github.trufelek124.weddingplanner.ui.PHONE
import io.github.trufelek124.weddingplanner.ui.PHONE_AT_NIGHT
import io.github.trufelek124.weddingplanner.ui.PlannerTheme
import io.github.trufelek124.weddingplanner.ui.picture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog

/**
 * The guests behind More: the head count as the server counts it, the guests by household narrowed
 * down by status, and one guest with their contact details. The same facts and words as the web's
 * guests page, kept on the phone and shown without a connection. Everyone here is made up.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class GuestsScreenTest : SignedInPhoneTest() {

    /** Confirmed, with a plus-one, two children, a room, a diet, a phone, an e-mail address and notes: everything a guest can have. */
    private val krystyna = StandIn.guest(
        "g1", "Aunt Krystyna", status = "CONFIRMED", sideMemberId = StandIn.ANNA, householdId = "h1", plusOne = true, children = 2,
        dietaryNeeds = "vegetarian", needsAccommodation = true, phone = "+48 600 000 011", email = "krystyna@guests.example", notes = "Allergic to nuts.",
    )
    private val tomek = StandIn.guest("g2", "Uncle Tomek", status = "CONFIRMED", sideMemberId = StandIn.ANNA, householdId = "h1")
    private val ola = StandIn.guest("g3", "Cousin Ola", status = "INVITED", sideMemberId = StandIn.MAREK, householdId = "h2", children = 1)

    /** Of both sides, and on their own. */
    private val bartek = StandIn.guest("g4", "Friend Bartek", status = "MAYBE")
    private val ewa = StandIn.guest("g5", "Colleague Ewa", status = "DECLINED", sideMemberId = StandIn.MAREK)

    /** A state that a newer server knows. */
    private val mystery = StandIn.guest("g6", "Mystery Guest", status = "ARCHIVED", sideMemberId = StandIn.MAREK)

    private val guests = listOf(krystyna, tomek, ola, bartek, ewa, mystery)

    /** In the server's order, which is not by name. */
    private val households = listOf(StandIn.household("h2", "The Zielinski family", guests = 1), StandIn.household("h1", "The Nowak family", guests = 2))

    /**
     * What the server counted: not what the list above comes to. Confirmed are 8 people (the list shows 5 of them), and the
     * Mystery Guest is in no count at all. 12 people on the list.
     */
    private val counts = StandIn.guestCounts(
        listOf(
            StandIn.Counted("CONFIRMED", StandIn.ANNA, plusOne = true, children = 2),
            StandIn.Counted("CONFIRMED", StandIn.ANNA),
            StandIn.Counted("CONFIRMED", StandIn.MAREK),
            StandIn.Counted("CONFIRMED", null, plusOne = true),
            StandIn.Counted("INVITED", StandIn.MAREK, children = 1),
            StandIn.Counted("MAYBE", null),
            StandIn.Counted("DECLINED", StandIn.MAREK),
        ),
    )

    @Before
    fun theirGuests() {
        server.planner(guests = guests, households = households, guestCounts = counts)
    }

    private val sayingOpenTheGuest = SemanticsMatcher("leads to a guest") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == "see this guest"
    }

    private fun openGuests() {
        goTo("More")
        compose.onNode(hasText("Guests") and hasClickAction()).performClick()
        waitForTheList()
    }

    private fun waitForTheList() {
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Guests") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun openGuest(name: String) {
        scrollTo(hasText(name) and hasClickAction())
        compose.onNode(hasText(name) and hasClickAction()).performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText(name) and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun pressBackButton() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    private fun chip(name: String) = compose.onNode(hasText(name) and hasClickAction() and isSelectable())

    private fun scrollToTheBreakdown() = scrollTo(hasText("By status and side") and isHeading())

    /** The chips scroll sideways: they are brought into view both ways. */
    private fun pressChip(name: String) {
        scrollTo(hasText(name) and isSelectable())
        chip(name).performScrollTo().performClick()
    }

    /**
     * The guests the list shows, top to bottom. The list is lazy: it is brought to its chips first, under which a
     * narrowed-down list stands entirely in view.
     */
    private fun shownGuests(): List<String> {
        scrollTo(hasText("All") and isSelectable())
        return compose.onAllNodes(sayingOpenTheGuest).fetchSemanticsNodes().map { node ->
            node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.firstOrNull()?.text.orEmpty()
        }
    }

    // ---------------------------------------------------------------- More

    @Test
    fun `More has a row for the guests that says how many people are on the list and how many are confirmed`() {
        open()

        goTo("More")

        compose.onNode(line("Guests", "12 people on the list, 8 confirmed") and hasClickAction()).assertIsDisplayed()
        compose.onNode(hasText("Vendors") and hasClickAction()).assertIsDisplayed()
        compose.onNode(hasText("Budget") and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun `More says that there are no guests yet`() {
        server.planner()
        open()

        goTo("More")

        compose.onNode(line("Guests", "No guests yet")).assertIsDisplayed()
    }

    // ---------------------------------------------------------------- the head count

    @Test
    fun `the head count is the server's, status by status and side by side, and not what the list comes to`() {
        open()

        openGuests()

        compose.onNode(hasText("More") and isSelectable()).assertIsSelected()
        compose.onNode(hasText("Head count") and isHeading()).assertIsDisplayed()
        compose.onNodeWithText("12 people on the list").assertIsDisplayed()
        // The server counted 8 confirmed people; the list shows 5 of them. The sentence and the lines tell the server's.
        compose.onNodeWithText("8 people confirmed — 4 guests, 2 plus-ones, 2 children").assertIsDisplayed()
        compose.onNodeWithText("5 people confirmed", substring = true).assertDoesNotExist()
        scrollToTheBreakdown()
        compose.onNode(hasText("By status and side") and isHeading()).assertIsDisplayed()
        compose.onNode(line("Not invited yet", "0 people", "Anna 0", "Marek 0", "Both sides 0") and hasContentDescription("Not invited yet: 0 people. Anna 0, Marek 0, both sides 0.")).assertExists()
        compose.onNode(line("Invited", "2 people", "Anna 0", "Marek 2", "Both sides 0") and hasContentDescription("Invited: 2 people. Anna 0, Marek 2, both sides 0.")).assertExists()
        compose.onNode(line("Confirmed", "8 people", "Anna 5", "Marek 1", "Both sides 2") and hasContentDescription("Confirmed: 8 people. Anna 5, Marek 1, both sides 2.")).assertExists()
        compose.onNode(line("Declined", "1 person", "Anna 0", "Marek 1", "Both sides 0") and hasContentDescription("Declined: 1 person. Anna 0, Marek 1, both sides 0.")).assertExists()
        compose.onNode(line("Maybe", "1 person", "Anna 0", "Marek 0", "Both sides 1") and hasContentDescription("Maybe: 1 person. Anna 0, Marek 0, both sides 1.")).assertExists()
        compose.onNode(line("On the list", "12 people", "Anna 5", "Marek 4", "Both sides 3") and hasContentDescription("On the list: 12 people. Anna 5, Marek 4, both sides 3.")).assertExists()
        compose.onNodeWithText("People, with plus-ones and children counted.").assertExists()
        compose.picture("guests-breakdown")
    }

    @Test
    fun `the head count is short, and the first guest is on the first screen without scrolling`() {
        open()

        openGuests()

        compose.onNode(hasText("Head count") and isHeading()).assertIsDisplayed()
        compose.onNodeWithText("8 people confirmed — 4 guests, 2 plus-ones, 2 children").assertIsDisplayed()
        compose.onNodeWithText("12 people on the list").assertIsDisplayed()
        chip("All").assertIsDisplayed()
        compose.onNode(hasText("The Zielinski family") and isHeading()).assertIsDisplayed()
        compose.onNode(hasText("Cousin Ola") and hasClickAction()).assertIsDisplayed()
        compose.picture("guests")
        // The breakdown is below the list, not above it.
        scrollToTheBreakdown()
        compose.onNode(hasText("Cousin Ola") and hasClickAction()).assertDoesNotExist()
    }

    @Test
    fun `the head count has a line for the statuses of the server's order, and everyone last`() {
        open()
        openGuests()
        scrollToTheBreakdown()

        val tops = listOf("Not invited yet", "Invited", "Confirmed", "Declined", "Maybe", "On the list").map { label ->
            compose.onNode(hasContentDescription("$label:", substring = true)).fetchSemanticsNode().positionInRoot.y
        }

        assertEquals(tops.sorted(), tops)
    }

    @Test
    fun `no head count in the copy leaves the section out and shows no zeros`() {
        aPhoneThatLoadedEarlier()
        server.plannerUnreachable()
        // What an older version of the app stored: guests, and no head count.
        SQLiteDatabase.openDatabase(folder.root.resolve("planner.db").path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE planner SET guestCounts = ''")
        }
        open()
        waitFor("No connection. This is what was loaded on 18 Nov 2026 at 14:05.")

        goTo("More")
        compose.onNode(hasText("Guests") and hasClickAction()).assertIsDisplayed()
        compose.onNodeWithText("people on the list", substring = true).assertDoesNotExist()
        compose.onNode(line("Guests", "See the guest list")).assertIsDisplayed()
        compose.onNode(hasText("Guests") and hasClickAction()).performClick()
        waitForTheList()

        compose.onNode(hasText("Head count") and isHeading()).assertDoesNotExist()
        compose.onNode(hasText("By status and side") and isHeading()).assertDoesNotExist()
        compose.onNodeWithText("On the list").assertDoesNotExist()
        compose.onNodeWithText("people on the list", substring = true).assertDoesNotExist()
        compose.onNodeWithText("0 people", substring = true).assertDoesNotExist()
        compose.onNodeWithText("0 people confirmed", substring = true).assertDoesNotExist()
        // The list is there all the same.
        compose.onNode(hasText("Aunt Krystyna") and hasClickAction()).assertIsDisplayed()
    }

    // ---------------------------------------------------------------- the list

    @Test
    fun `the guests stand under their households in the server's order, then those on their own`() {
        open()
        openGuests()

        val order = listOf(
            "The Zielinski family", "Cousin Ola", "The Nowak family", "Aunt Krystyna", "Uncle Tomek", "On their own", "Friend Bartek", "Colleague Ewa", "Mystery Guest",
        )
        // The list is lazy: it is read in two windows, and each must hold what it shows in the order of the whole.
        val seen = mutableSetOf<String>()
        for (stop in listOf("Cousin Ola", "Mystery Guest")) {
            scrollTo(hasText(stop) and hasClickAction())
            val shown = order.mapNotNull { label ->
                compose.onAllNodes(hasText(label) and (isHeading() or hasClickAction())).fetchSemanticsNodes().firstOrNull()?.let { label to it.positionInRoot.y }
            }
            assertEquals(shown.sortedBy { it.second }.map { it.first }, shown.map { it.first })
            seen += shown.map { it.first }
        }
        assertEquals(order.toSet(), seen)
        // A household says how many people its guests stand for; the Nowaks: Aunt Krystyna's four and Uncle Tomek.
        scrollTo(hasText("The Nowak family") and isHeading())
        compose.onNode(hasText("The Nowak family") and hasText("5 people") and hasContentDescription("The Nowak family, 5 people") and isHeading()).assertExists()
        scrollTo(hasText("The Zielinski family") and isHeading())
        compose.onNode(hasText("The Zielinski family") and hasText("2 people") and isHeading()).assertExists()
    }

    @Test
    fun `every fact of a guest is on their line`() {
        open()
        openGuests()
        scrollTo(hasText("Aunt Krystyna") and hasClickAction())

        compose.onNode(
            line("Aunt Krystyna", "Confirmed", "4 people", "Anna", "with a plus-one", "2 children", "needs a room", "vegetarian") and hasClickAction(),
        ).assertIsDisplayed()
        compose.onNode(
            hasContentDescription("Aunt Krystyna. Confirmed. 4 people. Anna. with a plus-one. 2 children. needs a room. vegetarian"),
        ).assertExists()
        compose.onNode(line("Uncle Tomek", "Confirmed", "Anna") and hasClickAction()).assertIsDisplayed()
        compose.onNode(hasText("Uncle Tomek") and hasText("people", substring = true)).assertDoesNotExist()
        scrollTo(hasText("Cousin Ola") and hasClickAction())
        compose.onNode(line("Cousin Ola", "Invited", "2 people", "Marek", "1 child") and hasClickAction()).assertIsDisplayed()
        scrollTo(hasText("Friend Bartek") and hasClickAction())
        compose.onNode(line("Friend Bartek", "Maybe", "Both sides") and hasClickAction()).assertIsDisplayed()
        compose.onNode(line("Colleague Ewa", "Declined", "Marek") and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun `a guest on the side of someone this phone does not know has no side said`() {
        server.planner(guests = listOf(StandIn.guest("g7", "Stray Guest", status = "INVITED", sideMemberId = "somebody-else")), guestCounts = counts)
        open()
        openGuests()
        scrollTo(hasText("Stray Guest") and hasClickAction())

        compose.onNode(line("Stray Guest", "Invited") and hasClickAction()).assertIsDisplayed()
        compose.onNode(hasText("Stray Guest") and hasText("Both sides")).assertDoesNotExist()
    }

    @Test
    fun `a state this app does not know reads Other and has its own chip`() {
        open()
        openGuests()
        scrollTo(hasText("Mystery Guest") and hasClickAction())

        compose.onNode(line("Mystery Guest", "Other", "Marek") and hasClickAction()).assertIsDisplayed()

        pressChip("Other")

        compose.onNode(hasText("Mystery Guest") and hasClickAction()).assertIsDisplayed()
        assertEquals(listOf("Mystery Guest"), shownGuests())
        // The server's head count knows nothing of this guest: it stays as it was.
        compose.onNodeWithText("12 people on the list").assertExists()
    }

    @Test
    fun `the chips are All and the statuses that occur, each narrows the list and none changes the head count`() {
        open()
        openGuests()
        scrollTo(hasText("All") and isSelectable())

        chip("All").assertIsSelected()
        for (status in listOf("Invited", "Confirmed", "Declined", "Maybe", "Other")) {
            compose.onNode(hasText(status) and isSelectable()).assertExists()
        }
        // Nobody is not yet invited: no chip for it.
        compose.onNode(hasText("Not invited yet") and isSelectable()).assertDoesNotExist()

        pressChip("Confirmed")

        chip("Confirmed").assertIsSelected()
        chip("All").assertIsNotSelected()
        assertEquals(listOf("Aunt Krystyna", "Uncle Tomek"), shownGuests())
        // Cousin Ola's household has nobody left to show, and nobody is on their own: neither heading is there.
        compose.onNode(hasText("The Zielinski family") and isHeading()).assertDoesNotExist()
        compose.onNode(hasText("The Nowak family") and isHeading()).assertExists()
        compose.onNode(hasText("On their own") and isHeading()).assertDoesNotExist()
        // Under the chosen status a household says what is shown of it.
        compose.onNode(hasText("The Nowak family") and hasText("5 people") and isHeading()).assertExists()

        pressChip("Invited")
        assertEquals(listOf("Cousin Ola"), shownGuests())
        compose.onNode(hasText("The Zielinski family") and isHeading()).assertExists()
        compose.onNode(hasText("The Nowak family") and isHeading()).assertDoesNotExist()

        pressChip("Maybe")
        assertEquals(listOf("Friend Bartek"), shownGuests())
        // Only guests on their own: no household heading at all, and nothing said of being on their own.
        compose.onNode(hasText("On their own") and isHeading()).assertDoesNotExist()
        compose.onNode(hasText("The Nowak family") and isHeading()).assertDoesNotExist()

        pressChip("Declined")
        assertEquals(listOf("Colleague Ewa"), shownGuests())

        pressChip("All")
        for (name in listOf("Cousin Ola", "Aunt Krystyna", "Uncle Tomek", "Friend Bartek", "Colleague Ewa", "Mystery Guest")) {
            scrollTo(hasText(name) and hasClickAction())
        }

        // The head count stayed whole through all of it.
        scrollTo(hasText("Head count") and isHeading())
        compose.onNodeWithText("8 people confirmed — 4 guests, 2 plus-ones, 2 children").assertIsDisplayed()
        compose.onNodeWithText("12 people on the list").assertIsDisplayed()
        scrollToTheBreakdown()
        compose.onNode(hasContentDescription("On the list: 12 people. Anna 5, Marek 4, both sides 3.")).assertExists()
    }

    @Test
    fun `the head count does not move when a status is chosen`() {
        open()
        openGuests()
        compose.onNodeWithText("8 people confirmed — 4 guests, 2 plus-ones, 2 children").assertIsDisplayed()
        pressChip("Declined")

        scrollTo(hasText("Head count") and isHeading())
        compose.onNodeWithText("8 people confirmed — 4 guests, 2 plus-ones, 2 children").assertIsDisplayed()
        scrollToTheBreakdown()
        compose.onNode(hasContentDescription("Declined: 1 person. Anna 0, Marek 1, both sides 0.")).assertExists()
        compose.onNode(hasContentDescription("Confirmed: 8 people. Anna 5, Marek 1, both sides 2.")).assertExists()
    }

    @Test
    fun `the chosen status is kept while the phone is turned`() {
        runBlocking { phone.container.planner.load() }
        val planner = runBlocking { phone.container.planner.planner.first() }!!
        val turning = StateRestorationTester(compose)
        turning.setContent { PlannerTheme { GuestsContent(planner, onOpen = {}, foot = { Text("Loaded on a day") }) } }
        pressChip("Declined")
        assertEquals(listOf("Colleague Ewa"), shownGuests())

        turning.emulateSavedInstanceStateRestore()

        chip("Declined").assertIsSelected()
        assertEquals(listOf("Colleague Ewa"), shownGuests())
    }

    @Test
    fun `when the chosen status has nobody any more the list says nobody matches and the chip stays`() {
        open()
        openGuests()
        pressChip("Declined")
        server.planner(guests = listOf(krystyna, tomek, ola), households = households, guestCounts = counts)

        compose.onNodeWithContentDescription("Load again").performClick()

        waitFor("Nobody matches. Choose another status.")
        chip("Declined").assertIsSelected()
        assertTrue(shownGuests().isEmpty())

        pressChip("All")
        compose.onNode(hasText("Cousin Ola") and hasClickAction()).assertIsDisplayed()
        compose.onNodeWithText("Nobody matches. Choose another status.").assertDoesNotExist()
    }

    @Test
    fun `with no guests the list says where to add them, and there is no head count and nothing to add with`() {
        server.planner()
        open()

        openGuests()

        compose.onNodeWithText("No guests yet. Add the people you are inviting in the web app.").assertIsDisplayed()
        compose.onNode(hasText("All") and isSelectable()).assertDoesNotExist()
        compose.onNode(hasText("Head count") and isHeading()).assertDoesNotExist()
        compose.onNodeWithText("0 people", substring = true).assertDoesNotExist()
        assertTrue(compose.onAllNodes(hasClickAction() and hasText("Add", substring = true)).fetchSemanticsNodes().isEmpty())
        compose.picture("guests-empty")
    }

    // ---------------------------------------------------------------- one guest

    @Test
    fun `one guest shows everything their line says, the household, the contact rows and the notes`() {
        open()
        openGuests()

        openGuest("Aunt Krystyna")

        compose.onNode(hasText("More") and isSelectable()).assertIsSelected()
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        compose.onNode(line("Confirmed", "4 people", "Anna", "with a plus-one", "2 children", "needs a room")).assertIsDisplayed()
        compose.onNode(line("Household", "The Nowak family")).assertIsDisplayed()
        compose.onNode(line("Dietary needs", "vegetarian")).assertIsDisplayed()
        compose.onNode(hasText("Contact") and isHeading()).assertIsDisplayed()
        compose.onNode(line("Phone", "+48 600 000 011") and hasContentDescription("Call +48 600 000 011") and hasClickAction()).assertIsDisplayed()
        compose.onNode(
            line("E-mail", "krystyna@guests.example") and hasContentDescription("Write an e-mail to krystyna@guests.example") and hasClickAction(),
        ).assertIsDisplayed()
        compose.onNodeWithText("No phone number or e-mail address yet.").assertDoesNotExist()
        compose.onNode(hasText("Notes") and isHeading()).assertIsDisplayed()
        compose.onNodeWithText("Allergic to nuts.").assertIsDisplayed()
        compose.picture("guest")
    }

    @Test
    fun `a guest on their own with nothing filled in says so`() {
        open()
        openGuests()

        openGuest("Friend Bartek")

        compose.onNode(line("Maybe", "Both sides")).assertIsDisplayed()
        compose.onNode(line("Household", "On their own")).assertIsDisplayed()
        compose.onNodeWithText("No phone number or e-mail address yet.").assertIsDisplayed()
        compose.onNode(hasText("Notes") and isHeading()).assertDoesNotExist()
        compose.onNodeWithText("Dietary needs").assertDoesNotExist()
    }

    @Test
    fun `a guest that was removed meanwhile says it is no longer there and leads back`() {
        open()
        openGuests()
        openGuest("Cousin Ola")
        server.planner(guests = listOf(krystyna), households = households, guestCounts = counts)

        compose.onNodeWithContentDescription("Load again").performClick()

        waitFor("This guest is no longer there.")
        compose.onNode(hasText("Guest") and isHeading()).assertIsDisplayed()
        compose.onNodeWithText("Cousin Ola").assertDoesNotExist()

        compose.onNode(hasText("Back") and hasClickAction()).performClick()

        waitForTheList()
        compose.onNode(hasText("Aunt Krystyna") and hasClickAction()).assertIsDisplayed()
        compose.onNodeWithText("Cousin Ola").assertDoesNotExist()
    }

    // ---------------------------------------------------------------- opening the phone's apps

    private fun started(): Intent? = shadowOf(compose.activity).nextStartedActivity

    private fun pressContact(description: String) {
        scrollTo(hasContentDescription(description))
        compose.onNode(hasContentDescription(description) and hasClickAction()).performClick()
    }

    @Test
    fun `the phone number opens the dialler, and only dials`() {
        open()
        openGuests()
        openGuest("Aunt Krystyna")

        pressContact("Call +48 600 000 011")

        val intent = started()!!
        assertEquals(Intent.ACTION_DIAL, intent.action)
        assertEquals("tel:+48600000011", intent.data.toString())
    }

    @Test
    fun `the e-mail address opens the mail app`() {
        open()
        openGuests()
        openGuest("Aunt Krystyna")

        pressContact("Write an e-mail to krystyna@guests.example")

        val intent = started()!!
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals("mailto:krystyna@guests.example", intent.data.toString())
    }

    @Test
    fun `with no app to take it the screen stays and says so, and the next press that works takes the message away`() {
        open()
        openGuests()
        openGuest("Aunt Krystyna")
        shadowOf(compose.activity.application).checkActivities(true)

        pressContact("Call +48 600 000 011")

        compose.onNodeWithText("No app on this phone can open that.").assertIsDisplayed()
        compose.onNode(hasText("Aunt Krystyna") and isHeading()).assertIsDisplayed()
        assertNull(started())

        shadowOf(compose.activity.application).checkActivities(false)
        pressContact("Call +48 600 000 011")

        compose.onNodeWithText("No app on this phone can open that.").assertDoesNotExist()
        assertNotNull(started())
    }

    // ---------------------------------------------------------------- getting there and back

    @Test
    fun `Back goes from the guest to the list and from the list to More`() {
        open()
        openGuests()
        openGuest("Aunt Krystyna")

        pressBackButton()

        waitForTheList()
        compose.onNode(hasText("Confirmed") and isSelectable()).assertIsDisplayed()

        compose.onNodeWithContentDescription("Back").performClick()

        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("More") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Guests") and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun `pressing More while on a guest or on the list leads to the list of what More holds`() {
        open()
        openGuests()
        openGuest("Aunt Krystyna")

        compose.onNode(hasText("More") and isSelectable()).performClick()

        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("More") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Guests") and hasClickAction()).assertIsDisplayed()
        compose.onNodeWithText("Contact").assertDoesNotExist()
    }

    // ---------------------------------------------------------------- without a connection, at night, big, logged and kept

    @Test
    fun `without a connection the guests and the head count are shown from the last load, with from when it is`() {
        aPhoneThatLoadedEarlier()
        server.plannerUnreachable()
        phone.clock.now = phone.clock.now.plusSeconds(3 * 3600)
        open()
        waitFor("No connection. This is what was loaded on 18 Nov 2026 at 14:05.")

        openGuests()

        compose.onNodeWithText("No connection. This is what was loaded on 18 Nov 2026 at 14:05.").assertIsDisplayed()
        compose.onNodeWithText("8 people confirmed — 4 guests, 2 plus-ones, 2 children").assertIsDisplayed()
        scrollTo(hasText("Aunt Krystyna") and hasClickAction())
        compose.onNode(line("Aunt Krystyna", "Confirmed", "4 people")).assertIsDisplayed()

        openGuest("Aunt Krystyna")

        compose.onNode(line("Phone", "+48 600 000 011")).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = PHONE_AT_NIGHT)
    fun `the guests read in the dark as well`() {
        open()
        openGuests()
        compose.onNodeWithText("8 people confirmed — 4 guests, 2 plus-ones, 2 children").assertIsDisplayed()
        compose.picture("guests-at-night")
        scrollTo(hasText("Aunt Krystyna") and hasClickAction())
        compose.picture("guests-at-night-list")

        openGuest("Aunt Krystyna")

        compose.onNode(line("Phone", "+48 600 000 011")).assertIsDisplayed()
        compose.picture("guest-at-night")
    }

    @Test
    fun `with the largest text nothing is cut off`() {
        open(fontScale = 2f)
        goTo("More")
        compose.onNode(hasText("Guests") and hasClickAction()).performClick()
        waitForTheList()
        compose.onNodeWithText("8 people confirmed — 4 guests, 2 plus-ones, 2 children").assertIsDisplayed()
        compose.picture("guests-big-letters")
        scrollTo(hasText("Aunt Krystyna") and hasClickAction())
        compose.picture("guests-big-letters-list")
        compose.onNode(hasText("Aunt Krystyna") and hasClickAction()).performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Aunt Krystyna") and isHeading()).fetchSemanticsNodes().isNotEmpty() }

        compose.onNode(line("Phone", "+48 600 000 011")).assertIsDisplayed()
        compose.picture("guest-big-letters")
    }

    @Test
    fun `nothing of a guest is written to the log`() {
        ShadowLog.clear()
        open()
        openGuests()
        pressChip("Confirmed")
        openGuest("Aunt Krystyna")
        pressContact("Call +48 600 000 011")
        shadowOf(compose.activity.application).checkActivities(true)
        pressContact("Write an e-mail to krystyna@guests.example")
        server.plannerUnreachable()
        compose.onNodeWithContentDescription("Load again").performClick()
        waitFor("No connection. This is what was loaded on 18 Nov 2026 at 14:05.")

        val written = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable?.stackTraceToString().orEmpty()}" }
        for (theirs in listOf(
            "Krystyna", "Tomek", "Ola", "Bartek", "Ewa", "Mystery", "Nowak", "Zielinski", "600 000", "600000", "guests.example", "vegetarian",
            "Allergic", "nuts", "Anna", "Marek", "access-1",
        )) {
            assertFalse("the log holds \"$theirs\"", written.contains(theirs, ignoreCase = true))
        }
    }

    @Test
    fun `signing out leaves nothing of the guests on the phone`() {
        open()
        val kept = phone.everythingKept()
        for (theirs in listOf("Aunt Krystyna", "krystyna@guests.example", "+48 600 000 011", "vegetarian", "Allergic to nuts.", "The Nowak family")) {
            assertTrue("the phone does not keep \"$theirs\" to begin with", kept.contains(theirs))
        }
        goTo("More")
        scrollTo(hasText("Sign out"))

        compose.onNodeWithText("Sign out").performClick()

        waitFor("Sign in to see your plan.")
        val left = phone.everythingKept()
        for (theirs in listOf(
            "Aunt Krystyna", "Uncle Tomek", "Cousin Ola", "Friend Bartek", "Colleague Ewa", "Mystery Guest", "krystyna@guests.example", "+48 600 000 011",
            "vegetarian", "Allergic to nuts.", "The Nowak family", "The Zielinski family",
        )) {
            assertFalse("the phone still keeps \"$theirs\"", left.contains(theirs))
        }
    }
}
