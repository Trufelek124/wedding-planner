package io.github.trufelek124.weddingplanner.planner

import io.github.trufelek124.weddingplanner.data.Board
import io.github.trufelek124.weddingplanner.data.Inspiration
import io.github.trufelek124.weddingplanner.data.InspirationSource
import io.github.trufelek124.weddingplanner.data.Picture
import io.github.trufelek124.weddingplanner.data.WaitingShare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The inspirations read on the phone as they do on the web's board. Everything here is made up. */
@RunWith(RobolectricTestRunner::class)
class InspirationWordsTest {

    private fun idea(
        id: String = "i1",
        url: String? = null,
        source: InspirationSource = if (url == null) InspirationSource.PHOTO else InspirationSource.INSTAGRAM,
        title: String = "",
        previewTitle: String = "",
        previewPending: Boolean = false,
        note: String = "",
        tags: List<String> = emptyList(),
        boardId: String? = null,
        pictures: Int = 0,
    ) = Inspiration(
        id = id, url = url, source = source, title = title, previewTitle = previewTitle, previewPending = previewPending, note = note, tags = tags,
        boardId = boardId, planItemId = null, vendorId = null, addedById = null, pictures = List(pictures) { Picture("p$it", 800, 600, false) },
    )

    private val instagram = "https://www.instagram.com/p/Cabc123/"

    // ---------------------------------------------------------------- names

    @Test
    fun `an idea is called what the couple called it, else what its page is called, else where it comes from, else what it is`() {
        assertEquals("Lace sleeves", inspirationName(idea(url = instagram, title = "Lace sleeves", previewTitle = "A page")))
        assertEquals("A page", inspirationName(idea(url = instagram, previewTitle = "A page")))
        assertEquals("From Instagram", inspirationName(idea(url = instagram)))
        assertEquals("From palace.example", inspirationName(idea(url = "https://www.palace.example/weddings", source = InspirationSource.WEB)))
        assertEquals("An idea", inspirationName(idea()))
        assertEquals("A picture", inspirationName(idea(pictures = 1)))
        assertEquals("Pictures", inspirationName(idea(pictures = 3)))
        // A picture or two does not name a link.
        assertEquals("From Instagram", inspirationName(idea(url = instagram, pictures = 2)))
    }

    @Test
    fun `a source is a name, a site for a link elsewhere on the web, Web where that cannot be read, and Other where this app does not know it`() {
        assertEquals("Instagram", sourceName(InspirationSource.INSTAGRAM, instagram))
        assertEquals("TikTok", sourceName(InspirationSource.TIKTOK, "https://vm.tiktok.com/ZMabc/"))
        assertEquals("Pinterest", sourceName(InspirationSource.PINTEREST, "https://pin.it/abc"))
        assertEquals("Photo", sourceName(InspirationSource.PHOTO, null))
        assertEquals("palace.example", sourceName(InspirationSource.WEB, "https://www.palace.example/weddings?x=1"))
        assertEquals("blog.palace.example", sourceName(InspirationSource.WEB, "http://blog.palace.example"))
        assertEquals("Web", sourceName(InspirationSource.WEB, null))
        assertEquals("Web", sourceName(InspirationSource.WEB, "javascript:alert(1)"))
        assertEquals("Web", sourceName(InspirationSource.WEB, "not a link"))
        assertEquals("Other", sourceName(InspirationSource.OTHER, "https://www.palace.example"))
    }

    @Test
    fun `where a link that waits to be sent comes from is worked out as the server does it`() {
        assertEquals(InspirationSource.INSTAGRAM, sourceOfLink("https://www.instagram.com/p/Cabc123/"))
        assertEquals(InspirationSource.INSTAGRAM, sourceOfLink("https://instagr.am/p/Cabc123/"))
        assertEquals(InspirationSource.TIKTOK, sourceOfLink("https://vm.tiktok.com/ZMabc/"))
        assertEquals(InspirationSource.PINTEREST, sourceOfLink("https://pl.pinterest.com/pin/1/"))
        assertEquals(InspirationSource.PINTEREST, sourceOfLink("https://www.pinterest.co.uk/pin/1/"))
        assertEquals(InspirationSource.PINTEREST, sourceOfLink("https://pin.it/abc"))
        assertEquals(InspirationSource.WEB, sourceOfLink("https://notinstagram.com/p/1"))
        assertEquals(InspirationSource.WEB, sourceOfLink("https://pinterest.example.com/pin/1/"))
        assertEquals(InspirationSource.WEB, sourceOfLink("https://www.palace.example"))
    }

    @Test
    fun `pictures are counted in words`() {
        assertEquals("1 picture", picturesInWords(1))
        assertEquals("3 pictures", picturesInWords(3))
        assertNull(morePicturesInWords(0))
        assertNull(morePicturesInWords(-1))
        assertEquals("1 more picture", morePicturesInWords(1))
        assertEquals("2 more pictures", morePicturesInWords(2))
        assertEquals("For Book the venue", forPlanItemInWords("Book the venue"))
    }

    // ---------------------------------------------------------------- the card

    @Test
    fun `what stands in the place of the picture says what is there`() {
        assertEquals(listOf("Instagram"), faceInWords(idea(url = instagram)))
        assertEquals(listOf("palace.example"), faceInWords(idea(url = "https://palace.example", source = InspirationSource.WEB)))
        assertEquals(listOf("No picture"), faceInWords(idea()))
        assertEquals(listOf("1 picture"), faceInWords(idea(pictures = 1)))
        assertEquals(listOf("3 pictures"), faceInWords(idea(pictures = 3)))
        // The pictures a link has brought are pictures too.
        assertEquals(listOf("2 pictures"), faceInWords(idea(url = instagram, pictures = 2)))
        assertEquals(listOf("Instagram", "Fetching a preview\u2026"), faceInWords(idea(url = instagram, previewPending = true)))
        assertEquals(listOf("No picture", "Fetching a preview\u2026"), faceInWords(idea(previewPending = true)))
    }

    @Test
    fun `the facts of a card are the source of a link, the board, the plan item and the pictures after the first`() {
        assertEquals(
            listOf("Instagram", "Dress", "For Book the venue", "2 more pictures"),
            inspirationFacts(idea(url = instagram, pictures = 3), "Dress", "Book the venue"),
        )
        assertEquals(listOf("1 more picture"), inspirationFacts(idea(pictures = 2), null, null))
        // One picture is the one the card shows: no more are said.
        assertEquals(emptyList<String>(), inspirationFacts(idea(pictures = 1), null, null))
        assertEquals(emptyList<String>(), inspirationFacts(idea(), null, null))
        assertEquals(listOf("Dress"), inspirationFacts(idea(), "Dress", null))
    }

    @Test
    fun `a card is one statement, with what is said twice said once`() {
        val full = idea(url = instagram, title = "Lace sleeves", note = "Ask Anna about the veil", tags = listOf("boho", "lace"), pictures = 2)

        assertEquals(
            "Lace sleeves. 2 pictures. Instagram. Dress. For Book the venue. 1 more picture. Ask Anna about the veil. Tags: boho, lace",
            inspirationInWords(full, "Dress", "Book the venue"),
        )
        assertEquals("From Instagram. Instagram", inspirationInWords(idea(url = instagram), null, null))
        assertEquals("From Instagram. Instagram. Fetching a preview\u2026", inspirationInWords(idea(url = instagram, previewPending = true), null, null))
        assertEquals("An idea. No picture", inspirationInWords(idea(), null, null))
        assertEquals("Show only \u201cboho\u201d", showOnlyTagInWords("boho"))
    }

    @Test
    fun `the original is opened on the source`() {
        assertEquals("Open the original on Instagram", openOriginalInWords(idea(url = instagram)))
        assertEquals("Open the original on palace.example", openOriginalInWords(idea(url = "https://www.palace.example/x", source = InspirationSource.WEB)))
        assertEquals("Added by Anna", addedByInWords("Anna"))
    }

    // ---------------------------------------------------------------- what waits to be sent

    @Test
    fun `a share that waits is told by what is known of it`() {
        val link = WaitingShare("s1", "https://vm.tiktok.com/ZMabc/", 0, null)
        val pictures = WaitingShare("s2", null, 2, null)
        val one = WaitingShare("s3", null, 1, null)
        val nothing = WaitingShare("s4", null, 0, null)

        assertEquals("From TikTok", shareName(link))
        assertEquals(listOf("TikTok"), shareFaceInWords(link))
        assertEquals(listOf("TikTok"), shareFacts(link))
        assertEquals("From TikTok. Waiting to be sent. TikTok", shareInWords(link))
        assertEquals("Pictures", shareName(pictures))
        assertEquals(listOf("2 pictures"), shareFaceInWords(pictures))
        assertEquals(emptyList<String>(), shareFacts(pictures))
        assertEquals("Pictures. Waiting to be sent. 2 pictures", shareInWords(pictures))
        assertEquals("A picture", shareName(one))
        assertEquals("An idea", shareName(nothing))
        assertEquals(listOf("No picture"), shareFaceInWords(nothing))
        assertEquals("From palace.example", shareName(WaitingShare("s5", "https://palace.example/x", 0, null)))
    }

    @Test
    fun `a share's link is not printed`() {
        assertFalse(WaitingShare("s1", "https://secret.example/x", 0, null, "b1").toString().contains("secret"))
    }

    // ---------------------------------------------------------------- More

    @Test
    fun `More tells how many ideas are kept and how many wait`() {
        assertEquals("Nothing kept yet", inspirationsSummary(emptyList(), 0))
        assertEquals("1 inspiration", inspirationsSummary(listOf(idea()), 0))
        assertEquals("12 inspirations", inspirationsSummary(List(12) { idea("i$it") }, 0))
        assertEquals("1 inspiration, 2 waiting to be sent", inspirationsSummary(listOf(idea()), 2))
        assertEquals("Nothing kept yet, 1 waiting to be sent", inspirationsSummary(emptyList(), 1))
    }

    // ---------------------------------------------------------------- narrowing down

    private val dress = Board("b1", "Dress")
    private val flowers = Board("b2", "Flowers")

    @Test
    fun `the boards offered are those that exist, then no board where an idea is on none`() {
        assertEquals(listOf("b1", "b2", "none"), boardsOffered(listOf(dress, flowers), listOf("b1", null), null))
        assertEquals(listOf("b1", "b2"), boardsOffered(listOf(dress, flowers), listOf("b1", "b2"), null))
        assertEquals(listOf("b1", "b2"), boardsOffered(listOf(dress, flowers), emptyList(), null))
        // No board stays offered once it was chosen and the last idea on none is gone.
        assertEquals(listOf("b1", "b2", "none"), boardsOffered(listOf(dress, flowers), listOf("b1"), "none"))
        assertEquals("Dress", boardChipLabel("b1", listOf(dress, flowers)))
        assertEquals("On no board", boardChipLabel("none", listOf(dress, flowers)))
    }

    @Test
    fun `a board that was chosen and is gone since is not chosen any more`() {
        assertEquals("b1", boardChosen("b1", listOf(dress)))
        assertEquals("none", boardChosen("none", emptyList()))
        assertNull(boardChosen("", listOf(dress)))
        assertNull(boardChosen("b9", listOf(dress)))
    }

    @Test
    fun `the tags offered are those that occur, the most used first and then by name, and the chosen one stays`() {
        val ideas = listOf(
            idea("i1", tags = listOf("lace", "boho")),
            idea("i2", tags = listOf("boho", "Autumn")),
            idea("i3", tags = listOf("boho", "boho", "zinnia", "lace")),
            idea("i4"),
        )

        assertEquals(listOf("boho", "lace", "Autumn", "zinnia"), tagsOffered(ideas))
        assertEquals(listOf("boho", "lace", "Autumn", "zinnia", "silk"), tagsOffered(ideas, "silk"))
        assertEquals(listOf("boho", "lace", "Autumn", "zinnia"), tagsOffered(ideas, "boho"))
        assertEquals(emptyList<String>(), tagsOffered(listOf(idea())))
    }

    @Test
    fun `the board shows one board or all, one tag or any, and both together`() {
        val ideas = listOf(
            idea("i1", boardId = "b1", tags = listOf("boho")),
            idea("i2", boardId = "b2", tags = listOf("boho", "lace")),
            idea("i3", boardId = null, tags = listOf("lace")),
        )

        assertEquals(listOf("i1", "i2", "i3"), ideasShown(ideas, null, null).map { it.id })
        assertEquals(listOf("i1"), ideasShown(ideas, "b1", null).map { it.id })
        assertEquals(listOf("i3"), ideasShown(ideas, "none", null).map { it.id })
        assertEquals(listOf("i1", "i2"), ideasShown(ideas, null, "boho").map { it.id })
        assertEquals(listOf("i2"), ideasShown(ideas, "b2", "boho").map { it.id })
        assertEquals(emptyList<String>(), ideasShown(ideas, "b1", "lace").map { it.id })
    }

    @Test
    fun `what waits to be sent is narrowed by its board, and a tag leaves it out`() {
        val shares = listOf(WaitingShare("s1", null, 1, null, "b1"), WaitingShare("s2", null, 1, null, null))

        assertEquals(listOf("s1", "s2"), sharesShown(shares, null, null).map { it.id })
        assertEquals(listOf("s1"), sharesShown(shares, "b1", null).map { it.id })
        assertEquals(listOf("s2"), sharesShown(shares, "none", null).map { it.id })
        assertEquals(emptyList<String>(), sharesShown(shares, null, "boho").map { it.id })
    }
}
