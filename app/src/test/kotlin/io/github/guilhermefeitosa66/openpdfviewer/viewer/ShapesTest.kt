package io.github.guilhermefeitosa66.openpdfviewer.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class ShapesTest {
    @Test
    fun arrowHeadEndsAtTheTip() {
        val paths = Shapes.arrow(0.1f, 0.5f, 0.9f, 0.5f, 600f, 800f, 12f)
        assertEquals(2, paths.size)
        assertEquals(0.9f to 0.5f, paths[1][1])
        // Wings sit behind the tip, symmetric around the shaft.
        assertEquals(paths[1][0].first, paths[1][2].first, 1e-4f)
        assertEquals(0.5f - paths[1][0].second, paths[1][2].second - 0.5f, 1e-4f)
    }

    @Test
    fun polygonCloses() {
        val p = Shapes.polygon(listOf(0f to 0f, 1f to 0f, 1f to 1f))
        assertEquals(4, p.single().size)
        assertEquals(p.single().first(), p.single().last())
    }

    @Test
    fun marksKeepTheirSizeInPoints() {
        // On a wide page, the same mark spans fewer normalized units across.
        val narrow = Shapes.crossMark(0.5f, 0.5f, 20f, 200f, 800f).first()
        val wide = Shapes.crossMark(0.5f, 0.5f, 20f, 800f, 800f).first()
        assertEquals(4f * (wide[1].first - wide[0].first), narrow[1].first - narrow[0].first, 1e-4f)
    }

    @Test
    fun dotIsCentered() {
        val d = Shapes.dot(0.5f, 0.25f, 10f, 500f, 1000f)
        assertEquals(0.5f, (d[0] + d[2]) / 2, 1e-5f)
        assertEquals(0.25f, (d[1] + d[3]) / 2, 1e-5f)
    }
}
