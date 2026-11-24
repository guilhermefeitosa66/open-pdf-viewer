package io.github.guilhermefeitosa66.openpdfviewer.viewer

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import io.github.guilhermefeitosa66.openpdfviewer.data.DocumentSource
import io.github.guilhermefeitosa66.openpdfviewer.pdfium.PdfDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream

/**
 * Prints through the Android print framework. The PDF goes to the print service as it is (the
 * system picks the requested page range); a password-protected PDF is first written without its
 * encryption, since the print service cannot open it.
 */
class PdfPrintAdapter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val uri: Uri,
    private val name: String,
    private val doc: PdfDocument,
    private val encrypted: Boolean,
) : PrintDocumentAdapter() {
    private var job: Job? = null

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal.isCanceled) {
            callback.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder(name)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(doc.pageCount)
            .build()
        callback.onLayoutFinished(info, oldAttributes != newAttributes)
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback,
    ) {
        job = scope.launch {
            val ok = runCatching {
                if (encrypted) {
                    doc.saveCopy(destination, removeSecurity = true)
                } else {
                    withContext(Dispatchers.IO) {
                        DocumentSource.openInput(context, uri).use { input ->
                            FileOutputStream(destination.fileDescriptor).use { out ->
                                DocumentSource.copy(input, out, -1) {}
                            }
                        }
                    }
                    true
                }
            }.getOrDefault(false)
            when {
                cancellationSignal.isCanceled -> callback.onWriteCancelled()
                ok -> callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                else -> callback.onWriteFailed(null)
            }
        }
        cancellationSignal.setOnCancelListener { job?.cancel() }
    }

    companion object {
        fun print(context: Context, scope: CoroutineScope, uri: Uri, name: String, doc: PdfDocument, encrypted: Boolean) {
            val pm = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
            pm.print(name, PdfPrintAdapter(context, scope, uri, name, doc, encrypted), null)
        }
    }
}
