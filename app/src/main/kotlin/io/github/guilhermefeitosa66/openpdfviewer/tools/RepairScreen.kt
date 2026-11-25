package io.github.guilhermefeitosa66.openpdfviewer.tools

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import io.github.guilhermefeitosa66.openpdfviewer.R
import io.github.guilhermefeitosa66.openpdfviewer.data.DocumentSource
import io.github.guilhermefeitosa66.openpdfviewer.pdfium.PdfDocument
import io.github.guilhermefeitosa66.openpdfviewer.pdfium.PdfOpenException
import io.github.guilhermefeitosa66.openpdfviewer.ui.CreateTypedDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What a repair attempt got back. */
sealed interface RepairResult {
    /** A rewritten file with [pages] pages; [engine] is "PDFium" or "PdfBox". */
    data class Recovered(val file: File, val pages: Int, val engine: String) : RepairResult
    data object NeedsPassword : RepairResult
    data object Failed : RepairResult
}

object Repair {
    /**
     * Last resort for a file that lost its catalog or page tree (e.g. cut short): finds the page
     * objects that survive, in file order, and appends a new page tree, catalog and trailer.
     * Returns null when no page is found.
     */
    fun salvage(bytes: ByteArray): ByteArray? {
        val text = String(bytes, Charsets.ISO_8859_1)
        val objRe = Regex("""(\d+)\s+(\d+)\s+obj\b""")
        val pages = ArrayList<Pair<Int, Int>>()
        var maxNum = 0
        for (m in objRe.findAll(text)) {
            val num = m.groupValues[1].toIntOrNull() ?: continue
            val gen = m.groupValues[2].toIntOrNull() ?: continue
            maxNum = maxOf(maxNum, num)
            val end = text.indexOf("endobj", m.range.last)
            if (end < 0) continue
            // Only the object's own dictionary (up to a stream, if any).
            val body = text.substring(m.range.last + 1, end).substringBefore("stream")
            if (Regex("""/Type\s*/Page(?![a-zA-Z])""").containsMatchIn(body)) pages += num to gen
        }
        if (pages.isEmpty()) return null
        val pagesNum = maxNum + 1
        val catalogNum = maxNum + 2
        val tail = buildString {
            append("\n").append(pagesNum).append(" 0 obj\n<< /Type /Pages /Count ").append(pages.size)
            append(" /MediaBox [0 0 595 842] /Kids [")
            pages.forEach { (n, g) -> append(n).append(' ').append(g).append(" R ") }
            append("] >>\nendobj\n")
            append(catalogNum).append(" 0 obj\n<< /Type /Catalog /Pages ").append(pagesNum).append(" 0 R >>\nendobj\n")
            append("trailer\n<< /Root ").append(catalogNum).append(" 0 R /Size ").append(catalogNum + 1).append(" >>\n%%EOF\n")
        }
        // Drop a last object that was cut short (an unfinished stream would swallow what follows).
        val keep = text.lastIndexOf("endobj").let { if (it < 0) bytes.size else it + "endobj".length }
        return bytes.copyOf(keep) + tail.toByteArray(Charsets.ISO_8859_1)
    }

    private suspend fun rewriteWithPdfium(input: File, out: File): Int? = runCatching {
        withContext(Dispatchers.IO) { ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY) }.use { pfd ->
            val doc = PdfDocument.open(pfd)
            try {
                val count = doc.pageCount
                val ok = withContext(Dispatchers.IO) {
                    ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY)
                }.use { doc.saveCopy(it) }
                if (ok && count > 0) count else null
            } finally {
                doc.closeAsync()
            }
        }
    }.getOrNull()

    /**
     * Rewrites [uri] from scratch: PDFium first (it rebuilds a broken cross-reference table when
     * it opens a file), then PdfBox's lenient parser when PDFium cannot open it at all.
     */
    suspend fun repair(context: Context, uri: Uri): RepairResult {
        val dir = File(context.cacheDir, "save").apply { mkdirs() }
        val input = File(dir, "to-repair.pdf")
        val out = File(dir, "repaired.pdf")
        withContext(Dispatchers.IO) {
            DocumentSource.openInput(context, uri).use { i -> input.outputStream().use { i.copyTo(it) } }
        }
        val pdfium = runCatching {
            withContext(Dispatchers.IO) { ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY) }.use { pfd ->
                val doc = PdfDocument.open(pfd)
                try {
                    val count = doc.pageCount
                    val ok = withContext(Dispatchers.IO) {
                        ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY)
                    }.use { doc.saveCopy(it) }
                    if (ok && count > 0) RepairResult.Recovered(out, count, "PDFium") else null
                } finally {
                    doc.closeAsync()
                }
            }
        }
        val e = pdfium.exceptionOrNull()
        if (e is PdfOpenException && e.reason == PdfOpenException.Reason.PASSWORD) return RepairResult.NeedsPassword
        pdfium.getOrNull()?.let { return it }
        val pdfbox = runCatching {
            withContext(Dispatchers.IO) {
                PDFBoxResourceLoader.init(context.applicationContext)
                PDDocument.load(input, "", MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                    if (doc.numberOfPages == 0) return@withContext RepairResult.Failed
                    doc.isAllSecurityToBeRemoved = false
                    doc.save(out)
                    RepairResult.Recovered(out, doc.numberOfPages, "PdfBox")
                }
            }
        }.onFailure { android.util.Log.w("OpenPdfViewer", "repair with PdfBox failed", it) }.getOrNull()
        if (pdfbox is RepairResult.Recovered) return pdfbox
        // The catalog or page tree is gone: rebuild them from the page objects that are left.
        val salvaged = withContext(Dispatchers.Default) { salvage(input.readBytes()) } ?: return RepairResult.Failed
        val rebuilt = File(dir, "salvaged.pdf").apply { writeBytes(salvaged) }
        val pages = rewriteWithPdfium(rebuilt, out) ?: return RepairResult.Failed
        return RepairResult.Recovered(out, pages, "PDFium")
    }
}

/** Pick a damaged PDF, rebuild it and save the result as a new file. */
@Composable
fun RepairScreen(onDismiss: () -> Unit, onOpen: (Uri) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf<Uri?>(null) }
    var result by remember { mutableStateOf<RepairResult?>(null) }
    val failed = stringResource(R.string.copy_failed)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) onDismiss() else source = uri
    }
    val saver = rememberLauncherForActivityResult(CreateTypedDocument()) { target ->
        val r = result as? RepairResult.Recovered
        if (target == null || r == null) return@rememberLauncherForActivityResult
        scope.launch {
            if (DocumentSource.overwrite(context, target, r.file)) {
                DocumentSource.persistAccess(context, target)
                onOpen(target)
            } else Toast.makeText(context, failed, Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(Unit) { picker.launch(arrayOf("application/pdf", "application/octet-stream", "*/*")) }
    LaunchedEffect(source) {
        val uri = source ?: return@LaunchedEffect
        result = Repair.repair(context, uri)
    }
    if (source == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.repair_pdf)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (val r = result) {
                    null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    is RepairResult.Recovered -> Text(pluralStringResource(R.plurals.repair_recovered, r.pages, r.pages, r.engine))
                    RepairResult.NeedsPassword -> Text(stringResource(R.string.repair_password))
                    RepairResult.Failed -> Text(stringResource(R.string.repair_failed))
                }
                Text(stringResource(R.string.repair_note), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            if (result is RepairResult.Recovered) {
                TextButton(onClick = {
                    val name = DocumentSource.displayName(context, source!!).removeSuffix(".pdf").removeSuffix(".PDF")
                    saver.launch(io.github.guilhermefeitosa66.openpdfviewer.ui.FileActions.safeName("$name (repaired)") to "application/pdf")
                }) { Text(stringResource(R.string.save_repaired)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
