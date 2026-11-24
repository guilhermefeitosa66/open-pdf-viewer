package io.github.guilhermefeitosa66.openpdfviewer.viewer

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Shape geometry in normalized page coordinates (0..1, top-left), aware of the page aspect. */
object Shapes {
    enum class Kind { RECTANGLE, ELLIPSE, LINE, ARROW, POLYGON }

    /**
     * An arrow from (x0, y0) to (x1, y1): the shaft plus a two-sided head, as polylines. The head
     * is [headPt] points long and opens 28° each side, computed in page points so it is not
     * squashed on non-square pages.
     */
    fun arrow(x0: Float, y0: Float, x1: Float, y1: Float, pageW: Float, pageH: Float, headPt: Float): List<List<Pair<Float, Float>>> {
        val dx = (x1 - x0) * pageW
        val dy = (y1 - y0) * pageH
        val len = hypot(dx, dy)
        val shaft = listOf(x0 to y0, x1 to y1)
        if (len < 1f) return listOf(shaft)
        val angle = atan2(dy, dx)
        val head = minOf(headPt, len * 0.5f)
        fun wing(a: Double): Pair<Float, Float> {
            val wx = x1 * pageW - head * cos(angle + a).toFloat()
            val wy = y1 * pageH - head * sin(angle + a).toFloat()
            return wx / pageW to wy / pageH
        }
        val spread = Math.toRadians(28.0)
        return listOf(shaft, listOf(wing(spread), x1 to y1, wing(-spread)))
    }

    /**
     * Marks for "fill and sign", centered on (x, y), [sizePt] points tall. Built in page points so
     * they keep their shape on any page size.
     */
    fun checkMark(x: Float, y: Float, sizePt: Float, pageW: Float, pageH: Float): List<List<Pair<Float, Float>>> {
        fun p(dx: Float, dy: Float) = (x + dx * sizePt / pageW) to (y + dy * sizePt / pageH)
        return listOf(listOf(p(-0.45f, 0.0f), p(-0.12f, 0.35f), p(0.5f, -0.45f)))
    }

    fun crossMark(x: Float, y: Float, sizePt: Float, pageW: Float, pageH: Float): List<List<Pair<Float, Float>>> {
        fun p(dx: Float, dy: Float) = (x + dx * sizePt / pageW) to (y + dy * sizePt / pageH)
        return listOf(listOf(p(-0.4f, -0.4f), p(0.4f, 0.4f)), listOf(p(-0.4f, 0.4f), p(0.4f, -0.4f)))
    }

    /** Corners (normalized) of a dot of [sizePt] points centered on (x, y). */
    fun dot(x: Float, y: Float, sizePt: Float, pageW: Float, pageH: Float): FloatArray {
        val rx = sizePt / 2f / pageW
        val ry = sizePt / 2f / pageH
        return floatArrayOf(x - rx, y - ry, x + rx, y + ry)
    }

    /** A closed polygon: the vertices plus the first one again. */
    fun polygon(points: List<Pair<Float, Float>>): List<List<Pair<Float, Float>>> =
        if (points.size < 2) emptyList() else listOf(points + points.first())
}
