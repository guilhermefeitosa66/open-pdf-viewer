package io.github.guilhermefeitosa66.openpdfviewer.viewer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarginsTest {
    private fun page(w: Int, h: Int, ink: (Int, Int) -> Boolean) =
        IntArray(w * h) { i -> if (ink(i % w, i / w)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }

    @Test fun blank() = assertNull(Margins.contentBox(page(10, 10) { _, _ -> false }, 10, 10))

    @Test fun box() {
        val px = page(100, 200) { x, y -> x in 10..89 && y in 20..179 }
        assertArrayEquals(floatArrayOf(0.1f, 0.1f, 0.9f, 0.9f), Margins.contentBox(px, 100, 200, pad = 0f), 1e-6f)
    }

    @Test fun padClamped() {
        val px = page(10, 10) { _, _ -> true }
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 1f), Margins.contentBox(px, 10, 10), 1e-6f)
    }

    @Test fun union() = assertArrayEquals(
        floatArrayOf(0.1f, 0.05f, 0.9f, 0.8f),
        Margins.union(listOf(floatArrayOf(0.1f, 0.2f, 0.5f, 0.8f), floatArrayOf(0.3f, 0.05f, 0.9f, 0.6f))),
        1e-6f,
    )
}
