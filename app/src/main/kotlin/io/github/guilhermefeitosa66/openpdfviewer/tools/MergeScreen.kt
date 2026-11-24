package io.github.guilhermefeitosa66.openpdfviewer.tools

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.guilhermefeitosa66.openpdfviewer.R
import io.github.guilhermefeitosa66.openpdfviewer.data.DocumentSource
import io.github.guilhermefeitosa66.openpdfviewer.pdfium.PdfDocument
import io.github.guilhermefeitosa66.openpdfviewer.pdfium.PdfOpenException
import io.github.guilhermefeitosa66.openpdfviewer.ui.CreateTypedDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A file in the merge list. [pages] is -1 until it opens; [locked] while it needs a password. */
private data class MergeItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val pages: Int = -1,
    val password: String? = null,
    val locked: Boolean = false,
    val wrongPassword: Boolean = false,
    val failed: Boolean = false,
)

/** Pick several PDFs, put them in order and write them as one; then open the result. */
@Composable
fun MergeScreen(onDismiss: () -> Unit, onOpen: (Uri) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<MergeItem>() }
    var nextId by remember { mutableStateOf(0L) }
    var working by remember { mutableStateOf(false) }
    val failedText = stringResource(R.string.merge_failed)

    fun update(id: Long, f: (MergeItem) -> MergeItem) {
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0) items[i] = f(items[i])
    }
    fun probe(item: MergeItem) = scope.launch {
        val r = runCatching {
            DocumentSource.open(context, item.uri).use { pfd ->
                val doc = PdfDocument.open(pfd, item.password)
                try { doc.pageCount } finally { doc.closeAsync() }
            }
        }
        val e = r.exceptionOrNull()
        update(item.id) {
            when {
                e == null -> it.copy(pages = r.getOrThrow(), locked = false, wrongPassword = false)
                e is PdfOpenException && e.reason == PdfOpenException.Reason.PASSWORD ->
                    it.copy(locked = true, wrongPassword = it.password != null, password = null)
                else -> it.copy(failed = true)
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        for (u in uris) {
            val item = MergeItem(nextId++, u, DocumentSource.displayName(context, u))
            items += item
            probe(item)
        }
    }
    val saver = rememberLauncherForActivityResult(CreateTypedDocument()) { target ->
        if (target == null) return@rememberLauncherForActivityResult
        working = true
        scope.launch {
            val ok = runCatching { merge(context, items.toList(), target) }
                .onFailure { android.util.Log.w("OpenPdfViewer", "merge failed", it) }.getOrDefault(false)
            working = false
            if (ok) {
                DocumentSource.persistAccess(context, target)
                onOpen(target)
            } else Toast.makeText(context, failedText, Toast.LENGTH_LONG).show()
        }
    }
    val ready = items.size >= 2 && items.all { it.pages >= 0 }
    val total = items.sumOf { it.pages.coerceAtLeast(0) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                    Text(stringResource(R.string.merge_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                }
                Text(
                    if (items.isEmpty()) stringResource(R.string.merge_hint)
                    else pluralStringResource(R.plurals.merge_total, total, items.size, total),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                if (working) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp))
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    itemsIndexed(items, key = { _, it -> it.id }) { i, item ->
                        MergeRow(
                            item, i, items.size,
                            onUp = { items.add(i - 1, items.removeAt(i)) },
                            onDown = { items.add(i + 1, items.removeAt(i)) },
                            onRemove = { items.removeAt(i) },
                            onPassword = { pw -> update(item.id) { it.copy(password = pw) }.also { probe(item.copy(password = pw)) } },
                        )
                        HorizontalDivider()
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                ) {
                    OutlinedButton(onClick = { picker.launch(arrayOf("application/pdf")) }, enabled = !working) { Text(stringResource(R.string.add_files)) }
                    Button(
                        enabled = ready && !working,
                        onClick = { saver.launch(io.github.guilhermefeitosa66.openpdfviewer.ui.FileActions.safeName(context.getString(R.string.merged_name)) to "application/pdf") },
                    ) { Text(stringResource(R.string.merge)) }
                }
            }
        }
    }
}

@Composable
private fun MergeRow(item: MergeItem, index: Int, count: Int, onUp: () -> Unit, onDown: () -> Unit, onRemove: () -> Unit, onPassword: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        item.failed -> stringResource(R.string.cannot_open_pdf)
                        item.locked -> stringResource(R.string.needs_password)
                        item.pages < 0 -> "…"
                        else -> pluralStringResource(R.plurals.n_pages, item.pages, item.pages)
                    },
                    fontSize = 13.sp,
                    color = if (item.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onUp, enabled = index > 0) { Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.move_up)) }
            IconButton(onClick = onDown, enabled = index < count - 1) { Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.move_down)) }
            IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, stringResource(R.string.remove_from_list)) }
        }
        if (item.locked) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pw, onValueChange = { pw = it }, singleLine = true,
                    label = { Text(stringResource(R.string.password)) },
                    isError = item.wrongPassword,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onPassword(pw) }) { Text(stringResource(R.string.open)) }
            }
        }
    }
}

/** Opens every file (with its password) and writes them as one PDF to [target]. */
private suspend fun merge(context: android.content.Context, items: List<MergeItem>, target: Uri): Boolean {
    val pfds = ArrayList<android.os.ParcelFileDescriptor>()
    val docs = ArrayList<PdfDocument>()
    try {
        for (it in items) {
            val pfd = DocumentSource.open(context, it.uri).also(pfds::add)
            docs += PdfDocument.open(pfd, it.password)
        }
        val out = File(File(context.cacheDir, "save").apply { mkdirs() }, "merged.pdf")
        val written = withContext(Dispatchers.IO) {
            android.os.ParcelFileDescriptor.open(
                out,
                android.os.ParcelFileDescriptor.MODE_CREATE or android.os.ParcelFileDescriptor.MODE_TRUNCATE or android.os.ParcelFileDescriptor.MODE_WRITE_ONLY,
            )
        }.use { PdfDocument.merge(docs, it) }
        return written && DocumentSource.overwrite(context, target, out)
    } finally {
        docs.forEach { it.closeAsync() }
        pfds.forEach { runCatching { it.close() } }
    }
}
