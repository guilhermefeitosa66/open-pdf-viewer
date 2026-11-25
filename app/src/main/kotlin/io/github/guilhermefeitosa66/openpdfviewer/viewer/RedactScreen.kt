package io.github.guilhermefeitosa66.openpdfviewer.viewer

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.guilhermefeitosa66.openpdfviewer.R
import io.github.guilhermefeitosa66.openpdfviewer.tools.RedactPattern
import io.github.guilhermefeitosa66.openpdfviewer.tools.Redaction
import kotlinx.coroutines.launch

/** Mark areas or find text to redact, page by page, then apply. */
@Composable
fun RedactScreen(vm: ViewerViewModel, ready: DocState.Ready) {
    val doc = ready.doc
    val scope = rememberCoroutineScope()
    val marks = remember { mutableStateMapOf<Int, List<RectF>>() }
    var page by remember { mutableStateOf(ready.viewport.currentPage.coerceIn(0, doc.pageCount - 1)) }
    var query by remember { mutableStateOf("") }
    var pattern by remember { mutableStateOf<RedactPattern?>(null) }
    var lastFound by remember { mutableStateOf<Int?>(null) }
    var confirm by remember { mutableStateOf(false) }
    val total = marks.values.sumOf { it.size }

    fun findAll() {
        val q = pattern?.regex ?: query
        val isRegex = pattern != null
        scope.launch {
            var n = 0
            for (p in 0 until doc.pageCount) {
                val text = doc.text(p)
                val ranges = Redaction.find(text, q, isRegex)
                val add = ranges.flatMap { r -> doc.textRects(p, r.first, r.last - r.first + 1) }
                if (add.isEmpty()) continue
                // Pad a little so no edge of a glyph survives.
                val padded = add.map { RectF(it.left - 0.002f, it.top - 0.003f, it.right + 0.002f, it.bottom + 0.003f) }
                marks[p] = marks[p].orEmpty() + padded
                n += add.size
            }
            lastFound = n
            marks.keys.minOrNull()?.let { if (n > 0) page = it }
        }
    }

    Dialog(onDismissRequest = { vm.redactOpen = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.redactOpen = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                    Text(stringResource(R.string.redact_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(enabled = total > 0, onClick = { confirm = true }) { Text(stringResource(R.string.redact_apply)) }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it; pattern = null }, singleLine = true,
                        label = { Text(stringResource(R.string.redact_find)) }, modifier = Modifier.weight(1f),
                    )
                    Button(enabled = query.isNotBlank() || pattern != null, onClick = { findAll() }) { Text(stringResource(R.string.mark)) }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((p, label) in listOf(RedactPattern.CPF to "CPF", RedactPattern.CNPJ to "CNPJ", RedactPattern.EMAIL to "E-mail", RedactPattern.PHONE to stringResource(R.string.phone))) {
                        FilterChip(selected = pattern == p, onClick = { pattern = if (pattern == p) null else p }, label = { Text(label) })
                    }
                }
                Text(
                    lastFound?.let { pluralStringResource(R.plurals.redact_found, it, it) } ?: stringResource(R.string.redact_hint),
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                RedactPage(ready, page, marks[page].orEmpty(), Modifier.weight(1f).fillMaxWidth().padding(12.dp)) { marks[page] = it }
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    IconButton(onClick = { page-- }, enabled = page > 0) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.previous_page)) }
                    Text("${page + 1} / ${doc.pageCount}  ·  " + pluralStringResource(R.plurals.redact_marks, total, total))
                    IconButton(onClick = { page++ }, enabled = page < doc.pageCount - 1) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.next_page)) }
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.redact_apply)) },
            text = { Text(stringResource(R.string.redact_confirm, marks.count { it.value.isNotEmpty() })) },
            confirmButton = {
                Row {
                    TextButton(onClick = { confirm = false; vm.applyRedaction(marks.toMap(), asCopy = true) }) { Text(stringResource(R.string.save_as_copy)) }
                    TextButton(onClick = { confirm = false; vm.applyRedaction(marks.toMap(), asCopy = false) }) { Text(stringResource(R.string.save)) }
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** One page with its marks: drag to draw a box, tap a box to remove it. */
@Composable
private fun RedactPage(ready: DocState.Ready, page: Int, marks: List<RectF>, modifier: Modifier, onChange: (List<RectF>) -> Unit) {
    // Not named "size": that would hide the drawing and gesture scopes' own size.
    val pageSize = ready.doc.pageSizes[page]
    val aspect = (pageSize.width / pageSize.height).coerceIn(0.2f, 5f)
    var bitmap by remember(page) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(page) {
        val w = 1000
        val h = (w / aspect).toInt().coerceAtLeast(1)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        b.eraseColor(android.graphics.Color.WHITE)
        if (ready.doc.render(page, b, w, h)) bitmap = b
    }
    var drawing by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    val current by androidx.compose.runtime.rememberUpdatedState(marks)
    val accent = MaterialTheme.colorScheme.error
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier.then(if (maxWidth / maxHeight > aspect) Modifier.fillMaxSize().aspectRatio(aspect, matchHeightConstraintsFirst = true) else Modifier.fillMaxWidth().aspectRatio(aspect))
                .background(Color.White),
        ) {
            bitmap?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(page) {
                        detectTapGestures { pos ->
                            val x = pos.x / size.width
                            val y = pos.y / size.height
                            val hit = current.lastOrNull { it.contains(x, y) }
                            if (hit != null) onChange(current - hit)
                        }
                    }
                    .pointerInput(page) {
                        detectDragGestures(
                            onDragStart = { drawing = it to it },
                            onDragEnd = {
                                drawing?.let { (a, b) ->
                                    val r = RectF(
                                        minOf(a.x, b.x) / size.width, minOf(a.y, b.y) / size.height,
                                        maxOf(a.x, b.x) / size.width, maxOf(a.y, b.y) / size.height,
                                    )
                                    if (r.width() > 0.005f && r.height() > 0.003f) onChange(current + r)
                                }
                                drawing = null
                            },
                            onDragCancel = { drawing = null },
                        ) { change, _ ->
                            change.consume()
                            drawing = drawing?.let { it.first to change.position }
                        }
                    },
            ) {
                for (m in marks) {
                    drawRect(Color(0xCC000000), Offset(m.left * size.width, m.top * size.height), Size(m.width() * size.width, m.height() * size.height))
                    drawRect(accent, Offset(m.left * size.width, m.top * size.height), Size(m.width() * size.width, m.height() * size.height), style = Stroke(1.5.dp.toPx()))
                }
                drawing?.let { (a, b) ->
                    drawRect(accent.copy(alpha = 0.35f), Offset(minOf(a.x, b.x), minOf(a.y, b.y)), Size(kotlin.math.abs(a.x - b.x), kotlin.math.abs(a.y - b.y)))
                }
            }
        }
    }
}
