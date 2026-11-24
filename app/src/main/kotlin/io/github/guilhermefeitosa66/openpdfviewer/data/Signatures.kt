package io.github.guilhermefeitosa66.openpdfviewer.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Up to [MAX] saved signatures or initials, as transparent PNGs in app storage (never in the
 * cloud, never in the documents until the user places them).
 */
class Signatures(context: Context) {
    private val dir = File(context.filesDir, "signatures")

    suspend fun list(): List<File> = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.extension == "png" }?.sortedBy { it.name }.orEmpty()
    }

    suspend fun load(file: File): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()
    }

    /** Saves a signature; false when there are already [MAX]. */
    suspend fun add(bitmap: Bitmap): Boolean = withContext(Dispatchers.IO) {
        dir.mkdirs()
        if ((dir.listFiles()?.count { it.extension == "png" } ?: 0) >= MAX) return@withContext false
        val f = File(dir, "sig-${System.currentTimeMillis()}.png")
        f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        true
    }

    suspend fun delete(file: File) = withContext(Dispatchers.IO) { file.delete() }

    companion object {
        const val MAX = 3

        /**
         * Makes light pixels transparent (a photo of a signature on paper) and crops to the ink,
         * scaled to at most [maxSide] pixels. Ink keeps its color; pale paper becomes see-through
         * with a soft edge between [paperFrom] and [paperTo] luminance.
         */
        fun removeBackground(src: Bitmap, maxSide: Int = 1200, paperFrom: Int = 150, paperTo: Int = 210): Bitmap? {
            val scale = minOf(1f, maxSide.toFloat() / maxOf(src.width, src.height))
            val base = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true) else src
            val w = base.width
            val h = base.height
            val px = IntArray(w * h)
            base.getPixels(px, 0, w, 0, 0, w, h)
            var minX = w; var minY = h; var maxX = -1; var maxY = -1
            for (i in px.indices) {
                val c = px[i]
                val lum = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
                val alpha = when {
                    lum >= paperTo -> 0
                    lum <= paperFrom -> 255
                    else -> 255 * (paperTo - lum) / (paperTo - paperFrom)
                } * Color.alpha(c) / 255
                px[i] = Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))
                if (alpha > 40) {
                    val x = i % w
                    val y = i / w
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
            if (maxX < minX || maxY < minY) return null
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            out.setPixels(px, 0, w, 0, 0, w, h)
            val pad = 4
            val x0 = (minX - pad).coerceAtLeast(0)
            val y0 = (minY - pad).coerceAtLeast(0)
            val x1 = (maxX + pad).coerceAtMost(w - 1)
            val y1 = (maxY + pad).coerceAtMost(h - 1)
            return Bitmap.createBitmap(out, x0, y0, x1 - x0 + 1, y1 - y0 + 1)
        }
    }
}
