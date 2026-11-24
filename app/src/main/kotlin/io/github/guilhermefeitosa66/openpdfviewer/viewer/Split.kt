package io.github.guilhermefeitosa66.openpdfviewer.viewer

/** How to cut a document into several files. */
sealed interface SplitMode {
    data class EveryN(val n: Int) : SplitMode
    /** Each comma-separated group of [text] becomes one file ("1-3, 4-10, 11-"). */
    data class Ranges(val text: String) : SplitMode
    data object EachPage : SplitMode
    data class MaxSize(val bytes: Long) : SplitMode

    companion object {
        /** The page groups for the modes that do not depend on file size; null when invalid. */
        fun groups(mode: SplitMode, count: Int): List<IntArray>? = when (mode) {
            is EveryN -> if (mode.n < 1) null else (0 until count).chunked(mode.n).map { it.toIntArray() }
            EachPage -> List(count) { intArrayOf(it) }
            is Ranges -> mode.text.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
                .map { PageRanges.parse(it, count)?.toIntArray() ?: return null }
                .ifEmpty { null }
            is MaxSize -> null
        }

        /**
         * Splits pages 0 until [count] into consecutive groups whose written size stays under
         * [max] bytes, using [sizeOf] to write a candidate group and measure it. A single page
         * larger than [max] still gets a file of its own. Grows each group by doubling, then
         * narrows down, so it writes O(log n) candidates per file.
         */
        suspend fun bySize(count: Int, max: Long, sizeOf: suspend (IntRange) -> Long): List<IntArray> {
            val out = ArrayList<IntArray>()
            var start = 0
            while (start < count) {
                var good = start // last end index known to fit (a single page always goes)
                var step = 1
                var bad = count // first end index known not to fit
                while (good + step < count) {
                    val end = good + step
                    if (sizeOf(start..end) <= max) {
                        good = end
                        step *= 2
                    } else {
                        bad = end
                        break
                    }
                }
                var lo = good
                var hi = minOf(bad, count)
                while (hi - lo > 1) {
                    val mid = (lo + hi) / 2
                    if (sizeOf(start..mid) <= max) lo = mid else hi = mid
                }
                out += (start..lo).toList().toIntArray()
                start = lo + 1
            }
            return out
        }
    }
}
