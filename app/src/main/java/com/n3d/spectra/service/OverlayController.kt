package com.n3d.spectra.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.n3d.spectra.dsp.AnalysisFrame
import com.n3d.spectra.paint.Neu
import com.n3d.spectra.paint.Palette
import com.n3d.spectra.paint.VizPainter
import com.n3d.spectra.settings.Settings
import kotlin.math.abs
import kotlin.math.roundToInt
import android.provider.Settings as AndroidSettings

private const val DEFAULT_WIDTH_DP = 250f
private const val DEFAULT_HEIGHT_DP = 120f
private const val MIN_WIDTH_DP = 140f
private const val MIN_HEIGHT_DP = 56f
private const val RESIZE_ZONE_DP = 26f

/**
 * The floating always-on-top analyser.
 *
 * This is the only surface that can actually run at the display refresh rate,
 * because it is a real View in this process rather than pixels shipped to
 * another one. The trade is that Android hides overlay windows on the keyguard,
 * so this and the lock-screen notification are two separate features rather than
 * one — there is no window type that gives both.
 */
class OverlayController(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: OverlayView? = null

    fun canDraw(): Boolean = AndroidSettings.canDrawOverlays(context)

    fun isShowing(): Boolean = view != null

    fun show(settings: Settings, dark: Boolean) {
        if (view != null) {
            apply(settings, dark)
            return
        }
        if (!canDraw()) {
            Log.w(TAG, "overlay requested without the draw-over-other-apps permission")
            return
        }
        val density = context.resources.displayMetrics.density
        val lp = WindowManager.LayoutParams(
            (DEFAULT_WIDTH_DP * density).roundToInt(),
            (DEFAULT_HEIGHT_DP * density).roundToInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (12 * density).roundToInt()
            y = (120 * density).roundToInt()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Let the overlay sit under a display cutout rather than be
                // pushed down by it — the user placed it where they placed it.
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val v = OverlayView(context, windowManager, lp)
        v.palette = Palette.of(dark)
        v.settings = settings
        v.alpha = settings.overlayOpacity.coerceIn(0.25f, 1f)
        try {
            windowManager.addView(v, lp)
        } catch (t: Throwable) {
            Log.e(TAG, "failed to add overlay", t)
            return
        }
        view = v
    }

    fun apply(settings: Settings, dark: Boolean) {
        val v = view ?: return
        v.settings = settings
        v.palette = Palette.of(dark)
        v.alpha = settings.overlayOpacity.coerceIn(0.25f, 1f)
    }

    /** Called from the render tick; cheap enough to run at 60 Hz. */
    fun update(frame: AnalysisFrame?) {
        val v = view ?: return
        v.frame = frame
        v.invalidate()
    }

    fun hide() {
        val v = view ?: return
        runCatching { windowManager.removeView(v) }
        v.release()
        view = null
    }

    companion object {
        private const val TAG = "SpectraOverlay"
    }

    /**
     * Drag anywhere to move; drag the bottom-right corner to resize; double-tap
     * to collapse to a bar. There is no chrome for any of that — chrome on a
     * 250 dp window is all chrome.
     */
    @SuppressLint("ViewConstructor")
    private class OverlayView(
        context: Context,
        private val windowManager: WindowManager,
        private val lp: WindowManager.LayoutParams,
    ) : View(context) {

        var palette: Palette = Palette.DARK
            set(value) {
                field = value
                painter.palette = value
                background = null
                invalidate()
            }
        var settings: Settings = Settings()
        var frame: AnalysisFrame? = null

        private val painter = VizPainter(Palette.DARK)
        private val density = context.resources.displayMetrics.density
        private var collapsed = false

        // The neumorphic frame is static between resizes, so it is rendered once
        // and blitted. Re-running four blurred shadows at 60 fps would cost more
        // than every graph in the app put together.
        private var chrome: Bitmap? = null
        private var chromeW = 0
        private var chromeH = 0
        private var chromeKey = 0
        private val contentRect = RectF()

        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var startW = 0
        private var startH = 0
        private var resizing = false
        private var moved = false
        private var lastTapAt = 0L

        init {
            painter.density = density
            setLayerType(LAYER_TYPE_HARDWARE, null)
        }

        private fun ensureChrome(w: Int, h: Int) {
            if (chrome != null && chromeW == w && chromeH == h && chromeKey == palette.bg) return
            chrome?.recycle()
            if (w <= 0 || h <= 0) return
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val depth = 5f * density
            val card = RectF(depth * 2f, depth * 2f, w - depth * 2f, h - depth * 2f)
            Neu.raised(c, card, 18f * density, palette, depth)
            val well = RectF(card)
            well.inset(7f * density, 7f * density)
            Neu.inset(c, well, 12f * density, palette, 2.5f * density)
            contentRect.set(well)
            contentRect.inset(5f * density, 4f * density)
            chrome = bmp
            chromeW = w
            chromeH = h
            chromeKey = palette.bg
        }

        override fun onDraw(canvas: Canvas) {
            ensureChrome(width, height)
            chrome?.let { canvas.drawBitmap(it, 0f, 0f, null) } ?: return
            painter.draw(
                canvas,
                contentRect,
                settings.overlayPage,
                frame,
                settings,
                compact = collapsed || height < 90f * density,
            )
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    startW = lp.width
                    startH = lp.height
                    resizing = event.x > width - RESIZE_ZONE_DP * density &&
                        event.y > height - RESIZE_ZONE_DP * density
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > 6 * density || abs(dy) > 6 * density) moved = true
                    if (!moved) return true
                    if (resizing) {
                        lp.width = (startW + dx).roundToInt()
                            .coerceAtLeast((MIN_WIDTH_DP * density).roundToInt())
                        lp.height = (startH + dy).roundToInt()
                            .coerceAtLeast((MIN_HEIGHT_DP * density).roundToInt())
                    } else {
                        lp.x = (startX + dx).roundToInt()
                        lp.y = (startY + dy).roundToInt()
                    }
                    runCatching { windowManager.updateViewLayout(this, lp) }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapAt < 320) {
                            collapsed = !collapsed
                            lp.height = if (collapsed) {
                                (MIN_HEIGHT_DP * density).roundToInt()
                            } else {
                                (DEFAULT_HEIGHT_DP * density).roundToInt()
                            }
                            runCatching { windowManager.updateViewLayout(this, lp) }
                            lastTapAt = 0L
                        } else {
                            lastTapAt = now
                        }
                    }
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        fun release() {
            painter.release()
            chrome?.recycle()
            chrome = null
        }
    }
}
