package io.github.guilhermefeitosa66.openpdfviewer.pdfium

import android.graphics.Bitmap

/** JNI entry points. Call only from [PdfiumThread]. */
internal object PdfiumNative {
    init {
        System.loadLibrary("pdfium")
        System.loadLibrary("pdfium_jni")
        init()
    }

    external fun init()
    external fun openDocument(fd: Int, password: String?): Long
    external fun lastOpenError(): Int
    external fun closeDocument(handle: Long)
    external fun pageCount(handle: Long): Int
    external fun pageSizes(handle: Long): FloatArray
    external fun loadPage(handle: Long, index: Int): Long
    external fun closePage(page: Long)
    external fun renderPage(page: Long, bitmap: Bitmap, startX: Int, startY: Int, sizeX: Int, sizeY: Int): Boolean
    external fun loadText(page: Long): Long
    external fun closeText(text: Long)
    external fun pageText(text: Long): String
    external fun textRects(page: Long, text: Long, start: Int, count: Int): FloatArray
    external fun outline(handle: Long): Array<Any>
    external fun documentInfo(handle: Long): Array<Any>
    external fun linkAt(handle: Long, page: Long, x: Float, y: Float): String?
    external fun charIndexAt(page: Long, text: Long, x: Float, y: Float, tolerance: Float): Int
}
