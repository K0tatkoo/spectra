package com.n3d.spectra.ui.neu

import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.n3d.spectra.paint.Neu
import com.n3d.spectra.paint.Palette
import com.n3d.spectra.ui.theme.LocalPalette
import com.n3d.spectra.ui.theme.Neumorph
import com.n3d.spectra.ui.theme.toComposeColor
import kotlin.math.ceil

/**
 * Draws a neumorphic surface behind a composable.
 *
 * The shadows come from a [android.graphics.BlurMaskFilter], which costs far
 * too much to run per frame, so they are rendered into a bitmap once and blitted
 * afterwards. The bitmap is padded so the outer shadows are not clipped, then
 * drawn at a negative offset; drawing outside the layout bounds is exactly what
 * CSS does with `box-shadow`.
 *
 * That bitmap is a nine-slice, not a picture of the whole surface. Away from the
 * corners a shadow is the same all along an edge, so the corners are drawn as
 * they are and one row or column of edge is stretched between them. Every
 * surface used to keep a full-size bitmap of its own, and the settings page —
 * twenty-odd cards and well over a hundred tracks, switches and pills — held
 * 157 MB of them on the emulator, more than the GPU's texture cache on a
 * 1080-wide phone. The cache then threw textures out and re-uploaded them on
 * every frame, and the page scrolled at 2 fps. Now all the cards share one
 * small bitmap, all the slider tracks another, and the page holds a few MB.
 *
 * The raised surfaces' diagonal fill depends on the whole size, so it is not
 * part of the bitmap: it is a gradient drawn on top, the same order [Neu.raised]
 * paints in. That sheen is the store's `--surface` and stays; coloured fills
 * are flat (see [neuFilled]).
 */
@Composable
fun Modifier.neuRaised(
    radius: Dp = Neumorph.RadiusMd,
    depth: Dp = Neumorph.DepthMd,
): Modifier = neuSurface(radius, depth, inset = false, fill = null)

@Composable
fun Modifier.neuInset(
    radius: Dp = Neumorph.RadiusMd,
    depth: Dp = Neumorph.DepthSm,
): Modifier = neuSurface(radius, depth, inset = true, fill = null)

/**
 * A filled control, such as a primary button: ONE flat colour where a raised
 * surface has its sheen, never a gradient (the sites' rule since 2026-09-28).
 * Raised, the colour stands proud of the page. [pressed], the inset shadows
 * fall on the colour itself, which is the order CSS paints an inset
 * `box-shadow` over a background in: a pressed primary button on the store is
 * still violet, only pushed in.
 */
@Composable
fun Modifier.neuFilled(
    color: Color,
    pressed: Boolean,
    radius: Dp = Neumorph.RadiusMd,
    depth: Dp = if (pressed) Neumorph.DepthSm else Neumorph.DepthMd,
): Modifier = neuSurface(radius, depth, inset = pressed, fill = color)

@Composable
private fun Modifier.neuSurface(radius: Dp, depth: Dp, inset: Boolean, fill: Color?): Modifier {
    val palette = LocalPalette.current
    val density = LocalDensity.current
    val radiusPx = with(density) { radius.toPx() }
    val depthPx = with(density) { depth.toPx() }

    return this.drawWithCache {
        val w = size.width
        val h = size.height
        if (w < 1f || h < 1f) return@drawWithCache onDrawBehind {}
        val r = radiusPx.coerceAtMost(minOf(w, h) / 2f)
        val corner = CornerRadius(r, r)
        // A well is filled with the deep background; a pressed filled control
        // keeps its own colour, so its inset is the two shadows alone. (Raised
        // templates have no fill either way, so they all share one key.)
        val slices = NeuSlices.of(palette, w, h, r, depthPx, inset, well = !inset || fill == null)
        val sheen = if (inset || fill != null) null else Brush.linearGradient(
            listOf(palette.surfaceHigh.toComposeColor(), palette.surfaceLow.toComposeColor()),
            start = Offset.Zero,
            end = Offset(w, h),
        )
        onDrawBehind {
            if (fill != null && inset) drawRoundRect(fill, cornerRadius = corner)
            slices.draw(this)
            if (sheen != null) drawRoundRect(sheen, cornerRadius = corner)
            if (fill != null && !inset) drawRoundRect(fill, cornerRadius = corner)
        }
    }
}

/**
 * One surface's shadow bitmap and how to lay it out at the surface's size.
 *
 * Along each axis the surface is either short enough to draw whole, or longer
 * than [Template.span] and drawn as start · stretched middle pixel · end.
 */
private class NeuSlices(private val image: ImageBitmap, private val x: List<Piece>, private val y: List<Piece>) {

    /** A run of the bitmap ([src], [srcLen]) drawn over a run of the surface ([dst], [dstLen]). */
    class Piece(val src: Int, val srcLen: Int, val dst: Int, val dstLen: Int)

    fun draw(scope: DrawScope) {
        for (py in y) for (px in x) {
            scope.drawImage(
                image,
                srcOffset = IntOffset(px.src, py.src),
                srcSize = IntSize(px.srcLen, py.srcLen),
                dstOffset = IntOffset(px.dst, py.dst),
                dstSize = IntSize(px.dstLen, py.dstLen),
            )
        }
    }

    companion object {
        fun of(palette: Palette, w: Float, h: Float, radius: Float, depth: Float, inset: Boolean, well: Boolean): NeuSlices {
            val pad = if (inset) 0 else ceil(depth * 3f).toInt()
            // How far in from an edge the corners still show. The shadow is
            // offset by the depth and blurred to about three sigma past it, on
            // top of the corner's own radius; a few pixels more keep bilinear
            // sampling next to the stretched pixel off the curve.
            val blur = depth * 2f
            val sigma = 0.57735f * blur + 0.5f
            val corner = ceil(radius + depth + sigma * 3f).toInt() + 3
            val span = corner * 2 + 1
            val tw = if (w > span) span.toFloat() else w
            val th = if (h > span) span.toFloat() else h
            val image = Template.get(Template.Key(palette, tw, th, radius, depth, pad, inset, well))
            return NeuSlices(image, pieces(w, tw, pad, corner), pieces(h, th, pad, corner))
        }

        private fun pieces(length: Float, template: Float, pad: Int, corner: Int): List<Piece> {
            val len = length.toInt()
            if (template == length) {
                val whole = len + pad * 2
                return listOf(Piece(0, whole, -pad, whole))
            }
            val edge = pad + corner
            return listOf(
                Piece(0, edge, -pad, edge),
                Piece(edge, 1, corner, len - corner * 2),
                Piece(edge + 1, edge, len - corner, edge),
            )
        }
    }
}

/**
 * The rendered shadow bitmaps, shared by every surface of the same shape: all
 * the settings cards draw from one, all the slider tracks from another. Bounded
 * so surfaces that come and go at many sizes cannot grow it without end; a
 * surface on screen keeps its own reference, so eviction never pulls a bitmap
 * out from under it.
 */
private object Template {

    data class Key(
        val palette: Palette,
        val width: Float,
        val height: Float,
        val radius: Float,
        val depth: Float,
        val pad: Int,
        val inset: Boolean,
        /** An inset filled with the deep background, as every well is; false for shadows alone. */
        val well: Boolean,
    )

    private const val BUDGET_BYTES = 8L shl 20

    private val cache = LinkedHashMap<Key, ImageBitmap>(64, 0.75f, true)
    private var bytes = 0L

    fun get(key: Key): ImageBitmap = synchronized(this) {
        cache[key] ?: render(key).also { image ->
            cache[key] = image
            bytes += image.width * image.height * 4L
            val oldest = cache.entries.iterator()
            while (bytes > BUDGET_BYTES && cache.size > 1) {
                val evicted = oldest.next()
                bytes -= evicted.value.width * evicted.value.height * 4L
                oldest.remove()
            }
        }
    }

    private fun render(key: Key): ImageBitmap {
        val w = ceil(key.width + key.pad * 2f).toInt().coerceAtLeast(1)
        val h = ceil(key.height + key.pad * 2f).toInt().coerceAtLeast(1)
        val image = ImageBitmap(w, h)
        val canvas = Canvas(image).nativeCanvas
        val pad = key.pad.toFloat()
        val rect = RectF(pad, pad, pad + key.width, pad + key.height)
        if (key.inset) {
            Neu.inset(canvas, rect, key.radius, key.palette, key.depth, fillColor = if (key.well) key.palette.bgDeep else 0)
        } else {
            Neu.raised(canvas, rect, key.radius, key.palette, key.depth, fill = false)
        }
        return image
    }
}
