package io.github.guilhermefeitosa66.openpdfviewer.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.guilhermefeitosa66.openpdfviewer.pdfium.PageSize

/** A point on a page, in normalized page coordinates (0..1, origin top-left). */
data class PageHit(val page: Int, val x: Float, val y: Float)

/**
 * Geometry of the continuous vertical layout. At zoom 1 the widest page fits the width; every
 * page, gap and margin scales with the zoom. Content coordinates start at the top-left of the
 * scrolled content; screen = content - scroll.
 */
class Viewport(private val sizes: List<PageSize>) {
    var width by mutableFloatStateOf(0f)
        private set
    var height by mutableFloatStateOf(0f)
        private set
    var zoom by mutableFloatStateOf(1f)
        private set
    var scrollX by mutableFloatStateOf(0f)
        private set
    var scrollY by mutableFloatStateOf(0f)
        private set

    /** Space kept under the top bar and above the bottom controls, not scaled by zoom. */
    var padTop = 0f
        private set
    var padBottom = 0f
        private set
    private var gap = 0f
    private var margin = 0f

    private val maxPageWidth = sizes.maxOfOrNull { it.width }?.coerceAtLeast(1f) ?: 1f
    private var tops1 = FloatArray(sizes.size)
    private var total1 = 0f
    private var layoutWidth = -1f

    val pageCount get() = sizes.size
    val ready get() = width > 0f && height > 0f && sizes.isNotEmpty()

    /** Pixels per point at zoom 1. */
    val baseScale get() = if (width > 0f) (width - 2 * margin) / maxPageWidth else 1f

    fun setSize(w: Float, h: Float, padTop: Float, padBottom: Float, gap: Float, margin: Float) {
        val anchor = if (ready) currentAnchor() else null
        width = w
        height = h
        this.padTop = padTop
        this.padBottom = padBottom
        this.gap = gap
        this.margin = margin
        relayout()
        if (anchor != null) restoreAnchor(anchor) else clamp()
    }

    private fun relayout() {
        if (layoutWidth == width) return
        layoutWidth = width
        val s = baseScale
        var y = gap
        for (i in sizes.indices) {
            tops1[i] = y
            y += sizes[i].height * s + gap
        }
        total1 = y
    }

    val contentWidth get() = width * zoom
    val contentHeight get() = padTop + total1 * zoom + padBottom

    fun pageTop(i: Int) = padTop + tops1[i] * zoom
    fun pageWidth(i: Int) = sizes[i].width * baseScale * zoom
    fun pageHeight(i: Int) = sizes[i].height * baseScale * zoom
    fun pageLeft(i: Int) = (contentWidth - pageWidth(i)) / 2f

    /** Page rectangle on screen. */
    fun pageRect(i: Int): Rect {
        val l = pageLeft(i) - scrollX
        val t = pageTop(i) - scrollY
        return Rect(l, t, l + pageWidth(i), t + pageHeight(i))
    }

    /** Index of the last page whose top is at or above content y. */
    private fun pageAtContentY(y: Float): Int {
        var lo = 0
        var hi = sizes.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (pageTop(mid) <= y) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun visiblePages(): IntRange {
        if (!ready) return IntRange.EMPTY
        val first = pageAtContentY(scrollY)
        val last = pageAtContentY(scrollY + height)
        return first..last
    }

    /** The page under the middle of the screen. */
    val currentPage: Int get() = if (ready) pageAtContentY(scrollY + height / 2f) else 0

    fun screenToPage(p: Offset, clampToPage: Int? = null): PageHit? {
        if (!ready) return null
        val cx = p.x + scrollX
        val cy = p.y + scrollY
        val i = clampToPage ?: pageAtContentY(cy)
        val nx = (cx - pageLeft(i)) / pageWidth(i)
        val ny = (cy - pageTop(i)) / pageHeight(i)
        if (clampToPage != null) return PageHit(i, nx.coerceIn(0f, 1f), ny.coerceIn(0f, 1f))
        if (nx !in 0f..1f || ny !in 0f..1f) return null
        return PageHit(i, nx, ny)
    }

    /** Converts a normalized rect of page [i] to screen coordinates. */
    fun toScreen(i: Int, l: Float, t: Float, r: Float, b: Float): Rect {
        val pr = pageRect(i)
        return Rect(pr.left + l * pr.width, pr.top + t * pr.height, pr.left + r * pr.width, pr.top + b * pr.height)
    }

    fun scrollBy(dx: Float, dy: Float): Offset {
        val ox = scrollX
        val oy = scrollY
        scrollX += dx
        scrollY += dy
        clamp()
        return Offset(scrollX - ox, scrollY - oy)
    }

    fun zoomTo(z: Float, focus: Offset) {
        if (!ready) return
        val docX = (scrollX + focus.x) / zoom
        val docY = (scrollY + focus.y - padTop) / zoom
        zoom = z.coerceIn(MIN_ZOOM, MAX_ZOOM)
        scrollX = docX * zoom - focus.x
        scrollY = padTop + docY * zoom - focus.y
        clamp()
    }

    /** Puts the top of page [i] (plus a normalized offset inside it) right under the top bar. */
    fun scrollToPage(i: Int, offsetInPage: Float = 0f) {
        if (!ready || sizes.isEmpty()) return
        val p = i.coerceIn(0, sizes.size - 1)
        scrollY = pageTop(p) + offsetInPage * pageHeight(p) - padTop - gap * zoom
        clamp()
    }

    /** Scrolls so the screen rect [r] is comfortably visible; does nothing if it already is. */
    fun reveal(r: Rect) {
        val top = padTop + gap
        val bottom = height - padBottom - gap
        if (r.top < top || r.bottom > bottom) scrollY += r.top - (top + (bottom - top) / 3f)
        if (r.left < 0 || r.right > width) scrollX += r.center.x - width / 2f
        clamp()
    }

    /** Zoom at which page [i] fits entirely between the bars. */
    fun fitPageZoom(i: Int): Float {
        val avail = height - padTop - padBottom - 2 * gap
        val h1 = sizes[i].height * baseScale
        val w1 = sizes[i].width * baseScale
        return minOf(1f, avail / h1, (width - 2 * margin) / w1).coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    fun setZoomAndPage(z: Float, page: Int) {
        zoom = z.coerceIn(MIN_ZOOM, MAX_ZOOM)
        scrollToPage(page)
    }

    private fun clamp() {
        scrollX = if (contentWidth <= width) (contentWidth - width) / 2f
        else scrollX.coerceIn(0f, contentWidth - width)
        scrollY = scrollY.coerceIn(0f, (contentHeight - height).coerceAtLeast(0f))
    }

    /** The page at the top of the reading area and how far into it (normalized). */
    fun topAnchor(): Pair<Int, Float> = currentAnchor().let { it.page to it.offset.coerceIn(0f, 1f) }

    private data class Anchor(val page: Int, val offset: Float)

    private fun currentAnchor(): Anchor {
        val y = scrollY + padTop
        val i = pageAtContentY(y)
        return Anchor(i, ((y - pageTop(i)) / pageHeight(i)).coerceIn(-0.5f, 1f))
    }

    private fun restoreAnchor(a: Anchor) {
        scrollY = pageTop(a.page) + a.offset * pageHeight(a.page) - padTop
        clamp()
    }

    companion object {
        const val MIN_ZOOM = 0.5f
        const val MAX_ZOOM = 6f
    }
}
