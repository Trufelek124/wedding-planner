package io.github.trufelek124.weddingplanner.share

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.trufelek124.weddingplanner.data.Kept
import io.github.trufelek124.weddingplanner.data.Share
import io.github.trufelek124.weddingplanner.data.StandIn
import io.github.trufelek124.weddingplanner.data.planner
import io.github.trufelek124.weddingplanner.planner.SignedInPhoneTest
import io.github.trufelek124.weddingplanner.ui.PHONE
import io.github.trufelek124.weddingplanner.ui.picture
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.ByteArrayInputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the planner's own screens say about things that were shared to it and are not with the
 * server: that they wait, or that they were not saved and why.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class SharesOnHomeTest : SignedInPhoneTest() {

    private fun shared(share: Share, pictures: Int = 0): String = runBlocking {
        (phone.container.planner.keepShare(share, List(pictures) { number -> { ByteArrayInputStream("picture $number".toByteArray()) } }) as Kept.Yes).inspirationId
    }

    @Test
    fun `without a connection Home says that what was shared is waiting`() {
        runBlocking { phone.container.planner.sync() }
        server.down = true
        shared(Share("https://www.instagram.com/p/Cabc123/", null, ""))
        shared(Share(null, null, ""), pictures = 2)

        open()

        waitFor("No connection. This is what was loaded on 18 Nov 2026 at 14:05. 2 things you shared are waiting to be sent.")
        compose.picture("home-with-shares-waiting")

        // The connection is back: loading again sends them, and the notice goes.
        server.down = false
        server.answer("POST /api/inspirations") { respond(StandIn.inspiration("any", pictures = 2), HttpStatusCode.Created, headersOf("Content-Type", "application/json")) }
        compose.onNodeWithContentDescription("Load again").performClick()
        waitUntil { runBlocking { phone.container.planner.planner.first()!!.shares.isEmpty() } }
        waitUntilGone("No connection.")
        compose.onNodeWithText("2 things you shared are waiting to be sent.").assertDoesNotExist()
    }

    @Test
    fun `a link the server did not take is named with the reason, and can be discarded`() {
        server.problem("POST /api/inspirations", 400, "bad-request", ""","fieldProblems":[{"field":"boardId","message":"Choose one of your boards."}]""")
        shared(Share("https://www.instagram.com/p/Cabc123/", "b9", ""))

        open()

        waitFor("What you shared (https://www.instagram.com/p/Cabc123/) was not saved: Choose one of your boards.")
        compose.picture("home-with-a-share-not-saved")
        compose.onNodeWithText("Discard").performClick()

        waitUntilGone("was not saved")
        assertTrue(runBlocking { phone.container.planner.planner.first()!!.shares.isEmpty() })
    }

    @Test
    fun `pictures the server did not take are named by their number`() {
        server.answer("POST /api/inspirations") { respond(StandIn.inspiration("any"), HttpStatusCode.Created, headersOf("Content-Type", "application/json")) }
        server.otherwise = { request ->
            if (request.url.encodedPath.endsWith("/pictures")) {
                respond(
                    """{"type":"urn:wedding-planner:problem:bad-request","title":"Problem","status":400,"fieldProblems":[{"field":"files","message":"1 is not a picture this planner can show."}]}""",
                    HttpStatusCode.BadRequest,
                    headersOf("Content-Type", "application/problem+json"),
                )
            } else {
                respond("", HttpStatusCode.NoContent)
            }
        }
        shared(Share(null, null, ""), pictures = 2)

        open()

        waitFor("The 2 pictures you shared were not saved: 1 is not a picture this planner can show.")
        compose.onNodeWithText("Discard").assertIsDisplayed()
    }

    @Test
    fun `signing out with something shared and not sent asks first`() {
        runBlocking { phone.container.planner.sync() }
        server.down = true
        shared(Share("https://www.instagram.com/p/Csecret/", null, ""))
        open()
        scrollTo(hasText("Sign out"))

        compose.onNodeWithText("Sign out").performClick()

        compose.onNodeWithText("1 change made on this phone has not reached the server and will be lost.").assertIsDisplayed()
        compose.onAllNodesWithText("Sign out").onLast().performClick()
        waitFor("Sign in to see your plan.")
        assertFalse(phone.everythingKept().contains("Csecret"))
    }
}
