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
    external fun attachmentNames(handle: Long): Array<String>
    external fun attachmentData(handle: Long, index: Int): ByteArray?
    external fun addTextMarkup(page: Long, text: Long, start: Int, count: Int, subtype: Int, argb: Int, author: String?): Int
    external fun addInk(page: Long, xy: FloatArray, argb: Int, width: Float, author: String?): Int
    external fun inkAt(page: Long, x: Float, y: Float, tolerance: Float): Int
    external fun removeAnnot(page: Long, index: Int): Boolean
    external fun addNote(page: Long, x: Float, y: Float, contents: String, argb: Int, author: String?): Int
    external fun addTextStamp(handle: Long, page: Long, x: Float, y: Float, text: String, font: String, size: Float, argb: Int, author: String?): Int
    external fun annotAt(page: Long, x: Float, y: Float): Array<Any>?
    external fun setAnnotContents(page: Long, index: Int, contents: String): Boolean
    external fun addShape(page: Long, subtype: Int, x0: Float, y0: Float, x1: Float, y1: Float, stroke: Int, fill: Int, width: Float, author: String?): Int
    external fun addInkPaths(page: Long, xy: FloatArray, lengths: IntArray, argb: Int, width: Float, author: String?): Int
    external fun setAnnotString(page: Long, index: Int, key: String, value: String): Boolean
    external fun annotByName(page: Long, nm: String): Int
    external fun listAnnots(page: Long): Array<Any>
    external fun saveCopy(handle: Long, fd: Int, removeSecurity: Boolean): Boolean
    external fun linkAt(handle: Long, page: Long, x: Float, y: Float): String?
    external fun charIndexAt(page: Long, text: Long, x: Float, y: Float, tolerance: Float): Int
}
