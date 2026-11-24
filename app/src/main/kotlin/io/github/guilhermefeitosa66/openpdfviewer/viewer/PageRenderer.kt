package io.github.guilhermefeitosa66.openpdfviewer.viewer

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.guilhermefeitosa66.openpdfviewer.pdfium.PdfDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** A high-resolution render of the visible part of a page at a given zoom. */
class DetailTile(
    val page: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val image: ImageBitmap,
)

/**
 * Keeps page bitmaps for the viewer:
 * - base: each page at its zoom-1 size, LRU sized from the heap, drawn scaled at any zoom;
 * - detail: when zoomed in and idle, the visible part of each visible page at full resolution;
 * - thumbs: small renders for the page grid.
 * [version] changes whenever a new bitmap is ready, so the canvas redraws.
 */
class PageRenderer(
    private val doc: PdfDocument,
    private val scope: CoroutineScope,
    private val openedAt: Long,
) {
    val version = mutableIntStateOf(0)

    private val maxMem = Runtime.getRuntime().maxMemory()
    private val base = object : LruCache<Int, ImageBitmap>((maxMem / 5).toInt().coerceAtLeast(16 shl 20)) {
        override fun sizeOf(key: Int, value: ImageBitmap) = value.width * value.height * 4
    }
    private val thumbs = object : LruCache<Int, ImageBitmap>(16 shl 20) {
        override fun sizeOf(key: Int, value: ImageBitmap) = value.width * value.height * 4
    }
    private val baseJobs = HashMap<Int, Job>()
    private val thumbJobs = HashMap<Int, Job>()
    private var baseLayout = -1
    private var detailJob: Job? = null
    private var tiles: List<DetailTile> = emptyList()
    private var firstPageLogged = false

    fun base(i: Int): ImageBitmap? = base.get(i)
    fun thumb(i: Int): ImageBitmap? = thumbs.get(i)
    fun tiles(): List<DetailTile> = tiles

    /** Makes sure the visible pages (and one on each side) have a base bitmap. */
    fun request(vp: Viewport) {
        if (!vp.ready) return
        if (vp.layoutVersion != baseLayout) {
            baseLayout = vp.layoutVersion
            base.evictAll()
            baseJobs.values.forEach { it.cancel() }
            baseJobs.clear()
        }
        val visible = vp.visiblePages()
        if (visible.isEmpty()) return
        val wanted = (visible.first - 1).coerceAtLeast(0)..(visible.last + 1).coerceAtMost(vp.pageCount - 1)
        // Cancel work for pages that scrolled far away.
        baseJobs.keys.filter { it !in wanted }.forEach { baseJobs.remove(it)?.cancel() }
        // Visible pages first, then the neighbours.
        val order = visible.toList() + wanted.filter { it !in visible }
        for (i in order) {
            if (base.get(i) != null || baseJobs[i]?.isActive == true) continue
            val w = vp.pageWidth1(i).roundToInt().coerceIn(1, MAX_BASE_SIDE)
            val h = vp.pageHeight1(i).roundToInt().coerceIn(1, MAX_BASE_SIDE)
            baseJobs[i] = scope.launch {
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                if (doc.render(i, bmp, w, h)) {
                    base.put(i, bmp.asImageBitmap())
                    version.intValue++
                    if (!firstPageLogged) {
                        firstPageLogged = true
                        Log.i(TAG, "open to first page: ${SystemClock.elapsedRealtime() - openedAt} ms")
                    }
                }
                baseJobs.remove(i)
            }
        }
    }

    /** Called when the view is idle: renders sharp tiles if zoomed past the base resolution. */
    fun refreshDetail(vp: Viewport) {
        detailJob?.cancel()
        if (!vp.ready || vp.zoom <= 1.1f) {
            if (tiles.isNotEmpty()) {
                tiles = emptyList()
                version.intValue++
            }
            return
        }
        val jobs = vp.visiblePages().mapNotNull { i ->
            val r = vp.pageRect(i)
            val x0 = (-r.left).coerceAtLeast(0f)
            val y0 = (-r.top).coerceAtLeast(0f)
            val x1 = (vp.width - r.left).coerceAtMost(r.width)
            val y1 = (vp.height - r.top).coerceAtMost(r.height)
            if (x1 - x0 < 1f || y1 - y0 < 1f) null
            else Triple(i, r, floatArrayOf(x0, y0, x1, y1))
        }
        detailJob = scope.launch {
            val out = ArrayList<DetailTile>()
            for ((i, r, b) in jobs) {
                val w = (b[2] - b[0]).roundToInt().coerceAtLeast(1)
                val h = (b[3] - b[1]).roundToInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val ok = doc.render(
                    i, bmp, r.width.roundToInt(), r.height.roundToInt(),
                    offsetX = b[0].roundToInt(), offsetY = b[1].roundToInt(),
                )
                if (ok) {
                    out += DetailTile(
                        i, b[0] / r.width, b[1] / r.height, b[2] / r.width, b[3] / r.height,
                        bmp.asImageBitmap(),
                    )
                }
            }
            tiles = out
            version.intValue++
        }
    }

    /** Drops the bitmaps of page [i] after it changed (annotations), so it renders again. */
    fun invalidate(i: Int, vp: Viewport) {
        baseJobs.remove(i)?.cancel()
        base.remove(i)
        thumbs.remove(i)
        tiles = tiles.filter { it.page != i }
        version.intValue++
        request(vp)
        refreshDetail(vp)
    }

    fun requestThumb(i: Int, widthPx: Int) {
        if (thumbs.get(i) != null || thumbJobs[i]?.isActive == true) return
        val size = doc.pageSizes[i]
        val w = widthPx.coerceAtLeast(1)
        val h = (w * size.height / size.width).roundToInt().coerceIn(1, w * 4)
        thumbJobs[i] = scope.launch {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            if (doc.render(i, bmp, w, h)) {
                thumbs.put(i, bmp.asImageBitmap())
                version.intValue++
            }
            thumbJobs.remove(i)
        }
    }

    fun cancelThumb(i: Int) {
        thumbJobs.remove(i)?.cancel()
    }

    companion object {
        private const val TAG = "OpenPdfViewer"
        private const val MAX_BASE_SIDE = 4096
    }
}
