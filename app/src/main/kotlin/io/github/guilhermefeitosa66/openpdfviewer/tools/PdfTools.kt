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

    /**
     * Saves every distinct image of the document into [dir]: JPEG streams as they are (no
     * re-encoding), the rest as PNG. Tiny images (icons, under 32 px) are skipped. Returns the
     * files in page order.
     */
    suspend fun extractImages(context: Context, input: File, dir: File, baseName: String, password: String?, onProgress: (Float) -> Unit = {}): List<File> =
        withContext(Dispatchers.IO) {
            val out = ArrayList<File>()
            load(context, input, password).use { doc ->
                val seen = HashSet<COSBase>()
                val count = doc.numberOfPages
                fun walk(res: PDResources, page: Int) {
                    for (name in res.xObjectNames) {
                        val xo = runCatching { res.getXObject(name) }.getOrNull() ?: continue
                        if (!seen.add(xo.cosObject)) continue
                        when (xo) {
                            is PDFormXObject -> xo.resources?.let { walk(it, page) }
                            is PDImageXObject -> runCatching {
                                if (xo.width < 32 || xo.height < 32) return@runCatching
                                val n = out.size + 1
                                val filters = xo.cosObject.filters
                                val jpeg = filters == com.tom_roush.pdfbox.cos.COSName.DCT_DECODE &&
                                    xo.colorSpace.numberOfComponents in setOf(1, 3)
                                val f = File(dir, "$baseName p${page + 1} ${n}." + if (jpeg) "jpg" else "png")
                                if (jpeg) {
                                    xo.cosObject.createRawInputStream().use { i -> f.outputStream().use { i.copyTo(it) } }
                                } else {
                                    val bmp = xo.image ?: return@runCatching
                                    f.outputStream().buffered().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                    bmp.recycle()
                                }
                                out += f
                            }.onFailure { android.util.Log.w("OpenPdfViewer", "image not extracted", it) }
                        }
                    }
                }
                for ((i, page) in doc.pages.withIndex()) {
                    coroutineContext.ensureActive()
                    page.resources?.let { walk(it, i) }
                    onProgress((i + 1).toFloat() / count)
                }
            }
            out
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

/** Page layout for turning images into a PDF. */
data class ImagePageOptions(
    /** Page size in points, or null to make each page the size of its image. */
    val pageSize: Pair<Float, Float>? = null,
    /** Margin in points. */
    val margin: Float = 0f,
    /** null = follow each image; true/false = force landscape/portrait. */
    val landscape: Boolean? = null,
) {
    companion object {
        val A4 = 595.28f to 841.89f
        val LETTER = 612f to 792f

        /**
         * The page size and the image rectangle (x, y from the bottom-left, w, h) in points for
         * an image of [iw] x [ih] pixels: the image is fitted inside the margins and centred.
         * With no page size, the page takes the image's size at 72 dpi-equivalent (long side
         * scaled to A4's long side) plus the margins.
         */
        fun layout(o: ImagePageOptions, iw: Int, ih: Int): Pair<Pair<Float, Float>, FloatArray> {
            val imageLandscape = iw > ih
            val (pw, ph) = when (val size = o.pageSize) {
                null -> {
                    val s = A4.second / maxOf(iw, ih)
                    (iw * s + 2 * o.margin) to (ih * s + 2 * o.margin)
                }
                else -> {
                    val land = o.landscape ?: imageLandscape
                    val (a, b) = size
                    if (land) maxOf(a, b) to minOf(a, b) else minOf(a, b) to maxOf(a, b)
                }
            }
            val aw = (pw - 2 * o.margin).coerceAtLeast(1f)
            val ah = (ph - 2 * o.margin).coerceAtLeast(1f)
            val scale = minOf(aw / iw, ah / ih)
            val w = iw * scale
            val h = ih * scale
            return (pw to ph) to floatArrayOf((pw - w) / 2, (ph - h) / 2, w, h)
        }
    }
}

object ImagesToPdf {
    /** Longest side kept for each image, in pixels (about 250 dpi on A4). */
    private const val MAX_SIDE = 3000

    /**
     * Writes one page per image (already decoded and rotated by the caller through [decode]) to
     * [output].
     */
    suspend fun write(
        context: Context,
        count: Int,
        decode: suspend (Int) -> Bitmap?,
        options: ImagePageOptions,
        output: File,
        onProgress: (Float) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument().use { doc ->
            for (i in 0 until count) {
                coroutineContext.ensureActive()
                val src = decode(i) ?: continue
                val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(src.width, src.height))
                val bmp = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true) else src
                val (size, rect) = ImagePageOptions.layout(options, bmp.width, bmp.height)
                val page = com.tom_roush.pdfbox.pdmodel.PDPage(com.tom_roush.pdfbox.pdmodel.common.PDRectangle(size.first, size.second))
                doc.addPage(page)
                val image = JPEGFactory.createFromImage(doc, bmp, 0.85f)
                com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc, page).use { cs ->
                    cs.drawImage(image, rect[0], rect[1], rect[2], rect[3])
                }
                if (bmp !== src) bmp.recycle()
                src.recycle()
                onProgress((i + 1).toFloat() / count)
            }
            doc.save(output)
        }
    }
}
