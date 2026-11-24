package io.github.guilhermefeitosa66.openpdfviewer.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class CopyTest {
    @Test
    fun copiesEverythingAndReportsProgress() = runBlocking {
        val data = ByteArray(1_000_000) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        var last: Float? = -1f
        DocumentSource.copy(ByteArrayInputStream(data), out, data.size.toLong()) { last = it }
        assertArrayEquals(data, out.toByteArray())
        assertEquals(1f, last)
    }

    @Test
    fun unknownSizeReportsNull() = runBlocking {
        val out = ByteArrayOutputStream()
        var last: Float? = 0f
        DocumentSource.copy(ByteArrayInputStream(ByteArray(10)), out, -1) { last = it }
        assertEquals(null, last)
    }
}
