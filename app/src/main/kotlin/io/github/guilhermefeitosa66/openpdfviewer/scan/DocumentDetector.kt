package io.github.guilhermefeitosa66.openpdfviewer.scan

/**
 * Finds a sheet of paper in a photo and cleans up scanned pages, with plain arrays so it runs
 * anywhere (no OpenCV, no ML). Images are grayscale, row-major, values 0..255.
 */
object DocumentDetector {

    /** Otsu's threshold: the gray level that best splits the histogram into two classes. */
    fun otsu(gray: IntArray): Int {
        val hist = IntArray(256)
        for (v in gray) hist[v.coerceIn(0, 255)]++
        val total = gray.size.toDouble()
        var sum = 0.0
        for (i in 0 until 256) sum += i * hist[i].toDouble()
        var sumB = 0.0
        var wB = 0.0
        var best = 0.0
        var threshold = 127
        for (t in 0 until 256) {
            wB += hist[t]
            if (wB == 0.0) continue
            val wF = total - wB
            if (wF == 0.0) break
            sumB += t * hist[t].toDouble()
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between
                threshold = t
            }
        }
        return threshold
    }

    /**
     * The four corners of the page (top-left, top-right, bottom-right, bottom-left), as x, y
     * pairs normalized to 0..1, or null when nothing page-like is found. The page is taken as
     * the largest bright region; its corners are the extreme points along the diagonals.
     */
    fun detect(gray: IntArray, w: Int, h: Int): FloatArray? {
        if (w < 8 || h < 8) return null
        val t = otsu(gray)
        val bright = BooleanArray(w * h) { gray[it] > t }
        // Largest 4-connected bright region (flood fill with an explicit stack).
        val label = IntArray(w * h)
        var bestLabel = 0
        var bestSize = 0
        var next = 1
        val stack = IntArray(w * h)
        for (start in 0 until w * h) {
            if (!bright[start] || label[start] != 0) continue
            var sp = 0
            stack[sp++] = start
            label[start] = next
            var size = 0
            while (sp > 0) {
                val p = stack[--sp]
                size++
                val x = p % w
                val y = p / w
                if (x > 0 && bright[p - 1] && label[p - 1] == 0) { label[p - 1] = next; stack[sp++] = p - 1 }
                if (x < w - 1 && bright[p + 1] && label[p + 1] == 0) { label[p + 1] = next; stack[sp++] = p + 1 }
                if (y > 0 && bright[p - w] && label[p - w] == 0) { label[p - w] = next; stack[sp++] = p - w }
                if (y < h - 1 && bright[p + w] && label[p + w] == 0) { label[p + w] = next; stack[sp++] = p + w }
            }
            if (size > bestSize) {
                bestSize = size
                bestLabel = next
            }
            next++
        }
        // Too small, or the whole frame (no background to tell the page apart): no detection.
        if (bestSize < w * h / 10 || bestSize > w * h * 97 / 100) return null
        var tl = -1; var tr = -1; var br = -1; var bl = -1
        var minSum = Int.MAX_VALUE; var maxSum = Int.MIN_VALUE; var minDiff = Int.MAX_VALUE; var maxDiff = Int.MIN_VALUE
        for (p in 0 until w * h) {
            if (label[p] != bestLabel) continue
            val x = p % w
            val y = p / w
            val s = x + y
            val d = x - y
            if (s < minSum) { minSum = s; tl = p }
            if (s > maxSum) { maxSum = s; br = p }
            if (d > maxDiff) { maxDiff = d; tr = p }
            if (d < minDiff) { minDiff = d; bl = p }
        }
        fun nx(p: Int) = (p % w + 0.5f) / w
        fun ny(p: Int) = (p / w + 0.5f) / h
        return floatArrayOf(nx(tl), ny(tl), nx(tr), ny(tr), nx(br), ny(br), nx(bl), ny(bl))
    }

    /** Output size (width, height) for the page between the corners, from its edge lengths in pixels. */
    fun outputSize(corners: FloatArray, imageW: Int, imageH: Int): Pair<Int, Int> {
        fun len(a: Int, b: Int): Double {
            val dx = (corners[a * 2] - corners[b * 2]) * imageW.toDouble()
            val dy = (corners[a * 2 + 1] - corners[b * 2 + 1]) * imageH.toDouble()
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }
        val w = maxOf(len(0, 1), len(3, 2))
        val h = maxOf(len(0, 3), len(1, 2))
        return w.toInt().coerceAtLeast(1) to h.toInt().coerceAtLeast(1)
    }

    /**
     * Black-and-white "document" look: each pixel is compared with the mean of its
     * neighbourhood (integral image), so shadows and uneven light do not turn into black areas.
     */
    fun adaptiveThreshold(gray: IntArray, w: Int, h: Int, radius: Int = maxOf(8, minOf(w, h) / 24), offset: Int = 12): IntArray {
        val integral = LongArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0L
            for (x in 0 until w) {
                row += gray[y * w + x]
                integral[(y + 1) * (w + 1) + x + 1] = integral[y * (w + 1) + x + 1] + row
            }
        }
        // Clearly dark pixels stay black even inside large dark areas, where the local mean is
        // dark too (a filled box or a thick title bar would otherwise come out hollow).
        // "Clearly dark" = under a third of the paper's brightness (mean of the bright class).
        val t = otsu(gray)
        var sum = 0L
        var n = 0
        for (v in gray) if (v > t) { sum += v; n++ }
        val dark = if (n == 0) 0 else (sum / n * 35 / 100).toInt()
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val y0 = maxOf(0, y - radius); val y1 = minOf(h, y + radius + 1)
            for (x in 0 until w) {
                val x0 = maxOf(0, x - radius); val x1 = minOf(w, x + radius + 1)
                val sum = integral[y1 * (w + 1) + x1] - integral[y0 * (w + 1) + x1] - integral[y1 * (w + 1) + x0] + integral[y0 * (w + 1) + x0]
                val mean = sum / ((x1 - x0) * (y1 - y0))
                val v = gray[y * w + x]
                out[y * w + x] = if (v < mean - offset || v < dark) 0 else 255
            }
        }
        return out
    }
}
