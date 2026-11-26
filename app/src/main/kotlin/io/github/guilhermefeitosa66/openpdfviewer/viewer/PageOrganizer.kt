package io.github.guilhermefeitosa66.openpdfviewer.viewer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.guilhermefeitosa66.openpdfviewer.R
import io.github.guilhermefeitosa66.openpdfviewer.data.DocumentSource
import io.github.guilhermefeitosa66.openpdfviewer.pdfium.PdfDocument
import io.github.guilhermefeitosa66.openpdfviewer.pdfium.PdfOpenException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import io.github.guilhermefeitosa66.openpdfviewer.ui.AppIcons

/**
 * One page in the organizer: the page it shows (an original index, or pageCount + the position
 * of an added page in the extras list) and the quarter turns added.
 */
private data class OrgPage(val id: Int, val source: Int, val turns: Int = 0)

/**
 * Pages in a grid to reorganize: tap to select, long-press and drag to move, rotate, duplicate
 * or delete the selection, undo. Nothing changes in the file until Save.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PageOrganizer(vm: ViewerViewModel, ready: DocState.Ready) {
    val total = ready.doc.pageCount
    val pages = remember { mutableStateListOf<OrgPage>().apply { addAll(List(total) { OrgPage(it, it) }) } }
    var nextId by remember { mutableStateOf(total) }
    val selected = remember { mutableStateListOf<Int>() }
    val extras = remember { mutableStateListOf<ViewerViewModel.InsertedPage>() }
    var insertMenu by remember { mutableStateOf(false) }
    var blankDialog by remember { mutableStateOf(false) }
    var splitDialog by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf<ImportState?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importing = ImportState(uri, DocumentSource.displayName(context, uri))
    }
    val history = remember { ArrayDeque<List<OrgPage>>() }
    var canUndo by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<Int?>(null) }
    var askLeave by remember { mutableStateOf(false) }
    var saveMenu by remember { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    val changed = canUndo
    fun snapshot() {
        history.addLast(pages.toList())
        if (history.size > 50) history.removeFirst()
        canUndo = true
    }
    fun undo() {
        val prev = history.removeLastOrNull() ?: return
        pages.clear(); pages.addAll(prev)
        selected.retainAll(prev.map { it.id }.toSet())
        canUndo = history.isNotEmpty()
    }
    fun close() { vm.organizeOpen = false }
    fun save(asCopy: Boolean) {
        vm.applyOrganize(pages.map { it.source }.toIntArray(), pages.map { it.turns }.toIntArray(), extras.toList(), asCopy)
    }
    /** Inserts new pages after the last selected one, or at the end. */
    fun insert(added: List<ViewerViewModel.InsertedPage>) {
        if (added.isEmpty()) return
        snapshot()
        val at = pages.indexOfLast { it.id in selected }.let { if (it < 0) pages.size else it + 1 }
        val items = added.map { e ->
            extras += e
            OrgPage(nextId++, total + extras.size - 1)
        }
        pages.addAll(at, items)
        selected.clear()
        selected.addAll(items.map { it.id })
    }
    /** Size of the original page before the insertion point, for "same size". */
    fun neighbourSize(): Pair<Float, Float> {
        val at = pages.indexOfLast { it.id in selected }.let { if (it < 0) pages.size - 1 else it }
        val src = (at downTo 0).map { pages[it].source }.firstOrNull { it < total } ?: 0
        val sz = ready.doc.pageSizes[src]
        return sz.width to sz.height
    }

    Dialog(
        onDismissRequest = { if (changed) askLeave = true else close() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (changed) askLeave = true else close() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                    Text(
                        if (selected.isEmpty()) stringResource(R.string.organize_pages) else stringResource(R.string.n_selected, selected.size),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Box {
                        IconButton(onClick = { insertMenu = true }) { Icon(Icons.Filled.Add, stringResource(R.string.insert_pages)) }
                        DropdownMenu(expanded = insertMenu, onDismissRequest = { insertMenu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.insert_blank)) }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.NoteAdd, null) }, onClick = { insertMenu = false; blankDialog = true })
                            DropdownMenuItem(text = { Text(stringResource(R.string.insert_from_pdf)) }, leadingIcon = { Icon(Icons.Outlined.PostAdd, null) }, onClick = {
                                insertMenu = false
                                picker.launch(arrayOf("application/pdf"))
                            })
                        }
                    }
                    IconButton(onClick = { undo() }, enabled = canUndo) { Icon(AppIcons.Undo, stringResource(R.string.undo)) }
                    Box {
                        IconButton(onClick = { moreMenu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more_options)) }
                        DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                            // Split works on the document as saved, so it waits for page changes to be saved.
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.split)) }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.CallSplit, null) },
                                enabled = !changed,
                                onClick = { moreMenu = false; splitDialog = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.crop_margins)) }, leadingIcon = { Icon(Icons.Outlined.Crop, null) },
                                enabled = !changed,
                                onClick = {
                                    moreMenu = false
                                    val first = pages.firstOrNull { it.id in selected && it.source < total }?.source
                                    vm.organizeOpen = false
                                    vm.cropPage = first ?: ready.viewport.currentPage
                                },
                            )
                        }
                    }
                    Box {
                        TextButton(onClick = { saveMenu = true }, enabled = changed) { Text(stringResource(R.string.save)) }
                        DropdownMenu(expanded = saveMenu, onDismissRequest = { saveMenu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.save)) }, leadingIcon = { Icon(Icons.Outlined.Save, null) }, onClick = { saveMenu = false; save(false) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.save_as_copy)) }, leadingIcon = { Icon(Icons.Outlined.SaveAs, null) }, onClick = { saveMenu = false; save(true) })
                        }
                    }
                }
                Text(
                    stringResource(R.string.organize_hint),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(104.dp),
                    state = grid,
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) {
                        // Long-press a page and drag it: the list reorders under the finger.
                        fun keyAt(pos: Offset): Int? = grid.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                            pos.x >= info.offset.x && pos.x < info.offset.x + info.size.width &&
                                pos.y >= info.offset.y && pos.y < info.offset.y + info.size.height
                        }?.key as? Int
                        // After the long press, events are taken in the Initial pass, before the
                        // grid's own scrolling can claim the movement.
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val long = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                            val id = keyAt(long.position) ?: return@awaitEachGesture
                            snapshot()
                            dragging = id
                            try {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == long.id } ?: break
                                    if (!change.pressed) {
                                        change.consume()
                                        break
                                    }
                                    change.consume()
                                    val over = keyAt(change.position) ?: continue
                                    if (over == id) continue
                                    val from = pages.indexOfFirst { it.id == id }
                                    val to = pages.indexOfFirst { it.id == over }
                                    if (from >= 0 && to >= 0) pages.add(to, pages.removeAt(from))
                                }
                            } finally {
                                dragging = null
                            }
                        }
                    },
                ) {
                    itemsIndexed(pages, key = { _, p -> p.id }) { i, p ->
                        OrgThumb(
                            p, i, ready, extras.getOrNull(p.source - total),
                            selected = p.id in selected,
                            dragging = p.id == dragging,
                            modifier = Modifier.animateItem(),
                            onClick = { if (p.id in selected) selected.remove(p.id) else selected.add(p.id) },
                        )
                    }
                }
                // Actions on the selection.
                val has = selected.isNotEmpty()
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    ActionButton(AppIcons.SelectAll, stringResource(R.string.select_all), true) {
                        if (selected.size == pages.size) selected.clear() else { selected.clear(); selected.addAll(pages.map { it.id }) }
                    }
                    ActionButton(AppIcons.RotateLeft, stringResource(R.string.rotate_left), has) {
                        snapshot()
                        for (k in pages.indices) if (pages[k].id in selected) pages[k] = pages[k].copy(turns = (pages[k].turns + 3) % 4)
                    }
                    ActionButton(AppIcons.RotateRight, stringResource(R.string.rotate_right), has) {
                        snapshot()
                        for (k in pages.indices) if (pages[k].id in selected) pages[k] = pages[k].copy(turns = (pages[k].turns + 1) % 4)
                    }
                    ActionButton(AppIcons.Copy, stringResource(R.string.duplicate), has) {
                        snapshot()
                        val out = ArrayList<OrgPage>()
                        for (pg in pages) {
                            out += pg
                            if (pg.id in selected) out += pg.copy(id = nextId++)
                        }
                        pages.clear(); pages.addAll(out)
                    }
                    ActionButton(AppIcons.Download, stringResource(R.string.extract), has) {
                        val chosen = pages.filter { it.id in selected }
                        vm.applyOrganize(chosen.map { it.source }.toIntArray(), chosen.map { it.turns }.toIntArray(), extras.toList(), asCopy = true, extract = true)
                    }
                    ActionButton(Icons.Filled.Delete, stringResource(R.string.delete), has && selected.size < pages.size) {
                        snapshot()
                        pages.removeAll { it.id in selected }
                        selected.clear()
                    }
                }
            }
        }
    }
    if (blankDialog) {
        BlankPageDialog(
            onPick = { w, h -> blankDialog = false; insert(listOf(ViewerViewModel.InsertedPage.Blank(w, h))) },
            same = neighbourSize(),
            onDismiss = { blankDialog = false },
        )
    }
    importing?.let { st ->
        ImportDialog(
            st,
            onUpdate = { importing = it },
            onInsert = { indices ->
                importing = null
                insert(indices.map { ViewerViewModel.InsertedPage.FromFile(st.uri, st.password, it, st.name) })
            },
            onDismiss = { importing = null },
            scope = scope,
        )
    }
    if (splitDialog) SplitDialog(total, onSplit = { mode, folder -> splitDialog = false; vm.split(mode, folder) }, onDismiss = { splitDialog = false })
    if (askLeave) {
        AlertDialog(
            onDismissRequest = { askLeave = false },
            title = { Text(stringResource(R.string.organize_discard_title)) },
            confirmButton = { TextButton(onClick = { askLeave = false; close() }) { Text(stringResource(R.string.discard)) } },
            dismissButton = { TextButton(onClick = { askLeave = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun ActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, enabled = enabled) { Icon(icon, label) }
        Text(
            label, fontSize = 11.sp,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OrgThumb(
    p: OrgPage,
    position: Int,
    ready: DocState.Ready,
    extra: ViewerViewModel.InsertedPage?,
    selected: Boolean,
    dragging: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val renderer = ready.renderer
    val widthPx = with(LocalDensity.current) { 120.dp.roundToPx() }
    val original = extra == null
    DisposableEffect(p.source) {
        if (original) renderer.requestThumb(p.source, widthPx)
        onDispose { if (original) renderer.cancelThumb(p.source) }
    }
    renderer.version.intValue
    val img = if (original) renderer.thumb(p.source) else null
    val aspect = when (extra) {
        null -> ready.doc.pageSizes[p.source].let { it.width / it.height }
        is ViewerViewModel.InsertedPage.Blank -> extra.width / extra.height
        else -> 0.7071f
    }.coerceIn(0.3f, 3f)
    val odd = p.turns % 2 == 1
    val shown = if (odd) 1f / aspect else aspect
    val accent = MaterialTheme.colorScheme.primary
    val label = stringResource(R.string.page_n, position + 1)
    val paper = io.github.guilhermefeitosa66.openpdfviewer.ui.ThemeSettings.get(LocalContext.current).pageMode
    Column(
        modifier
            .graphicsLayer { if (dragging) { scaleX = 1.08f; scaleY = 1.08f; alpha = 0.85f } }
            .combinedClickable(onClick = onClick)
            .semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(shown)
                .then(if (selected) Modifier.border(3.dp, accent, RoundedCornerShape(4.dp)).padding(5.dp) else Modifier)
                .clip(RoundedCornerShape(3.dp))
                .background(paper.paper),
            contentAlignment = Alignment.Center,
        ) {
            if (img != null) {
                // Rotated pages: turn the image and scale it so it fits the swapped box.
                val scale = if (odd) minOf(aspect, 1f / aspect) else 1f
                Image(
                    img, contentDescription = null, contentScale = ContentScale.Fit, colorFilter = paper.filter,
                    modifier = Modifier.fillMaxSize().graphicsLayer { rotationZ = p.turns * 90f; scaleX = scale; scaleY = scale },
                )
            }
            if (extra != null) {
                Text(
                    when (extra) {
                        is ViewerViewModel.InsertedPage.Blank -> stringResource(R.string.blank_page)
                        is ViewerViewModel.InsertedPage.FromFile -> stringResource(R.string.page_of_file, extra.index + 1, extra.name)
                    },
                    fontSize = 11.sp, color = Color(0xFF555555), textAlign = TextAlign.Center,
                    modifier = Modifier.padding(6.dp).graphicsLayer { rotationZ = p.turns * 90f },
                )
            }
            if (selected) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).background(accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
            }
        }
        Text(
            if (p.source == position || extra != null) "${position + 1}" else "${position + 1} · ${p.source + 1}",
            modifier = Modifier.padding(top = 6.dp),
            fontSize = 13.sp,
            color = if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A PDF chosen to take pages from: its page count once open, or the need for a password. */
private data class ImportState(
    val uri: android.net.Uri,
    val name: String,
    val password: String? = null,
    val pageCount: Int = -1,
    val needsPassword: Boolean = false,
    val wrongPassword: Boolean = false,
    val failed: Boolean = false,
)

@Composable
private fun BlankPageDialog(onPick: (Float, Float) -> Unit, same: Pair<Float, Float>, onDismiss: () -> Unit) {
    val options = listOf(
        stringResource(R.string.size_same) to same,
        "A4" to (595.28f to 841.89f),
        stringResource(R.string.size_letter) to (612f to 792f),
    )
    var choice by remember { mutableStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.insert_blank)) },
        text = {
            Column {
                options.forEachIndexed { i, (label, _) ->
                    Row(Modifier.fillMaxWidth().clickable { choice = i }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = choice == i, onClick = { choice = i })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { val (w, h) = options[choice].second; onPick(w, h) }) { Text(stringResource(R.string.insert)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ImportDialog(
    st: ImportState,
    onUpdate: (ImportState) -> Unit,
    onInsert: (List<Int>) -> Unit,
    onDismiss: () -> Unit,
    scope: CoroutineScope,
) {
    val context = LocalContext.current
    // Opens the file to count its pages (and to learn whether it needs a password).
    LaunchedEffect(st.uri, st.password) {
        if (st.pageCount >= 0 || (st.needsPassword && st.password == null)) return@LaunchedEffect
        val result = runCatching {
            DocumentSource.open(context, st.uri).use { pfd ->
                val doc = PdfDocument.open(pfd, st.password)
                try { doc.pageCount } finally { doc.closeAsync() }
            }
        }
        val e = result.exceptionOrNull()
        onUpdate(
            when {
                e == null -> st.copy(pageCount = result.getOrThrow(), needsPassword = false, wrongPassword = false)
                e is PdfOpenException && e.reason == PdfOpenException.Reason.PASSWORD ->
                    st.copy(needsPassword = true, wrongPassword = st.password != null, password = null)
                else -> st.copy(failed = true)
            }
        )
    }
    var text by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.insert_from_pdf)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(st.name, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                when {
                    st.failed -> Text(stringResource(R.string.cannot_open_pdf), color = MaterialTheme.colorScheme.error)
                    st.needsPassword -> OutlinedTextField(
                        value = pw, onValueChange = { pw = it }, singleLine = true,
                        label = { Text(stringResource(R.string.password)) },
                        isError = st.wrongPassword,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    st.pageCount < 0 -> androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                    else -> {
                        val valid = PageRanges.parse(text, st.pageCount) != null
                        OutlinedTextField(
                            value = text, onValueChange = { text = it.take(80) }, singleLine = true,
                            label = { Text(stringResource(R.string.pages_to_insert)) },
                            placeholder = { Text(stringResource(R.string.all_pages_n, st.pageCount)) },
                            isError = !valid,
                            supportingText = { Text(stringResource(R.string.range_hint)) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            when {
                st.needsPassword -> TextButton(onClick = { onUpdate(st.copy(password = pw)) }) { Text(stringResource(R.string.open)) }
                st.pageCount >= 0 -> TextButton(
                    enabled = PageRanges.parse(text, st.pageCount) != null,
                    onClick = { PageRanges.parse(text, st.pageCount)?.let(onInsert) },
                ) { Text(stringResource(R.string.insert)) }
                else -> {}
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SplitDialog(pageCount: Int, onSplit: (SplitMode, android.net.Uri?) -> Unit, onDismiss: () -> Unit) {
    var choice by remember { mutableStateOf(0) }
    var n by remember { mutableStateOf("1") }
    var ranges by remember { mutableStateOf("") }
    var mb by remember { mutableStateOf("5") }
    val mode: SplitMode? = when (choice) {
        0 -> n.toIntOrNull()?.takeIf { it in 1..pageCount }?.let { SplitMode.EveryN(it) }
        1 -> SplitMode.Ranges(ranges).takeIf { SplitMode.groups(it, pageCount) != null }
        2 -> SplitMode.EachPage
        else -> mb.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }?.let { SplitMode.MaxSize((it * 1024 * 1024).toLong()) }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null && mode != null) onSplit(mode, tree)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.split)) },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                @Composable
                fun option(i: Int, label: String, field: (@Composable () -> Unit)? = null) {
                    Row(Modifier.fillMaxWidth().clickable { choice = i }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = choice == i, onClick = { choice = i })
                        Text(label, modifier = Modifier.weight(1f))
                    }
                    if (choice == i && field != null) Box(Modifier.padding(start = 48.dp, bottom = 8.dp)) { field() }
                }
                option(0, stringResource(R.string.split_every_n)) {
                    OutlinedTextField(
                        value = n, onValueChange = { n = it.filter(Char::isDigit).take(5) }, singleLine = true,
                        label = { Text(stringResource(R.string.pages_per_file)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                option(1, stringResource(R.string.split_ranges)) {
                    OutlinedTextField(
                        value = ranges, onValueChange = { ranges = it.take(200) }, singleLine = true,
                        label = { Text(stringResource(R.string.pages_to_insert)) },
                        supportingText = { Text(stringResource(R.string.split_ranges_hint)) },
                        isError = ranges.isNotBlank() && mode == null,
                    )
                }
                option(2, stringResource(R.string.split_each_page))
                option(3, stringResource(R.string.split_max_size)) {
                    OutlinedTextField(
                        value = mb, onValueChange = { mb = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(6) }, singleLine = true,
                        label = { Text("MB") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(enabled = mode != null, onClick = { mode?.let { onSplit(it, null) } }) { Text(stringResource(R.string.share_zip)) }
                TextButton(enabled = mode != null, onClick = { folderPicker.launch(null) }) { Text(stringResource(R.string.save_to_folder)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
