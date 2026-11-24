package io.github.guilhermefeitosa66.openpdfviewer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FileActionsTest {
    @Test
    fun keepsPdfNames() = assertEquals("Contrato 2026.pdf", FileActions.safeName("Contrato 2026.pdf"))

    @Test
    fun addsExtension() = assertEquals("scan.pdf", FileActions.safeName("scan"))

    @Test
    fun keepsUppercaseExtension() = assertEquals("PLANTA.PDF", FileActions.safeName("PLANTA.PDF"))

    @Test
    fun replacesForbiddenCharacters() = assertEquals("a_b_c_.pdf", FileActions.safeName("a/b:c?.pdf"))

    @Test
    fun emptyBecomesDocument() = assertEquals("document.pdf", FileActions.safeName("  "))
}
