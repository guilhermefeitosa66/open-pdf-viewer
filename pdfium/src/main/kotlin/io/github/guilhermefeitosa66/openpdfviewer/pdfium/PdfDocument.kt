package io.github.guilhermefeitosa66.openpdfviewer.pdfium

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.withContext

class PdfOpenException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { FILE, FORMAT, PASSWORD, SECURITY, UNKNOWN }
}

/** Properties stored in the document. Empty strings when absent. */
data class DocumentInfo(
    val title: String,
    val author: String,
    val subject: String,
    val keywords: String,
    val creator: String,
    val producer: String,
    /** Raw PDF dates ("D:YYYYMMDDHHmmSSOHH'mm'"). */
    val created: String,
    val modified: String,
    /** e.g. 17 for PDF 1.7; 0 when unknown. */
    val version: Int,
    val encrypted: Boolean,
    /** 0 none, 1 AcroForm, 2/3 XFA. */
    val formType: Int,
    val signatures: Int,
)

/** One entry of the document outline (table of contents), in reading order. */
data class OutlineItem(val title: String, val level: Int, val page: Int, val offset: Float)

/** What a tap on a link leads to. */
sealed interface LinkTarget {
    /** A place in this document: page index and normalized vertical offset in it. */
    data class Page(val page: Int, val offset: Float) : LinkTarget
    data class Web(val uri: String) : LinkTarget
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

    /** The outline, flattened in reading order with nesting levels. Empty when there is none. */
    suspend fun outline(): List<OutlineItem> = withContext(PdfiumThread.dispatcher) {
        if (closed) return@withContext emptyList()
        val raw = PdfiumNative.outline(handle)
        @Suppress("UNCHECKED_CAST") val titles = raw[0] as Array<String>
        val levels = raw[1] as IntArray
        val pages = raw[2] as IntArray
        val ys = raw[3] as FloatArray
        List(titles.size) { i -> OutlineItem(titles[i].trim(), levels[i], pages[i], offsetFor(pages[i], ys[i])) }
    }

    suspend fun info(): DocumentInfo? = withContext(PdfiumThread.dispatcher) {
        if (closed) return@withContext null
        val raw = PdfiumNative.documentInfo(handle)
        @Suppress("UNCHECKED_CAST") val m = (raw[0] as Array<String>).map { it.trim() }
        val nums = raw[1] as IntArray
        DocumentInfo(m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7], nums[0], nums[1] >= 0, nums[2], nums[3])
    }

    /** True once the document was changed in memory (annotations, forms) and not saved yet. */
    @Volatile
    var modified = false
        private set

    /** Marks the in-memory document as saved (after a successful save). */
    fun markSaved() {
        modified = false
    }

    enum class Markup(val subtype: Int) { HIGHLIGHT(9), UNDERLINE(10), SQUIGGLY(11), STRIKEOUT(12) }

    /**
     * Adds a text markup annotation over characters [start, start + count) of page [index].
     * The change lives in memory until the document is saved. Returns false when nothing was
     * added (no text there).
     */
    suspend fun addTextMarkup(index: Int, start: Int, count: Int, type: Markup, argb: Int, author: String?): Boolean =
        withContext(PdfiumThread.dispatcher) {
            if (closed || count <= 0) return@withContext false
            val lp = page(index) ?: return@withContext false
            val t = textOf(lp)
            if (t == 0L) return@withContext false
            val ok = PdfiumNative.addTextMarkup(lp.page, t, start, count, type.subtype, argb, author) >= 0
            if (ok) modified = true
            ok
        }

    /** Adds a free-hand stroke (normalized points [x0, y0, x1, y1...]) as an ink annotation. */
    suspend fun addInk(index: Int, xy: FloatArray, argb: Int, widthPt: Float, author: String?): Boolean =
        withContext(PdfiumThread.dispatcher) {
            if (closed || xy.size < 2) return@withContext false
            val lp = page(index) ?: return@withContext false
            val ok = PdfiumNative.addInk(lp.page, xy, argb, widthPt, author) >= 0
            if (ok) modified = true
            ok
        }

    /** Removes the ink stroke passing near a normalized point; true when one was removed. */
    suspend fun eraseInkAt(index: Int, x: Float, y: Float, tolerancePt: Float): Boolean =
        withContext(PdfiumThread.dispatcher) {
            if (closed) return@withContext false
            val lp = page(index) ?: return@withContext false
            val annot = PdfiumNative.inkAt(lp.page, x, y, tolerancePt)
            val ok = annot >= 0 && PdfiumNative.removeAnnot(lp.page, annot)
            if (ok) modified = true
            ok
        }

    /** An annotation found under the finger: its index on the page, PDF subtype and text. */
    data class AnnotHit(val page: Int, val index: Int, val subtype: Int, val contents: String) {
        val isNote get() = subtype == 1
    }

    enum class StandardFont(val pdfName: String) { SANS("Helvetica"), SERIF("Times-Roman"), MONO("Courier") }

    suspend fun addNote(index: Int, x: Float, y: Float, contents: String, argb: Int, author: String?): Boolean =
        edit(index) { lp -> PdfiumNative.addNote(lp.page, x, y, contents, argb, author) >= 0 }

    /** Text placed with its top-left corner at a normalized point; one line per '\n'. */
    suspend fun addText(index: Int, x: Float, y: Float, text: String, font: StandardFont, sizePt: Float, argb: Int, author: String?): Boolean =
        edit(index) { lp -> PdfiumNative.addTextStamp(handle, lp.page, x, y, text, font.pdfName, sizePt, argb, author) >= 0 }

    suspend fun annotAt(index: Int, x: Float, y: Float): AnnotHit? = withContext(PdfiumThread.dispatcher) {
        if (closed) return@withContext null
        val lp = page(index) ?: return@withContext null
        val raw = PdfiumNative.annotAt(lp.page, x, y) ?: return@withContext null
        val ids = raw[0] as IntArray
        AnnotHit(index, ids[0], ids[1], raw[1] as String)
    }

    suspend fun setAnnotContents(hit: AnnotHit, contents: String): Boolean =
        edit(hit.page) { lp -> PdfiumNative.setAnnotContents(lp.page, hit.index, contents) }

    suspend fun removeAnnot(hit: AnnotHit): Boolean =
        edit(hit.page) { lp -> PdfiumNative.removeAnnot(lp.page, hit.index) }

    /** Runs a change on page [index] on the PDFium thread and marks the document modified. */
    private suspend fun edit(index: Int, block: (LoadedPage) -> Boolean): Boolean = withContext(PdfiumThread.dispatcher) {
        if (closed) return@withContext false
        val lp = page(index) ?: return@withContext false
        val ok = block(lp)
        if (ok) modified = true
        ok
    }

    /**
     * Writes the document to [pfd] (a full rewrite). With [removeSecurity] a password-protected
     * document, opened with its password, is written without encryption.
     */
    suspend fun saveCopy(pfd: ParcelFileDescriptor, removeSecurity: Boolean = false): Boolean =
        withContext(PdfiumThread.dispatcher) {
            if (closed) return@withContext false
            PdfiumNative.saveCopy(handle, pfd.fd, removeSecurity)
        }

    /** Names of the files attached to the document, in order. */
    suspend fun attachments(): List<String> = withContext(PdfiumThread.dispatcher) {
        if (closed) emptyList() else PdfiumNative.attachmentNames(handle).toList()
    }

    /** Contents of attachment [index], or null when it cannot be read. */
    suspend fun attachmentData(index: Int): ByteArray? = withContext(PdfiumThread.dispatcher) {
        if (closed) null else PdfiumNative.attachmentData(handle, index)
    }

    /** The link under a normalized point of page [index], if any. */
    suspend fun linkAt(index: Int, x: Float, y: Float): LinkTarget? = withContext(PdfiumThread.dispatcher) {
        if (closed) return@withContext null
        val lp = page(index) ?: return@withContext null
        val s = PdfiumNative.linkAt(handle, lp.page, x, y) ?: return@withContext null
        when {
            s.startsWith("u:") -> LinkTarget.Web(s.substring(2))
            s.startsWith("p:") -> {
                val parts = s.split(':')
                val target = parts.getOrNull(1)?.toIntOrNull() ?: return@withContext null
                if (target !in 0 until pageCount) return@withContext null
                LinkTarget.Page(target, offsetFor(target, parts.getOrNull(2)?.toFloatOrNull() ?: Float.NaN))
            }
            else -> null
        }
    }

    /** PDF y (origin bottom-left) to a normalized offset from the top of the page. */
    private fun offsetFor(page: Int, y: Float): Float {
        if (y.isNaN() || page !in 0 until pageCount) return 0f
        val h = pageSizes[page].height
        return (1f - y / h).coerceIn(0f, 1f)
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
