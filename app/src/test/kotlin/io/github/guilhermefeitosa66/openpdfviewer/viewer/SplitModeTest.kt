package io.github.guilhermefeitosa66.openpdfviewer.viewer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SplitModeTest {
    private fun List<IntArray>.plain() = map { it.toList() }

    @Test fun everyN() = assertEquals(listOf(listOf(0, 1), listOf(2, 3), listOf(4)), SplitMode.groups(SplitMode.EveryN(2), 5)!!.plain())
    @Test fun eachPage() = assertEquals(3, SplitMode.groups(SplitMode.EachPage, 3)!!.size)
    @Test fun ranges() = assertEquals(listOf(listOf(0, 1, 2), listOf(3, 4)), SplitMode.groups(SplitMode.Ranges("1-3, 4-"), 5)!!.plain())
    @Test fun badRange() = assertNull(SplitMode.groups(SplitMode.Ranges("1-9"), 5))

    @Test fun bySizeRespectsLimit() = runBlocking {
        // Page i weighs 10 + i bytes; 50 bytes of shared overhead per file.
        val sizes = List(20) { 10L + it }
        val groups = SplitMode.bySize(20, 120) { r -> 50 + r.sumOf { sizes[it] } }
        assertEquals((0 until 20).toList(), groups.flatMap { it.toList() })
        for (g in groups) assert(50 + g.sumOf { sizes[it] } <= 120 || g.size == 1)
        // Greedy: adding the next page to any group would break the limit.
        for (g in groups.dropLast(1)) assert(50 + g.sumOf { sizes[it] } + sizes[g.last() + 1] > 120)
    }

    @Test fun bySizeOversizedPage() = runBlocking {
        val groups = SplitMode.bySize(3, 10) { 100L }
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), groups.plain())
    }
}
