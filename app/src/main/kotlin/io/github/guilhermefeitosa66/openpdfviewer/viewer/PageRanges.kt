package io.github.guilhermefeitosa66.openpdfviewer.viewer

/** Page ranges as people type them ("1-3, 5, 8-"), 1-based, into 0-based indices. */
object PageRanges {
    /** The indices in the order written, or null when the text is not valid for [count] pages. */
    fun parse(text: String, count: Int): List<Int>? {
        val t = text.trim()
        if (t.isEmpty()) return List(count) { it }
        val out = ArrayList<Int>()
        for (part in t.split(',', ';')) {
            val p = part.trim()
            if (p.isEmpty()) continue
            val m = Regex("""^(\d*)\s*[-–]\s*(\d*)$""").find(p)
            if (m != null) {
                val a = m.groupValues[1].ifEmpty { "1" }.toIntOrNull() ?: return null
                val b = m.groupValues[2].ifEmpty { count.toString() }.toIntOrNull() ?: return null
                if (a < 1 || b < 1 || a > count || b > count) return null
                val range = if (a <= b) a..b else a downTo b
                for (n in range) out += n - 1
            } else {
                val n = p.toIntOrNull() ?: return null
                if (n < 1 || n > count) return null
                out += n - 1
            }
        }
        return out.ifEmpty { null }
    }
}
