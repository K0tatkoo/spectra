package com.n3d.spectra.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.n3d.spectra.audio.AudioEngine
import com.n3d.spectra.audio.MicCapture
import com.n3d.spectra.audio.PlaybackCapture
import com.n3d.spectra.settings.Settings
import com.n3d.spectra.settings.SettingsStore
import com.n3d.spectra.settings.SourceKind
import com.n3d.spectra.settings.ThemeMode
import com.n3d.spectra.settings.VizPage
import com.n3d.spectra.widget.WidgetRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Owns the capture and every surface that outlives the Activity.
 *
 * One service rather than three: the microphone can only be open once, and the
 * notification, the overlay and the widget all want the same frames. Splitting
 * them would mean either three capture threads fighting over one input or an
 * IPC layer between our own components.
 *
 * The render loops are deliberately separate and independently paced. The
 * notification runs on a background thread at ~10 fps because every frame is a
 * bitmap crossing a process boundary; the overlay runs on the main thread at up
 * to 60 fps because it is a real View here; the widget crawls. Driving all three
 * from one timer would force the slowest rate onto the fastest surface.
 */
class AnalyzerService : Service() {

    private lateinit var settingsStore: SettingsStore
    private lateinit var notifications: NotificationRenderer
    private lateinit var overlay: OverlayController

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var renderThread: HandlerThread
    private lateinit var renderHandler: Handler

    // Not Main: reacting to a settings change can mean tearing down and
    // reopening AudioRecord, which joins the DSP thread.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var projection: MediaProjection? = null
    private var settings: Settings = Settings()
    private var startedForeground = false
    private var lastWidgetPushAt = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection revoked")
            AudioEngine.stop()
            stopSelf()
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Kick the loops so they re-evaluate immediately rather than at the
            // end of whatever delay they are sitting in.
            scheduleNotification(0)
            scheduleOverlay(0)
        }
    }

    override fun onCreate() {
        super.onCreate()
        settingsStore = SettingsStore.get(this)
        settings = settingsStore.awaitLoaded()
        AudioEngine.updateSettings(settings)
        notifications = NotificationRenderer(this)
        overlay = OverlayController(this)

        renderThread = HandlerThread("spectra-render", android.os.Process.THREAD_PRIORITY_DISPLAY)
        renderThread.start()
        renderHandler = Handler(renderThread.looper)

        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        scope.launch {
            settingsStore.state.collectLatest { next ->
                val needsRestart = AudioEngine.updateSettings(next)
                val overlayChanged = next.overlayEnabled != settings.overlayEnabled
                settings = next
                if (needsRestart && AudioEngine.isRunning()) restartCapture()
                syncOverlay(force = overlayChanged)
                scheduleNotification(0)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MIC -> startMicrophone()
            ACTION_START_PLAYBACK -> startPlayback(intent)
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_PAUSE -> {
                AudioEngine.setPaused(!AudioEngine.paused.value)
                scheduleNotification(0)
            }
            ACTION_CYCLE_PAGE -> {
                val pages = VizPage.entries
                val next = pages[(pages.indexOf(settings.notificationPage) + 1) % pages.size]
                settingsStore.update { it.copy(notificationPage = next) }
            }
            ACTION_RESET_METERS -> AudioEngine.resetMeters()
        }
        scheduleNotification(0)
        scheduleOverlay(0)
        scheduleWidget(0)
        // START_STICKY would have Android restart us with a null intent after a
        // kill, at which point we would have neither a projection token nor a
        // fresh microphone grant. Better to stay down and let the user restart.
        return START_NOT_STICKY
    }

    // ---- capture -----------------------------------------------------------

    private fun startMicrophone() {
        goForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        releaseProjection()
        AudioEngine.start(MicCapture(this, settings))
        syncOverlay(force = true)
    }

    private fun startPlayback(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (data == null) {
            Log.w(TAG, "playback capture started without a consent token")
            stopEverything()
            return
        }

        // Order matters and is enforced from Android 14: the service must
        // already be in the foreground with the mediaProjection type before
        // getMediaProjection() is called, and a callback must be registered
        // before the projection is used at all.
        goForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        releaseProjection()

        val manager = getSystemService(MediaProjectionManager::class.java)
        val mp = try {
            manager.getMediaProjection(resultCode, data)
        } catch (t: Throwable) {
            Log.e(TAG, "could not obtain MediaProjection", t)
            null
        }
        if (mp == null) {
            stopEverything()
            return
        }
        mp.registerCallback(projectionCallback, mainHandler)
        projection = mp
        AudioEngine.start(PlaybackCapture(mp, settings.sampleRate, settings.stereo))
        syncOverlay(force = true)
    }

    private fun restartCapture() {
        val mp = projection
        if (settings.source == SourceKind.DEVICE_AUDIO && mp != null) {
            AudioEngine.start(PlaybackCapture(mp, settings.sampleRate, settings.stereo))
        } else if (settings.source == SourceKind.MICROPHONE) {
            AudioEngine.start(MicCapture(this, settings))
        } else {
            // Switching to device audio needs a fresh consent token, which only
            // an Activity can ask for.
            AudioEngine.stop()
        }
    }

    private fun goForeground(type: Int) {
        val notification = notifications.build(
            AudioEngine.frame.value,
            settings,
            isDark(),
            AudioEngine.paused.value,
            statusLine(),
        )
        ServiceCompat.startForeground(this, NotificationRenderer.NOTIFICATION_ID, notification, type)
        startedForeground = true
    }

    private fun releaseProjection() {
        projection?.let {
            runCatching { it.unregisterCallback(projectionCallback) }
            runCatching { it.stop() }
        }
        projection = null
    }

    private fun stopEverything() {
        AudioEngine.stop()
        releaseProjection()
        overlay.hide()
        if (startedForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            startedForeground = false
        }
        stopSelf()
    }

    // ---- render loops ------------------------------------------------------

    private val notificationTick = Runnable {
        val delay = renderNotification()
        scheduleNotification(delay)
    }

    private val overlayTick = Runnable {
        val delay = renderOverlay()
        scheduleOverlay(delay)
    }

    private val widgetTick = Runnable {
        val delay = renderWidget()
        scheduleWidget(delay)
    }

    private fun scheduleNotification(delay: Long) {
        renderHandler.removeCallbacks(notificationTick)
        renderHandler.postDelayed(notificationTick, delay)
    }

    private fun scheduleOverlay(delay: Long) {
        mainHandler.removeCallbacks(overlayTick)
        mainHandler.postDelayed(overlayTick, delay)
    }

    private fun scheduleWidget(delay: Long) {
        renderHandler.removeCallbacks(widgetTick)
        renderHandler.postDelayed(widgetTick, delay)
    }

    /** Returns the delay until the next tick. */
    private fun renderNotification(): Long {
        if (!startedForeground) return 500L
        val s = settings
        if (!s.notificationEnabled) return 1_000L
        if (s.pauseRenderWhenScreenOff && !isScreenOn()) return 800L

        val notification = notifications.build(
            AudioEngine.frame.value,
            s,
            isDark(),
            AudioEngine.paused.value,
            statusLine(),
        )
        try {
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager.notify(NotificationRenderer.NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            Log.w(TAG, "notification update failed", t)
            return 1_000L
        }
        return (1000L / s.notificationFps.coerceIn(1, 15))
    }

    private fun renderOverlay(): Long {
        val s = settings
        if (!s.overlayEnabled || !overlay.isShowing()) return 1_000L
        if (s.pauseRenderWhenScreenOff && !isScreenOn()) return 800L
        overlay.update(AudioEngine.frame.value)
        return (1000L / s.overlayFps.coerceIn(1, 120))
    }

    private fun renderWidget(): Long {
        val s = settings
        if (!s.widgetEnabled) return 2_000L
        if (s.pauseRenderWhenScreenOff && !isScreenOn()) return 2_000L
        val now = System.currentTimeMillis()
        val minGap = 1000L / s.widgetFps.coerceIn(1, 4)
        if (now - lastWidgetPushAt < minGap) return minGap - (now - lastWidgetPushAt)
        lastWidgetPushAt = now
        WidgetRenderer.push(this, AudioEngine.frame.value, s, isDark())
        return minGap
    }

    private fun syncOverlay(force: Boolean) {
        mainHandler.post {
            if (settings.overlayEnabled && AudioEngine.isRunning()) {
                overlay.show(settings, isDark())
            } else if (!settings.overlayEnabled) {
                overlay.hide()
            } else if (force) {
                overlay.apply(settings, isDark())
            }
        }
    }

    // ---- helpers -----------------------------------------------------------

    private fun isScreenOn(): Boolean =
        getSystemService(PowerManager::class.java)?.isInteractive ?: true

    private fun isDark(): Boolean = when (settings.theme) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM ->
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
    }

    private fun statusLine(): String {
        val state = AudioEngine.state.value
        return when (state) {
            is AudioEngine.State.Running ->
                (if (AudioEngine.paused.value) "Paused · " else "") + state.describe
            is AudioEngine.State.Failed -> state.message
            AudioEngine.State.Starting -> "Starting…"
            AudioEngine.State.Idle -> "Stopped"
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // A day/night flip has to reach the surfaces that are not Compose.
        syncOverlay(force = true)
        scheduleNotification(0)
        scheduleWidget(0)
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { unregisterReceiver(screenReceiver) }
        mainHandler.removeCallbacksAndMessages(null)
        renderHandler.removeCallbacksAndMessages(null)
        renderThread.quitSafely()
        overlay.hide()
        notifications.release()
        releaseProjection()
        AudioEngine.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "SpectraService"

        const val ACTION_START_MIC = "com.n3d.spectra.action.START_MIC"
        const val ACTION_START_PLAYBACK = "com.n3d.spectra.action.START_PLAYBACK"
        const val ACTION_STOP = "com.n3d.spectra.action.STOP"
        const val ACTION_TOGGLE_PAUSE = "com.n3d.spectra.action.TOGGLE_PAUSE"
        const val ACTION_CYCLE_PAGE = "com.n3d.spectra.action.CYCLE_PAGE"
        const val ACTION_RESET_METERS = "com.n3d.spectra.action.RESET_METERS"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        fun intent(context: Context, action: String): Intent =
            Intent(context, AnalyzerService::class.java).setAction(action)
    }
}
