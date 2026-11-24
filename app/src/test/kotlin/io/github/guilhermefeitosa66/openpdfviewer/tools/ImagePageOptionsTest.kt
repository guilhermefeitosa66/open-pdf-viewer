package io.github.guilhermefeitosa66.openpdfviewer.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class ImagePageOptionsTest {
    @Test fun fitsA4PortraitCentred() {
        val (size, r) = ImagePageOptions.layout(ImagePageOptions(ImagePageOptions.A4, margin = 20f), 1000, 1000)
        assertEquals(595.28f, size.first, 0.01f)
        assertEquals(841.89f, size.second, 0.01f)
        assertEquals(555.28f, r[2], 0.01f) // full width inside the margins
        assertEquals(20f, r[0], 0.01f)
        assertEquals((841.89f - 555.28f) / 2, r[1], 0.01f)
    }

    @Test fun landscapeImageTurnsPage() {
        val (size, _) = ImagePageOptions.layout(ImagePageOptions(ImagePageOptions.A4), 2000, 1000)
        assertEquals(841.89f, size.first, 0.01f)
    }

    @Test fun forcedPortrait() {
        val (size, _) = ImagePageOptions.layout(ImagePageOptions(ImagePageOptions.LETTER, landscape = false), 2000, 1000)
        assertEquals(612f, size.first, 0.01f)
    }

    @Test fun fitToImage() {
        val (size, r) = ImagePageOptions.layout(ImagePageOptions(null, margin = 10f), 400, 800)
        assertEquals(841.89f + 20f, size.second, 0.01f)
        assertEquals(10f, r[0], 0.01f)
        assertEquals(841.89f, r[3], 0.01f)
    }
}
