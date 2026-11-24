package io.github.guilhermefeitosa66.openpdfviewer.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

/** Turns a content URI into something PDFium can read, and copies documents around. */
object DocumentSource {

    class AccessLost(cause: Throwable) : Exception(cause)

    /**
     * Private copies of files that came with a temporary grant ("Open with", Share, or a provider
     * that only hands out a pipe). They let the app reopen, share and save the file after the
     * grant expires. Kept only while the file is in the recent list.
     */
    fun receivedDir(context: Context) = File(context.filesDir, "received")

    fun receivedCopy(context: Context, uri: Uri) =
        File(receivedDir(context), RecentFile.keyOf(uri.toString()) + ".pdf")

    /**
     * Opens [uri] for reading. Providers that hand out a pipe instead of a seekable file (some
     * mail and chat apps) are copied first. When access is gone, the private copy is used.
     */
    suspend fun open(context: Context, uri: Uri): ParcelFileDescriptor = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        val local = receivedCopy(context, uri)
        val pfd = try {
            cr.openFileDescriptor(uri, "r") ?: throw FileNotFoundException(uri.toString())
        } catch (e: Exception) {
            if (e !is SecurityException && e !is FileNotFoundException && e !is IllegalArgumentException) throw e
            if (local.exists()) return@withContext ParcelFileDescriptor.open(local, ParcelFileDescriptor.MODE_READ_ONLY)
            throw AccessLost(e)
        }
        if (pfd.statSize >= 0) return@withContext pfd
        pfd.close()
        try {
            cr.openInputStream(uri)?.use { input -> writeAtomically(local) { input.copyTo(it) } }
                ?: throw FileNotFoundException(uri.toString())
        } catch (e: SecurityException) {
            throw AccessLost(e)
        }
        ParcelFileDescriptor.open(local, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** A stream of the document, falling back to the private copy when the grant expired. */
    fun openInput(context: Context, uri: Uri): InputStream {
        val local = receivedCopy(context, uri)
        return try {
            context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
        } catch (e: Exception) {
            if (local.exists()) local.inputStream() else throw AccessLost(e)
        }
    }

    /** Size in bytes, or -1 when the provider does not say. */
    fun size(context: Context, uri: Uri): Long {
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { if (it.statSize >= 0) return it.statSize }
        }
        val local = receivedCopy(context, uri)
        return if (local.exists()) local.length() else -1L
    }

    fun hasPersistedAccess(context: Context, uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    /**
     * After a file opened through a temporary grant, keep a private copy in the background so it
     * can still be reopened, shared and saved later. Never on the opening path.
     */
    suspend fun keepPrivateCopy(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        if (uri.scheme != "content" || hasPersistedAccess(context, uri)) return@withContext
        val local = receivedCopy(context, uri)
        if (local.exists()) return@withContext
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input -> writeAtomically(local) { input.copyTo(it) } }
        }
    }

    /**
     * Copies [input] to [output] in chunks, reporting progress (0..1, or null when the size is
     * unknown) and stopping when the coroutine is cancelled.
     */
    suspend fun copy(input: InputStream, output: OutputStream, total: Long, onProgress: (Float?) -> Unit) {
        val buf = ByteArray(256 * 1024)
        var done = 0L
        while (true) {
            coroutineContext.ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            done += n
            onProgress(if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null)
        }
        output.flush()
    }

    private inline fun writeAtomically(target: File, block: (OutputStream) -> Unit) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".part")
        try {
            tmp.outputStream().use(block)
            tmp.renameTo(target)
        } finally {
            tmp.delete()
        }
    }

    fun displayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0)?.let { return it } }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "document.pdf"
    }

    /** Keeps read access across restarts when the grant allows it (picker results do). */
    fun persistAccess(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
