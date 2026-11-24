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

data class Match(
    val page: Int,
    val start: Int,
    val length: Int,
    val rects: List<RectF>,
    val before: String = "",
    val text: String = "",
    val after: String = "",
)

typealias Maker = suspend (io.github.guilhermefeitosa66.openpdfviewer.pdfium.PdfDocument, String) -> Boolean

/**
 * Selected text from [start] on [startPage] up to [end] (exclusive) on [endPage]; it can span
 * pages. [rects] holds the highlight rectangles of each page involved.
 */
data class Selection(val startPage: Int, val start: Int, val endPage: Int, val end: Int, val rects: Map<Int, List<RectF>>) {
    val page get() = startPage
    val firstRect get() = rects[startPage]?.firstOrNull()
    val lastRect get() = rects[endPage]?.lastOrNull()
}

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
    var wholeWord by mutableStateOf(false)
        private set
    var ignoreAccents by mutableStateOf(true)
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

    // Bookmarks
    private val bookmarkStore = io.github.guilhermefeitosa66.openpdfviewer.data.Bookmarks(app)
    var bookmarks by mutableStateOf<List<io.github.guilhermefeitosa66.openpdfviewer.data.Bookmark>>(emptyList())
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
            bookmarks = bookmarkStore.load(target).filter { it.page in 0 until doc.pageCount }
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

    fun toggleWholeWord() {
        wholeWord = !wholeWord
        runSearch()
    }

    fun toggleIgnoreAccents() {
        ignoreAccents = !ignoreAccents
        runSearch()
    }

    /** Jumps to match [index] (from the results list). */
    fun goToMatch(index: Int) {
        if (index !in matches.indices) return
        currentMatch = index
        revealMatch()
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
        val options = NormalizedText.Options(matchCase = matchCase, wholeWord = wholeWord, ignoreAccents = ignoreAccents)
        if (NormalizedText.normalizeQuery(q).isEmpty()) return
        searching = true
        searchJob = viewModelScope.launch {
            delay(250)
            val startPage = r.viewport.currentPage
            for (page in 0 until r.doc.pageCount) {
                val found = normalizedText(r.doc, page).findHits(q, options, MAX_MATCHES - matches.size)
                for (hit in found) {
                    val rects = r.doc.textRects(page, hit.start, hit.count)
                    matches += Match(page, hit.start, hit.count, rects, hit.before, hit.match, hit.after)
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
        if (first == null || (r.viewport.paged && r.viewport.zoom <= 1.05f)) {
            r.viewport.scrollToPage(m.page)
        } else {
            r.viewport.reveal(r.viewport.toScreen(m.page, first.left, first.top, first.right, first.bottom))
        }
        r.renderer.request(r.viewport)
    }

    // ---- Document info ----

    var info by mutableStateOf<io.github.guilhermefeitosa66.openpdfviewer.pdfium.DocumentInfo?>(null)
        private set
    var fileSize by mutableStateOf(-1L)
        private set

    fun loadInfo() {
        val r = ready ?: return
        val target = uri ?: return
        viewModelScope.launch {
            info = r.doc.info()
            fileSize = withContext(Dispatchers.IO) { DocumentSource.size(getApplication(), target) }
        }
    }

    /** Sends the document to the Android print dialog (it needs an Activity context). */
    fun print(context: android.content.Context) {
        val r = ready ?: return
        val target = uri ?: return
        viewModelScope.launch {
            val encrypted = r.doc.info()?.encrypted == true
            PdfPrintAdapter.print(context, viewModelScope, target, r.name, r.doc, encrypted)
        }
    }

    // ---- Attachments ----

    var attachments by mutableStateOf<List<String>?>(null)
        private set

    fun loadAttachments() {
        val r = ready ?: return
        if (attachments != null) return
        viewModelScope.launch { attachments = r.doc.attachments() }
    }

    /** Writes attachment [index] to a private file ready to share or save, or null on failure. */
    suspend fun extractAttachment(index: Int): java.io.File? {
        val r = ready ?: return null
        val name = attachments?.getOrNull(index) ?: return null
        val data = r.doc.attachmentData(index) ?: return null
        return withContext(Dispatchers.IO) {
            val dir = java.io.File(getApplication<Application>().cacheDir, "share/attachments").apply { mkdirs() }
            val safe = name.substringAfterLast('/').replace(Regex("[\\\\:*?\"<>|\\p{Cntrl}]"), "_").ifBlank { "attachment" }
            java.io.File(dir, safe).apply { writeBytes(data) }
        }
    }

    // ---- Read aloud ----

    var readAloud by mutableStateOf<ReadAloud?>(null)
        private set

    fun startReadAloud() {
        val r = ready ?: return
        val ra = readAloud ?: ReadAloud(getApplication(), viewModelScope, r.doc) { page -> normalizedText(r.doc, page) }
            .also { readAloud = it }
        ra.start(r.viewport.currentPage)
    }

    fun stopReadAloud() {
        readAloud?.release()
        readAloud = null
    }

    /**
     * Renders the current page at about 150 dpi to a PNG and puts it on the clipboard (as a
     * content URI other apps can paste). Returns false when it could not.
     */
    suspend fun copyPageAsImage(): Boolean {
        val r = ready ?: return false
        val app = getApplication<Application>()
        val page = r.viewport.currentPage
        val size = r.doc.pageSizes[page]
        val scale = 150f / 72f
        val w = (size.width * scale).toInt().coerceIn(1, 3000)
        val h = (size.height * w / size.width).toInt().coerceIn(1, 4200)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (!r.doc.render(page, bmp, w, h)) return false
        val file = withContext(Dispatchers.IO) {
            val dir = java.io.File(app.cacheDir, "share/pages").apply { mkdirs() }
            java.io.File(dir, "page-${page + 1}.png").apply { outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
        bmp.recycle()
        val uri = androidx.core.content.FileProvider.getUriForFile(app, app.packageName + ".files", file)
        val cm = app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newUri(app.contentResolver, file.name, uri))
        return true
    }

    // ---- Bookmarks ----

    fun isBookmarked(page: Int) = bookmarks.any { it.page == page }

    fun addBookmark(page: Int, title: String) = updateBookmarks {
        (it.filterNot { b -> b.page == page } +
            io.github.guilhermefeitosa66.openpdfviewer.data.Bookmark(page, title.trim(), System.currentTimeMillis()))
            .sortedBy { b -> b.page }
    }

    fun renameBookmark(page: Int, title: String) = updateBookmarks { list ->
        list.map { if (it.page == page) it.copy(title = title.trim()) else it }
    }

    fun removeBookmark(page: Int) = updateBookmarks { list -> list.filterNot { it.page == page } }

    private fun updateBookmarks(
        change: (List<io.github.guilhermefeitosa66.openpdfviewer.data.Bookmark>) -> List<io.github.guilhermefeitosa66.openpdfviewer.data.Bookmark>,
    ) {
        val target = uri ?: return
        bookmarks = change(bookmarks)
        val snapshot = bookmarks
        viewModelScope.launch { bookmarkStore.save(target, snapshot) }
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

    /** A tap on the page: places a pending note/text, opens an annotation, follows a link, or runs [orElse]. */
    fun onTap(p: Offset, orElse: () -> Unit) {
        val r = ready ?: return orElse()
        placing?.let { mode ->
            // Placing a note or text: a tap in a gap still lands on the nearest page.
            val at = r.viewport.nearestPageHit(p) ?: return
            placing = null
            placementTarget = Triple(mode, at.page, Offset(at.x, at.y))
            return
        }
        val hit = r.viewport.screenToPage(p) ?: return orElse()
        viewModelScope.launch {
            r.doc.annotAt(hit.page, hit.x, hit.y)?.let {
                openedAnnot = it
                return@launch
            }
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
            setSelection(r, hit.page, s, hit.page, e)
        }
    }

    fun selectAllOnPage() {
        val r = ready ?: return
        val page = selection?.startPage ?: r.viewport.currentPage
        selectionJob?.cancel()
        selectionJob = viewModelScope.launch {
            val len = r.doc.text(page).length
            if (len > 0) setSelection(r, page, 0, page, len)
        }
    }

    /**
     * Moves one end of the selection to the character nearest to screen point [p], which may be on
     * another page than the selection so far.
     */
    fun dragHandle(start: Boolean, p: Offset) {
        val r = ready ?: return
        if (selection == null) return
        val hit = r.viewport.nearestPageHit(p) ?: return
        selectionJob?.cancel()
        selectionJob = viewModelScope.launch {
            var idx = r.doc.charIndexAt(hit.page, hit.x, hit.y, 24f)
            if (idx < 0) {
                // Above or below all text of that page: snap to its first or last character.
                val len = r.doc.text(hit.page).length
                if (len == 0) return@launch
                idx = if (hit.y < 0.5f) 0 else len - 1
            }
            val cur = selection ?: return@launch
            if (start) {
                // Keep start before end.
                if (hit.page > cur.endPage || (hit.page == cur.endPage && idx >= cur.end)) return@launch
                setSelection(r, hit.page, idx, cur.endPage, cur.end)
            } else {
                if (hit.page < cur.startPage || (hit.page == cur.startPage && idx < cur.start)) return@launch
                setSelection(r, cur.startPage, cur.start, hit.page, idx + 1)
            }
        }
    }

    private suspend fun setSelection(r: DocState.Ready, startPage: Int, start: Int, endPage: Int, end: Int) {
        val rects = HashMap<Int, List<RectF>>()
        // Rectangles only for a reasonable span; text beyond it is still copied.
        for (p in startPage..minOf(endPage, startPage + MAX_SELECTION_PAGES)) {
            val from = if (p == startPage) start else 0
            val to = if (p == endPage) end else r.doc.text(p).length
            if (to > from) rects[p] = r.doc.textRects(p, from, to - from)
        }
        selection = Selection(startPage, start, endPage, end, rects)
    }

    /** True when the document has changes (annotations) that are not saved to the file yet. */
    var unsaved by mutableStateOf(false)
        private set

    /** Turns the selection into a text markup annotation (highlight, underline...), page by page. */
    fun markSelection(type: io.github.guilhermefeitosa66.openpdfviewer.pdfium.PdfDocument.Markup, argb: Int) {
        val r = ready ?: return
        val sel = selection ?: return
        val author = io.github.guilhermefeitosa66.openpdfviewer.ui.ThemeSettings.get(getApplication()).authorName.ifBlank { null }
        viewModelScope.launch {
            val adds = ArrayList<Edit>()
            for (p in sel.startPage..sel.endPage) {
                val from = if (p == sel.startPage) sel.start else 0
                val to = if (p == sel.endPage) sel.end else r.doc.text(p).length
                if (to <= from) continue
                val make: Maker = { doc, nm -> doc.addTextMarkup(p, from, to - from, type, argb, author, nm) }
                val nm = newName()
                if (make(r.doc, nm)) {
                    adds += Edit.Add(p, nm, make)
                    r.renderer.invalidate(p, r.viewport)
                }
            }
            if (adds.isNotEmpty()) record(if (adds.size == 1) adds[0] else Edit.Group(adds))
            unsaved = r.doc.modified
            clearSelection()
        }
    }

    // ---- Notes and text ----

    enum class Place { NOTE, TEXT }

    /** Waiting for a tap that says where the new note or text goes. */
    var placing by mutableStateOf<Place?>(null)
    /** Where the user tapped; the dialog asking for the content is open while this is set. */
    var placementTarget by mutableStateOf<Triple<Place, Int, Offset>?>(null)
    /** An existing annotation the user tapped (shown in a dialog). */
    var openedAnnot by mutableStateOf<io.github.guilhermefeitosa66.openpdfviewer.pdfium.PdfDocument.AnnotHit?>(null)

    private val authorName get() = io.github.guilhermefeitosa66.openpdfviewer.ui.ThemeSettings.get(getApplication()).authorName.ifBlank { null }

    fun addNote(page: Int, at: Offset, text: String) {
        val author = authorName
        createAnnot(page) { doc, nm -> doc.addNote(page, at.x, at.y, text, NOTE_COLOR, author, nm) }
    }

    fun addText(page: Int, at: Offset, text: String, font: io.github.guilhermefeitosa66.openpdfviewer.pdfium.PdfDocument.StandardFont, size: Float, argb: Int) {
        val author = authorName
        createAnnot(page) { doc, nm -> doc.addText(page, at.x, at.y, text, font, size, argb, author, nm) }
    }

    /** Changes the text of an annotation; it gets a /NM name first so the change can be undone. */
    fun updateAnnotText(hit: io.github.guilhermefeitosa66.openpdfviewer.pdfium.PdfDocument.AnnotHit, text: String) {
        val r = ready ?: return
        viewModelScope.launch {
            val nm = hit.nm.ifEmpty { newName().also { r.doc.nameAnnot(hit, it) } }
            if (r.doc.setContentsByName(hit.page, nm, text)) {
                record(Edit.Contents(hit.page, nm, hit.contents, text))
                r.renderer.invalidate(hit.page, r.viewport)
                unsaved = r.doc.modified
            }
        }
    }

    /** True when deleting this annotation can be undone (it was created in this session). */
    fun canUndoDelete(hit: io.github.guilhermefeitosa66.openpdfviewer.pdfium.PdfDocument.AnnotHit) = hit.nm.isNotEmpty() && makers.containsKey(hit.nm)

    fun deleteAnnot(hit: io.github.guilhermefeitosa66.openpdfviewer.pdfium.PdfDocument.AnnotHit) {
        val r = ready ?: return
        viewModelScope.launch {
            val make = makers[hit.nm]
            val ok = if (make != null) r.doc.removeByName(hit.page, hit.nm) else r.doc.removeAnnot(hit)
            if (ok) {
                if (make != null) record(Edit.Remove(hit.page, hit.nm, make)) else annotationsVersion++
                r.renderer.invalidate(hit.page, r.viewport)
                unsaved = r.doc.modified
            }
        }
    }

    /** All annotations of the document, page by page (for the annotations list). */
    suspend fun allAnnotations(): List<io.github.guilhermefeitosa66.openpdfviewer.pdfium.PdfDocument.AnnotInfo> {
        val r = ready ?: return emptyList()
        val out = ArrayList<io.github.guilhermefeitosa66.openpdfviewer.pdfium.PdfDocument.AnnotInfo>()
        for (p in 0 until r.doc.pageCount) out += r.doc.annotations(p)
        return out
    }

    // ---- Edit history (undo/redo for this session) ----

    private sealed interface Edit {
        data class Add(val page: Int, val nm: String, val make: Maker) : Edit
        data class Remove(val page: Int, val nm: String, val make: Maker) : Edit
        data class Contents(val page: Int, val nm: String, val old: String, val new: String) : Edit
        data class Group(val edits: List<Edit>) : Edit
    }

    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()
    /** How to recreate each annotation made in this session, by its /NM name. */
    private val makers = HashMap<String, Maker>()
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    /** Bumped when annotations change, so the annotation list reloads. */
    var annotationsVersion by mutableIntStateOf(0)
        private set

    private fun newName() = "oppv-" + java.util.UUID.randomUUID().toString()

    private fun record(e: Edit) {
        undoStack.addLast(e)
        if (undoStack.size > 100) undoStack.removeFirst()
        redoStack.clear()
        rememberMaker(e)
        updateHistoryFlags()
    }

    private fun rememberMaker(e: Edit) {
        when (e) {
            is Edit.Add -> makers[e.nm] = e.make
            is Edit.Group -> e.edits.forEach { rememberMaker(it) }
            else -> Unit
        }
    }

    private fun updateHistoryFlags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
        annotationsVersion++
    }

    /** Creates an annotation through [make] with a fresh name and records it for undo. */
    private fun createAnnot(page: Int, make: Maker) {
        val r = ready ?: return
        viewModelScope.launch {
            val nm = newName()
            if (make(r.doc, nm)) {
                record(Edit.Add(page, nm, make))
                r.renderer.invalidate(page, r.viewport)
                unsaved = r.doc.modified
            }
        }
    }

    fun undo() = step(undo = true)

    fun redo() = step(undo = false)

    private fun step(undo: Boolean) {
        val r = ready ?: return
        val e = (if (undo) undoStack.removeLastOrNull() else redoStack.removeLastOrNull()) ?: return
        viewModelScope.launch {
            val pages = HashSet<Int>()
            applyEdit(r.doc, e, undo, pages)
            if (undo) redoStack.addLast(e) else undoStack.addLast(e)
            pages.forEach { r.renderer.invalidate(it, r.viewport) }
            unsaved = r.doc.modified
            updateHistoryFlags()
        }
    }

    private suspend fun applyEdit(doc: io.github.guilhermefeitosa66.openpdfviewer.pdfium.PdfDocument, e: Edit, undo: Boolean, pages: MutableSet<Int>) {
        when (e) {
            is Edit.Add -> {
                if (undo) doc.removeByName(e.page, e.nm) else e.make(doc, e.nm)
                pages += e.page
            }
            is Edit.Remove -> {
                if (undo) e.make(doc, e.nm) else doc.removeByName(e.page, e.nm)
                pages += e.page
            }
            is Edit.Contents -> {
                doc.setContentsByName(e.page, e.nm, if (undo) e.old else e.new)
                pages += e.page
            }
            is Edit.Group -> (if (undo) e.edits.asReversed() else e.edits).forEach { applyEdit(doc, it, undo, pages) }
        }
    }

    /** Runs an edit of page [page], then re-renders it and updates the unsaved flag. */
    private fun change(page: Int, block: suspend (io.github.guilhermefeitosa66.openpdfviewer.pdfium.PdfDocument) -> Boolean) {
        val r = ready ?: return
        viewModelScope.launch {
            if (block(r.doc)) {
                r.renderer.invalidate(page, r.viewport)
                unsaved = r.doc.modified
            }
        }
    }

    // ---- Free-hand drawing ----

    enum class DrawTool { PEN, ERASER }

    var drawing by mutableStateOf(false)
        private set
    var drawTool by mutableStateOf(DrawTool.PEN)
    /** Once a stylus touches the screen, fingers only scroll and zoom (palm rejection). */
    var stylusSeen = false
    /** The stroke being drawn: page and normalized points. */
    var liveStroke by mutableStateOf<Pair<Int, List<Offset>>?>(null)
        private set
    private var eraseJob: Job? = null

    fun startDrawing() {
        clearSelection()
        drawing = true
        drawTool = DrawTool.PEN
    }

    fun stopDrawing() {
        undoStack.clear()
        redoStack.clear()
        makers.clear()
        canUndo = false
        canRedo = false
        drawing = false
        liveStroke = null
        shapeKind = null
        shapePreview = null
        placing = null
        placementTarget = null
        openedAnnot = null
    }

    fun beginStroke(page: Int, p: Offset) {
        liveStroke = page to listOf(p)
    }

    fun extendStroke(p: Offset) {
        val (page, pts) = liveStroke ?: return
        // Skip points closer than ~0.2% of the page: smaller paths, same look.
        val last = pts.last()
        if (kotlin.math.abs(p.x - last.x) + kotlin.math.abs(p.y - last.y) < 0.002f) return
        liveStroke = page to pts + p
    }

    fun cancelStroke() {
        liveStroke = null
    }

    fun endStroke() {
        val r = ready ?: return
        val (page, pts) = liveStroke ?: return
        val settings = io.github.guilhermefeitosa66.openpdfviewer.ui.ThemeSettings.get(getApplication())
        val author = settings.authorName.ifBlank { null }
        val xy = FloatArray(pts.size * 2).also { a -> pts.forEachIndexed { i, o -> a[i * 2] = o.x; a[i * 2 + 1] = o.y } }
        viewModelScope.launch {
            val color = settings.inkColor
            val width = settings.inkWidth
            val make: Maker = { doc, nm -> doc.addInk(page, xy, color, width, author, nm) }
            val nm = newName()
            if (make(r.doc, nm)) {
                record(Edit.Add(page, nm, make))
                r.renderer.invalidate(page, r.viewport)
                unsaved = r.doc.modified
            }
            // Keep the live stroke until the page shows the real one.
            delay(120)
            if (liveStroke?.second === pts) liveStroke = null
        }
    }

    // ---- Shapes ----

    /** The shape tool in use, or null when not adding shapes. */
    var shapeKind by mutableStateOf<Shapes.Kind?>(null)
    /** Fill for rectangles and ellipses (ARGB), 0 = none. */
    var shapeFill by mutableStateOf(0)
    /** Shape being drawn: page and its normalized points (start/end, or polygon vertices). */
    var shapePreview by mutableStateOf<Pair<Int, List<Offset>>?>(null)
        private set

    fun startShapes() {
        clearSelection()
        drawing = false
        shapeKind = Shapes.Kind.RECTANGLE
    }

    fun stopShapes() {
        shapeKind = null
        shapePreview = null
    }

    fun shapeDown(page: Int, p: Offset) {
        val kind = shapeKind ?: return
        val current = shapePreview
        shapePreview = if (kind == Shapes.Kind.POLYGON && current != null && current.first == page) {
            page to current.second + p
        } else {
            page to listOf(p, p)
        }
    }

    fun shapeMove(p: Offset) {
        val kind = shapeKind ?: return
        if (kind == Shapes.Kind.POLYGON) return
        val (page, pts) = shapePreview ?: return
        shapePreview = page to listOf(pts.first(), p)
    }

    /** Ends a drag; for every tool except the polygon this commits the shape. */
    fun shapeUp() {
        if (shapeKind != Shapes.Kind.POLYGON) commitShape()
    }

    fun commitShape() {
        val r = ready ?: return
        val kind = shapeKind ?: return
        val (page, pts) = shapePreview ?: return
        shapePreview = null
        val settings = io.github.guilhermefeitosa66.openpdfviewer.ui.ThemeSettings.get(getApplication())
        val stroke = settings.inkColor
        val width = settings.inkWidth
        val size = r.doc.pageSizes[page]
        val a = pts.first()
        val b = pts.last()
        // Ignore taps that did not drag into a shape.
        if (kind != Shapes.Kind.POLYGON && kotlin.math.abs(a.x - b.x) * size.width < 3f && kotlin.math.abs(a.y - b.y) * size.height < 3f) return
        val fill = shapeFill
        val author = authorName
        createAnnot(page) { doc, nm ->
            when (kind) {
                Shapes.Kind.RECTANGLE, Shapes.Kind.ELLIPSE ->
                    doc.addBox(page, a.x, a.y, b.x, b.y, kind == Shapes.Kind.ELLIPSE, stroke, fill, width, author, nm)
                Shapes.Kind.LINE -> doc.addPaths(page, listOf(listOf(a.x to a.y, b.x to b.y)), stroke, width, author, nm)
                Shapes.Kind.ARROW -> doc.addPaths(page, Shapes.arrow(a.x, a.y, b.x, b.y, size.width, size.height, 6f + width * 3f), stroke, width, author, nm)
                Shapes.Kind.POLYGON -> doc.addPaths(page, Shapes.polygon(pts.map { it.x to it.y }), stroke, width, author, nm)
            }
        }
    }

    /** Eraser: removes the ink stroke under a normalized point of [page]. */
    fun eraseAt(page: Int, p: Offset) {
        val r = ready ?: return
        if (eraseJob?.isActive == true) return
        eraseJob = viewModelScope.launch {
            if (r.doc.eraseInkAt(page, p.x, p.y, 8f)) {
                r.renderer.invalidate(page, r.viewport)
                unsaved = r.doc.modified
            }
        }
    }

    fun clearSelection() {
        selectionJob?.cancel()
        selection = null
    }

    suspend fun selectedText(): String {
        val r = ready ?: return ""
        val sel = selection ?: return ""
        val parts = ArrayList<String>()
        for (p in sel.startPage..sel.endPage) {
            val text = r.doc.text(p)
            val from = if (p == sel.startPage) sel.start else 0
            val to = if (p == sel.endPage) sel.end else text.length
            parts += NormalizedText.forClipboard(text, from, to)
        }
        return parts.joinToString("\n").trim()
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
        attachments = null
        stopReadAloud()
        unsaved = false
        undoStack.clear()
        redoStack.clear()
        makers.clear()
        canUndo = false
        canRedo = false
        drawing = false
        liveStroke = null
        shapeKind = null
        shapePreview = null
        placing = null
        placementTarget = null
        openedAnnot = null
        bookmarks = emptyList()
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
        private const val MAX_SELECTION_PAGES = 30
        private const val NOTE_COLOR = 0xFFFFD54F.toInt()
        private val closingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
