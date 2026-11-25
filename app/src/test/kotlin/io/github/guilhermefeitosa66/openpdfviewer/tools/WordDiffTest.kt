package io.github.guilhermefeitosa66.openpdfviewer.tools

import io.github.guilhermefeitosa66.openpdfviewer.tools.DiffRun.Kind.ADDED
import io.github.guilhermefeitosa66.openpdfviewer.tools.DiffRun.Kind.REMOVED
import io.github.guilhermefeitosa66.openpdfviewer.tools.DiffRun.Kind.SAME
import org.junit.Assert.assertEquals
import org.junit.Test

class WordDiffTest {
    private fun d(a: String, b: String) = WordDiff.diff(WordDiff.words(a), WordDiff.words(b)).map { it.kind to it.words.joinToString(" ") }

    @Test fun same() = assertEquals(listOf(SAME to "a b c"), d("a b c", "a  b\nc"))
    @Test fun changedWord() = assertEquals(listOf(SAME to "the", REMOVED to "red", ADDED to "blue", SAME to "car"), d("the red car", "the blue car"))
    @Test fun inserted() = assertEquals(listOf(SAME to "a", ADDED to "x y", SAME to "b"), d("a b", "a x y b"))
    @Test fun removedAtEnd() = assertEquals(listOf(SAME to "a b", REMOVED to "c"), d("a b c", "a b"))
    @Test fun allNew() = assertEquals(listOf(ADDED to "x"), d("", "x"))
    @Test fun empty() = assertEquals(emptyList<Any>(), d("", ""))

    @Test fun rebuildsBothTexts() {
        val a = WordDiff.words("one two three four five six seven")
        val b = WordDiff.words("one 2 three five six eight seven nine")
        val runs = WordDiff.diff(a, b)
        assertEquals(a, runs.filter { it.kind != ADDED }.flatMap { it.words })
        assertEquals(b, runs.filter { it.kind != REMOVED }.flatMap { it.words })
    }
}
