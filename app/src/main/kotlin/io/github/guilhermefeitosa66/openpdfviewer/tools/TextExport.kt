package io.github.guilhermefeitosa66.openpdfviewer.tools

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A line of text as laid out on the page: its font size, bold or not, and vertical position. */
data class TextLine(val page: Int, val text: String, val size: Float, val bold: Boolean, val y: Float, val height: Float)

/** PDF to plain text (reading order) or to simple Markdown. */
object TextExport {

    suspend fun plainText(context: Context, input: File, password: String?): String = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(input, password ?: "", MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            PDFTextStripper().apply {
                sortByPosition = true
                lineSeparator = "\n"
            }.getText(doc)
        }
    }

    suspend fun markdown(context: Context, input: File, password: String?): String = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(input, password ?: "", MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            val lines = ArrayList<TextLine>()
            val stripper = object : PDFTextStripper() {
                var page = 0
                override fun startPage(page: com.tom_roush.pdfbox.pdmodel.PDPage?) {
                    this.page++
                    super.startPage(page)
                }
                override fun writeString(text: String, positions: MutableList<TextPosition>) {
                    if (text.isBlank() || positions.isEmpty()) return
                    // The size most characters of the line use, and whether the font looks bold.
                    val size = positions.groupingBy { (it.fontSizeInPt * 2).toInt() / 2f }.eachCount().maxBy { it.value }.key
                    val boldChars = positions.count { p ->
                        val f = p.font
                        f?.name?.contains("Bold", true) == true || (f?.fontDescriptor?.isForceBold == true) ||
                            (f?.fontDescriptor?.fontWeight ?: 0f) >= 600f
                    }
                    val top = positions.minOf { it.yDirAdj - it.heightDir }
                    val bottom = positions.maxOf { it.yDirAdj }
                    lines += TextLine(page, text.trim(), size, boldChars * 2 > positions.size, top, bottom - top)
                }
            }
            stripper.sortByPosition = true
            stripper.getText(doc)
            toMarkdown(lines)
        }
    }

    /**
     * Turns lines into Markdown: the font size most of the text uses is the body; lines clearly
     * larger become headings (#, ##, ###), short bold lines at body size become ###. Body lines
     * join into paragraphs, split where the line spacing is clearly larger than usual or the page ends.
     * Hyphenated line ends are joined.
     */
    fun toMarkdown(lines: List<TextLine>): String {
        if (lines.isEmpty()) return ""
        val body = lines.groupBy { it.size }.maxBy { (_, l) -> l.sumOf { it.text.length } }.key
        fun level(l: TextLine): Int = when {
            l.size >= body * 1.6f -> 1
            l.size >= body * 1.3f -> 2
            l.size >= body * 1.12f -> 3
            l.bold && l.text.length < 80 && !l.text.endsWith('.') -> 3
            else -> 0
        }
        // Baseline-to-baseline distance between body lines; the usual one (lower quartile, as
        // most lines sit inside paragraphs) is the line pitch, and a clearly larger one starts
        // a new paragraph. Baselines do not depend on how tall the font's box is reported.
        fun base(l: TextLine) = l.y + l.height
        val pitches = lines.zipWithNext().filter { (a, b) -> a.page == b.page && level(a) == 0 && level(b) == 0 }
            .map { (a, b) -> base(b) - base(a) }.filter { it > 0 }.sorted()
        val usualPitch = pitches.getOrNull(pitches.size / 4) ?: (body * 1.2f)
        val out = StringBuilder()
        val para = StringBuilder()
        fun flush() {
            if (para.isNotEmpty()) {
                out.append(para.toString().trim()).append("\n\n")
                para.clear()
            }
        }
        var prev: TextLine? = null
        for (l in lines) {
            val lv = level(l)
            if (lv > 0) {
                flush()
                // Consecutive heading lines of the same size are one heading.
                if (prev != null && level(prev) == lv && prev.page == l.page && out.endsWith("\n\n")) {
                    out.setLength(out.length - 2)
                    out.append(' ').append(l.text).append("\n\n")
                } else {
                    out.append("#".repeat(lv)).append(' ').append(l.text).append("\n\n")
                }
            } else {
                val p = prev
                val newParagraph = p == null || level(p) > 0 || p.page != l.page ||
                    (base(l) - base(p)) > usualPitch * 1.3f
                if (newParagraph) flush()
                if (para.isNotEmpty()) {
                    if (para.endsWith("-") && para.length > 1 && para[para.length - 2].isLetter()) para.setLength(para.length - 1)
                    else para.append(' ')
                }
                para.append(l.text)
            }
            prev = l
        }
        flush()
        return out.toString().trimEnd() + "\n"
    }
}
