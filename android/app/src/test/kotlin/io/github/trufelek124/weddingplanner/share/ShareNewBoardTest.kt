package io.github.trufelek124.weddingplanner.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.layout.positionInRoot
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.github.trufelek124.weddingplanner.StandInPhone
import io.github.trufelek124.weddingplanner.SwitchedOffAfterwards
import io.github.trufelek124.weddingplanner.WeddingPlannerApp
import io.github.trufelek124.weddingplanner.api.StandInServer
import io.github.trufelek124.weddingplanner.api.respondJson
import io.github.trufelek124.weddingplanner.data.StandIn
import io.github.trufelek124.weddingplanner.data.planner
import io.github.trufelek124.weddingplanner.ui.PHONE
import io.github.trufelek124.weddingplanner.ui.picture
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowToast

/**
 * Naming a board that does not exist yet while sharing (S09 section 4), on the screen Android opens: where "New board"
 * stands, what is said about a name that cannot be used, and what is kept when the share is saved. A stand-in server
 * and phone. Every name here is made up.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class ShareNewBoardTest {

    @get:Rule(order = 0)
    val folder = TemporaryFolder()

    @get:Rule(order = 1)
    val switchedOff = SwitchedOffAfterwards { if (::phone.isInitialized) phone else null }

    @get:Rule(order = 2)
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var phone: StandInPhone
    private val server get() = phone.server
    private var screen: ActivityScenario<ShareActivity>? = null
    private lateinit var opened: ShareActivity

    @Before
    fun aPhoneWithTheirBoards() {
        phone = StandInPhone(folder.root)
        (context as WeddingPlannerApp).container = phone.container
        server.json("GET /api/auth/config", """{"googleClientId":"client-1.apps.example","devLoginEnabled":true}""")
        server.json("POST /api/auth/dev", StandInServer.session())
        server.json("POST /api/auth/refresh", StandInServer.session(accessToken = "access-2", refreshToken = "refresh-2"))
        server.planner(boards = listOf(StandIn.board("b1", "Dress"), StandIn.board("b2", "Flowers")))
        // The server keeps what it is sent under the id it came with, as the real one does.
        server.answer("POST /api/inspirations") { request ->
            val sent = Json.parseToJsonElement(String((request.body as io.ktor.http.content.TextContent).text.toByteArray())).jsonObject
            respondJson(StandIn.inspiration(sent.getValue("id").toString().trim('"')), 201)
        }
    }

    @After
    fun close() {
        screen?.close()
    }

    private fun signedIn() = runBlocking {
        phone.container.session.signInForDevelopment("anna@example.com")
        phone.container.planner.sync()
        server.calls.clear()
    }

    private fun share(intent: Intent) {
        screen = ActivityScenario.launch<ShareActivity>(intent.setClass(context, ShareActivity::class.java)).also { started ->
            started.onActivity { opened = it }
        }
        compose.waitForIdle()
    }

    private fun aLink(text: String = "https://www.instagram.com/p/Cabc123/") = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)

    /** A real, small picture behind an address another app lends. */
    private fun aPicture(name: String): Uri {
        val painted = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(if (name.hashCode() % 2 == 0) Color.rgb(150, 90, 60) else Color.rgb(60, 110, 95)) }
        val bytes = ByteArrayOutputStream().also { painted.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val address = Uri.parse("content://gallery.example/$name")
        shadowOf(context.contentResolver).registerInputStreamSupplier(address) { ByteArrayInputStream(bytes) }
        return address
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

    private fun sentInspiration() = Json.parseToJsonElement(server.routes("POST /api/inspirations").last().body).jsonObject

    /** The screen has asked to be closed: Android then takes the person back to the app they shared from. */
    private fun closed(): Boolean = opened.isFinishing

    private fun nameField() = compose.onNode(hasText("Name of the new board") and hasSetTextAction())

    private fun typeName(name: String) {
        nameField().performTextInput(name)
        compose.waitForIdle()
    }

    private fun chooseNewBoard() {
        waitFor("New board")
        compose.onNodeWithText("New board").performClick()
        compose.waitForIdle()
    }

    private fun top(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().positionInRoot.let { it.y to it.x }

    private fun waiting() = runBlocking { phone.container.planner.planner.first()!!.shares }

    // ---------------------------------------------------------------- where it is offered

    @Test
    fun `New board is offered after No board and the boards the phone knows`() {
        signedIn()
        share(aLink())

        waitFor("Dress")
        compose.onNodeWithText("New board").assertIsDisplayed()
        compose.onNodeWithText("New board").assertIsNotSelected()
        val order = listOf("No board", "Dress", "Flowers", "New board").map(::top)
        assertEquals(order.sortedWith(compareBy({ it.first }, { it.second })), order)
        // The field for its name is not there until it is chosen.
        nameField().assertDoesNotExist()
    }

    @Test
    fun `New board is offered also when the phone knows no board at all`() {
        server.planner()
        signedIn()
        share(aLink())

        waitFor("Save to the planner")
        compose.onNodeWithText("Board").assertIsDisplayed()
        compose.onNodeWithText("No board").assertIsSelected()
        compose.onNodeWithText("New board").assertIsDisplayed()
    }

    @Test
    fun `choosing New board shows a field for its name and puts the keyboard there`() {
        signedIn()
        share(aLink())

        chooseNewBoard()

        compose.onNodeWithText("New board").assertIsSelected()
        compose.onNodeWithText("No board").assertIsNotSelected()
        compose.onNodeWithText("Dress").assertIsNotSelected()
        nameField().assertIsDisplayed()
        nameField().assertIsFocused()
        // Nothing is said while nothing is wrong yet.
        compose.onNodeWithText("Give the new board a name.").assertDoesNotExist()
        compose.picture("share-new-board-chosen")

        // Another choice takes the field away, and the name typed is not saved with a board that was chosen instead.
        typeName("First dance")
        compose.onNodeWithText("Dress").performClick()
        nameField().assertDoesNotExist()
    }

    // ---------------------------------------------------------------- a name that cannot be used

    @Test
    fun `a name of 61 characters is said beside the field, and the share is not saved`() {
        signedIn()
        share(aLink())
        chooseNewBoard()

        typeName("x".repeat(61))

        compose.onNodeWithText("A board's name has at most 60 characters.").assertIsDisplayed()
        compose.picture("share-new-board-too-long")
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()

        assertFalse(closed())
        assertTrue(waiting().isEmpty())
        assertTrue(server.routes("POST /api/inspirations").isEmpty())
        assertEquals(0, phone.askedToSendLater)
    }

    @Test
    fun `a name of 60 characters is the longest that is saved`() {
        signedIn()
        share(aLink())
        chooseNewBoard()
        server.down = true

        typeName("x".repeat(60))
        compose.onNodeWithText("A board's name has at most 60 characters.").assertDoesNotExist()
        compose.onNodeWithText("Save").performClick()

        waitUntil { closed() }
        waitUntil { waiting().size == 1 }
        assertEquals("x".repeat(60), waiting().single().newBoard)
    }

    @Test
    fun `a name of only spaces is said beside the field once Save was pressed, and the share is not saved`() {
        signedIn()
        share(aLink())
        chooseNewBoard()

        typeName("     ")
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Give the new board a name.").assertIsDisplayed()
        nameField().assertIsFocused()
        assertFalse(closed())
        assertTrue(waiting().isEmpty())

        // Once the name is something, the share can be saved.
        server.down = true
        typeName("First dance")
        compose.onNodeWithText("Give the new board a name.").assertDoesNotExist()
        compose.onNodeWithText("Save").performClick()
        waitUntil { closed() }
        waitUntil { waiting().size == 1 }
        assertEquals("First dance", waiting().single().newBoard)
    }

    @Test
    fun `no name at all is said in the same words`() {
        signedIn()
        share(aLink())
        chooseNewBoard()

        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Give the new board a name.").assertIsDisplayed()
        assertFalse(closed())
    }

    // ---------------------------------------------------------------- a name a board has already

    @Test
    fun `the name of a board that is known, in another spelling, chooses that board`() {
        signedIn()
        share(aLink())
        chooseNewBoard()
        server.down = true

        typeName("  flowers ")
        compose.onNodeWithText("Save").performClick()

        // Kept on the known board, with no board to be made.
        waitUntil { closed() }
        waitUntil { waiting().size == 1 }
        assertEquals("b2", waiting().single().boardId)
        assertNull(waiting().single().newBoard)

        server.down = false
        runBlocking { phone.container.planner.sync() }
        assertEquals("\"b2\"", sentInspiration().getValue("boardId").toString())
        assertTrue(server.routes("POST /api/boards").isEmpty())
    }

    // ---------------------------------------------------------------- what is kept

    @Test
    fun `saving closes the screen and keeps the share with the name of the new board, which is sent when there is a connection`() {
        signedIn()
        share(aLink())
        chooseNewBoard()
        server.down = true

        typeName("  First dance ")
        compose.onNode(hasText("Note") and hasSetTextAction()).performTextInput("Slow, with lights")
        compose.onNodeWithText("Save").performClick()

        waitUntil { closed() }
        assertEquals("Saved to the planner", ShadowToast.getTextOfLatestToast())
        waitUntil { waiting().size == 1 }
        val kept = waiting().single()
        assertEquals("First dance", kept.newBoard)
        assertNull(kept.boardId)
        assertEquals("https://www.instagram.com/p/Cabc123/", kept.link)
        assertEquals(1, phone.askedToSendLater)

        server.down = false
        server.json("POST /api/boards", StandIn.board("b3", "First dance"), io.ktor.http.HttpStatusCode.Created)
        runBlocking { phone.container.planner.sync() }

        assertEquals("""{"name":"First dance"}""", Json.parseToJsonElement(server.routes("POST /api/boards").single().body).toString())
        assertEquals("\"b3\"", sentInspiration().getValue("boardId").toString())
        assertEquals("\"Slow, with lights\"", sentInspiration().getValue("note").toString())
        assertTrue(waiting().isEmpty())
    }

    @Test
    fun `nothing of the share, the name of its board included, is written to the log`() {
        ShadowLog.clear()
        signedIn()
        server.json("POST /api/boards", StandIn.board("b3", "Surprise honeymoon"), io.ktor.http.HttpStatusCode.Created)
        share(aLink("https://www.instagram.com/p/Csecret-dress/"))
        chooseNewBoard()
        typeName("Surprise honeymoon")
        compose.onNode(hasText("Note") and hasSetTextAction()).performTextInput("Do not show Marek")
        compose.onNodeWithText("Save").performClick()
        waitUntil { server.routes("POST /api/inspirations").isNotEmpty() }

        val written = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable?.stackTraceToString().orEmpty()}" }
        for (theirs in listOf("Csecret-dress", "Do not show", "Marek", "Surprise", "honeymoon", "access-1")) {
            assertFalse("the log holds \"$theirs\"", written.contains(theirs, ignoreCase = true))
        }
    }

    @Test
    fun `the screen with a new board chosen and a name typed, in a picture`() {
        signedIn()
        share(aLink())
        chooseNewBoard()

        typeName("First dance")
        compose.picture("share-new-board")
    }

    private companion object {
        const val WAIT = 10_000L
    }
}
