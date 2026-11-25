package io.github.guilhermefeitosa66.openpdfviewer.viewer

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.guilhermefeitosa66.openpdfviewer.R
import io.github.guilhermefeitosa66.openpdfviewer.tools.PdfTools

/** File tools for the open document. Each one writes a new version and asks before keeping it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsPanel(vm: ViewerViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            PanelItem(stringResource(R.string.compress)) { onDismiss(); vm.compressOpen = true }
            PanelItem(stringResource(R.string.export_images)) { onDismiss(); vm.imagesExportOpen = true }
            PanelItem(stringResource(R.string.export_text)) { onDismiss(); vm.textExportOpen = true }
            PanelItem(stringResource(R.string.extract_images)) { onDismiss(); vm.extractImagesOpen = true }
            PanelItem(stringResource(R.string.save_flattened)) { onDismiss(); vm.requestFlatten = true }
        }
    }
}

/** Dialogs and progress of the file tools. */
@Composable
fun ToolEffects(vm: ViewerViewModel) {
    val context = LocalContext.current
    if (vm.compressOpen) CompressDialog(onDismiss = { vm.compressOpen = false }, onCompress = vm::compress)
    if (vm.textExportOpen) {
        AlertDialog(
            onDismissRequest = { vm.textExportOpen = false },
            title = { Text(stringResource(R.string.export_text)) },
            text = { Text(stringResource(R.string.export_text_hint)) },
            confirmButton = {
                Row {
                    TextButton(onClick = { vm.exportText(markdown = false) }) { Text(stringResource(R.string.plain_text)) }
                    TextButton(onClick = { vm.exportText(markdown = true) }) { Text("Markdown") }
                }
            },
            dismissButton = { TextButton(onClick = { vm.textExportOpen = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    val plainSaver = androidx.activity.compose.rememberLauncherForActivityResult(io.github.guilhermefeitosa66.openpdfviewer.ui.CreateTypedDocument()) { vm.savePlainFile(it) }
    vm.plainFileToSave?.let { pending ->
        androidx.compose.runtime.LaunchedEffect(pending) { plainSaver.launch(pending.second to pending.third) }
    }
    if (vm.extractImagesOpen) {
        val folder = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { tree ->
            if (tree != null) vm.extractImages(tree)
        }
        AlertDialog(
            onDismissRequest = { vm.extractImagesOpen = false },
            title = { Text(stringResource(R.string.extract_images)) },
            text = { Text(stringResource(R.string.extract_images_hint)) },
            confirmButton = {
                Row {
                    TextButton(onClick = { vm.extractImages(null) }) { Text(stringResource(R.string.share_zip)) }
                    TextButton(onClick = { folder.launch(null) }) { Text(stringResource(R.string.save_to_folder)) }
                }
            },
            dismissButton = { TextButton(onClick = { vm.extractImagesOpen = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (vm.noImagesFound) {
        AlertDialog(
            onDismissRequest = { vm.noImagesFound = false },
            text = { Text(stringResource(R.string.no_images_found)) },
            confirmButton = { TextButton(onClick = { vm.noImagesFound = false }) { Text(stringResource(R.string.ok)) } },
        )
    }
    (vm.state as? DocState.Ready)?.let { r ->
        if (vm.imagesExportOpen) ExportImagesDialog(r.doc.pageCount, onDismiss = { vm.imagesExportOpen = false }, onExport = vm::exportImages)
    }
    vm.toolProgress?.let { p ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.working)) },
            text = { LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {},
        )
    }
    vm.compressResult?.let { res ->
        val before = Formatter.formatShortFileSize(context, res.before)
        val after = Formatter.formatShortFileSize(context, res.after)
        val smaller = res.after < res.before
        AlertDialog(
            onDismissRequest = { vm.compressResult = null },
            title = { Text(stringResource(R.string.compress)) },
            text = {
                Text(
                    if (smaller) stringResource(R.string.compress_result, before, after, ((res.before - res.after) * 100 / res.before).toInt())
                    else stringResource(R.string.compress_no_gain, before),
                )
            },
            confirmButton = {
                if (smaller) {
                    Row {
                        TextButton(onClick = { vm.keepCompressed(asCopy = true) }) { Text(stringResource(R.string.save_as_copy)) }
                        TextButton(onClick = { vm.keepCompressed(asCopy = false) }) { Text(stringResource(R.string.save)) }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { vm.compressResult = null }) { Text(stringResource(if (smaller) R.string.discard else R.string.ok)) } },
        )
    }
}

@Composable
private fun CompressDialog(onDismiss: () -> Unit, onCompress: (PdfTools.Compression, Boolean) -> Unit) {
    var level by remember { mutableStateOf(PdfTools.Compression.RECOMMENDED) }
    var strip by remember { mutableStateOf(false) }
    val labels = mapOf(
        PdfTools.Compression.LIGHT to R.string.compress_light,
        PdfTools.Compression.RECOMMENDED to R.string.compress_recommended,
        PdfTools.Compression.STRONG to R.string.compress_strong,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.compress)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.compress_hint), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                for ((l, label) in labels) {
                    Row(Modifier.fillMaxWidth().clickable { level = l }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = level == l, onClick = { level = l })
                        Text(stringResource(label))
                    }
                }
                Row(Modifier.fillMaxWidth().clickable { strip = !strip }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = strip, onCheckedChange = { strip = it })
                    Text(stringResource(R.string.remove_metadata))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onCompress(level, strip) }) { Text(stringResource(R.string.compress_action)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ExportImagesDialog(pageCount: Int, onDismiss: () -> Unit, onExport: (List<Int>, Int, Boolean, android.net.Uri?) -> Unit) {
    var png by remember { mutableStateOf(true) }
    var dpi by remember { mutableStateOf(150) }
    var range by remember { mutableStateOf("") }
    val pages = PageRanges.parse(range, pageCount)
    val folder = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null && pages != null) onExport(pages, dpi, png, tree)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export_images)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilterChip(selected = png, onClick = { png = true }, label = { Text("PNG") })
                    androidx.compose.material3.FilterChip(selected = !png, onClick = { png = false }, label = { Text("JPEG") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (d in listOf(72, 150, 300)) {
                        androidx.compose.material3.FilterChip(selected = dpi == d, onClick = { dpi = d }, label = { Text("$d dpi") })
                    }
                }
                androidx.compose.material3.OutlinedTextField(
                    value = range, onValueChange = { range = it.take(80) }, singleLine = true,
                    label = { Text(stringResource(R.string.pages_to_insert)) },
                    placeholder = { Text(stringResource(R.string.all_pages_n, pageCount)) },
                    isError = pages == null,
                    supportingText = { Text(stringResource(R.string.range_hint)) },
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(enabled = pages != null, onClick = { pages?.let { onExport(it, dpi, png, null) } }) { Text(stringResource(R.string.share_zip)) }
                TextButton(enabled = pages != null, onClick = { folder.launch(null) }) { Text(stringResource(R.string.save_to_folder)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
