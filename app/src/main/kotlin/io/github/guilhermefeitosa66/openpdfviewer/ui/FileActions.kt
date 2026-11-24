package io.github.guilhermefeitosa66.openpdfviewer.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.guilhermefeitosa66.openpdfviewer.R
import io.github.guilhermefeitosa66.openpdfviewer.data.DocumentSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Share a document or save a copy of it somewhere the user picks. Both copy in the background,
 * with a cancellable progress dialog for big files, and never touch the original.
 */
class FileActions internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val launchSave: (String) -> Unit,
) {
    internal var progress by mutableStateOf<Float?>(null)
    internal var busy by mutableStateOf(false)
    private var job: Job? = null
    internal var pendingSave: Pair<Uri, String>? = null

    /** Opens the system share sheet with the PDF under its own name. */
    fun share(uri: Uri, name: String) = launchCopy {
        val dir = File(context.cacheDir, "share")
        val out = withContext(Dispatchers.IO) {
            dir.deleteRecursively()
            dir.mkdirs()
            File(dir, safeName(name)).also { target ->
                DocumentSource.openInput(context, uri).use { input ->
                    target.outputStream().use { DocumentSource.copy(input, it, DocumentSource.size(context, uri)) { p -> progress = p } }
                }
            }
        }
        val shared = FileProvider.getUriForFile(context, context.packageName + ".files", out)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, shared)
            .putExtra(Intent.EXTRA_TITLE, out.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newUri(context.contentResolver, out.name, shared)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Asks where to save (the system "Save as" screen), then copies the file there. */
    fun saveCopy(uri: Uri, name: String) {
        pendingSave = uri to name
        launchSave(safeName(name))
    }

    internal fun onSaveTarget(target: Uri?) {
        val (source, _) = pendingSave ?: return
        pendingSave = null
        if (target == null) return
        launchCopy {
            withContext(Dispatchers.IO) {
                val out = context.contentResolver.openOutputStream(target, "w") ?: error("no output")
                out.use { o ->
                    DocumentSource.openInput(context, source).use { input ->
                        DocumentSource.copy(input, o, DocumentSource.size(context, source)) { p -> progress = p }
                    }
                }
            }
            Toast.makeText(context, R.string.saved_copy, Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchCopy(block: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch {
            progress = null
            // Small files finish before the dialog would even show.
            val show = launch { delay(300); busy = true }
            try {
                block()
            } catch (e: DocumentSource.AccessLost) {
                Toast.makeText(context, R.string.file_unavailable, Toast.LENGTH_LONG).show()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, R.string.copy_failed, Toast.LENGTH_LONG).show()
            } finally {
                show.cancel()
                busy = false
            }
        }
    }

    internal fun cancel() {
        job?.cancel()
        busy = false
    }

    companion object {
        /** A file name that is safe on any file system, ending in .pdf. */
        fun safeName(name: String): String {
            val base = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().ifEmpty { "document" }
            return if (base.endsWith(".pdf", ignoreCase = true)) base else "$base.pdf"
        }
    }
}

@Composable
fun rememberFileActions(): FileActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ref = remember { arrayOfNulls<FileActions>(1) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        ref[0]?.onSaveTarget(uri)
    }
    val actions = remember { FileActions(context, scope) { name -> saver.launch(name) } }
    ref[0] = actions

    if (actions.busy) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.copying)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val p = actions.progress
                    if (p == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = actions::cancel) { Text(stringResource(R.string.cancel)) } },
        )
    }
    return actions
}
