package io.github.guilhermefeitosa66.openpdfviewer.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DocumentDetectorTest {
    /** A bright quadrilateral (a tilted sheet) on a dark background. */
    private fun scene(w: Int, h: Int, quad: List<Pair<Float, Float>>): IntArray {
        fun inside(x: Float, y: Float): Boolean {
            var c = false
            var j = quad.size - 1
            for (i in quad.indices) {
                val (xi, yi) = quad[i]; val (xj, yj) = quad[j]
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c
                j = i
            }
            return c
        }
        return IntArray(w * h) { p -> if (inside(p % w + 0.5f, p / w + 0.5f)) 230 else 40 }
    }

    @Test fun findsTiltedSheet() {
        val q = listOf(30f to 20f, 170f to 35f, 160f to 260f, 20f to 240f)
        val c = DocumentDetector.detect(scene(200, 300, q), 200, 300)
        assertNotNull(c)
        val expected = floatArrayOf(30f / 200, 20f / 300, 170f / 200, 35f / 300, 160f / 200, 260f / 300, 20f / 200, 240f / 300)
        for (i in 0 until 8) assertEquals("coord $i", expected[i], c!![i], 0.03f)
    }

    @Test fun nothingOnPlainImage() = assertNull(DocumentDetector.detect(IntArray(100 * 100) { 128 }, 100, 100))

    @Test fun otsuSplitsTwoLevels() {
        val t = DocumentDetector.otsu(IntArray(1000) { if (it < 500) 40 else 220 })
        assert(t in 40 until 220)
    }

    @Test fun adaptiveThresholdIgnoresShadow() {
        // Left half in shadow (gray 100), right half lit (gray 220), a dark line in each half.
        val w = 120; val h = 40
        val g = IntArray(w * h) { p -> val x = p % w; val base = if (x < 60) 100 else 220; if (x == 30 || x == 90) base - 60 else base }
        val out = DocumentDetector.adaptiveThreshold(g, w, h, radius = 8)
        assertEquals(255, out[20 * w + 10]) // shadowed paper stays white
        assertEquals(0, out[20 * w + 30]) // the line in the shadow is black
        assertEquals(0, out[20 * w + 90])
    }

    @Test fun adaptiveThresholdKeepsLargeDarkAreasFilled() {
        val w = 200; val h = 100
        // White paper with a 120 x 40 black bar: its middle is far from any light pixel.
        val g = IntArray(w * h) { p -> val x = p % w; val y = p / w; if (x in 40 until 160 && y in 30 until 70) 25 else 235 }
        val out = DocumentDetector.adaptiveThreshold(g, w, h, radius = 8)
        assertEquals(0, out[50 * w + 100])
        assertEquals(255, out[10 * w + 10])
    }

    @Test fun outputSizeFromEdges() {
        val (w, h) = DocumentDetector.outputSize(floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), 300, 400)
        assertEquals(300, w); assertEquals(400, h)
    }
}
