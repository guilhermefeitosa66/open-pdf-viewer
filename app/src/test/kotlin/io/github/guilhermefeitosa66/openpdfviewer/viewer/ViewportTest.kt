package io.github.guilhermefeitosa66.openpdfviewer.viewer

import io.github.guilhermefeitosa66.openpdfviewer.pdfium.PageSize
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewportTest {
    private fun viewport() = Viewport(List(20) { PageSize(612f, 792f) }).apply {
        setSize(1080f, 2400f, padTop = 200f, padBottom = 150f, gap = 20f, margin = 20f)
    }

    @Test
    fun restoreReturnsToTheSameAnchor() {
        val vp = viewport()
        vp.restore(zoom = 2f, page = 7, offset = 0.4f, xFraction = 0.25f)
        val (page, offset) = vp.topAnchor()
        assertEquals(7, page)
        assertEquals(0.4f, offset, 0.001f)
        assertEquals(2f, vp.zoom, 0.0001f)
        assertEquals(0.25f, vp.scrollXFraction, 0.001f)
    }

    @Test
    fun zoomIsClamped() {
        val vp = viewport()
        vp.restore(zoom = 50f, page = 0, offset = 0f, xFraction = 0f)
        assertEquals(Viewport.MAX_ZOOM, vp.zoom, 0.0001f)
    }

    @Test
    fun scrollToPagePutsPageUnderTheBar() {
        val vp = viewport()
        vp.scrollToPage(5)
        assertEquals(5, vp.topAnchor().first)
    }
}
