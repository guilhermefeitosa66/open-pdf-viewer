package io.github.guilhermefeitosa66.openpdfviewer.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class RedactionTest {
    private fun found(text: String, q: String, regex: Boolean) = Redaction.find(text, q, regex).map { text.substring(it) }

    @Test fun cpf() = assertEquals(
        listOf("123.456.789-09", "98765432100"),
        found("CPF 123.456.789-09 e 98765432100; tel 1234", RedactPattern.CPF.regex, true),
    )
    @Test fun email() = assertEquals(listOf("ana.lima@exemplo.com.br"), found("fale com ana.lima@exemplo.com.br hoje", RedactPattern.EMAIL.regex, true))
    @Test fun plainIgnoresCase() = assertEquals(listOf("Secret", "SECRET"), found("Secret and SECRET", "secret", false))
    @Test fun plainEscapesSymbols() = assertEquals(listOf("a.b"), found("a.b axb", "a.b", false))
    @Test fun badRegexFindsNothing() = assertEquals(emptyList<String>(), found("abc", "(", true))
    @Test fun words() = assertEquals(listOf(0..2, 4..6), Redaction.wordRanges("one two"))
}
