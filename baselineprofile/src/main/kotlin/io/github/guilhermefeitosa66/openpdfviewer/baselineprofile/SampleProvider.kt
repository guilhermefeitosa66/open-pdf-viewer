package io.github.guilhermefeitosa66.openpdfviewer.baselineprofile

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/** Serves the sample PDF (from this test APK's assets) to the app, read-only. */
class SampleProvider : ContentProvider() {
    private fun file(): File {
        val ctx = context!!
        val f = File(ctx.cacheDir, NAME)
        if (!f.exists()) ctx.assets.open("sample.pdf").use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    override fun onCreate() = true
    override fun getType(uri: Uri) = "application/pdf"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>(NAME, file().length())) }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    companion object {
        const val NAME = "camera-field-guide.pdf"
        val URI: Uri = Uri.parse("content://io.github.guilhermefeitosa66.openpdfviewer.baselineprofile.samples/$NAME")

        /** "Open with" for the sample, granting the app read access. */
        fun viewIntent(context: Context): Intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(URI, "application/pdf")
            .setClassName(TARGET, "$TARGET_BASE.MainActivity")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

        /**
         * Opens the sample from this test process (not through the shell's "am start", which
         * cannot grant access to this APK's provider), then waits for the first page.
         */
        fun openSample(device: androidx.test.uiautomator.UiDevice) {
            val ctx = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context
            ctx.startActivity(viewIntent(ctx))
            device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.textContains("/ 41")), 10_000)
        }

        const val TARGET = "io.github.guilhermefeitosa66.openpdfviewer"
        const val TARGET_BASE = "io.github.guilhermefeitosa66.openpdfviewer"
    }
}
