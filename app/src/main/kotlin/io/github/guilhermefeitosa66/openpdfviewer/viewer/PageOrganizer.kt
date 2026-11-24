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
import io.github.guilhermefeitosa66.openpdfviewer.ui.AppIcons

/** One page in the organizer: the original page it shows and the quarter turns added. */
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
        vm.applyOrganize(pages.map { it.source }.toIntArray(), pages.map { it.turns }.toIntArray(), asCopy)
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
                    IconButton(onClick = { undo() }, enabled = canUndo) { Icon(AppIcons.Undo, stringResource(R.string.undo)) }
                    Box {
                        TextButton(onClick = { saveMenu = true }, enabled = changed) { Text(stringResource(R.string.save)) }
                        DropdownMenu(expanded = saveMenu, onDismissRequest = { saveMenu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.save)) }, onClick = { saveMenu = false; save(false) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.save_as_copy)) }, onClick = { saveMenu = false; save(true) })
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
                            p, i, ready,
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
                    ActionButton(Icons.Filled.Delete, stringResource(R.string.delete), has && selected.size < pages.size) {
                        snapshot()
                        pages.removeAll { it.id in selected }
                        selected.clear()
                    }
                }
            }
        }
    }
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
    selected: Boolean,
    dragging: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val renderer = ready.renderer
    val widthPx = with(LocalDensity.current) { 120.dp.roundToPx() }
    DisposableEffect(p.source) {
        renderer.requestThumb(p.source, widthPx)
        onDispose { renderer.cancelThumb(p.source) }
    }
    renderer.version.intValue
    val img = renderer.thumb(p.source)
    val size = ready.doc.pageSizes[p.source]
    val aspect = (size.width / size.height).coerceIn(0.3f, 3f)
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
            if (selected) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).background(accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
            }
        }
        Text(
            if (p.source == position) "${position + 1}" else "${position + 1} · ${p.source + 1}",
            modifier = Modifier.padding(top = 6.dp),
            fontSize = 13.sp,
            color = if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
