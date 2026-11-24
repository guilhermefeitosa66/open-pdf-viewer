package io.github.guilhermefeitosa66.openpdfviewer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryTest {
    private val files = listOf(
        LibraryFile("a", "Contrato.pdf", 300, 10),
        LibraryFile("b", "boleto.pdf", 100, 30),
        LibraryFile("c", "Manual câmera.pdf", 200, 20),
    )

    @Test
    fun sortsByName() = assertEquals(listOf("b", "a", "c"), Library.filterAndSort(files, "", Library.Companion.Sort.NAME).map { it.uri })

    @Test
    fun sortsByDateNewestFirst() = assertEquals(listOf("b", "c", "a"), Library.filterAndSort(files, "", Library.Companion.Sort.DATE).map { it.uri })

    @Test
    fun filtersIgnoringCase() = assertEquals(listOf("a"), Library.filterAndSort(files, "contr", Library.Companion.Sort.SIZE).map { it.uri })

    @Test
    fun jsonRoundTrip() = assertEquals(files, Library.decodeFiles(Library.encodeFiles(files)))
}
