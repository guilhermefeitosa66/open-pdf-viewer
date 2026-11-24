package io.github.guilhermefeitosa66.openpdfviewer.viewer

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class PageModeTest {
    /** Applies the 4x5 matrix to an opaque RGB pixel (0..255). */
    private fun apply(m: FloatArray, r: Float, g: Float, b: Float): FloatArray =
        FloatArray(3) { row -> m[row * 5] * r + m[row * 5 + 1] * g + m[row * 5 + 2] * b + m[row * 5 + 4] }

    private val ink = Color(0xFF3B2E22)
    private val paper = Color(0xFFF4E9D3)
    private val m = PageMode.rampMatrix(ink, paper).values

    @Test
    fun whiteBecomesPaper() {
        val out = apply(m, 255f, 255f, 255f)
        assertEquals(paper.red * 255, out[0], 0.5f)
        assertEquals(paper.green * 255, out[1], 0.5f)
        assertEquals(paper.blue * 255, out[2], 0.5f)
    }

    @Test
    fun blackBecomesInk() {
        val out = apply(m, 0f, 0f, 0f)
        assertEquals(ink.red * 255, out[0], 0.5f)
        assertEquals(ink.blue * 255, out[2], 0.5f)
    }

    @Test
    fun unknownNameIsNormal() = assertEquals(PageMode.NORMAL, PageMode.fromName("bogus"))

    @Test
    fun nightHasNoMultiply() = assertEquals(false, PageMode.NIGHT.multiplyHighlights)
}
