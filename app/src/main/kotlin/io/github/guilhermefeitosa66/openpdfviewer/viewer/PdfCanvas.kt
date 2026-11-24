package io.github.guilhermefeitosa66.openpdfviewer.viewer

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.guilhermefeitosa66.openpdfviewer.ui.LocalReaderColors
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** The scrolling, zooming page surface: draws pages, search highlights and the selection. */
@OptIn(FlowPreview::class)
@Composable
fun PdfCanvas(
    vm: ViewerViewModel,
    ready: DocState.Ready,
    padTop: Float,
    padBottom: Float,
    onTap: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vp = ready.viewport
    val renderer = ready.renderer
    val colors = LocalReaderColors.current
    val density = LocalDensity.current
    val gap = with(density) { 8.dp.toPx() }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = remember { io.github.guilhermefeitosa66.openpdfviewer.ui.ThemeSettings.get(context) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var placed by remember(ready) { mutableStateOf(false) }

    // Two-page spreads only where they fit: landscape or tablets.
    val wide = size.width > size.height || androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp >= 600
    val layout = LayoutOptions(
        paged = settings.pagedLayout,
        dual = settings.pagedLayout && settings.twoPages && wide,
        coverAlone = settings.coverAlone,
        rtl = settings.pagedLayout && settings.rightToLeft,
    )
    LaunchedEffect(ready, size, padTop, padBottom, layout) {
        if (size.width == 0 || size.height == 0) return@LaunchedEffect
        if (!placed) vp.setLayout(layout)
        vp.setSize(size.width.toFloat(), size.height.toFloat(), padTop, padBottom, gap, gap)
        if (placed) vp.setLayout(layout)
        if (!placed) {
            if (ready.initialOffset == 0f && ready.initialZoom == 1f) vp.scrollToPage(ready.initialPage)
            else vp.restore(ready.initialZoom, ready.initialPage, ready.initialOffset, ready.initialScrollX)
            placed = true
        }
        renderer.request(vp)
    }
    LaunchedEffect(ready) {
        snapshotFlow { listOf(vp.scrollX, vp.scrollY, vp.zoom, vp.width) }
            .collect { renderer.request(vp) }
    }
    LaunchedEffect(ready) {
        snapshotFlow { listOf(vp.scrollX, vp.scrollY, vp.zoom, vp.width) }
            .collectLatest {
                delay(150)
                renderer.refreshDetail(vp)
            }
    }
    LaunchedEffect(ready) {
        // Remember the reading position (page shown, offset into the page at the top, zoom).
        snapshotFlow { Triple(vp.topAnchor(), vp.zoom, vp.scrollXFraction) }
            .distinctUntilChanged()
            .debounce(600)
            .collect { (anchor, zoom, x) ->
                if (placed) vm.onPositionChanged(anchor.first, anchor.second, zoom, x)
            }
    }

    val vScroll = rememberScrollableState { d -> -vp.scrollBy(0f, -d).y }
    val hScroll = rememberScrollableState { d -> -vp.scrollBy(-d, 0f).x }
    // Page by page: a horizontal fling settles on a whole page (or spread) unless zoomed in.
    val defaultFling = androidx.compose.foundation.gestures.ScrollableDefaults.flingBehavior()
    val snapThreshold = with(density) { 400.dp.toPx() }
    val hFling = remember(vp, defaultFling) {
        object : androidx.compose.foundation.gestures.FlingBehavior {
            override suspend fun androidx.compose.foundation.gestures.ScrollScope.performFling(initialVelocity: Float): Float {
                if (!vp.paged || vp.zoom > 1.05f) return with(defaultFling) { performFling(initialVelocity) }
                // Scrollable deltas point opposite to scrollX: dragging right shows the left page.
                val target = vp.snapTarget(-initialVelocity, snapThreshold)
                var last = vp.scrollX
                animate(vp.scrollX, target, animationSpec = tween(220)) { v, _ ->
                    scrollBy(-(v - last))
                    last = v
                }
                return 0f
            }
        }
    }

    Canvas(
        modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(ready) {
                // Pinch to zoom (two fingers), taking over from the scrollables below.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = true)
                            if (zoom != 1f) vp.zoomTo(vp.zoom * zoom, centroid)
                            vp.scrollBy(-pan.x, -pan.y)
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(ready) {
                detectTapGestures(
                    onTap = { p -> onTap(p) },
                    onDoubleTap = { p ->
                        val target = if (abs(vp.zoom - 1f) < 0.05f) 2f else 1f
                        scope.launch {
                            animate(vp.zoom, target, animationSpec = tween(220)) { v, _ -> vp.zoomTo(v, p) }
                        }
                    },
                    onLongPress = { p -> vm.selectWordAt(p) },
                )
            }
            .scrollable(vScroll, Orientation.Vertical)
            .scrollable(hScroll, Orientation.Horizontal, flingBehavior = hFling),
    ) {
        drawRect(colors.gutter)
        if (!vp.ready) return@Canvas
        renderer.version.intValue // redraw when bitmaps arrive
        vm.matchesVersion
        val current = vm.currentMatch
        val selection = vm.selection
        val range = vp.visiblePages()
        val shadow = Color(0x2E1E1612)
        val mode = settings.pageMode
        val filter = mode.filter

        for (i in range) {
            val r = vp.pageRect(i)
            drawRect(shadow, Offset(r.left, r.top + 1.5f), r.size)
            drawRect(mode.paper, r.topLeft, r.size)
            renderer.base(i)?.let { drawBitmap(it, r, filter) }
        }
        for (t in renderer.tiles()) {
            if (t.page in range) drawBitmap(t.image, vp.toScreen(t.page, t.left, t.top, t.right, t.bottom), filter)
        }
        for (i in range) {
            for (mi in vm.matchesOnPage(i)) {
                val m = vm.matches.getOrNull(mi) ?: continue
                val isCurrent = mi == current
                for (rc in m.rects) {
                    val sr = vp.toScreen(i, rc.left, rc.top, rc.right, rc.bottom).inflate(1.5f)
                    val tint = if (isCurrent) colors.highlightCurrent else colors.highlight
                    if (mode.multiplyHighlights) {
                        drawRect(tint, sr.topLeft, sr.size, blendMode = BlendMode.Multiply)
                    } else {
                        drawRect(tint.copy(alpha = 0.45f), sr.topLeft, sr.size)
                    }
                    if (isCurrent) {
                        drawRect(colors.highlightCurrentRing, sr.topLeft, sr.size, style = Stroke(2f * density.density))
                    }
                }
            }
        }
        // A ribbon on the top-right corner of bookmarked pages.
        val marked = vm.bookmarks
        if (marked.isNotEmpty()) {
            val rw = 14.dp.toPx()
            val rh = 26.dp.toPx()
            for (bm in marked) {
                if (bm.page !in range) continue
                val r = vp.pageRect(bm.page)
                val x = r.right - rw - 10.dp.toPx()
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(x, r.top)
                    lineTo(x + rw, r.top)
                    lineTo(x + rw, r.top + rh)
                    lineTo(x + rw / 2f, r.top + rh - rw / 2f)
                    lineTo(x, r.top + rh)
                    close()
                }
                drawPath(path, colors.handle)
            }
        }
        // The sentence being read aloud.
        vm.readAloud?.let { ra ->
            if (ra.state != ReadAloud.State.IDLE && ra.page in range) {
                for (rc in ra.rects) {
                    val sr = vp.toScreen(ra.page, rc.left, rc.top, rc.right, rc.bottom).inflate(2f)
                    drawRect(colors.selection, sr.topLeft, sr.size)
                }
            }
        }
        if (selection != null && selection.page in range) {
            for (rc in selection.rects) {
                val sr = vp.toScreen(selection.page, rc.left, rc.top, rc.right, rc.bottom)
                drawRect(colors.selection, sr.topLeft, sr.size)
            }
        }
    }
}

private fun DrawScope.drawBitmap(img: ImageBitmap, dst: Rect, filter: androidx.compose.ui.graphics.ColorFilter?) {
    drawImage(
        image = img,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(img.width, img.height),
        dstOffset = IntOffset(dst.left.roundToInt(), dst.top.roundToInt()),
        dstSize = IntSize(dst.width.roundToInt().coerceAtLeast(1), dst.height.roundToInt().coerceAtLeast(1)),
        filterQuality = FilterQuality.Medium,
        colorFilter = filter,
    )
}
