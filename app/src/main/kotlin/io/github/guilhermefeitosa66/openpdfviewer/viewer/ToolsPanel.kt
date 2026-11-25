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
import kotlinx.coroutines.launch
import io.github.guilhermefeitosa66.openpdfviewer.tools.NumberPosition as P

/** File tools for the open document. Each one writes a new version and asks before keeping it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsPanel(vm: ViewerViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            PanelItem(stringResource(R.string.compress)) { onDismiss(); vm.compressOpen = true }
            PanelItem(stringResource(R.string.page_numbers)) { onDismiss(); vm.pageNumbersOpen = true }
            PanelItem(stringResource(R.string.watermark)) { onDismiss(); vm.watermarkOpen = true }
            PanelItem(stringResource(R.string.export_images)) { onDismiss(); vm.imagesExportOpen = true }
            PanelItem(stringResource(R.string.export_text)) { onDismiss(); vm.textExportOpen = true }
            PanelItem(stringResource(R.string.extract_images)) { onDismiss(); vm.extractImagesOpen = true }
            PanelItem(stringResource(R.string.edit_properties)) { onDismiss(); vm.loadInfo(); vm.metadataOpen = true }
            PanelItem(stringResource(R.string.protect)) { onDismiss(); vm.protectOpen = true }
            PanelItem(stringResource(R.string.unprotect)) { onDismiss(); vm.unprotectOpen = true }
            PanelItem(stringResource(R.string.save_flattened)) { onDismiss(); vm.requestFlatten = true }
        }
    }
}

/** Dialogs and progress of the file tools. */
@Composable
fun ToolEffects(vm: ViewerViewModel) {
    val context = LocalContext.current
    if (vm.compressOpen) CompressDialog(onDismiss = { vm.compressOpen = false }, onCompress = vm::compress)
    if (vm.pageNumbersOpen) {
        PageNumbersDialog(onDismiss = { vm.pageNumbersOpen = false }) { o, asCopy ->
            vm.pageNumbersOpen = false
            val app = context.applicationContext
            vm.runFileTool("numbered", asCopy) { input, output ->
                io.github.guilhermefeitosa66.openpdfviewer.tools.Stamping.addPageNumbers(app, input, output, o, vm.documentPassword)
            }
        }
    }
    (vm.state as? DocState.Ready)?.let { r ->
        if (vm.watermarkOpen) {
            WatermarkDialog(r.doc.pageCount, onDismiss = { vm.watermarkOpen = false }) { o, asCopy ->
                vm.watermarkOpen = false
                val app = context.applicationContext
                vm.runFileTool("watermarked", asCopy) { input, output ->
                    io.github.guilhermefeitosa66.openpdfviewer.tools.Stamping.addWatermark(app, input, output, o, vm.documentPassword)
                }
            }
        }
    }
    if (vm.metadataOpen) {
        vm.info?.let { info ->
            MetadataDialog(info, onDismiss = { vm.metadataOpen = false }) { fields, strip, asCopy ->
                vm.metadataOpen = false
                val app = context.applicationContext
                vm.runFileTool("properties", asCopy) { input, output ->
                    io.github.guilhermefeitosa66.openpdfviewer.tools.Metadata.write(app, input, output, fields, strip, vm.documentPassword)
                }
            }
        }
    }
    if (vm.protectOpen) {
        ProtectDialog(onDismiss = { vm.protectOpen = false }) { open, owner, restr, asCopy ->
            vm.protectOpen = false
            val app = context.applicationContext
            vm.runFileTool("protected", asCopy) { input, output ->
                io.github.guilhermefeitosa66.openpdfviewer.tools.Security.protect(app, input, output, open, owner, restr, vm.documentPassword)
            }
        }
    }
    if (vm.unprotectOpen) {
        var pw by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { vm.unprotectOpen = false },
            title = { Text(stringResource(R.string.unprotect)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.unprotect_hint), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PasswordField(pw, { pw = it }, stringResource(R.string.password))
                }
            },
            confirmButton = {
                Row {
                    TextButton(enabled = pw.isNotEmpty(), onClick = { vm.removePassword(pw, asCopy = true) }) { Text(stringResource(R.string.save_as_copy)) }
                    TextButton(enabled = pw.isNotEmpty(), onClick = { vm.removePassword(pw, asCopy = false) }) { Text(stringResource(R.string.save)) }
                }
            },
            dismissButton = { TextButton(onClick = { vm.unprotectOpen = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    vm.unprotectProblem?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.unprotectProblem = null },
            text = { Text(stringResource(msg)) },
            confirmButton = { TextButton(onClick = { vm.unprotectProblem = null }) { Text(stringResource(R.string.ok)) } },
        )
    }
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
            text = {
                if (p > 0f) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            },
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

@Composable
private fun PageNumbersDialog(onDismiss: () -> Unit, onApply: (io.github.guilhermefeitosa66.openpdfviewer.tools.PageNumberOptions, Boolean) -> Unit) {
    var position by remember { mutableStateOf(P.BOTTOM_CENTER) }
    val formats = listOf("{n}", "{n} / {total}", stringResource(R.string.page_n_of_total, "{n}", "{total}"))
    var format by remember { mutableStateOf(0) }
    var size by remember { mutableStateOf(11f) }
    var fromPage by remember { mutableStateOf("1") }
    var firstNumber by remember { mutableStateOf("1") }
    val from = fromPage.toIntOrNull()?.takeIf { it >= 1 }
    val first = firstNumber.toIntOrNull()?.takeIf { it >= 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.page_numbers)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.position), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                for (row in listOf(listOf(P.TOP_LEFT, P.TOP_CENTER, P.TOP_RIGHT), listOf(P.BOTTOM_LEFT, P.BOTTOM_CENTER, P.BOTTOM_RIGHT))) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (p in row) {
                            androidx.compose.material3.FilterChip(
                                selected = position == p, onClick = { position = p },
                                label = { Text(stringResource(when (p.align) { -1 -> R.string.align_left; 0 -> R.string.align_center; else -> R.string.align_right })) },
                            )
                        }
                    }
                }
                Text(stringResource(R.string.format), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                formats.forEachIndexed { i, f ->
                    Row(Modifier.fillMaxWidth().clickable { format = i }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = format == i, onClick = { format = i })
                        Text(f.replace("{n}", "1").replace("{total}", "10"))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.text_size), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    for (sz in listOf(9f, 11f, 14f)) {
                        androidx.compose.material3.FilterChip(selected = size == sz, onClick = { size = sz }, label = { Text("${sz.toInt()} pt") })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.OutlinedTextField(
                        value = fromPage, onValueChange = { fromPage = it.filter(Char::isDigit).take(5) }, singleLine = true,
                        label = { Text(stringResource(R.string.from_page)) }, isError = from == null, modifier = Modifier.weight(1f),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = firstNumber, onValueChange = { firstNumber = it.filter(Char::isDigit).take(5) }, singleLine = true,
                        label = { Text(stringResource(R.string.first_number)) }, isError = first == null, modifier = Modifier.weight(1f),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    )
                }
            }
        },
        confirmButton = {
            val ok = from != null && first != null
            fun opts() = io.github.guilhermefeitosa66.openpdfviewer.tools.PageNumberOptions(position, formats[format], size, fromPage = from!! - 1, firstNumber = first!!)
            Row {
                TextButton(enabled = ok, onClick = { onApply(opts(), true) }) { Text(stringResource(R.string.save_as_copy)) }
                TextButton(enabled = ok, onClick = { onApply(opts(), false) }) { Text(stringResource(R.string.save)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun WatermarkDialog(pageCount: Int, onDismiss: () -> Unit, onApply: (io.github.guilhermefeitosa66.openpdfviewer.tools.WatermarkOptions, Boolean) -> Unit) {
    val context = LocalContext.current
    var useImage by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(context.getString(R.string.stamp_confidential)) }
    var image by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var opacity by remember { mutableStateOf(0.3f) }
    var rotation by remember { mutableStateOf(45f) }
    var tile by remember { mutableStateOf(false) }
    var under by remember { mutableStateOf(false) }
    var range by remember { mutableStateOf("") }
    val pages = PageRanges.parse(range, pageCount)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            image = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
                        d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                        val side = maxOf(info.size.width, info.size.height)
                        if (side > 1500) d.setTargetSize(info.size.width * 1500 / side, info.size.height * 1500 / side)
                    }
                }.getOrNull()
            }
        }
    }
    @Composable
    fun chips(label: Int, options: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(label), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            options.forEachIndexed { i, (t, sel) -> androidx.compose.material3.FilterChip(selected = sel, onClick = { onPick(i) }, label = { Text(t) }) }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.watermark)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                chips(R.string.watermark_kind, listOf(stringResource(R.string.plain_text) to !useImage, stringResource(R.string.image) to useImage)) { useImage = it == 1 }
                if (useImage) {
                    androidx.compose.material3.OutlinedButton(onClick = {
                        picker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Text(stringResource(if (image == null) R.string.choose_image else R.string.change_image)) }
                } else {
                    androidx.compose.material3.OutlinedTextField(value = text, onValueChange = { text = it.take(60) }, singleLine = true)
                }
                chips(R.string.opacity, listOf("15%" to (opacity == 0.15f), "30%" to (opacity == 0.3f), "50%" to (opacity == 0.5f))) { opacity = listOf(0.15f, 0.3f, 0.5f)[it] }
                chips(R.string.rotation, listOf("0°" to (rotation == 0f), "45°" to (rotation == 45f), "90°" to (rotation == 90f))) { rotation = listOf(0f, 45f, 90f)[it] }
                chips(R.string.layout_label, listOf(stringResource(R.string.align_center) to !tile, stringResource(R.string.tiled) to tile)) { tile = it == 1 }
                chips(R.string.layer, listOf(stringResource(R.string.over_content) to !under, stringResource(R.string.under_content) to under)) { under = it == 1 }
                androidx.compose.material3.OutlinedTextField(
                    value = range, onValueChange = { range = it.take(80) }, singleLine = true,
                    label = { Text(stringResource(R.string.pages_to_insert)) },
                    placeholder = { Text(stringResource(R.string.all_pages_n, pageCount)) },
                    isError = pages == null,
                )
                if (under) Text(stringResource(R.string.under_note), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            val ok = pages != null && (if (useImage) image != null else text.isNotBlank())
            fun opts() = io.github.guilhermefeitosa66.openpdfviewer.tools.WatermarkOptions(
                text = if (useImage) null else text.trim(), image = if (useImage) image else null,
                opacity = opacity, rotation = rotation, tile = tile, under = under,
                pages = pages?.toSet()?.takeIf { range.isNotBlank() },
            )
            Row {
                TextButton(enabled = ok, onClick = { onApply(opts(), true) }) { Text(stringResource(R.string.save_as_copy)) }
                TextButton(enabled = ok, onClick = { onApply(opts(), false) }) { Text(stringResource(R.string.save)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String, isError: Boolean = false) {
    androidx.compose.material3.OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true, isError = isError,
        label = { Text(label) },
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
    )
}

@Composable
private fun ProtectDialog(onDismiss: () -> Unit, onApply: (String, String, io.github.guilhermefeitosa66.openpdfviewer.tools.Security.Restrictions, Boolean) -> Unit) {
    var open by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var restrict by remember { mutableStateOf(false) }
    var owner by remember { mutableStateOf("") }
    var print by remember { mutableStateOf(true) }
    var copy by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf(false) }
    val mismatch = confirm.isNotEmpty() && confirm != open
    val ok = (open.isNotEmpty() && open == confirm || open.isEmpty() && restrict) && (!restrict || owner.isNotEmpty()) && owner != open.ifEmpty { "\u0000" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.protect)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PasswordField(open, { open = it }, stringResource(R.string.open_password))
                PasswordField(confirm, { confirm = it }, stringResource(R.string.confirm_password), isError = mismatch)
                Row(Modifier.fillMaxWidth().clickable { restrict = !restrict }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = restrict, onCheckedChange = { restrict = it })
                    Text(stringResource(R.string.restrict_permissions))
                }
                if (restrict) {
                    PasswordField(owner, { owner = it }, stringResource(R.string.permissions_password), isError = owner.isNotEmpty() && owner == open)
                    for ((label, value, set) in listOf(
                        Triple(R.string.allow_print, print) { v: Boolean -> print = v },
                        Triple(R.string.allow_copy, copy) { v: Boolean -> copy = v },
                        Triple(R.string.allow_edit, edit) { v: Boolean -> edit = v },
                    )) {
                        Row(Modifier.fillMaxWidth().clickable { set(!value) }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = value, onCheckedChange = set)
                            Text(stringResource(label))
                        }
                    }
                }
                Text(stringResource(R.string.protect_note), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            val restr = if (restrict) io.github.guilhermefeitosa66.openpdfviewer.tools.Security.Restrictions(print, copy, edit)
            else io.github.guilhermefeitosa66.openpdfviewer.tools.Security.Restrictions()
            Row {
                TextButton(enabled = ok, onClick = { onApply(open, if (restrict) owner else "", restr, true) }) { Text(stringResource(R.string.save_as_copy)) }
                TextButton(enabled = ok, onClick = { onApply(open, if (restrict) owner else "", restr, false) }) { Text(stringResource(R.string.save)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun MetadataDialog(
    info: io.github.guilhermefeitosa66.openpdfviewer.pdfium.DocumentInfo,
    onDismiss: () -> Unit,
    onApply: (io.github.guilhermefeitosa66.openpdfviewer.tools.Metadata.Fields, Boolean, Boolean) -> Unit,
) {
    var title by remember { mutableStateOf(info.title) }
    var author by remember { mutableStateOf(info.author) }
    var subject by remember { mutableStateOf(info.subject) }
    var keywords by remember { mutableStateOf(info.keywords) }
    var strip by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_properties)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((label, value, set) in listOf(
                    Triple(R.string.info_title, title) { v: String -> title = v },
                    Triple(R.string.info_author, author) { v: String -> author = v },
                    Triple(R.string.info_subject, subject) { v: String -> subject = v },
                    Triple(R.string.info_keywords, keywords) { v: String -> keywords = v },
                )) {
                    androidx.compose.material3.OutlinedTextField(value = value, onValueChange = { set(it.take(300)) }, singleLine = true, label = { Text(stringResource(label)) })
                }
                Row(Modifier.fillMaxWidth().clickable { strip = !strip }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = strip, onCheckedChange = { strip = it })
                    Text(stringResource(R.string.remove_hidden_metadata))
                }
                if (strip) Text(stringResource(R.string.remove_hidden_metadata_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            val f = io.github.guilhermefeitosa66.openpdfviewer.tools.Metadata.Fields(title, author, subject, keywords)
            Row {
                TextButton(onClick = { onApply(f, strip, true) }) { Text(stringResource(R.string.save_as_copy)) }
                TextButton(onClick = { onApply(f, strip, false) }) { Text(stringResource(R.string.save)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
