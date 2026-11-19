package io.github.trufelek124.weddingplanner.planner

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.trufelek124.weddingplanner.api.respondPicture
import io.github.trufelek124.weddingplanner.api.respondProblem
import io.github.trufelek124.weddingplanner.data.Kept
import io.github.trufelek124.weddingplanner.data.PictureSize
import io.github.trufelek124.weddingplanner.data.Share
import io.github.trufelek124.weddingplanner.data.StandIn
import io.github.trufelek124.weddingplanner.data.TestPictures
import io.github.trufelek124.weddingplanner.data.planner
import io.github.trufelek124.weddingplanner.ui.PHONE
import io.github.trufelek124.weddingplanner.ui.PHONE_AT_NIGHT
import io.github.trufelek124.weddingplanner.ui.picture
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog

/**
 * The pictures of the inspirations on the board and on an idea's own screen: fetched with the access token, kept on the
 * phone, shown without a connection once the phone holds them. The pictures are made by the test: a red left half, a blue
 * right half and a yellow stripe across the middle, so that one that is stretched, cut or turned shows. Everyone and
 * everything here is made up.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class InspirationPicturesScreenTest : SignedInPhoneTest() {

    /** Two landscape pictures (3:2): the one its link brought, and one of its own. */
    private val lace = StandIn.inspiration(
        "i1", url = "https://www.instagram.com/p/Cabc123/", pictures = 1, previews = 1, title = "Lace sleeves", previewStatus = "FETCHED",
        tags = listOf("boho"), boardId = "b1", size = 1200 to 800,
    )

    /** Three portrait pictures (9:16) a person sent. */
    private val photos = StandIn.inspiration("i2", pictures = 3, boardId = "b1", note = "From the florist's window", size = 900 to 1600)

    private val board = StandIn.board("b1", "Dress", inspirations = 2)

    /** The server's small and whole versions of each picture. */
    private lateinit var thumbnails: List<ByteArray>
    private lateinit var wholes: List<ByteArray>
    private lateinit var portraitThumbnails: List<ByteArray>
    private lateinit var portraitWholes: List<ByteArray>

    /** While set, the whole versions are kept waiting. */
    @Volatile
    private var holdWhole: CompletableDeferred<Unit>? = null

    @Before
    fun theirIdeas() {
        val colours = listOf(TestPictures.RED to TestPictures.BLUE, TestPictures.GREEN to TestPictures.RED, TestPictures.BLUE to TestPictures.GREEN)
        // Each in the shape the server says: the landscape ones of the first idea, the portrait ones of the second.
        thumbnails = colours.map { (left, right) -> TestPictures.jpeg(300, 200, left, right) }
        wholes = colours.map { (left, right) -> TestPictures.jpeg(900, 600, left, right) }
        portraitThumbnails = colours.map { (left, right) -> TestPictures.jpeg(180, 320, left, right) }
        portraitWholes = colours.map { (left, right) -> TestPictures.jpeg(450, 800, left, right) }
        server.planner(boards = listOf(board), inspirations = listOf(lace, photos))
        servePictures("i1", 2)
        servePictures("i2", 3)
    }

    private fun servePictures(idea: String, count: Int) {
        val small = if (idea == "i2") portraitThumbnails else thumbnails
        val whole = if (idea == "i2") portraitWholes else wholes
        for (number in 0 until count) {
            server.answer("GET /api/inspirations/$idea/pictures/pic-$number") { request ->
                if (request.url.parameters["size"] == PictureSize.FULL.parameter) {
                    holdWhole?.await()
                    respondPicture(whole[number])
                } else {
                    respondPicture(small[number])
                }
            }
        }
    }

    private fun pictureCalls() = server.calls.filter { it.route.contains("/pictures/") }

    private fun openInspirations() {
        goTo("More")
        compose.onNode(hasText("Inspirations") and hasClickAction()).performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Inspirations") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun openIdea(name: String) {
        scrollTo(line(name) and hasClickAction())
        compose.onNode(line(name) and hasClickAction()).performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText(name) and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun facePictures() = compose.onAllNodes(hasTestTag(FACE_PICTURE), useUnmergedTree = true)

    private fun waitForFacePictures(count: Int) {
        compose.waitUntil(WAIT) { facePictures().fetchSemanticsNodes().size >= count }
        compose.waitForIdle()
    }

    private fun near(pixel: Color, argb: Int): Boolean {
        val wanted = Color(argb)
        return abs(pixel.red - wanted.red) < 0.18f && abs(pixel.green - wanted.green) < 0.18f && abs(pixel.blue - wanted.blue) < 0.18f
    }

    /** The colours of the face at its top left, top right and middle: where the halves and the stripe should be. */
    private fun coloursOf(node: androidx.compose.ui.test.SemanticsNodeInteraction): Triple<Color, Color, Color> {
        val pixels = node.captureToImage().toPixelMap()
        return Triple(pixels[pixels.width / 4, pixels.height / 8], pixels[pixels.width * 3 / 4, pixels.height / 8], pixels[pixels.width / 2, pixels.height / 2])
    }

    private fun shared(share: Share, picture: ByteArray): String = runBlocking {
        (phone.container.planner.keepShare(share, listOf { ByteArrayInputStream(picture) }) as Kept.Yes).inspirationId
    }

    /** The planner was loaded, the pictures of the board were fetched in the background, and the app was closed since. */
    private fun aPhoneThatSawTheBoard() {
        runBlocking { phone.container.planner.load() }
        compose.waitUntil(WAIT) { phone.container.pictures.quiet }
        phone = phone.restarted()
    }

    // ---------------------------------------------------------------- the board

    @Test
    fun `a card's face is the first picture, filling it and not stretched, and the card still says what it holds`() {
        open()
        openInspirations()
        waitForFacePictures(2)

        val lacePicture = facePictures().onFirst()
        lacePicture.assertIsDisplayed()
        // The face is wider than the picture is: it is cropped at the top and the bottom, so the halves are where they were
        // and the stripe is in the middle.
        val (left, right, middle) = coloursOf(lacePicture)
        assertTrue("left half is red", near(left, TestPictures.RED))
        assertTrue("right half is blue", near(right, TestPictures.BLUE))
        assertTrue("the stripe is in the middle", near(middle, TestPictures.YELLOW))
        // The picture is decoration: it says nothing of its own, and the card's statement is what it was.
        assertNull(facePictures().fetchSemanticsNodes().first().config.getOrNull(SemanticsProperties.ContentDescription))
        compose.onNode(
            hasContentDescription("Lace sleeves. 2 pictures. Instagram. Dress. 1 more picture. Tags: boho") and hasClickAction(),
        ).assertIsDisplayed()
        compose.onNodeWithText("1 more picture").assertIsDisplayed()
        compose.picture("inspirations-with-pictures")
    }

    @Test
    fun `a portrait picture's face is taller than a landscape one's, and both stay within the bounds`() {
        open()
        openInspirations()
        waitForFacePictures(2)

        val faces = facePictures().fetchSemanticsNodes().map { it.size }
        val (landscape, portrait) = faces.sortedBy { it.height }.let { it.first() to it.last() }
        assertTrue("the portrait one is taller: $landscape $portrait", portrait.height > landscape.height)
        // Wide as the card, never taller than 5:4 nor flatter than 16:9; the landscape one keeps its 3:2, the tall one is cut to 4:5.
        assertEquals(landscape.width, portrait.width)
        assertEquals(portrait.width * 5 / 4.0, portrait.height.toDouble(), 2.0)
        assertEquals(landscape.width * 2 / 3.0, landscape.height.toDouble(), 2.0)
    }

    @Test
    @Config(qualifiers = PHONE_AT_NIGHT)
    fun `the board with its pictures reads in the dark as well`() {
        open()
        openInspirations()
        waitForFacePictures(2)

        compose.onNode(line("Lace sleeves") and hasClickAction()).assertIsDisplayed()
        compose.picture("inspirations-with-pictures-at-night")
    }

    @Test
    fun `with the largest text the board with pictures is not cut off`() {
        open(fontScale = 2f)
        openInspirations()
        waitForFacePictures(1)

        compose.onNode(line("Lace sleeves") and hasClickAction()).assertIsDisplayed()
        compose.picture("inspirations-with-pictures-big-letters")
    }

    @Test
    fun `the board asks for the small version only, with the token`() {
        open()
        openInspirations()
        waitForFacePictures(2)
        compose.waitUntil(WAIT) { phone.container.pictures.quiet }

        val asked = pictureCalls()
        assertEquals(setOf("GET /api/inspirations/i1/pictures/pic-0", "GET /api/inspirations/i2/pictures/pic-0"), asked.map { it.route }.toSet())
        assertTrue(asked.all { it.query == "size=THUMBNAIL" })
        assertTrue(asked.all { it.authorization == "Bearer access-1" })
        // Each once: the load fetched them in the background, and the cards asked for the same ones.
        assertEquals(2, asked.size)
    }

    @Test
    fun `the board is whole without a connection once it was seen, and asks for nothing`() {
        aPhoneThatSawTheBoard()
        server.down = true
        server.calls.clear()

        open()
        openInspirations()
        waitForFacePictures(2)

        val (left, right, _) = coloursOf(facePictures().onFirst())
        assertTrue(near(left, TestPictures.RED) && near(right, TestPictures.BLUE))
        compose.onNode(line("Lace sleeves") and hasClickAction()).assertIsDisplayed()
        assertTrue("nothing was asked for the pictures", pictureCalls().isEmpty())
        compose.picture("inspirations-pictures-offline")
    }

    @Test
    fun `a picture that cannot be had says in words what the card holds, and the board still works`() {
        for (route in listOf("i1/pictures/pic-0", "i1/pictures/pic-1", "i2/pictures/pic-0")) {
            server.problem("GET /api/inspirations/$route", 404, "not-found")
        }
        open()

        openInspirations()

        waitFor("2 pictures")
        waitFor("3 pictures")
        assertEquals(0, facePictures().fetchSemanticsNodes().size)
        compose.onNode(line("Lace sleeves", "2 pictures", "1 more picture") and hasClickAction()).assertIsDisplayed()
        compose.picture("inspirations-pictures-missing")
        openIdea("Lace sleeves")
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText(PICTURE_MISSING)).fetchSemanticsNodes().size == 2 }
        compose.onNode(hasContentDescription("Picture 1 of 2. $PICTURE_MISSING")).assertIsDisplayed()
        compose.picture("inspiration-pictures-missing")
    }

    @Test
    fun `a picture is not asked for again at every look, only after the next load`() {
        server.problem("GET /api/inspirations/i1/pictures/pic-0", 404, "not-found")
        server.problem("GET /api/inspirations/i2/pictures/pic-0", 404, "not-found")
        open()
        openInspirations()
        waitFor("2 pictures")
        compose.waitUntil(WAIT) { phone.container.pictures.quiet }
        val first = pictureCalls().size

        compose.onNodeWithContentDescription("Back").performClick()
        openInspirations()
        waitFor("2 pictures")
        compose.waitForIdle()

        assertEquals(first, pictureCalls().size)

        // The server has them now, and a load says so.
        servePictures("i1", 2)
        servePictures("i2", 3)
        compose.onNodeWithContentDescription("Load again").performClick()
        waitForFacePictures(2)
    }

    // ---------------------------------------------------------------- one idea

    @Test
    fun `an idea shows every picture whole, one under the other, each saying which of how many it is`() {
        open()
        openInspirations()
        openIdea("Pictures")

        for (number in 1..3) {
            scrollTo(hasContentDescription("Picture $number of 3"))
            compose.onNode(hasContentDescription("Picture $number of 3")).assertIsDisplayed()
        }
        scrollTo(hasContentDescription("Picture 1 of 3"))
        val (left, right, middle) = coloursOf(compose.onNode(hasContentDescription("Picture 1 of 3")))
        assertTrue(near(left, TestPictures.RED) && near(right, TestPictures.BLUE) && near(middle, TestPictures.YELLOW))
        val whole = pictureCalls().filter { it.query == "size=FULL" }.map { it.route }.toSet()
        assertEquals(setOf("GET /api/inspirations/i2/pictures/pic-0", "GET /api/inspirations/i2/pictures/pic-1", "GET /api/inspirations/i2/pictures/pic-2"), whole)
        assertTrue(pictureCalls().all { it.authorization == "Bearer access-1" })
        compose.picture("inspiration-with-pictures")
    }

    @Test
    fun `the small version stands where the whole one will be until it has arrived, and the place does not change size`() {
        open()
        openInspirations()
        waitForFacePictures(2)
        compose.waitUntil(WAIT) { phone.container.pictures.quiet }
        holdWhole = CompletableDeferred()

        openIdea("Pictures")

        // The first picture's small version was fetched for the board; the others are not there yet and say nothing.
        val first = compose.onNode(hasContentDescription("Picture 1 of 3"))
        val second = compose.onNode(hasContentDescription("Picture 2 of 3"))
        compose.waitUntil(WAIT) { near(coloursOf(first).third, TestPictures.YELLOW) }
        // The pictures are as tall as their shapes: the second is further down.
        scrollTo(hasContentDescription("Picture 2 of 3"))
        val before = second.fetchSemanticsNode().size
        assertFalse(near(coloursOf(second).third, TestPictures.YELLOW))
        assertEquals(1, pictureCalls().count { it.route.endsWith("i2/pictures/pic-0") && it.query == "size=THUMBNAIL" })

        holdWhole!!.complete(Unit)
        compose.waitUntil(WAIT) { near(coloursOf(compose.onNode(hasContentDescription("Picture 2 of 3"))).third, TestPictures.YELLOW) }

        assertEquals(before, compose.onNode(hasContentDescription("Picture 2 of 3")).fetchSemanticsNode().size)
    }

    @Test
    fun `an idea that was seen is whole without a connection`() {
        runBlocking {
            phone.container.planner.load()
            for (number in 0..2) {
                phone.container.pictures.file("i2", "pic-$number", PictureSize.FULL)
            }
        }
        phone = phone.restarted()
        server.down = true
        server.calls.clear()

        open()
        openInspirations()
        openIdea("Pictures")

        for (number in 1..3) {
            scrollTo(hasContentDescription("Picture $number of 3"))
            compose.waitUntil(WAIT) { near(coloursOf(compose.onNode(hasContentDescription("Picture $number of 3"))).third, TestPictures.YELLOW) }
        }
        assertTrue(pictureCalls().isEmpty())
    }

    // ---------------------------------------------------------------- what waits to be sent

    @Test
    fun `a share that waits to be sent shows its own picture from the phone, without asking the server`() {
        runBlocking { phone.container.planner.sync() }
        server.down = true
        shared(Share(null, "b1", ""), TestPictures.jpeg(600, 800, TestPictures.GREEN, TestPictures.YELLOW, TestPictures.RED))
        server.calls.clear()

        open()
        openInspirations()
        waitForFacePictures(1)

        compose.onNode(hasContentDescription("A picture. Waiting to be sent. 1 picture")).assertIsDisplayed()
        assertTrue(pictureCalls().isEmpty())
        compose.picture("inspirations-with-pictures-waiting")
    }

    // ---------------------------------------------------------------- what is left

    @Test
    fun `nothing of the pictures is written to the log`() {
        ShadowLog.clear()
        open()
        openInspirations()
        waitForFacePictures(2)
        openIdea("Pictures")
        scrollTo(hasContentDescription("Picture 3 of 3"))
        server.down = true

        val written = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable?.stackTraceToString().orEmpty()}" }
        for (theirs in listOf("pic-", "/pictures/", "i1", "i2", "Lace", "sleeves", "florist", "Cabc123", "instagram", "THUMBNAIL", "image/jpeg", "access-1")) {
            assertFalse("the log holds \"$theirs\"", written.contains(theirs, ignoreCase = true))
        }
    }

    @Test
    fun `signing out leaves no picture on the phone`() {
        open()
        openInspirations()
        waitForFacePictures(2)
        compose.waitUntil(WAIT) { phone.container.pictures.quiet }
        val kept = File(folder.root, "pictures")
        assertTrue("the pictures are on the phone to begin with", kept.listFiles().orEmpty().isNotEmpty())
        assertTrue(phone.everythingKept().contains("JFIF"))
        goTo("More")
        scrollTo(hasText("Sign out"))

        compose.onNodeWithText("Sign out").performClick()

        waitFor("Sign in to see your plan.")
        assertEquals(emptyList<String>(), kept.walkTopDown().filter { it.isFile }.map { it.name }.toList())
        assertFalse("no picture is left anywhere in the phone's files", phone.everythingKept().contains("JFIF"))
    }
}
