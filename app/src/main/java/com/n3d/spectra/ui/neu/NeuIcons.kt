package com.n3d.spectra.ui.neu

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The sites' line icons, so the app draws the same marks the family's pages do:
 * a 24-unit grid, a 2-unit stroke with round ends and joins, no fill. Where a
 * site already has the icon, the path data is copied from its sprite
 * (`i-tune`, `i-refresh`, `i-arrow-left`, …); play, pause and minus are drawn
 * to the same rules.
 *
 * They replace font symbols (⚙ ⏸ ▶ ⟲ ‹ ▾ ✕). Those were drawn by whatever font
 * the phone has, at whatever size and weight it picked, and Android's emoji font
 * claims some of them outright: the pause button came out as an orange emoji
 * tile. The sites dropped every font glyph for the same reason.
 *
 * Drawn in black and tinted where they are used, like an SVG in `currentColor`.
 */
object NeuIcons {
    /** Settings: `i-tune`, the mark NetLab's site puts on its settings button. */
    val Tune = icon("tune", "M4 7h10M18 7h2M4 17h4M12 17h8", circle(16f, 7f, 2f), circle(10f, 17f, 2f))

    /** Centred on its centroid rather than its box, which is what makes a triangle look centred. */
    val Play = icon("play", "M8 5v14l11-7z")
    /** Two outlined bars as tall as the play triangle, 2 units apart. */
    val Pause = icon("pause", "M6 5h4v14H6zM14 5h4v14h-4z")

    /** Reset: the sites' `i-refresh` turned to run anticlockwise, which is how a reset reads. */
    val Reset = icon("reset", "M4 12a8 8 0 1 0 2.6-5.9", "M4 4v5h5")

    val Plus = icon("plus", "M12 5v14M5 12h14")
    val Minus = icon("minus", "M5 12h14")
    val ArrowLeft = icon("arrow-left", "M11 5 4 12l7 7", "M4 12h16")
    val ChevronDown = icon("chevron-down", "m6 9 6 6 6-6")
    val Close = icon("close", "M6 6 18 18M18 6 6 18")
}

private fun icon(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        for (d in paths) {
            addPath(
                pathData = addPathNodes(d),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

/** An SVG `<circle>` as path data: two half-circle arcs. */
private fun circle(cx: Float, cy: Float, r: Float) =
    "M${cx - r} ${cy}a$r $r 0 1 0 ${r * 2} 0a$r $r 0 1 0 ${-r * 2} 0"
