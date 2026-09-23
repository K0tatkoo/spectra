package com.n3d.spectra.audio

import android.os.Process
import android.util.Log
import com.n3d.spectra.dsp.AnalysisFrame
import com.n3d.spectra.dsp.BandSplitter
import com.n3d.spectra.dsp.LoudnessMeter
import com.n3d.spectra.dsp.PeakHold
import com.n3d.spectra.dsp.ScrollBuffer
import com.n3d.spectra.dsp.SpectrogramBuffer
import com.n3d.spectra.dsp.SpectrumAnalyzer
import com.n3d.spectra.dsp.StereoAnalyzer
import com.n3d.spectra.settings.Settings
import com.n3d.spectra.settings.SourceKind
import com.n3d.spectra.settings.VizPage
import com.n3d.spectra.settings.WaveformMode
import com.n3d.spectra.stems.ScopeRunner
import com.n3d.spectra.stems.StemWorkerHooks
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * The single analysis pipeline, shared by every surface.
 *
 * There is exactly one of these because there is exactly one microphone: the
 * in-app UI, the lock-screen notification, the floating overlay and the home
 * screen widget all read the same [frame], and none of them owns the capture.
 * The service owns it, and the service outlives the Activity.
 *
 * Threading: one dedicated thread does capture and DSP; everything published
 * outwards goes through StateFlow, which is conflated, so a slow collector
 * drops frames instead of back-pressuring the audio thread.
 */
object AudioEngine {

    sealed class State {
        data object Idle : State()
        data object Starting : State()
        data class Running(val source: SourceKind, val describe: String) : State()
        data class Failed(val kind: CaptureException.Kind, val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _frame = MutableStateFlow<AnalysisFrame?>(null)
    val frame: StateFlow<AnalysisFrame?> = _frame.asStateFlow()

    /** Non-fatal condition the user should know about, e.g. a source that is muted by policy. */
    private val _warning = MutableStateFlow<String?>(null)
    val warning: StateFlow<String?> = _warning.asStateFlow()

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    const val SPECTROGRAM_ROWS = 256
    const val SPECTROGRAM_COLUMNS = 512
    const val BAND_HISTORY = 256
    private const val SCOPE_SPAN = 4096
    private const val SCOPE_POINTS = 512
    /**
     * Frames per capture read. Every frame the surfaces see is published from
     * inside one read, so this — not the hop — is what sets how often the
     * picture can change: 1024 is about 47 updates a second, which the
     * held-still scopes need to look still rather than to step.
     */
    private const val READ_FRAMES = 1024

    /** Shared history, referenced (never copied) by every published frame. */
    val spectrogram = SpectrogramBuffer(SPECTROGRAM_ROWS, SPECTROGRAM_COLUMNS)

    /** Short-term loudness trace, shared with every surface for the same reason. */
    val loudnessHistory = ScrollBuffer(BAND_HISTORY, -70f)

    @Volatile private var settings = Settings()
    @Volatile private var running = false
    @Volatile private var resetRequested = false
    private var thread: Thread? = null
    private var capture: AudioCapture? = null

    fun currentSettings(): Settings = settings

    /**
     * The page the app itself is showing, or null while it is not on screen.
     * The stem model and the held-still scopes only run for pages somebody can
     * see — they are the two most expensive things in the app.
     */
    @Volatile var inAppPage: VizPage? = null

    /** Kept up to date by the service, so background surfaces stop costing when the screen is off. */
    @Volatile var screenOn: Boolean = true

    /** Where the stem model is on disk. Set once by the Application. */
    @Volatile var stemModel: (() -> File)? = null

    /** Priority and performance hints for the stem worker. Set once by the Application. */
    @Volatile var stemHooks: () -> StemWorkerHooks = { StemWorkerHooks.NONE }

    /**
     * Applies new settings to the running analysis. Safe from any thread: the
     * DSP thread picks the new object up whole at the top of its next block, so
     * a change can never be observed half-applied.
     *
     * Returns true when the change requires the capture itself to be reopened —
     * the caller (the service) restarts it, because only the service knows
     * whether it holds a MediaProjection token.
     */
    fun updateSettings(next: Settings): Boolean {
        val previous = settings
        settings = next
        return previous.source != next.source ||
            previous.sampleRate != next.sampleRate ||
            previous.stereo != next.stereo ||
            previous.micPreset != next.micPreset ||
            previous.micAgc != next.micAgc ||
            previous.micNoiseSuppression != next.micNoiseSuppression
    }

    fun setPaused(value: Boolean) { _paused.value = value }

    fun resetMeters() { resetRequested = true }

    @Synchronized
    fun start(source: AudioCapture) {
        stop()
        _state.value = State.Starting
        _warning.value = null
        capture = source
        running = true
        thread = Thread({ runLoop(source) }, "spectra-dsp").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        val cap = capture
        // Order matters. AudioRecord.read() blocks and is not interruptible;
        // only stop() makes it return. Releasing the record while the DSP thread
        // is still inside read() is a native crash, so: stop, join, then release.
        runCatching { cap?.stop() }
        thread?.let {
            it.interrupt()
            runCatching { it.join(1_000) }
        }
        thread = null
        runCatching { cap?.release() }
        capture = null
        if (_state.value !is State.Failed) _state.value = State.Idle
        _frame.value = null
        spectrogram.clear()
        loudnessHistory.clear()
    }

    fun isRunning(): Boolean = running

    // ------------------------------------------------------------------------

    private fun runLoop(cap: AudioCapture) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        try {
            cap.start()
        } catch (e: CaptureException) {
            _state.value = State.Failed(e.kind, e.message ?: "Could not open the audio input.")
            running = false
            return
        } catch (t: Throwable) {
            _state.value = State.Failed(CaptureException.Kind.UNKNOWN, t.message ?: "Unknown capture failure.")
            running = false
            return
        }

        val rate = cap.sampleRate
        val channels = cap.channelCount
        var s = settings
        _state.value = State.Running(s.source, cap.describe)

        val analyzer = SpectrumAnalyzer(rate, s, SPECTROGRAM_ROWS)
        val bands = BandSplitter(rate, s, BAND_HISTORY)
        val loudness = LoudnessMeter(rate, s)
        val stereo = StereoAnalyzer(rate, s)

        var ring = MonoRing(s.fftSize)
        var block = FloatArray(s.fftSize)
        var hop = s.hopSize
        val scopes = ScopeRunner(
            rate,
            modelFile = { stemModel?.invoke() ?: error("no model provider") },
            hooks = { stemHooks() },
        )
        val scopeL = MonoRing(SCOPE_SPAN)
        val scopeR = MonoRing(SCOPE_SPAN)
        val column = ByteArray(SPECTROGRAM_ROWS)

        val interleaved = FloatArray(READ_FRAMES * channels)
        val left = FloatArray(READ_FRAMES)
        val right = FloatArray(READ_FRAMES)
        val waveL = FloatArray(SCOPE_POINTS)
        val waveR = FloatArray(SCOPE_POINTS)
        val scopeStride = SCOPE_SPAN / SCOPE_POINTS

        var gain = dbToLinear(s.inputGainDb)
        var seq = 0L
        var silentMs = 0f
        var warnedSilent = false
        var clipHoldMs = 0f

        while (running && !Thread.currentThread().isInterrupted) {
            val read = try {
                cap.read(interleaved)
            } catch (t: Throwable) {
                Log.w(TAG, "read failed", t)
                -1
            }
            if (read < 0) {
                _state.value = State.Failed(
                    CaptureException.Kind.UNAVAILABLE,
                    "The audio input stopped delivering samples. Another app may have taken it.",
                )
                break
            }
            if (read == 0) continue

            // Pick up a settings change at a block boundary.
            if (settings !== s) {
                val next = settings
                if (next.fftSize != s.fftSize || next.overlap != s.overlap) {
                    ring = MonoRing(next.fftSize)
                    block = FloatArray(next.fftSize)
                    hop = next.hopSize
                    spectrogram.clear()
                }
                s = next
                gain = dbToLinear(s.inputGainDb)
                analyzer.configure(s, rate)
                bands.configure(s, rate)
                loudness.configure(s, rate)
                stereo.configure(s, rate)
            }

            if (resetRequested) {
                resetRequested = false
                loudness.reset()
                bands.reset()
                stereo.reset()
                analyzer.peaks.reset()
                spectrogram.clear()
                loudnessHistory.clear()
                scopes.reset()
            }

            val frames = read / channels
            var clipped = false
            var allSilent = true

            if (channels == 2) {
                var j = 0
                for (i in 0 until frames) {
                    val l = interleaved[j++] * gain
                    val r = interleaved[j++] * gain
                    left[i] = l
                    right[i] = r
                    if (abs(l) >= CLIP_THRESHOLD || abs(r) >= CLIP_THRESHOLD) clipped = true
                    if (allSilent && (abs(l) > SILENCE_THRESHOLD || abs(r) > SILENCE_THRESHOLD)) allSilent = false
                }
            } else {
                for (i in 0 until frames) {
                    val l = interleaved[i] * gain
                    left[i] = l
                    if (abs(l) >= CLIP_THRESHOLD) clipped = true
                    if (allSilent && abs(l) > SILENCE_THRESHOLD) allSilent = false
                }
            }

            val blockMs = frames * 1000f / rate
            if (allSilent) silentMs += blockMs else silentMs = 0f
            clipHoldMs = if (clipped) CLIP_HOLD_MS else (clipHoldMs - blockMs).coerceAtLeast(0f)

            warnedSilent = updateSilenceWarning(s, silentMs, warnedSilent)

            if (_paused.value) continue

            val rightOrNull = if (channels == 2) right else null

            bands.process(left, rightOrNull, frames)
            // The band meters tick once per capture chunk rather than once per
            // hop: the filters are streaming, so the natural grain is however
            // many samples AudioRecord just handed over.
            bands.tick(blockMs)
            loudness.process(left, rightOrNull, frames)
            stereo.process(left, rightOrNull, frames)

            scopes.process(left, rightOrNull, frames, wants(s), s, blockMs)

            scopeL.write(left, 0, frames)
            if (rightOrNull != null) scopeR.write(rightOrNull, 0, frames)

            // The transform runs on the mono sum: a spectrum of L+R is what an
            // engineer expects from an analyser, and running two transforms
            // would double the cost to show two nearly identical curves.
            if (channels == 2) {
                for (i in 0 until frames) left[i] = (left[i] + right[i]) * 0.5f
            }
            ring.write(left, 0, frames)

            val dtMs = hop * 1000f / rate
            while (ring.pending >= hop) {
                ring.consume(hop)
                ring.copyLatest(block)
                analyzer.process(block, dtMs)
                analyzer.fillSpectrogramColumn(column, s.spectrogramFloorDb, s.spectrogramCeilingDb)
                spectrogram.push(column)

                scopeL.copyNewestDecimated(waveL, SCOPE_SPAN, scopeStride)
                if (rightOrNull != null) scopeR.copyNewestDecimated(waveR, SCOPE_SPAN, scopeStride)

                val loudnessReading = loudness.read()
                loudnessHistory.push(loudnessReading.shortTerm)

                _frame.value = AnalysisFrame(
                    seq = seq++,
                    sampleRate = rate,
                    fftSize = s.fftSize,
                    binHz = rate.toFloat() / s.fftSize,
                    magnitudesDb = analyzer.smoothedDb.copyOf(),
                    peakDb = if (s.peakHold) analyzer.peaks.values.copyOf() else FloatArray(0),
                    bands = bands.readings(),
                    waveL = waveL.copyOf(),
                    waveR = if (rightOrNull != null) waveR.copyOf() else FloatArray(0),
                    gonio = stereo.snapshotCloud(),
                    correlation = stereo.correlation,
                    stereoWidth = stereo.width,
                    loudness = loudnessReading,
                    rmsDbL = StereoAnalyzer.toDb(stereo.rmsL),
                    rmsDbR = StereoAnalyzer.toDb(if (rightOrNull != null) stereo.rmsR else stereo.rmsL),
                    peakDbL = StereoAnalyzer.toDb(stereo.peakL),
                    peakDbR = StereoAnalyzer.toDb(if (rightOrNull != null) stereo.peakR else stereo.peakL),
                    clipped = clipHoldMs > 0f,
                    silent = silentMs > SILENCE_HINT_MS,
                    spectrogram = spectrogram,
                    scopes = scopes.latest,
                    nes = scopes.latestNes,
                )
            }
        }

        scopes.release()
        cap.release()
        running = false
    }

    /** Which of the expensive analyses a visible surface is actually showing. */
    private fun wants(s: Settings): ScopeRunner.Wants {
        val app = inAppPage
        val background = screenOn || !s.pauseRenderWhenScreenOff
        fun shows(page: VizPage) = app == page || (background && (
            (s.overlayEnabled && s.overlayPage == page) ||
                (s.notificationEnabled && s.notificationPage == page) ||
                (s.widgetEnabled && s.widgetPage == page)
            ))
        val wave = shows(VizPage.WAVEFORM)
        return ScopeRunner.Wants(
            stems = shows(VizPage.STEMS),
            hold = wave && s.waveformMode == WaveformMode.HOLD,
            nes = wave && s.waveformMode == WaveformMode.NES,
        )
    }

    /**
     * Digital silence is ambiguous — nothing is playing, or the app that is
     * playing has opted out of capture. Rather than guess (or silently swap to
     * the microphone, which would quietly change what the numbers mean), say
     * exactly what the platform allows and let the user decide.
     */
    private fun updateSilenceWarning(s: Settings, silentMs: Float, alreadyWarned: Boolean): Boolean {
        if (silentMs > SILENCE_WARN_MS) {
            if (!alreadyWarned) {
                _warning.value = if (s.source == SourceKind.DEVICE_AUDIO) {
                    "No audio is reaching the capture. Either nothing is playing, or the app " +
                        "playing it blocks capture — Spotify, YouTube and anything DRM-protected " +
                        "opt out, and Android gives them silence rather than an error. " +
                        "Switch the source to Microphone to measure those."
                } else {
                    "The microphone is delivering digital silence. Check that no other app is " +
                        "holding it and that the mic is not muted."
                }
            }
            return true
        }
        if (alreadyWarned) _warning.value = null
        return false
    }

    fun clearWarning() { _warning.value = null }

    private fun dbToLinear(db: Float): Float = if (db == 0f) 1f else 10f.pow(db / 20f)

    fun linearToDb(v: Float): Float {
        if (v <= 1e-7f) return PeakHold.FLOOR_DB
        return 20f * log10(v)
    }

    private const val TAG = "SpectraEngine"
    private const val CLIP_THRESHOLD = 0.999f
    private const val SILENCE_THRESHOLD = 1e-6f
    private const val SILENCE_HINT_MS = 1000f
    private const val SILENCE_WARN_MS = 2500f
    private const val CLIP_HOLD_MS = 1200f
}
