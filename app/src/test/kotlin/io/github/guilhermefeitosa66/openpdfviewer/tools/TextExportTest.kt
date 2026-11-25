package io.github.guilhermefeitosa66.openpdfviewer.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class TextExportTest {
    private fun line(text: String, y: Float, size: Float = 10f, bold: Boolean = false, page: Int = 1) =
        TextLine(page, text, size, bold, y, size)

    @Test fun headingsAndParagraphs() {
        val md = TextExport.toMarkdown(listOf(
            line("Camera Guide", 50f, size = 24f),
            line("Getting started", 90f, size = 14f),
            line("First line of a para-", 120f),
            line("graph continues here.", 132f),
            line("Second paragraph.", 160f),
            line("Settings", 190f, bold = true),
            line("Text after.", 210f),
        ))
        assertEquals(
            "# Camera Guide\n\n## Getting started\n\nFirst line of a paragraph continues here.\n\nSecond paragraph.\n\n### Settings\n\nText after.\n",
            md,
        )
    }

    @Test fun pageBreakSplitsParagraphs() {
        val md = TextExport.toMarkdown(listOf(line("End of page one", 700f), line("Start of page two", 50f, page = 2)))
        assertEquals("End of page one\n\nStart of page two\n", md)
    }

    @Test fun empty() = assertEquals("", TextExport.toMarkdown(emptyList()))
}
