package io.github.trufelek124.weddingplanner.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
 * Sharing to the planner from another app, as Android starts it: the screen that opens, what it
 * saves, and that the person is back where they came from at once. A stand-in server and phone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class ShareActivityTest {

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

    // ---------------------------------------------------------------- a link

    @Test
    fun `a link from Instagram is saved onto a board in two taps, and the screen is gone`() {
        signedIn()
        share(aLink())

        waitFor("Save to the planner")
        compose.onNodeWithText("https://www.instagram.com/p/Cabc123/").assertIsDisplayed()
        waitFor("Dress")
        compose.onNodeWithText("No board").assertIsSelected()
        compose.picture("share-a-link")

        compose.onNodeWithText("Dress").performClick()
        compose.onNodeWithText("Save").performClick()

        waitUntil { closed() }
        assertEquals("Saved to the planner", ShadowToast.getTextOfLatestToast())
        waitUntil { server.routes("POST /api/inspirations").isNotEmpty() }
        val sent = sentInspiration()
        assertEquals("\"https://www.instagram.com/p/Cabc123/\"", sent.getValue("url").toString())
        assertEquals("\"b1\"", sent.getValue("boardId").toString())
        assertEquals("\"\"", sent.getValue("note").toString())
        // The phone was also asked to send it later, should it not get through now.
        assertEquals(1, phone.askedToSendLater)
        waitUntil { runBlocking { phone.container.planner.planner.first()!!.shares.isEmpty() } }
    }

    @Test
    fun `one tap is enough - without a board and without a note`() {
        signedIn()
        share(aLink("Check out this video! https://vm.tiktok.com/ZMabc12/ This post is shared via TikTok."))
        waitFor("https://vm.tiktok.com/ZMabc12/")

        compose.onNodeWithText("Save").performClick()

        waitUntil { closed() }
        waitUntil { server.routes("POST /api/inspirations").isNotEmpty() }
        assertEquals("\"https://vm.tiktok.com/ZMabc12/\"", sentInspiration().getValue("url").toString())
        assertEquals("null", sentInspiration().getValue("boardId").toString())
    }

    @Test
    fun `a note typed here goes with it`() {
        signedIn()
        share(aLink())
        waitFor("Save to the planner")

        compose.onNode(hasText("Note") and hasSetTextAction()).performTextInput("Lace sleeves like these")
        compose.onNodeWithText("Flowers").performClick()
        compose.onNodeWithText("Save").performClick()

        waitUntil { server.routes("POST /api/inspirations").isNotEmpty() }
        assertEquals("\"Lace sleeves like these\"", sentInspiration().getValue("note").toString())
        assertEquals("\"b2\"", sentInspiration().getValue("boardId").toString())
    }

    @Test
    fun `text without an address is offered as a note`() {
        signedIn()
        share(aLink("Peonies in June, ask the florist"))

        waitFor("A note")
        compose.onNode(hasText("Peonies in June, ask the florist") and hasSetTextAction()).assertIsDisplayed()
        compose.onNodeWithText("Save").performClick()

        waitUntil { server.routes("POST /api/inspirations").isNotEmpty() }
        assertEquals("null", sentInspiration().getValue("url").toString())
        assertEquals("\"Peonies in June, ask the florist\"", sentInspiration().getValue("note").toString())
    }

    // ---------------------------------------------------------------- pictures

    @Test
    fun `pictures are saved as one inspiration, the inspiration first and then the pictures`() {
        signedIn()
        var id = ""
        server.answer("POST /api/inspirations") { request ->
            id = Json.parseToJsonElement((request.body as io.ktor.http.content.TextContent).text).jsonObject.getValue("id").toString().trim('"')
            server.json("POST /api/inspirations/$id/pictures", StandIn.inspiration(id, pictures = 2))
            respondJson(StandIn.inspiration(id), 201)
        }
        share(Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(aPicture("one"), aPicture("two"))))

        waitFor("2 pictures")
        compose.picture("share-pictures")
        compose.onNodeWithText("Dress").performClick()
        compose.onNodeWithText("Save").performClick()

        waitUntil { closed() }
        waitUntil { id.isNotEmpty() && server.routes("POST /api/inspirations/$id/pictures").isNotEmpty() }
        assertEquals("null", sentInspiration().getValue("url").toString())
        assertEquals("\"b1\"", sentInspiration().getValue("boardId").toString())
        val upload = server.routes("POST /api/inspirations/$id/pictures").single()
        assertEquals(2, Regex("name=\"?files\"?").findAll(upload.body).count())
        waitUntil { runBlocking { phone.container.planner.planner.first()!!.shares.isEmpty() } }
    }

    @Test
    fun `a single picture, as a screenshot is shared`() {
        signedIn()
        share(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, aPicture("screenshot")))

        waitFor("1 picture")
        compose.onNodeWithText("Save").performClick()

        waitUntil { closed() }
        waitUntil { server.routes("POST /api/inspirations").isNotEmpty() }
        assertEquals("null", sentInspiration().getValue("url").toString())
    }

    @Test
    fun `more pictures than an inspiration holds are said to be too many, and the screen stays`() {
        signedIn()
        val many = ArrayList((1..11).map { aPicture("p$it") })
        share(Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, many))
        waitFor("11 pictures")

        compose.onNodeWithText("Save").performClick()

        waitFor("An inspiration holds at most 10 pictures. Share fewer at a time.")
        assertFalse(closed())
        assertTrue(runBlocking { phone.container.planner.planner.first()!!.shares.isEmpty() })
    }

    // ---------------------------------------------------------------- without a connection, and signed out

    @Test
    fun `without a connection the share is kept, the screen closes all the same, and it is sent later`() {
        signedIn()
        share(aLink())
        waitFor("Save to the planner")
        server.down = true

        compose.onNodeWithText("Dress").performClick()
        compose.onNodeWithText("Save").performClick()

        waitUntil { closed() }
        assertEquals("Saved to the planner", ShadowToast.getTextOfLatestToast())
        waitUntil { runBlocking { phone.container.planner.planner.first()!!.shares.size == 1 } }
        assertEquals(1, phone.askedToSendLater)

        server.down = false
        runBlocking { phone.container.planner.sync() }
        assertEquals("\"b1\"", sentInspiration().getValue("boardId").toString())
        assertTrue(runBlocking { phone.container.planner.planner.first()!!.shares.isEmpty() })
    }

    @Test
    fun `someone who is signed out signs in first and finds what was shared still there`() {
        share(aLink())

        waitFor("Sign in to save this to the planner. What you shared waits here.")
        compose.onNodeWithText("https://www.instagram.com/p/Cabc123/").assertDoesNotExist()
        compose.picture("share-signed-out")

        waitFor("Development sign-in")
        compose.onNodeWithText("E-mail address").performTextInput("anna@example.com")
        compose.onNodeWithText("Sign in").performClick()

        waitFor("https://www.instagram.com/p/Cabc123/")
        waitFor("Dress")
        compose.onNodeWithText("Flowers").performClick()
        compose.onNodeWithText("Save").performClick()

        waitUntil { closed() }
        waitUntil { server.routes("POST /api/inspirations").isNotEmpty() }
        assertEquals("\"https://www.instagram.com/p/Cabc123/\"", sentInspiration().getValue("url").toString())
        assertEquals("\"b2\"", sentInspiration().getValue("boardId").toString())
    }

    // ---------------------------------------------------------------- the rest

    @Test
    fun `Cancel closes without saving anything`() {
        signedIn()
        share(aLink())
        waitFor("Save to the planner")

        compose.onNodeWithText("Cancel").performClick()

        waitUntil { closed() }
        assertTrue(server.routes("POST /api/inspirations").isEmpty())
        assertTrue(runBlocking { phone.container.planner.planner.first()!!.shares.isEmpty() })
        assertEquals(0, phone.askedToSendLater)
    }

    @Test
    fun `what the planner cannot keep is said, with a way out`() {
        signedIn()
        share(Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, Uri.parse("content://gallery.example/film")))

        waitFor("There is nothing here the planner can save. It takes a link, or pictures.")
        compose.onNodeWithText("Save").assertDoesNotExist()
        compose.onNodeWithText("Close").performClick()

        waitUntil { closed() }
    }

    @Test
    fun `nothing of what was shared is written to the log`() {
        ShadowLog.clear()
        signedIn()
        share(aLink("https://www.instagram.com/p/Csecret-dress/"))
        waitFor("Save to the planner")
        compose.onNode(hasText("Note") and hasSetTextAction()).performTextInput("Do not show Marek")
        compose.onNodeWithText("Save").performClick()
        waitUntil { server.routes("POST /api/inspirations").isNotEmpty() }

        val written = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable?.stackTraceToString().orEmpty()}" }
        for (theirs in listOf("Csecret-dress", "Do not show", "Marek", "access-1")) {
            assertFalse("the log holds \"$theirs\"", written.contains(theirs, ignoreCase = true))
        }
    }

    private companion object {
        const val WAIT = 10_000L
    }
}
