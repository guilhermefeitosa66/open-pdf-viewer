package io.github.guilhermefeitosa66.openpdfviewer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class BookmarksTest {
    @Test
    fun roundTripSortedByPage() {
        val list = listOf(Bookmark(9, "Índice \"final\"", 2L), Bookmark(2, "Capítulo 1", 1L))
        assertEquals(list.sortedBy { it.page }, Bookmarks.decode(Bookmarks.encode(list)))
    }
}
