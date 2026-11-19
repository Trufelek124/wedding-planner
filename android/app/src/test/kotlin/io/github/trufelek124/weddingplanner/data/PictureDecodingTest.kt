package io.github.trufelek124.weddingplanner.data

import android.graphics.Color
import android.media.ExifInterface
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Reading a picture into no more memory than the place it is shown in needs. The pictures are made by the test. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PictureDecodingTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun file(bytes: ByteArray, name: String = "picture"): File = File(folder.root, name).also { it.writeBytes(bytes) }

    @Test
    fun `the fraction is the largest power of two that still covers the place`() {
        assertEquals(1, PictureDecoding.sampleSize(800, 600, 800, 600))
        assertEquals(1, PictureDecoding.sampleSize(800, 600, 500, 400))
        assertEquals(2, PictureDecoding.sampleSize(800, 600, 400, 300))
        assertEquals(8, PictureDecoding.sampleSize(4000, 3000, 360, 168))
        // Both sides have to stay long enough: a tall place in a wide picture keeps more of it.
        assertEquals(4, PictureDecoding.sampleSize(4000, 3000, 100, 700))
        // A picture smaller than the place is not made smaller, and a place of no size is a place of one pixel.
        assertEquals(1, PictureDecoding.sampleSize(100, 80, 720, 540))
        assertEquals(4096, PictureDecoding.sampleSize(4096, 4096, 0, 0))
    }

    @Test
    fun `a large picture is read at a fraction of its size, not whole`() {
        val picture = file(TestPictures.jpeg(2000, 1500))

        val read = (PictureDecoding.decode(picture, 200, 150) as Decoded.Ok).bitmap

        // 2000 x 1500 would be twelve million bytes; for a place of 200 x 150 it is read at an eighth of its sides.
        assertEquals(250, read.width)
        assertEquals(188, read.height)
        assertTrue(read.byteCount < 2000 * 1500 * 4 / 50)
    }

    @Test
    fun `what is read is the picture, with its colours where they were`() {
        val picture = file(TestPictures.jpeg(400, 300))

        val read = (PictureDecoding.decode(picture, 400, 300) as Decoded.Ok).bitmap

        assertEquals(400, read.width)
        val left = read.getPixel(read.width / 4, read.height / 10)
        val right = read.getPixel(read.width * 3 / 4, read.height / 10)
        assertTrue("left is red: ${Integer.toHexString(left)}", Color.red(left) > 180 && Color.blue(left) < 90)
        assertTrue("right is blue: ${Integer.toHexString(right)}", Color.blue(right) > 160 && Color.red(right) < 90)
    }

    @Test
    fun `a file that is no picture is refused, and so is an empty one`() {
        assertEquals(Decoded.NotAPicture, PictureDecoding.decode(file("<html>not a picture</html>".toByteArray()), 100, 100))
        assertEquals(Decoded.NotAPicture, PictureDecoding.decode(file(ByteArray(0), "empty"), 100, 100))
        assertEquals(Decoded.NotAPicture, PictureDecoding.decode(File(folder.root, "missing"), 100, 100))
    }

    @Test
    fun `a photograph that was taken sideways is turned upright`() {
        val picture = file(TestPictures.jpeg(400, 200))
        ExifInterface(picture.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }

        val read = (PictureDecoding.decode(picture, 200, 400) as Decoded.Ok).bitmap

        assertEquals(200, read.width)
        assertEquals(400, read.height)
    }

    @Test
    fun `a PNG is read as well`() {
        val read = (PictureDecoding.decode(file(TestPictures.png(64, 48)), 64, 48) as Decoded.Ok).bitmap

        assertEquals(64, read.width)
    }
}
