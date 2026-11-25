package io.github.guilhermefeitosa66.openpdfviewer.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import io.github.guilhermefeitosa66.openpdfviewer.pdfium.PdfDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A word of the page and its box (normalized 0..1, top-left origin). */
data class PageWord(val text: String, val box: RectF)

/** Patterns offered for finding sensitive text. */
enum class RedactPattern(val regex: String) {
    CPF("""\b\d{3}\.?\d{3}\.?\d{3}-?\d{2}\b"""),
    CNPJ("""\b\d{2}\.?\d{3}\.?\d{3}/?\d{4}-?\d{2}\b"""),
    EMAIL("""[\w.+-]+@[\w-]+(\.[\w-]+)+"""),
    PHONE("""(\+?\d{1,3}[\s.-]?)?\(?\d{2,3}\)?[\s.-]?\d{4,5}[\s.-]?\d{4}\b"""),
}

/**
 * Real redaction: each affected page becomes an image with the marked areas painted black, so
 * whatever was underneath (text, images, drawings) is gone from the file, and the words outside
 * the marks come back as an invisible text layer so the page stays searchable. Annotations on
 * those pages are drawn into the image first and then removed.
 */
object Redaction {

    /** Matches of [query] in [text] (plain, case-insensitive, or a regular expression). */
    fun find(text: String, query: String, regex: Boolean): List<IntRange> {
        if (query.isBlank()) return emptyList()
        val re = runCatching {
            if (regex) Regex(query) else Regex(Regex.escape(query.trim()), RegexOption.IGNORE_CASE)
        }.getOrNull() ?: return emptyList()
        return re.findAll(text).map { it.range }.filter { !it.isEmpty() }.toList()
    }

    /** Words of the page text with their character ranges. */
    fun wordRanges(text: String): List<IntRange> = Regex("""\S+""").findAll(text).map { it.range }.toList()

    fun intersects(a: RectF, b: RectF): Boolean = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    /** The words of page [index] with their boxes. */
    suspend fun words(doc: PdfDocument, index: Int): List<PageWord> {
        val text = doc.text(index)
        return wordRanges(text).mapNotNull { r ->
            val rects = doc.textRects(index, r.first, r.last - r.first + 1)
            if (rects.isEmpty()) return@mapNotNull null
            val u = RectF(rects[0]).apply { rects.drop(1).forEach { union(it) } }
            PageWord(text.substring(r.first, r.last + 1).replace('\u0002', '-'), u)
        }
    }

    data class Result(val pages: Int, val verified: Boolean)

    /**
     * Writes [input] to [output] with [marks] (normalized boxes per page) redacted. [secrets]
     * are strings that must not be found in the result afterwards (the redacted words).
     */
    suspend fun apply(
        context: Context,
        doc: PdfDocument,
        input: File,
        output: File,
        marks: Map<Int, List<RectF>>,
        password: String?,
        onProgress: (Float) -> Unit,
    ): Result {
        // 1. Render each affected page with its marks painted black, and keep the other words.
        val pages = HashMap<Int, Pair<Bitmap, List<PageWord>>>()
        val secrets = HashMap<Int, List<String>>()
        for ((n, entry) in marks.entries.sortedBy { it.key }.withIndex()) {
            val (index, boxes) = entry
            if (boxes.isEmpty()) continue
            val size = doc.pageSizes[index]
            var scale = 200f / 72f
            val mp = size.width * scale * size.height * scale
            if (mp > 20_000_000f) scale *= kotlin.math.sqrt(20_000_000f / mp)
            val w = (size.width * scale).toInt().coerceAtLeast(1)
            val h = (size.height * scale).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.WHITE)
            check(doc.render(index, bmp, w, h)) { "render" }
            val paint = Paint().apply { color = android.graphics.Color.BLACK }
            val canvas = Canvas(bmp)
            for (b in boxes) canvas.drawRect(b.left * w, b.top * h, b.right * w, b.bottom * h, paint)
            val all = words(doc, index)
            val (hidden, kept) = all.partition { word -> boxes.any { intersects(it, word.box) } }
            pages[index] = bmp to kept
            secrets[index] = hidden.map { it.text }.filter { it.length >= 2 }
            onProgress((n + 1f) / marks.size * 0.7f)
        }
        // 2. Replace those pages' content with the image and the invisible kept words.
        withContext(Dispatchers.IO) {
            PDFBoxResourceLoader.init(context.applicationContext)
            PDDocument.load(input, password ?: "", MemoryUsageSetting.setupTempFileOnly()).use { pd ->
                val font = PDType1Font.HELVETICA
                for ((index, entry) in pages) {
                    val (bmp, kept) = entry
                    val page = pd.getPage(index)
                    val (pw, ph) = Stamping.seenSize(page)
                    // Opaque, so no transparency mask is written along with the JPEG.
                    bmp.setHasAlpha(false)
                    val image = JPEGFactory.createFromImage(pd, bmp, 0.9f)
                    bmp.recycle()
                    // A clean page: no old content, resources, annotations or extra boxes.
                    page.cosObject.removeItem(COSName.CROP_BOX)
                    page.cosObject.removeItem(COSName.TRIM_BOX)
                    page.cosObject.removeItem(COSName.BLEED_BOX)
                    page.cosObject.removeItem(COSName.ART_BOX)
                    page.cosObject.removeItem(COSName.getPDFName("Thumb"))
                    page.cosObject.removeItem(COSName.getPDFName("PieceInfo"))
                    page.cosObject.removeItem(COSName.METADATA)
                    page.mediaBox = PDRectangle(pw, ph)
                    page.rotation = 0
                    page.resources = PDResources()
                    page.annotations = emptyList()
                    PDPageContentStream(pd, page, PDPageContentStream.AppendMode.OVERWRITE, true).use { cs ->
                        cs.drawImage(image, 0f, 0f, pw, ph)
                        if (kept.isNotEmpty()) {
                            cs.beginText()
                            cs.setRenderingMode(RenderingMode.NEITHER)
                            for (word in kept) {
                                val text = Stamping.encodable(font, word.text)
                                val hPt = word.box.height() * ph
                                val size = (hPt * 1.1f).coerceIn(2f, 200f)
                                val natural = font.getStringWidth(text) / 1000f * size
                                if (natural <= 0f) continue
                                val k = word.box.width() * pw / natural
                                cs.setFont(font, size)
                                cs.setTextMatrix(Matrix(k, 0f, 0f, 1f, word.box.left * pw, ph - word.box.bottom * ph + hPt * 0.18f))
                                cs.showText(text)
                            }
                            cs.endText()
                        }
                    }
                }
                pd.save(output)
            }
        }
        onProgress(0.9f)
        // 3. Check: the redacted words must not be in the new pages' text.
        val verified = withContext(Dispatchers.IO) { ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY) }.use { pfd ->
            val check = PdfDocument.open(pfd, password)
            try {
                secrets.all { (index, hidden) ->
                    val remaining = WordDiff.words(check.text(index)).toMutableList()
                    // Each hidden word may appear as often as it does among the kept words.
                    val keptCount = pages[index]?.second.orEmpty().groupingBy { it.text }.eachCount()
                    hidden.all { secret -> remaining.count { it == secret } <= (keptCount[secret] ?: 0) }
                }
            } finally {
                check.closeAsync()
            }
        }
        onProgress(1f)
        return Result(pages.size, verified)
    }
}
