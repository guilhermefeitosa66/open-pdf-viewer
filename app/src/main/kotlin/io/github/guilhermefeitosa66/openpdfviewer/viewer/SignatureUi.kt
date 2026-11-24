package io.github.guilhermefeitosa66.openpdfviewer.viewer

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.guilhermefeitosa66.openpdfviewer.R
import io.github.guilhermefeitosa66.openpdfviewer.data.Signatures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** Saved signatures (up to three), with ways to add one by drawing or from a photo. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignatureSheet(vm: ViewerViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { Signatures(context) }
    val files = remember { mutableStateListOf<Pair<File, ImageBitmap>>() }
    var reload by remember { mutableStateOf(0) }
    var drawing by remember { mutableStateOf(false) }
    val noInk = stringResource(R.string.signature_no_ink)
    LaunchedEffect(reload) {
        files.clear()
        for (f in store.list()) store.load(f)?.let { files += f to it.asImageBitmap() }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val cleaned = withContext(Dispatchers.Default) {
                runCatching { Signatures.removeBackground(decode(context, uri)) }.getOrNull()
            }
            if (cleaned == null) Toast.makeText(context, noInk, Toast.LENGTH_LONG).show()
            else if (store.add(cleaned)) reload++
        }
    }
    ModalBottomSheet(
        onDismissRequest = { vm.signatureSheetOpen = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.signature), style = MaterialTheme.typography.titleLarge)
            if (files.isEmpty()) {
                Text(stringResource(R.string.no_signatures), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            }
            for ((file, img) in files) {
                Row(
                    Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                        .background(Color.White, RoundedCornerShape(12.dp))
                        .clickable {
                            scope.launch {
                                store.load(file)?.let { vm.placingSignature = it }
                                vm.signatureSheetOpen = false
                            }
                        }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(img, stringResource(R.string.place_signature), modifier = Modifier.weight(1f).height(64.dp), contentScale = ContentScale.Fit)
                    IconButton(onClick = { scope.launch { store.delete(file); reload++ } }) {
                        Icon(Icons.Filled.Delete, stringResource(R.string.delete), tint = Color(0xFF6B5242))
                    }
                }
            }
            if (files.size < Signatures.MAX) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { drawing = true }) { Text(stringResource(R.string.draw_signature)) }
                    OutlinedButton(onClick = { importer.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Text(stringResource(R.string.import_signature))
                    }
                }
            } else {
                Text(stringResource(R.string.signatures_full), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (drawing) {
        SignaturePad(
            onSave = { bmp ->
                drawing = false
                scope.launch { if (store.add(bmp)) reload++ }
            },
            onDismiss = { drawing = false },
        )
    }
}

private fun decode(context: android.content.Context, uri: Uri): Bitmap =
    if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val max = 2000
            val side = maxOf(info.size.width, info.size.height)
            if (side > max) d.setTargetSize(info.size.width * max / side, info.size.height * max / side)
        }
    } else {
        @Suppress("DEPRECATION") MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
    }.copy(Bitmap.Config.ARGB_8888, false)

/** A white pad to sign with a finger or stylus; saved as a transparent image cropped to the ink. */
@Composable
private fun SignaturePad(onSave: (Bitmap) -> Unit, onDismiss: () -> Unit) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    val density = LocalDensity.current
    val widthPx = with(density) { 3.dp.toPx() }
    var padSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.draw_signature), style = MaterialTheme.typography.titleMedium)
                Canvas(
                    Modifier.fillMaxWidth().height(220.dp).background(Color.White, RoundedCornerShape(12.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                        .pointerInput(Unit) {
                            padSize = size
                            detectDragGestures(
                                onDragStart = { current = listOf(it) },
                                onDragEnd = { strokes += current; current = emptyList() },
                                onDragCancel = { strokes += current; current = emptyList() },
                            ) { change, _ -> current = current + change.position }
                        },
                ) {
                    val style = Stroke(width = widthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    for (s in strokes + listOf(current)) {
                        if (s.size < 2) continue
                        val path = Path().apply { moveTo(s[0].x, s[0].y); s.drop(1).forEach { lineTo(it.x, it.y) } }
                        drawPath(path, Color(0xFF1A1A1A), style = style)
                    }
                    drawLine(Color(0xFFCCCCCC), Offset(24f, size.height * 0.75f), Offset(size.width - 24f, size.height * 0.75f), strokeWidth = 2f)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { strokes.clear() }) { Text(stringResource(R.string.clear)) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Button(
                        enabled = strokes.isNotEmpty(),
                        onClick = { render(strokes, padSize.width, padSize.height, widthPx)?.let(onSave) },
                    ) { Text(stringResource(R.string.save)) }
                }
            }
        }
    }
}

/** Draws the strokes into a transparent bitmap cropped to the ink. */
private fun render(strokes: List<List<Offset>>, w: Int, h: Int, width: Float): Bitmap? {
    if (w <= 0 || h <= 0) return null
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1A1A1A.toInt()
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = width
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    var minX = w.toFloat(); var minY = h.toFloat(); var maxX = 0f; var maxY = 0f
    for (s in strokes) {
        if (s.size < 2) continue
        val p = android.graphics.Path().apply { moveTo(s[0].x, s[0].y); s.drop(1).forEach { lineTo(it.x, it.y) } }
        c.drawPath(p, paint)
        s.forEach { minX = minOf(minX, it.x); minY = minOf(minY, it.y); maxX = maxOf(maxX, it.x); maxY = maxOf(maxY, it.y) }
    }
    if (maxX <= minX || maxY <= minY) return null
    val pad = width.roundToInt() + 2
    val x0 = (minX.roundToInt() - pad).coerceAtLeast(0)
    val y0 = (minY.roundToInt() - pad).coerceAtLeast(0)
    val x1 = (maxX.roundToInt() + pad).coerceAtMost(w)
    val y1 = (maxY.roundToInt() + pad).coerceAtMost(h)
    return Bitmap.createBitmap(bmp, x0, y0, x1 - x0, y1 - y0)
}

/** The signature over the page: drag to move, drag the corner to resize, then Place. */
@Composable
fun BoxScope.SignaturePlacer(bitmap: Bitmap, vp: Viewport, onPlace: (Rect) -> Unit, onCancel: () -> Unit) {
    val img = remember(bitmap) { bitmap.asImageBitmap() }
    val aspect = bitmap.height.toFloat() / bitmap.width
    var width by remember { mutableStateOf(vp.width * 0.45f) }
    var topLeft by remember { mutableStateOf(Offset(vp.width * 0.275f, vp.height * 0.45f)) }
    val height = width * aspect
    val density = LocalDensity.current
    val handle = with(density) { 28.dp.toPx() }
    Box(
        Modifier
            .offset { IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()) }
            .size(with(density) { width.toDp() }, with(density) { height.toDp() })
            .border(2.dp, MaterialTheme.colorScheme.primary)
            .pointerInput(Unit) { detectDragGestures { change, drag -> change.consume(); topLeft += drag } },
    ) {
        Image(img, stringResource(R.string.place_signature), modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
    }
    // Resize handle at the bottom-right corner (keeps the proportions).
    Box(
        Modifier
            .offset { IntOffset((topLeft.x + width - handle / 2).roundToInt(), (topLeft.y + height - handle / 2).roundToInt()) }
            .size(with(density) { handle.toDp() })
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    width = (width + drag.x).coerceIn(handle * 2, vp.width)
                }
            },
    )
    Row(
        Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 84.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        Button(onClick = { onPlace(Rect(topLeft, androidx.compose.ui.geometry.Size(width, height))) }) { Text(stringResource(R.string.place_signature)) }
    }
}
