package com.n3d.spectra.desktop.paint

import com.n3d.spectra.paint.Palette
import java.awt.AlphaComposite
import java.awt.Graphics2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The neumorphic surfaces, drawn with Java2D.
 *
 * CSS gets this from two box-shadows and Android from a `BlurMaskFilter`; Java2D
 * has neither, so each shadow is a blurred round-rect rendered into an image and
 * blitted. Everything is cached by shape and colour, because a three-pass box
 * blur over a panel-sized image is nowhere near a 60 fps operation and none of
 * these shapes change between frames — only their contents do.
 *
 * The geometry is the website's: depth 4/7/12 px, blur twice the depth, light
 * fixed at the top-left. The same numbers as the Android build's `Neu`.
 */
object Neu2D {

    const val DEPTH_SM = 4f
    const val DEPTH_MD = 7f
    const val DEPTH_LG = 12f

    const val RADIUS_SM = 10f
    const val RADIUS_MD = 16f
    const val RADIUS_LG = 22f

    private data class Key(
        val w: Int, val h: Int, val radius: Int, val blur: Int,
        val dx: Int, val dy: Int, val color: Int, val inner: Boolean,
    )

    private val cache = LinkedHashMap<Key, BufferedImage>(64, 0.75f, true)
    private const val CACHE_LIMIT = 160

    /** A control that stands proud of the surface. */
    fun raised(
        g: Graphics2D,
        b: Box,
        radius: Float,
        palette: Palette,
        depth: Float = DEPTH_MD,
        fill: Boolean = true,
        fillFrom: Int = palette.surfaceHigh,
        fillTo: Int = palette.surfaceLow,
    ) {
        shadow(g, b, radius, depth, depth, depth * 2f, palette.dark, false)
        shadow(g, b, radius, -depth, -depth, depth * 2f, palette.light, false)
        if (fill) {
            g.paint = diagonalGradient(b, fillFrom, fillTo)
            g.fillRound(b, radius)
        }
    }

    /** A control pressed into the surface: a well, a track, a graph area. */
    fun inset(
        g: Graphics2D,
        b: Box,
        radius: Float,
        palette: Palette,
        depth: Float = DEPTH_SM,
        fill: Int = palette.bgDeep,
    ) {
        g.paint = Colors.of(fill)
        g.fillRound(b, radius)
        shadow(g, b, radius, depth, depth, depth * 2f, palette.dark, true)
        shadow(g, b, radius, -depth, -depth, depth * 2f, palette.light, true)
    }

    /** A hairline that reads as a seam rather than a border. */
    fun seam(g: Graphics2D, b: Box, radius: Float, palette: Palette) {
        g.strokeRound(b, radius, Palette.withAlpha(palette.line, 0.7f), 1f)
    }

    private fun shadow(
        g: Graphics2D,
        b: Box,
        radius: Float,
        dx: Float,
        dy: Float,
        blur: Float,
        color: Int,
        inner: Boolean,
    ) {
        val w = b.width().roundToInt()
        val h = b.height().roundToInt()
        if (w < 2 || h < 2) return
        val blurI = blur.roundToInt().coerceIn(1, 40)
        val key = Key(w, h, radius.roundToInt(), blurI, dx.roundToInt(), dy.roundToInt(), color, inner)
        val img = cache.getOrPut(key) {
            if (cache.size >= CACHE_LIMIT) {
                val oldest = cache.keys.iterator()
                oldest.next()
                oldest.remove()
            }
            if (inner) renderInner(key) else renderOuter(key)
        }
        val pad = if (inner) 0 else blurI * 2
        g.drawImage(img, (b.left - pad).roundToInt(), (b.top - pad).roundToInt(), null)
    }

    private fun renderOuter(k: Key): BufferedImage {
        val pad = k.blur * 2
        val img = BufferedImage(k.w + pad * 2, k.h + pad * 2, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics().quality()
        g.paint = Colors.of(k.color or (0xFF shl 24))
        val r = min(k.radius.toFloat(), min(k.w, k.h) / 2f)
        g.fill(
            RoundRectangle2D.Float(
                (pad + k.dx).toFloat(), (pad + k.dy).toFloat(), k.w.toFloat(), k.h.toFloat(), r * 2f, r * 2f,
            ),
        )
        g.dispose()
        blurAlpha(img, k.blur, k.color)
        return img
    }

    /**
     * An inner shadow is the blur of everything *outside* the shape: fill the
     * box, punch the (offset) shape out of it, blur what is left, then clip the
     * result back to the shape.
     */
    private fun renderInner(k: Key): BufferedImage {
        val img = BufferedImage(k.w, k.h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics().quality()
        val r = min(k.radius.toFloat(), min(k.w, k.h) / 2f)
        val shape = RoundRectangle2D.Float(0f, 0f, k.w.toFloat(), k.h.toFloat(), r * 2f, r * 2f)
        g.paint = Colors.of(k.color or (0xFF shl 24))
        g.fill(java.awt.geom.Rectangle2D.Float(-k.blur.toFloat(), -k.blur.toFloat(), (k.w + k.blur * 2).toFloat(), (k.h + k.blur * 2).toFloat()))
        g.composite = AlphaComposite.Clear
        g.fill(
            RoundRectangle2D.Float(
                k.dx.toFloat(), k.dy.toFloat(), k.w.toFloat(), k.h.toFloat(), r * 2f, r * 2f,
            ),
        )
        g.dispose()
        blurAlpha(img, k.blur, k.color)

        val out = BufferedImage(k.w, k.h, BufferedImage.TYPE_INT_ARGB)
        val og = out.createGraphics().quality()
        og.clip(shape)
        og.drawImage(img, 0, 0, null)
        og.dispose()
        return out
    }

    /**
     * Three box blur passes over the alpha channel.
     *
     * Three boxes converge on a Gaussian closely enough that the difference is
     * invisible at these radii, and each pass is a running sum — linear in the
     * pixel count, where a real Gaussian convolution is not.
     */
    private fun blurAlpha(img: BufferedImage, radius: Int, color: Int) {
        if (radius < 1) return
        val w = img.width
        val h = img.height
        val px = (img.raster.dataBuffer as DataBufferInt).data
        // The colour is passed in rather than read back off a pixel. Reading it
        // from the image samples a *cleared* pixel outside the shape, which is
        // transparent black — so every shadow came out black regardless of the
        // palette, and the light-side shadow that is supposed to lift a control
        // towards the light darkened it instead.
        val rgb = color and 0x00FFFFFF
        val alpha = IntArray(w * h)
        for (i in px.indices) alpha[i] = px[i] ushr 24
        val tmp = IntArray(w * h)
        val r = max(1, radius / 2)
        repeat(3) {
            boxBlurH(alpha, tmp, w, h, r)
            boxBlurV(tmp, alpha, w, h, r)
        }
        for (i in px.indices) px[i] = (alpha[i] shl 24) or rgb
    }

    private fun boxBlurH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val span = r * 2 + 1
        for (y in 0 until h) {
            val row = y * w
            var sum = 0
            for (i in -r..r) sum += src[row + i.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                dst[row + x] = sum / span
                sum += src[row + (x + r + 1).coerceIn(0, w - 1)] - src[row + (x - r).coerceIn(0, w - 1)]
            }
        }
    }

    private fun boxBlurV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val span = r * 2 + 1
        for (x in 0 until w) {
            var sum = 0
            for (i in -r..r) sum += src[i.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                dst[y * w + x] = sum / span
                sum += src[(y + r + 1).coerceIn(0, h - 1) * w + x] - src[(y - r).coerceIn(0, h - 1) * w + x]
            }
        }
    }

    /** Drops every cached shadow. Called when the theme flips. */
    fun clearCache() = cache.clear()
}
