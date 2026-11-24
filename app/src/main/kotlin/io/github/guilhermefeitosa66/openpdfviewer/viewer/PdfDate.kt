package io.github.guilhermefeitosa66.openpdfviewer.viewer

import java.util.Calendar
import java.util.TimeZone

/** Parses PDF dates: "D:YYYYMMDDHHmmSSOHH'mm'" where everything after the year is optional. */
object PdfDate {
    private val pattern = Regex("""^(?:D:)?(\d{4})(\d{2})?(\d{2})?(\d{2})?(\d{2})?(\d{2})?([Zz+\-])?(\d{2})?'?(\d{2})?'?""")

    /** Milliseconds since the epoch, or null when the text is not a PDF date. */
    fun parse(text: String): Long? {
        val m = pattern.find(text.trim()) ?: return null
        val g = m.groupValues
        fun num(i: Int, default: Int) = g[i].toIntOrNull() ?: default
        val sign = g[7]
        val offsetMin = if (sign == "+" || sign == "-") {
            (num(8, 0) * 60 + num(9, 0)) * (if (sign == "-") -1 else 1)
        } else 0
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            clear()
            set(num(1, 1970), num(2, 1) - 1, num(3, 1), num(4, 0), num(5, 0), num(6, 0))
        }
        return cal.timeInMillis - offsetMin * 60_000L
    }
}
