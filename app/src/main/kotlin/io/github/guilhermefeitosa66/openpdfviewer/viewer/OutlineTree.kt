package io.github.guilhermefeitosa66.openpdfviewer.viewer

/** Tree logic for the flattened outline (entries in reading order with nesting levels). */
object OutlineTree {

    /** Whether each entry has children (the next entry is deeper). */
    fun hasChildren(levels: List<Int>): BooleanArray =
        BooleanArray(levels.size) { i -> i + 1 < levels.size && levels[i + 1] > levels[i] }

    /** Indices to show: an entry is visible when every ancestor is expanded. */
    fun visible(levels: List<Int>, isExpanded: (Int) -> Boolean): List<Int> {
        val out = ArrayList<Int>()
        // Stack of (level, open) for the ancestors of the current entry.
        val stack = ArrayDeque<Pair<Int, Boolean>>()
        for (i in levels.indices) {
            val lv = levels[i]
            while (stack.isNotEmpty() && stack.last().first >= lv) stack.removeLast()
            val shown = stack.all { it.second }
            if (shown) out += i
            stack.addLast(lv to (shown && isExpanded(i)))
        }
        return out
    }

    /** Ancestors of the last entry that starts at or before [page], so its branch opens. */
    fun ancestorsOfPage(levels: List<Int>, pages: List<Int>, page: Int): List<Int> {
        val at = pages.indexOfLast { it in 0..page }
        if (at < 0) return emptyList()
        val out = ArrayList<Int>()
        var level = levels[at]
        for (i in at - 1 downTo 0) {
            if (levels[i] < level) {
                out += i
                level = levels[i]
            }
        }
        return out
    }
}
