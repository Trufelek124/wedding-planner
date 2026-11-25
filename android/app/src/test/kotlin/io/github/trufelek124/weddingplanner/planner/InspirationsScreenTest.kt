package io.github.trufelek124.weddingplanner.planner

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.trufelek124.weddingplanner.data.Kept
import io.github.trufelek124.weddingplanner.data.Share
import io.github.trufelek124.weddingplanner.data.StandIn
import io.github.trufelek124.weddingplanner.data.planner
import io.github.trufelek124.weddingplanner.data.plannerUnreachable
import io.github.trufelek124.weddingplanner.ui.PHONE
import io.github.trufelek124.weddingplanner.ui.PHONE_AT_NIGHT
import io.github.trufelek124.weddingplanner.ui.PlannerTheme
import io.github.trufelek124.weddingplanner.ui.picture
import java.io.ByteArrayInputStream
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
 * The inspirations behind More: the board of ideas narrowed down by board and by tag, and one idea with
 * its facts and the way to its original. The same facts and words as the web's board, kept on the phone and
 * shown without a connection. The pictures themselves are in InspirationPicturesScreenTest: here the stand-in server has none to give, so where one belongs the card says in words what
 * is there. Everyone and everything here is made up.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class InspirationsScreenTest : SignedInPhoneTest() {

    private val instagram = "https://www.instagram.com/p/Cabc123/"

    /** Everything an idea can have: a title, a link, a note, two tags, a board, a plan item, a vendor, a picture of its own and one from its link. */
    private val lace = StandIn.inspiration(
        "i1", url = instagram, boardId = "b1", note = "Soft, with a train", pictures = 1, previews = 1, title = "Lace sleeves",
        previewStatus = "FETCHED", tags = listOf("boho", "lace"), planItemId = "p1", vendorId = "v1",
    )

    /** A link elsewhere on the web whose page has a title, added by Marek. */
    private val arch = StandIn.inspiration(
        "i2", url = "https://www.palace.example/ideas", source = "WEB", previewTitle = "Garden arch", previewStatus = "FETCHED", previews = 1,
        boardId = "b2", tags = listOf("boho"), addedById = StandIn.MAREK,
    )

    /** A link whose preview is still being fetched, on no board. */
    private val dance = StandIn.inspiration("i3", url = "https://vm.tiktok.com/ZMabc/", source = "TIKTOK")

    /** Three pictures a person sent. */
    private val photos = StandIn.inspiration("i4", pictures = 3, boardId = "b1", tags = listOf("lace"), note = "From the florist's window")

    /** Neither a link nor a picture. */
    private val thought = StandIn.inspiration("i5", note = "Think about it")

    /** A source a newer server knows. */
    private val strange = StandIn.inspiration("i6", url = "https://bsky.example/x", source = "BLUESKY", previewStatus = "NONE")

    /** A link that is no web address. */
    private val script = StandIn.inspiration("i7", url = "javascript:alert(1)", source = "WEB", previewStatus = "NONE")

    private val ideas = listOf(lace, arch, dance, photos, thought, strange, script)

    private val boards = listOf(StandIn.board("b1", "Dress", inspirations = 2), StandIn.board("b2", "Flowers", inspirations = 1))

    @Before
    fun theirIdeas() {
        server.planner(
            entries = listOf(StandIn.entry("p1", "Book the venue", kind = "PLAN_ITEM")),
            vendors = listOf(StandIn.vendor("v1", "Palace on the Hill", inspirations = 1)),
            boards = boards,
            inspirations = ideas,
        )
    }

    private val sayingOpenTheIdea = SemanticsMatcher("leads to an idea") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == "open this idea"
    }

    private fun openInspirations() {
        goTo("More")
        compose.onNode(hasText("Inspirations") and hasClickAction()).performClick()
        waitForTheBoard()
    }

    private fun waitForTheBoard() {
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Inspirations") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun openIdea(name: String) {
        scrollTo(line(name) and hasClickAction())
        compose.onNode(line(name) and hasClickAction()).performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText(name) and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        // The stand-in server has no pictures to give: each place settles on its words before the page is looked at, or the
        // page changes its length under the test.
        val pictures = mapOf("Lace sleeves" to 2, "Garden arch" to 1, "Pictures" to 3)[name] ?: 0
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText(PICTURE_MISSING)).fetchSemanticsNodes().size == pictures }
        compose.waitForIdle()
    }

    private fun pressBackButton() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    private fun chip(name: String) = compose.onNode(hasText(name) and hasClickAction() and isSelectable())

    /** The chips scroll sideways: they are brought into view both ways. */
    private fun pressChip(name: String) {
        scrollTo(hasText(name) and isSelectable())
        chip(name).performScrollTo().performClick()
    }

    /**
     * The cards the board shows, top to bottom, by name. The list is lazy: it is brought to its chips first, under
     * which a narrowed-down board of a few cards stands in view.
     */
    private fun shownIdeas(): List<String> {
        scrollTo(hasText("All boards") and isSelectable())
        return compose.onAllNodes(sayingOpenTheIdea).fetchSemanticsNodes().map { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull().orEmpty().substringBefore(". ")
        }
    }

    private fun shared(share: Share, pictures: Int = 0): String = runBlocking {
        (phone.container.planner.keepShare(share, List(pictures) { number -> { ByteArrayInputStream("picture $number".toByteArray()) } }) as Kept.Yes).inspirationId
    }

    // ---------------------------------------------------------------- More

    @Test
    fun `More has a row for the inspirations that says how many ideas are kept`() {
        open()

        goTo("More")

        compose.onNode(line("Inspirations", "7 inspirations") and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun `More says that nothing is kept yet, and one idea is not many`() {
        server.planner()
        open()
        goTo("More")
        compose.onNode(line("Inspirations", "Nothing kept yet")).assertIsDisplayed()

        server.planner(inspirations = listOf(lace))
        compose.onNodeWithContentDescription("Load again").performClick()

        waitFor("1 inspiration")
    }

    @Test
    fun `More says how many shares are waiting to be sent`() {
        runBlocking { phone.container.planner.sync() }
        server.down = true
        shared(Share("https://vm.tiktok.com/ZMabc/", null, ""))
        shared(Share(null, null, ""), pictures = 2)
        open()

        goTo("More")

        compose.onNode(line("Inspirations", "7 inspirations, 2 waiting to be sent")).assertIsDisplayed()
    }

    // ---------------------------------------------------------------- the cards

    @Test
    fun `every fact of an idea is on its card, which is one statement`() {
        open()
        openInspirations()

        compose.onNode(hasText("More") and isSelectable()).assertIsSelected()
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        compose.onNode(
            line("2 pictures", "Lace sleeves", "Instagram", "Dress", "For Book the venue", "1 more picture", "Soft, with a train") and hasClickAction() and
                hasContentDescription("Lace sleeves. 2 pictures. Instagram. Dress. For Book the venue. 1 more picture. Soft, with a train. Tags: boho, lace"),
        ).assertIsDisplayed()
        // A tag is a button of its own on the card.
        for (tag in listOf("boho", "lace")) {
            assertTrue(compose.onAllNodes(hasText(tag) and hasContentDescription("Show only \u201c$tag\u201d") and hasClickAction()).fetchSemanticsNodes().isNotEmpty())
        }
        compose.picture("inspirations")
    }

    @Test
    fun `a link elsewhere on the web is named by its page and its site`() {
        open()
        openInspirations()
        scrollTo(line("Garden arch") and hasClickAction())

        compose.onNode(
            line("1 picture", "Garden arch", "palace.example", "Flowers") and hasClickAction() and
                hasContentDescription("Garden arch. 1 picture. palace.example. Flowers. Tags: boho"),
        ).assertIsDisplayed()
    }

    @Test
    fun `where the picture will be, the card says in words what is there`() {
        open()
        openInspirations()

        // A link whose preview is still being fetched.
        scrollTo(line("From TikTok") and hasClickAction())
        compose.onNode(line("From TikTok", "TikTok", "Fetching a preview\u2026") and hasClickAction()).assertIsDisplayed()
        // Pictures a person sent: how many, and how many more than the first.
        scrollTo(line("Pictures") and hasClickAction())
        compose.onNode(line("3 pictures", "Pictures", "2 more pictures", "From the florist's window") and hasClickAction()).assertIsDisplayed()
        // Neither a link nor a picture.
        scrollTo(line("An idea") and hasClickAction())
        compose.onNode(line("No picture", "An idea", "Think about it") and hasClickAction()).assertIsDisplayed()
        // A link without a picture says where it comes from; one that is no web address says Web.
        scrollTo(line("From Web") and hasClickAction())
        compose.onNode(line("Web", "From Web") and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun `a source this app does not know reads Other`() {
        open()
        openInspirations()
        scrollTo(line("From Other") and hasClickAction())

        compose.onNode(line("Other", "From Other") and hasClickAction()).assertIsDisplayed()
        openIdea("From Other")
        compose.onNode(line("Comes from", "Other")).assertIsDisplayed()
    }

    // ---------------------------------------------------------------- narrowing down

    @Test
    fun `the chips are all boards, each board by name and no board, and then any tag and the tags that occur`() {
        open()
        openInspirations()
        scrollTo(hasText("All boards") and isSelectable())

        chip("All boards").assertIsSelected()
        for (board in listOf("Dress", "Flowers", "On no board")) {
            compose.onNode(hasText(board) and isSelectable()).assertExists()
        }
        chip("Any tag").assertIsSelected()
        for (tag in listOf("boho", "lace")) {
            compose.onNode(hasText(tag) and isSelectable()).assertExists()
        }
    }

    @Test
    fun `a board narrows the list down, and so does no board`() {
        open()
        openInspirations()

        pressChip("Dress")

        chip("Dress").assertIsSelected()
        chip("All boards").assertIsNotSelected()
        assertEquals(listOf("Lace sleeves", "Pictures"), shownIdeas())

        pressChip("Flowers")
        assertEquals(listOf("Garden arch"), shownIdeas())

        pressChip("On no board")
        scrollTo(line("From TikTok") and hasClickAction())
        scrollTo(line("An idea") and hasClickAction())
        compose.onNode(line("Lace sleeves") and hasClickAction()).assertDoesNotExist()
        compose.onNode(line("Garden arch") and hasClickAction()).assertDoesNotExist()

        pressChip("All boards")
        assertEquals("Lace sleeves", shownIdeas().first())
    }

    @Test
    fun `a tag narrows the list down, and any tag takes it away`() {
        open()
        openInspirations()

        pressChip("boho")

        chip("boho").assertIsSelected()
        chip("Any tag").assertIsNotSelected()
        assertEquals(listOf("Lace sleeves", "Garden arch"), shownIdeas())

        pressChip("lace")
        assertEquals(listOf("Lace sleeves", "Pictures"), shownIdeas())

        pressChip("Any tag")
        assertEquals("Lace sleeves", shownIdeas().first())
        chip("Any tag").assertIsSelected()
    }

    @Test
    fun `a board and a tag together narrow it down to what is on both`() {
        open()
        openInspirations()

        pressChip("Dress")
        pressChip("boho")
        assertEquals(listOf("Lace sleeves"), shownIdeas())

        pressChip("lace")
        assertEquals(listOf("Lace sleeves", "Pictures"), shownIdeas())
    }

    @Test
    fun `pressing a tag on a card chooses that tag`() {
        open()
        openInspirations()
        scrollTo(hasContentDescription("Show only \u201clace\u201d") and hasClickAction())

        compose.onAllNodes(hasContentDescription("Show only \u201clace\u201d") and hasClickAction()).onFirst().performClick()

        compose.waitForIdle()
        chip("lace").assertIsSelected()
        assertEquals(listOf("Lace sleeves", "Pictures"), shownIdeas())
        // The card did not open: the board is still there.
        compose.onNode(hasText("Inspirations") and isHeading()).assertIsDisplayed()
    }

    @Test
    fun `nothing matches says so and says what to do, and the choices stay`() {
        open()
        openInspirations()

        pressChip("Flowers")
        pressChip("lace")

        compose.onNodeWithText("Nothing here matches. Choose another board or tag.").assertIsDisplayed()
        chip("Flowers").assertIsSelected()
        chip("lace").assertIsSelected()
        assertTrue(shownIdeas().isEmpty())
    }

    @Test
    fun `a chosen tag that no idea has any more stays chosen, and the list says nothing matches`() {
        open()
        openInspirations()
        pressChip("lace")
        server.planner(
            entries = listOf(StandIn.entry("p1", "Book the venue", kind = "PLAN_ITEM")), boards = boards,
            inspirations = listOf(arch, dance),
        )

        compose.onNodeWithContentDescription("Load again").performClick()

        waitFor("Nothing here matches. Choose another board or tag.")
        chip("lace").assertIsSelected()
        pressChip("Any tag")
        assertEquals(listOf("Garden arch", "From TikTok"), shownIdeas())
        compose.onNodeWithText("Nothing here matches. Choose another board or tag.").assertDoesNotExist()
    }

    @Test
    fun `a chosen board that is gone since is not chosen any more`() {
        open()
        openInspirations()
        pressChip("Flowers")
        server.planner(boards = listOf(StandIn.board("b1", "Dress")), inspirations = listOf(lace))

        compose.onNodeWithContentDescription("Load again").performClick()

        waitFor("Lace sleeves")
        chip("All boards").assertIsSelected()
        compose.onNodeWithText("Nothing here matches. Choose another board or tag.").assertDoesNotExist()
    }

    @Test
    fun `the chosen board and tag are kept while the phone is turned`() {
        runBlocking { phone.container.planner.load() }
        val planner = runBlocking { phone.container.planner.planner.first() }!!
        val turning = StateRestorationTester(compose)
        turning.setContent { PlannerTheme { InspirationsContent(planner, onOpen = {}) } }
        pressChip("Dress")
        pressChip("lace")
        assertEquals(listOf("Lace sleeves", "Pictures"), shownIdeas())

        turning.emulateSavedInstanceStateRestore()

        chip("Dress").assertIsSelected()
        chip("lace").assertIsSelected()
        assertEquals(listOf("Lace sleeves", "Pictures"), shownIdeas())
    }

    @Test
    fun `with no board at all there are no board chips, and without a tag no tag chips`() {
        server.planner(inspirations = listOf(thought, dance))
        open()
        openInspirations()

        compose.onNodeWithText("All boards").assertDoesNotExist()
        compose.onNodeWithText("On no board").assertDoesNotExist()
        compose.onNodeWithText("Any tag").assertDoesNotExist()
        compose.onNode(line("An idea", "Think about it") and hasClickAction()).assertIsDisplayed()
    }

    // ---------------------------------------------------------------- nothing kept

    @Test
    fun `with nothing kept the board says how to keep something`() {
        server.planner()
        open()

        openInspirations()

        compose.onNodeWithText("Nothing kept yet. Share a link or a picture to Wedding planner from another app, or add one in the web app.").assertIsDisplayed()
        compose.onNodeWithText("All boards").assertDoesNotExist()
        compose.onNodeWithText("Any tag").assertDoesNotExist()
        compose.picture("inspirations-empty")
    }

    // ---------------------------------------------------------------- what waits to be sent

    @Test
    fun `what was shared on this phone and is not sent stands first, as a card that cannot be opened`() {
        runBlocking { phone.container.planner.sync() }
        server.down = true
        shared(Share("https://vm.tiktok.com/ZMabc/", null, ""))
        shared(Share(null, "b1", ""), pictures = 2)
        open()

        openInspirations()
        // The copy of the shared pictures is read from the phone's own folder, which takes a moment.
        waitFor("2 pictures")

        compose.onNode(line("Waiting to be sent", "From TikTok", "TikTok") and hasContentDescription("From TikTok. Waiting to be sent. TikTok")).assertIsDisplayed()
        compose.onNode(line("Waiting to be sent", "Pictures", "2 pictures") and hasContentDescription("Pictures. Waiting to be sent. 2 pictures")).assertIsDisplayed()
        compose.onNode(line("Waiting to be sent") and hasClickAction()).assertDoesNotExist()
        // They stand before the ideas that are kept.
        val tops = listOf("From TikTok", "Pictures").map { name ->
            compose.onNode(line("Waiting to be sent", name)).fetchSemanticsNode().positionInRoot.y
        }
        assertTrue(tops[0] < tops[1])
        // The first idea that is kept is further down, if the list has come to it at all.
        assertTrue(compose.onAllNodes(sayingOpenTheIdea).fetchSemanticsNodes().all { it.positionInRoot.y > tops[1] })
        compose.picture("inspirations-waiting")

        // Narrowed down by board, what waits goes by its board too; a tag leaves it out.
        pressChip("Dress")
        compose.onNode(line("Waiting to be sent", "Pictures")).assertExists()
        compose.onNode(line("Waiting to be sent", "From TikTok")).assertDoesNotExist()
        pressChip("lace")
        compose.onNode(line("Waiting to be sent")).assertDoesNotExist()
    }

    @Test
    fun `a share that waits is the board's even when nothing else is kept`() {
        server.planner()
        runBlocking { phone.container.planner.sync() }
        server.down = true
        shared(Share("https://www.instagram.com/p/Cabc123/", null, ""))
        open()

        openInspirations()

        compose.onNode(line("Waiting to be sent", "From Instagram", "Instagram")).assertIsDisplayed()
        compose.onNodeWithText("Nothing kept yet", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a share that waits for a board of its own stands under that board's name, as one on a known board does`() {
        runBlocking { phone.container.planner.sync() }
        server.down = true
        shared(Share("https://vm.tiktok.com/ZMabc/", null, "", newBoard = "First dance"))
        shared(Share(null, "b1", ""), pictures = 2)
        shared(Share("https://www.instagram.com/p/Cabc123/", null, "", newBoard = " FIRST dance "))
        open()

        openInspirations()
        waitFor("2 pictures")

        // The name is offered beside the boards that exist, once, as it was typed first.
        chip("First dance").assertExists()
        compose.onAllNodes(hasText("First dance") and isSelectable()).assertCountEquals(1)
        compose.onNode(line("Waiting to be sent", "From TikTok")).assertExists()

        pressChip("First dance")
        compose.onNode(line("Waiting to be sent", "From TikTok")).assertExists()
        compose.onNode(line("Waiting to be sent", "From Instagram")).assertExists()
        compose.onNode(line("Waiting to be sent", "Pictures")).assertDoesNotExist()
        compose.picture("inspirations-waiting-new-board")

        pressChip("Dress")
        compose.onNode(line("Waiting to be sent", "From TikTok")).assertDoesNotExist()
        compose.onNode(line("Waiting to be sent", "Pictures")).assertExists()
    }

    @Test
    fun `a share that waits for a board of its own offers that board even where the phone knows no board`() {
        server.planner()
        runBlocking { phone.container.planner.sync() }
        server.down = true
        shared(Share("https://vm.tiktok.com/ZMabc/", null, "", newBoard = "First dance"))
        open()

        openInspirations()

        chip("First dance").assertExists()
        compose.onNode(line("Waiting to be sent", "From TikTok")).assertExists()
    }

    @Test
    fun `a share the server did not take keeps its notice on the board, and is no card`() {
        server.problem("POST /api/inspirations", 400, "bad-request", ""","fieldProblems":[{"field":"boardId","message":"Choose one of your boards."}]""")
        shared(Share("https://www.instagram.com/p/Cabc123/", "b9", ""))
        open()

        openInspirations()

        compose.onNodeWithText("What you shared (https://www.instagram.com/p/Cabc123/) was not saved: Choose one of your boards.").assertIsDisplayed()
        compose.onNode(line("Waiting to be sent")).assertDoesNotExist()
        compose.onNodeWithText("Discard").performClick()
        waitUntilGone("was not saved")
    }

    // ---------------------------------------------------------------- one idea

    @Test
    fun `one idea shows where it comes from, its board, plan item, vendor, who added it, its note and tags, and the link`() {
        open()
        openInspirations()

        openIdea("Lace sleeves")

        compose.onNode(hasText("More") and isSelectable()).assertIsSelected()
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        // This phone holds none of its two pictures and the server is not asked for them here: each says so.
        compose.onAllNodes(hasText(PICTURE_MISSING)).assertCountEquals(2)
        compose.onNode(line("Comes from", "Instagram")).assertIsDisplayed()
        compose.picture("inspiration")
        compose.onNode(line("Board", "Dress")).assertIsDisplayed()
        compose.onNode(line("Plan item", "Book the venue")).assertIsDisplayed()
        compose.onNode(line("Vendor", "Palace on the Hill") and hasClickAction()).assertIsDisplayed()
        compose.onNodeWithText("Added by Anna").assertIsDisplayed()
        compose.onNode(hasText("Note") and isHeading()).assertIsDisplayed()
        compose.onNodeWithText("Soft, with a train").assertIsDisplayed()
        compose.onNode(hasText("Tags") and isHeading()).assertIsDisplayed()
        scrollTo(hasContentDescription("Tags: boho, lace"))
        compose.onNode(hasContentDescription("Tags: boho, lace")).assertExists()
        scrollTo(hasContentDescription("Open the original on Instagram"))
        compose.onNode(line("Link", instagram) and hasContentDescription("Open the original on Instagram") and hasClickAction()).assertIsDisplayed()
        compose.picture("inspiration-link")
    }

    @Test
    fun `an idea with little says only what it has`() {
        open()
        openInspirations()

        openIdea("An idea")

        compose.onNode(line("Comes from", "Photo")).assertIsDisplayed()
        compose.onNode(line("Board", "On no board")).assertIsDisplayed()
        compose.onNodeWithText("No picture").assertIsDisplayed()
        compose.onNodeWithText("Plan item").assertDoesNotExist()
        compose.onNodeWithText("Vendor").assertDoesNotExist()
        compose.onNodeWithText("Link").assertDoesNotExist()
        compose.onNode(hasText("Tags") and isHeading()).assertDoesNotExist()
        compose.onNodeWithText("Think about it").assertIsDisplayed()
        // Added by Anna, whom the phone knows.
        compose.onNodeWithText("Added by Anna").assertIsDisplayed()
    }

    @Test
    fun `an idea added by someone the phone does not know says no one`() {
        server.planner(inspirations = listOf(StandIn.inspiration("i1", note = "Stray", addedById = "somebody-else")))
        open()
        openInspirations()

        openIdea("An idea")

        compose.onNodeWithText("Added by", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a link elsewhere on the web is told by its site, and the idea is added by who added it`() {
        open()
        openInspirations()

        openIdea("Garden arch")

        compose.onNode(line("Comes from", "palace.example")).assertIsDisplayed()
        compose.onNode(line("Board", "Flowers")).assertIsDisplayed()
        compose.onNodeWithText("Added by Marek").assertIsDisplayed()
        scrollTo(hasContentDescription("Open the original on palace.example"))
        compose.onNode(line("Link", "https://www.palace.example/ideas") and hasClickAction()).assertIsDisplayed()
    }

    // ---------------------------------------------------------------- opening the phone's apps

    private fun started(): Intent? = shadowOf(compose.activity).nextStartedActivity

    private fun pressTheLink(description: String) {
        scrollTo(hasContentDescription(description))
        compose.onNode(hasContentDescription(description) and hasClickAction()).performClick()
    }

    @Test
    fun `the link opens the original in the phone's browser`() {
        open()
        openInspirations()
        openIdea("Lace sleeves")

        pressTheLink("Open the original on Instagram")

        val intent = started()!!
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(instagram, intent.data.toString())
        compose.onNodeWithText("No app on this phone can open that.").assertDoesNotExist()
    }

    @Test
    fun `a link that is no web address is shown as text and opens nothing`() {
        open()
        openInspirations()
        openIdea("From Web")

        scrollTo(line("Link", "javascript:alert(1)"))
        compose.onNode(line("Link", "javascript:alert(1)")).assertIsDisplayed()
        compose.onNode(line("Link", "javascript:alert(1)") and hasClickAction()).assertDoesNotExist()
        compose.onNode(hasContentDescription("Open the original", substring = true)).assertDoesNotExist()
        assertNull(started())
    }

    @Test
    fun `with no app to take the link the screen stays and says so, and the next press that works takes the message away`() {
        open()
        openInspirations()
        openIdea("Lace sleeves")
        shadowOf(compose.activity.application).checkActivities(true)

        pressTheLink("Open the original on Instagram")

        // The page is shorter than it was with a block of words in place of the pictures: the message may be below the window.
        scrollTo(hasText("No app on this phone can open that."))
        compose.onNodeWithText("No app on this phone can open that.").assertIsDisplayed()
        compose.onNode(hasText("Lace sleeves") and isHeading()).assertIsDisplayed()
        assertNull(started())

        shadowOf(compose.activity.application).checkActivities(false)
        pressTheLink("Open the original on Instagram")

        compose.onNodeWithText("No app on this phone can open that.").assertDoesNotExist()
        assertNotNull(started())
    }

    @Test
    fun `the vendor row leads to the vendor, and Back leads back to the idea`() {
        open()
        openInspirations()
        openIdea("Lace sleeves")

        compose.onNode(line("Vendor", "Palace on the Hill") and hasClickAction()).performClick()

        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Palace on the Hill") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("More") and isSelectable()).assertIsSelected()

        pressBackButton()

        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Lace sleeves") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
    }

    // ---------------------------------------------------------------- gone, and back

    @Test
    fun `an idea that was removed meanwhile says it is no longer there and leads back`() {
        open()
        openInspirations()
        openIdea("Lace sleeves")
        server.planner(boards = boards, inspirations = listOf(arch))

        compose.onNodeWithContentDescription("Load again").performClick()

        waitFor("This idea is no longer there.")
        compose.onNode(hasText("Inspiration") and isHeading()).assertIsDisplayed()
        compose.onNodeWithText("Lace sleeves").assertDoesNotExist()

        compose.onNode(hasText("Back") and hasClickAction()).performClick()

        waitForTheBoard()
        compose.onNode(line("Garden arch") and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun `Back goes from the idea to the board and from the board to More`() {
        open()
        openInspirations()
        openIdea("Lace sleeves")

        pressBackButton()

        waitForTheBoard()
        compose.onNode(line("Lace sleeves") and hasClickAction()).assertIsDisplayed()

        compose.onNodeWithContentDescription("Back").performClick()

        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("More") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Inspirations") and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun `pressing More on the board or on an idea leads to the list of what More holds`() {
        open()
        openInspirations()
        openIdea("Lace sleeves")

        compose.onNode(hasText("More") and isSelectable()).performClick()

        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("More") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Inspirations") and hasClickAction()).assertIsDisplayed()
        compose.onNodeWithText("Comes from").assertDoesNotExist()
    }

    // ---------------------------------------------------------------- without a connection, at night, big, logged and kept

    @Test
    fun `without a connection the ideas are shown from the last load, with from when it is`() {
        aPhoneThatLoadedEarlier()
        server.plannerUnreachable()
        open()
        waitFor("No connection. This is what was loaded on 18 Nov 2026 at 14:05.")

        openInspirations()

        compose.onNodeWithText("No connection. This is what was loaded on 18 Nov 2026 at 14:05.").assertIsDisplayed()
        compose.onNode(line("Lace sleeves", "Instagram", "Dress", "For Book the venue") and hasClickAction()).assertIsDisplayed()

        openIdea("Lace sleeves")

        compose.onNode(line("Vendor", "Palace on the Hill")).assertIsDisplayed()
        compose.onNodeWithText("Added by Anna").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = PHONE_AT_NIGHT)
    fun `the board and an idea read in the dark as well`() {
        open()
        openInspirations()
        compose.onNode(line("Lace sleeves") and hasClickAction()).assertIsDisplayed()
        compose.picture("inspirations-at-night")

        openIdea("Lace sleeves")

        compose.onNode(line("Board", "Dress")).assertIsDisplayed()
        compose.picture("inspiration-at-night")
    }

    @Test
    fun `with the largest text nothing is cut off`() {
        open(fontScale = 2f)
        goTo("More")
        compose.onNode(hasText("Inspirations") and hasClickAction()).performClick()
        waitForTheBoard()
        compose.onNode(line("Lace sleeves") and hasClickAction()).assertIsDisplayed()
        compose.picture("inspirations-big-letters")
        scrollTo(line("Pictures") and hasClickAction())
        compose.picture("inspirations-big-letters-list")
        compose.onNode(line("Pictures") and hasClickAction()).performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Pictures") and isHeading()).fetchSemanticsNodes().isNotEmpty() }

        compose.onNode(line("Comes from", "Photo")).assertIsDisplayed()
        compose.picture("inspiration-big-letters")
    }

    @Test
    fun `nothing of an inspiration is written to the log`() {
        ShadowLog.clear()
        open()
        openInspirations()
        pressChip("lace")
        openIdea("Lace sleeves")
        pressTheLink("Open the original on Instagram")
        shadowOf(compose.activity.application).checkActivities(true)
        pressTheLink("Open the original on Instagram")
        server.plannerUnreachable()
        compose.onNodeWithContentDescription("Load again").performClick()
        waitFor("No connection. This is what was loaded on 18 Nov 2026 at 14:05.")

        val written = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable?.stackTraceToString().orEmpty()}" }
        for (theirs in listOf(
            "Lace", "sleeves", "Soft", "train", "boho", "instagram", "Cabc123", "palace.example", "Garden arch", "florist", "ZMabc", "tiktok", "bsky",
            "javascript", "Think about it", "Palace on the Hill", "access-1",
        )) {
            assertFalse("the log holds \"$theirs\"", written.contains(theirs, ignoreCase = true))
        }
    }

    @Test
    fun `signing out leaves nothing of the inspirations on the phone`() {
        open()
        val kept = phone.everythingKept()
        for (theirs in listOf("Lace sleeves", "Soft, with a train", "Cabc123", "boho", "Garden arch", "palace.example/ideas", "florist's window", "javascript:alert")) {
            assertTrue("the phone does not keep \"$theirs\" to begin with", kept.contains(theirs))
        }
        goTo("More")
        scrollTo(hasText("Sign out"))

        compose.onNodeWithText("Sign out").performClick()

        waitFor("Sign in to see your plan.")
        val left = phone.everythingKept()
        for (theirs in listOf("Lace sleeves", "Soft, with a train", "Cabc123", "boho", "lace", "Garden arch", "palace.example/ideas", "florist's window", "javascript:alert", "bsky.example")) {
            assertFalse("the phone still keeps \"$theirs\"", left.contains(theirs))
        }
    }
}
