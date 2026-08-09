package com.n3d.spectra.paint

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader

/**
 * Neumorphic surfaces drawn straight onto an [android.graphics.Canvas].
 *
 * CSS gets this for free with two box-shadows; Android has no such primitive, so
 * each shadow is a blurred round-rect drawn behind (raised) or clipped inside
 * (inset) the shape. The geometry is copied from the website's tokens so the two
 * products actually match: depth 4/7/12 px, blur twice the depth, light fixed at
 * the top-left.
 *
 * A [BlurMaskFilter] is not cheap. Nothing here should be called at 60 fps
 * directly — use [Chrome], which renders the static frame once per size change
 * and blits it afterwards.
 */
object Neu {

    const val DEPTH_SM = 4f
    const val DEPTH_MD = 7f
    const val DEPTH_LG = 12f

    const val RADIUS_SM = 10f
    const val RADIUS_MD = 16f
    const val RADIUS_LG = 22f
    const val RADIUS_XL = 30f

    // No shared scratch Paint/Path here on purpose. These entry points are called
    // from the UI thread, from the notification renderer's thread and from the
    // overlay, and a shared mutable Paint in an `object` is exactly the kind of
    // cross-thread corruption that shows up as one wrong frame a minute. They
    // run once per size change (see [Chrome]), so allocating is free.

    /**
     * A control that sits proud of the surface. [depth] is the shadow offset in
     * pixels; blur is always twice it, which is the ratio the site uses.
     */
    fun raised(
        canvas: Canvas,
        rect: RectF,
        radius: Float,
        palette: Palette,
        depth: Float = DEPTH_MD,
        fill: Boolean = true,
    ) {
        drawOuterShadow(canvas, rect, radius, palette.dark, depth, depth, depth * 2f)
        drawOuterShadow(canvas, rect, radius, palette.light, -depth, -depth, depth * 2f)
        if (fill) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.shader = LinearGradient(
                rect.left, rect.top, rect.right, rect.bottom,
                palette.surfaceHigh, palette.surfaceLow, Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }

    /**
     * A well: the same two shadows moved inside. Every graph in the app lives in
     * one of these, which is what makes the visualisation read as inlaid rather
     * than pasted on.
     */
    fun inset(
        canvas: Canvas,
        rect: RectF,
        radius: Float,
        palette: Palette,
        depth: Float = DEPTH_SM,
        fillColor: Int = palette.bgDeep,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = fillColor
        canvas.drawRoundRect(rect, radius, radius, paint)
        drawInnerShadow(canvas, rect, radius, palette.dark, depth, depth, depth * 2f)
        drawInnerShadow(canvas, rect, radius, palette.light, -depth, -depth, depth * 2f)
    }

    /** Fills the page behind everything else. */
    fun background(canvas: Canvas, width: Float, height: Float, palette: Palette) {
        val paint = Paint()
        paint.color = palette.bg
        canvas.drawRect(0f, 0f, width, height, paint)
    }

    private fun drawOuterShadow(
        canvas: Canvas,
        rect: RectF,
        radius: Float,
        color: Int,
        dx: Float,
        dy: Float,
        blur: Float,
    ) {
        if (blur <= 0f) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = color
        paint.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        val r = RectF(rect)
        r.offset(dx, dy)
        canvas.drawRoundRect(r, radius, radius, paint)
    }

    /**
     * The inset trick: clip to the shape, then fill everything *outside* an
     * offset copy of the same shape with a blurred colour. What lands inside the
     * clip is a soft edge hugging one side — exactly what `inset` does in CSS.
     */
    private fun drawInnerShadow(
        canvas: Canvas,
        rect: RectF,
        radius: Float,
        color: Int,
        dx: Float,
        dy: Float,
        blur: Float,
    ) {
        if (blur <= 0f) return
        canvas.save()
        val clip = Path()
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.clipPath(clip)

        val ring = Path()
        ring.fillType = Path.FillType.EVEN_ODD
        val outer = RectF(rect)
        outer.inset(-blur * 4f, -blur * 4f)
        ring.addRect(outer, Path.Direction.CW)
        val inner = RectF(rect)
        inner.offset(dx, dy)
        ring.addRoundRect(inner, radius, radius, Path.Direction.CW)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = color
        paint.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        canvas.drawPath(ring, paint)
        canvas.restore()
    }

}
