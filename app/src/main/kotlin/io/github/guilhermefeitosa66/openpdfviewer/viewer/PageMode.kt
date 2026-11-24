package io.github.guilhermefeitosa66.openpdfviewer.viewer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix

/**
 * How the page itself is painted. The rendered bitmaps never change: the mode is a color filter
 * applied when drawing, so switching is instant and needs no re-render.
 *
 * Night and sepia map each pixel's luminance onto a two-color ramp (ink → paper). Text keeps its
 * contrast; photos and colored figures become monochrome in those modes, which is the point of a
 * reading mode.
 */
enum class PageMode(val paper: Color, private val ink: Color?) {
    NORMAL(Color.White, null),
    NIGHT(Color(0xFF16130F), Color(0xFFE6DFD3)),
    SEPIA(Color(0xFFF4E9D3), Color(0xFF3B2E22));

    /** Null for NORMAL (draw the page as authored). */
    val filter: ColorFilter? by lazy { ink?.let { ColorFilter.colorMatrix(rampMatrix(it, paper)) } }

    /** Search and selection tints are multiplied on white paper but painted over dark paper. */
    val multiplyHighlights get() = this != NIGHT

    companion object {
        fun fromName(name: String?) = entries.firstOrNull { it.name == name } ?: NORMAL

        /**
         * out = ink + (paper - ink) * luminance, per channel, with luminance from Rec. 601
         * weights. Black maps to [ink], white to [paper].
         */
        fun rampMatrix(ink: Color, paper: Color): ColorMatrix {
            fun row(i: Float, p: Float): FloatArray {
                val k = p - i // channel span in 0..1 units; the matrix works in 0..255
                return floatArrayOf(k * 0.299f, k * 0.587f, k * 0.114f, 0f, i * 255f)
            }
            val r = row(ink.red, paper.red)
            val g = row(ink.green, paper.green)
            val b = row(ink.blue, paper.blue)
            return ColorMatrix(r + g + b + floatArrayOf(0f, 0f, 0f, 1f, 0f))
        }
    }
}
