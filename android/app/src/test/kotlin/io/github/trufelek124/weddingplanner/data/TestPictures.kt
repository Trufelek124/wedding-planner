package io.github.trufelek124.weddingplanner.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayOutputStream

/**
 * Pictures a test makes itself: coloured rectangles, compressed the way a camera's would be. Nothing binary is kept in the
 * repository and no real photograph is ever used. Needs `@GraphicsMode(GraphicsMode.Mode.NATIVE)` to draw and encode.
 */
object TestPictures {

    const val RED = 0xFFD32F2F.toInt()
    const val BLUE = 0xFF1976D2.toInt()
    const val GREEN = 0xFF388E3C.toInt()
    const val YELLOW = 0xFFFBC02D.toInt()

    /** A picture with a left half and a right half of two colours, and a stripe of a third across the middle, so that a stretched or cut one shows. */
    fun bitmap(width: Int, height: Int, left: Int = RED, right: Int = BLUE, stripe: Int = YELLOW): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        paint.color = left
        canvas.drawRect(0f, 0f, width / 2f, height.toFloat(), paint)
        paint.color = right
        canvas.drawRect(width / 2f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.color = stripe
        canvas.drawRect(0f, height * 0.4f, width.toFloat(), height * 0.6f, paint)
        return bitmap
    }

    fun jpeg(width: Int, height: Int, left: Int = RED, right: Int = BLUE, stripe: Int = YELLOW): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap(width, height, left, right, stripe).compress(Bitmap.CompressFormat.JPEG, 90, out)
        return out.toByteArray()
    }

    fun png(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap(width, height, Color.BLACK, Color.WHITE, Color.GRAY).compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }
}
