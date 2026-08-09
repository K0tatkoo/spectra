package com.n3d.spectra.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import android.widget.RemoteViews
import com.n3d.spectra.MainActivity
import com.n3d.spectra.R
import com.n3d.spectra.dsp.AnalysisFrame
import com.n3d.spectra.paint.Neu
import com.n3d.spectra.paint.Palette
import com.n3d.spectra.paint.VizPainter
import com.n3d.spectra.settings.Settings
import kotlin.math.roundToInt

/**
 * Home-screen widget rendering.
 *
 * Widgets are the slowest surface by a wide margin. The platform's own update
 * period bottoms out at thirty minutes, so every frame here is pushed manually
 * from the service — and each push is a full bitmap across Binder into the
 * launcher's process. A couple of frames a second is the honest ceiling, which
 * is why the default page is the band meters: a level indicator still reads
 * correctly at 2 fps, where a spectrum would just look broken.
 */
object WidgetRenderer {

    private val painter = VizPainter()
    private val ditherPaint = Paint().apply { isDither = true }
    private var scratch: Bitmap? = null
    private var out: Bitmap? = null

    fun push(context: Context, frame: AnalysisFrame?, settings: Settings, dark: Boolean) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val component = ComponentName(context, SpectraWidgetProvider::class.java)
        val ids = try {
            manager.getAppWidgetIds(component)
        } catch (t: Throwable) {
            Log.w(TAG, "cannot enumerate widgets", t)
            return
        }
        if (ids.isEmpty()) return

        val palette = Palette.of(dark)
        painter.palette = palette
        val displayDensity = context.resources.displayMetrics.density

        for (id in ids) {
            val options = manager.getAppWidgetOptions(id)
            val wDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250).coerceAtLeast(120)
            val hDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 70).coerceAtLeast(40)
            val w = (wDp * displayDensity).roundToInt().coerceAtMost(MAX_W)
            val h = (hDp * displayDensity).roundToInt().coerceAtMost(MAX_H)
            painter.density = w / wDp.toFloat()

            val bitmap = render(w, h, frame, settings, palette)
            val views = RemoteViews(context.packageName, R.layout.widget_spectra)
            views.setImageViewBitmap(R.id.widget_image, bitmap)
            views.setOnClickPendingIntent(R.id.widget_root, openIntent(context))
            try {
                manager.updateAppWidget(id, views)
            } catch (t: Throwable) {
                Log.w(TAG, "widget update rejected", t)
            }
        }
    }

    private fun openIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun render(
        w: Int,
        h: Int,
        frame: AnalysisFrame?,
        settings: Settings,
        palette: Palette,
    ): Bitmap {
        val cachedScratch = scratch
        val cachedOut = out
        val s: Bitmap
        val o: Bitmap
        if (cachedScratch == null || cachedOut == null ||
            cachedScratch.width != w || cachedScratch.height != h
        ) {
            cachedScratch?.recycle()
            cachedOut?.recycle()
            s = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            o = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            scratch = s
            out = o
        } else {
            s = cachedScratch
            o = cachedOut
        }

        val canvas = Canvas(s)
        val depth = 4f * painter.density
        canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
        val card = RectF(depth * 2f, depth * 2f, w - depth * 2f, h - depth * 2f)
        Neu.raised(canvas, card, 18f * painter.density, palette, depth)
        val well = RectF(card)
        well.inset(6f * painter.density, 6f * painter.density)
        Neu.inset(canvas, well, 12f * painter.density, palette, 2f * painter.density)
        val content = RectF(well)
        content.inset(5f * painter.density, 4f * painter.density)
        painter.draw(canvas, content, settings.widgetPage, frame, settings, compact = true)

        // 565 has no alpha, so the rounded corners land on the widget's own
        // background colour. Filling with the page colour first keeps the card
        // reading as a card instead of gaining black corners.
        val target = Canvas(o)
        target.drawColor(palette.bg)
        target.drawBitmap(s, 0f, 0f, ditherPaint)
        return o
    }

    private const val TAG = "SpectraWidget"
    private const val MAX_W = 512
    private const val MAX_H = 256
}
