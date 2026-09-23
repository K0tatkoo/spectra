package com.n3d.spectra.stems

import com.n3d.spectra.dsp.Biquad
import com.n3d.spectra.dsp.HistoryRing
import com.n3d.spectra.dsp.LaneTrigger
import com.n3d.spectra.dsp.Resampler
import com.n3d.spectra.dsp.ScopeFrame
import com.n3d.spectra.dsp.ScopeLaneSpec
import com.n3d.spectra.dsp.ScopeLock
import com.n3d.spectra.dsp.StemLane
import com.n3d.spectra.dsp.StemsInfo
import com.n3d.spectra.dsp.StemsState
import com.n3d.spectra.dsp.nes.NesOptions
import com.n3d.spectra.dsp.nes.NesReading
import com.n3d.spectra.dsp.nes.PitchPreFilter
import com.n3d.spectra.dsp.nes.TriangleTracker
import com.n3d.spectra.settings.Settings
import java.io.File

/**
 * Everything the held-still pages need, run from the analysis thread.
 *
 * Three consumers, each only while something on screen shows it:
 *
 *  - **Stems** — resamples the capture to 44.1 kHz, feeds the [StemSeparator],
 *    and holds each separated stem still with its own [ScopeLock].
 *  - **Hold still** — one [ScopeLock] on the mix, for the Waveform page.
 *  - **2A03 triangle** — the same [TriangleTracker] the Windows build uses.
 *
 * The separator is kept alive for a while after its page goes away, because
 * loading the model takes a noticeable moment and flicking between pages should
 * not pay it every time.
 */
class ScopeRunner(
    private val rate: Int,
    private val modelFile: () -> File,
    private val hooks: () -> StemWorkerHooks,
) {

    class Wants(val stems: Boolean, val hold: Boolean, val nes: Boolean) {
        val any: Boolean get() = stems || hold || nes
    }

    // ---- stems -------------------------------------------------------------

    private var separator: StemSeparator? = null
    private var separatorThreads = 0
    private var loadError: String? = null
    private var stemsIdleMs = 0f
    private val resampler = Resampler(rate, StemSeparator.SAMPLE_RATE)
    private var rsL = FloatArray(4096)
    private var rsR = FloatArray(4096)

    /** Display order, top to bottom: the stem that is usually highest first. */
    private val laneOrder = listOf(Stem.VOCALS, Stem.OTHER, Stem.BASS, Stem.DRUMS)
    private val stemLocks = laneOrder.associateWith { ScopeLock(StemSeparator.SAMPLE_RATE, specFor(it)) }
    private val laneRaw = FloatArray(LANE_HISTORY)
    private val lanePitched = FloatArray(LANE_HISTORY)

    // ---- the mix: hold still + 2A03 ---------------------------------------

    private val mixRing = HistoryRing(TriangleTracker.HISTORY)
    private val holdLpRing = HistoryRing(TriangleTracker.HISTORY)
    private val nesLpRing = HistoryRing(TriangleTracker.HISTORY)
    private val holdFilter = Biquad.butterworthQs(4).map { Biquad.lowPass(rate, HOLD_CORNER_HZ, it) }
    private var nesFilter = PitchPreFilter(rate, PitchPreFilter.cornerFor(NesOptions().huntMaxHz))
    private val holdLock = ScopeLock(
        rate,
        ScopeLaneSpec("Mix", 30f, 1000f, pitchWindowFor(rate, 30f), LaneTrigger.PITCH),
    )
    private val tracker = TriangleTracker(rate)
    private val mono = FloatArray(8192)
    private val lowScratch = FloatArray(8192)
    private val holdRaw = FloatArray(LANE_HISTORY)
    private val holdPitched = FloatArray(LANE_HISTORY)
    private val nesRaw = FloatArray(TriangleTracker.HISTORY)
    private val nesPitched = FloatArray(TriangleTracker.HISTORY)
    private var nesRegion = NesOptions().region

    private var sinceScopeMs = 0f
    private var wasHold = false
    private var wasNes = false
    private var wasStems = false

    @Volatile var latest: ScopeFrame? = null
        private set
    @Volatile var latestNes: NesReading? = null
        private set

    /**
     * One capture block. [right] is null for a mono source; both at the
     * engine's own rate.
     */
    fun process(left: FloatArray, right: FloatArray?, frames: Int, wants: Wants, s: Settings, blockMs: Float) {
        feedStems(left, right ?: left, frames, wants.stems, s, blockMs)

        if (wants.hold || wants.nes) {
            val n = frames.coerceAtMost(mono.size)
            if (right != null) {
                for (i in 0 until n) mono[i] = 0.5f * (left[i] + right[i])
            } else {
                System.arraycopy(left, 0, mono, 0, n)
            }
            // All three rings are written together or not at all. They are read
            // at one shared absolute index, and a ring that skipped the blocks
            // its mode was off for would sit permanently behind the others —
            // switching from 2A03 to "hold still" then never drew anything.
            mixRing.write(mono, 0, n)
            for (i in 0 until n) {
                var v = mono[i].toDouble()
                for (f in holdFilter) v = f.process(v)
                lowScratch[i] = v.toFloat()
            }
            holdLpRing.write(lowScratch, 0, n)
            if (s.nesRegion != nesRegion) {
                nesRegion = s.nesRegion
                tracker.reset()
            }
            nesFilter.process(mono, lowScratch, n)
            nesLpRing.write(lowScratch, 0, n)
        }

        if (wants.hold != wasHold) { holdLock.reset(); wasHold = wants.hold }
        if (wants.nes != wasNes) { tracker.reset(); latestNes = null; wasNes = wants.nes }
        if (wants.stems != wasStems) { stemLocks.values.forEach { it.reset() }; wasStems = wants.stems }

        if (!wants.any) {
            latest = null
            latestNes = null
            sinceScopeMs = 0f
            return
        }

        sinceScopeMs += blockMs
        if (sinceScopeMs < SCOPE_INTERVAL_MS) return
        val dt = sinceScopeMs
        sinceScopeMs = 0f

        val lanes = if (wants.stems) stemLanes(s, dt) else emptyList()
        val info = if (wants.stems) stemsInfo() else null
        val hold = if (wants.hold && mixRing.written > 0) {
            val end = minOf(mixRing.written, holdLpRing.written)
            if (mixRing.read(end, holdRaw, holdRaw.size) && holdLpRing.read(end, holdPitched, holdPitched.size)) {
                holdLock.analyze(holdRaw, holdPitched, end, s.scopeWindowMs, s.scopeCleanMs, POINTS, dt)
            } else {
                null
            }
        } else {
            null
        }
        latest = ScopeFrame(lanes, info, hold)

        if (wants.nes) {
            val end = minOf(mixRing.written, nesLpRing.written)
            latestNes = if (mixRing.read(end, nesRaw, nesRaw.size) && nesLpRing.read(end, nesPitched, nesPitched.size)) {
                tracker.analyze(nesRaw, nesPitched, dt, NesOptions(region = s.nesRegion))
            } else {
                null
            }
        } else {
            latestNes = null
        }
    }

    private fun feedStems(left: FloatArray, right: FloatArray, frames: Int, wanted: Boolean, s: Settings, blockMs: Float) {
        if (!wanted) {
            val sep = separator ?: return
            stemsIdleMs += blockMs
            if (stemsIdleMs >= STEMS_LINGER_MS) {
                sep.stop()
                separator = null
            }
            return
        }
        stemsIdleMs = 0f

        var sep = separator
        // A different thread count is a different session. A failure, on the
        // other hand, stays on screen: retrying a model that just failed to
        // load would only fail again, every block.
        if (sep != null && separatorThreads != s.stemThreads) {
            sep.stop()
            separator = null
            sep = null
        }
        if (sep == null && loadError == null) {
            sep = try {
                StemSeparator(modelFile(), s.stemThreads, hooks()).also { it.start() }
            } catch (t: Throwable) {
                loadError = "The stem model is missing from this build: ${t.message ?: t.javaClass.simpleName}"
                null
            }
            separator = sep
            separatorThreads = s.stemThreads
            resampler.reset()
        }
        if (sep == null || !sep.isRunning) return

        val need = resampler.maxOutput(frames)
        if (rsL.size < need) {
            rsL = FloatArray(need)
            rsR = FloatArray(need)
        }
        val out = resampler.process(left, right, frames, rsL, rsR)
        sep.push(rsL, rsR, out)
    }

    private fun stemLanes(s: Settings, dt: Float): List<StemLane> {
        val sep = separator ?: return emptyList()
        if (sep.status !is StemSeparator.Status.Running) return emptyList()
        return laneOrder.map { stem ->
            val raw = sep.stems[stem.ordinal]
            val low = sep.pitched[stem.ordinal]
            val end = minOf(raw.written, low.written)
            val lock = stemLocks.getValue(stem)
            val trace = if (end > 0 && raw.read(end, laneRaw, LANE_HISTORY) && low.read(end, lanePitched, LANE_HISTORY)) {
                lock.analyze(laneRaw, lanePitched, end, s.scopeWindowMs, s.scopeCleanMs, POINTS, dt, sep.cleanFrom)
            } else {
                com.n3d.spectra.dsp.ScopeTrace.quiet(POINTS, s.scopeWindowMs, -120f)
            }
            StemLane(stem.label, trace)
        }
    }

    private fun stemsInfo(): StemsInfo {
        loadError?.let { return StemsInfo(StemsState.FAILED, it, 0f, 0, 0f) }
        return when (val st = separator?.status) {
            is StemSeparator.Status.Running -> StemsInfo(StemsState.RUNNING, null, st.load, st.skips, st.msPerHop)
            is StemSeparator.Status.Failed -> StemsInfo(StemsState.FAILED, st.message, 0f, 0, 0f)
            else -> StemsInfo(StemsState.LOADING, null, 0f, 0, 0f)
        }
    }

    fun reset() {
        stemLocks.values.forEach { it.reset() }
        holdLock.reset()
        tracker.reset()
        latestNes = null
    }

    fun release() {
        separator?.stop()
        separator = null
        latest = null
        latestNes = null
    }

    companion object {
        /** Samples of each lane handed to its lock: 370 ms at 44.1 kHz. */
        const val LANE_HISTORY = 16_384
        const val POINTS = 512
        /** Scope refresh. Blocks arrive about every 21 ms; this is one per block. */
        private const val SCOPE_INTERVAL_MS = 15f
        private const val STEMS_LINGER_MS = 20_000f
        private const val HOLD_CORNER_HZ = 1200.0

        /** A pitch window that holds two periods of [minHz], rounded up to a power of two. */
        fun pitchWindowFor(rate: Int, minHz: Float): Int {
            val need = (2.2f * rate / minHz).toInt()
            var n = 1024
            while (n < need) n *= 2
            return n
        }

        fun specFor(stem: Stem): ScopeLaneSpec {
            val sr = StemSeparator.SAMPLE_RATE
            return when (stem) {
                Stem.VOCALS -> ScopeLaneSpec(stem.label, 80f, 1000f, pitchWindowFor(sr, 80f), LaneTrigger.PITCH)
                Stem.OTHER -> ScopeLaneSpec(stem.label, 50f, 1000f, pitchWindowFor(sr, 50f), LaneTrigger.PITCH)
                Stem.BASS -> ScopeLaneSpec(stem.label, 28f, 300f, pitchWindowFor(sr, 28f), LaneTrigger.PITCH)
                Stem.DRUMS -> ScopeLaneSpec(stem.label, 40f, 400f, 1024, LaneTrigger.TRANSIENT)
            }
        }
    }
}
