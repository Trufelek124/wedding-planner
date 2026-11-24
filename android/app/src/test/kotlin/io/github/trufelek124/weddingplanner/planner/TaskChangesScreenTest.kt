package io.github.trufelek124.weddingplanner.planner

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import io.github.trufelek124.weddingplanner.data.LivingPlanner
import io.github.trufelek124.weddingplanner.data.StandIn
import io.github.trufelek124.weddingplanner.data.Status
import io.github.trufelek124.weddingplanner.ui.PHONE
import io.github.trufelek124.weddingplanner.ui.picture
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog

/**
 * Ticking off, adding and changing a task on the phone, with and without a connection, against a
 * stand-in server that keeps what it is sent. The wedding is made up.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class TaskChangesScreenTest : SignedInPhoneTest() {

    private lateinit var theirs: LivingPlanner

    private val venue = StandIn.entry("p1", "Book the venue", kind = "PLAN_ITEM", startDate = "2026-11-01", dueDate = "2026-12-15", status = "IN_PROGRESS", taskCount = 1)
    private val deposit = StandIn.entry(
        "t1", "Pay the deposit", dueDate = "2026-11-10", priority = "CRITICAL", priorityReason = "OVERDUE", parentId = "p1",
        categoryId = "c1", vendorId = "v1", amount = 250000, paymentKind = "DEPOSIT", assigneeIds = listOf(StandIn.MAREK), version = 3,
    )
    private val rings = StandIn.entry("t2", "Choose the rings", assigneeIds = listOf(StandIn.ANNA))
    private val tasting = StandIn.entry("t3", "Go to the tasting", dueDate = "2026-11-25", priority = "HIGH", priorityReason = "DUE_WITHIN_14_DAYS")

    @Before
    fun theirPlanner() {
        theirs = LivingPlanner(
            server,
            entries = listOf(deposit, tasting, venue, rings),
            categories = listOf(StandIn.category("c1", "Venue"), StandIn.category("c2", "Music")),
            vendors = listOf(StandIn.vendor("v1", "Palace on the Hill"), StandIn.vendor("v2", "Studio Light")),
        )
    }

    private fun sent(route: String) = server.routes(route).map { Json.parseToJsonElement(it.body).jsonObject }

    private fun waitUntilSent(route: String, times: Int = 1) {
        waitUntil { server.routes(route).size >= times }
    }

    private fun openTheTasks() {
        open()
        goTo("Tasks")
    }

    private fun field(label: String) = compose.onNode(hasText(label) and hasSetTextAction())

    private fun save() {
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun addATask() {
        compose.onNode(hasText("Add a task") and hasClickAction()).performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Add a task") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
    }

    // ---------------------------------------------------------------- ticking off

    @Test
    fun `ticking a task off sends it at once and takes it off the list of what is to do`() {
        openTheTasks()
        compose.onNodeWithContentDescription("Done: Choose the rings").assertIsOff()
        compose.picture("tasks-with-boxes")

        compose.onNodeWithContentDescription("Done: Choose the rings").performClick()

        waitUntil { theirs.statusOf("t2") == "DONE" }
        assertEquals("""{"status":"DONE"}""", server.routes("PUT /api/entries/t2/status").single().body)
        waitUntilGone("Choose the rings")

        compose.onNodeWithText("Everything").performClick()
        compose.onNodeWithContentDescription("Done: Choose the rings").assertIsOn()
        compose.onNode(line("Choose the rings", "Done")).assertIsDisplayed()
        // Once the server's answer is in, nothing is waiting any more.
        waitUntilGone("Waiting to be sent")
    }

    @Test
    fun `a tick can be taken away again`() {
        theirs.put(StandIn.entry("t2", "Choose the rings", status = "DONE", version = 1))
        openTheTasks()
        compose.onNodeWithText("Everything").performClick()

        compose.onNodeWithContentDescription("Done: Choose the rings").performClick()

        waitUntil { theirs.statusOf("t2") == "NOT_STARTED" }
        compose.onNodeWithContentDescription("Done: Choose the rings").assertIsOff()
    }

    @Test
    fun `a payment is marked paid from Home, and says so`() {
        open()
        // It stands under what comes next and again under the payments: either box pays it.
        compose.onAllNodesWithContentDescription("Paid: Pay the deposit").onFirst().performClick()

        waitUntil { theirs.statusOf("t1") == "DONE" }
        goTo("Tasks")
        compose.onNodeWithText("Everything").performClick()
        // The day it was paid is the server's to say: it is on the line once the answer is in.
        waitFor("2,500.00\u00a0zł, deposit, paid 18 Nov 2026")
        compose.onNode(line("Pay the deposit", "Done", "2,500.00\u00a0zł, deposit, paid 18 Nov 2026")).assertIsDisplayed()
    }

    @Test
    fun `without a connection a tick waits, says so, and is sent when the connection is back`() {
        openTheTasks()
        compose.onNodeWithText("Everything").performClick()
        server.down = true

        compose.onNodeWithContentDescription("Done: Choose the rings").performClick()

        waitFor("Waiting to be sent")
        compose.onNodeWithContentDescription("Done: Choose the rings").assertIsOn()
        compose.onNode(line("Choose the rings", "Waiting to be sent", "Done")).assertIsDisplayed()
        waitFor("No connection. This is what was loaded on 18 Nov 2026 at 14:05. 1 change is waiting to be sent.")
        // The phone was asked to send it once there is a connection, should the app be closed by then.
        assertEquals(1, phone.askedToSendLater)
        assertEquals("NOT_STARTED", theirs.statusOf("t2"))
        compose.picture("tasks-with-a-change-waiting")

        server.down = false
        compose.onNodeWithContentDescription("Load again").performClick()

        waitUntilGone("Waiting to be sent")
        waitUntilGone("No connection.")
        assertEquals("DONE", theirs.statusOf("t2"))
        compose.onNodeWithContentDescription("Done: Choose the rings").assertIsOn()
    }

    @Test
    fun `a tick made without a connection is still there, waiting, after the app was closed`() {
        runBlocking {
            phone.container.planner.sync()
            server.down = true
            phone.container.planner.setStatus("t2", Status.DONE)
        }
        phone = phone.restarted()

        open()
        goTo("Tasks")
        compose.onNodeWithText("Everything").performClick()

        compose.onNode(line("Choose the rings", "Waiting to be sent", "Done")).assertIsDisplayed()

        server.down = false
        compose.onNodeWithContentDescription("Load again").performClick()
        waitUntilGone("Waiting to be sent")
        assertEquals("DONE", theirs.statusOf("t2"))
    }

    // ---------------------------------------------------------------- adding

    @Test
    fun `a task is added with what was filled in, and stands in the list`() {
        openTheTasks()
        addATask()
        compose.picture("task-form-new")

        field("Title").performTextInput("Order the flowers")
        field("Due").performTextInput("2.5.2027")
        compose.onNodeWithText("Anna").performClick()
        compose.onNodeWithContentDescription("Category: No category").performScrollTo().performClick()
        compose.onNodeWithText("Music").performClick()
        compose.onNodeWithContentDescription("Vendor: No vendor").performScrollTo().performClick()
        compose.onNodeWithText("Studio Light").performClick()
        field("Notes").performScrollTo().performTextInput("Peonies if they have them.")
        save()

        waitUntilSent("POST /api/entries")
        val added = sent("POST /api/entries").single()
        assertEquals("\"Order the flowers\"", added.getValue("title").toString())
        assertEquals("\"2027-05-02\"", added.getValue("dueDate").toString())
        assertEquals("[\"${StandIn.ANNA}\"]", added.getValue("assigneeIds").toString())
        assertEquals("\"c2\"", added.getValue("categoryId").toString())
        assertEquals("\"v2\"", added.getValue("vendorId").toString())
        assertEquals("\"Peonies if they have them.\"", added.getValue("notes").toString())
        assertEquals("\"TASK\"", added.getValue("kind").toString())
        assertEquals("\"AUTO\"", added.getValue("priorityMode").toString())
        assertEquals("null", added.getValue("amount").toString())
        // Back on the list, with the new task on it.
        compose.onNode(hasText("Tasks") and isHeading()).assertIsDisplayed()
        waitFor("Order the flowers")
        scrollTo(hasText("Order the flowers"))
        compose.onNode(line("Order the flowers", "due 2 May 2027", "Music", "Studio Light", "Anna")).assertIsDisplayed()
        waitUntilGone("Waiting to be sent")
        assertEquals(1, theirs.entries.values.count { it.getValue("title").toString() == "\"Order the flowers\"" })
    }

    @Test
    fun `a task added without a connection stands first, waiting, and is added once when it is back`() {
        openTheTasks()
        server.down = true
        addATask()
        field("Title").performTextInput("Order the flowers")
        save()

        waitFor("Order the flowers")
        compose.onNode(line("Order the flowers", "Waiting to be sent")).assertIsDisplayed()
        assertTrue(theirs.entries.values.none { it.getValue("title").toString() == "\"Order the flowers\"" })

        server.down = false
        compose.onNodeWithContentDescription("Load again").performClick()
        waitUntilGone("Waiting to be sent")
        compose.onNodeWithContentDescription("Load again").performClick()
        compose.waitForIdle()

        assertEquals(1, theirs.entries.values.count { it.getValue("title").toString() == "\"Order the flowers\"" })
        scrollTo(hasText("Order the flowers"))
        compose.onNode(line("Order the flowers", "Normal — no date")).assertIsDisplayed()
    }

    @Test
    fun `the form says what it cannot read, and saves nothing until it can`() {
        openTheTasks()
        addATask()

        save()
        // Beside the button it says that nothing was saved; beside the field, what is wrong.
        compose.onNodeWithText("Not saved yet: put right what is marked above.").assertIsDisplayed()
        compose.onNodeWithText("Enter a title.").assertExists()

        field("Title").performTextInput("Pay the band")
        field("Due").performTextInput("soon")
        compose.onNodeWithContentDescription("This is a payment").performScrollTo().performClick()
        field("Amount in zł").performScrollTo().performTextInput("a lot")
        save()

        compose.onNodeWithText("Enter a day like 15.01.2027.").assertExists()
        compose.onNodeWithText("Enter an amount like 2500 or 2500.50.").assertExists()
        compose.onNodeWithText("Enter a title.").assertDoesNotExist()
        assertTrue(server.routes("POST /api/entries").isEmpty())
        compose.onNode(hasText("Add a task") and isHeading()).assertIsDisplayed()
    }

    @Test
    fun `a payment is added with its amount as it was typed, in whole grosze`() {
        openTheTasks()
        addATask()
        field("Title").performTextInput("Pay the band")
        compose.onNodeWithContentDescription("This is a payment").performScrollTo().performClick()
        field("Amount in zł").performScrollTo().performTextInput("1 200,50")
        compose.onNodeWithText("Instalment").performScrollTo().performClick()
        compose.picture("task-form-payment")
        save()

        waitUntilSent("POST /api/entries")
        val added = sent("POST /api/entries").single()
        assertEquals("120050", added.getValue("amount").toString())
        assertEquals("\"INSTALMENT\"", added.getValue("paymentKind").toString())
        assertEquals("null", added.getValue("paidOn").toString())
        waitFor("Pay the band")
        scrollTo(hasText("Pay the band"))
        compose.onNode(line("Pay the band", "1,200.50\u00a0zł, instalment, to pay")).assertIsDisplayed()
    }

    @Test
    fun `a task is added to a step of the plan from the plan`() {
        open()
        goTo("Plan")

        compose.onNodeWithText("Add a task to this").performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Add a task") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Belongs to: Book the venue").assertExists()
        field("Title").performTextInput("Ask about parking")
        save()

        waitUntilSent("POST /api/entries")
        assertEquals("\"p1\"", sent("POST /api/entries").single().getValue("parentId").toString())
        compose.onNode(hasText("Plan") and isHeading()).assertIsDisplayed()
    }

    @Test
    fun `a day picked from the calendar is written into the field`() {
        openTheTasks()
        addATask()
        field("Title").performTextInput("Order the flowers")

        compose.onNodeWithContentDescription("Pick a day for: Due").performClick()
        // The calendar opens on today, where the wedding takes place.
        compose.onNodeWithText("Use this day").performClick()

        field("Due").assertTextContains("18.11.2026")
        save()
        waitUntilSent("POST /api/entries")
        assertEquals("\"2026-11-18\"", sent("POST /api/entries").single().getValue("dueDate").toString())
    }

    // ---------------------------------------------------------------- changing

    @Test
    fun `a task opens with what it has, and a change is sent with the version it was made on`() {
        openTheTasks()

        compose.onNode(line("Choose the rings", "Normal — no date", "Anna")).performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Change the task") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        field("Title").assertTextContains("Choose the rings")
        compose.onNodeWithText("Not started").assertIsSelected()
        compose.onNodeWithText("Anna").assertIsSelected()
        compose.onNodeWithText("Set by the planner").performScrollTo().assertIsSelected()

        field("Title").performTextReplacement("Choose and order the rings")
        compose.onNodeWithText("In progress").performScrollTo().performClick()
        compose.onNodeWithText("High").performScrollTo().performClick()
        save()

        waitUntil { theirs.titleOf("t2") == "Choose and order the rings" }
        val change = sent("PUT /api/entries/t2").single()
        assertEquals("0", change.getValue("version").toString())
        assertEquals("\"Choose and order the rings\"", change.getValue("title").toString())
        assertEquals("\"IN_PROGRESS\"", change.getValue("status").toString())
        assertEquals("\"MANUAL\"", change.getValue("priorityMode").toString())
        assertEquals("\"HIGH\"", change.getValue("priority").toString())
        assertEquals("[\"${StandIn.ANNA}\"]", change.getValue("assigneeIds").toString())
        waitFor("High — set by hand")
        compose.onNode(line("Choose and order the rings", "In progress", "High — set by hand", "Anna")).assertIsDisplayed()
    }

    @Test
    fun `a payment opens with its money as it would be typed, and keeps it when something else is changed`() {
        openTheTasks()

        compose.onNode(line("Pay the deposit", "Critical — overdue")).performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Change the task") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        field("Due").assertTextContains("10.11.2026")
        compose.onNodeWithContentDescription("Belongs to: Book the venue").assertExists()
        compose.onNodeWithContentDescription("Category: Venue").assertExists()
        compose.onNodeWithContentDescription("Vendor: Palace on the Hill").assertExists()
        compose.onNodeWithContentDescription("This is a payment").performScrollTo().assertIsOn()
        field("Amount in zł").performScrollTo().assertTextContains("2500")
        compose.onNodeWithText("Deposit").performScrollTo().assertIsSelected()

        field("Due").performScrollTo().performTextReplacement("20.11.2026")
        save()

        waitUntilSent("PUT /api/entries/t1")
        val change = sent("PUT /api/entries/t1").single()
        assertEquals("3", change.getValue("version").toString())
        assertEquals("\"2026-11-20\"", change.getValue("dueDate").toString())
        assertEquals("250000", change.getValue("amount").toString())
        assertEquals("\"DEPOSIT\"", change.getValue("paymentKind").toString())
        assertEquals("\"p1\"", change.getValue("parentId").toString())
        assertEquals("\"v1\"", change.getValue("vendorId").toString())
        assertEquals("[\"${StandIn.MAREK}\"]", change.getValue("assigneeIds").toString())
    }

    @Test
    fun `leaving the form without saving changes nothing`() {
        openTheTasks()
        compose.onNode(line("Choose the rings", "Normal — no date")).performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Change the task") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        field("Title").performTextReplacement("Something else")

        compose.onNodeWithContentDescription("Back").performClick()

        compose.onNode(hasText("Tasks") and isHeading()).assertIsDisplayed()
        compose.onNodeWithText("Choose the rings").assertIsDisplayed()
        compose.onNodeWithText("Something else").assertDoesNotExist()
        assertTrue(server.calls.none { it.route.startsWith("PUT") || it.route == "POST /api/entries" })
    }

    // ---------------------------------------------------------------- when the server does not take it

    private fun changeTheRingsWhileTheOtherOwnerDoesToo() {
        openTheTasks()
        compose.onNode(line("Choose the rings", "Normal — no date")).performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Change the task") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        field("Title").performTextReplacement("Choose the rings on Saturday")
        // Meanwhile, on the other owner's phone:
        theirs.put(StandIn.entry("t2", "Choose the rings together", dueDate = "2026-12-05", assigneeIds = listOf(StandIn.ANNA, StandIn.MAREK), version = 1))
        save()
        waitFor("Not sent: changed on another device")
    }

    @Test
    fun `a change that meets someone else's is not sent over theirs - the task shows the latest version and says so`() {
        changeTheRingsWhileTheOtherOwnerDoesToo()

        compose.onNode(line("Choose the rings together", "Not sent: changed on another device", "due 5 Dec 2026", "Anna & Marek")).assertIsDisplayed()
        compose.onNodeWithText("Choose the rings on Saturday").assertDoesNotExist()
        assertEquals("Choose the rings together", theirs.titleOf("t2"))
        // Until it was looked at, nothing more is done to it from the list.
        compose.onNodeWithContentDescription("Done: Choose the rings together").assertIsNotEnabled()
        compose.picture("tasks-with-a-change-not-sent")
    }

    @Test
    fun `the task then opens with the latest version, which can be kept as it is`() {
        changeTheRingsWhileTheOtherOwnerDoesToo()

        compose.onNode(line("Choose the rings together", "Not sent: changed on another device")).performClick()
        waitFor("Your change to this task was not sent: someone changed it on another device in the meantime. This is the latest version. Saving from here changes that version.")
        field("Title").assertTextContains("Choose the rings together")
        field("Due").assertTextContains("05.12.2026")
        compose.picture("task-form-changed-meanwhile")
        val sentBefore = server.routes("PUT /api/entries/t2").size

        compose.onNodeWithText("Keep the latest version").performClick()

        compose.onNode(hasText("Tasks") and isHeading()).assertIsDisplayed()
        waitUntilGone("Not sent: changed on another device")
        compose.onNodeWithText("Choose the rings together").assertIsDisplayed()
        compose.onNodeWithContentDescription("Load again").performClick()
        compose.waitForIdle()
        assertEquals(sentBefore, server.routes("PUT /api/entries/t2").size)
        assertEquals("Choose the rings together", theirs.titleOf("t2"))
    }

    @Test
    fun `or changed again from there, as a change of the latest version`() {
        changeTheRingsWhileTheOtherOwnerDoesToo()
        compose.onNode(line("Choose the rings together", "Not sent: changed on another device")).performClick()
        waitFor("Keep the latest version")

        field("Title").performTextReplacement("Choose the rings together on Saturday")
        save()

        waitUntil { theirs.titleOf("t2") == "Choose the rings together on Saturday" }
        assertEquals("1", sent("PUT /api/entries/t2").last().getValue("version").toString())
        // What the other owner set stays: the form started from their version.
        assertEquals("\"2026-12-05\"", sent("PUT /api/entries/t2").last().getValue("dueDate").toString())
        waitUntilGone("Not sent: changed on another device")
    }

    @Test
    fun `what the server refuses is said beside the field, to be put right and saved again`() {
        openTheTasks()
        server.problem("POST /api/entries", 400, "bad-request", ""","fieldProblems":[{"field":"parentId","message":"Choose one of your plan items."}]""")
        addATask()
        field("Title").performTextInput("Ask about parking")
        compose.onNodeWithContentDescription("Belongs to: No step of the plan").performScrollTo().performClick()
        compose.onNodeWithText("Book the venue").performClick()
        save()

        waitFor("Not saved: open it to see why")
        compose.onNode(line("Ask about parking", "Not saved: open it to see why")).performClick()
        waitFor("This was not saved on the server. Put right what is marked, then save again.")
        compose.onNodeWithText("Choose one of your plan items.").assertExists()

        // The server takes it now.
        server.unreachable("POST /api/entries")
        save()

        waitUntil { theirs.entries.values.count { it.getValue("title").toString() == "\"Ask about parking\"" } == 1 }
        waitUntilGone("Not saved: open it to see why")
    }

    @Test
    fun `a tick on a task that was deleted elsewhere is named and let go of`() {
        openTheTasks()
        theirs.entries.remove("t2")

        compose.onNodeWithContentDescription("Done: Choose the rings").performClick()

        waitFor("Your change to “Choose the rings” could not be sent: the task no longer exists.")
        compose.onNodeWithText("OK").performClick()
        waitUntilGone("could not be sent")
        compose.onNodeWithText("Choose the rings").assertDoesNotExist()
    }

    // ---------------------------------------------------------------- signing out, and the log

    @Test
    fun `signing out with changes that were not sent asks first`() {
        open()
        server.down = true
        compose.onAllNodesWithContentDescription("Done: Go to the tasting").onFirst().performClick()
        waitFor("Waiting to be sent")
        scrollTo(hasText("Sign out"))

        compose.onNodeWithText("Sign out").performClick()
        compose.onNodeWithText("Sign out?").assertIsDisplayed()
        compose.onNodeWithText("1 change made on this phone has not reached the server and will be lost.").assertIsDisplayed()
        compose.onNodeWithText("Stay signed in").performClick()
        compose.onNodeWithText("Sign out?").assertDoesNotExist()
        compose.onNodeWithText("Signed in as Anna").assertIsDisplayed()

        compose.onNodeWithText("Sign out").performClick()
        compose.onAllNodesWithText("Sign out").onLast().performClick()

        waitFor("Sign in to see your plan.")
        assertFalse(phone.everythingKept().contains("Go to the tasting"))
    }

    @Test
    fun `signing out with nothing unsent does not ask`() {
        open()
        scrollTo(hasText("Sign out"))

        compose.onNodeWithText("Sign out").performClick()

        waitFor("Sign in to see your plan.")
    }

    @Test
    fun `nothing of a task that is added or changed is written to the log`() {
        ShadowLog.clear()
        openTheTasks()
        addATask()
        field("Title").performTextInput("Order the flowers for the secret party")
        field("Notes").performScrollTo().performTextInput("Do not tell Marek.")
        save()
        waitUntilSent("POST /api/entries")
        server.down = true
        compose.onNodeWithContentDescription("Done: Choose the rings").performClick()
        waitFor("No connection. This is what was loaded on 18 Nov 2026 at 14:05. 1 change is waiting to be sent.")

        val written = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable?.stackTraceToString().orEmpty()}" }
        for (theirs in listOf("secret party", "Do not tell", "Marek", "Anna", "rings", "access-1")) {
            assertFalse("the log holds \"$theirs\"", written.contains(theirs, ignoreCase = true))
        }
    }

    @Test
    fun `with the largest text the form is still whole`() {
        open(fontScale = 2f)
        goTo("Tasks")
        addATask()

        field("Title").performTextInput("Order the flowers")
        compose.picture("task-form-large-text")
        compose.onNodeWithText("Save").performScrollTo().assertIsDisplayed()
        compose.onNode(hasContentDescription("This is a payment")).assertExists()
    }
}
