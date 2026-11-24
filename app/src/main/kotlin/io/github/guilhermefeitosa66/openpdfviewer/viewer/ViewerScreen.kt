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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
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
) {
    LaunchedEffect(uri) { vm.open(uri) }
    val leave = {
        vm.closeDocument()
        onBack()
    }
    BackHandler {
        when {
            vm.selection != null -> vm.clearSelection()
            vm.searchOpen -> vm.closeSearch()
            vm.goBack() -> Unit
            else -> leave()
        }
    }
    val colors = LocalReaderColors.current
    Box(Modifier.fillMaxSize().background(colors.gutter)) {
        when (val s = vm.state) {
            DocState.Loading -> Loading()
            is DocState.NeedPassword -> PasswordDialog(s.wrong, onSubmit = vm::submitPassword, onCancel = leave)
            is DocState.Failed -> Failure(s.kind, onBack = leave, onPickFile = onPickFile)
            is DocState.Ready -> Reader(vm, s, onBack = leave)
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
    var layoutDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val vp = ready.viewport
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
        IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, stringResource(R.string.search)) }
        IconButton(onClick = onPages) { Icon(AppIcons.Pages, stringResource(R.string.pages)) }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, stringResource(R.string.more_options))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                val fitWidth = kotlin.math.abs(vp.zoom - 1f) < 0.01f
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.fit_width)) },
                    trailingIcon = { if (fitWidth) Icon(Icons.Filled.Check, null) },
                    onClick = {
                        menu = false
                        vp.setZoomAndPage(1f, vp.currentPage)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.fit_page)) },
                    onClick = {
                        menu = false
                        val page = vp.currentPage
                        vp.setZoomAndPage(vp.fitPageZoom(page), page)
                    },
                )
                HorizontalDivider()
                val settings = remember { io.github.guilhermefeitosa66.openpdfviewer.ui.ThemeSettings.get(context) }
                for ((mode, label) in listOf(
                    PageMode.NORMAL to R.string.page_normal,
                    PageMode.NIGHT to R.string.page_night,
                    PageMode.SEPIA to R.string.page_sepia,
                )) {
                    DropdownMenuItem(
                        text = { Text(stringResource(label)) },
                        trailingIcon = { if (settings.pageMode == mode) Icon(Icons.Filled.Check, null) },
                        onClick = {
                            menu = false
                            settings.choosePageMode(mode)
                        },
                    )
                }
                val page = vp.currentPage
                if (vm.isBookmarked(page)) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.remove_bookmark)) },
                        onClick = {
                            menu = false
                            vm.removeBookmark(page)
                        },
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.add_bookmark)) },
                        onClick = {
                            menu = false
                            bookmarkPage = page
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.layout)) },
                    onClick = {
                        menu = false
                        layoutDialog = true
                    },
                )
                HorizontalDivider()
                for ((label, on, toggle) in listOf(
                    Triple(R.string.keep_screen_on, settings.keepScreenOn, settings::toggleKeepScreenOn),
                    Triple(R.string.lock_rotation, settings.lockRotation, settings::toggleLockRotation),
                    Triple(R.string.full_screen, settings.fullScreen, settings::toggleFullScreen),
                )) {
                    DropdownMenuItem(
                        text = { Text(stringResource(label)) },
                        trailingIcon = { if (on) Icon(Icons.Filled.Check, null) },
                        onClick = {
                            menu = false
                            toggle()
                        },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.share_file)) },
                    onClick = {
                        menu = false
                        onShare()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.print)) },
                    onClick = {
                        menu = false
                        vm.print(context)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.document_info)) },
                    onClick = {
                        menu = false
                        vm.loadInfo()
                        infoDialog = true
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.save_copy)) },
                    onClick = {
                        menu = false
                        onSaveCopy()
                    },
                )
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
            Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, bottom = 6.dp).height(36.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
            FilterChip(
                selected = vm.matchCase,
                onClick = vm::toggleMatchCase,
                label = { Text(stringResource(R.string.match_case), fontSize = 13.sp) },
            )
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
    if (sel.rects.isEmpty()) return
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val colors = LocalReaderColors.current
    val first = sel.rects.first()
    val last = sel.rects.last()
    val a = vp.toScreen(sel.page, first.left, first.top, first.right, first.bottom)
    val b = vp.toScreen(sel.page, last.left, last.top, last.right, last.bottom)
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
            TextButton(onClick = vm::selectAllOnPage) { Text(stringResource(R.string.select_all_page)) }
            TextButton(onClick = {
                scope.launch {
                    val text = vm.selectedText()
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    context.startActivity(Intent.createChooser(send, null))
                }
            }) { Text(stringResource(R.string.share)) }
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
