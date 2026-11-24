package io.github.guilhermefeitosa66.openpdfviewer.viewer

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Names common page sizes from their dimensions in points (1/72 inch). */
object PageSizes {
    private val known = listOf(
        "A3" to (842f to 1191f),
        "A4" to (595f to 842f),
        "A5" to (420f to 595f),
        "Letter" to (612f to 792f),
        "Legal" to (612f to 1008f),
        "Tabloid" to (792f to 1224f),
    )

    fun name(w: Float, h: Float): String? {
        val (s, l) = if (w <= h) w to h else h to w
        return known.firstOrNull { (_, d) -> abs(d.first - s) <= 3f && abs(d.second - l) <= 3f }?.first
    }

    /** "A4 · 210 × 297 mm", or just the millimeters for other sizes. */
    fun describe(w: Float, h: Float): String {
        val mm = String.format(Locale.ROOT, "%d × %d mm", (w / 72f * 25.4f).roundToInt(), (h / 72f * 25.4f).roundToInt())
        return name(w, h)?.let { "$it · $mm" } ?: mm
    }
}
