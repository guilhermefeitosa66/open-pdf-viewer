package io.github.guilhermefeitosa66.openpdfviewer.tools

/** One run of a word diff: words both texts share, or only the old / only the new has. */
data class DiffRun(val kind: Kind, val words: List<String>) {
    enum class Kind { SAME, REMOVED, ADDED }
}

/** Word-level differences between two texts (Myers' algorithm), for comparing PDF pages. */
object WordDiff {

    fun words(text: String): List<String> = text.split(Regex("\\s+")).filter { it.isNotEmpty() }

    fun diff(old: List<String>, new: List<String>): List<DiffRun> {
        val n = old.size
        val m = new.size
        val max = n + m
        if (max == 0) return emptyList()
        val offset = max
        var v = IntArray(2 * max + 2)
        val trace = ArrayList<IntArray>()
        var found = false
        for (d in 0..max) {
            trace += v.copyOf()
            var k = -d
            while (k <= d) {
                var x = if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) v[offset + k + 1] else v[offset + k - 1] + 1
                var y = x - k
                while (x < n && y < m && old[x] == new[y]) { x++; y++ }
                v[offset + k] = x
                if (x >= n && y >= m) { found = true; break }
                k += 2
            }
            if (found) break
        }
        // Walk the trace back into edit operations.
        val ops = ArrayList<Pair<DiffRun.Kind, String>>()
        var x = n
        var y = m
        for (d in trace.size - 1 downTo 0) {
            val vd = trace[d]
            val k = x - y
            val prevK = if (k == -d || (k != d && vd[offset + k - 1] < vd[offset + k + 1])) k + 1 else k - 1
            val prevX = if (d == 0) 0 else vd[offset + prevK]
            val prevY = prevX - prevK
            while (x > prevX && y > prevY) { ops += DiffRun.Kind.SAME to old[x - 1]; x--; y-- }
            if (d > 0) {
                if (x == prevX) ops += DiffRun.Kind.ADDED to new[y - 1] else ops += DiffRun.Kind.REMOVED to old[x - 1]
            }
            x = prevX
            y = prevY
        }
        ops.reverse()
        // Merge into runs.
        val runs = ArrayList<DiffRun>()
        for ((kind, w) in ops) {
            val last = runs.lastOrNull()
            if (last != null && last.kind == kind) runs[runs.size - 1] = last.copy(words = last.words + w)
            else runs += DiffRun(kind, listOf(w))
        }
        return runs
    }
}
