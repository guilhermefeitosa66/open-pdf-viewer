package io.github.guilhermefeitosa66.openpdfviewer.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class OutlineTreeTest {
    // 0 Part A
    // 1   Ch 1
    // 2     1.1
    // 3   Ch 2
    // 4 Part B
    private val levels = listOf(0, 1, 2, 1, 0)
    private val pages = listOf(0, 1, 2, 5, 9)

    @Test
    fun collapsedShowsTopLevel() = assertEquals(listOf(0, 4), OutlineTree.visible(levels) { false })

    @Test
    fun expandingPartShowsChapters() = assertEquals(listOf(0, 1, 3, 4), OutlineTree.visible(levels) { it == 0 })

    @Test
    fun expandingBothShowsSection() = assertEquals(listOf(0, 1, 2, 3, 4), OutlineTree.visible(levels) { it == 0 || it == 1 })

    @Test
    fun childOfCollapsedParentStaysHidden() = assertEquals(listOf(0, 4), OutlineTree.visible(levels) { it == 1 })

    @Test
    fun hasChildren() = assertEquals(listOf(true, true, false, false, false), OutlineTree.hasChildren(levels).toList())

    @Test
    fun opensBranchOfCurrentPage() = assertEquals(listOf(1, 0), OutlineTree.ancestorsOfPage(levels, pages, 3))
}
