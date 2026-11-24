package io.github.guilhermefeitosa66.openpdfviewer.viewer

/** Finds the white margins of a rendered page. */
object Margins {
    /**
     * The normalized box [l, t, r, b] around everything that is not near-white in [pixels]
     * (ARGB, [w] x [h]), grown by [pad] on each side; null for a blank page.
     */
    fun contentBox(pixels: IntArray, w: Int, h: Int, threshold: Int = 235, pad: Float = 0.015f): FloatArray? {
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val c = pixels[row + x]
                val lum = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
                if (lum < threshold) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return null
        return floatArrayOf(
            (minX.toFloat() / w - pad).coerceAtLeast(0f),
            (minY.toFloat() / h - pad).coerceAtLeast(0f),
            ((maxX + 1).toFloat() / w + pad).coerceAtMost(1f),
            ((maxY + 1).toFloat() / h + pad).coerceAtMost(1f),
        )
    }

    /** The union of several boxes (for "same crop on every page"). */
    fun union(boxes: List<FloatArray>): FloatArray? = if (boxes.isEmpty()) null else floatArrayOf(
        boxes.minOf { it[0] }, boxes.minOf { it[1] }, boxes.maxOf { it[2] }, boxes.maxOf { it[3] },
    )
}
