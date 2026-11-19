package io.github.trufelek124.weddingplanner.planner

import org.junit.Assert.assertEquals
import org.junit.Test

/** The shape a picture has on the board, worked out without a screen. */
class BoardShapeTest {

    private val four3 = 4f / 3f

    @Test
    fun `a picture inside the bounds keeps its own shape`() {
        assertEquals(0.8f, boardShape(4, 5), 0.001f)
        assertEquals(1.5f, boardShape(3, 2), 0.001f)
        assertEquals(1f, boardShape(500, 500), 0.001f)
    }

    @Test
    fun `a tall picture is bounded to 4 by 5 and a panorama to 16 by 9`() {
        assertEquals(0.8f, boardShape(9, 16), 0.001f)
        assertEquals(16f / 9f, boardShape(3, 1), 0.001f)
    }

    @Test
    fun `where the server did not say the size, or said nonsense, it is 4 by 3`() {
        assertEquals(four3, boardShape(null, null), 0.001f)
        assertEquals(four3, boardShape(800, null), 0.001f)
        assertEquals(four3, boardShape(0, 600), 0.001f)
        assertEquals(four3, boardShape(800, -1), 0.001f)
    }
}
