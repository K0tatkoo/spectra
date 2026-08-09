package com.n3d.spectra.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.n3d.spectra.MainActivity
import com.n3d.spectra.R
import com.n3d.spectra.SpectraApp
import com.n3d.spectra.dsp.AnalysisFrame
import com.n3d.spectra.paint.Neu
import com.n3d.spectra.paint.Palette
import com.n3d.spectra.paint.VizPainter
import com.n3d.spectra.settings.Settings

/**
 * Renders the lock-screen / shade notification.
 *
 * The graph is a bitmap because RemoteViews cannot host a custom View — there is
 * no way to run drawing code inside the system's notification process. So the
 * app draws into a bitmap here and ships the pixels across.
 *
 * Two consequences shape everything below.
 *
 * First, the frame rate. Notification updates are rate-limited by the platform;
 * pushing faster than roughly 10–12 per second gets them dropped rather than
 * queued, and it also means megabytes a second through Binder. The settings cap
 * exists for that reason and is not conservatism.
 *
 * Second, the pixel format. RGB_565 halves the bytes crossing the boundary. On
 * a neumorphic surface — whose whole vocabulary is 5 % luminance steps — that
 * would band visibly, so dithering is on. It is a real trade, made knowingly:
 * half the bandwidth for a faint stipple no one will see at 52 dp tall.
 */
class NotificationRenderer(private val context: Context) {

    private val painterCollapsed = VizPainter()
    private val painterExpanded = VizPainter()
    private val collapsed = RenderTarget(compact = true)
    private val expanded = RenderTarget(compact = false)
    private val ditherPaint = Paint().apply { isDither = true }

    /** One render target: full-quality scratch, plus the 565 copy that ships. */
    private class RenderTarget(val compact: Boolean) {
        var scratch: Bitmap? = null
        var out: Bitmap? = null

        fun ensure(w: Int, h: Int): Pair<Bitmap, Bitmap> {
            val s = scratch
            if (s == null || s.width != w || s.height != h) {
                scratch?.recycle()
                out?.recycle()
                scratch = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            }
            return scratch!! to out!!
        }

        fun release() {
            scratch?.recycle(); scratch = null
            out?.recycle(); out = null
        }
    }

    /**
     * Synchronised because the service builds notifications from two threads —
     * the render loop, and whichever thread calls startForeground — and the
     * bitmaps below are reused rather than reallocated.
     */
    @Synchronized
    fun build(
        frame: AnalysisFrame?,
        settings: Settings,
        dark: Boolean,
        paused: Boolean,
        statusLine: String,
    ): Notification {
        val palette = Palette.of(dark)
        painterCollapsed.palette = palette
        painterExpanded.palette = palette

        val width = renderWidth()
        val scale = width / REFERENCE_DP
        painterCollapsed.density = scale
        painterExpanded.density = scale

        val collapsedViews = RemoteViews(context.packageName, R.layout.notification_collapsed)
        collapsedViews.setImageViewBitmap(
            R.id.notif_image,
            render(collapsed, width, (COLLAPSED_DP * scale).toInt().coerceAtLeast(24), painterCollapsed, frame, settings, palette),
        )

        val expandedViews = RemoteViews(context.packageName, R.layout.notification_expanded)
        expandedViews.setImageViewBitmap(
            R.id.notif_image,
            render(expanded, width, (EXPANDED_DP * scale).toInt().coerceAtLeast(64), painterExpanded, frame, settings, palette),
        )
        expandedViews.setImageViewResource(
            R.id.notif_btn_toggle,
            if (paused) R.drawable.ic_play else R.drawable.ic_pause,
        )
        expandedViews.setOnClickPendingIntent(R.id.notif_btn_toggle, action(NotificationActionReceiver.ACTION_TOGGLE, 1))
        expandedViews.setOnClickPendingIntent(R.id.notif_btn_cycle, action(NotificationActionReceiver.ACTION_CYCLE, 2))
        expandedViews.setOnClickPendingIntent(R.id.notif_btn_stop, action(NotificationActionReceiver.ACTION_STOP, 3))

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(context, SpectraApp.CHANNEL_ANALYZER)
            .setSmallIcon(R.drawable.ic_stat_spectra)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(statusLine)
            .setCustomContentView(collapsedViews)
            .setCustomBigContentView(expandedViews)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            // Without this the lock screen shows "contents hidden" and the whole
            // feature disappears behind the user's privacy setting.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun action(name: String, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, NotificationActionReceiver::class.java).setAction(name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * Draws at full colour depth, then copies down to 565 with dithering. The
     * extra blit is far cheaper than the banding that drawing 5 %-luminance
     * neumorphic shadows straight into 565 would produce.
     */
    private fun render(
        surface: RenderTarget,
        w: Int,
        h: Int,
        painter: VizPainter,
        frame: AnalysisFrame?,
        settings: Settings,
        palette: Palette,
    ): Bitmap {
        val (scratch, out) = surface.ensure(w, h)
        val canvas = Canvas(scratch)
        Neu.background(canvas, w.toFloat(), h.toFloat(), palette)

        val inset = painter.density * 5f
        val well = RectF(inset, inset, w - inset, h - inset)
        Neu.inset(canvas, well, painter.density * 12f, palette, painter.density * 2.5f)

        val content = RectF(well)
        content.inset(painter.density * 5f, painter.density * 4f)
        painter.draw(canvas, content, settings.notificationPage, frame, settings, compact = surface.compact)

        Canvas(out).drawBitmap(scratch, 0f, 0f, ditherPaint)
        return out
    }

    /**
     * Notification width in pixels, capped. The cap is the whole budget: every
     * pixel here is copied across a process boundary ten times a second.
     */
    private fun renderWidth(): Int {
        val metrics = context.resources.displayMetrics
        return metrics.widthPixels.coerceAtMost(MAX_WIDTH_PX).coerceAtLeast(240)
    }

    fun release() {
        painterCollapsed.release()
        painterExpanded.release()
        collapsed.release()
        expanded.release()
    }

    companion object {
        const val NOTIFICATION_ID = 4711
        private const val MAX_WIDTH_PX = 480
        /** The layouts are authored against a 360 dp-wide notification. */
        private const val REFERENCE_DP = 360f
        private const val COLLAPSED_DP = 52f
        private const val EXPANDED_DP = 132f
    }
}
