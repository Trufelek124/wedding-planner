package io.github.trufelek124.weddingplanner.share

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** What other apps hand over, read as they really send it. The addresses are made up. */
@RunWith(RobolectricTestRunner::class)
class SharedTest {

    private fun text(shared: String) = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shared)

    @Test
    fun `Instagram shares the bare address of the post`() {
        val shared = sharedOf(text("https://www.instagram.com/p/Cabc123/?igsh=MWx5"))

        assertEquals(Shared("https://www.instagram.com/p/Cabc123/?igsh=MWx5", "", emptyList()), shared)
        assertFalse(shared.empty)
    }

    @Test
    fun `TikTok puts words around the address - only the address is kept`() {
        val shared = sharedOf(text("Check out this video! https://vm.tiktok.com/ZMabc12/ This post is shared via TikTok."))

        assertEquals("https://vm.tiktok.com/ZMabc12/", shared.link)
        assertEquals("", shared.text)
    }

    @Test
    fun `what ends the sentence after an address is not part of it`() {
        assertEquals("https://example.com/dress", linkIn("Look at this: https://example.com/dress."))
        assertEquals("https://example.com/dress", linkIn("(https://example.com/dress)"))
        assertEquals("https://example.com/a?b=c", linkIn("https://example.com/a?b=c, and more"))
        assertEquals("HTTPS://EXAMPLE.COM/X", linkIn("see HTTPS://EXAMPLE.COM/X"))
        assertEquals("http://example.com/first", linkIn("http://example.com/first and https://example.com/second"))
        assertNull(linkIn("no address here, not even example.com"))
        assertNull(linkIn("ftp://example.com/file"))
    }

    @Test
    fun `text without an address becomes the note`() {
        val shared = sharedOf(text("  Peonies in June, ask the florist  "))

        assertNull(shared.link)
        assertEquals("Peonies in June, ask the florist", shared.text)
        assertFalse(shared.empty)
    }

    @Test
    fun `one picture, and several`() {
        val one = Uri.parse("content://gallery/1")
        val two = Uri.parse("content://gallery/2")

        assertEquals(listOf(one), sharedOf(Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, one)).pictures)
        assertEquals(
            listOf(one, two),
            sharedOf(Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(one, two))).pictures,
        )
    }

    @Test
    fun `what is neither a link, a text nor a picture is nothing the planner can keep`() {
        assertTrue(sharedOf(null).empty)
        assertTrue(sharedOf(Intent(Intent.ACTION_MAIN)).empty)
        assertTrue(sharedOf(text("   ")).empty)
        assertTrue(sharedOf(Intent(Intent.ACTION_SEND).setType("image/png")).empty)
        assertTrue(sharedOf(Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, Uri.parse("content://gallery/9"))).empty)
    }
}
