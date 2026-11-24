package io.github.guilhermefeitosa66.openpdfviewer.viewer

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.guilhermefeitosa66.openpdfviewer.R
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Crop the margins of one page or all: drag the corners or edges, or detect white margins. */
@Composable
fun CropScreen(vm: ViewerViewModel, ready: DocState.Ready, page: Int) {
    val size = ready.doc.pageSizes[page]
    val aspect = (size.width / size.height).coerceIn(0.2f, 5f)
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    // l, t, r, b, normalized to the page as shown.
    var box by remember { mutableStateOf(floatArrayOf(0f, 0f, 1f, 1f)) }
    var detected by remember { mutableStateOf(false) }
    var allPages by remember { mutableStateOf(false) }
    var saveMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val screenW = with(density) { 360.dp.roundToPx() }
    LaunchedEffect(page) {
        val w = (screenW * 1.5f).toInt()
        val h = (w / aspect).toInt().coerceAtLeast(1)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        b.eraseColor(android.graphics.Color.WHITE)
        if (ready.doc.render(page, b, w, h)) bitmap = b
    }
    val changed = box[0] > 0.001f || box[1] > 0.001f || box[2] < 0.999f || box[3] < 0.999f
    fun save(asCopy: Boolean) {
        val pages = if (allPages) (0 until ready.doc.pageCount).toList() else listOf(page)
        vm.applyCrop(pages, box, detectEach = detected && allPages, asCopy = asCopy)
    }

    Dialog(
        onDismissRequest = { vm.cropPage = null },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.cropPage = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                    Text(stringResource(R.string.crop_title, page + 1), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Box {
                        TextButton(onClick = { saveMenu = true }, enabled = changed || (detected && allPages)) { Text(stringResource(R.string.save)) }
                        DropdownMenu(expanded = saveMenu, onDismissRequest = { saveMenu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.save)) }, onClick = { saveMenu = false; save(false) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.save_as_copy)) }, onClick = { saveMenu = false; save(true) })
                        }
                    }
                }
                Text(
                    stringResource(if (detected && allPages) R.string.crop_hint_each else R.string.crop_hint),
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .then(if (maxWidth / maxHeight > aspect) Modifier.fillMaxSize().aspectRatio(aspect, matchHeightConstraintsFirst = true) else Modifier.fillMaxWidth().aspectRatio(aspect))
                            .background(Color.White),
                    ) {
                        bitmap?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
                        CropOverlay(box) { box = it; detected = false }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(selected = !allPages, onClick = { allPages = false }, label = { Text(stringResource(R.string.this_page)) })
                    FilterChip(selected = allPages, onClick = { allPages = true }, label = { Text(stringResource(R.string.all_pages)) })
                    Box(Modifier.weight(1f))
                    OutlinedButton(onClick = {
                        scope.launch {
                            vm.detectMargins(ready.doc, page)?.let { box = it; detected = true }
                        }
                    }) { Text(stringResource(R.string.detect_margins)) }
                }
                TextButton(
                    onClick = { box = floatArrayOf(0f, 0f, 1f, 1f); detected = false },
                    modifier = Modifier.align(Alignment.End).padding(end = 16.dp, bottom = 8.dp),
                ) { Text(stringResource(R.string.reset)) }
            }
        }
    }
}

/** Shades what is cut away; drag near a corner or edge to move it. */
@Composable
private fun CropOverlay(box: FloatArray, onChange: (FloatArray) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val touch = with(LocalDensity.current) { 32.dp.toPx() }
    var grab by remember { mutableStateOf(IntArray(0)) }
    // The gesture handler is created once; it reads the latest box through this.
    val current by androidx.compose.runtime.rememberUpdatedState(box)
    Canvas(
        Modifier.fillMaxSize().pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { pos ->
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    // Which edges are within reach: 0 left, 1 top, 2 right, 3 bottom.
                    val b = current
                    val near = buildList {
                        if (abs(pos.x - b[0] * w) < touch) add(0)
                        if (abs(pos.y - b[1] * h) < touch) add(1)
                        if (abs(pos.x - b[2] * w) < touch) add(2)
                        if (abs(pos.y - b[3] * h) < touch) add(3)
                    }
                    grab = near.toIntArray()
                },
                onDragEnd = { grab = IntArray(0) },
            ) { change, drag ->
                if (grab.isEmpty()) return@detectDragGestures
                change.consume()
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                val b = current.copyOf()
                val min = 0.05f
                for (e in grab) when (e) {
                    0 -> b[0] = (b[0] + drag.x / w).coerceIn(0f, b[2] - min)
                    1 -> b[1] = (b[1] + drag.y / h).coerceIn(0f, b[3] - min)
                    2 -> b[2] = (b[2] + drag.x / w).coerceIn(b[0] + min, 1f)
                    3 -> b[3] = (b[3] + drag.y / h).coerceIn(b[1] + min, 1f)
                }
                onChange(b)
            }
        },
    ) {
        val l = box[0] * size.width
        val t = box[1] * size.height
        val r = box[2] * size.width
        val b = box[3] * size.height
        val shade = Color(0x99000000)
        drawRect(shade, Offset.Zero, Size(size.width, t))
        drawRect(shade, Offset(0f, b), Size(size.width, size.height - b))
        drawRect(shade, Offset(0f, t), Size(l, b - t))
        drawRect(shade, Offset(r, t), Size(size.width - r, b - t))
        drawRect(accent, Offset(l, t), Size(r - l, b - t), style = Stroke(width = 2.dp.toPx()))
        for ((x, y) in listOf(l to t, r to t, l to b, r to b)) drawCircle(accent, 8.dp.toPx(), Offset(x, y))
    }
}
