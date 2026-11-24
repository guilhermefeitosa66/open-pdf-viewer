package io.github.guilhermefeitosa66.openpdfviewer.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageRangesTest {
    @Test fun emptyMeansAll() = assertEquals(listOf(0, 1, 2), PageRanges.parse(" ", 3))
    @Test fun singlesAndRanges() = assertEquals(listOf(0, 1, 2, 4), PageRanges.parse("1-3, 5", 6))
    @Test fun openEnds() = assertEquals(listOf(0, 1, 4, 5), PageRanges.parse("-2, 5-", 6))
    @Test fun reversed() = assertEquals(listOf(2, 1, 0), PageRanges.parse("3-1", 3))
    @Test fun outOfRange() = assertNull(PageRanges.parse("2-9", 5))
    @Test fun garbage() = assertNull(PageRanges.parse("a, 2", 5))
    @Test fun zero() = assertNull(PageRanges.parse("0", 5))
}
