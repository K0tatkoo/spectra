package com.n3d.spectra.desktop.paint

import com.n3d.spectra.paint.Palette
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.LinearGradientPaint
import java.awt.MultipleGradientPaint
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import kotlin.math.max
import kotlin.math.min

/**
 * A mutable rectangle with the same accessors Android's `RectF` has.
 *
 * The graphs are a port of the Android painter and the port is worth keeping
 * readable line by line, so the geometry type keeps `left/top/right/bottom` and
 * `centerX()` rather than being rewritten around `java.awt`'s x/y/w/h.
 */
class Box(
    @JvmField var left: Float,
    @JvmField var top: Float,
    @JvmField var right: Float,
    @JvmField var bottom: Float,
) {
    fun width() = right - left
    fun height() = bottom - top
    fun centerX() = (left + right) * 0.5f
    fun centerY() = (top + bottom) * 0.5f
    fun copy() = Box(left, top, right, bottom)
    fun inset(dx: Float, dy: Float) = Box(left + dx, top + dy, right - dx, bottom - dy)
    fun contains(x: Int, y: Int) = x >= left && x < right && y >= top && y < bottom
    override fun toString() = "Box($left,$top,$right,$bottom)"
}

/** ARGB int to an AWT colour, with a small cache — every frame asks for the same dozen. */
object Colors {
    private val cache = HashMap<Int, Color>(64)

    fun of(argb: Int): Color = cache.getOrPut(argb) {
        Color(
            Palette.red(argb), Palette.green(argb), Palette.blue(argb), Palette.alpha(argb),
        )
    }
}

/** The typefaces, resolved once against whatever the machine actually has. */
object Fonts {
    private val available: Set<String> by lazy {
        GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
    }

    private fun pick(vararg names: String, fallback: String): String =
        names.firstOrNull { it in available } ?: fallback

    val sans: String by lazy {
        pick("Segoe UI", "Inter", "Helvetica Neue", "Roboto", "DejaVu Sans", fallback = Font.SANS_SERIF)
    }
    val mono: String by lazy {
        pick("Consolas", "SF Mono", "Menlo", "DejaVu Sans Mono", "Courier New", fallback = Font.MONOSPACED)
    }

    private val cache = HashMap<Long, Font>(64)

    fun sans(size: Float, bold: Boolean = false): Font = get(sans, size, bold)
    fun mono(size: Float, bold: Boolean = true): Font = get(mono, size, bold)

    private fun get(family: String, size: Float, bold: Boolean): Font {
        val key = (family.hashCode().toLong() shl 20) or
            ((size * 16f).toInt().toLong() shl 2) or (if (bold) 1L else 0L)
        return cache.getOrPut(key) {
            Font(family, if (bold) Font.BOLD else Font.PLAIN, 12).deriveFont(size)
        }
    }
}

enum class Align { LEFT, CENTER, RIGHT }

// ---- Graphics2D conveniences ------------------------------------------------

fun Graphics2D.quality(): Graphics2D {
    setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    return this
}

/**
 * Sets the paint from a packed ARGB int.
 *
 * Deliberately not called `setColor`: `Graphics` already has one taking an AWT
 * `Color`, and in a file that has not imported this extension Kotlin quietly
 * resolves to that member and fails with a type error thirty lines from the
 * cause.
 */
fun Graphics2D.useColor(argb: Int) {
    paint = Colors.of(argb)
}

fun Graphics2D.fillBox(b: Box, argb: Int) {
    useColor(argb)
    fill(Rectangle2D.Float(b.left, b.top, b.width(), b.height()))
}

fun Graphics2D.fillRound(b: Box, radius: Float) {
    val r = min(radius, min(b.width(), b.height()) / 2f)
    fill(RoundRectangle2D.Float(b.left, b.top, b.width(), b.height(), r * 2f, r * 2f))
}

fun Graphics2D.fillRound(b: Box, radius: Float, argb: Int) {
    useColor(argb)
    fillRound(b, radius)
}

fun Graphics2D.strokeRound(b: Box, radius: Float, argb: Int, width: Float) {
    useColor(argb)
    stroke = java.awt.BasicStroke(width)
    val r = min(radius, min(b.width(), b.height()) / 2f)
    draw(RoundRectangle2D.Float(b.left, b.top, b.width(), b.height(), r * 2f, r * 2f))
}

fun Graphics2D.line(x0: Float, y0: Float, x1: Float, y1: Float, argb: Int, width: Float) {
    useColor(argb)
    stroke = java.awt.BasicStroke(width)
    draw(Line2D.Float(x0, y0, x1, y1))
}

fun Graphics2D.circle(cx: Float, cy: Float, r: Float, argb: Int) {
    useColor(argb)
    fill(Ellipse2D.Float(cx - r, cy - r, r * 2f, r * 2f))
}

fun Graphics2D.strokeCircle(cx: Float, cy: Float, r: Float, argb: Int, width: Float) {
    useColor(argb)
    stroke = java.awt.BasicStroke(width)
    draw(Ellipse2D.Float(cx - r, cy - r, r * 2f, r * 2f))
}

/**
 * A vertical gradient between two points.
 *
 * `LinearGradientPaint` throws if the two points coincide or the fractions are
 * not strictly increasing, and both happen naturally when a panel is collapsed to
 * nothing, so the degenerate case is folded down to a flat colour here rather
 * than guarded at forty call sites.
 */
fun vGradient(top: Float, bottom: Float, colors: IntArray, stops: FloatArray): java.awt.Paint {
    if (bottom - top < 0.5f || colors.size < 2) return Colors.of(colors[0])
    return LinearGradientPaint(
        java.awt.geom.Point2D.Float(0f, top),
        java.awt.geom.Point2D.Float(0f, bottom),
        stops,
        Array(colors.size) { Colors.of(colors[it]) },
        MultipleGradientPaint.CycleMethod.NO_CYCLE,
    )
}

fun vGradient(top: Float, bottom: Float, from: Int, to: Int): java.awt.Paint {
    if (bottom - top < 0.5f) return Colors.of(from)
    return GradientPaint(0f, top, Colors.of(from), 0f, bottom, Colors.of(to))
}

fun hGradient(left: Float, right: Float, colors: IntArray, stops: FloatArray): java.awt.Paint {
    if (right - left < 0.5f || colors.size < 2) return Colors.of(colors[0])
    return LinearGradientPaint(
        java.awt.geom.Point2D.Float(left, 0f),
        java.awt.geom.Point2D.Float(right, 0f),
        stops,
        Array(colors.size) { Colors.of(colors[it]) },
        MultipleGradientPaint.CycleMethod.NO_CYCLE,
    )
}

fun diagonalGradient(b: Box, from: Int, to: Int): java.awt.Paint {
    if (b.width() < 0.5f && b.height() < 0.5f) return Colors.of(from)
    return GradientPaint(b.left, b.top, Colors.of(from), b.right, b.bottom, Colors.of(to))
}

/** Draws [text] with the baseline at [y], aligned about [x]. */
fun Graphics2D.text(text: String, x: Float, y: Float, font: Font, argb: Int, align: Align = Align.LEFT) {
    this.font = font
    useColor(argb)
    val w = if (align == Align.LEFT) 0f else fontMetrics.stringWidth(text).toFloat()
    val dx = when (align) {
        Align.LEFT -> 0f
        Align.CENTER -> -w / 2f
        Align.RIGHT -> -w
    }
    drawString(text, x + dx, y)
}

fun Graphics2D.textWidth(text: String, font: Font): Float {
    this.font = font
    return fontMetrics.stringWidth(text).toFloat()
}

/** Truncates with an ellipsis so a long device name cannot push a layout apart. */
fun Graphics2D.ellipsize(text: String, font: Font, maxWidth: Float): String {
    this.font = font
    if (fontMetrics.stringWidth(text) <= maxWidth) return text
    var n = text.length
    while (n > 1 && fontMetrics.stringWidth(text.take(n) + "…") > maxWidth) n--
    return text.take(max(1, n)) + "…"
}
