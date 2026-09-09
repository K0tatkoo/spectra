package com.n3d.spectra.desktop.dev

import java.awt.BasicStroke
import java.awt.Color
import java.awt.LinearGradientPaint
import java.awt.MultipleGradientPaint
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Arc2D
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Renders the Windows icon from the Android launcher icon.
 *
 * The point is that it is *the same icon*, not a redrawing of it: it reads
 * `ic_launcher_background.xml` and `ic_launcher_foreground.xml` out of the
 * Android module and rasterises them, so a change to the app's mark reaches the
 * Windows build by rebuilding rather than by someone remembering.
 *
 * VectorDrawable `pathData` is SVG path syntax, so this is a faithful conversion.
 * Only the commands the icon actually uses are implemented, and anything else
 * throws rather than being skipped — an icon that silently loses a shape is worse
 * than a build that stops.
 */
private const val VIEWPORT = 108.0

/** The middle 72 of the 108 canvas: the rest is adaptive-icon parallax bleed. */
private const val SAFE = 72.0
private const val INSET = (VIEWPORT - SAFE) / 2.0

fun main(args: Array<String>) {
    val res = File(args.getOrElse(0) { "app/src/main/res/drawable" })
    val outDir = File(args.getOrElse(1) { "build/icon" })
    // The .ico goes somewhere else on purpose: the PNGs are read at runtime for
    // the window icon and belong in the jar, while the .ico is only ever an input
    // to jpackage and would be 110 KB of dead weight inside it.
    val icoDir = File(args.getOrElse(2) { "build/icon" })
    outDir.mkdirs()
    icoDir.mkdirs()

    val layers = listOf("ic_launcher_background.xml", "ic_launcher_foreground.xml")
        .map { File(res, it) }
        .onEach { require(it.exists()) { "missing ${it.absolutePath}" } }
        .flatMap { parseLayer(it.readText()) }

    val sizes = intArrayOf(16, 24, 32, 48, 64, 128, 256)
    val images = sizes.map { size ->
        val image = render(layers, size)
        val file = File(outDir, "spectra-$size.png")
        ImageIO.write(image, "png", file)
        println("wrote ${file.name}")
        image
    }
    val ico = File(icoDir, "spectra.ico")
    ico.writeBytes(buildIco(images))
    println("wrote ${ico.name} (${ico.length()} bytes, ${images.size} sizes)")
    println("layers: ${layers.size} shapes")
}

/**
 * Packs the rendered sizes into a Windows `.ico`.
 *
 * Written here rather than shelled out to an image tool so the Windows build has
 * no dependency beyond a JDK and Wine. Everything below 256 px is stored as a
 * 32-bit DIB, which every version of the Windows shell reads; 256 is stored as a
 * PNG, which is what Vista introduced the format extension for and what keeps the
 * file from being a megabyte.
 */
private fun buildIco(images: List<BufferedImage>): ByteArray {
    val payloads = images.map { if (it.width >= 256) pngBytes(it) else dibBytes(it) }
    val header = java.io.ByteArrayOutputStream()
    fun le16(v: Int) = header.write(byteArrayOf((v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte()))
    fun le32(v: Int) = header.write(
        byteArrayOf(
            (v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte(),
            (v shr 16 and 0xFF).toByte(), (v shr 24 and 0xFF).toByte(),
        ),
    )
    le16(0); le16(1); le16(images.size)
    var offset = 6 + images.size * 16
    for ((i, image) in images.withIndex()) {
        // 256 is written as 0: the field is one byte.
        header.write(if (image.width >= 256) 0 else image.width)
        header.write(if (image.height >= 256) 0 else image.height)
        header.write(0)
        header.write(0)
        le16(1)
        le16(32)
        le32(payloads[i].size)
        le32(offset)
        offset += payloads[i].size
    }
    val out = java.io.ByteArrayOutputStream()
    out.write(header.toByteArray())
    payloads.forEach { out.write(it) }
    return out.toByteArray()
}

private fun pngBytes(image: BufferedImage): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    ImageIO.write(image, "png", out)
    return out.toByteArray()
}

/** A 32-bit bottom-up DIB with the (unused but mandatory) 1-bit AND mask after it. */
private fun dibBytes(image: BufferedImage): ByteArray {
    val w = image.width
    val h = image.height
    val out = java.io.ByteArrayOutputStream()
    fun le16(v: Int) = out.write(byteArrayOf((v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte()))
    fun le32(v: Int) = out.write(
        byteArrayOf(
            (v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte(),
            (v shr 16 and 0xFF).toByte(), (v shr 24 and 0xFF).toByte(),
        ),
    )
    le32(40)
    le32(w)
    // Doubled: the header describes the colour bitmap and the mask as one image.
    le32(h * 2)
    le16(1)
    le16(32)
    le32(0)
    le32(w * h * 4)
    le32(2835); le32(2835); le32(0); le32(0)
    for (y in h - 1 downTo 0) {
        for (x in 0 until w) {
            val argb = image.getRGB(x, y)
            out.write(argb and 0xFF)
            out.write(argb shr 8 and 0xFF)
            out.write(argb shr 16 and 0xFF)
            out.write(argb ushr 24)
        }
    }
    val maskStride = ((w + 31) / 32) * 4
    repeat(h * maskStride) { out.write(0) }
    return out.toByteArray()
}

private class Shape2D(val path: Path2D.Double, val gradient: Gradient)

private class Gradient(
    val x0: Double, val y0: Double, val x1: Double, val y1: Double,
    val stops: List<Pair<Float, Color>>,
)

private fun render(shapes: List<Shape2D>, size: Int): BufferedImage {
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

    // Windows does not mask an icon, so the rounding a launcher would apply has
    // to be baked in. 22 % matches what the store page uses for these marks.
    val radius = size * 0.22
    g.clip(RoundRectangle2D.Double(0.0, 0.0, size.toDouble(), size.toDouble(), radius * 2, radius * 2))

    val scale = size / SAFE
    val transform = AffineTransform().apply {
        scale(scale, scale)
        translate(-INSET, -INSET)
    }
    for (shape in shapes) {
        val p = Path2D.Double(shape.path)
        p.transform(transform)
        val a = Point2D.Double(shape.gradient.x0, shape.gradient.y0).also { transform.transform(it, it) }
        val b = Point2D.Double(shape.gradient.x1, shape.gradient.y1).also { transform.transform(it, it) }
        g.paint = if (a.distance(b) < 0.01) {
            shape.gradient.stops.first().second
        } else {
            LinearGradientPaint(
                a, b,
                shape.gradient.stops.map { it.first }.toFloatArray(),
                shape.gradient.stops.map { it.second }.toTypedArray(),
                MultipleGradientPaint.CycleMethod.NO_CYCLE,
            )
        }
        g.fill(p)
    }
    // A hairline of the top-left highlight, the same trick the neumorphic
    // surfaces use, so the mark does not read as a flat sticker at 256 px.
    if (size >= 48) {
        g.stroke = BasicStroke((size / 128f).coerceAtLeast(1f))
        g.paint = Color(255, 255, 255, 38)
        g.draw(RoundRectangle2D.Double(0.5, 0.5, size - 1.0, size - 1.0, radius * 2, radius * 2))
    }
    g.dispose()
    return image
}

// ---- VectorDrawable ---------------------------------------------------------

private val PATH_RE = Regex("""android:pathData="([^"]+)"""")
private val GRADIENT_RE = Regex(
    """<gradient[^>]*android:startX="([-\d.]+)"[^>]*android:startY="([-\d.]+)"[^>]*""" +
        """android:endX="([-\d.]+)"[^>]*android:endY="([-\d.]+)"[^>]*>(.*?)</gradient>""",
    RegexOption.DOT_MATCHES_ALL,
)
private val ITEM_RE = Regex("""android:offset="([-\d.]+)"\s+android:color="#([0-9A-Fa-f]{8})"""")

private fun parseLayer(xml: String): List<Shape2D> {
    // Split on <path so each path keeps the gradient nested inside it.
    val blocks = xml.split("<path").drop(1)
    return blocks.mapNotNull { block ->
        val data = PATH_RE.find(block)?.groupValues?.get(1) ?: return@mapNotNull null
        val g = GRADIENT_RE.find(block)
            ?: error("path has no linear gradient; solid fills are not implemented: ${data.take(40)}")
        val stops = ITEM_RE.findAll(g.groupValues[5]).map { m ->
            val argb = m.groupValues[2].toLong(16).toInt()
            m.groupValues[1].toFloat() to Color(argb, true)
        }.toList()
        require(stops.size >= 2) { "gradient needs at least two stops" }
        Shape2D(
            parsePath(data),
            Gradient(
                g.groupValues[1].toDouble(), g.groupValues[2].toDouble(),
                g.groupValues[3].toDouble(), g.groupValues[4].toDouble(),
                // LinearGradientPaint rejects equal or unsorted fractions.
                stops.sortedBy { it.first }.mapIndexed { i, s ->
                    (s.first + i * 1e-4f).coerceIn(0f, 1f) to s.second
                },
            ),
        )
    }
}

private val TOKEN_RE = Regex("""[MmLlHhVvAaCcZz]|[-+]?[0-9]*\.?[0-9]+(?:[eE][-+]?[0-9]+)?""")

private fun parsePath(d: String): Path2D.Double {
    val path = Path2D.Double(Path2D.WIND_NON_ZERO)
    val tokens = TOKEN_RE.findAll(d).map { it.value }.toMutableList()
    var i = 0
    var x = 0.0
    var y = 0.0
    var startX = 0.0
    var startY = 0.0
    var command = ' '

    fun num(): Double = tokens[i++].toDouble()

    while (i < tokens.size) {
        val token = tokens[i]
        if (token.length == 1 && token[0].isLetter()) {
            command = token[0]
            i++
        }
        when (command) {
            'M', 'm' -> {
                val nx = num(); val ny = num()
                x = if (command == 'm') x + nx else nx
                y = if (command == 'm') y + ny else ny
                path.moveTo(x, y)
                startX = x; startY = y
                // A second coordinate pair after a moveto is an implicit lineto.
                command = if (command == 'm') 'l' else 'L'
            }
            'L', 'l' -> {
                val nx = num(); val ny = num()
                x = if (command == 'l') x + nx else nx
                y = if (command == 'l') y + ny else ny
                path.lineTo(x, y)
            }
            'H', 'h' -> {
                val nx = num()
                x = if (command == 'h') x + nx else nx
                path.lineTo(x, y)
            }
            'V', 'v' -> {
                val ny = num()
                y = if (command == 'v') y + ny else ny
                path.lineTo(x, y)
            }
            'C', 'c' -> {
                val x1 = num(); val y1 = num(); val x2 = num(); val y2 = num(); val nx = num(); val ny = num()
                val rel = command == 'c'
                val cx1 = if (rel) x + x1 else x1
                val cy1 = if (rel) y + y1 else y1
                val cx2 = if (rel) x + x2 else x2
                val cy2 = if (rel) y + y2 else y2
                x = if (rel) x + nx else nx
                y = if (rel) y + ny else ny
                path.curveTo(cx1, cy1, cx2, cy2, x, y)
            }
            'A', 'a' -> {
                val rx = num(); val ry = num(); val rotation = num()
                val largeArc = num() != 0.0
                val sweep = num() != 0.0
                val nx = num(); val ny = num()
                val ex = if (command == 'a') x + nx else nx
                val ey = if (command == 'a') y + ny else ny
                arcTo(path, x, y, ex, ey, rx, ry, rotation, largeArc, sweep)
                x = ex; y = ey
            }
            'Z', 'z' -> {
                path.closePath()
                x = startX; y = startY
                i++
            }
            else -> error("unsupported path command '$command' in: $d")
        }
    }
    return path
}

/**
 * SVG's endpoint arc, appended as Java2D curves.
 *
 * Only the circular case is implemented — `rx == ry` — because that is all a
 * rounded corner ever is, and the general elliptical case would be untested code.
 */
private fun arcTo(
    path: Path2D.Double,
    x0: Double, y0: Double, x1: Double, y1: Double,
    rx: Double, ry: Double, rotation: Double,
    largeArc: Boolean, sweep: Boolean,
) {
    require(abs(rx - ry) < 1e-9 && abs(rotation) < 1e-9) {
        "only circular, unrotated arcs are implemented (rx=$rx ry=$ry rot=$rotation)"
    }
    val d = hypot(x1 - x0, y1 - y0)
    if (d < 1e-9) return
    var r = rx
    if (r < d / 2) r = d / 2 // SVG says scale the radius up until a solution exists.

    val mx = (x0 + x1) / 2
    val my = (y0 + y1) / 2
    val h = sqrt((r * r - d * d / 4).coerceAtLeast(0.0))
    // Two candidate centres, one either side of the chord; large-arc and sweep
    // pick between them. Getting this sign backwards still joins the endpoints —
    // the arc just bulges the wrong way — so a rounded corner comes out as a
    // circular blob rather than as a visibly broken path.
    val sgn = if (largeArc == sweep) 1.0 else -1.0
    val cx = mx + sgn * h * (y1 - y0) / d
    val cy = my - sgn * h * (x1 - x0) / d

    fun angleOf(px: Double, py: Double): Double {
        val a = acos(((px - cx) / r).coerceIn(-1.0, 1.0))
        return if (py - cy < 0) -a else a
    }

    var start = angleOf(x0, y0)
    var end = angleOf(x1, y1)
    var extent = end - start
    if (sweep && extent < 0) extent += 2 * Math.PI
    if (!sweep && extent > 0) extent -= 2 * Math.PI

    // Arc2D works in degrees with y up; the path is y down.
    val arc = Arc2D.Double(
        cx - r, cy - r, r * 2, r * 2,
        Math.toDegrees(-start), Math.toDegrees(-extent), Arc2D.OPEN,
    )
    path.append(arc, true)
    // Guard against a drifting endpoint from the degree round-trip.
    val cur = path.currentPoint
    if (cur != null && hypot(cur.x - x1, cur.y - y1) > 1e-6) path.lineTo(x1, y1)
}
