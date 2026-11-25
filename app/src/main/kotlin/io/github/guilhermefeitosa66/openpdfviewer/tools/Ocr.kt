package io.github.guilhermefeitosa66.openpdfviewer.tools

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import io.github.guilhermefeitosa66.openpdfviewer.pdfium.PdfDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/** A recognized word on a rendered page, in pixels of a [imageW] x [imageH] image. */
data class OcrWord(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * Offline OCR with Tesseract (models packaged with the app): pages are rendered, read word by
 * word, and an invisible text layer is added over each word so the PDF can be searched and its
 * text selected and copied. The page images do not change.
 */
object Ocr {
    /** Languages packaged with the app, as Tesseract codes. */
    val LANGUAGES = listOf("por", "eng")

    /** Rendering resolution for recognition. */
    private const val DPI = 250

    /** Copies the packaged models to app storage once (Tesseract reads files, not assets). */
    private suspend fun dataPath(context: Context): File = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "ocr")
        val dir = File(root, "tessdata").apply { mkdirs() }
        for (lang in LANGUAGES) {
            val f = File(dir, "$lang.traineddata")
            if (f.exists() && f.length() > 0) continue
            val tmp = File(dir, "$lang.part")
            context.assets.open("tessdata/$lang.traineddata").use { i -> tmp.outputStream().use { i.copyTo(it) } }
            tmp.renameTo(f)
        }
        root
    }

    /**
     * Recognizes [pages] of [doc] (the open document, for rendering) and writes [input] plus a
     * text layer to [output]. [languages] like "por+eng". Returns how many pages got text.
     */
    suspend fun makeSearchable(
        context: Context,
        doc: PdfDocument,
        input: File,
        output: File,
        pages: List<Int>,
        languages: String,
        password: String?,
        onProgress: (Float) -> Unit,
    ): Int {
        val path = dataPath(context)
        val words = HashMap<Int, Pair<Pair<Int, Int>, List<OcrWord>>>()
        val api = TessBaseAPI()
        try {
            check(api.init(path.absolutePath, languages)) { "tesseract init" }
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            for ((n, index) in pages.withIndex()) {
                coroutineContext.ensureActive()
                val size = doc.pageSizes[index]
                var scale = DPI / 72f
                val mp = size.width * scale * size.height * scale
                if (mp > 12_000_000f) scale *= kotlin.math.sqrt(12_000_000f / mp)
                val w = (size.width * scale).toInt().coerceAtLeast(1)
                val h = (size.height * scale).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                doc.render(index, bmp, w, h)
                val found = withContext(Dispatchers.Default) {
                    api.setImage(bmp)
                    api.getUTF8Text()
                    val list = ArrayList<OcrWord>()
                    val it = api.resultIterator
                    if (it != null) {
                        try {
                            it.begin()
                            do {
                                val text = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)?.trim().orEmpty()
                                if (text.isEmpty() || it.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD) < 30f) continue
                                val r = it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                                list += OcrWord(text, r.left, r.top, r.right, r.bottom)
                            } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
                        } finally {
                            it.delete()
                        }
                    }
                    list
                }
                bmp.recycle()
                if (found.isNotEmpty()) words[index] = (w to h) to found
                onProgress((n + 1f) / pages.size * 0.9f)
            }
        } finally {
            api.recycle()
        }
        withContext(Dispatchers.IO) {
            PDFBoxResourceLoader.init(context.applicationContext)
            PDDocument.load(input, password ?: "", MemoryUsageSetting.setupTempFileOnly()).use { pd ->
                val font = PDType1Font.HELVETICA
                for ((index, entry) in words) {
                    val (imageSize, list) = entry
                    val page = pd.getPage(index)
                    val (pw, ph) = Stamping.seenSize(page)
                    val sx = pw / imageSize.first
                    val sy = ph / imageSize.second
                    val box = page.cropBox
                    PDPageContentStream(pd, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                        cs.beginText()
                        cs.setRenderingMode(RenderingMode.NEITHER)
                        for (word in list) {
                            val text = Stamping.encodable(font, word.text)
                            val heightPt = (word.bottom - word.top) * sy
                            val size = (heightPt * 1.1f).coerceIn(2f, 200f)
                            val natural = font.getStringWidth(text) / 1000f * size
                            if (natural <= 0f) continue
                            val target = (word.right - word.left) * sx
                            // Baseline a little above the bottom of the box (descenders).
                            val vx = word.left * sx
                            val vy = ph - word.bottom * sy + heightPt * 0.18f
                            val m = Stamping.uprightMatrix(page.rotation, box.lowerLeftX, box.lowerLeftY, box.width, box.height, vx, vy)
                            // Stretch the word to the width it has on the image, so selection lines up.
                            val k = target / natural
                            cs.setFont(font, size)
                            cs.setTextMatrix(Matrix(m[0] * k, m[1] * k, m[2], m[3], m[4], m[5]))
                            cs.showText(text)
                        }
                        cs.endText()
                    }
                }
                pd.save(output)
            }
        }
        onProgress(1f)
        return words.size
    }
}
