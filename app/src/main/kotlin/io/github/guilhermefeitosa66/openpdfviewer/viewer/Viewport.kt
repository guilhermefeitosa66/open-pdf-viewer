package io.github.guilhermefeitosa66.openpdfviewer.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.guilhermefeitosa66.openpdfviewer.pdfium.PageSize
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** A point on a page, in normalized page coordinates (0..1, origin top-left). */
data class PageHit(val page: Int, val x: Float, val y: Float)

/**
 * How pages are arranged.
 * - continuous: one column, scrolling vertically (the default);
 * - paged: one screen-wide slot per page (or per two-page spread), swiped horizontally with snap;
 *   [dual] puts two pages side by side ([coverAlone] keeps the first page on its own);
 *   [rtl] orders slots and spreads right to left.
 */
data class LayoutOptions(
    val paged: Boolean = false,
    val dual: Boolean = false,
    val coverAlone: Boolean = true,
    val rtl: Boolean = false,
)

/**
 * Geometry of the reading surface. Every page has a rectangle at zoom 1 (in content pixels, below
 * the top padding); pages, gaps and margins scale with the zoom. Content coordinates start at the
 * top-left of the scrolled content; screen = content - scroll.
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
    var options by mutableStateOf(LayoutOptions())
        private set

    /** Space kept under the top bar and above the bottom controls, not scaled by zoom. */
    var padTop = 0f
        private set
    var padBottom = 0f
        private set
    private var gap = 0f
    private var margin = 0f

    private val n = sizes.size
    private val maxPageWidth = sizes.maxOfOrNull { it.width }?.coerceAtLeast(1f) ?: 1f

    // Layout at zoom 1.
    private val left1 = FloatArray(n)
    private val top1 = FloatArray(n)
    private val w1 = FloatArray(n)
    private val h1 = FloatArray(n)
    private var content1W = 0f
    private var content1H = 0f
    /** Paged layout: the slot (screen-wide column) of each page, and the pages of each slot. */
    private val slotOf = IntArray(n)
    private var slots: List<IntArray> = emptyList()
    /** Pages in visual order along the main axis. */
    private val visualOrder = IntArray(n)
    private var layoutKey: Any? = null

    val pageCount get() = n
    val ready get() = width > 0f && height > 0f && n > 0
    val paged get() = options.paged

    /** Pixels per point at zoom 1 in the continuous layout (the widest page fits the width). */
    val baseScale get() = if (width > 0f) (width - 2 * margin) / maxPageWidth else 1f

    fun setSize(w: Float, h: Float, padTop: Float, padBottom: Float, gap: Float, margin: Float) {
        val anchor = if (ready) topAnchor() else null
        width = w
        height = h
        this.padTop = padTop
        this.padBottom = padBottom
        this.gap = gap
        this.margin = margin
        relayout()
        if (anchor != null) placeAt(anchor.first, anchor.second) else clamp()
    }

    /** Switches layout keeping the current page in view. */
    fun setLayout(o: LayoutOptions) {
        if (o == options) return
        val anchor = if (ready) topAnchor() else null
        options = o
        if (o.paged) zoom = 1f
        relayout()
        if (anchor != null) placeAt(anchor.first, if (o.paged) 0f else anchor.second)
    }

    private fun relayout() {
        val key = listOf(width, height, padTop, padBottom, gap, margin, options)
        if (key == layoutKey || width <= 0f || n == 0) return
        layoutKey = key
        if (options.paged) layoutPaged() else layoutContinuous()
        layoutVersion++
    }

    /** Changes whenever page sizes at zoom 1 change (bitmaps rendered for the old ones are stale). */
    var layoutVersion = 0
        private set

    /** Width and height of page [i] in pixels at zoom 1. */
    fun pageWidth1(i: Int) = w1[i]
    fun pageHeight1(i: Int) = h1[i]

    private fun layoutContinuous() {
        val s = baseScale
        var y = gap
        for (i in 0 until n) {
            w1[i] = sizes[i].width * s
            h1[i] = sizes[i].height * s
            left1[i] = (width - w1[i]) / 2f
            top1[i] = y
            y += h1[i] + gap
            visualOrder[i] = i
            slotOf[i] = 0
        }
        slots = emptyList()
        content1W = width
        content1H = y
    }

    private fun layoutPaged() {
        // Group pages into spreads, in reading order.
        val spreads = ArrayList<IntArray>()
        var i = 0
        if (options.dual && options.coverAlone) {
            spreads += intArrayOf(0)
            i = 1
        }
        while (i < n) {
            if (options.dual && i + 1 < n) {
                spreads += intArrayOf(i, i + 1)
                i += 2
            } else {
                spreads += intArrayOf(i)
                i += 1
            }
        }
        val slotW = width
        val slotH = (height - padTop - padBottom).coerceAtLeast(1f)
        val count = spreads.size
        slots = List(count) { k -> spreads[if (options.rtl) count - 1 - k else k] }
        for ((k, spread) in slots.withIndex()) {
            // Right to left puts the first page of the spread on the right.
            val onScreen = if (options.rtl) spread.reversedArray() else spread
            val totalW = onScreen.sumOf { sizes[it].width.toDouble() }.toFloat()
            val maxH = onScreen.maxOf { sizes[it].height }
            val inner = gap * (onScreen.size - 1)
            val s = minOf((slotW - 2 * margin - inner) / totalW, (slotH - 2 * gap) / maxH).coerceAtLeast(0.01f)
            var x = k * slotW + (slotW - (totalW * s + inner)) / 2f
            for (p in onScreen) {
                w1[p] = sizes[p].width * s
                h1[p] = sizes[p].height * s
                left1[p] = x
                top1[p] = (slotH - h1[p]) / 2f
                slotOf[p] = k
                x += w1[p] + gap
            }
        }
        (0 until n).sortedBy { left1[it] }.forEachIndexed { k, p -> visualOrder[k] = p }
        content1W = count * slotW
        content1H = slotH
    }

    val contentWidth get() = content1W * zoom
    val contentHeight get() = padTop + content1H * zoom + padBottom

    fun pageTop(i: Int) = padTop + top1[i] * zoom
    fun pageLeft(i: Int) = left1[i] * zoom
    fun pageWidth(i: Int) = w1[i] * zoom
    fun pageHeight(i: Int) = h1[i] * zoom

    /** Page rectangle on screen. */
    fun pageRect(i: Int): Rect {
        val l = pageLeft(i) - scrollX
        val t = pageTop(i) - scrollY
        return Rect(l, t, l + pageWidth(i), t + pageHeight(i))
    }

    /** Main-axis content coordinate where page i starts. */
    private fun mainStart(i: Int) = if (paged) pageLeft(i) else pageTop(i)

    /** Visual index of the last page starting at or before main-axis content coordinate [c]. */
    private fun visualAt(c: Float): Int {
        var lo = 0
        var hi = n - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (mainStart(visualOrder[mid]) <= c) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun visiblePages(): IntRange {
        if (!ready) return IntRange.EMPTY
        val start = if (paged) scrollX else scrollY
        val end = start + if (paged) width else height
        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE
        for (k in visualAt(start)..visualAt(end)) {
            val p = visualOrder[k]
            val pageEnd = if (paged) pageLeft(p) + pageWidth(p) else pageTop(p) + pageHeight(p)
            if (pageEnd <= start && k < n - 1) continue // ends before the screen starts
            lo = minOf(lo, p)
            hi = maxOf(hi, p)
        }
        return lo..hi
    }

    /** The page under the middle of the screen (the first page of the spread when paged). */
    val currentPage: Int
        get() {
            if (!ready) return 0
            if (!paged) return visualOrder[visualAt(scrollY + height / 2f)]
            val slot = ((scrollX + width / 2f) / (width * zoom)).toInt().coerceIn(0, slots.size - 1)
            return slots[slot].min()
        }

    fun screenToPage(p: Offset, clampToPage: Int? = null): PageHit? {
        if (!ready) return null
        val cx = p.x + scrollX
        val cy = p.y + scrollY
        if (clampToPage != null) {
            val i = clampToPage
            val nx = (cx - pageLeft(i)) / pageWidth(i)
            val ny = (cy - pageTop(i)) / pageHeight(i)
            return PageHit(i, nx.coerceIn(0f, 1f), ny.coerceIn(0f, 1f))
        }
        for (i in visiblePages()) {
            val nx = (cx - pageLeft(i)) / pageWidth(i)
            val ny = (cy - pageTop(i)) / pageHeight(i)
            if (nx in 0f..1f && ny in 0f..1f) return PageHit(i, nx, ny)
        }
        return null
    }

    /**
     * The page point closest to a screen point: the page under it, or (in a gap or margin) the
     * nearest page along the reading axis, with coordinates clamped to it.
     */
    fun nearestPageHit(p: Offset): PageHit? {
        if (!ready) return null
        screenToPage(p)?.let { return it }
        val c = if (paged) p.x + scrollX else p.y + scrollY
        val k = visualAt(c)
        // Pick the closer of this page and the next one along the axis.
        val candidates = listOfNotNull(visualOrder[k], visualOrder.getOrNull(k + 1))
        val best = candidates.minBy { i ->
            val r = pageRect(i)
            val d = if (paged) p.x else p.y
            val lo = if (paged) r.left else r.top
            val hi = if (paged) r.right else r.bottom
            if (d < lo) lo - d else if (d > hi) d - hi else 0f
        }
        return screenToPage(p, clampToPage = best)
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

    /** Shows page [i]: its top (plus a normalized offset) under the top bar, or its slot when paged. */
    fun scrollToPage(i: Int, offsetInPage: Float = 0f) {
        if (!ready) return
        placeAt(i, offsetInPage)
    }

    private fun placeAt(page: Int, offset: Float) {
        if (n == 0) return
        val p = page.coerceIn(0, n - 1)
        if (paged) {
            scrollX = slotOf[p] * width * zoom
            scrollY = if (zoom > 1f) pageTop(p) + offset * pageHeight(p) - padTop else 0f
        } else {
            scrollY = pageTop(p) + offset * pageHeight(p) - padTop - gap * zoom
        }
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

    /** Zoom at which page [i] fits entirely between the bars (paged slots already fit: 1). */
    fun fitPageZoom(i: Int): Float {
        if (paged) return 1f
        val avail = height - padTop - padBottom - 2 * gap
        return minOf(1f, avail / h1[i], (width - 2 * margin) / w1[i]).coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    fun setZoomAndPage(z: Float, page: Int) {
        zoom = z.coerceIn(MIN_ZOOM, MAX_ZOOM)
        scrollToPage(page)
    }

    /**
     * Paged mode: the scrollX to settle on after a horizontal fling. [velocity] points in scroll
     * direction (positive = towards larger scrollX). Fast flings move one slot; slow ones snap to
     * the nearest.
     */
    fun snapTarget(velocity: Float, threshold: Float): Float {
        val slotW = width * zoom
        val k = scrollX / slotW
        val target = when {
            velocity > threshold -> floor(k + 0.02f) + 1f
            velocity < -threshold -> ceil(k - 0.02f) - 1f
            else -> k.roundToInt().toFloat()
        }.coerceIn(0f, (slots.size - 1).coerceAtLeast(0).toFloat())
        return (target * slotW).coerceIn(0f, (contentWidth - width).coerceAtLeast(0f))
    }

    private fun clamp() {
        scrollX = if (contentWidth <= width) (contentWidth - width) / 2f
        else scrollX.coerceIn(0f, contentWidth - width)
        scrollY = if (contentHeight <= height) 0f
        else scrollY.coerceIn(0f, contentHeight - height)
    }

    /** Horizontal position when zoomed in: 0 = left edge, 1 = right edge. */
    val scrollXFraction: Float
        get() = if (paged || contentWidth <= width) 0.5f else (scrollX / (contentWidth - width)).coerceIn(0f, 1f)

    /** Restores a saved reading position: zoom, then page and offset, then horizontal position. */
    fun restore(zoom: Float, page: Int, offset: Float, xFraction: Float) {
        this.zoom = if (paged) 1f else zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        placeAt(page, offset.coerceIn(0f, 1f))
        if (!paged) {
            scrollX = xFraction.coerceIn(0f, 1f) * (contentWidth - width)
            clamp()
        }
    }

    /** The page at the top of the reading area and how far into it (normalized). */
    fun topAnchor(): Pair<Int, Float> {
        if (paged) return currentPage to 0f
        val y = scrollY + padTop + gap * zoom
        val i = visualOrder[visualAt(y)]
        return i to ((y - pageTop(i)) / pageHeight(i)).coerceIn(0f, 1f)
    }

    companion object {
        const val MIN_ZOOM = 0.5f
        const val MAX_ZOOM = 6f
    }
}
