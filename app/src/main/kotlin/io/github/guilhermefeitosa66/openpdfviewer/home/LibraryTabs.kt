package io.github.guilhermefeitosa66.openpdfviewer.home

import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.guilhermefeitosa66.openpdfviewer.R
import io.github.guilhermefeitosa66.openpdfviewer.data.Library
import io.github.guilhermefeitosa66.openpdfviewer.data.LibraryFile
import io.github.guilhermefeitosa66.openpdfviewer.data.LibraryFolder
import io.github.guilhermefeitosa66.openpdfviewer.ui.FileActions
import kotlinx.coroutines.launch

/** Starred files, kept even after they leave the recent list. */
@Composable
fun FavoritesTab(panel: Color, fileActions: FileActions, onOpen: (Uri) -> Unit) {
    val context = LocalContext.current
    val library = remember { Library.get(context) }
    LaunchedEffect(Unit) { library.load() }
    val favorites by library.favorites.collectAsState()
    if (favorites.isEmpty()) {
        Message(panel, stringResource(R.string.no_favorites))
        return
    }
    FileList(favorites, panel, fileActions, onOpen)
}

/** Folders granted through the system picker, with the PDFs inside them. */
@Composable
fun FoldersTab(panel: Color, fileActions: FileActions, onOpen: (Uri) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val library = remember { Library.get(context) }
    LaunchedEffect(Unit) { library.load() }
    val folders by library.folders.collectAsState()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val current = folders.firstOrNull { it.uri == selected } ?: folders.firstOrNull()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) scope.launch {
            library.addFolder(tree)
            selected = tree.toString()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(panel).horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (f in folders) {
                InputChip(
                    selected = f.uri == current?.uri,
                    onClick = { selected = f.uri },
                    label = { Text(f.name, maxLines = 1) },
                    trailingIcon = {
                        Icon(
                            Icons.Filled.Close,
                            stringResource(R.string.remove_folder, f.name),
                            Modifier.clickable { scope.launch { library.removeFolder(f) } },
                        )
                    },
                )
            }
            FilterChip(
                selected = false,
                onClick = { picker.launch(null) },
                label = { Text(stringResource(R.string.add_folder)) },
                leadingIcon = { Icon(Icons.Filled.Add, null) },
            )
        }
        if (current == null) {
            Message(panel, stringResource(R.string.no_folders))
        } else {
            FolderContents(current, panel, fileActions, onOpen)
        }
    }
}

@Composable
private fun FolderContents(folder: LibraryFolder, panel: Color, fileActions: FileActions, onOpen: (Uri) -> Unit) {
    val context = LocalContext.current
    val library = remember { Library.get(context) }
    var files by remember(folder.uri) { mutableStateOf<List<LibraryFile>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(Library.Companion.Sort.NAME) }
    LaunchedEffect(folder.uri) { files = library.scan(folder) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(panel).padding(start = 12.dp, end = 4.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text(stringResource(R.string.search_files)) },
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1f),
            )
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.sort_by)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    for ((s, label) in listOf(
                        Library.Companion.Sort.NAME to R.string.sort_name,
                        Library.Companion.Sort.DATE to R.string.sort_date,
                        Library.Companion.Sort.SIZE to R.string.sort_size,
                    )) {
                        DropdownMenuItem(
                            text = { Text(stringResource(label)) },
                            trailingIcon = { if (sort == s) Icon(Icons.Filled.Check, null) },
                            onClick = { sort = s; menu = false },
                        )
                    }
                }
            }
        }
        val list = files
        when {
            list == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Message(panel, stringResource(R.string.no_pdfs_in_folder))
            else -> FileList(Library.filterAndSort(list, query, sort), panel, fileActions, onOpen)
        }
    }
}

@Composable
private fun FileList(files: List<LibraryFile>, panel: Color, fileActions: FileActions, onOpen: (Uri) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 96.dp), modifier = Modifier.fillMaxSize()) {
        items(files, key = { it.uri }) { f -> LibraryRow(f, panel, fileActions, onOpen) }
    }
}

@Composable
private fun LibraryRow(file: LibraryFile, panel: Color, fileActions: FileActions, onOpen: (Uri) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val library = remember { Library.get(context) }
    val favorites by library.favorites.collectAsState()
    val starred = favorites.any { it.uri == file.uri }
    var menu by remember { mutableStateOf(false) }
    val meta = buildList {
        if (file.size >= 0) add(Formatter.formatShortFileSize(context, file.size))
        if (file.modified > 0) add(DateUtils.formatDateTime(context, file.modified, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH))
    }.joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(panel).clickable { onOpen(Uri.parse(file.uri)) }
            .heightIn(min = 64.dp).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(file.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (meta.isNotEmpty()) Text(meta, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { scope.launch { library.toggleFavorite(file) } }) {
            Icon(
                if (starred) Icons.Filled.Star else io.github.guilhermefeitosa66.openpdfviewer.ui.AppIcons.StarOutline,
                stringResource(if (starred) R.string.remove_favorite else R.string.add_favorite),
                tint = if (starred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.file_options, file.name)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.share_file)) }, onClick = { menu = false; fileActions.share(Uri.parse(file.uri), file.name) })
                DropdownMenuItem(text = { Text(stringResource(R.string.save_copy)) }, onClick = { menu = false; fileActions.saveCopy(Uri.parse(file.uri), file.name) })
            }
        }
    }
}

@Composable
private fun Message(panel: Color, text: String) {
    Text(
        text,
        modifier = Modifier.padding(20.dp).clip(RoundedCornerShape(16.dp)).background(panel).padding(16.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )
}
