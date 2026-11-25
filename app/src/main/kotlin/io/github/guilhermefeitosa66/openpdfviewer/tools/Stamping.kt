package io.github.guilhermefeitosa66.openpdfviewer.tools

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Where a page number goes, as the page is seen (after its rotation). */
enum class NumberPosition(val top: Boolean, val align: Int) {
    BOTTOM_LEFT(false, -1), BOTTOM_CENTER(false, 0), BOTTOM_RIGHT(false, 1),
    TOP_LEFT(true, -1), TOP_CENTER(true, 0), TOP_RIGHT(true, 1),
}

data class PageNumberOptions(
    val position: NumberPosition = NumberPosition.BOTTOM_CENTER,
    /** "{n}" and "{total}" are replaced. */
    val format: String = "{n}",
    val size: Float = 11f,
    val margin: Float = 28f,
    /** First page (0-based) that gets a number. */
    val fromPage: Int = 0,
    /** The number that page gets. */
    val firstNumber: Int = 1,
    val font: String = "Helvetica",
)

/** Drawing on pages as they are seen: the visible area and its rotation are taken into account. */
object Stamping {

    /**
     * The text matrix that puts upright text with its baseline origin at ([vx], [vy]) of the
     * page as seen (origin bottom-left of the visible area), for a page whose visible box starts
     * at ([llx], [lly]) with size [w] x [h] in unrotated page space and is shown turned
     * [rotation] degrees clockwise. Returns a, b, c, d, e, f.
     */
    fun uprightMatrix(rotation: Int, llx: Float, lly: Float, w: Float, h: Float, vx: Float, vy: Float): FloatArray =
        when (((rotation % 360) + 360) % 360) {
            90 -> floatArrayOf(0f, 1f, -1f, 0f, llx + w - vy, lly + vx)
            180 -> floatArrayOf(-1f, 0f, 0f, -1f, llx + w - vx, lly + h - vy)
            270 -> floatArrayOf(0f, -1f, 1f, 0f, llx + vy, lly + h - vx)
            else -> floatArrayOf(1f, 0f, 0f, 1f, llx + vx, lly + vy)
        }

    /** The visible size of [page] as seen (width and height swap for quarter turns). */
    fun seenSize(page: PDPage): Pair<Float, Float> {
        val box = page.cropBox
        return if (page.rotation % 180 == 0) box.width to box.height else box.height to box.width
    }

    fun font(name: String): PDType1Font = when (name) {
        "Times-Roman" -> PDType1Font.TIMES_ROMAN
        "Courier" -> PDType1Font.COURIER
        "Helvetica-Bold" -> PDType1Font.HELVETICA_BOLD
        else -> PDType1Font.HELVETICA
    }

    /** Draws [text] upright at ([vx], [vy]) of the page as seen, in an appended content stream. */
    fun drawText(doc: PDDocument, page: PDPage, text: String, font: PDType1Font, size: Float, vx: Float, vy: Float, gray: Float = 0.15f) {
        val box = page.cropBox
        val m = uprightMatrix(page.rotation, box.lowerLeftX, box.lowerLeftY, box.width, box.height, vx, vy)
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            cs.beginText()
            cs.setFont(font, size)
            cs.setNonStrokingColor(gray, gray, gray)
            cs.setTextMatrix(Matrix(m[0], m[1], m[2], m[3], m[4], m[5]))
            cs.showText(text)
            cs.endText()
        }
    }

    /** Text the standard fonts can show (WinAnsi): other characters become "?". */
    fun encodable(font: PDType1Font, text: String): String = buildString {
        for (ch in text) append(if (runCatching { font.encode(ch.toString()) }.isSuccess) ch else '?')
    }

    suspend fun addPageNumbers(context: Context, input: File, output: File, o: PageNumberOptions, password: String?) =
        withContext(Dispatchers.IO) {
            PDFBoxResourceLoader.init(context.applicationContext)
            PDDocument.load(input, password ?: "", MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                val font = font(o.font)
                val total = doc.numberOfPages - o.fromPage + o.firstNumber - 1
                for ((i, page) in doc.pages.withIndex()) {
                    if (i < o.fromPage) continue
                    val n = i - o.fromPage + o.firstNumber
                    val text = encodable(font, o.format.replace("{n}", n.toString()).replace("{total}", total.toString()))
                    val tw = font.getStringWidth(text) / 1000f * o.size
                    val (w, h) = seenSize(page)
                    val x = when (o.position.align) {
                        -1 -> o.margin
                        0 -> (w - tw) / 2
                        else -> w - o.margin - tw
                    }
                    val y = if (o.position.top) h - o.margin - o.size * 0.75f else o.margin
                    drawText(doc, page, text, font, o.size, x, y)
                }
                doc.save(output)
            }
        }
}
