package io.github.guilhermefeitosa66.openpdfviewer.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class NormalizedTextTest {

    @Test
    fun findsAcrossGeneratedLineBreaks() {
        val page = "image\r\nstabilization buys"
        val m = NormalizedText(page).find("image stabilization", matchCase = false)
        assertEquals(listOf(0 to 20), m)
        assertEquals("image\r\nstabilization", page.substring(0, 20))
    }

    @Test
    fun joinsWrapHyphens() {
        val page = "stabi\u0002\r\nlization"
        val m = NormalizedText(page).find("stabilization", matchCase = false)
        assertEquals(1, m.size)
        assertEquals(0, m[0].first)
        assertEquals(page.length, m[0].second)
    }

    @Test
    fun respectsMatchCase() {
        val page = "Night night NIGHT"
        assertEquals(3, NormalizedText(page).find("night", matchCase = false).size)
        assertEquals(listOf(6 to 5), NormalizedText(page).find("night", matchCase = true))
    }

    @Test
    fun ignoresBlankQueries() {
        assertEquals(emptyList<Pair<Int, Int>>(), NormalizedText("abc").find("   ", matchCase = false))
    }

    @Test
    fun clipboardKeepsLinesAndJoinsHyphens() {
        val page = "Left on,\r\nit can stabi\u0002\r\nlize"
        assertEquals("Left on,\nit can stabilize", NormalizedText.forClipboard(page, 0, page.length))
    }
}

class SentencesTest {
    @org.junit.Test
    fun splitsSentencesAndMapsBack() {
        val page = "Hold still.\r\nThe counter reaches zero. Then\r\nshoot!"
        val n = NormalizedText(page)
        val s = n.sentences(java.util.Locale.ENGLISH)
        org.junit.Assert.assertEquals(listOf("Hold still.", "The counter reaches zero.", "Then shoot!"), s.map { n.text.substring(it.first, it.last + 1) })
        val (start, count) = n.toPage(s[2].first, s[2].last + 1)
        org.junit.Assert.assertEquals("Then\r\nshoot!", page.substring(start, start + count))
    }
}

class AdvancedSearchTest {
    private val page = "A função de exportação\r\nexporta tudo. Exportar é fácil; não-exportável."

    @org.junit.Test
    fun ignoresAccentsByDefault() {
        val hits = NormalizedText(page).findHits("exportacao", NormalizedText.Options())
        org.junit.Assert.assertEquals(listOf("exportação"), hits.map { it.match })
    }

    @org.junit.Test
    fun accentsCanMatter() {
        val hits = NormalizedText(page).findHits("exportacao", NormalizedText.Options(ignoreAccents = false))
        org.junit.Assert.assertEquals(0, hits.size)
    }

    @org.junit.Test
    fun wholeWordSkipsPartials() {
        val all = NormalizedText(page).findHits("exporta", NormalizedText.Options())
        val whole = NormalizedText(page).findHits("exporta", NormalizedText.Options(wholeWord = true))
        org.junit.Assert.assertEquals(4, all.size)
        org.junit.Assert.assertEquals(listOf("exporta"), whole.map { it.match })
    }

    @org.junit.Test
    fun contextAroundTheMatch() {
        val hit = NormalizedText(page).findHits("fácil", NormalizedText.Options()).single()
        org.junit.Assert.assertTrue(hit.before.endsWith("Exportar é "))
        org.junit.Assert.assertTrue(hit.after.startsWith("; não"))
    }

    @org.junit.Test
    fun foldKeepsLength() {
        val s = "Ação, pingüim, naïve, Ñandú"
        org.junit.Assert.assertEquals(s.length, NormalizedText.fold(s).length)
        org.junit.Assert.assertEquals("Acao, pinguim, naive, Nandu", NormalizedText.fold(s))
    }
}
