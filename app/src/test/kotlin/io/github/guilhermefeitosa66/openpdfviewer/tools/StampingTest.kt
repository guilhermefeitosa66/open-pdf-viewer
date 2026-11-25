package io.github.guilhermefeitosa66.openpdfviewer.tools

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class StampingTest {
    // Applies the matrix to a text-space point.
    private fun apply(m: FloatArray, x: Float, y: Float) = floatArrayOf(m[0] * x + m[2] * y + m[4], m[1] * x + m[3] * y + m[5])

    /** Where page point (px, py) shows up on screen (bottom-left origin) for a clockwise rotation. */
    private fun seen(rotation: Int, w: Float, h: Float, px: Float, py: Float) = when (rotation) {
        90 -> floatArrayOf(py, w - px)
        180 -> floatArrayOf(w - px, h - py)
        270 -> floatArrayOf(h - py, px)
        else -> floatArrayOf(px, py)
    }

    @Test fun originLandsWhereAsked() {
        for (r in listOf(0, 90, 180, 270)) {
            val m = Stamping.uprightMatrix(r, 0f, 0f, 600f, 800f, 50f, 30f)
            val p = apply(m, 0f, 0f)
            assertArrayEquals("rotation $r", floatArrayOf(50f, 30f), seen(r, 600f, 800f, p[0], p[1]), 1e-3f)
            // Text runs to the right as seen.
            val q = apply(m, 10f, 0f)
            assertArrayEquals("direction $r", floatArrayOf(60f, 30f), seen(r, 600f, 800f, q[0], q[1]), 1e-3f)
        }
    }

    @Test fun offsetBox() {
        val m = Stamping.uprightMatrix(0, 20f, 40f, 500f, 700f, 10f, 10f)
        assertArrayEquals(floatArrayOf(30f, 50f), apply(m, 0f, 0f), 1e-3f)
    }
}
