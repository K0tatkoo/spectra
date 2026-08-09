package com.n3d.spectra.ui.neu

import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.n3d.spectra.paint.Neu
import com.n3d.spectra.ui.theme.LocalPalette
import com.n3d.spectra.ui.theme.Neumorph

/**
 * Draws a neumorphic surface behind a composable.
 *
 * The shadows are rendered into an [ImageBitmap] once per size (or palette)
 * change and then blitted, because a [android.graphics.BlurMaskFilter] costs far
 * too much to run per frame — and the chrome never changes between frames
 * anyway. The bitmap is padded so the outer shadows are not clipped, then drawn
 * at a negative offset; drawing outside the layout bounds is exactly what CSS
 * does with `box-shadow`.
 */
@Composable
fun Modifier.neuRaised(
    radius: Dp = Neumorph.RadiusMd,
    depth: Dp = Neumorph.DepthMd,
): Modifier = neuSurface(radius, depth, inset = false)

@Composable
fun Modifier.neuInset(
    radius: Dp = Neumorph.RadiusMd,
    depth: Dp = Neumorph.DepthSm,
): Modifier = neuSurface(radius, depth, inset = true)

@Composable
private fun Modifier.neuSurface(radius: Dp, depth: Dp, inset: Boolean): Modifier {
    val palette = LocalPalette.current
    val density = LocalDensity.current
    val radiusPx = with(density) { radius.toPx() }
    val depthPx = with(density) { depth.toPx() }

    return this.drawWithCache {
        val pad = if (inset) 0f else depthPx * 3f
        val w = (size.width + pad * 2f).toInt().coerceAtLeast(1)
        val h = (size.height + pad * 2f).toInt().coerceAtLeast(1)
        val image = ImageBitmap(w, h)
        val canvas = Canvas(image)
        val rect = RectF(pad, pad, pad + size.width, pad + size.height)
        val r = radiusPx.coerceAtMost(minOf(size.width, size.height) / 2f)
        if (inset) {
            Neu.inset(canvas.nativeCanvas, rect, r, palette, depthPx)
        } else {
            Neu.raised(canvas.nativeCanvas, rect, r, palette, depthPx)
        }
        onDrawBehind {
            drawImage(image, topLeft = Offset(-pad, -pad))
        }
    }
}
