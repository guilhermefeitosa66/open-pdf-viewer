package io.github.guilhermefeitosa66.openpdfviewer.home

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.guilhermefeitosa66.openpdfviewer.BuildConfig
import io.github.guilhermefeitosa66.openpdfviewer.R
import io.github.guilhermefeitosa66.openpdfviewer.data.Library
import io.github.guilhermefeitosa66.openpdfviewer.data.LibraryFile
import io.github.guilhermefeitosa66.openpdfviewer.data.RecentFile
import io.github.guilhermefeitosa66.openpdfviewer.data.RecentFiles
import io.github.guilhermefeitosa66.openpdfviewer.ui.AppIcons
import io.github.guilhermefeitosa66.openpdfviewer.ui.LocalBackgroundAsset
import io.github.guilhermefeitosa66.openpdfviewer.ui.ThemeBackground
import io.github.guilhermefeitosa66.openpdfviewer.ui.rememberFileActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SOURCE_URL = "https://github.com/guilhermefeitosa66/open-pdf-viewer"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpen: (Uri) -> Unit, onPickFile: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val recent = remember { RecentFiles.get(context) }
    LaunchedEffect(Unit) {
        recent.load()
        // The library (favorites, folders) is not needed for the first frame.
        androidx.compose.runtime.withFrameNanos { }
        Library.get(context).load()
    }
    val items by recent.items.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    // With a background image, content sits on the theme surface at ~85% so text stays readable.
    val hasImage = LocalBackgroundAsset.current != null
    val panel = if (hasImage) MaterialTheme.colorScheme.surface.copy(alpha = 0.85f) else Color.Transparent
    val fileActions = rememberFileActions()
    val removedText = stringResource(R.string.removed_from_list)
    val undoText = stringResource(R.string.undo)

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    ThemeBackground(Modifier.fillMaxSize())
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onPickFile,
                icon = { Icon(AppIcons.Folder, null) },
                text = { Text(stringResource(R.string.open_pdf), fontSize = 16.sp) },
                shape = RoundedCornerShape(16.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            Row(
                Modifier.fillMaxWidth().background(panel).padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.app_name),
                    modifier = Modifier.weight(1f),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium,
                )
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, stringResource(R.string.more_options))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.merge_title)) },
                            onClick = { menu = false; merging = true },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.settings)) },
                            onClick = { menu = false; onSettings() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.about)) },
                            onClick = { menu = false; about = true },
                        )
                    }
                }
            }

            var tab by rememberSaveable { mutableIntStateOf(0) }
            HomeTabs(tab, panel = if (hasImage) panel else MaterialTheme.colorScheme.background) { tab = it }
            val list = items
            when {
                tab == 1 -> FavoritesTab(panel, fileActions, onOpen)
                tab == 2 -> FoldersTab(panel, fileActions, onOpen)
                list == null -> Unit
                list.isEmpty() -> EmptyState(panel)
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 96.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(list, key = { it.uri }) { entry ->
                            val state = rememberSwipeToDismissBoxState(
                                confirmValueChange = { value ->
                                    if (value != SwipeToDismissBoxValue.Settled) {
                                        scope.launch {
                                            recent.remove(entry)
                                            val r = snackbar.showSnackbar(removedText, undoText, duration = SnackbarDuration.Short)
                                            if (r == SnackbarResult.ActionPerformed) recent.restore(entry)
                                        }
                                        true
                                    } else false
                                },
                            )
                            SwipeToDismissBox(state = state, backgroundContent = {}) {
                                RecentRow(
                                    entry, recent, if (hasImage) panel else MaterialTheme.colorScheme.background,
                                    onShare = { fileActions.share(Uri.parse(entry.uri), entry.name) },
                                    onSaveCopy = { fileActions.saveCopy(Uri.parse(entry.uri), entry.name) },
                                    onToggleFavorite = {
                                        scope.launch {
                                            Library.get(context).toggleFavorite(LibraryFile(entry.uri, entry.name))
                                        }
                                    },
                                    onRemove = {
                                        scope.launch {
                                            recent.remove(entry)
                                            val r = snackbar.showSnackbar(removedText, undoText, duration = SnackbarDuration.Short)
                                            if (r == SnackbarResult.ActionPerformed) recent.restore(entry)
                                        }
                                    },
                                ) { onOpen(Uri.parse(entry.uri)) }
                            }
                        }
                        item {
                            Text(
                                stringResource(R.string.swipe_hint),
                                modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(16.dp)).background(panel).padding(horizontal = 12.dp, vertical = 12.dp),
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    }

    if (about) {
        AlertDialog(
            onDismissRequest = { about = false },
            title = { Text(stringResource(R.string.app_name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.about_body))
                    Text(
                        stringResource(R.string.version, BuildConfig.VERSION_NAME),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL))) }
                    }) { Text(stringResource(R.string.source_code)) }
                    TextButton(onClick = { about = false; licenses = true }) {
                        Text(stringResource(R.string.licenses))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { about = false }) { Text(stringResource(R.string.close)) } },
        )
    }
    if (licenses) LicensesDialog(onDismiss = { licenses = false })
    if (merging) io.github.guilhermefeitosa66.openpdfviewer.tools.MergeScreen(onDismiss = { merging = false }, onOpen = { merging = false; onOpen(it) })
}

@Composable
private fun EmptyState(panel: Color) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 96.dp).clip(RoundedCornerShape(20.dp)).background(panel).padding(horizontal = 12.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            AppIcons.Folder, null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(stringResource(R.string.no_recent_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.no_recent_body),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun RecentRow(
    entry: RecentFile,
    recent: RecentFiles,
    rowColor: Color,
    onShare: () -> Unit,
    onSaveCopy: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRemove: () -> Unit,
    onClick: () -> Unit,
) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val thumb by produceState<ImageBitmap?>(null, entry.key, entry.openedAt) {
        value = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(recent.thumbFile(entry).path)?.asImageBitmap() }.getOrNull()
        }
    }
    val today = stringResource(R.string.today)
    val yesterday = stringResource(R.string.yesterday)
    val whenText = remember(entry.openedAt) {
        when {
            DateUtils.isToday(entry.openedAt) -> today
            DateUtils.isToday(entry.openedAt + DateUtils.DAY_IN_MILLIS) -> yesterday
            else -> DateUtils.formatDateTime(
                context, entry.openedAt,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH,
            )
        }
    }
    val pages = entry.pageCount.coerceAtLeast(1)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(rowColor)
            .clickable(onClick = onClick)
            .heightIn(min = 72.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier
                .size(44.dp, 58.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp)),
        ) {
            thumb?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(entry.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.recent_meta, entry.lastPage + 1, pages, whenText),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = { ((entry.lastPage + 1).toFloat() / pages).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(3.dp).padding(top = 0.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outlineVariant,
                drawStopIndicator = {},
                gapSize = 0.dp,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, stringResource(R.string.file_options, entry.name))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.share_file)) }, onClick = { menu = false; onShare() })
                DropdownMenuItem(text = { Text(stringResource(R.string.save_copy)) }, onClick = { menu = false; onSaveCopy() })
                // Looked up only when the menu opens: nothing extra on the first frame.
                val starred = remember(menu) { Library.get(ctx).isFavorite(entry.uri) }
                DropdownMenuItem(
                    text = { Text(stringResource(if (starred) R.string.remove_favorite else R.string.add_favorite)) },
                    onClick = { menu = false; onToggleFavorite() },
                )
                DropdownMenuItem(text = { Text(stringResource(R.string.remove_from_list)) }, onClick = { menu = false; onRemove() })
            }
        }
    }
}

@Composable
private fun LicensesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val files = remember { context.assets.list("licenses")?.sorted().orEmpty() }
    var open by remember { mutableStateOf<String?>(null) }
    val text = remember(open) {
        open?.let { name -> runCatching { context.assets.open("licenses/$name").bufferedReader().readText() }.getOrDefault("") }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(open?.removeSuffix(".txt") ?: stringResource(R.string.licenses)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                if (text != null) {
                    Text(text, fontSize = 12.sp, lineHeight = 16.sp)
                } else {
                    Text(
                        "Kotlin, kotlinx.coroutines, AndroidX, Jetpack Compose, Material 3: Apache License 2.0.\n" +
                            "PDFium: BSD-3-Clause / Apache License 2.0. Its bundled libraries are listed below.\n" +
                            "Digital signatures: PdfBox-Android (Apache License 2.0) and Bouncy Castle (MIT).\n" +
                            "Themes: the Dracula and Tokyo Night color palettes are used under the MIT License; " +
                            "Pastel, Kitty and all background art are original libre-apps work.",
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    files.forEach { f ->
                        TextButton(onClick = { open = f }) { Text(f.removeSuffix(".txt")) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (open != null) open = null else onDismiss() }) {
                Text(stringResource(if (open != null) R.string.back else R.string.close))
            }
        },
        modifier = Modifier.navigationBarsPadding().statusBarsPadding(),
    )
}

/** Recent · Favorites · Folders, as plain text tabs (lighter than a Material TabRow on start-up). */
@Composable
private fun HomeTabs(selected: Int, panel: Color, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().background(panel).padding(horizontal = 8.dp)) {
        for ((i, label) in listOf(R.string.recent, R.string.favorites, R.string.folders).withIndex()) {
            val on = selected == i
            Column(
                Modifier.weight(1f).clickable { onSelect(i) }.padding(top = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(label),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(
                    Modifier.padding(top = 10.dp).fillMaxWidth(0.6f).height(3.dp)
                        .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(2.dp)),
                )
            }
        }
    }
}
