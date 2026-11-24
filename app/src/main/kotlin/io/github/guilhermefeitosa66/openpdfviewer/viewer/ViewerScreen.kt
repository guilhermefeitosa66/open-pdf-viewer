package io.github.guilhermefeitosa66.openpdfviewer.viewer

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.guilhermefeitosa66.openpdfviewer.R
import io.github.guilhermefeitosa66.openpdfviewer.ui.AppIcons
import io.github.guilhermefeitosa66.openpdfviewer.pdfium.PdfDocument
import io.github.guilhermefeitosa66.openpdfviewer.ui.LocalBackgroundAsset
import io.github.guilhermefeitosa66.openpdfviewer.ui.LocalReaderColors
import io.github.guilhermefeitosa66.openpdfviewer.ui.ThemeBackground
import io.github.guilhermefeitosa66.openpdfviewer.ui.rememberFileActions
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun ViewerScreen(
    vm: ViewerViewModel,
    uri: Uri,
    onBack: () -> Unit,
    onPickFile: () -> Unit,
    onOpenOther: (Uri) -> Unit = {},
) {
    LaunchedEffect(uri) { vm.open(uri) }
    val leave = {
        vm.closeDocument()
        onBack()
    }
    var askSave by remember { mutableStateOf(false) }
    // After "Save as", keep reading the saved copy.
    vm.saveTargetOpened?.let { saved ->
        LaunchedEffect(saved) {
            vm.saveTargetOpened = null
            onOpenOther(saved)
        }
    }
    val saveAsLauncher = androidx.activity.compose.rememberLauncherForActivityResult(io.github.guilhermefeitosa66.openpdfviewer.ui.CreateTypedDocument()) { target ->
        vm.saveAs(target)
    }
    if (vm.needsSaveAs) {
        LaunchedEffect(Unit) {
            val name = (vm.state as? DocState.Ready)?.name ?: "document.pdf"
            saveAsLauncher.launch(io.github.guilhermefeitosa66.openpdfviewer.ui.FileActions.safeName(name) to "application/pdf")
        }
    }
    if (askSave) {
        AlertDialog(
            onDismissRequest = { askSave = false },
            title = { Text(stringResource(R.string.unsaved_title)) },
            text = { Text(stringResource(R.string.unsaved_body)) },
            confirmButton = { TextButton(onClick = { askSave = false; vm.save { leave() } }) { Text(stringResource(R.string.save)) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { askSave = false; vm.discardChanges(); leave() }) { Text(stringResource(R.string.discard)) }
                    TextButton(onClick = { askSave = false }) { Text(stringResource(R.string.cancel)) }
                }
            },
        )
    }
    val flattenLauncher = androidx.activity.compose.rememberLauncherForActivityResult(io.github.guilhermefeitosa66.openpdfviewer.ui.CreateTypedDocument()) { target ->
        vm.exportFlattened(target)
    }
    if (vm.requestFlatten) {
        LaunchedEffect(Unit) {
            vm.requestFlatten = false
            val name = (vm.state as? DocState.Ready)?.name?.removeSuffix(".pdf")?.removeSuffix(".PDF") ?: "document"
            flattenLauncher.launch(io.github.guilhermefeitosa66.openpdfviewer.ui.FileActions.safeName("$name (flattened)") to "application/pdf")
        }
    }
    vm.exportResult?.let { ok ->
        val msg = stringResource(if (ok) R.string.saved_copy else R.string.copy_failed)
        val ctx = LocalContext.current
        LaunchedEffect(ok) {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            vm.exportResult = null
        }
    }
    if (vm.saving) {
        AlertDialog(onDismissRequest = {}, title = { Text(stringResource(R.string.saving)) }, text = { androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth()) }, confirmButton = {})
    }
    BackHandler {
        when {
            vm.selection != null -> vm.clearSelection()
            vm.drawing -> vm.stopDrawing()
            vm.shapeKind != null -> vm.stopShapes()
            vm.placing != null -> vm.placing = null
            vm.searchOpen -> vm.closeSearch()
            vm.goBack() -> Unit
            vm.unsaved -> askSave = true
            else -> leave()
        }
    }
    val colors = LocalReaderColors.current
    Box(Modifier.fillMaxSize().background(colors.gutter)) {
        when (val s = vm.state) {
            DocState.Loading -> Loading()
            is DocState.NeedPassword -> PasswordDialog(s.wrong, onSubmit = vm::submitPassword, onCancel = leave)
            is DocState.Failed -> Failure(s.kind, onBack = leave, onPickFile = onPickFile)
            is DocState.Ready -> Reader(vm, s, onBack = { if (vm.unsaved) askSave = true else leave() })
        }
    }
}

@Composable
private fun Loading() {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.opening), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun androidx.compose.foundation.layout.BoxScope.Reader(vm: ViewerViewModel, ready: DocState.Ready, onBack: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val vp = ready.viewport
    val fileActions = rememberFileActions()
    var barsVisible by rememberSaveable { mutableStateOf(true) }
    var pagesOpen by rememberSaveable { mutableStateOf(false) }
    // Ignoring visibility: hiding the system bars in full screen must not shift the pages.
    val statusTop = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBarsIgnoringVisibility.asPaddingValues().calculateBottomPadding()
    val padTop = with(density) { (statusTop + 64.dp).toPx() }
    val padBottom = with(density) { (navBottom + 72.dp).toPx() }

    LaunchedEffect(ready) {
        (context as? Activity)?.reportFullyDrawn()
    }
    ReadingWindowOptions(vm, vp, barsVisible, onHideBars = { barsVisible = false })

    PdfCanvas(
        vm = vm,
        ready = ready,
        padTop = padTop,
        padBottom = padBottom,
        onTap = { p ->
            if (vm.selection != null) vm.clearSelection()
            else vm.onTap(p) { barsVisible = !barsVisible }
        },
    )

    SelectionOverlay(vm, vp)

    if (vm.drawing) DrawToolbar(vm, modifier = Modifier.align(Alignment.BottomCenter))
    if (vm.shapeKind != null) ShapeToolbar(vm, modifier = Modifier.align(Alignment.BottomCenter))
    AnnotationDialogs(vm)
    if (vm.signatureSheetOpen) SignatureSheet(vm)
    vm.placingSignature?.let { SignaturePlacer(it, vp, onPlace = vm::placeSignature, onCancel = { vm.placingSignature = null }) }
    vm.editingField?.let { field -> FieldEditor(vm, field) }
    vm.placing?.let { mode ->
        Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 84.dp)) {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(
                            when (mode) {
                                ViewerViewModel.Place.NOTE -> R.string.tap_to_place_note
                                ViewerViewModel.Place.TEXT -> R.string.tap_to_place_text
                                else -> R.string.tap_to_place_mark
                            }
                        ),
                        fontSize = 14.sp,
                    )
                    val stays = mode != ViewerViewModel.Place.NOTE && mode != ViewerViewModel.Place.TEXT
                    TextButton(onClick = { vm.placing = null }) {
                        Text(stringResource(if (stays) R.string.done else R.string.cancel), color = MaterialTheme.colorScheme.inverseOnSurface)
                    }
                }
            }
        }
    }

    vm.readAloud?.let { ra ->
        ReadAloudBar(ra, onClose = vm::stopReadAloud, modifier = Modifier.align(Alignment.BottomCenter))
        // Follow the sentence being read.
        LaunchedEffect(ra.page, ra.rects) {
            val first = ra.rects.firstOrNull() ?: return@LaunchedEffect
            if (vp.paged && vp.zoom <= 1.05f) {
                if (vp.currentPage != ra.page && !vp.visiblePages().contains(ra.page)) vp.scrollToPage(ra.page)
            } else {
                vp.reveal(vp.toScreen(ra.page, first.left, first.top, first.right, first.bottom))
            }
            ready.renderer.request(vp)
        }
        if (ra.state == ReadAloud.State.UNAVAILABLE) {
            val msg = stringResource(R.string.tts_unavailable)
            LaunchedEffect(Unit) {
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                vm.stopReadAloud()
            }
        }
    }

    // After following a link or a contents entry: a way back to where the reader was.
    vm.historyTop?.let { (page, _) ->
        Box(Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start = 16.dp, bottom = 84.dp)) {
            Surface(
                onClick = { vm.goBack() },
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 4.dp,
            ) {
                Row(Modifier.padding(start = 10.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.back_to_page, page + 1), fontSize = 14.sp)
                }
            }
        }
    }

    vm.pendingWebLink?.let { link ->
        AlertDialog(
            onDismissRequest = vm::dismissWebLink,
            title = { Text(stringResource(R.string.open_link_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(link, fontWeight = FontWeight.Medium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.open_link_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.dismissWebLink()
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
                }) { Text(stringResource(R.string.open)) }
            },
            dismissButton = { TextButton(onClick = vm::dismissWebLink) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (vp.pageCount > 3 && !vp.paged) FastScroller(vp, padTop, padBottom)

    // Bottom page indicator.
    AnimatedVisibility(
        visible = barsVisible || vm.searchOpen,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        PageIndicator(vp) { pagesOpen = true }
    }

    // Top bar.
    AnimatedVisibility(
        visible = barsVisible || vm.searchOpen,
        enter = slideInVertically { -it },
        exit = slideOutVertically { -it },
        modifier = Modifier.align(Alignment.TopCenter),
    ) {
        // The theme image may show behind the bar (surface at ~85%); never behind the document.
        val barImage = LocalBackgroundAsset.current != null
        Box(Modifier.background(MaterialTheme.colorScheme.surface)) {
        ThemeBackground(Modifier.matchParentSize(), alignment = Alignment.TopCenter)
        Surface(
            color = if (barImage) MaterialTheme.colorScheme.surface.copy(alpha = 0.85f) else MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(Modifier.statusBarsPadding()) {
                if (vm.searchOpen) SearchBar(vm) else TopBar(
                    title = ready.name,
                    vm = vm,
                    ready = ready,
                    onBack = onBack,
                    onSearch = { vm.searchOpen = true },
                    onPages = { pagesOpen = true },
                    onShare = { vm.uri?.let { fileActions.share(it, ready.name) } },
                    onSaveCopy = { vm.uri?.let { fileActions.saveCopy(it, ready.name) } },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        }
    }

    if (pagesOpen) {
        PagesSheet(
            ready = ready,
            vm = vm,
            fileActions = fileActions,
            onGoTo = { page, offset ->
                vm.jumpTo(page, offset)
                pagesOpen = false
            },
            onDismiss = { pagesOpen = false },
        )
    }
}

@Composable
private fun TopBar(
    title: String,
    vm: ViewerViewModel,
    ready: DocState.Ready,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onPages: () -> Unit,
    onShare: () -> Unit,
    onSaveCopy: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    val scope = rememberCoroutineScope()
    var layoutDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val vp = ready.viewport
    when (panel) {
        Panel.ANNOTATE -> AnnotatePanel(vm, onDismiss = { panel = null })
        Panel.VIEW -> ViewPanel(vp, onLayout = { panel = null; layoutDialog = true }, onDismiss = { panel = null })
        null -> Unit
    }
    if (layoutDialog) LayoutDialog(onDismiss = { layoutDialog = false })
    var infoDialog by remember { mutableStateOf(false) }
    if (infoDialog) DocumentInfoDialog(vm, ready, onDismiss = { infoDialog = false })
    var bookmarkPage by remember { mutableStateOf<Int?>(null) }
    bookmarkPage?.let { p ->
        // Suggest the contents entry of the page when the document has one.
        val suggested = vm.outline?.lastOrNull { it.page in 0..p && it.page == p }?.title
            ?: stringResource(R.string.page_n, p + 1)
        BookmarkTitleDialog(
            initial = suggested,
            onSave = { title ->
                vm.addBookmark(p, title)
                bookmarkPage = null
            },
            onDismiss = { bookmarkPage = null },
        )
    }
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
        }
        Text(
            title,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (vm.canUndo || vm.canRedo) {
            IconButton(onClick = vm::undo, enabled = vm.canUndo) { Icon(AppIcons.Undo, stringResource(R.string.undo)) }
            IconButton(onClick = vm::redo, enabled = vm.canRedo) { Icon(AppIcons.Redo, stringResource(R.string.redo)) }
        }
        IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, stringResource(R.string.search)) }
        IconButton(onClick = onPages) { Icon(AppIcons.Pages, stringResource(R.string.pages)) }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, stringResource(R.string.more_options))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                // Short on purpose: file actions first, then two panels for annotating and viewing.
                if (vm.unsaved) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.save)) }, onClick = { menu = false; vm.save() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.save_as)) }, onClick = { menu = false; vm.needsSaveAs = true })
                    HorizontalDivider()
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.annotate)) }, onClick = { menu = false; panel = Panel.ANNOTATE })
                DropdownMenuItem(text = { Text(stringResource(R.string.view_options)) }, onClick = { menu = false; panel = Panel.VIEW })
                val page = vp.currentPage
                if (vm.isBookmarked(page)) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.remove_bookmark)) }, onClick = { menu = false; vm.removeBookmark(page) })
                } else {
                    DropdownMenuItem(text = { Text(stringResource(R.string.add_bookmark)) }, onClick = { menu = false; bookmarkPage = page })
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.read_aloud)) }, onClick = { menu = false; vm.startReadAloud() })
                HorizontalDivider()
                DropdownMenuItem(text = { Text(stringResource(R.string.share_file)) }, onClick = { menu = false; onShare() })
                if (!vm.unsaved) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.save_copy)) }, onClick = { menu = false; onSaveCopy() })
                }
                val copiedImage = stringResource(R.string.page_copied_image)
                val copyFailed = stringResource(R.string.copy_failed)
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.copy_page_image)) },
                    onClick = {
                        menu = false
                        scope.launch {
                            val ok = vm.copyPageAsImage()
                            Toast.makeText(context, if (ok) copiedImage else copyFailed, Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                DropdownMenuItem(text = { Text(stringResource(R.string.save_flattened)) }, onClick = { menu = false; vm.requestFlatten = true })
                DropdownMenuItem(text = { Text(stringResource(R.string.print)) }, onClick = { menu = false; vm.print(context) })
                DropdownMenuItem(text = { Text(stringResource(R.string.document_info)) }, onClick = { menu = false; vm.loadInfo(); infoDialog = true })
            }
        }
    }
}

@Composable
private fun SearchBar(vm: ViewerViewModel) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    @Suppress("UNUSED_VARIABLE") val version = vm.matchesVersion // re-read matches when they change
    val hasMatches = vm.matches.isNotEmpty()
    var resultsOpen by remember { mutableStateOf(false) }
    Column {
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = vm::closeSearch) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.close_search))
            }
            TextField(
                value = vm.query,
                onValueChange = vm::updateQuery,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.search)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.nextMatch(1) }),
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                modifier = Modifier.weight(1f).focusRequester(focus)
                    .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp)),
            )
            IconButton(onClick = { vm.nextMatch(-1) }, enabled = hasMatches) {
                Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.previous_match))
            }
            IconButton(onClick = { vm.nextMatch(1) }, enabled = hasMatches) {
                Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.next_match))
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 56.dp, end = 8.dp).height(36.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val count = vm.matches.size
            val m = vm.matches.getOrNull(vm.currentMatch)
            when {
                vm.query.isBlank() -> Spacer(Modifier.weight(1f))
                count == 0 && vm.searching -> Text(stringResource(R.string.searching), Modifier.weight(1f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                count == 0 -> Text(stringResource(R.string.no_matches), Modifier.weight(1f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.match_count, vm.currentMatch + 1, count) + if (vm.searching) "…" else "",
                        fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    )
                    if (m != null) {
                        Text(stringResource(R.string.match_page, m.page + 1), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (count > 0) TextButton(onClick = { resultsOpen = true }) { Text(stringResource(R.string.all_results), fontSize = 13.sp) }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(start = 56.dp, end = 16.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = vm.matchCase, onClick = vm::toggleMatchCase, label = { Text(stringResource(R.string.match_case), fontSize = 13.sp) })
            FilterChip(selected = vm.wholeWord, onClick = vm::toggleWholeWord, label = { Text(stringResource(R.string.whole_word), fontSize = 13.sp) })
            FilterChip(selected = vm.ignoreAccents, onClick = vm::toggleIgnoreAccents, label = { Text(stringResource(R.string.ignore_accents), fontSize = 13.sp) })
        }
    }
    if (resultsOpen) SearchResultsSheet(vm, onDismiss = { resultsOpen = false })
}

/** Every match with a bit of context, grouped by page; tapping one jumps to it. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SearchResultsSheet(vm: ViewerViewModel, onDismiss: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val version = vm.matchesVersion
    val matches = vm.matches.toList()
    val strong = MaterialTheme.colorScheme.primary
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            var lastPage = -1
            matches.forEachIndexed { i, m ->
                if (m.page != lastPage) {
                    lastPage = m.page
                    item(key = "p${m.page}") {
                        Text(
                            stringResource(R.string.page_n, m.page + 1),
                            modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
                            fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item(key = i) {
                    Text(
                        androidx.compose.ui.text.buildAnnotatedString {
                            append(m.before)
                            pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, color = strong))
                            append(m.text)
                            pop()
                            append(m.after)
                        },
                        modifier = Modifier.fillMaxWidth()
                            .clickable { vm.goToMatch(i); onDismiss() }
                            .background(if (i == vm.currentMatch) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        fontSize = 14.sp, lineHeight = 20.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun PageIndicator(vp: Viewport, onClick: () -> Unit) {
    val colors = LocalReaderColors.current
    val current = vp.currentPage + 1
    val total = vp.pageCount
    val a11y = stringResource(R.string.page_indicator_a11y, current, total)
    Box(Modifier.navigationBarsPadding().padding(bottom = 20.dp)) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(20.dp),
            color = colors.pill,
            contentColor = colors.onPill,
            shadowElevation = 4.dp,
            modifier = Modifier.semantics { contentDescription = a11y },
        ) {
            Text(
                stringResource(R.string.page_indicator, current, total),
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** A draggable thumb on the right edge that maps to the whole document. */
@Composable
private fun FastScroller(vp: Viewport, padTop: Float, padBottom: Float) {
    val colors = LocalReaderColors.current
    val density = LocalDensity.current
    val thumbH = with(density) { 48.dp.toPx() }
    val track = (vp.height - padTop - padBottom - thumbH).coerceAtLeast(1f)
    val range = (vp.contentHeight - vp.height).coerceAtLeast(1f)
    val y = padTop + (vp.scrollY / range).coerceIn(0f, 1f) * track
    Box(
        Modifier
            .offset { IntOffset((vp.width - with(density) { 28.dp.toPx() }).roundToInt(), y.roundToInt()) }
            .size(28.dp, 48.dp)
            .pointerInput(vp) {
                detectDragGestures { change, drag ->
                    change.consume()
                    vp.scrollBy(0f, drag.y / track * range)
                }
            },
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(
            Modifier.padding(end = 3.dp).width(6.dp).height(44.dp)
                .background(colors.scrollThumb, RoundedCornerShape(3.dp))
        )
    }
}

@Composable
private fun SelectionOverlay(vm: ViewerViewModel, vp: Viewport) {
    val sel = vm.selection ?: return
    val first = sel.firstRect ?: return
    val last = sel.lastRect ?: return
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val colors = LocalReaderColors.current
    val a = vp.toScreen(sel.startPage, first.left, first.top, first.right, first.bottom)
    val b = vp.toScreen(sel.endPage, last.left, last.top, last.right, last.bottom)
    val handle = with(density) { 22.dp.toPx() }
    val touch = with(density) { 44.dp.toPx() }

    // Start handle: below the left edge of the first line, pointing up-right.
    SelectionHandle(
        anchor = Offset(a.left, a.bottom),
        isStart = true,
        handle = handle,
        touch = touch,
        color = colors.handle,
        onDrag = { p -> vm.dragHandle(true, Offset(p.x + 2f, p.y - a.height / 2f)) },
    )
    SelectionHandle(
        anchor = Offset(b.right, b.bottom),
        isStart = false,
        handle = handle,
        touch = touch,
        color = colors.handle,
        onDrag = { p -> vm.dragHandle(false, Offset(p.x - 2f, p.y - b.height / 2f)) },
    )

    // Toolbar above the selection (or below when there is no room).
    val barH = with(density) { 48.dp.toPx() }
    val margin = with(density) { 12.dp.toPx() }
    val top = if (a.top - barH - margin > vp.padTop) a.top - barH - margin else b.bottom + handle + margin
    val copied = stringResource(R.string.copied)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 6.dp,
        modifier = Modifier
            .offset { IntOffset(margin.roundToInt(), top.roundToInt().coerceAtMost((vp.height - barH - margin).roundToInt())) }
            .widthIn(max = 380.dp),
    ) {
        Row(Modifier.padding(horizontal = 4.dp)) {
            TextButton(onClick = {
                scope.launch {
                    val text = vm.selectedText()
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("PDF", text))
                    if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                    vm.clearSelection()
                }
            }) { Text(stringResource(R.string.copy)) }
            val settings = remember { io.github.guilhermefeitosa66.openpdfviewer.ui.ThemeSettings.get(context) }
            val markColor = Color(settings.markupColor)
            TextButton(onClick = { vm.markSelection(PdfDocument.Markup.HIGHLIGHT, settings.markupColor) }) {
                Box(Modifier.size(14.dp).background(markColor, androidx.compose.foundation.shape.CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.highlight))
            }
            var more by remember { mutableStateOf(false) }
            var colorDialog by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { more = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more_options)) }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    for ((type, label) in listOf(
                        PdfDocument.Markup.UNDERLINE to R.string.underline,
                        PdfDocument.Markup.STRIKEOUT to R.string.strikethrough,
                        PdfDocument.Markup.SQUIGGLY to R.string.squiggly,
                    )) {
                        DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { more = false; vm.markSelection(type, settings.markupColor) })
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.markup_color)) },
                        leadingIcon = { Box(Modifier.size(18.dp).background(markColor, androidx.compose.foundation.shape.CircleShape)) },
                        onClick = { more = false; colorDialog = true },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(stringResource(R.string.select_all_page)) }, onClick = { more = false; vm.selectAllOnPage() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.share)) }, onClick = {
                        more = false
                        scope.launch {
                            val text = vm.selectedText()
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                            context.startActivity(Intent.createChooser(send, null))
                        }
                    })
                }
            }
            if (colorDialog) {
                ColorPickerDialog(
                    initial = settings.markupColor,
                    onPick = { settings.chooseMarkupColor(it); colorDialog = false },
                    onDismiss = { colorDialog = false },
                )
            }
        }
    }
}

@Composable
private fun SelectionHandle(
    anchor: Offset,
    isStart: Boolean,
    handle: Float,
    touch: Float,
    color: Color,
    onDrag: (Offset) -> Unit,
) {
    var dragPos by remember { mutableStateOf<Offset?>(null) }
    val density = LocalDensity.current
    // The touch box is centred on the visible teardrop.
    val inset = (touch - handle) / 2f
    val boxLeft = if (isStart) anchor.x - handle - inset else anchor.x - inset
    val boxTop = anchor.y - inset
    Box(
        Modifier
            .offset { IntOffset(boxLeft.roundToInt(), boxTop.roundToInt()) }
            .size(with(density) { touch.toDp() })
            .pointerInput(isStart) {
                detectDragGestures(
                    onDragStart = { dragPos = anchor },
                    onDragEnd = { dragPos = null },
                    onDragCancel = { dragPos = null },
                ) { change, drag ->
                    change.consume()
                    val p = (dragPos ?: anchor) + drag
                    dragPos = p
                    onDrag(p)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val shape = if (isStart) {
            RoundedCornerShape(topStartPercent = 50, topEndPercent = 0, bottomEndPercent = 50, bottomStartPercent = 50)
        } else {
            RoundedCornerShape(topStartPercent = 0, topEndPercent = 50, bottomEndPercent = 50, bottomStartPercent = 50)
        }
        Box(Modifier.size(with(density) { handle.toDp() }).background(color, shape))
    }
}

@Composable
private fun PasswordDialog(wrong: Boolean, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.password_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (wrong) R.string.password_wrong else R.string.password_body))
                OutlinedTextField(
                    value = pw,
                    onValueChange = { pw = it },
                    label = { Text(stringResource(R.string.password)) },
                    singleLine = true,
                    isError = wrong,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (pw.isNotEmpty()) onSubmit(pw) }),
                    modifier = Modifier.focusRequester(focus),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(pw) }, enabled = pw.isNotEmpty()) { Text(stringResource(R.string.open)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Failure(kind: DocState.Failed.Kind, onBack: () -> Unit, onPickFile: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.error_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(
                when (kind) {
                    DocState.Failed.Kind.ACCESS -> R.string.error_access
                    DocState.Failed.Kind.FORMAT -> R.string.error_format
                    DocState.Failed.Kind.SECURITY -> R.string.error_security
                    DocState.Failed.Kind.UNKNOWN -> R.string.error_unknown
                }
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            if (kind == DocState.Failed.Kind.ACCESS) {
                Button(onClick = onPickFile) { Text(stringResource(R.string.pick_again)) }
            }
        }
    }
}



/**
 * Window-level reading options: keep the screen on, lock the rotation, and full screen (the
 * app bars hide when the page scrolls, the system bars with them; a tap brings both back).
 */
@Composable
private fun ReadingWindowOptions(vm: ViewerViewModel, vp: Viewport, barsVisible: Boolean, onHideBars: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity ?: return
    val settings = remember { io.github.guilhermefeitosa66.openpdfviewer.ui.ThemeSettings.get(context) }
    val density = LocalDensity.current

    DisposableEffect(settings.keepScreenOn) {
        if (settings.keepScreenOn) activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    DisposableEffect(settings.lockRotation) {
        activity.requestedOrientation = if (settings.lockRotation) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose { activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    // Hide the bars once the reader scrolls a little.
    val threshold = with(density) { 24.dp.toPx() }
    LaunchedEffect(vp, settings.fullScreen) {
        if (!settings.fullScreen) return@LaunchedEffect
        var anchor = vp.scrollY
        var wasReady = vp.ready
        snapshotFlow { vp.ready to vp.scrollY }.collect { (ready, y) ->
            // The first placement (restoring the saved position) is not the reader scrolling.
            if (!wasReady || !ready) {
                wasReady = ready
                anchor = y
                return@collect
            }
            if (kotlin.math.abs(y - anchor) > threshold) {
                anchor = y
                if (!vm.searchOpen && vm.selection == null) onHideBars()
            }
        }
    }

    val immersive = settings.fullScreen && !barsVisible && !vm.searchOpen
    DisposableEffect(immersive) {
        val controller = androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (immersive) {
            controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
    }
}

/** Page layout: continuous scroll or page by page, plus spreads and reading direction. */
@Composable
private fun LayoutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { io.github.guilhermefeitosa66.openpdfviewer.ui.ThemeSettings.get(context) }
    var paged by remember { mutableStateOf(settings.pagedLayout) }
    var two by remember { mutableStateOf(settings.twoPages) }
    var cover by remember { mutableStateOf(settings.coverAlone) }
    var rtl by remember { mutableStateOf(settings.rightToLeft) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.layout)) },
        text = {
            Column {
                for ((value, label) in listOf(false to R.string.layout_scroll, true to R.string.layout_paged)) {
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = paged == value, onClick = { paged = value }, role = androidx.compose.ui.semantics.Role.RadioButton)
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.RadioButton(selected = paged == value, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(label))
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                for ((value, label, set) in listOf(
                    Triple(two, R.string.layout_two_pages) { v: Boolean -> two = v },
                    Triple(cover, R.string.layout_cover_alone) { v: Boolean -> cover = v },
                    Triple(rtl, R.string.layout_rtl) { v: Boolean -> rtl = v },
                )) {
                    Row(
                        Modifier.fillMaxWidth()
                            .toggleable(value = value, enabled = paged, onValueChange = set, role = androidx.compose.ui.semantics.Role.Checkbox)
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(checked = value, onCheckedChange = null, enabled = paged)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(label), color = if (paged) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                settings.setLayout(paged, two, cover, rtl)
                onDismiss()
            }) { Text(stringResource(R.string.apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Title of a bookmark: used when adding one and when renaming it. */
@Composable
fun BookmarkTitleDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bookmark_title)) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(120) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (title.isNotBlank()) onSave(title) }),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(title) }, enabled = title.isNotBlank()) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun DocumentInfoDialog(vm: ViewerViewModel, ready: DocState.Ready, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val info = vm.info
    val yes = stringResource(R.string.yes)
    val no = stringResource(R.string.no)
    fun date(raw: String): String = PdfDate.parse(raw)?.let {
        android.text.format.DateUtils.formatDateTime(
            context, it,
            android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME or android.text.format.DateUtils.FORMAT_SHOW_YEAR,
        )
    } ?: raw
    val size = ready.doc.pageSizes.firstOrNull()
    val rows = buildList {
        add(R.string.info_file to ready.name)
        if (info != null) {
            add(R.string.info_title to info.title)
            add(R.string.info_author to info.author)
            add(R.string.info_subject to info.subject)
            add(R.string.info_keywords to info.keywords)
            add(R.string.info_creator to info.creator)
            add(R.string.info_producer to info.producer)
            add(R.string.info_created to date(info.created))
            add(R.string.info_modified to date(info.modified))
            if (info.version > 0) add(R.string.info_version to "${info.version / 10}.${info.version % 10}")
        }
        add(R.string.info_pages to ready.doc.pageCount.toString())
        if (size != null) add(R.string.info_page_size to PageSizes.describe(size.width, size.height))
        if (vm.fileSize >= 0) add(R.string.info_file_size to android.text.format.Formatter.formatFileSize(context, vm.fileSize))
        if (info != null) {
            add(R.string.info_form to if (info.formType > 0) yes else no)
            add(R.string.info_signatures to if (info.signatures > 0) info.signatures.toString() else no)
            add(R.string.info_encrypted to if (info.encrypted) yes else no)
        }
    }.filter { it.second.isNotBlank() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.document_info)) },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((label, value) in rows) {
                    Column {
                        Text(stringResource(label), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        androidx.compose.foundation.text.selection.SelectionContainer { Text(value, fontSize = 15.sp) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun ReadAloudBar(ra: ReadAloud, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val rates = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
    Box(modifier.navigationBarsPadding().padding(bottom = 84.dp)) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 6.dp) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val playing = ra.state == ReadAloud.State.PLAYING || ra.state == ReadAloud.State.STARTING
                IconButton(onClick = { if (playing) ra.pause() else ra.resume() }) {
                    Icon(
                        if (playing) AppIcons.Pause else Icons.Filled.PlayArrow,
                        stringResource(if (playing) R.string.pause else R.string.play),
                    )
                }
                Text(stringResource(R.string.page_n, ra.page + 1), fontSize = 14.sp, modifier = Modifier.padding(horizontal = 8.dp))
                TextButton(onClick = {
                    val next = rates[(rates.indexOf(ra.speed).coerceAtLeast(0) + 1) % rates.size]
                    ra.setRate(next)
                }) {
                    Text(stringResource(R.string.speed, ra.speed.toString().removeSuffix(".0")))
                }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.stop_reading)) }
            }
        }
    }
}

/** Markup colors: a few soft ones that read well over text, plus any hue. */
private val MarkupPalette = listOf(0xFFFFE066, 0xFFA5D6A7, 0xFF90CAF9, 0xFFF48FB1, 0xFFFFCC80, 0xFFCE93D8, 0xFFE57373, 0xFFB0BEC5).map { it.toInt() }

@Composable
fun ColorPickerDialog(initial: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    var color by remember { mutableStateOf(initial) }
    val hsv = remember(initial) { FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) } }
    var hue by remember { mutableStateOf(hsv[0]) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.markup_color)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (c in MarkupPalette) {
                        val label = String.format("#%06X", c and 0xFFFFFF)
                        Box(
                            Modifier.size(40.dp)
                                .background(Color(c), androidx.compose.foundation.shape.CircleShape)
                                .border(if (c == color) 3.dp else 1.dp, if (c == color) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, androidx.compose.foundation.shape.CircleShape)
                                .clickable { color = c }
                                .semantics { contentDescription = label },
                        )
                    }
                }
                Text(stringResource(R.string.custom_color), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.material3.Slider(
                    value = hue,
                    onValueChange = {
                        hue = it
                        color = android.graphics.Color.HSVToColor(floatArrayOf(it, 0.45f, 1f))
                    },
                    valueRange = 0f..359f,
                )
                Box(Modifier.fillMaxWidth().height(28.dp).background(Color(color), RoundedCornerShape(8.dp)))
            }
        },
        confirmButton = { TextButton(onClick = { onPick(color) }) { Text(stringResource(R.string.apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Ink colors: strong ones, like pens. */
private val InkPalette = listOf(0xFF1A1A1A, 0xFF1F3A93, 0xFFC62828, 0xFF2E7D32, 0xFFEF6C00, 0xFF6A1B9A, 0xFFFFD600, 0xFFFFFFFF).map { it.toInt() }

@Composable
private fun DrawToolbar(vm: ViewerViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = remember { io.github.guilhermefeitosa66.openpdfviewer.ui.ThemeSettings.get(context) }
    var penDialog by remember { mutableStateOf(false) }
    Box(modifier.navigationBarsPadding().padding(bottom = 84.dp)) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 6.dp) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = vm.drawTool == ViewerViewModel.DrawTool.PEN,
                    onClick = { vm.drawTool = ViewerViewModel.DrawTool.PEN },
                    label = { Text(stringResource(R.string.pen)) },
                    leadingIcon = { Box(Modifier.size(14.dp).background(Color(settings.inkColor), androidx.compose.foundation.shape.CircleShape)) },
                )
                Spacer(Modifier.width(6.dp))
                FilterChip(
                    selected = vm.drawTool == ViewerViewModel.DrawTool.ERASER,
                    onClick = { vm.drawTool = ViewerViewModel.DrawTool.ERASER },
                    label = { Text(stringResource(R.string.eraser)) },
                )
                IconButton(onClick = { penDialog = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.pen_settings)) }
                TextButton(onClick = vm::stopDrawing) { Text(stringResource(R.string.done)) }
            }
        }
    }
    if (penDialog) PenDialog(settings.inkColor, settings.inkWidth, onPick = { c, w -> settings.choosePen(c, w); penDialog = false }, onDismiss = { penDialog = false })
}

/** Pen color, opacity and width, with a preview stroke. */
@Composable
private fun PenDialog(initialColor: Int, initialWidth: Float, onPick: (Int, Float) -> Unit, onDismiss: () -> Unit) {
    var rgb by remember { mutableStateOf(initialColor or 0xFF000000.toInt()) }
    var opacity by remember { mutableStateOf(((initialColor ushr 24) and 0xFF) / 255f) }
    var width by remember { mutableStateOf(initialWidth) }
    val color = (rgb and 0xFFFFFF) or ((opacity * 255).toInt().coerceIn(25, 255) shl 24)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pen_settings)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (c in InkPalette) {
                        val label = String.format("#%06X", c and 0xFFFFFF)
                        Box(
                            Modifier.size(36.dp).background(Color(c), androidx.compose.foundation.shape.CircleShape)
                                .border(if (c == rgb) 3.dp else 1.dp, if (c == rgb) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, androidx.compose.foundation.shape.CircleShape)
                                .clickable { rgb = c }
                                .semantics { contentDescription = label },
                        )
                    }
                }
                Text(stringResource(R.string.pen_width, String.format("%.1f", width)), fontSize = 13.sp)
                androidx.compose.material3.Slider(value = width, onValueChange = { width = it }, valueRange = 0.5f..12f)
                Text(stringResource(R.string.pen_opacity, (opacity * 100).toInt()), fontSize = 13.sp)
                androidx.compose.material3.Slider(value = opacity, onValueChange = { opacity = it }, valueRange = 0.1f..1f)
                androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(36.dp).background(Color.White, RoundedCornerShape(8.dp))) {
                    drawLine(Color(color), Offset(24f, size.height / 2), Offset(size.width - 24f, size.height / 2), strokeWidth = width * density * 1.5f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(color, width) }) { Text(stringResource(R.string.apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** The dialogs for new notes and texts, and for an annotation the user tapped. */
@Composable
private fun AnnotationDialogs(vm: ViewerViewModel) {
    vm.placementTarget?.let { (mode, page, at) ->
        if (mode == ViewerViewModel.Place.NOTE) {
            NoteDialog(
                initial = "",
                onSave = { vm.addNote(page, at, it); vm.placementTarget = null },
                onDelete = null,
                onDismiss = { vm.placementTarget = null },
            )
        } else {
            TextBoxDialog(
                onSave = { text, font, size, color -> vm.addText(page, at, text, font, size, color); vm.placementTarget = null },
                onDismiss = { vm.placementTarget = null },
            )
        }
    }
    vm.openedAnnot?.let { hit ->
        if (hit.isNote) {
            NoteDialog(
                initial = hit.contents,
                onSave = { vm.updateAnnotText(hit, it); vm.openedAnnot = null },
                onDelete = { vm.deleteAnnot(hit); vm.openedAnnot = null },
                onDismiss = { vm.openedAnnot = null },
            )
        } else {
            val permanent = !vm.canUndoDelete(hit)
            AlertDialog(
                onDismissRequest = { vm.openedAnnot = null },
                title = { Text(stringResource(R.string.annotation)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (hit.contents.isNotBlank()) Text(hit.contents)
                        if (permanent) Text(stringResource(R.string.delete_from_file_note), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = { TextButton(onClick = { vm.openedAnnot = null }) { Text(stringResource(R.string.close)) } },
                dismissButton = { TextButton(onClick = { vm.deleteAnnot(hit); vm.openedAnnot = null }) { Text(stringResource(R.string.delete)) } },
            )
        }
    }
}

@Composable
private fun NoteDialog(initial: String, onSave: (String) -> Unit, onDelete: (() -> Unit)?, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.note)) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, minLines = 3, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.save)) } },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

/** Text placed on the page as typed: font, size and color. */
@Composable
private fun TextBoxDialog(
    onSave: (String, PdfDocument.StandardFont, Float, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var font by remember { mutableStateOf(PdfDocument.StandardFont.SANS) }
    var size by remember { mutableStateOf(12f) }
    var color by remember { mutableStateOf(InkPalette.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_text)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = text, onValueChange = { text = it }, minLines = 2, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((f, label) in listOf(
                        PdfDocument.StandardFont.SANS to R.string.font_sans,
                        PdfDocument.StandardFont.SERIF to R.string.font_serif,
                        PdfDocument.StandardFont.MONO to R.string.font_mono,
                    )) {
                        FilterChip(selected = font == f, onClick = { font = f }, label = { Text(stringResource(label)) })
                    }
                }
                Text(stringResource(R.string.font_size, size.toInt()), fontSize = 13.sp)
                androidx.compose.material3.Slider(value = size, onValueChange = { size = it }, valueRange = 6f..48f)
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (c in InkPalette) {
                        val label = String.format("#%06X", c and 0xFFFFFF)
                        Box(
                            Modifier.size(32.dp).background(Color(c), androidx.compose.foundation.shape.CircleShape)
                                .border(if (c == color) 3.dp else 1.dp, if (c == color) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, androidx.compose.foundation.shape.CircleShape)
                                .clickable { color = c }
                                .semantics { contentDescription = label },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text.trimEnd(), font, size, color) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.add)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ShapeToolbar(vm: ViewerViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = remember { io.github.guilhermefeitosa66.openpdfviewer.ui.ThemeSettings.get(context) }
    var penDialog by remember { mutableStateOf(false) }
    var fillMenu by remember { mutableStateOf(false) }
    Column(modifier.navigationBarsPadding().padding(bottom = 84.dp, start = 12.dp, end = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 6.dp) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((kind, label) in listOf(
                        Shapes.Kind.RECTANGLE to R.string.shape_rectangle,
                        Shapes.Kind.ELLIPSE to R.string.shape_ellipse,
                        Shapes.Kind.LINE to R.string.shape_line,
                        Shapes.Kind.ARROW to R.string.shape_arrow,
                        Shapes.Kind.POLYGON to R.string.shape_polygon,
                    )) {
                        FilterChip(selected = vm.shapeKind == kind, onClick = { vm.commitShape(); vm.shapeKind = kind }, label = { Text(stringResource(label)) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { penDialog = true }) {
                        Box(Modifier.size(14.dp).background(Color(settings.inkColor), androidx.compose.foundation.shape.CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.line_color))
                    }
                    Box {
                        TextButton(onClick = { fillMenu = true }) {
                            Box(
                                Modifier.size(14.dp)
                                    .background(if (vm.shapeFill == 0) Color.Transparent else Color(vm.shapeFill), androidx.compose.foundation.shape.CircleShape)
                                    .border(1.dp, MaterialTheme.colorScheme.outline, androidx.compose.foundation.shape.CircleShape),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.fill))
                        }
                        DropdownMenu(expanded = fillMenu, onDismissRequest = { fillMenu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.no_fill)) }, onClick = { vm.shapeFill = 0; fillMenu = false })
                            for (c in MarkupPalette) {
                                DropdownMenuItem(
                                    text = { Text(String.format("#%06X", c and 0xFFFFFF)) },
                                    leadingIcon = { Box(Modifier.size(18.dp).background(Color(c), androidx.compose.foundation.shape.CircleShape)) },
                                    onClick = { vm.shapeFill = c; fillMenu = false },
                                )
                            }
                        }
                    }
                    if (vm.shapeKind == Shapes.Kind.POLYGON && (vm.shapePreview?.second?.size ?: 0) >= 3) {
                        TextButton(onClick = vm::commitShape) { Text(stringResource(R.string.finish_polygon)) }
                    }
                    TextButton(onClick = { vm.commitShape(); vm.stopShapes() }) { Text(stringResource(R.string.done)) }
                }
            }
        }
    }
    if (penDialog) PenDialog(settings.inkColor, settings.inkWidth, onPick = { c, w -> settings.choosePen(c, w); penDialog = false }, onDismiss = { penDialog = false })
}

private enum class Panel { ANNOTATE, VIEW }

@Composable
private fun PanelItem(label: String, checked: Boolean? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 16.sp)
        if (checked == true) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
    }
}

/** Ways to annotate: note, text, drawing, shapes (highlighting starts from a text selection). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AnnotatePanel(vm: ViewerViewModel, onDismiss: () -> Unit) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.padding(bottom = 24.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            Text(stringResource(R.string.annotate_hint), modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PanelItem(stringResource(R.string.add_note)) { onDismiss(); vm.placing = ViewerViewModel.Place.NOTE }
            PanelItem(stringResource(R.string.add_text)) { onDismiss(); vm.placing = ViewerViewModel.Place.TEXT }
            PanelItem(stringResource(R.string.draw)) { onDismiss(); vm.startDrawing() }
            PanelItem(stringResource(R.string.shapes)) { onDismiss(); vm.startShapes() }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text(stringResource(R.string.fill_and_sign), modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PanelItem(stringResource(R.string.signature)) { onDismiss(); vm.signatureSheetOpen = true }
            PanelItem(stringResource(R.string.place_date)) { onDismiss(); vm.placing = ViewerViewModel.Place.DATE }
            PanelItem(stringResource(R.string.place_check)) { onDismiss(); vm.placing = ViewerViewModel.Place.CHECK }
            PanelItem(stringResource(R.string.place_cross)) { onDismiss(); vm.placing = ViewerViewModel.Place.CROSS }
            PanelItem(stringResource(R.string.place_dot)) { onDismiss(); vm.placing = ViewerViewModel.Place.DOT }
        }
    }
}

/** How the document is shown: fit, page color, layout and screen options. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ViewPanel(vp: Viewport, onLayout: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { io.github.guilhermefeitosa66.openpdfviewer.ui.ThemeSettings.get(context) }
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.padding(bottom = 24.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            PanelItem(stringResource(R.string.fit_width), checked = kotlin.math.abs(vp.zoom - 1f) < 0.01f) { vp.setZoomAndPage(1f, vp.currentPage); onDismiss() }
            PanelItem(stringResource(R.string.fit_page)) { val p = vp.currentPage; vp.setZoomAndPage(vp.fitPageZoom(p), p); onDismiss() }
            PanelItem(stringResource(R.string.layout)) { onLayout() }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            for ((mode, label) in listOf(PageMode.NORMAL to R.string.page_normal, PageMode.NIGHT to R.string.page_night, PageMode.SEPIA to R.string.page_sepia)) {
                PanelItem(stringResource(label), checked = settings.pageMode == mode) { settings.choosePageMode(mode) }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            PanelItem(stringResource(R.string.keep_screen_on), checked = settings.keepScreenOn) { settings.toggleKeepScreenOn() }
            PanelItem(stringResource(R.string.lock_rotation), checked = settings.lockRotation) { settings.toggleLockRotation() }
            PanelItem(stringResource(R.string.full_screen), checked = settings.fullScreen) { settings.toggleFullScreen() }
        }
    }
}

/** Fills a text field or picks an option, with "Next" to go to the following field. */
@Composable
private fun FieldEditor(vm: ViewerViewModel, field: PdfDocument.FormField) {
    var hasNext by remember(field) { mutableStateOf(false) }
    LaunchedEffect(field) { hasNext = vm.hasNextField(field) }
    val title = field.name.substringAfterLast('.').ifBlank { stringResource(R.string.form_field) }
    if (field.isChoice) {
        var chosen by remember(field) { mutableStateOf(field.options.indexOf(field.value).coerceAtLeast(0)) }
        AlertDialog(
            onDismissRequest = vm::closeFieldEditor,
            title = { Text(title) },
            text = {
                Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    field.options.forEachIndexed { i, option ->
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = chosen == i, onClick = { chosen = i }, role = androidx.compose.ui.semantics.Role.RadioButton).padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.RadioButton(selected = chosen == i, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(option)
                        }
                    }
                }
            },
            confirmButton = {
                Row {
                    if (hasNext) TextButton(onClick = { vm.chooseFieldOption(field, chosen, thenNext = true) }) { Text(stringResource(R.string.next_field)) }
                    TextButton(onClick = { vm.chooseFieldOption(field, chosen, thenNext = false) }) { Text(stringResource(R.string.done)) }
                }
            },
            dismissButton = { TextButton(onClick = vm::closeFieldEditor) { Text(stringResource(R.string.cancel)) } },
        )
        return
    }
    var text by remember(field) { mutableStateOf(field.value) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(field) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = vm::closeFieldEditor,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = !field.multiline,
                minLines = if (field.multiline) 3 else 1,
                visualTransformation = if (field.password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = when {
                        field.password -> KeyboardType.Password
                        field.name.contains("mail", ignoreCase = true) -> KeyboardType.Email
                        field.name.contains("phone", ignoreCase = true) || field.name.contains("tel", ignoreCase = true) -> KeyboardType.Phone
                        else -> KeyboardType.Text
                    },
                    imeAction = if (hasNext && !field.multiline) ImeAction.Next else ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onNext = { vm.setFieldText(field, text, thenNext = true) },
                    onDone = { vm.setFieldText(field, text, thenNext = false) },
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            Row {
                if (hasNext) TextButton(onClick = { vm.setFieldText(field, text, thenNext = true) }) { Text(stringResource(R.string.next_field)) }
                TextButton(onClick = { vm.setFieldText(field, text, thenNext = false) }) { Text(stringResource(R.string.done)) }
            }
        },
        dismissButton = { TextButton(onClick = vm::closeFieldEditor) { Text(stringResource(R.string.cancel)) } },
    )
}
