package com.n3d.spectra.desktop.audio

import com.n3d.spectra.audio.AudioCapture
import com.n3d.spectra.audio.CaptureException
import com.n3d.spectra.audio.MonoRing
import com.n3d.spectra.dsp.nes.NesOptions
import com.n3d.spectra.dsp.nes.NesReading
import com.n3d.spectra.dsp.nes.PitchPreFilter
import com.n3d.spectra.dsp.nes.TriangleTracker
import com.n3d.spectra.dsp.AnalysisFrame
import com.n3d.spectra.dsp.BandSplitter
import com.n3d.spectra.dsp.LoudnessMeter
import com.n3d.spectra.dsp.PeakHold
import com.n3d.spectra.dsp.ScrollBuffer
import com.n3d.spectra.dsp.SpectrogramBuffer
import com.n3d.spectra.dsp.SpectrumAnalyzer
import com.n3d.spectra.dsp.StereoAnalyzer
import com.n3d.spectra.settings.Settings
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/** One published snapshot: the shared analysis, plus whatever the 2A03 tracker made of it. */
class DesktopFrame(val analysis: AnalysisFrame, val nes: NesReading?)

/**
 * The desktop analysis pipeline.
 *
 * A port of the Android `AudioEngine` — the same order of operations, the same
 * shared DSP objects, the same conflate-don't-block contract — with three
 * differences that the platform forces:
 *
 *  * capture is a `TargetDataLine` rather than an `AudioRecord`;
 *  * there is no coroutine machinery, so the newest frame sits in a volatile
 *    field and the UI's repaint timer reads it. That is what a conflated
 *    `StateFlow` did anyway: a slow consumer drops frames instead of pushing back
 *    on the audio thread;
 *  * it carries the 2A03 tracker, which needs full-rate history that the Android
 *    engine never keeps.
 */
object DesktopEngine {

    sealed class State {
        data object Idle : State()
        data object Starting : State()
        data class Running(val describe: String) : State()
        data class Failed(val kind: CaptureException.Kind, val message: String) : State()
    }

    const val SPECTROGRAM_ROWS = 256
    const val SPECTROGRAM_COLUMNS = 512
    const val BAND_HISTORY = 256
    private const val SCOPE_SPAN = 4096
    private const val SCOPE_POINTS = 512
    private const val READ_FRAMES = 2048

    /** How often the 2A03 tracker runs. Twice the display rate is already more than enough. */
    private const val NES_INTERVAL_MS = 16f

    val spectrogram = SpectrogramBuffer(SPECTROGRAM_ROWS, SPECTROGRAM_COLUMNS)
    val loudnessHistory = ScrollBuffer(BAND_HISTORY, -70f)

    @Volatile var state: State = State.Idle
        private set

    @Volatile var warning: String? = null

    @Volatile var frame: DesktopFrame? = null
        private set

    @Volatile var paused: Boolean = false

    /** Set by the UI: the tracker is expensive and only the 2A03 page needs it. */
    @Volatile var nesActive: Boolean = false

    @Volatile private var settings = Settings()
    @Volatile private var nesOptions = NesOptions()
    @Volatile private var running = false
    @Volatile private var resetRequested = false
    @Volatile private var nesResetRequested = false

    private var thread: Thread? = null
    private var capture: AudioCapture? = null

    fun currentSettings(): Settings = settings

    /** True when the change needs the line reopening — only the caller knows how to do that. */
    fun updateSettings(next: Settings): Boolean {
        val previous = settings
        settings = next
        return previous.sampleRate != next.sampleRate || previous.stereo != next.stereo
    }

    fun updateNes(next: NesOptions) {
        val previous = nesOptions
        nesOptions = next
        if (previous.huntMaxHz != next.huntMaxHz || previous.region != next.region) nesResetRequested = true
    }

    fun resetMeters() {
        resetRequested = true
    }

    @Synchronized
    fun start(source: AudioCapture) {
        stop()
        state = State.Starting
        warning = null
        capture = source
        running = true
        thread = Thread({ runLoop(source) }, "spectra-dsp").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        val cap = capture
        // Same order as the Android build, for the same reason: read() blocks and
        // only stop() makes it return, so stop, join, then close.
        runCatching { cap?.stop() }
        thread?.let {
            it.interrupt()
            runCatching { it.join(1_000) }
        }
        thread = null
        runCatching { cap?.release() }
        capture = null
        if (state !is State.Failed) state = State.Idle
        frame = null
        spectrogram.clear()
        loudnessHistory.clear()
    }

    fun isRunning(): Boolean = running

    // ------------------------------------------------------------------------

    private fun runLoop(cap: AudioCapture) {
        try {
            cap.start()
        } catch (e: CaptureException) {
            state = State.Failed(e.kind, e.message ?: "Could not open the audio input.")
            running = false
            return
        } catch (t: Throwable) {
            state = State.Failed(CaptureException.Kind.UNKNOWN, t.message ?: "Unknown capture failure.")
            running = false
            return
        }

        val rate = cap.sampleRate
        val channels = cap.channelCount
        var s = settings
        var nes = nesOptions
        state = State.Running(cap.describe)

        val analyzer = SpectrumAnalyzer(rate, s, SPECTROGRAM_ROWS)
        val bands = BandSplitter(rate, s, BAND_HISTORY)
        val loudness = LoudnessMeter(rate, s)
        val stereo = StereoAnalyzer(rate, s)

        var ring = MonoRing(s.fftSize)
        var block = FloatArray(s.fftSize)
        var hop = s.hopSize
        val scopeL = MonoRing(SCOPE_SPAN)
        val scopeR = MonoRing(SCOPE_SPAN)
        val column = ByteArray(SPECTROGRAM_ROWS)

        val interleaved = FloatArray(READ_FRAMES * channels)
        val left = FloatArray(READ_FRAMES)
        val right = FloatArray(READ_FRAMES)
        val waveL = FloatArray(SCOPE_POINTS)
        val waveR = FloatArray(SCOPE_POINTS)
        val scopeStride = SCOPE_SPAN / SCOPE_POINTS

        // ---- 2A03 -----------------------------------------------------------
        // Two histories, not one: the display needs the raw signal and the period
        // detector needs it low-passed, and the filter has to run as a stream so
        // its settling transient never lands inside an analysis window.
        val nesRaw = MonoRing(TriangleTracker.HISTORY)
        val nesLp = MonoRing(TriangleTracker.HISTORY)
        val nesRawSnapshot = FloatArray(TriangleTracker.HISTORY)
        val nesLpSnapshot = FloatArray(TriangleTracker.HISTORY)
        val nesScratch = FloatArray(READ_FRAMES)
        var pitchFilter = PitchPreFilter(rate, PitchPreFilter.cornerFor(nes.huntMaxHz))
        val tracker = TriangleTracker(rate)
        var msSinceNes = 0f
        var nesReading: NesReading? = null

        var gain = dbToLinear(s.inputGainDb)
        var seq = 0L
        var silentMs = 0f
        var warnedSilent = false
        var clipHoldMs = 0f

        while (running && !Thread.currentThread().isInterrupted) {
            val read = try {
                cap.read(interleaved)
            } catch (t: Throwable) {
                -1
            }
            if (read < 0) {
                state = State.Failed(
                    CaptureException.Kind.UNAVAILABLE,
                    "The audio input stopped delivering samples. Another app may have taken it.",
                )
                break
            }
            if (read == 0) continue

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
            if (nesOptions !== nes) {
                val next = nesOptions
                if (next.huntMaxHz != nes.huntMaxHz) {
                    pitchFilter = PitchPreFilter(rate, PitchPreFilter.cornerFor(next.huntMaxHz))
                    nesLp.clear()
                }
                nes = next
            }
            if (nesResetRequested) {
                nesResetRequested = false
                tracker.reset()
                nesReading = null
            }

            if (resetRequested) {
                resetRequested = false
                loudness.reset()
                bands.reset()
                stereo.reset()
                analyzer.peaks.reset()
                spectrogram.clear()
                loudnessHistory.clear()
                tracker.reset()
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
            warnedSilent = updateSilenceWarning(silentMs, warnedSilent)

            if (paused) continue

            val rightOrNull = if (channels == 2) right else null

            bands.process(left, rightOrNull, frames)
            bands.tick(blockMs)
            loudness.process(left, rightOrNull, frames)
            stereo.process(left, rightOrNull, frames)

            scopeL.write(left, 0, frames)
            if (rightOrNull != null) scopeR.write(rightOrNull, 0, frames)

            // The mono sum, which is what the transform and the 2A03 tracker both
            // want: the triangle channel is centred, and a stereo emulator would
            // otherwise be analysed twice to say the same thing.
            if (channels == 2) {
                for (i in 0 until frames) left[i] = (left[i] + right[i]) * 0.5f
            }

            if (nesActive) {
                nesRaw.write(left, 0, frames)
                pitchFilter.process(left, nesScratch, frames)
                nesLp.write(nesScratch, 0, frames)
                msSinceNes += blockMs
                if (msSinceNes >= NES_INTERVAL_MS) {
                    nesRaw.copyLatest(nesRawSnapshot)
                    nesLp.copyLatest(nesLpSnapshot)
                    nesReading = tracker.analyze(nesRawSnapshot, nesLpSnapshot, msSinceNes, nes)
                    msSinceNes = 0f
                }
            } else if (nesReading != null) {
                nesReading = null
                tracker.reset()
                msSinceNes = 0f
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

                frame = DesktopFrame(
                    AnalysisFrame(
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
                    ),
                    nesReading,
                )
            }
        }

        cap.release()
        running = false
    }

    /**
     * Digital silence is ambiguous, so say what it might mean rather than guess.
     *
     * The Windows version of the Android problem: there, a DRM-protected app opts
     * out of capture and the OS hands back silence. Here, the usual cause is a
     * physical input selected while the audio the user wants is coming out of the
     * speakers — which needs a loopback endpoint, not a microphone.
     */
    private fun updateSilenceWarning(silentMs: Float, alreadyWarned: Boolean): Boolean {
        if (silentMs > SILENCE_WARN_MS) {
            if (!alreadyWarned) {
                warning = if (Devices.hasLoopback()) {
                    "The selected input is delivering digital silence. If you meant to measure what " +
                        "is playing rather than what the microphone hears, choose the loopback input " +
                        "(\"Stereo Mix\" or similar) in Source."
                } else {
                    "The selected input is delivering digital silence. Windows is not exposing any " +
                        "loopback endpoint on this machine, so system audio cannot be captured " +
                        "directly — enable \"Stereo Mix\" in Sound settings, or install a virtual " +
                        "audio cable, then pick it in Source."
                }
            }
            return true
        }
        if (alreadyWarned) warning = null
        return false
    }

    private fun dbToLinear(db: Float): Float = if (db == 0f) 1f else 10f.pow(db / 20f)

    fun linearToDb(v: Float): Float {
        if (v <= 1e-7f) return PeakHold.FLOOR_DB
        return 20f * log10(v)
    }

    private const val CLIP_THRESHOLD = 0.999f
    private const val SILENCE_THRESHOLD = 1e-6f
    private const val SILENCE_HINT_MS = 1000f
    private const val SILENCE_WARN_MS = 2500f
    private const val CLIP_HOLD_MS = 1200f
}
