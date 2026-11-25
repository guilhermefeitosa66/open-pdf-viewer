package io.github.guilhermefeitosa66.openpdfviewer.viewer

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.guilhermefeitosa66.openpdfviewer.R
import io.github.guilhermefeitosa66.openpdfviewer.data.DocumentSource
import io.github.guilhermefeitosa66.openpdfviewer.pdfium.PdfDocument
import io.github.guilhermefeitosa66.openpdfviewer.pdfium.PdfOpenException
import io.github.guilhermefeitosa66.openpdfviewer.tools.DiffRun
import io.github.guilhermefeitosa66.openpdfviewer.tools.WordDiff
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The differences on one page: word runs, and whether anything changed. */
private class PageDiff(val page: Int, val runs: List<DiffRun>) {
    val changed = runs.any { it.kind != DiffRun.Kind.SAME }
}

private sealed interface CompareState {
    data object Picking : CompareState
    data class NeedsPassword(val uri: Uri, val wrong: Boolean) : CompareState
    data object Working : CompareState
    data class Done(val otherName: String, val pages: List<PageDiff>) : CompareState
    data object Failed : CompareState
}

/** Text differences, page by page, between the open document and another PDF. */
@Composable
fun CompareScreen(ready: DocState.Ready, onGoTo: (Int) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var state by remember { mutableStateOf<CompareState>(CompareState.Picking) }
    var request by remember { mutableStateOf<Pair<Uri, String?>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) onDismiss() else request = uri to null
    }
    LaunchedEffect(Unit) { picker.launch(arrayOf("application/pdf")) }
    LaunchedEffect(request) {
        val (uri, pw) = request ?: return@LaunchedEffect
        state = CompareState.Working
        state = runCatching {
            DocumentSource.open(context, uri).use { pfd ->
                val other = PdfDocument.open(pfd, pw)
                try {
                    fun clean(s: String) = s.replace('\u0002', ' ')
                    val count = maxOf(ready.doc.pageCount, other.pageCount)
                    val pages = (0 until count).map { i ->
                        val a = if (i < ready.doc.pageCount) clean(ready.doc.text(i)) else ""
                        val b = if (i < other.pageCount) clean(other.text(i)) else ""
                        PageDiff(i, withContext(Dispatchers.Default) { WordDiff.diff(WordDiff.words(a), WordDiff.words(b)) })
                    }
                    CompareState.Done(DocumentSource.displayName(context, uri), pages)
                } finally {
                    other.closeAsync()
                }
            }
        }.getOrElse { e ->
            if (e is PdfOpenException && e.reason == PdfOpenException.Reason.PASSWORD) CompareState.NeedsPassword(uri, pw != null)
            else CompareState.Failed
        }
    }
    if (state == CompareState.Picking) return

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                    Text(stringResource(R.string.compare_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                }
                when (val s = state) {
                    CompareState.Working, CompareState.Picking -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(20.dp))
                    CompareState.Failed -> Text(stringResource(R.string.cannot_open_pdf), modifier = Modifier.padding(20.dp))
                    is CompareState.NeedsPassword -> {
                        var pw by remember { mutableStateOf("") }
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.needs_password))
                            OutlinedTextField(
                                value = pw, onValueChange = { pw = it }, singleLine = true, isError = s.wrong,
                                label = { Text(stringResource(R.string.password)) },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            )
                            TextButton(onClick = { request = s.uri to pw }) { Text(stringResource(R.string.open)) }
                        }
                    }
                    is CompareState.Done -> DiffView(s, ready.name, onGoTo)
                }
            }
        }
    }
}

@Composable
private fun DiffView(s: CompareState.Done, thisName: String, onGoTo: (Int) -> Unit) {
    val changed = s.pages.filter { it.changed }
    var at by remember { mutableStateOf(0) }
    if (changed.isEmpty()) {
        Text(stringResource(R.string.compare_same), modifier = Modifier.padding(20.dp))
        return
    }
    val page = changed[at.coerceIn(0, changed.size - 1)]
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            pluralStringResource(R.plurals.compare_pages, changed.size, changed.size) + " · " + stringResource(R.string.page_n, page.page + 1),
            modifier = Modifier.weight(1f), fontSize = 14.sp,
        )
        IconButton(onClick = { at-- }, enabled = at > 0) { Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.previous_difference)) }
        IconButton(onClick = { at++ }, enabled = at < changed.size - 1) { Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.next_difference)) }
    }
    TextButton(onClick = { onGoTo(page.page) }, modifier = Modifier.padding(start = 8.dp)) { Text(stringResource(R.string.show_page)) }
    val removed = Color(0x33D32F2F)
    val added = Color(0x332E7D32)
    fun side(keep: DiffRun.Kind): AnnotatedString = buildAnnotatedString {
        for ((i, run) in page.runs.withIndex()) {
            if (run.kind != DiffRun.Kind.SAME && run.kind != keep) continue
            // Long unchanged stretches keep a few words of context on each side.
            val words = run.words
            val text = if (run.kind == DiffRun.Kind.SAME && words.size > 24) {
                val head = if (i == 0) "… " else words.take(10).joinToString(" ") + " … "
                val tail = if (i == page.runs.size - 1) "" else words.takeLast(10).joinToString(" ") + " "
                head + tail
            } else words.joinToString(" ") + " "
            when (run.kind) {
                DiffRun.Kind.SAME -> append(text)
                DiffRun.Kind.REMOVED -> withStyle(SpanStyle(background = removed, textDecoration = TextDecoration.LineThrough)) { append(text) }
                DiffRun.Kind.ADDED -> withStyle(SpanStyle(background = added, fontWeight = FontWeight.SemiBold)) { append(text) }
            }
        }
    }
    // A fresh scroll position for each page.
    val scroll = remember(page.page) { androidx.compose.foundation.ScrollState(0) }
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(thisName, fontWeight = FontWeight.SemiBold, color = Color(0xFFC62828))
        Text(side(DiffRun.Kind.REMOVED), modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp)).padding(12.dp), fontSize = 14.sp)
        Text(s.otherName, fontWeight = FontWeight.SemiBold, color = Color(0xFF2E7D32))
        Text(side(DiffRun.Kind.ADDED), modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp)).padding(12.dp), fontSize = 14.sp)
    }
}
