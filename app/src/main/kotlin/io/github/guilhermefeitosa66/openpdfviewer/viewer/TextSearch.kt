package io.github.guilhermefeitosa66.openpdfviewer.viewer

/**
 * Page text as PDFium gives it has generated line breaks ("\r\n") and soft-hyphen markers
 * (U+0002) where a word wraps. Searching and copying work on a normalized form where every run of
 * whitespace is one space and hyphen markers are dropped; [map] leads back to page char indices.
 */
class NormalizedText(source: String) {
    val text: String
    private val map: IntArray

    init {
        val sb = StringBuilder(source.length)
        val m = IntArray(source.length)
        var n = 0
        var lastWasSpace = true
        var afterWrapHyphen = false
        for (i in source.indices) {
            val c = source[i]
            when {
                c == '\u0002' || c == '\u00AD' -> afterWrapHyphen = true
                c.isWhitespace() || c == '\u0000' -> {
                    if (lastWasSpace || afterWrapHyphen) continue
                    sb.append(' ')
                    m[n++] = i
                    lastWasSpace = true
                }
                else -> {
                    sb.append(c)
                    m[n++] = i
                    lastWasSpace = false
                    afterWrapHyphen = false
                }
            }
        }
        text = sb.toString()
        map = m.copyOf(n)
    }

    /** All matches as (page char start, page char count). */
    fun find(query: String, matchCase: Boolean, limit: Int = Int.MAX_VALUE): List<Pair<Int, Int>> {
        val q = normalizeQuery(query)
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<Pair<Int, Int>>()
        var from = 0
        while (out.size < limit) {
            val idx = text.indexOf(q, from, ignoreCase = !matchCase)
            if (idx < 0) break
            val start = map[idx]
            val end = map[idx + q.length - 1] + 1
            out += start to (end - start)
            from = idx + q.length.coerceAtLeast(1)
        }
        return out
    }

    companion object {
        fun normalizeQuery(q: String): String = q.trim().replace(Regex("\\s+"), " ")

        /** Text for the clipboard: line breaks kept, wrap hyphens joined. */
        fun forClipboard(pageText: String, start: Int, end: Int): String =
            pageText.substring(start.coerceAtLeast(0), end.coerceAtMost(pageText.length))
                .replace("\u0002\r\n", "")
                .replace("\u0002", "")
                .replace("\r\n", "\n")
                .replace('\u0000', ' ')
                .trim()

        fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '\'' || c == '’' || c == '-' || c == '_'
    }
}
