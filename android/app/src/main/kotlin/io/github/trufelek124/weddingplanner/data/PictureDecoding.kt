package io.github.trufelek124.weddingplanner.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File

/** What reading a picture file came to. */
sealed interface Decoded {
    class Ok(val bitmap: Bitmap) : Decoded

    /** The file is not a picture the phone can read. */
    data object NotAPicture : Decoded

    /** The phone has no room for it just now; the file may be fine. */
    data object NoRoom : Decoded
}

/**
 * Reads picture files into memory no larger than the place they are shown in needs: the size is read first, then the
 * picture is decoded at a fraction of its size. A photograph of twelve million pixels takes forty-eight million bytes
 * whole; shown on a card it needs a few hundred thousand.
 *
 * This is slow work for the disk and the processor: never call it on the main thread.
 */
object PictureDecoding {

    /** Nothing is decoded to more than this many pixels in either direction, whatever place is asked for. */
    private const val MOST_SIDE = 4096

    /**
     * The fraction, as a power of two, to decode a picture of [width] × [height] at so that it still covers a place of
     * [wantedWidth] × [wantedHeight] pixels: the largest one that leaves both sides at least as long as the place's.
     */
    fun sampleSize(width: Int, height: Int, wantedWidth: Int, wantedHeight: Int): Int {
        val wantedW = wantedWidth.coerceIn(1, MOST_SIDE)
        val wantedH = wantedHeight.coerceIn(1, MOST_SIDE)
        var sample = 1
        while (width / (sample * 2) >= wantedW && height / (sample * 2) >= wantedH) {
            sample *= 2
        }
        return sample
    }

    /** Decodes the picture in [file] for a place of [wantedWidth] × [wantedHeight] pixels, turned the way it was taken. */
    fun decode(file: File, wantedWidth: Int, wantedHeight: Int): Decoded {
        val orientation = orientationOf(file)
        val turned = orientation in TURNED_SIDEWAYS
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return Decoded.NotAPicture
            }
            // The place is told as it is seen; the file's own width and height are the other way round for a sideways one.
            val sample = if (turned) {
                sampleSize(bounds.outWidth, bounds.outHeight, wantedHeight, wantedWidth)
            } else {
                sampleSize(bounds.outWidth, bounds.outHeight, wantedWidth, wantedHeight)
            }
            val read = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return Decoded.NotAPicture
            return Decoded.Ok(turnedUpright(read, orientation))
        } catch (full: OutOfMemoryError) {
            return Decoded.NoRoom
        }
    }

    private fun orientationOf(file: File): Int =
        try {
            ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (unreadable: Exception) {
            // Not a file with such a note (a PNG), or not a picture at all, which the decoding says.
            ExifInterface.ORIENTATION_NORMAL
        }

    private fun turnedUpright(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private val TURNED_SIDEWAYS = setOf(
        ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_ROTATE_90,
        ExifInterface.ORIENTATION_TRANSVERSE, ExifInterface.ORIENTATION_ROTATE_270,
    )
}
