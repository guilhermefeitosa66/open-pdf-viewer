package io.github.guilhermefeitosa66.openpdfviewer.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class PageSizesTest {
    @Test
    fun a4() = assertEquals("A4 · 210 × 297 mm", PageSizes.describe(595.3f, 841.9f))

    @Test
    fun letterLandscape() = assertEquals("Letter", PageSizes.name(792f, 612f))

    @Test
    fun unknownShowsMillimeters() = assertEquals("100 × 100 mm", PageSizes.describe(283.46f, 283.46f))
}
