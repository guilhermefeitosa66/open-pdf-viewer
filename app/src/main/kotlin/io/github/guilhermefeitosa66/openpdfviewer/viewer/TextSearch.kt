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

    /** Page char range (start, count) of the normalized range [nStart, nEnd). */
    fun toPage(nStart: Int, nEnd: Int): Pair<Int, Int> {
        if (map.isEmpty() || nEnd <= nStart) return 0 to 0
        val start = map[nStart.coerceIn(0, map.size - 1)]
        val end = map[(nEnd - 1).coerceIn(0, map.size - 1)] + 1
        return start to (end - start).coerceAtLeast(0)
    }

    /** Sentences of the page as normalized ranges, skipping blank ones. */
    fun sentences(locale: java.util.Locale): List<IntRange> {
        val it = java.text.BreakIterator.getSentenceInstance(locale)
        it.setText(text)
        val out = ArrayList<IntRange>()
        var start = it.first()
        var end = it.next()
        while (end != java.text.BreakIterator.DONE) {
            // Trim spaces so highlights hug the words.
            var a = start
            var b = end
            while (a < b && text[a] == ' ') a++
            while (b > a && text[b - 1] == ' ') b--
            if (b > a && text.substring(a, b).any { c -> c.isLetterOrDigit() }) out += a until b
            start = end
            end = it.next()
        }
        return out
    }

    /** Search options beyond the text itself. */
    data class Options(val matchCase: Boolean = false, val wholeWord: Boolean = false, val ignoreAccents: Boolean = true)

    /** One hit: the page char range plus a short excerpt around it for the results list. */
    data class Hit(val start: Int, val count: Int, val before: String, val match: String, val after: String)

    /** Accent-folded copy of [text], same length, so indices stay aligned. */
    private val folded: String by lazy { fold(text) }

    /** All matches as (page char start, page char count). */
    fun find(query: String, matchCase: Boolean, limit: Int = Int.MAX_VALUE): List<Pair<Int, Int>> =
        findHits(query, Options(matchCase = matchCase, ignoreAccents = false), limit).map { it.start to it.count }

    fun findHits(query: String, options: Options, limit: Int = Int.MAX_VALUE): List<Hit> {
        var q = normalizeQuery(query)
        if (q.isEmpty()) return emptyList()
        val hay = if (options.ignoreAccents) folded else text
        if (options.ignoreAccents) q = fold(q)
        val out = ArrayList<Hit>()
        var from = 0
        while (out.size < limit) {
            val idx = hay.indexOf(q, from, ignoreCase = !options.matchCase)
            if (idx < 0) break
            val end = idx + q.length
            from = idx + q.length.coerceAtLeast(1)
            if (options.wholeWord && (isWordAt(idx - 1) || isWordAt(end))) continue
            val start = map[idx]
            val pageEnd = map[end - 1] + 1
            out += Hit(
                start, pageEnd - start,
                before = excerpt(text, (idx - CONTEXT).coerceAtLeast(0), idx, leading = true),
                match = text.substring(idx, end),
                after = excerpt(text, end, (end + CONTEXT).coerceAtMost(text.length), leading = false),
            )
        }
        return out
    }

    private fun isWordAt(i: Int) = i in text.indices && text[i].isLetterOrDigit()

    companion object {
        private const val CONTEXT = 40

        /** Removes diacritics char by char (á → a, ç → c), keeping the length. */
        fun fold(s: String): String {
            val sb = StringBuilder(s.length)
            for (c in s) {
                val d = java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFD)
                val base = d[0]
                val onlyMarks = d.length > 1 && d.substring(1).all { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
                sb.append(if (onlyMarks || d.length == 1) base else c)
            }
            return sb.toString()
        }

        /** A piece of context cut at a word boundary, with an ellipsis when cut. */
        private fun excerpt(t: String, from: Int, to: Int, leading: Boolean): String {
            var a = from
            var b = to
            if (leading && a > 0) { val sp = t.indexOf(' ', a); if (sp in a until b) a = sp + 1 }
            if (!leading && b < t.length) { val sp = t.lastIndexOf(' ', b - 1); if (sp > a) b = sp }
            val core = t.substring(a, b)
            return when {
                leading && a > 0 -> "…$core"
                !leading && b < t.length -> "$core…"
                else -> core
            }
        }

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
