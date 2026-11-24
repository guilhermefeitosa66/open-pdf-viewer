package io.github.guilhermefeitosa66.openpdfviewer.viewer

import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
fun PagesSheet(ready: DocState.Ready, vm: ViewerViewModel, onGoTo: (Int, Float) -> Unit, onDismiss: () -> Unit) {
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

    LaunchedEffect(Unit) { vm.loadOutline() }
    val outline = vm.outline
    var tab by rememberSaveable { mutableIntStateOf(0) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            if (!outline.isNullOrEmpty()) {
                TabRow(selectedTabIndex = tab, modifier = Modifier.padding(bottom = 8.dp)) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.pages)) })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.contents)) })
                }
            }
            if (tab == 1 && !outline.isNullOrEmpty()) {
                OutlineList(outline, current, onGoTo)
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
