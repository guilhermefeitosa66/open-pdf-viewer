package io.github.guilhermefeitosa66.openpdfviewer.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.guilhermefeitosa66.openpdfviewer.R
import io.github.guilhermefeitosa66.openpdfviewer.data.DocumentSource
import io.github.guilhermefeitosa66.openpdfviewer.ui.AppIcons
import io.github.guilhermefeitosa66.openpdfviewer.ui.CreateTypedDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class PickedImage(val id: Long, val uri: Uri, val turns: Int = 0)

/** Pick photos (no permission: the system photo picker), arrange them and make a PDF. */
@Composable
fun ImagesScreen(onDismiss: () -> Unit, onOpen: (Uri) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<PickedImage>() }
    var nextId by remember { mutableStateOf(0L) }
    var size by remember { mutableStateOf<Pair<Float, Float>?>(ImagePageOptions.A4) }
    var margin by remember { mutableStateOf(0f) }
    var landscape by remember { mutableStateOf<Boolean?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    val failed = stringResource(R.string.images_failed)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        for (u in uris) items += PickedImage(nextId++, u)
    }
    val saver = rememberLauncherForActivityResult(CreateTypedDocument()) { target ->
        if (target == null) return@rememberLauncherForActivityResult
        val list = items.toList()
        val options = ImagePageOptions(size, margin, landscape)
        progress = 0f
        scope.launch {
            val ok = runCatching {
                val out = File(File(context.cacheDir, "save").apply { mkdirs() }, "images.pdf")
                ImagesToPdf.write(context, list.size, { i -> decode(context, list[i].uri, list[i].turns, 3000) }, options, out) { p ->
                    scope.launch { progress = p }
                }
                DocumentSource.overwrite(context, target, out)
            }.onFailure { android.util.Log.w("OpenPdfViewer", "images to pdf failed", it) }.getOrDefault(false)
            progress = null
            if (ok) {
                DocumentSource.persistAccess(context, target)
                onOpen(target)
            } else Toast.makeText(context, failed, Toast.LENGTH_LONG).show()
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                    Text(stringResource(R.string.images_to_pdf), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                }
                progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) }
                if (items.isEmpty()) {
                    Text(
                        stringResource(R.string.images_hint), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(20.dp),
                    )
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    itemsIndexed(items, key = { _, it -> it.id }) { i, item ->
                        ImageRow(
                            item, i, items.size,
                            onRotate = { items[i] = item.copy(turns = (item.turns + 1) % 4) },
                            onUp = { items.add(i - 1, items.removeAt(i)) },
                            onDown = { items.add(i + 1, items.removeAt(i)) },
                            onRemove = { items.removeAt(i) },
                        )
                        HorizontalDivider()
                    }
                }
                // Page options.
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ChipRow(stringResource(R.string.page_size), listOf(
                        stringResource(R.string.size_fit_image) to (size == null),
                        "A4" to (size == ImagePageOptions.A4),
                        stringResource(R.string.size_letter) to (size == ImagePageOptions.LETTER),
                    )) { size = listOf(null, ImagePageOptions.A4, ImagePageOptions.LETTER)[it] }
                    ChipRow(stringResource(R.string.margin), listOf(
                        stringResource(R.string.margin_none) to (margin == 0f),
                        stringResource(R.string.margin_small) to (margin == 18f),
                        stringResource(R.string.margin_large) to (margin == 54f),
                    )) { margin = listOf(0f, 18f, 54f)[it] }
                    if (size != null) {
                        ChipRow(stringResource(R.string.orientation), listOf(
                            stringResource(R.string.orientation_auto) to (landscape == null),
                            stringResource(R.string.orientation_portrait) to (landscape == false),
                            stringResource(R.string.orientation_landscape) to (landscape == true),
                        )) { landscape = listOf(null, false, true)[it] }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                    OutlinedButton(
                        onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        enabled = progress == null,
                    ) { Text(stringResource(R.string.add_images)) }
                    Button(
                        enabled = items.isNotEmpty() && progress == null,
                        onClick = { saver.launch(io.github.guilhermefeitosa66.openpdfviewer.ui.FileActions.safeName(context.getString(R.string.images_name)) to "application/pdf") },
                    ) { Text(stringResource(R.string.create_pdf)) }
                }
            }
        }
    }
}

@Composable
private fun ChipRow(label: String, options: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        options.forEachIndexed { i, (text, selected) -> FilterChip(selected = selected, onClick = { onPick(i) }, label = { Text(text) }) }
    }
}

@Composable
private fun ImageRow(item: PickedImage, index: Int, count: Int, onRotate: () -> Unit, onUp: () -> Unit, onDown: () -> Unit, onRemove: () -> Unit) {
    val context = LocalContext.current
    var thumb by remember(item.uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(item.uri) { thumb = decode(context, item.uri, 0, 240)?.asImageBitmap() }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x11000000)), contentAlignment = Alignment.Center) {
            thumb?.let { Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().rotate(item.turns * 90f)) }
        }
        Text("${index + 1}", modifier = Modifier.padding(start = 12.dp).weight(1f), fontSize = 15.sp)
        IconButton(onClick = onRotate) { Icon(AppIcons.RotateRight, stringResource(R.string.rotate_right)) }
        IconButton(onClick = onUp, enabled = index > 0) { Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.move_up)) }
        IconButton(onClick = onDown, enabled = index < count - 1) { Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.move_down)) }
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, stringResource(R.string.remove_from_list)) }
    }
}

/** Decodes an image (EXIF orientation applied) to at most [maxSide] pixels, then turns it. */
private suspend fun decode(context: Context, uri: Uri, turns: Int, maxSide: Int): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val bmp = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val side = maxOf(info.size.width, info.size.height)
                if (side > maxSide) d.setTargetSize(info.size.width * maxSide / side, info.size.height * maxSide / side)
            }
        } else {
            @Suppress("DEPRECATION") MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }
        if (turns % 4 == 0) bmp else {
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(turns * 90f) }, true)
                .also { if (it !== bmp) bmp.recycle() }
        }
    }.getOrNull()
}
