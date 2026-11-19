package io.github.trufelek124.weddingplanner.planner

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.trufelek124.weddingplanner.data.LivingPlanner
import io.github.trufelek124.weddingplanner.data.StandIn
import io.github.trufelek124.weddingplanner.data.planner
import io.github.trufelek124.weddingplanner.data.plannerUnreachable
import io.github.trufelek124.weddingplanner.ui.PHONE
import io.github.trufelek124.weddingplanner.ui.PlannerTheme
import io.github.trufelek124.weddingplanner.ui.PHONE_AT_NIGHT
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
 * The vendors behind More: the list narrowed down by status, and one vendor with its contact details,
 * its money and what concerns it. The same facts and words as the web's vendors pages, kept on the
 * phone and shown without a connection. Everyone here is made up.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class VendorsScreenTest : SignedInPhoneTest() {

    private fun zl(amount: String) = "$amount zł"

    private val categories = listOf(StandIn.category("c1", "Venue"), StandIn.category("c2", "Music"), StandIn.category("c3", "Flowers and decor"))

    /** Booked, with every contact detail, a price and payments that do not reach it yet. */
    private val palace = StandIn.vendor(
        "v1", "Palace on the Hill", status = "BOOKED", categoryId = "c1", contactPerson = "Jan Palacowy", phone = "+48 600 000 001",
        email = "events@palace.example", website = "https://www.palace.example/weddings", address = "Hill Road 1, Gdansk",
        notes = "Prefers the deposit in two parts.", agreedPrice = 1000000, committed = 700000, paid = 300000, inspirations = 2,
    )

    /** Considered only: no contact, no money. */
    private val studio = StandIn.vendor("v2", "Studio Light", status = "CONSIDERING", categoryId = "c2")

    private val barn = StandIn.vendor("v3", "Old Barn Band", status = "DROPPED", categoryId = "c2", contactPerson = "Ola")

    /** Payments beyond the agreed price. */
    private val flowers = StandIn.vendor(
        "v4", "Flower Cart", status = "BOOKED", categoryId = "c3", phone = "600 000 004", agreedPrice = 500000, committed = 620000, paid = 0,
    )

    /** A state that a newer server knows. */
    private val caterer = StandIn.vendor("v5", "Mystery Caterer", status = "ARCHIVED")

    /** Payments and no agreed price. */
    private val cake = StandIn.vendor("v6", "Cake Lady", status = "BOOKED", committed = 40000, paid = 40000)

    private val vendors = listOf(palace, studio, barn, flowers, caterer, cake)

    private val deposit = StandIn.entry(
        "t1", "Pay the deposit", dueDate = "2026-11-25", priority = "HIGH", priorityReason = "DUE_WITHIN_14_DAYS", parentId = "p1",
        vendorId = "v1", amount = 300000, paymentKind = "DEPOSIT",
    )
    private val visit = StandIn.entry("t2", "Visit the Palace", vendorId = "v1")
    private val book = StandIn.entry("p1", "Book the venue", kind = "PLAN_ITEM", dueDate = "2026-12-15", status = "IN_PROGRESS", vendorId = "v1", taskCount = 1)
    private val elsewhere = StandIn.entry("t3", "Choose the rings")

    private val entries = listOf(deposit, visit, book, elsewhere)

    @Before
    fun theirVendors() {
        server.planner(vendors = vendors, entries = entries, categories = categories)
    }

    private val sayingOpenTheVendor = SemanticsMatcher("leads to a vendor") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == "see this vendor"
    }

    private fun openVendors() {
        goTo("More")
        compose.onNode(hasText("Vendors") and hasClickAction()).performClick()
        waitForTheList()
    }

    private fun waitForTheList() {
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Vendors") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun openVendor(name: String) {
        compose.onNode(hasText(name) and hasClickAction()).performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText(name) and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun pressBackButton() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    private fun chip(name: String) = compose.onNode(hasText(name) and hasClickAction() and isSelectable())

    /** The chips scroll sideways: one that is out of sight is brought in first. */
    private fun pressChip(name: String) {
        chip(name).performScrollTo().performClick()
    }

    private fun theRowsPressed(): List<String> = compose.onAllNodes(sayingOpenTheVendor).fetchSemanticsNodes().map { node ->
        node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.firstOrNull()?.text.orEmpty()
    }

    // ---------------------------------------------------------------- More

    @Test
    fun `More has a row for the vendors that says how many there are and how many are booked`() {
        open()

        goTo("More")

        compose.onNode(line("Vendors", "6 vendors, 3 booked") and hasClickAction()).assertIsDisplayed()
        // The budget is still there beside it.
        compose.onNode(hasText("Budget") and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun `More says that there are no vendors yet`() {
        server.planner()
        open()

        goTo("More")

        compose.onNode(line("Vendors", "No vendors yet")).assertIsDisplayed()
    }

    // ---------------------------------------------------------------- the list

    @Test
    fun `the list tells each vendor with its status, category, contact person and money`() {
        open()

        openVendors()

        compose.onNode(hasText("More") and isSelectable()).assertIsSelected()
        compose.onNode(
            line("Palace on the Hill", "Booked", "Venue", "Jan Palacowy", "${zl("10,000.00")} agreed", "${zl("3,000.00")} paid") and hasClickAction(),
        ).assertIsDisplayed()
        // Nothing agreed and nothing paid: no money to say.
        compose.onNode(line("Studio Light", "Considering", "Music")).assertIsDisplayed()
        compose.onNode(hasText("Studio Light") and hasText("paid", substring = true)).assertDoesNotExist()
        compose.onNode(line("Flower Cart", "Booked", "Flowers and decor", "${zl("5,000.00")} agreed", "${zl("0.00")} paid")).assertIsDisplayed()
        // Payments and no price: only what is paid.
        compose.onNode(line("Cake Lady", "Booked", "${zl("400.00")} paid")).assertIsDisplayed()
        compose.onNode(hasText("Cake Lady") and hasText("agreed", substring = true)).assertDoesNotExist()
        compose.onNode(line("Old Barn Band", "Dropped", "Music", "Ola")).assertIsDisplayed()
        compose.onNode(hasContentDescription("Palace on the Hill. Booked. Venue. Jan Palacowy. ${zl("10,000.00")} agreed. ${zl("3,000.00")} paid")).assertExists()
        compose.picture("vendors")
    }

    @Test
    fun `a state this app does not know reads Other and has its own chip`() {
        open()
        openVendors()

        compose.onNode(line("Mystery Caterer", "Other")).assertIsDisplayed()

        pressChip("Other")

        compose.onNode(hasText("Mystery Caterer") and hasClickAction()).assertIsDisplayed()
        assertEquals(1, theRowsPressed().size)
    }

    @Test
    fun `a dropped vendor is listed, after the others`() {
        open()
        openVendors()

        val tops = listOf("Palace on the Hill", "Studio Light", "Flower Cart", "Mystery Caterer", "Cake Lady", "Old Barn Band").map { name ->
            compose.onNode(hasText(name) and hasClickAction()).fetchSemanticsNode().positionInRoot.y
        }

        assertEquals(tops.sorted(), tops)
    }

    @Test
    fun `the chips are All and the statuses that occur, and each narrows the list`() {
        open()
        openVendors()

        chip("All").assertIsSelected()
        for (status in listOf("Considering", "Booked", "Dropped", "Other")) {
            compose.onNode(hasText(status) and isSelectable()).assertExists()
        }

        pressChip("Booked")

        chip("Booked").assertIsSelected()
        chip("All").assertIsNotSelected()
        compose.onNode(hasText("Palace on the Hill") and hasClickAction()).assertIsDisplayed()
        compose.onNode(hasText("Flower Cart") and hasClickAction()).assertIsDisplayed()
        compose.onNode(hasText("Cake Lady") and hasClickAction()).assertIsDisplayed()
        compose.onNode(hasText("Studio Light") and hasClickAction()).assertDoesNotExist()
        compose.onNode(hasText("Old Barn Band") and hasClickAction()).assertDoesNotExist()

        pressChip("Considering")
        compose.onNode(hasText("Studio Light") and hasClickAction()).assertIsDisplayed()
        assertEquals(1, theRowsPressed().size)

        pressChip("Dropped")
        compose.onNode(hasText("Old Barn Band") and hasClickAction()).assertIsDisplayed()
        assertEquals(1, theRowsPressed().size)

        pressChip("All")
        assertEquals(6, theRowsPressed().size)
    }

    @Test
    fun `only the statuses that occur are offered`() {
        server.planner(vendors = listOf(palace, cake), categories = categories)
        open()
        openVendors()

        compose.onNode(hasText("Booked") and isSelectable()).assertExists()
        compose.onNode(hasText("Dropped") and isSelectable()).assertDoesNotExist()
        compose.onNode(hasText("Considering") and isSelectable()).assertDoesNotExist()
    }

    @Test
    fun `the chosen status is kept while the phone is turned`() {
        runBlocking { phone.container.planner.load() }
        val planner = runBlocking { phone.container.planner.planner.first() }!!
        val turning = StateRestorationTester(compose)
        turning.setContent { PlannerTheme { VendorsContent(planner, onOpen = {}) } }
        pressChip("Dropped")
        assertEquals(1, theRowsPressed().size)

        turning.emulateSavedInstanceStateRestore()

        chip("Dropped").assertIsSelected()
        compose.onNode(hasText("Old Barn Band") and hasClickAction()).assertIsDisplayed()
        assertEquals(1, theRowsPressed().size)
    }

    @Test
    fun `when the chosen status has no vendor any more the list says nothing matches and the chip stays`() {
        open()
        openVendors()
        pressChip("Dropped")
        server.planner(vendors = listOf(palace, studio, flowers), categories = categories, entries = entries)

        compose.onNodeWithContentDescription("Load again").performClick()

        waitFor("No vendor matches. Choose another status.")
        chip("Dropped").assertIsSelected()
        assertTrue(theRowsPressed().isEmpty())

        pressChip("All")
        compose.onNode(hasText("Studio Light") and hasClickAction()).assertIsDisplayed()
        compose.onNodeWithText("No vendor matches. Choose another status.").assertDoesNotExist()
    }

    @Test
    fun `with no vendors the list says where to add them`() {
        server.planner()
        open()

        openVendors()

        compose.onNodeWithText("No vendors yet. Add the ones you are thinking about in the web app.").assertIsDisplayed()
        compose.onNode(hasText("All") and isSelectable()).assertDoesNotExist()
        assertTrue(compose.onAllNodes(hasClickAction() and hasText("Add", substring = true)).fetchSemanticsNodes().isEmpty())
        compose.picture("vendors-empty")
    }

    // ---------------------------------------------------------------- one vendor

    @Test
    fun `one vendor shows every contact row, its notes, its money and what concerns it`() {
        open()
        openVendors()

        openVendor("Palace on the Hill")

        compose.onNode(hasText("More") and isSelectable()).assertIsSelected()
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        compose.onNode(line("Booked", "Venue", "2 inspirations")).assertIsDisplayed()
        compose.onNode(hasText("Contact") and isHeading()).assertIsDisplayed()
        compose.onNode(line("Contact person", "Jan Palacowy")).assertIsDisplayed()
        compose.onNode(line("Phone", "+48 600 000 001") and hasContentDescription("Call +48 600 000 001") and hasClickAction()).assertIsDisplayed()
        compose.onNode(line("E-mail", "events@palace.example") and hasContentDescription("Write an e-mail to events@palace.example") and hasClickAction()).assertIsDisplayed()
        compose.onNode(line("Website", "palace.example") and hasContentDescription("Open the website palace.example") and hasClickAction()).assertIsDisplayed()
        compose.onNode(line("Address", "Hill Road 1, Gdansk") and hasContentDescription("Show the address on a map: Hill Road 1, Gdansk") and hasClickAction()).assertIsDisplayed()
        compose.onNodeWithText("No contact details yet.").assertDoesNotExist()
        compose.onNode(hasText("Notes") and isHeading()).assertIsDisplayed()
        compose.onNodeWithText("Prefers the deposit in two parts.").assertIsDisplayed()
        compose.picture("vendor")
    }

    @Test
    fun `the money of a vendor with a price and payments that do not reach it`() {
        open()
        openVendors()

        openVendor("Palace on the Hill")

        scrollTo(hasText("Money") and isHeading())
        compose.onNode(hasText("Money") and isHeading()).assertIsDisplayed()
        compose.onNode(line("${zl("10,000.00")} agreed.", "${zl("3,000.00")} paid, ${zl("4,000.00")} still to pay.")).assertIsDisplayed()
        compose.onNode(hasContentDescription("Palace on the Hill: ${zl("3,000.00")} paid, ${zl("4,000.00")} still to pay, of ${zl("10,000.00")} agreed")).assertExists()
        compose.onNodeWithText("${zl("3,000.00")} of the agreed price has no payment yet.").assertIsDisplayed()
        compose.onNodeWithText("The payments come to", substring = true).assertDoesNotExist()
    }

    @Test
    fun `payments over the agreed price are said in words`() {
        open()
        openVendors()

        openVendor("Flower Cart")

        scrollTo(hasText("Money") and isHeading())
        compose.onNode(line("${zl("5,000.00")} agreed.", "${zl("0.00")} paid, ${zl("6,200.00")} still to pay.")).assertIsDisplayed()
        compose.onNodeWithText("The payments come to ${zl("1,200.00")} more than was agreed.").assertIsDisplayed()
        compose.onNode(
            hasContentDescription("Flower Cart: ${zl("0.00")} paid, ${zl("6,200.00")} still to pay: ${zl("1,200.00")} more than the ${zl("5,000.00")} agreed"),
        ).assertExists()
        compose.picture("vendor-over")
    }

    @Test
    fun `payments and no price, and a price and no payment, say what they are`() {
        server.planner(
            vendors = listOf(
                cake,
                StandIn.vendor("v7", "Ring Maker", status = "BOOKED", agreedPrice = 250000),
                StandIn.vendor("v8", "Photo Place", status = "BOOKED", agreedPrice = 400000, committed = 400000, paid = 400000),
            ),
        )
        open()
        openVendors()

        openVendor("Cake Lady")
        scrollTo(hasText("Money") and isHeading())
        compose.onNode(line("No price agreed.", "${zl("400.00")} paid.")).assertIsDisplayed()
        compose.onNodeWithText("of the agreed price", substring = true).assertDoesNotExist()
        pressBackButton()
        waitForTheList()

        openVendor("Ring Maker")
        scrollTo(hasText("Money") and isHeading())
        compose.onNode(line("${zl("2,500.00")} agreed.", "${zl("0.00")} paid.")).assertIsDisplayed()
        compose.onNodeWithText("${zl("2,500.00")} of the agreed price has no payment yet.").assertIsDisplayed()
        // No payment: no bar.
        compose.onNode(hasContentDescription("Ring Maker:", substring = true)).assertDoesNotExist()
        pressBackButton()
        waitForTheList()

        openVendor("Photo Place")
        scrollTo(hasText("Money") and isHeading())
        compose.onNode(line("${zl("4,000.00")} agreed.", "${zl("4,000.00")} paid.")).assertIsDisplayed()
        // Exactly as agreed: nothing more to say.
        compose.onNodeWithText("agreed price has no payment", substring = true).assertDoesNotExist()
        compose.onNodeWithText("more than was agreed", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a vendor with nothing agreed and no payments says so, and has no contact details`() {
        open()
        openVendors()

        openVendor("Studio Light")

        compose.onNodeWithText("No contact details yet.").assertIsDisplayed()
        compose.onNode(hasText("Notes") and isHeading()).assertDoesNotExist()
        scrollTo(hasText("Money") and isHeading())
        compose.onNodeWithText("Nothing agreed and no payments yet.").assertIsDisplayed()
        compose.onNode(hasContentDescription("Studio Light:", substring = true)).assertDoesNotExist()
        scrollTo(hasText("Tasks and payments") and isHeading())
        assertEquals(2, compose.onAllNodes(hasText("None yet.")).fetchSemanticsNodes().size)
    }

    @Test
    fun `what concerns the vendor is told as the other places tell it, and nothing else`() {
        open()
        openVendors()

        openVendor("Palace on the Hill")

        scrollTo(hasText("Plan items") and isHeading())
        compose.onNode(hasText("Plan items") and isHeading()).assertExists()
        scrollTo(line("Book the venue", "In progress"))
        compose.onNode(line("Book the venue", "In progress")).assertIsDisplayed()
        scrollTo(hasText("Tasks and payments") and isHeading())
        compose.onNode(hasText("Tasks and payments") and isHeading()).assertExists()
        scrollTo(hasText("Visit the Palace"))
        compose.onNode(line("Pay the deposit", "${zl("3,000.00")}, deposit, to pay")).assertExists()
        compose.onNode(line("Pay the deposit", "Palace on the Hill")).assertDoesNotExist()
        compose.onNode(line("Visit the Palace") and hasClickAction()).assertIsDisplayed()
        compose.onNodeWithText("Choose the rings").assertDoesNotExist()
        compose.picture("vendor-lower")
    }

    @Test
    fun `nothing points to a vendor, and it says so`() {
        open()
        openVendors()

        openVendor("Cake Lady")

        scrollTo(hasText("Tasks and payments") and isHeading())
        assertEquals(2, compose.onAllNodes(hasText("None yet.")).fetchSemanticsNodes().size)
    }

    @Test
    fun `a task is ticked off from the vendor and opened as on the tasks`() {
        val theirs = LivingPlanner(server, entries = entries, categories = categories, vendors = vendors)
        open()
        openVendors()
        openVendor("Palace on the Hill")
        scrollTo(hasText("Visit the Palace"))

        compose.onNodeWithContentDescription("Done: Visit the Palace").assertIsOff()
        compose.onNodeWithContentDescription("Done: Visit the Palace").performClick()

        waitUntil { theirs.statusOf("t2") == "DONE" }
        compose.onNodeWithContentDescription("Done: Visit the Palace").assertIsOn()

        compose.onNode(hasText("Visit the Palace") and hasClickAction()).performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Change the task") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        pressBackButton()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Palace on the Hill") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `a vendor that was removed meanwhile says it is no longer there and leads back`() {
        open()
        openVendors()
        openVendor("Studio Light")
        server.planner(vendors = listOf(palace), categories = categories, entries = entries)

        compose.onNodeWithContentDescription("Load again").performClick()

        waitFor("This vendor is no longer there.")
        compose.onNode(hasText("Vendor") and isHeading()).assertIsDisplayed()
        compose.onNodeWithText("Studio Light").assertDoesNotExist()

        compose.onNode(hasText("Back") and hasClickAction()).performClick()

        waitForTheList()
        compose.onNode(hasText("Palace on the Hill") and hasClickAction()).assertIsDisplayed()
        compose.onNodeWithText("Studio Light").assertDoesNotExist()
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
        openVendors()
        openVendor("Palace on the Hill")

        pressContact("Call +48 600 000 001")

        val intent = started()!!
        assertEquals(Intent.ACTION_DIAL, intent.action)
        assertEquals("tel:+48600000001", intent.data.toString())
    }

    @Test
    fun `the e-mail address opens the mail app`() {
        open()
        openVendors()
        openVendor("Palace on the Hill")

        pressContact("Write an e-mail to events@palace.example")

        val intent = started()!!
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals("mailto:events@palace.example", intent.data.toString())
    }

    @Test
    fun `the address opens a map`() {
        open()
        openVendors()
        openVendor("Palace on the Hill")

        pressContact("Show the address on a map: Hill Road 1, Gdansk")

        val intent = started()!!
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("geo:0,0?q=Hill%20Road%201%2C%20Gdansk", intent.data.toString())
    }

    @Test
    fun `the website opens in the browser`() {
        open()
        openVendors()
        openVendor("Palace on the Hill")

        pressContact("Open the website palace.example")

        val intent = started()!!
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://www.palace.example/weddings", intent.data.toString())
    }

    @Test
    fun `a website that is not a web address is shown as text and opens nothing`() {
        server.planner(vendors = listOf(StandIn.vendor("v9", "Odd Site", website = "javascript:alert(1)", phone = "+48 600 000 009")))
        open()
        openVendors()
        openVendor("Odd Site")

        compose.onNode(line("Website", "javascript:alert(1)")).assertIsDisplayed()
        compose.onNode(hasText("javascript:alert(1)") and hasClickAction()).assertDoesNotExist()
        compose.onNode(hasContentDescription("Open the website", substring = true)).assertDoesNotExist()
        // Pressing the text does nothing; another row still works.
        compose.onNodeWithText("javascript:alert(1)").performClick()
        assertNull(started())
        pressContact("Call +48 600 000 009")
        assertEquals(Intent.ACTION_DIAL, started()!!.action)
    }

    @Test
    fun `with no app to take it the screen stays and says so, and the next press that works takes the message away`() {
        open()
        openVendors()
        openVendor("Palace on the Hill")
        shadowOf(compose.activity.application).checkActivities(true)

        pressContact("Call +48 600 000 001")

        compose.onNodeWithText("No app on this phone can open that.").assertIsDisplayed()
        compose.onNode(hasText("Palace on the Hill") and isHeading()).assertIsDisplayed()
        assertNull(started())

        shadowOf(compose.activity.application).checkActivities(false)
        pressContact("Call +48 600 000 001")

        compose.onNodeWithText("No app on this phone can open that.").assertDoesNotExist()
        assertNotNull(started())
    }

    // ---------------------------------------------------------------- getting there and back

    @Test
    fun `Back goes from the vendor to the list and from the list to More`() {
        open()
        openVendors()
        openVendor("Palace on the Hill")

        pressBackButton()

        waitForTheList()
        compose.onNode(hasText("Booked") and isSelectable()).assertIsDisplayed()

        compose.onNodeWithContentDescription("Back").performClick()

        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("More") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Vendors") and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun `pressing More while on a vendor or on the list leads to the list of what More holds`() {
        open()
        openVendors()
        openVendor("Palace on the Hill")

        compose.onNode(hasText("More") and isSelectable()).performClick()

        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("More") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Vendors") and hasClickAction()).assertIsDisplayed()
        compose.onNodeWithText("Contact").assertDoesNotExist()
    }

    @Test
    fun `the budget is still reached from More, and a vendor and the budget do not mix`() {
        open()
        goTo("More")
        compose.onNode(hasText("Budget") and hasClickAction()).performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("No budget yet.", substring = true)).fetchSemanticsNodes().isNotEmpty() }

        compose.onNode(hasText("More") and isSelectable()).performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Vendors") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Vendors") and hasClickAction()).performClick()
        waitForTheList()

        compose.onNodeWithText("No budget yet.", substring = true).assertDoesNotExist()
    }

    // ---------------------------------------------------------------- without a connection, kept and logged

    @Test
    fun `without a connection the vendors are shown from the last load, with from when it is`() {
        aPhoneThatLoadedEarlier()
        server.plannerUnreachable()
        phone.clock.now = phone.clock.now.plusSeconds(3 * 3600)
        open()
        waitFor("No connection. This is what was loaded on 18 Nov 2026 at 14:05.")

        openVendors()

        compose.onNodeWithText("No connection. This is what was loaded on 18 Nov 2026 at 14:05.").assertIsDisplayed()
        compose.onNode(line("Palace on the Hill", "Booked", "Venue")).assertIsDisplayed()

        openVendor("Palace on the Hill")

        compose.onNode(line("Phone", "+48 600 000 001")).assertIsDisplayed()
        compose.onNodeWithText("Loaded on 18 Nov 2026 at 17:05", substring = true).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = PHONE_AT_NIGHT)
    fun `the vendors read in the dark as well`() {
        open()
        openVendors()
        compose.onNode(line("Palace on the Hill", "Booked")).assertIsDisplayed()
        compose.picture("vendors-at-night")

        openVendor("Palace on the Hill")

        compose.onNode(line("Phone", "+48 600 000 001")).assertIsDisplayed()
        compose.picture("vendor-at-night")
    }

    @Test
    fun `with the largest text nothing is cut off`() {
        open(fontScale = 2f)
        goTo("More")
        compose.onNode(hasText("Vendors") and hasClickAction()).performClick()
        waitForTheList()
        compose.picture("vendors-big-letters")
        compose.onNode(hasText("Palace on the Hill") and hasClickAction()).performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Palace on the Hill") and isHeading()).fetchSemanticsNodes().isNotEmpty() }

        compose.onNode(line("Phone", "+48 600 000 001")).assertIsDisplayed()
        compose.picture("vendor-big-letters")
        scrollTo(hasText("Money") and isHeading())
        compose.picture("vendor-big-letters-money")
    }

    @Test
    fun `nothing of a vendor is written to the log`() {
        ShadowLog.clear()
        open()
        openVendors()
        pressChip("Booked")
        openVendor("Palace on the Hill")
        pressContact("Call +48 600 000 001")
        shadowOf(compose.activity.application).checkActivities(true)
        pressContact("Open the website palace.example")
        server.plannerUnreachable()
        compose.onNodeWithContentDescription("Load again").performClick()
        waitFor("No connection. This is what was loaded on 18 Nov 2026 at 14:05.")

        val written = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable?.stackTraceToString().orEmpty()}" }
        for (theirs in listOf(
            "Palace", "Jan Palacowy", "Studio Light", "Flower Cart", "600 000", "600000", "events@", "palace.example", "Hill Road", "Gdansk",
            "deposit in two parts", "Anna", "Marek", "1000000", "access-1",
        )) {
            assertFalse("the log holds \"$theirs\"", written.contains(theirs, ignoreCase = true))
        }
    }

    @Test
    fun `signing out leaves nothing of the vendors on the phone`() {
        open()
        val kept = phone.everythingKept()
        for (theirs in listOf("Palace on the Hill", "events@palace.example", "+48 600 000 001", "Hill Road 1", "Prefers the deposit")) {
            assertTrue("the phone does not keep \"$theirs\" to begin with", kept.contains(theirs))
        }
        goTo("More")
        scrollTo(hasText("Sign out"))

        compose.onNodeWithText("Sign out").performClick()

        waitFor("Sign in to see your plan.")
        val left = phone.everythingKept()
        for (theirs in listOf("Palace on the Hill", "Studio Light", "Jan Palacowy", "events@palace.example", "+48 600 000 001", "palace.example", "Hill Road 1", "Prefers the deposit")) {
            assertFalse("the phone still keeps \"$theirs\"", left.contains(theirs))
        }
    }
}
