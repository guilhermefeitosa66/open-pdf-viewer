package io.github.guilhermefeitosa66.openpdfviewer.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** The few stroke icons that material-icons-core lacks, drawn like the design canvas. */
object AppIcons {
    private fun stroke(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    private fun ImageVector.Builder.line(pathData: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = pathData,
        )
    }

    val Pages: ImageVector by lazy {
        stroke("Pages") {
            for ((x, y) in listOf(4f to 4f, 13f to 4f, 4f to 13f, 13f to 13f)) {
                line {
                    moveTo(x + 1, y); lineTo(x + 6, y); quadTo(x + 7, y, x + 7, y + 1)
                    lineTo(x + 7, y + 6); quadTo(x + 7, y + 7, x + 6, y + 7)
                    lineTo(x + 1, y + 7); quadTo(x, y + 7, x, y + 6)
                    lineTo(x, y + 1); quadTo(x, y, x + 1, y); close()
                }
            }
        }
    }

    val Folder: ImageVector by lazy {
        stroke("Folder") {
            line {
                moveTo(3f, 7f); quadTo(3f, 5f, 5f, 5f); lineTo(9f, 5f); lineTo(11f, 7f)
                lineTo(19f, 7f); quadTo(21f, 7f, 21f, 9f); lineTo(21f, 17f)
                quadTo(21f, 19f, 19f, 19f); lineTo(5f, 19f); quadTo(3f, 19f, 3f, 17f); close()
            }
        }
    }

    val Pause: ImageVector by lazy {
        stroke("Pause") {
            line { moveTo(9f, 6f); lineTo(9f, 18f) }
            line { moveTo(15f, 6f); lineTo(15f, 18f) }
        }
    }

    val Copy: ImageVector by lazy {
        stroke("Copy") {
            line {
                moveTo(9f, 9f); lineTo(19f, 9f); lineTo(19f, 19f); lineTo(9f, 19f); close()
            }
            line {
                moveTo(5f, 15f); lineTo(5f, 5f); lineTo(15f, 5f)
            }
        }
    }
}
