package io.github.guilhermefeitosa66.openpdfviewer.pdfium

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.withContext

class PdfOpenException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { FILE, FORMAT, PASSWORD, SECURITY, UNKNOWN }
}

/** Size of a page in points, with the page rotation applied. */
data class PageSize(val width: Float, val height: Float)

/**
 * An open PDF. All work runs on [PdfiumThread]; the methods are safe to call from any coroutine.
 * Loaded pages and their text are kept in a small LRU so scrolling back and forth stays cheap.
 */
class PdfDocument private constructor(private val handle: Long) {

    val pageCount: Int
    val pageSizes: List<PageSize>

    init {
        val raw = PdfiumNative.pageSizes(handle)
        pageCount = raw.size / 2
        pageSizes = List(pageCount) { PageSize(raw[it * 2], raw[it * 2 + 1]) }
    }

    private class LoadedPage(val page: Long) {
        var text: Long = 0L
        fun close() {
            if (text != 0L) PdfiumNative.closeText(text)
            PdfiumNative.closePage(page)
        }
    }

    private val pages = object : LinkedHashMap<Int, LoadedPage>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, LoadedPage>): Boolean {
            if (size > MAX_LOADED_PAGES) {
                eldest.value.close()
                return true
            }
            return false
        }
    }
    private val textCache = HashMap<Int, String>()
    private var closed = false

    private fun page(index: Int): LoadedPage? {
        check(!closed) { "document closed" }
        pages[index]?.let { return it }
        val p = PdfiumNative.loadPage(handle, index)
        if (p == 0L) return null
        return LoadedPage(p).also { pages[index] = it }
    }

    private fun textOf(lp: LoadedPage): Long {
        if (lp.text == 0L) lp.text = PdfiumNative.loadText(lp.page)
        return lp.text
    }

    /**
     * Renders page [index] scaled to [pageWidthPx] x [pageHeightPx] into [bitmap], with the
     * page's top-left corner at (-offsetX, -offsetY) of the bitmap.
     */
    suspend fun render(
        index: Int,
        bitmap: Bitmap,
        pageWidthPx: Int,
        pageHeightPx: Int,
        offsetX: Int = 0,
        offsetY: Int = 0,
    ): Boolean = withContext(PdfiumThread.dispatcher) {
        if (closed) return@withContext false
        val lp = page(index) ?: return@withContext false
        PdfiumNative.renderPage(lp.page, bitmap, -offsetX, -offsetY, pageWidthPx, pageHeightPx)
    }

    /** Text of the page; string index i is character i of the page. */
    suspend fun text(index: Int): String = withContext(PdfiumThread.dispatcher) {
        textCache[index]?.let { return@withContext it }
        if (closed) return@withContext ""
        val lp = page(index) ?: return@withContext ""
        val t = textOf(lp)
        val s = if (t == 0L) "" else PdfiumNative.pageText(t)
        textCache[index] = s
        s
    }

    /** Normalized (0..1, top-left origin) rectangles covering [start, start + count). */
    suspend fun textRects(index: Int, start: Int, count: Int): List<RectF> =
        withContext(PdfiumThread.dispatcher) {
            if (closed || count <= 0) return@withContext emptyList()
            val lp = page(index) ?: return@withContext emptyList()
            val t = textOf(lp)
            if (t == 0L) return@withContext emptyList()
            val raw = PdfiumNative.textRects(lp.page, t, start, count)
            List(raw.size / 4) { RectF(raw[it * 4], raw[it * 4 + 1], raw[it * 4 + 2], raw[it * 4 + 3]) }
        }

    /** Character index at a normalized point of the page, or -1. */
    suspend fun charIndexAt(index: Int, x: Float, y: Float, tolerancePt: Float = 8f): Int =
        withContext(PdfiumThread.dispatcher) {
            if (closed) return@withContext -1
            val lp = page(index) ?: return@withContext -1
            val t = textOf(lp)
            if (t == 0L) -1 else PdfiumNative.charIndexAt(lp.page, t, x, y, tolerancePt)
        }

    /** Closes on the PDFium thread; queued work for this document finishes first. */
    suspend fun closeAsync() = withContext(PdfiumThread.dispatcher) { closeNow() }

    private fun closeNow() {
        if (closed) return
        closed = true
        pages.values.forEach { it.close() }
        pages.clear()
        textCache.clear()
        PdfiumNative.closeDocument(handle)
    }

    companion object {
        private const val MAX_LOADED_PAGES = 8

        /**
         * Opens a PDF from a seekable file descriptor. The descriptor is duplicated, so the
         * caller may close [pfd] afterwards.
         */
        suspend fun open(pfd: ParcelFileDescriptor, password: String? = null): PdfDocument =
            withContext(PdfiumThread.dispatcher) {
                val h = PdfiumNative.openDocument(pfd.fd, password)
                // A handle is an opaque pointer: on arm64 it may be negative (tagged pointers).
                if (h == 0L) {
                    throw PdfOpenException(
                        when (PdfiumNative.lastOpenError()) {
                            2 -> PdfOpenException.Reason.FILE
                            3 -> PdfOpenException.Reason.FORMAT
                            4 -> PdfOpenException.Reason.PASSWORD
                            5 -> PdfOpenException.Reason.SECURITY
                            else -> PdfOpenException.Reason.UNKNOWN
                        }
                    )
                }
                PdfDocument(h)
            }
    }
}
