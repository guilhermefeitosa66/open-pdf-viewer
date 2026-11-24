package io.github.guilhermefeitosa66.openpdfviewer.viewer

import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Share
import kotlinx.coroutines.launch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.rememberSaveable
import io.github.guilhermefeitosa66.openpdfviewer.pdfium.OutlineItem
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.guilhermefeitosa66.openpdfviewer.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PagesSheet(
    ready: DocState.Ready,
    vm: ViewerViewModel,
    fileActions: io.github.guilhermefeitosa66.openpdfviewer.ui.FileActions,
    onGoTo: (Int, Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val vp = ready.viewport
    val renderer = ready.renderer
    val total = vp.pageCount
    val current = vp.currentPage
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val grid = rememberLazyGridState(initialFirstVisibleItemIndex = (current - 3).coerceAtLeast(0))
    var goto by remember { mutableStateOf("") }
    val thumbPx = with(LocalDensity.current) { 120.dp.roundToPx() }
    val submit = {
        goto.toIntOrNull()?.let { n -> if (n in 1..total) onGoTo(n - 1, 0f) }
    }

    LaunchedEffect(Unit) {
        vm.loadOutline()
        vm.loadAttachments()
    }
    val outline = vm.outline
    var tab by rememberSaveable { mutableIntStateOf(0) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            // Tabs: pages always, contents when the document has an outline, bookmarks always.
            val tabs = buildList {
                add(R.string.pages)
                if (!outline.isNullOrEmpty()) add(R.string.contents)
                add(R.string.bookmarks)
                add(R.string.annotations)
                if (!vm.attachments.isNullOrEmpty()) add(R.string.attachments)
            }
            val selected = tabs.getOrNull(tab) ?: R.string.pages
            TabRow(selectedTabIndex = tabs.indexOf(selected), modifier = Modifier.padding(bottom = 8.dp)) {
                tabs.forEachIndexed { i, label ->
                    Tab(selected = selected == label, onClick = { tab = i }, text = { Text(stringResource(label)) })
                }
            }
            if (selected == R.string.contents && outline != null) {
                OutlineList(outline, current, onGoTo)
                return@Column
            }
            if (selected == R.string.bookmarks) {
                BookmarkList(vm, onGoTo)
                return@Column
            }
            if (selected == R.string.annotations) {
                AnnotationList(vm, onGoTo)
                return@Column
            }
            if (selected == R.string.attachments) {
                AttachmentList(vm, fileActions)
                return@Column
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.pages),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(stringResource(R.string.go_to), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                OutlinedTextField(
                    value = goto,
                    onValueChange = { v -> goto = v.filter { it.isDigit() }.take(6) },
                    placeholder = { Text("${current + 1}", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.Center),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.width(88.dp),
                )
                Text(stringResource(R.string.of_pages, total), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(100.dp),
                state = grid,
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(total, key = { it }) { i ->
                    Thumbnail(i, ready, renderer, thumbPx, selected = i == current, onClick = { onGoTo(i, 0f) })
                }
            }
        }
    }
}

@Composable
private fun Thumbnail(
    index: Int,
    ready: DocState.Ready,
    renderer: PageRenderer,
    widthPx: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DisposableEffect(index) {
        renderer.requestThumb(index, widthPx)
        onDispose { renderer.cancelThumb(index) }
    }
    renderer.version.intValue // recompose when the thumbnail arrives
    val img = renderer.thumb(index)
    val size = ready.doc.pageSizes[index]
    val accent = MaterialTheme.colorScheme.primary
    val label = stringResource(R.string.page_n, index + 1)
    Column(
        Modifier.clickable(onClick = onClick).semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio((size.width / size.height).coerceIn(0.3f, 3f))
                .then(if (selected) Modifier.border(3.dp, accent, RoundedCornerShape(4.dp)).padding(5.dp) else Modifier)
                .clip(RoundedCornerShape(3.dp))
                .background(io.github.guilhermefeitosa66.openpdfviewer.ui.ThemeSettings.get(LocalContext.current).pageMode.paper),
        ) {
            if (img != null) {
                val mode = io.github.guilhermefeitosa66.openpdfviewer.ui.ThemeSettings.get(LocalContext.current).pageMode
                Image(img, contentDescription = null, contentScale = ContentScale.Fit, colorFilter = mode.filter, modifier = Modifier.fillMaxSize())
            }
        }
        Text(
            "${index + 1}",
            modifier = Modifier.padding(top = 6.dp),
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The table of contents as an expandable tree. Entries with children show a chevron; the branch
 * that holds the current page starts open.
 */
@Composable
private fun OutlineList(items: List<OutlineItem>, currentPage: Int, onGoTo: (Int, Float) -> Unit) {
    val hasChildren = remember(items) { OutlineTree.hasChildren(items.map { it.level }) }
    val expanded = remember(items) {
        mutableStateMapOf<Int, Boolean>().apply {
            OutlineTree.ancestorsOfPage(items.map { it.level }, items.map { it.page }, currentPage).forEach { put(it, true) }
        }
    }
    val visible = remember(items, expanded.toMap()) {
        OutlineTree.visible(items.map { it.level }) { expanded[it] == true }
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.fillMaxSize()) {
        items(visible, key = { it }) { i ->
            val item = items[i]
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { if (item.page >= 0) onGoTo(item.page, item.offset) }
                    .heightIn(min = 48.dp)
                    .padding(start = (12 + item.level * 16).dp, end = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (hasChildren[i]) {
                    val open = expanded[i] == true
                    IconButton(onClick = { expanded[i] = !open }) {
                        Icon(
                            if (open) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            stringResource(if (open) R.string.collapse else R.string.expand),
                        )
                    }
                } else {
                    Spacer(Modifier.width(48.dp))
                }
                Text(
                    item.title.ifEmpty { "—" },
                    modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                    fontSize = 15.sp,
                    fontWeight = if (item.level == 0) FontWeight.Medium else FontWeight.Normal,
                )
                if (item.page >= 0) {
                    Text("${item.page + 1}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun BookmarkList(vm: ViewerViewModel, onGoTo: (Int, Float) -> Unit) {
    var renaming by remember { mutableStateOf<io.github.guilhermefeitosa66.openpdfviewer.data.Bookmark?>(null) }
    val list = vm.bookmarks
    if (list.isEmpty()) {
        Text(
            stringResource(R.string.no_bookmarks),
            modifier = Modifier.padding(24.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.fillMaxSize()) {
        items(list, key = { it.page }) { bm ->
            Row(
                Modifier.fillMaxWidth().clickable { onGoTo(bm.page, 0f) }.heightIn(min = 56.dp).padding(start = 20.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(bm.title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(stringResource(R.string.page_n, bm.page + 1), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { renaming = bm }) {
                    Icon(Icons.Filled.Edit, stringResource(R.string.rename))
                }
                IconButton(onClick = { vm.removeBookmark(bm.page) }) {
                    Icon(Icons.Filled.Delete, stringResource(R.string.remove_bookmark))
                }
            }
        }
    }
    renaming?.let { bm ->
        BookmarkTitleDialog(
            initial = bm.title,
            onSave = { vm.renameBookmark(bm.page, it); renaming = null },
            onDismiss = { renaming = null },
        )
    }
}

@Composable
private fun AttachmentList(vm: ViewerViewModel, fileActions: io.github.guilhermefeitosa66.openpdfviewer.ui.FileActions) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = LocalContext.current
    val failed = stringResource(R.string.attachment_failed)
    val names = vm.attachments.orEmpty()
    fun mimeOf(name: String) = android.webkit.MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
    fun withFile(i: Int, block: (java.io.File) -> Unit) = scope.launch {
        val f = vm.extractAttachment(i)
        if (f == null) android.widget.Toast.makeText(context, failed, android.widget.Toast.LENGTH_LONG).show() else block(f)
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.fillMaxSize()) {
        items(names.size) { i ->
            val name = names[i].ifBlank { "attachment" }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 20.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(name, modifier = Modifier.weight(1f).padding(vertical = 8.dp), fontSize = 15.sp)
                IconButton(onClick = { withFile(i) { f -> fileActions.saveFile(f, f.name, mimeOf(f.name)) } }) {
                    Icon(io.github.guilhermefeitosa66.openpdfviewer.ui.AppIcons.Download, stringResource(R.string.save_attachment, name))
                }
                IconButton(onClick = { withFile(i) { f -> fileActions.shareFile(f, mimeOf(f.name)) } }) {
                    Icon(Icons.Filled.Share, stringResource(R.string.share_attachment, name))
                }
            }
        }
    }
}

/** Every annotation, grouped by page: tap to go there; notes can be edited; any can be deleted. */
@Composable
private fun AnnotationList(vm: ViewerViewModel, onGoTo: (Int, Float) -> Unit) {
    var items by remember { mutableStateOf<List<io.github.guilhermefeitosa66.openpdfviewer.pdfium.PdfDocument.AnnotInfo>?>(null) }
    LaunchedEffect(vm.annotationsVersion) { items = vm.allAnnotations() }
    val list = items
    if (list == null) {
        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }
        return
    }
    if (list.isEmpty()) {
        Text(stringResource(R.string.no_annotations), modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    var editing by remember { mutableStateOf<io.github.guilhermefeitosa66.openpdfviewer.pdfium.PdfDocument.AnnotInfo?>(null) }
    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.fillMaxSize()) {
        var lastPage = -1
        for (a in list) {
            if (a.page != lastPage) {
                lastPage = a.page
                item(key = "p${a.page}") {
                    Text(
                        stringResource(R.string.page_n, a.page + 1),
                        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
                        fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item(key = "${a.page}:${a.index}:${a.nm}") {
                Row(
                    Modifier.fillMaxWidth().clickable { onGoTo(a.page, a.top) }.heightIn(min = 56.dp).padding(start = 20.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(stringResource(annotTypeLabel(a.subtype)), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        val meta = listOf(a.author, a.contents.replace('\n', ' ').take(80)).filter { it.isNotBlank() }.joinToString(" · ")
                        if (meta.isNotEmpty()) Text(meta, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    }
                    if (a.subtype == 1) {
                        IconButton(onClick = { editing = a }) { Icon(Icons.Filled.Edit, stringResource(R.string.rename)) }
                    }
                    IconButton(onClick = {
                        vm.deleteAnnot(io.github.guilhermefeitosa66.openpdfviewer.pdfium.PdfDocument.AnnotHit(a.page, a.index, a.subtype, a.contents, a.nm))
                    }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
                }
            }
        }
    }
    editing?.let { a ->
        BookmarkTitleDialog(
            initial = a.contents,
            onSave = {
                vm.updateAnnotText(io.github.guilhermefeitosa66.openpdfviewer.pdfium.PdfDocument.AnnotHit(a.page, a.index, a.subtype, a.contents, a.nm), it)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

fun annotTypeLabel(subtype: Int): Int = when (subtype) {
    1 -> R.string.note
    3, 13 -> R.string.annot_text
    4, 8 -> R.string.shape_line
    5 -> R.string.shape_rectangle
    6 -> R.string.shape_ellipse
    7 -> R.string.shape_polygon
    9 -> R.string.highlight
    10 -> R.string.underline
    11 -> R.string.squiggly
    12 -> R.string.strikethrough
    15 -> R.string.annot_drawing
    else -> R.string.annotation
}
