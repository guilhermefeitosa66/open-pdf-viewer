package io.github.guilhermefeitosa66.openpdfviewer.tools

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepairTest {
    @Test fun rebuildsPageTreeFromSurvivingPages() {
        val broken = """%PDF-1.7
1 0 obj << /Type /Pages /Kids [3 0 R 5 0 R] >> endobj
3 0 obj << /Type /Page /Parent 1 0 R /Contents 4 0 R >> endobj
4 0 obj << /Length 0 >> stream
endstream endobj
5 0 obj << /Type /Page /Parent 1 0 R >> endobj
6 0 obj << /Type /Page /Parent 1 0 R""" // cut short: no endobj
        val out = String(Repair.salvage(broken.toByteArray(Charsets.ISO_8859_1))!!, Charsets.ISO_8859_1)
        assertTrue(out, out.contains("/Type /Pages /Count 2"))
        assertTrue(out, out.contains("/Kids [3 0 R 5 0 R ]"))
        assertTrue(out, out.contains("8 0 obj\n<< /Type /Catalog /Pages 7 0 R >>"))
        assertTrue(out, out.contains("/Root 8 0 R"))
        // The unfinished object 6 is cut away before the new objects.
        assertTrue(out, !out.contains("6 0 obj"))
    }

    @Test fun nothingToSave() = assertNull(Repair.salvage("%PDF-1.7\n1 0 obj << >> endobj".toByteArray()))
}
