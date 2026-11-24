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

    private fun paged(o: LayoutOptions, pages: Int = 7) = Viewport(List(pages) { PageSize(612f, 792f) }).apply {
        setLayout(o)
        setSize(2400f, 1080f, padTop = 100f, padBottom = 100f, gap = 20f, margin = 20f)
    }

    @Test
    fun pagedShowsOnePagePerScreen() {
        val vp = paged(LayoutOptions(paged = true))
        vp.scrollToPage(3)
        assertEquals(3, vp.currentPage)
        assertEquals(3..3, vp.visiblePages())
        assertEquals(7 * 2400f, vp.contentWidth, 0.01f)
    }

    @Test
    fun dualWithCoverPairsPagesAfterTheFirst() {
        val vp = paged(LayoutOptions(paged = true, dual = true, coverAlone = true))
        vp.scrollToPage(0)
        assertEquals(0..0, vp.visiblePages())
        vp.scrollToPage(2)
        assertEquals(1..2, vp.visiblePages()) // spread 1-2
        assertEquals(1, vp.currentPage)
        assertEquals(4 * 2400f, vp.contentWidth, 0.01f) // [0] [1,2] [3,4] [5,6]
    }

    @Test
    fun rightToLeftPutsFirstPageOnTheRight() {
        val vp = paged(LayoutOptions(paged = true, rtl = true))
        vp.scrollToPage(0)
        assertEquals(6 * 2400f, vp.scrollX, 0.01f)
        vp.scrollToPage(6)
        assertEquals(0f, vp.scrollX, 0.01f)
    }

    @Test
    fun dualRtlPutsReadingFirstPageOnTheRightOfTheSpread() {
        val vp = paged(LayoutOptions(paged = true, dual = true, coverAlone = false, rtl = true), pages = 2)
        vp.scrollToPage(0)
        assert(vp.pageLeft(0) > vp.pageLeft(1))
    }

    @Test
    fun snapGoesToNextSlotOnFastFling() {
        val vp = paged(LayoutOptions(paged = true))
        vp.scrollToPage(2)
        vp.scrollBy(300f, 0f)
        assertEquals(3 * 2400f, vp.snapTarget(velocity = 2000f, threshold = 500f), 0.01f)
        assertEquals(2 * 2400f, vp.snapTarget(velocity = 0f, threshold = 500f), 0.01f)
        assertEquals(2 * 2400f, vp.snapTarget(velocity = -2000f, threshold = 500f), 0.01f)
    }

    @Test
    fun switchingLayoutKeepsThePage() {
        val vp = viewport()
        vp.scrollToPage(9)
        vp.setLayout(LayoutOptions(paged = true))
        assertEquals(9, vp.currentPage)
        vp.setLayout(LayoutOptions(paged = false))
        assertEquals(9, vp.topAnchor().first)
    }
}
