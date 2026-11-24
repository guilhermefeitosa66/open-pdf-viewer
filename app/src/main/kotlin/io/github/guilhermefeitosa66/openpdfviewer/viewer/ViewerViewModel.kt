package io.github.guilhermefeitosa66.openpdfviewer.viewer

import android.app.Application
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.guilhermefeitosa66.openpdfviewer.data.DocumentSource
import io.github.guilhermefeitosa66.openpdfviewer.data.RecentFile
import io.github.guilhermefeitosa66.openpdfviewer.data.RecentFiles
import io.github.guilhermefeitosa66.openpdfviewer.pdfium.PdfDocument
import io.github.guilhermefeitosa66.openpdfviewer.pdfium.PdfOpenException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface DocState {
    data object Loading : DocState
    data class NeedPassword(val wrong: Boolean) : DocState
    data class Failed(val kind: Kind) : DocState {
        enum class Kind { ACCESS, FORMAT, SECURITY, UNKNOWN }
    }
    data class Ready(
        val doc: PdfDocument,
        val name: String,
        val viewport: Viewport,
        val renderer: PageRenderer,
        val initialPage: Int,
        val initialOffset: Float = 0f,
        val initialZoom: Float = 1f,
        val initialScrollX: Float = 0.5f,
    ) : DocState
}

data class Match(val page: Int, val start: Int, val length: Int, val rects: List<RectF>)

data class Selection(val page: Int, val start: Int, val end: Int, val rects: List<RectF>)

class ViewerViewModel(app: Application) : AndroidViewModel(app) {
    private val recent = RecentFiles.get(app)

    var uri: Uri? = null
        private set
    var state by mutableStateOf<DocState>(DocState.Loading)
        private set
    private var openJob: Job? = null
    private var password: String? = null

    // Search
    var searchOpen by mutableStateOf(false)
    var query by mutableStateOf("")
        private set
    var matchCase by mutableStateOf(false)
        private set
    var searching by mutableStateOf(false)
        private set
    val matches = ArrayList<Match>()
    private val matchesByPage = HashMap<Int, MutableList<Int>>()
    var matchesVersion by mutableIntStateOf(0)
        private set
    var currentMatch by mutableIntStateOf(-1)
        private set
    private var searchJob: Job? = null
    private val normalized = HashMap<Int, NormalizedText>()

    // Outline and links
    var outline by mutableStateOf<List<io.github.guilhermefeitosa66.openpdfviewer.pdfium.OutlineItem>?>(null)
        private set
    private val history = ArrayDeque<Pair<Int, Float>>()
    var historyTop by mutableStateOf<Pair<Int, Float>?>(null)
        private set
    /** A web link waiting for the user to confirm before the browser opens. */
    var pendingWebLink by mutableStateOf<String?>(null)
        private set

    // Selection
    var selection by mutableStateOf<Selection?>(null)
        private set
    private var selectionJob: Job? = null

    private val ready get() = state as? DocState.Ready

    fun open(target: Uri) {
        if (target == uri && state !is DocState.Failed) return
        closeDocument()
        uri = target
        password = null
        load()
    }

    fun submitPassword(pw: String) {
        password = pw
        load()
    }

    private fun load() {
        val target = uri ?: return
        val app = getApplication<Application>()
        val startedAt = SystemClock.elapsedRealtime()
        state = DocState.Loading
        openJob?.cancel()
        openJob = viewModelScope.launch {
            recent.load()
            val result = runCatching {
                val pfd = DocumentSource.open(app, target)
                try {
                    PdfDocument.open(pfd, password)
                } finally {
                    pfd.close()
                }
            }
            val doc = result.getOrElse { e ->
                android.util.Log.w("OpenPdfViewer", "open failed", e)
                state = when (e) {
                    is PdfOpenException -> when (e.reason) {
                        PdfOpenException.Reason.PASSWORD -> DocState.NeedPassword(wrong = password != null)
                        PdfOpenException.Reason.FORMAT -> DocState.Failed(DocState.Failed.Kind.FORMAT)
                        PdfOpenException.Reason.SECURITY -> DocState.Failed(DocState.Failed.Kind.SECURITY)
                        PdfOpenException.Reason.FILE -> DocState.Failed(DocState.Failed.Kind.ACCESS)
                        else -> DocState.Failed(DocState.Failed.Kind.UNKNOWN)
                    }
                    is DocumentSource.AccessLost -> DocState.Failed(DocState.Failed.Kind.ACCESS)
                    else -> DocState.Failed(DocState.Failed.Kind.UNKNOWN)
                }
                return@launch
            }
            val known = recent.find(target)
            val name = known?.name ?: withContext(Dispatchers.IO) { DocumentSource.displayName(app, target) }
            val initialPage = (known?.lastPage ?: 0).coerceIn(0, (doc.pageCount - 1).coerceAtLeast(0))
            state = DocState.Ready(
                doc = doc,
                name = name,
                viewport = Viewport(doc.pageSizes),
                renderer = PageRenderer(doc, viewModelScope, startedAt),
                initialPage = initialPage,
                initialOffset = known?.offset ?: 0f,
                initialZoom = known?.zoom ?: 1f,
                initialScrollX = known?.scrollX ?: 0.5f,
            )
            recent.upsert(
                RecentFile(
                    uri = target.toString(),
                    name = name,
                    pageCount = doc.pageCount,
                    lastPage = initialPage,
                    openedAt = System.currentTimeMillis(),
                    offset = known?.offset ?: 0f,
                    zoom = known?.zoom ?: 1f,
                    scrollX = known?.scrollX ?: 0.5f,
                )
            )
            saveThumbnail(doc, target)
            DocumentSource.keepPrivateCopy(app, target)
        }
    }

    private suspend fun saveThumbnail(doc: PdfDocument, target: Uri) {
        if (doc.pageCount == 0) return
        val file = recent.thumbFile(target)
        if (file.exists()) return
        delay(400) // let the first pages render first
        val size = doc.pageSizes[0]
        val w = 132
        val h = (w * size.height / size.width).toInt().coerceIn(1, w * 3)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (!doc.render(0, bmp, w, h)) return
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            tmp.renameTo(file)
        }
    }

    /** Saves where the reader is (page, offset in it, zoom, horizontal position). */
    fun onPositionChanged(page: Int, offset: Float, zoom: Float, scrollX: Float) {
        val target = uri ?: return
        viewModelScope.launch { recent.setPosition(target, page, offset, zoom, scrollX) }
    }

    // ---- Search ----

    fun updateQuery(q: String) {
        query = q
        runSearch()
    }

    fun toggleMatchCase() {
        matchCase = !matchCase
        runSearch()
    }

    fun closeSearch() {
        searchOpen = false
        searchJob?.cancel()
        query = ""
        clearMatches()
    }

    private fun clearMatches() {
        matches.clear()
        matchesByPage.clear()
        currentMatch = -1
        searching = false
        matchesVersion++
    }

    private suspend fun normalizedText(doc: PdfDocument, page: Int): NormalizedText =
        normalized[page] ?: NormalizedText(doc.text(page)).also { normalized[page] = it }

    private fun runSearch() {
        searchJob?.cancel()
        clearMatches()
        val r = ready ?: return
        val q = query
        val case = matchCase
        if (NormalizedText.normalizeQuery(q).isEmpty()) return
        searching = true
        searchJob = viewModelScope.launch {
            delay(250)
            val startPage = r.viewport.currentPage
            for (page in 0 until r.doc.pageCount) {
                val found = normalizedText(r.doc, page).find(q, case, MAX_MATCHES - matches.size)
                for ((start, len) in found) {
                    val rects = r.doc.textRects(page, start, len)
                    matches += Match(page, start, len, rects)
                    matchesByPage.getOrPut(page) { ArrayList() } += matches.lastIndex
                    if (currentMatch < 0 && page >= startPage) {
                        currentMatch = matches.lastIndex
                        revealMatch()
                    }
                }
                if (found.isNotEmpty()) matchesVersion++
                if (matches.size >= MAX_MATCHES) break
            }
            if (currentMatch < 0 && matches.isNotEmpty()) {
                currentMatch = 0
                revealMatch()
            }
            searching = false
            matchesVersion++
        }
    }

    fun matchesOnPage(page: Int): List<Int> = matchesByPage[page].orEmpty()

    fun nextMatch(step: Int) {
        if (matches.isEmpty()) return
        currentMatch = Math.floorMod(currentMatch + step, matches.size)
        revealMatch()
    }

    private fun revealMatch() {
        val r = ready ?: return
        val m = matches.getOrNull(currentMatch) ?: return
        val first = m.rects.firstOrNull()
        if (first == null) {
            r.viewport.scrollToPage(m.page)
        } else {
            r.viewport.reveal(r.viewport.toScreen(m.page, first.left, first.top, first.right, first.bottom))
        }
        r.renderer.request(r.viewport)
    }

    // ---- Outline and links ----

    fun loadOutline() {
        val r = ready ?: return
        if (outline != null) return
        viewModelScope.launch { outline = r.doc.outline() }
    }

    /** Jumps inside the document, remembering where the reader was so "back" can return. */
    fun jumpTo(page: Int, offset: Float) {
        val r = ready ?: return
        history.addLast(r.viewport.topAnchor())
        if (history.size > 50) history.removeFirst()
        historyTop = history.lastOrNull()
        r.viewport.scrollToPage(page, offset)
        r.renderer.request(r.viewport)
    }

    /** Returns to the place before the last jump; false when there is none. */
    fun goBack(): Boolean {
        val r = ready ?: return false
        val (page, offset) = history.removeLastOrNull() ?: return false
        historyTop = history.lastOrNull()
        r.viewport.scrollToPage(page, offset)
        r.renderer.request(r.viewport)
        return true
    }

    /** A tap on the page: follows a link under the finger, otherwise runs [orElse]. */
    fun onTap(p: Offset, orElse: () -> Unit) {
        val r = ready ?: return orElse()
        val hit = r.viewport.screenToPage(p) ?: return orElse()
        viewModelScope.launch {
            when (val link = r.doc.linkAt(hit.page, hit.x, hit.y)) {
                is io.github.guilhermefeitosa66.openpdfviewer.pdfium.LinkTarget.Page -> jumpTo(link.page, link.offset)
                is io.github.guilhermefeitosa66.openpdfviewer.pdfium.LinkTarget.Web -> pendingWebLink = link.uri
                null -> orElse()
            }
        }
    }

    fun dismissWebLink() {
        pendingWebLink = null
    }

    // ---- Selection ----

    fun selectWordAt(p: Offset) {
        val r = ready ?: return
        val hit = r.viewport.screenToPage(p) ?: return
        selectionJob?.cancel()
        selectionJob = viewModelScope.launch {
            // Line gaps are not part of any character box: widen the search step by step.
            var idx = -1
            for (tolerance in floatArrayOf(4f, 10f, 20f)) {
                idx = r.doc.charIndexAt(hit.page, hit.x, hit.y, tolerance)
                if (idx >= 0) break
            }
            if (idx < 0) return@launch
            val text = r.doc.text(hit.page)
            if (idx >= text.length) return@launch
            var s = idx
            var e = idx + 1
            if (NormalizedText.isWordChar(text[idx])) {
                while (s > 0 && NormalizedText.isWordChar(text[s - 1])) s--
                while (e < text.length && NormalizedText.isWordChar(text[e])) e++
            }
            setSelection(r, hit.page, s, e)
        }
    }

    fun selectAllOnPage() {
        val r = ready ?: return
        val page = selection?.page ?: r.viewport.currentPage
        selectionJob?.cancel()
        selectionJob = viewModelScope.launch {
            val len = r.doc.text(page).length
            if (len > 0) setSelection(r, page, 0, len)
        }
    }

    /** Moves one end of the selection to the character nearest to screen point [p]. */
    fun dragHandle(start: Boolean, p: Offset) {
        val r = ready ?: return
        val sel = selection ?: return
        val hit = r.viewport.screenToPage(p, clampToPage = sel.page) ?: return
        selectionJob?.cancel()
        selectionJob = viewModelScope.launch {
            val idx = r.doc.charIndexAt(sel.page, hit.x, hit.y, 24f)
            if (idx < 0) return@launch
            val cur = selection ?: return@launch
            if (start) setSelection(r, cur.page, minOf(idx, cur.end - 1), cur.end)
            else setSelection(r, cur.page, cur.start, maxOf(idx + 1, cur.start + 1))
        }
    }

    private suspend fun setSelection(r: DocState.Ready, page: Int, start: Int, end: Int) {
        val rects = r.doc.textRects(page, start, end - start)
        selection = Selection(page, start, end, rects)
    }

    fun clearSelection() {
        selectionJob?.cancel()
        selection = null
    }

    suspend fun selectedText(): String {
        val r = ready ?: return ""
        val sel = selection ?: return ""
        return NormalizedText.forClipboard(r.doc.text(sel.page), sel.start, sel.end)
    }

    // ---- Lifecycle ----

    fun closeDocument() {
        openJob?.cancel()
        searchJob?.cancel()
        selectionJob?.cancel()
        closeSearch()
        selection = null
        normalized.clear()
        outline = null
        history.clear()
        historyTop = null
        pendingWebLink = null
        val r = ready
        state = DocState.Loading
        uri = null
        if (r != null) closingScope.launch { r.doc.closeAsync() }
    }

    override fun onCleared() {
        closeDocument()
    }

    companion object {
        private const val MAX_MATCHES = 5000
        private val closingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
