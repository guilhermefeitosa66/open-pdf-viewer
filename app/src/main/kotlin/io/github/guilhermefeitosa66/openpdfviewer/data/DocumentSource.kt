package io.github.guilhermefeitosa66.openpdfviewer.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException

/** Turns a content URI into something PDFium can read. */
object DocumentSource {

    class AccessLost(cause: Throwable) : Exception(cause)

    /**
     * Opens [uri] for reading. Providers that hand out a pipe instead of a seekable file (some
     * mail and chat apps) are copied to the cache first.
     */
    suspend fun open(context: Context, uri: Uri): ParcelFileDescriptor = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        val pfd = try {
            cr.openFileDescriptor(uri, "r") ?: throw FileNotFoundException(uri.toString())
        } catch (e: SecurityException) {
            throw AccessLost(e)
        } catch (e: FileNotFoundException) {
            throw AccessLost(e)
        } catch (e: IllegalArgumentException) {
            throw AccessLost(e)
        }
        if (pfd.statSize >= 0) return@withContext pfd
        pfd.close()
        val dir = File(context.cacheDir, "incoming").apply { mkdirs() }
        val copy = File(dir, RecentFile.keyOf(uri.toString()) + ".pdf")
        try {
            cr.openInputStream(uri)?.use { input -> copy.outputStream().use { input.copyTo(it) } }
                ?: throw FileNotFoundException(uri.toString())
        } catch (e: SecurityException) {
            throw AccessLost(e)
        }
        ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY)
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
