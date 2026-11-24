package io.github.guilhermefeitosa66.openpdfviewer.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PdfDateTest {
    @Test
    fun fullDateWithOffset() =
        // 2026-11-24 14:34:31 at -03:00 is 17:34:31 UTC.
        assertEquals(1795541671000L, PdfDate.parse("D:20261124143431-03'00'"))

    @Test
    fun utcZ() = assertEquals(1795530871000L, PdfDate.parse("D:20261124143431Z"))

    @Test
    fun yearOnly() = assertEquals(1767225600000L, PdfDate.parse("D:2026"))

    @Test
    fun garbage() = assertNull(PdfDate.parse("yesterday"))
}
