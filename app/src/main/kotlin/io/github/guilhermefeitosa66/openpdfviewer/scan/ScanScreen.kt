package io.github.guilhermefeitosa66.openpdfviewer.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import io.github.guilhermefeitosa66.openpdfviewer.R
import io.github.guilhermefeitosa66.openpdfviewer.data.DocumentSource
import io.github.guilhermefeitosa66.openpdfviewer.tools.ImagePageOptions
import io.github.guilhermefeitosa66.openpdfviewer.tools.ImagesToPdf
import io.github.guilhermefeitosa66.openpdfviewer.ui.CreateTypedDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class ScanFilter { ORIGINAL, GRAY, DOCUMENT }

/** A photo being straightened: the decoded image and the page corners on it. */
private class Capture(val bitmap: Bitmap, corners: FloatArray) {
    var corners by mutableStateOf(corners)
}

/**
 * Scan to PDF: take photos with the system camera app (the app itself has no camera
 * permission) or pick them from the gallery; the page is found and straightened, then cleaned
 * up with a filter. Several pages make one PDF.
 */
@Composable
fun ScanScreen(onDismiss: () -> Unit, onOpen: (Uri) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dir = remember { File(context.cacheDir, "scan").apply { mkdirs() } }
    val pages = remember { mutableStateListOf<Pair<File, ImageBitmap>>() }
    var capture by remember { mutableStateOf<Capture?>(null) }
    var filter by remember { mutableStateOf(ScanFilter.DOCUMENT) }
    var busy by remember { mutableStateOf(false) }
    var photoFile by remember { mutableStateOf<File?>(null) }
    val failed = stringResource(R.string.images_failed)

    fun load(uri: Uri) {
        busy = true
        scope.launch {
            val bmp = decode(context, uri, 2500)
            capture = bmp?.let { Capture(it, withContext(Dispatchers.Default) { detect(it) }) }
            busy = false
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = photoFile
        if (ok && f != null) load(Uri.fromFile(f))
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) load(uri) }
    val saver = rememberLauncherForActivityResult(CreateTypedDocument()) { target ->
        if (target == null) return@rememberLauncherForActivityResult
        busy = true
        val files = pages.map { it.first }
        scope.launch {
            val ok = runCatching {
                val out = File(dir, "scan.pdf")
                ImagesToPdf.write(context, files.size, { i ->
                    withContext(Dispatchers.IO) { android.graphics.BitmapFactory.decodeFile(files[i].path) }
                }, ImagePageOptions(ImagePageOptions.A4), out)
                DocumentSource.overwrite(context, target, out)
            }.onFailure { android.util.Log.w("OpenPdfViewer", "scan to pdf failed", it) }.getOrDefault(false)
            busy = false
            if (ok) {
                DocumentSource.persistAccess(context, target)
                onOpen(target)
            } else Toast.makeText(context, failed, Toast.LENGTH_LONG).show()
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (capture != null) capture = null else onDismiss() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                    Text(stringResource(R.string.scan_to_pdf), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp))
                val cap = capture
                if (cap != null) {
                    Text(stringResource(R.string.scan_adjust_hint), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
                    CornerEditor(cap, Modifier.weight(1f).fillMaxWidth().padding(16.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((f, label) in listOf(ScanFilter.ORIGINAL to R.string.filter_original, ScanFilter.GRAY to R.string.filter_gray, ScanFilter.DOCUMENT to R.string.filter_document)) {
                            FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(stringResource(label)) })
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                        TextButton(onClick = { cap.corners = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f) }) { Text(stringResource(R.string.whole_photo)) }
                        Button(enabled = !busy, onClick = {
                            busy = true
                            scope.launch {
                                val page = withContext(Dispatchers.Default) { process(cap.bitmap, cap.corners, filter) }
                                val f = File(dir, "page-${System.currentTimeMillis()}.jpg")
                                withContext(Dispatchers.IO) { f.outputStream().buffered().use { page.compress(Bitmap.CompressFormat.JPEG, 88, it) } }
                                val thumb = Bitmap.createScaledBitmap(page, 160, (160f * page.height / page.width).toInt().coerceAtLeast(1), true)
                                page.recycle()
                                pages += f to thumb.asImageBitmap()
                                capture = null
                                busy = false
                            }
                        }) { Text(stringResource(R.string.add_page)) }
                    }
                } else {
                    Text(
                        if (pages.isEmpty()) stringResource(R.string.scan_hint) else stringResource(R.string.scan_pages, pages.size),
                        fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp),
                    )
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pages.forEachIndexed { i, (_, thumb) ->
                            Box {
                                Image(thumb, null, modifier = Modifier.width(96.dp).background(Color.White), contentScale = ContentScale.FillWidth)
                                IconButton(onClick = { pages.removeAt(i) }, modifier = Modifier.align(Alignment.TopEnd)) {
                                    Icon(Icons.Filled.Close, stringResource(R.string.delete), tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                    Box(Modifier.weight(1f))
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                        OutlinedButton(enabled = !busy, onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                            Text(stringResource(R.string.from_gallery))
                        }
                        OutlinedButton(enabled = !busy, onClick = {
                            val f = File(dir, "photo.jpg").also { photoFile = it }
                            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
                            runCatching { camera.launch(uri) }.onFailure { Toast.makeText(context, R.string.no_camera_app, Toast.LENGTH_LONG).show() }
                        }) { Text(stringResource(R.string.take_photo)) }
                    }
                    Button(
                        enabled = pages.isNotEmpty() && !busy,
                        onClick = { saver.launch(io.github.guilhermefeitosa66.openpdfviewer.ui.FileActions.safeName(context.getString(R.string.scan_name)) to "application/pdf") },
                        modifier = Modifier.align(Alignment.End).padding(end = 16.dp, bottom = 16.dp),
                    ) { Text(stringResource(R.string.create_pdf)) }
                }
            }
        }
    }
}

/** The photo with its four corner handles; drag a handle to move that corner. */
@Composable
private fun CornerEditor(cap: Capture, modifier: Modifier) {
    val img = remember(cap.bitmap) { cap.bitmap.asImageBitmap() }
    val aspect = cap.bitmap.width.toFloat() / cap.bitmap.height
    val accent = MaterialTheme.colorScheme.primary
    val touch = with(LocalDensity.current) { 40.dp.toPx() }
    val corners by rememberUpdatedState(cap.corners)
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        Box(Modifier.then(if (maxWidth / maxHeight > aspect) Modifier.fillMaxSize().aspectRatio(aspect, matchHeightConstraintsFirst = true) else Modifier.fillMaxWidth().aspectRatio(aspect))) {
            Image(img, null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            var grabbed by remember { mutableStateOf(-1) }
            ComposeCanvas(Modifier.fillMaxSize().pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { pos ->
                        val c = corners
                        grabbed = (0 until 4).minByOrNull { i ->
                            val dx = c[i * 2] * size.width - pos.x
                            val dy = c[i * 2 + 1] * size.height - pos.y
                            dx * dx + dy * dy
                        }?.takeIf { i ->
                            val dx = c[i * 2] * size.width - pos.x
                            val dy = c[i * 2 + 1] * size.height - pos.y
                            dx * dx + dy * dy < touch * touch * 4
                        } ?: -1
                    },
                    onDragEnd = { grabbed = -1 },
                ) { change, drag ->
                    val i = grabbed
                    if (i < 0) return@detectDragGestures
                    change.consume()
                    val c = corners.copyOf()
                    c[i * 2] = (c[i * 2] + drag.x / size.width).coerceIn(0f, 1f)
                    c[i * 2 + 1] = (c[i * 2 + 1] + drag.y / size.height).coerceIn(0f, 1f)
                    cap.corners = c
                }
            }) {
                val c = cap.corners
                val pts = (0 until 4).map { Offset(c[it * 2] * size.width, c[it * 2 + 1] * size.height) }
                val path = Path().apply { moveTo(pts[0].x, pts[0].y); for (p in pts.drop(1)) lineTo(p.x, p.y); close() }
                drawPath(path, accent.copy(alpha = 0.18f))
                drawPath(path, accent, style = Stroke(width = 2.dp.toPx()))
                for (p in pts) drawCircle(accent, 10.dp.toPx(), p)
            }
        }
    }
}

/** Page corners found in [bmp], or the whole image when nothing is found. */
private fun detect(bmp: Bitmap): FloatArray {
    val scale = 400f / maxOf(bmp.width, bmp.height)
    val small = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(8), (bmp.height * scale).toInt().coerceAtLeast(8), true)
    val px = IntArray(small.width * small.height)
    small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
    val gray = IntArray(px.size) { (((px[it] shr 16) and 0xFF) * 299 + ((px[it] shr 8) and 0xFF) * 587 + (px[it] and 0xFF) * 114) / 1000 }
    val found = DocumentDetector.detect(gray, small.width, small.height)
    if (small !== bmp) small.recycle()
    return found ?: floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
}

/** Straightens the page between the corners and applies the filter. */
private fun process(src: Bitmap, corners: FloatArray, filter: ScanFilter): Bitmap {
    var (ow, oh) = DocumentDetector.outputSize(corners, src.width, src.height)
    val cap = 2500f / maxOf(ow, oh)
    if (cap < 1f) { ow = (ow * cap).toInt(); oh = (oh * cap).toInt() }
    val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
    val srcPts = FloatArray(8) { i -> corners[i] * if (i % 2 == 0) src.width else src.height }
    val dst = floatArrayOf(0f, 0f, ow.toFloat(), 0f, ow.toFloat(), oh.toFloat(), 0f, oh.toFloat())
    val m = Matrix().apply { setPolyToPoly(srcPts, 0, dst, 0, 4) }
    val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    if (filter == ScanFilter.GRAY) paint.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
    Canvas(out).apply {
        drawColor(android.graphics.Color.WHITE)
        drawBitmap(src, m, paint)
    }
    if (filter == ScanFilter.DOCUMENT) {
        val px = IntArray(ow * oh)
        out.getPixels(px, 0, ow, 0, 0, ow, oh)
        val gray = IntArray(px.size) { (((px[it] shr 16) and 0xFF) * 299 + ((px[it] shr 8) and 0xFF) * 587 + (px[it] and 0xFF) * 114) / 1000 }
        val bw = DocumentDetector.adaptiveThreshold(gray, ow, oh)
        for (i in px.indices) px[i] = if (bw[i] == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        out.setPixels(px, 0, ow, 0, 0, ow, oh)
    }
    return out
}

private suspend fun decode(context: Context, uri: Uri, maxSide: Int): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        if (Build.VERSION.SDK_INT >= 28) {
            val source = if (uri.scheme == "file") ImageDecoder.createSource(File(uri.path!!)) else ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { d, info, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val side = maxOf(info.size.width, info.size.height)
                if (side > maxSide) d.setTargetSize(info.size.width * maxSide / side, info.size.height * maxSide / side)
            }.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            @Suppress("DEPRECATION") MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }
    }.getOrNull()
}
