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
            PanelItem(stringResource(R.string.save_flattened)) { onDismiss(); vm.requestFlatten = true }
        }
    }
}

/** Dialogs and progress of the file tools. */
@Composable
fun ToolEffects(vm: ViewerViewModel) {
    val context = LocalContext.current
    if (vm.compressOpen) CompressDialog(onDismiss = { vm.compressOpen = false }, onCompress = vm::compress)
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
