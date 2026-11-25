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

data class WatermarkOptions(
    /** Text, or null when [image] is used. */
    val text: String? = null,
    val image: android.graphics.Bitmap? = null,
    val opacity: Float = 0.3f,
    /** Degrees, counter-clockwise as seen. */
    val rotation: Float = 45f,
    val tile: Boolean = false,
    val under: Boolean = false,
    /** 0-based pages; null = all. */
    val pages: Set<Int>? = null,
    val textSize: Float = 60f,
    val color: Int = 0xFF808080.toInt(),
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

    /** Centers of the copies of a [ew] x [eh] mark tiled over a [w] x [h] page (staggered rows). */
    fun tileCenters(w: Float, h: Float, ew: Float, eh: Float): List<Pair<Float, Float>> {
        val sx = ew + 120f
        val sy = eh + 120f
        val out = ArrayList<Pair<Float, Float>>()
        var row = 0
        var y = -sy / 2
        while (y < h + sy) {
            var x = if (row % 2 == 0) 0f else sx / 2
            while (x < w + sx) {
                out += x to y
                x += sx
            }
            y += sy
            row++
        }
        return out
    }

    suspend fun addWatermark(context: Context, input: File, output: File, o: WatermarkOptions, password: String?) =
        withContext(Dispatchers.IO) {
            PDFBoxResourceLoader.init(context.applicationContext)
            PDDocument.load(input, password ?: "", MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                val font = PDType1Font.HELVETICA_BOLD
                val text = o.text?.let { encodable(font, it) }
                val image = o.image?.let { com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(doc, it) }
                val gs = com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState().apply {
                    nonStrokingAlphaConstant = o.opacity
                    strokingAlphaConstant = o.opacity
                }
                val r = ((o.color shr 16) and 0xFF) / 255f
                val g = ((o.color shr 8) and 0xFF) / 255f
                val b = (o.color and 0xFF) / 255f
                for ((i, page) in doc.pages.withIndex()) {
                    if (o.pages != null && i !in o.pages) continue
                    val (w, h) = seenSize(page)
                    // The mark's size: text at its size; an image at up to 40 % of the page width.
                    val (ew, eh) = when {
                        text != null -> font.getStringWidth(text) / 1000f * o.textSize to o.textSize * 0.72f
                        image != null -> {
                            val iw = minOf(w * 0.4f, image.width.toFloat())
                            iw to iw * image.height / image.width
                        }
                        else -> continue
                    }
                    // Tiles are spaced by the box of the turned mark.
                    val rad = Math.toRadians(o.rotation.toDouble())
                    val bw = (kotlin.math.abs(ew * kotlin.math.cos(rad)) + kotlin.math.abs(eh * kotlin.math.sin(rad))).toFloat()
                    val bh = (kotlin.math.abs(ew * kotlin.math.sin(rad)) + kotlin.math.abs(eh * kotlin.math.cos(rad))).toFloat()
                    val centers = if (o.tile) tileCenters(w, h, bw, bh) else listOf(w / 2 to h / 2)
                    val box = page.cropBox
                    val mode = if (o.under) PDPageContentStream.AppendMode.PREPEND else PDPageContentStream.AppendMode.APPEND
                    PDPageContentStream(doc, page, mode, true, true).use { cs ->
                        cs.saveGraphicsState()
                        cs.setGraphicsStateParameters(gs)
                        for ((cx, cy) in centers) {
                            cs.saveGraphicsState()
                            val m = uprightMatrix(page.rotation, box.lowerLeftX, box.lowerLeftY, box.width, box.height, cx, cy)
                            cs.transform(Matrix(m[0], m[1], m[2], m[3], m[4], m[5]))
                            cs.transform(Matrix.getRotateInstance(Math.toRadians(o.rotation.toDouble()), 0f, 0f))
                            if (text != null) {
                                cs.beginText()
                                cs.setFont(font, o.textSize)
                                cs.setNonStrokingColor(r, g, b)
                                cs.newLineAtOffset(-ew / 2, -eh / 2)
                                cs.showText(text)
                                cs.endText()
                            } else if (image != null) {
                                cs.drawImage(image, -ew / 2, -eh / 2, ew, eh)
                            }
                            cs.restoreGraphicsState()
                        }
                        cs.restoreGraphicsState()
                    }
                }
                doc.save(output)
            }
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
