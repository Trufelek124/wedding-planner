package io.github.trufelek124.weddingplanner.planner

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isSelectable
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
import io.github.trufelek124.weddingplanner.data.Kind
import io.github.trufelek124.weddingplanner.data.StandIn
import io.github.trufelek124.weddingplanner.data.TaskFields
import io.github.trufelek124.weddingplanner.ui.PHONE
import io.github.trufelek124.weddingplanner.ui.picture
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
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
 * Adding and changing a step of the plan on the phone (S10), and adding the tasks of a step one after the other, with and
 * without a connection, against a stand-in server that keeps what it is sent. The wedding is made up.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class StepsScreenTest : SignedInPhoneTest() {

    private lateinit var theirs: LivingPlanner

    private val venue = StandIn.entry(
        "p1", "Book the venue", kind = "PLAN_ITEM", startDate = "2026-11-01", dueDate = "2026-12-15", status = "IN_PROGRESS", taskCount = 1,
        priority = "HIGH", priorityReason = "DUE_WITHIN_60_DAYS", categoryId = "c1", vendorId = "v1", assigneeIds = listOf(StandIn.MAREK), version = 4,
    )
    private val music = StandIn.entry("p2", "Choose the music", kind = "PLAN_ITEM")
    private val deposit = StandIn.entry("t1", "Pay the deposit", dueDate = "2026-11-10", parentId = "p1", priority = "CRITICAL", priorityReason = "OVERDUE")

    @Before
    fun theirPlanner() {
        theirs = LivingPlanner(
            server,
            entries = listOf(venue, deposit, music),
            categories = listOf(StandIn.category("c1", "Venue"), StandIn.category("c2", "Music")),
            vendors = listOf(StandIn.vendor("v1", "Palace on the Hill"), StandIn.vendor("v2", "Studio Light")),
        )
    }

    private fun sent(route: String) = server.routes(route).map { Json.parseToJsonElement(it.body).jsonObject }

    private fun waitUntilSent(route: String, times: Int = 1) {
        waitUntil { server.routes(route).size >= times }
    }

    private fun field(label: String) = compose.onNode(hasText(label) and hasSetTextAction())

    private fun save() {
        compose.onNodeWithText("Save", substring = false).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun openThePlan() {
        open()
        goTo("Plan")
    }

    private fun addAStep() {
        compose.onNode(hasText("Add a step") and hasClickAction()).performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Add a step") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun openTheStep(vararg says: String) {
        compose.onNode(line(*says)).performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Change the step") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun addATaskToTheVenue() {
        compose.onAllNodesWithText("Add a task to this").onFirst().performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Add a task") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Where a node stands from the top of the screen: a line is under a heading when it is further down. */
    private fun top(matcher: SemanticsMatcher): Float = compose.onNode(matcher).fetchSemanticsNode().positionInRoot.y

    private fun stepsOf(title: String) = theirs.entries.values.filter { it.getValue("title").toString() == "\"$title\"" }

    // ---------------------------------------------------------------- adding a step

    @Test
    fun `the plan offers to add a step, also when it is empty`() {
        openThePlan()
        compose.onNode(hasText("Add a step") and hasClickAction()).assertIsDisplayed()
        compose.picture("plan-with-add-a-step")
    }

    @Test
    fun `a step without days is added with what was filled in, and stands under Not scheduled`() {
        openThePlan()
        addAStep()
        compose.picture("step-form-new")

        field("Name").performTextInput("Plan the honeymoon")
        compose.onNodeWithText("Anna").performClick()
        compose.onNodeWithText("In progress").performClick()
        compose.onNodeWithContentDescription("Category: No category").performScrollTo().performClick()
        compose.onNodeWithText("Music").performClick()
        compose.onNodeWithContentDescription("Vendor: No vendor").performScrollTo().performClick()
        compose.onNodeWithText("Studio Light").performClick()
        save()

        waitUntilSent("POST /api/entries")
        val added = sent("POST /api/entries").single()
        assertEquals("\"PLAN_ITEM\"", added.getValue("kind").toString())
        assertTrue(added.getValue("id").toString().length > 10)
        assertEquals("\"Plan the honeymoon\"", added.getValue("title").toString())
        assertEquals("null", added.getValue("startDate").toString())
        assertEquals("null", added.getValue("dueDate").toString())
        assertEquals("\"IN_PROGRESS\"", added.getValue("status").toString())
        assertEquals("[\"${StandIn.ANNA}\"]", added.getValue("assigneeIds").toString())
        assertEquals("\"c2\"", added.getValue("categoryId").toString())
        assertEquals("\"v2\"", added.getValue("vendorId").toString())
        assertEquals("\"AUTO\"", added.getValue("priorityMode").toString())
        assertEquals("null", added.getValue("parentId").toString())
        assertEquals("null", added.getValue("amount").toString())
        assertEquals("null", added.getValue("paymentKind").toString())
        compose.onNode(hasText("Plan") and isHeading()).assertIsDisplayed()
        waitFor("Plan the honeymoon")
        waitUntilGone("Waiting to be sent")
        compose.onNode(line("Plan the honeymoon", "In progress", "Music", "Studio Light", "Anna")).assertIsDisplayed()
        assertTrue(top(hasText("Plan the honeymoon")) > top(hasText("Not scheduled")))
        assertEquals(1, stepsOf("Plan the honeymoon").size)
    }

    @Test
    fun `a step with days is added under Scheduled and is on the timeline`() {
        openThePlan()
        addAStep()
        field("Name").performTextInput("Send the invitations")
        field("First day").performTextInput("1.12.2026")
        field("Last day").performTextInput("20.12.2026")
        save()

        waitUntilSent("POST /api/entries")
        val added = sent("POST /api/entries").single()
        assertEquals("\"2026-12-01\"", added.getValue("startDate").toString())
        assertEquals("\"2026-12-20\"", added.getValue("dueDate").toString())
        waitFor("Send the invitations")
        waitUntilGone("Waiting to be sent")
        compose.onNode(line("Send the invitations", "1 Dec 2026 – 20 Dec 2026", "Not started")).assertIsDisplayed()
        assertTrue(top(hasText("Send the invitations")) < top(hasText("Not scheduled")))
        assertTrue(top(hasText("Send the invitations")) > top(hasText("Scheduled")))

        compose.onNodeWithText("Timeline").performClick()
        compose.waitForIdle()
        compose.onNode(line("Send the invitations", "1 Dec 2026 – 20 Dec 2026 · Not started")).assertIsDisplayed()
    }

    @Test
    fun `a step with days that waits is on the timeline too, and says that it waits`() {
        openThePlan()
        server.down = true
        addAStep()
        field("Name").performTextInput("Send the invitations")
        field("First day").performTextInput("1.12.2026")
        field("Last day").performTextInput("20.12.2026")
        save()
        waitFor("Send the invitations")

        compose.onNodeWithText("Timeline").performClick()
        compose.waitForIdle()

        compose.onNode(line("Send the invitations", "Waiting to be sent · 1 Dec 2026 – 20 Dec 2026 · Not started")).assertIsDisplayed()
    }

    @Test
    fun `the three rules are said beside their fields before anything is sent`() {
        openThePlan()
        addAStep()

        field("First day").performTextInput("1.12.2026")
        save()
        // A name is needed, and with a first day there must be a last day.
        compose.onNodeWithText("Enter a name.").assertExists()
        compose.onNodeWithText("Enter the end date too, or leave both dates empty.").assertExists()
        compose.onNodeWithText("Not saved yet: put right what is marked above.").assertIsDisplayed()

        // With the name filled in, it is the missing last day alone that keeps it from being sent.
        field("Name").performTextInput("Send the invitations")
        save()
        compose.onNodeWithText("Enter a name.").assertDoesNotExist()
        compose.onNodeWithText("Enter the end date too, or leave both dates empty.").assertExists()
        assertTrue(server.routes("POST /api/entries").isEmpty())

        field("Last day").performTextInput("20.11.2026")
        save()
        compose.onNodeWithText("Enter the end date too, or leave both dates empty.").assertDoesNotExist()
        compose.onNodeWithText("The end date cannot be before the start date.").assertExists()
        field("Name").performScrollTo()
        compose.picture("step-form-end-before-start")

        assertTrue(server.routes("POST /api/entries").isEmpty())
        compose.onNode(hasText("Add a step") and isHeading()).assertIsDisplayed()

        field("Last day").performTextReplacement("20.12.2026")
        save()
        waitUntilSent("POST /api/entries")
        compose.onNode(hasText("Plan") and isHeading()).assertIsDisplayed()
    }

    @Test
    fun `two days that are the same are a step of one day`() {
        openThePlan()
        addAStep()
        field("Name").performTextInput("The rehearsal")
        field("First day").performTextInput("1.12.2026")
        field("Last day").performTextInput("1.12.2026")
        save()

        waitUntilSent("POST /api/entries")
        assertEquals("\"2026-12-01\"", sent("POST /api/entries").single().getValue("dueDate").toString())
    }

    @Test
    fun `what the server refuses is said beside its field, to be put right and saved again`() {
        openThePlan()
        server.problem(
            "POST /api/entries", 400, "bad-request",
            ""","fieldProblems":[{"field":"startDate","message":"Choose a day in the next two years."},{"field":"dueDate","message":"The end date cannot be after the wedding."}]""",
        )
        addAStep()
        field("Name").performTextInput("Send the invitations")
        field("First day").performTextInput("1.12.2026")
        field("Last day").performTextInput("20.12.2026")
        save()

        waitFor("Not saved: open it to see why")
        compose.onNode(line("Send the invitations", "Not saved: open it to see why")).performClick()
        waitFor("This was not saved on the server. Put right what is marked, then save again.")
        compose.onNodeWithText("Choose a day in the next two years.").assertExists()
        compose.onNodeWithText("The end date cannot be after the wedding.").assertExists()

        // The server takes it now.
        server.unreachable("POST /api/entries")
        field("Last day").performTextReplacement("10.12.2026")
        save()

        waitUntil { stepsOf("Send the invitations").size == 1 }
        waitUntilGone("Not saved: open it to see why")
    }

    // ---------------------------------------------------------------- changing a step

    @Test
    fun `a step opens from the list with what it has, and a change is sent with the version it was made on`() {
        openThePlan()
        compose.picture("plan-with-steps")

        openTheStep("Book the venue", "In progress")
        field("Name").assertTextContains("Book the venue")
        field("First day").assertTextContains("01.11.2026")
        field("Last day").assertTextContains("15.12.2026")
        compose.onNodeWithText("In progress").assertIsSelected()
        compose.onNodeWithText("Marek").assertIsSelected()
        compose.onNodeWithText("Set by the planner").performScrollTo().assertIsSelected()
        compose.onNodeWithContentDescription("Category: Venue").assertExists()
        compose.onNodeWithContentDescription("Vendor: Palace on the Hill").assertExists()
        // A step has no step of its own to belong to, and no money.
        compose.onNodeWithContentDescription("Belongs to: No step of the plan").assertDoesNotExist()
        compose.onNodeWithContentDescription("This is a payment").assertDoesNotExist()
        compose.picture("step-form-change")

        field("Name").performTextReplacement("Book the venue and the band")
        field("Last day").performTextReplacement("20.12.2026")
        compose.onNodeWithText("Done").performScrollTo().performClick()
        save()

        waitUntil { theirs.titleOf("p1") == "Book the venue and the band" }
        val change = sent("PUT /api/entries/p1").single()
        assertEquals("4", change.getValue("version").toString())
        assertEquals("\"2026-11-01\"", change.getValue("startDate").toString())
        assertEquals("\"2026-12-20\"", change.getValue("dueDate").toString())
        assertEquals("\"DONE\"", change.getValue("status").toString())
        assertEquals("null", change.getValue("parentId").toString())
        assertEquals("null", change.getValue("amount").toString())
        assertEquals("\"c1\"", change.getValue("categoryId").toString())
        assertEquals("\"v1\"", change.getValue("vendorId").toString())
        assertEquals("[\"${StandIn.MAREK}\"]", change.getValue("assigneeIds").toString())
        compose.onNode(hasText("Plan") and isHeading()).assertIsDisplayed()
    }

    @Test
    fun `leaving the form of a step without saving changes nothing`() {
        openThePlan()
        openTheStep("Book the venue", "In progress")
        field("Name").performTextReplacement("Something else")

        compose.onNodeWithContentDescription("Back").performClick()

        compose.onNode(hasText("Plan") and isHeading()).assertIsDisplayed()
        compose.onNodeWithText("Something else").assertDoesNotExist()
        assertTrue(server.calls.none { it.route.startsWith("PUT") || it.route == "POST /api/entries" })
    }

    private fun changeTheVenueWhileTheOtherOwnerDoesToo() {
        openThePlan()
        openTheStep("Book the venue", "In progress")
        field("Name").performTextReplacement("Book the venue on Saturday")
        // Meanwhile, on the other owner's phone:
        theirs.put(
            StandIn.entry(
                "p1", "Book the venue together", kind = "PLAN_ITEM", startDate = "2026-11-02", dueDate = "2026-12-30", status = "IN_PROGRESS", taskCount = 1,
                assigneeIds = listOf(StandIn.ANNA, StandIn.MAREK), version = 5,
            ),
        )
        save()
        waitFor("Not sent: changed on another device")
    }

    @Test
    fun `a change of a step that meets someone else's is not sent over theirs`() {
        changeTheVenueWhileTheOtherOwnerDoesToo()

        compose.onNode(line("Book the venue together", "Not sent: changed on another device", "2 Nov 2026 – 30 Dec 2026", "Anna & Marek")).assertIsDisplayed()
        compose.onNodeWithText("Book the venue on Saturday").assertDoesNotExist()
        assertEquals("Book the venue together", theirs.titleOf("p1"))

        compose.onNode(line("Book the venue together", "Not sent: changed on another device")).performClick()
        waitFor("Your change to this step was not sent: someone changed it on another device in the meantime. This is the latest version. Saving from here changes that version.")
        field("Name").assertTextContains("Book the venue together")
        field("Last day").assertTextContains("30.12.2026")

        compose.onNodeWithText("Keep the latest version").performClick()
        compose.onNode(hasText("Plan") and isHeading()).assertIsDisplayed()
        waitUntilGone("Not sent: changed on another device")
        assertEquals("Book the venue together", theirs.titleOf("p1"))
        assertEquals(1, server.routes("PUT /api/entries/p1").size)
    }

    @Test
    fun `a step that is gone from the server is named, and the change to it let go of`() {
        openThePlan()
        server.down = true
        openTheStep("Choose the music")
        field("Name").performTextReplacement("Choose the music and the band")
        save()
        waitFor("Waiting to be sent")
        theirs.entries.remove("p2")
        server.down = false

        compose.onNodeWithContentDescription("Load again").performClick()

        waitFor("Your change to “Choose the music and the band” could not be sent: the step no longer exists.")
        compose.onNodeWithText("OK").performClick()
        waitUntilGone("could not be sent")
        compose.onNodeWithText("Choose the music and the band").assertDoesNotExist()
        compose.onNodeWithText("Choose the music").assertDoesNotExist()
    }

    // ---------------------------------------------------------------- without a connection

    @Test
    fun `added and changed steps wait without a connection, say so, and go when it is back`() {
        openThePlan()
        server.down = true

        addAStep()
        field("Name").performTextInput("Plan the honeymoon")
        save()
        waitFor("Plan the honeymoon")
        compose.onNode(line("Plan the honeymoon", "Waiting to be sent", "Not started")).assertIsDisplayed()
        openTheStep("Choose the music")
        field("Name").performTextReplacement("Choose the music and the band")
        save()
        waitFor("Choose the music and the band")
        compose.onNode(line("Choose the music and the band", "Waiting to be sent")).assertIsDisplayed()
        waitFor("No connection. This is what was loaded on 18 Nov 2026 at 14:05. 2 changes are waiting to be sent.")
        assertTrue(stepsOf("Plan the honeymoon").isEmpty())
        assertEquals("Choose the music", theirs.titleOf("p2"))
        compose.picture("plan-with-steps-waiting")

        server.down = false
        compose.onNodeWithContentDescription("Load again").performClick()

        waitUntilGone("Waiting to be sent")
        waitUntilGone("No connection.")
        assertEquals(1, stepsOf("Plan the honeymoon").size)
        assertEquals("PLAN_ITEM", stepsOf("Plan the honeymoon").single().getValue("kind").toString().trim('"'))
        assertEquals("Choose the music and the band", theirs.titleOf("p2"))
        compose.onNodeWithContentDescription("Load again").performClick()
        compose.waitForIdle()
        assertEquals(1, stepsOf("Plan the honeymoon").size)
    }

    @Test
    fun `a step that waits is changed again, and what is sent is the last form of it`() {
        openThePlan()
        server.down = true
        addAStep()
        field("Name").performTextInput("Plan the honeymoon")
        save()
        waitFor("Plan the honeymoon")

        openTheStep("Plan the honeymoon", "Waiting to be sent")
        waitFor("A change to this step is waiting to be sent. What you save now takes its place.")
        field("Name").performTextReplacement("Plan the honeymoon in Portugal")
        field("First day").performTextInput("1.3.2027")
        field("Last day").performTextInput("15.3.2027")
        save()
        waitFor("Plan the honeymoon in Portugal")

        server.calls.clear()
        server.down = false
        compose.onNodeWithContentDescription("Load again").performClick()
        waitUntilGone("Waiting to be sent")

        // One request, a new step: a step is not sent twice, and not as a change of something the server does not have.
        val added = sent("POST /api/entries").single()
        assertEquals("\"PLAN_ITEM\"", added.getValue("kind").toString())
        assertEquals("\"Plan the honeymoon in Portugal\"", added.getValue("title").toString())
        assertEquals("\"2027-03-01\"", added.getValue("startDate").toString())
        assertTrue(server.routes("PUT /api/entries/${added.getValue("id").toString().trim('"')}").isEmpty())
    }

    @Test
    fun `a task added to a step that waits is sent after it and belongs to it`() {
        openThePlan()
        server.down = true
        addAStep()
        field("Name").performTextInput("Plan the honeymoon")
        save()
        waitFor("Plan the honeymoon")

        // The step is in the plan already, and can be given a task: it stands first under "Not scheduled", after the venue.
        compose.onAllNodesWithText("Add a task to this")[1].performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Add a task") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Belongs to: Plan the honeymoon").assertExists()
        field("Title").performTextInput("Book the flights")
        save()
        // Under its step, folded away: the step says it has a task now.
        waitFor("▸ 1 task")

        server.calls.clear()
        server.down = false
        compose.onNodeWithContentDescription("Load again").performClick()
        waitUntilGone("Waiting to be sent")

        val posts = sent("POST /api/entries")
        assertEquals(listOf("\"PLAN_ITEM\"", "\"TASK\""), posts.map { it.getValue("kind").toString() })
        assertEquals(posts[0].getValue("id"), posts[1].getValue("parentId"))
        val stepId = posts[0].getValue("id").toString().trim('"')
        assertEquals(stepId, theirs.entries.values.single { it.getValue("title").toString() == "\"Book the flights\"" }.getValue("parentId").toString().trim('"'))
    }

    @Test
    fun `a step made without a connection is still there, waiting, after the app was closed, and is sent then`() {
        runBlocking {
            phone.container.planner.sync()
            server.down = true
            phone.container.planner.add(TaskFields(title = "Plan the honeymoon", startDate = LocalDate.of(2026, 12, 1), dueDate = LocalDate.of(2026, 12, 20)), Kind.PLAN_ITEM)
        }
        phone = phone.restarted()

        open()
        goTo("Plan")

        compose.onNode(line("Plan the honeymoon", "Waiting to be sent", "1 Dec 2026 – 20 Dec 2026", "Not started")).assertIsDisplayed()
        server.down = false
        compose.onNodeWithContentDescription("Load again").performClick()
        waitUntilGone("Waiting to be sent")
        assertEquals(1, stepsOf("Plan the honeymoon").size)
    }

    // ---------------------------------------------------------------- tasks in a row

    @Test
    fun `three tasks are added to a step in a row, with the step kept and the keyboard in the title`() {
        openThePlan()
        addATaskToTheVenue()

        field("Title").performTextInput("Ask about parking")
        field("Due").performTextInput("2.12.2026")
        compose.onNodeWithText("In progress").performClick()
        compose.onNodeWithText("Save and add another").performScrollTo()
        compose.picture("task-form-save-and-add-another")
        compose.onNodeWithText("Save and add another").performClick()

        // Kept, and the form is empty again for the next one: still the venue's, with the keyboard in the title.
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Title") and hasSetTextAction() and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        field("Title").assertTextContains("")
        compose.onNodeWithText("Ask about parking").assertDoesNotExist()
        field("Due").assertTextContains("")
        compose.onNodeWithText("Not started").assertIsSelected()
        compose.onNodeWithContentDescription("Belongs to: Book the venue").assertExists()
        compose.onNodeWithText("1 task kept so far. Add the next one.").assertIsDisplayed()
        compose.onNode(hasText("Add a task") and isHeading()).assertIsDisplayed()
        compose.picture("task-form-after-save-and-add-another")

        field("Title").performTextInput("Ask about the piano")
        compose.onNodeWithText("Save and add another").performScrollTo().performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("2 tasks kept so far. Add the next one.")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Title") and hasSetTextAction() and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Belongs to: Book the venue").assertExists()
        field("Title").performTextInput("Ask about the car park")
        save()

        // "Save" keeps the last one and leads back to the plan.
        compose.onNode(hasText("Plan") and isHeading()).assertIsDisplayed()
        waitUntilSent("POST /api/entries", times = 3)
        val posts = sent("POST /api/entries")
        assertEquals(listOf("Ask about parking", "Ask about the piano", "Ask about the car park"), posts.map { it.getValue("title").toString().trim('"') })
        assertTrue(posts.all { it.getValue("parentId").toString() == "\"p1\"" && it.getValue("kind").toString() == "\"TASK\"" })
        assertEquals("\"2026-12-02\"", posts[0].getValue("dueDate").toString())
        assertEquals("\"IN_PROGRESS\"", posts[0].getValue("status").toString())
        assertEquals("null", posts[1].getValue("dueDate").toString())
        assertEquals("\"NOT_STARTED\"", posts[1].getValue("status").toString())
        waitUntil { theirs.entries.values.count { it.getValue("parentId").toString() == "\"p1\"" } == 4 }
    }

    @Test
    fun `Cancel after tasks were kept leads back and what was kept stays`() {
        openThePlan()
        addATaskToTheVenue()
        field("Title").performTextInput("Ask about parking")
        compose.onNodeWithText("Save and add another").performScrollTo().performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("1 task kept so far. Add the next one.")).fetchSemanticsNodes().isNotEmpty() }
        field("Title").performTextInput("Not kept")

        compose.onNodeWithText("Cancel").performScrollTo().performClick()

        compose.onNode(hasText("Plan") and isHeading()).assertIsDisplayed()
        waitUntilSent("POST /api/entries")
        assertEquals(listOf("Ask about parking"), sent("POST /api/entries").map { it.getValue("title").toString().trim('"') })
    }

    @Test
    fun `Save and add another does not keep a task that cannot be read`() {
        openThePlan()
        addATaskToTheVenue()

        compose.onNodeWithText("Save and add another").performScrollTo().performClick()

        compose.onNodeWithText("Enter a title.").assertExists()
        assertTrue(server.routes("POST /api/entries").isEmpty())
        compose.onNodeWithText("1 task kept so far. Add the next one.").assertDoesNotExist()
    }

    @Test
    fun `Save and add another is only on the form that Add a task to this opens`() {
        openThePlan()
        addATaskToTheVenue()
        compose.onNodeWithText("Save and add another").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Cancel").performScrollTo().performClick()

        // The tasks' own button, and a task that is changed, have the plain form.
        goTo("Tasks")
        compose.onNode(hasText("Add a task") and hasClickAction()).performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Add a task") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Save").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Save and add another").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()

        compose.onNode(line("Pay the deposit", "Critical — overdue")).performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Change the task") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Save").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Save and add another").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()

        // Nor does the form of a step.
        goTo("Plan")
        addAStep()
        compose.onNodeWithText("Save and add another").assertDoesNotExist()
    }

    // ---------------------------------------------------------------- signing out, and the log

    @Test
    fun `signing out with a step that was not sent asks first, and leaves nothing of it`() {
        openThePlan()
        server.down = true
        addAStep()
        field("Name").performTextInput("Plan the honeymoon")
        save()
        waitFor("Waiting to be sent")
        compose.onNode(hasText("Home") and isSelectable()).performClick()
        waitFor("Anna & Marek")
        scrollTo(hasText("Sign out"))

        compose.onNodeWithText("Sign out").performClick()
        compose.onNodeWithText("Sign out?").assertIsDisplayed()
        compose.onNodeWithText("1 change made on this phone has not reached the server and will be lost.").assertIsDisplayed()
        compose.onAllNodesWithText("Sign out").onLast().performClick()

        waitFor("Sign in to see your plan.")
        assertFalse(phone.everythingKept().contains("Plan the honeymoon"))
    }

    @Test
    fun `nothing of a step that is added or changed is written to the log`() {
        ShadowLog.clear()
        openThePlan()
        server.down = true
        addAStep()
        field("Name").performTextInput("Surprise honeymoon for the secret trip")
        field("Notes").performScrollTo().performTextInput("Do not tell Marek.")
        save()
        waitFor("Surprise honeymoon for the secret trip")
        openTheStep("Choose the music")
        field("Name").performTextReplacement("Choose the secret playlist")
        save()
        waitFor("Choose the secret playlist")
        server.down = false
        compose.onNodeWithContentDescription("Load again").performClick()
        waitUntilGone("Waiting to be sent")

        val written = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable?.stackTraceToString().orEmpty()}" }
        for (secret in listOf("honeymoon", "secret", "Do not tell", "Marek", "Anna", "playlist", "access-1")) {
            assertFalse("the log holds \"$secret\"", written.contains(secret, ignoreCase = true))
        }
    }
}
