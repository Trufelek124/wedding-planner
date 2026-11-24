package io.github.trufelek124.weddingplanner.planner

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.trufelek124.weddingplanner.data.LivingPlanner
import io.github.trufelek124.weddingplanner.data.StandIn
import io.github.trufelek124.weddingplanner.ui.PHONE
import io.github.trufelek124.weddingplanner.ui.picture
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Where a task's priority is found on its form, what the form says about it, and what choosing does. The wedding is made up. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class TaskPriorityScreenTest : SignedInPhoneTest() {

    private lateinit var theirs: LivingPlanner

    private val rule = "The planner sets it from the due day: critical within 3 days, high within 14, normal within 60, low when later or with no day."
    private val byHand = "You set this by hand. It stays until you change it here."

    private val flowers = StandIn.entry("t1", "Order the flowers", dueDate = "2027-06-01", priority = "LOW", priorityReason = "DUE_LATER")
    private val cake = StandIn.entry("t2", "Choose the cake", priority = "HIGH", priorityReason = "SET_BY_HAND", priorityMode = "MANUAL")

    @Before
    fun theirPlanner() {
        theirs = LivingPlanner(server, entries = listOf(flowers, cake))
    }

    private fun sent(route: String) = server.routes(route).map { Json.parseToJsonElement(it.body).jsonObject }

    private fun openTheForm(task: String, row: Array<String>) {
        open()
        goTo("Tasks")
        compose.onNode(line(task, *row)).performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Change the task") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun choose(text: String) = compose.onNodeWithText(text).performScrollTo().performClick()

    private fun save() {
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun top(text: String, substring: Boolean = false) = compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().size

    @Test
    fun `the part is called Priority, has five choices and stands after the status and before who does it`() {
        openTheForm("Order the flowers", arrayOf("Low — due in more than 60 days"))

        compose.onNode(hasText("Priority") and isHeading()).performScrollTo().assertIsDisplayed()
        val names = listOf("Status", "Priority", "Who does it")
        val tops = names.map { name -> compose.onNode(hasText(name) and isHeading()).fetchSemanticsNode().positionInRoot.y }
        assertTrue("order of the parts: $tops", tops[0] < tops[1] && tops[1] < tops[2])
        for (choice in listOf("Set by the planner", "Critical", "High", "Normal")) {
            compose.onNodeWithText(choice).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText("Set by the planner").assertIsSelected()
        assertEquals(0, top("How urgent"))
        assertEquals(0, top("Worked out"))
    }

    @Test
    fun `a task the server worked out says what it is now and why, and the sentence follows the choice`() {
        openTheForm("Order the flowers", arrayOf("Low — due in more than 60 days"))

        compose.onNodeWithText("$rule Now: Low — due in more than 60 days.").performScrollTo().assertIsDisplayed()

        choose("Critical")
        compose.onNodeWithText(byHand).performScrollTo().assertIsDisplayed()
        assertEquals(0, top("$rule Now: Low — due in more than 60 days."))

        choose("Set by the planner")
        compose.onNodeWithText("$rule Now: Low — due in more than 60 days.").assertIsDisplayed()
        compose.picture("task-form-priority")
    }

    @Test
    fun `a new task is told only how the planner sets it`() {
        open()
        goTo("Tasks")
        compose.onNode(hasText("Add a task") and hasClickAction()).performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Add a task") and isHeading()).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText(rule).performScrollTo().assertIsDisplayed()
        assertEquals(0, top("Now:", substring = true))
    }

    @Test
    fun `choosing High sends it as set by hand and the row says so`() {
        openTheForm("Order the flowers", arrayOf("Low — due in more than 60 days"))

        choose("High")
        save()

        waitUntil { server.routes("PUT /api/entries/t1").isNotEmpty() }
        val change = sent("PUT /api/entries/t1").single()
        assertEquals("\"MANUAL\"", change.getValue("priorityMode").toString())
        assertEquals("\"HIGH\"", change.getValue("priority").toString())
        waitFor("High — set by hand")
        compose.onNode(line("Order the flowers", "High — set by hand")).assertIsDisplayed()
    }

    @Test
    fun `choosing Set by the planner on a task set by hand sends AUTO`() {
        openTheForm("Choose the cake", arrayOf("High — set by hand"))
        compose.onNodeWithText(byHand).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("High").performScrollTo().assertIsSelected()

        choose("Set by the planner")
        compose.onNodeWithText(rule).assertIsDisplayed()
        save()

        waitUntil { server.routes("PUT /api/entries/t2").isNotEmpty() }
        val change = sent("PUT /api/entries/t2").single()
        assertEquals("\"AUTO\"", change.getValue("priorityMode").toString())
        assertTrue(change["priority"] == null || change.getValue("priority").toString() == "null")
    }

    @Test
    fun `without a connection the choice waits and is laid over what the screens show`() {
        openTheForm("Order the flowers", arrayOf("Low — due in more than 60 days"))
        server.down = true

        choose("Critical")
        save()

        waitFor("Critical — set by hand")
        compose.onNode(line("Order the flowers", "Critical — set by hand", "Waiting to be sent")).assertIsDisplayed()
        assertEquals("LOW", theirs.entries.getValue("t1").getValue("priority").jsonPrimitive.content)

        server.down = false
        compose.onNodeWithContentDescription("Load again").performClick()
        waitUntil { server.routes("PUT /api/entries/t1").isNotEmpty() }
        assertEquals("\"CRITICAL\"", sent("PUT /api/entries/t1").last().getValue("priority").toString())
    }

    @Test
    fun `without a connection, giving a task back to the planner waits too`() {
        openTheForm("Choose the cake", arrayOf("High — set by hand"))
        server.down = true

        choose("Set by the planner")
        save()

        waitFor("Waiting to be sent")
        compose.onNode(line("Choose the cake", "Waiting to be sent")).assertIsDisplayed()
        assertEquals(0, top("High — set by hand"))

        server.down = false
        compose.onNodeWithContentDescription("Load again").performClick()
        waitUntil { server.routes("PUT /api/entries/t2").isNotEmpty() }
        assertEquals("\"AUTO\"", sent("PUT /api/entries/t2").last().getValue("priorityMode").toString())
    }
}
