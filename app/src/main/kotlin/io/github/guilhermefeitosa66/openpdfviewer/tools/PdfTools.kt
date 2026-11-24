package io.github.guilhermefeitosa66.openpdfviewer.tools

import android.content.Context
import android.graphics.Bitmap
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * File-level tools done with PdfBox-Android (loaded only when a tool runs): things PDFium does
 * not do, such as re-encoding images.
 */
object PdfTools {

    enum class Compression(val jpegQuality: Float, val maxDpi: Int) {
        LIGHT(0.85f, 220), RECOMMENDED(0.7f, 150), STRONG(0.5f, 96)
    }

    private fun load(context: Context, input: File, password: String?): PDDocument {
        PDFBoxResourceLoader.init(context.applicationContext)
        return PDDocument.load(input, password ?: "", MemoryUsageSetting.setupTempFileOnly())
    }

    /**
     * Writes a smaller copy of [input] to [output]: images are re-encoded as JPEG at [level]'s
     * quality and scaled down to its DPI (measured against the page size), keeping the new
     * version only when it is smaller. Saving drops objects nothing uses. [stripMetadata] also
     * removes the document information and XMP metadata.
     */
    suspend fun compress(
        context: Context,
        input: File,
        output: File,
        level: Compression,
        stripMetadata: Boolean,
        password: String?,
        onProgress: (Float) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        load(context, input, password).use { doc ->
            val replaced = HashMap<COSBase, PDImageXObject>()
            val visited = HashSet<COSBase>()
            val count = doc.numberOfPages
            for ((i, page) in doc.pages.withIndex()) {
                coroutineContext.ensureActive()
                val box = page.cropBox
                val maxW = (box.width / 72f * level.maxDpi).toInt().coerceAtLeast(64)
                val maxH = (box.height / 72f * level.maxDpi).toInt().coerceAtLeast(64)
                page.resources?.let { recompress(doc, it, maxW, maxH, level.jpegQuality, replaced, visited) }
                onProgress((i + 1).toFloat() / count)
            }
            if (stripMetadata) {
                doc.documentInformation = PDDocumentInformation()
                doc.documentCatalog.metadata = null
            }
            doc.save(output)
        }
    }

    private fun recompress(
        doc: PDDocument,
        res: PDResources,
        maxW: Int,
        maxH: Int,
        quality: Float,
        replaced: HashMap<COSBase, PDImageXObject>,
        visited: HashSet<COSBase>,
    ) {
        for (name in res.xObjectNames) {
            val xo = runCatching { res.getXObject(name) }.getOrNull() ?: continue
            when (xo) {
                is PDImageXObject -> {
                    val key = xo.cosObject
                    replaced[key]?.let { res.put(name, it); continue }
                    if (!visited.add(key)) continue
                    val smaller = runCatching { reencode(doc, xo, maxW, maxH, quality) }
                        .onFailure { android.util.Log.w("OpenPdfViewer", "image not re-encoded", it) }.getOrNull() ?: continue
                    replaced[key] = smaller
                    res.put(name, smaller)
                }
                is PDFormXObject -> {
                    if (visited.add(xo.cosObject)) xo.resources?.let { recompress(doc, it, maxW, maxH, quality, replaced, visited) }
                }
            }
        }
    }

    /** A JPEG version of [img] no larger than [maxW] x [maxH], or null when it would not be smaller. */
    private fun reencode(doc: PDDocument, img: PDImageXObject, maxW: Int, maxH: Int, quality: Float): PDImageXObject? {
        if (img.isStencil || img.bitsPerComponent == 1) return null
        val w = img.width
        val h = img.height
        if (w * h < 96 * 96) return null
        val oldLength = img.cosObject.length
        // Decode at a fraction of the size when the image is far larger than needed. JPEG
        // streams go straight to BitmapFactory (PdfBox-Android's subsampled decoding is broken).
        val sub = maxOf(1, minOf(w / maxW, h / maxH))
        val filters = img.cosObject.filters
        val plainJpeg = filters is com.tom_roush.pdfbox.cos.COSName && filters == com.tom_roush.pdfbox.cos.COSName.DCT_DECODE &&
            img.colorSpace.numberOfComponents in setOf(1, 3) && img.cosObject.getDictionaryObject(com.tom_roush.pdfbox.cos.COSName.SMASK) == null
        val decoded: Bitmap = if (plainJpeg) {
            val opts = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = Integer.highestOneBit(sub)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            img.cosObject.createRawInputStream().use { android.graphics.BitmapFactory.decodeStream(it, null, opts) } ?: return null
        } else {
            if (w.toLong() * h > 20_000_000L) return null
            img.image ?: return null
        }
        val scale = minOf(1f, maxW.toFloat() / decoded.width, maxH.toFloat() / decoded.height)
        val bmp = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
                .also { if (it !== decoded) decoded.recycle() }
        } else decoded
        val jpeg = JPEGFactory.createFromImage(doc, bmp, quality)
        bmp.recycle()
        // Keep the original unless the new one saves at least 10 %.
        return if (jpeg.cosObject.length < oldLength * 0.9) jpeg else null
    }
}
