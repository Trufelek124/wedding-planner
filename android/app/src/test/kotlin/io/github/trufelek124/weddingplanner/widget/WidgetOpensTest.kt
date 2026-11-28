package io.github.trufelek124.weddingplanner.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.github.trufelek124.weddingplanner.MainActivity
import io.github.trufelek124.weddingplanner.StandInPhone
import io.github.trufelek124.weddingplanner.SwitchedOffAfterwards
import io.github.trufelek124.weddingplanner.WeddingPlannerApp
import io.github.trufelek124.weddingplanner.api.StandInServer
import io.github.trufelek124.weddingplanner.data.LivingPlanner
import io.github.trufelek124.weddingplanner.data.StandIn
import io.github.trufelek124.weddingplanner.data.planner
import io.github.trufelek124.weddingplanner.reminders.OpenAt
import io.github.trufelek124.weddingplanner.ui.PHONE
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the widget's presses do to the app: the intents the widget sends, handed to the real activity — when the app was not
 * running, and when it is open on another screen. The wedding is made up.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class WidgetOpensTest {

    @get:Rule(order = 0)
    val folder = TemporaryFolder()

    private lateinit var phone: StandInPhone

    @get:Rule(order = 1)
    val switchedOff = SwitchedOffAfterwards { if (::phone.isInitialized) phone else null }

    @get:Rule(order = 2)
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server get() = phone.server
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun aSignedInPhoneThatLoadedEarlier() {
        phone = StandInPhone(folder.root)
        (context as WeddingPlannerApp).container = phone.container
        server.json("POST /api/auth/dev", StandInServer.session())
        server.json("POST /api/auth/refresh", StandInServer.session(accessToken = "access-2", refreshToken = "refresh-2"))
        server.nothing("POST /api/auth/sign-out")
        server.json("GET /api/auth/config", """{"googleClientId":"client-1.apps.example","devLoginEnabled":false}""")
        server.planner()
        LivingPlanner(
            server,
            entries = listOf(
                StandIn.entry("t1", "Book the band", dueDate = "2026-11-12"),
                StandIn.entry("p1", "Find the venue", kind = "PLAN_ITEM", dueDate = "2026-11-25"),
            ),
        )
        runBlocking {
            phone.container.session.signInForDevelopment("anna@example.com")
            phone.container.planner.load()
        }
        // The app was closed since: the same phone, nothing in memory.
        phone = phone.restarted()
        (context as WeddingPlannerApp).container = phone.container
    }

    @After
    fun closeTheApp() {
        scenario?.close()
    }

    private fun fromWidget(opening: String?) = openFromWidget(context, opening)

    private fun start(intent: Intent) {
        scenario = ActivityScenario.launch(intent)
    }

    private fun heading(text: String) = hasText(text) and isHeading()

    private fun waitForHeading(text: String) {
        compose.waitUntil(WAIT) { compose.onAllNodes(heading(text)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForNoHeading(text: String) {
        compose.waitUntil(WAIT) { compose.onAllNodes(heading(text)).fetchSemanticsNodes().isEmpty() }
    }

    private fun waitFor(text: String) {
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitUntil(what: () -> Boolean) {
        compose.waitUntil(WAIT) {
            compose.waitForIdle()
            what()
        }
    }

    private fun press(label: String) {
        compose.onNode(hasText(label) and hasClickAction()).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun field(label: String) = compose.onNode(hasText(label) and hasSetTextAction())

    private fun sent(route: String) = server.routes(route).map { Json.parseToJsonElement(it.body).jsonObject }

    private fun deliver(intent: Intent) {
        // What the phone does when the app is already open and the widget's press comes to it.
        scenario!!.onActivity {
            MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java).apply { isAccessible = true }.invoke(it, intent)
        }
    }

    @Test
    fun `the widget's intent names what to open, and starts the app at its front`() {
        val intent = fromWidget(OpenAt.ADD_STEP)

        assertEquals(MainActivity::class.java.name, intent.component!!.className)
        assertEquals(OpenAt.ADD_STEP, intent.getStringExtra(OpenAt.EXTRA))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertEquals(null, fromWidget(null).getStringExtra(OpenAt.EXTRA))
    }

    @Test
    fun `Add a task opens the form for a new task on top of the tasks, and leaving it leads back to the tasks`() {
        start(fromWidget(OpenAt.ADD_TASK))

        waitForHeading("Add a task")
        press("Cancel")

        waitForHeading("Tasks")
        waitForNoHeading("Add a task")
    }

    @Test
    fun `a task saved from there is added, and the tasks are shown`() {
        start(fromWidget(OpenAt.ADD_TASK))
        waitForHeading("Add a task")

        field("Title").performTextInput("Order the cake")
        press("Save")

        waitForHeading("Tasks")
        waitUntil { server.routes("POST /api/entries").isNotEmpty() }
        val added = sent("POST /api/entries").single()
        assertEquals("\"TASK\"", added.getValue("kind").toString())
        assertEquals("\"Order the cake\"", added.getValue("title").toString())
    }

    @Test
    fun `Add a step opens the form for a new step on top of the plan, and leaving it leads back to the plan`() {
        start(fromWidget(OpenAt.ADD_STEP))

        waitForHeading("Add a step")
        press("Cancel")

        waitForHeading("Plan")
        waitForNoHeading("Add a step")
    }

    @Test
    fun `a step saved from there is added as a step, and the plan is shown`() {
        start(fromWidget(OpenAt.ADD_STEP))
        waitForHeading("Add a step")

        field("Name").performTextInput("Plan the honeymoon")
        press("Save")

        waitForHeading("Plan")
        waitUntil { server.routes("POST /api/entries").isNotEmpty() }
        val added = sent("POST /api/entries").single()
        assertEquals("\"PLAN_ITEM\"", added.getValue("kind").toString())
        assertEquals("\"Plan the honeymoon\"", added.getValue("title").toString())
    }

    @Test
    fun `a task's row opens that task's form, and leaving it leads to the tasks`() {
        start(fromWidget(OpenAt.task("t1")))

        waitForHeading("Change the task")
        waitFor("Book the band")
        onNameIs("Book the band")
        press("Cancel")

        waitForHeading("Tasks")
    }

    @Test
    fun `a step's row opens that step's form, and leaving it leads to the plan`() {
        start(fromWidget(OpenAt.step("p1")))

        waitForHeading("Change the step")
        onNameIs("Find the venue")
        press("Cancel")

        waitForHeading("Plan")
    }

    private fun onNameIs(name: String) {
        compose.onNode(hasText(name) and hasSetTextAction()).assertExists()
    }

    @Test
    fun `where the app is already open on another screen the press brings its form there`() {
        start(fromWidget(null))
        waitFor("Anna & Marek")
        compose.onNode(hasText("More") and hasClickAction()).performClick()
        waitForHeading("More")
        compose.onNode(hasText("Guests") and hasClickAction()).performClick()
        waitForHeading("Guests")

        deliver(fromWidget(OpenAt.ADD_STEP))
        waitForHeading("Add a step")
        // Another press while a form is open replaces it: the one that was pressed is shown, and leaving it leads to its place.
        deliver(fromWidget(OpenAt.task("t1")))
        waitForHeading("Change the task")
        onNameIs("Book the band")
        press("Cancel")

        waitForHeading("Tasks")
    }

    @Test
    fun `a press on a row for an entry that is gone says so instead of opening an empty form`() {
        start(fromWidget(OpenAt.task("gone")))

        waitFor("This task is no longer there.")
    }

    @Test
    fun `nobody signed in, any press opens the app at the sign-in and nothing of a plan`() {
        runBlocking { phone.container.session.signOut() }

        start(fromWidget(null))

        waitFor("Sign in to see your plan.")
        compose.onNodeWithText("Book the band").assertDoesNotExistCompat()
    }

    @Test
    fun `starting the app draws the widget anew, for a day that has begun meanwhile`() {
        val before = phone.widget.redraws

        start(fromWidget(null))
        waitFor("Anna & Marek")

        assertTrue(phone.widget.redraws > before)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistCompat() = assertDoesNotExist()

    private companion object {
        const val WAIT = 10_000L
    }
}
