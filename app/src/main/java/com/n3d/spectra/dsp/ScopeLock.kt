package com.n3d.spectra.dsp

import com.n3d.spectra.dsp.nes.Nes2A03
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** How a lane decides where its picture starts. */
enum class LaneTrigger {
    /** Find the note's period and start every frame at the same point of it. */
    PITCH,
    /** No note to find (drums): freeze on each hit until the next one. */
    TRANSIENT,
}

/** What one lane of a held-still scope listens for. */
class ScopeLaneSpec(
    val name: String,
    val minHz: Float,
    val maxHz: Float,
    /** Samples the pitch detector looks at. Must hold two periods of [minHz]. */
    val pitchWindow: Int,
    val trigger: LaneTrigger,
)

/** What a trace on screen is doing, for the painter's labels. */
enum class TraceMode {
    /** Locked to a detected note — the picture stands still while the note is held. */
    PITCH,
    /** Frozen on the most recent hit. */
    HIT,
    /** Nothing to lock to; showing the newest audio as it arrives. */
    FREE,
    /** Below the level where a picture would be amplified noise. */
    QUIET,
}

/**
 * One frame of one held-still lane, ready to draw.
 *
 * [points] span the whole time window left to right and are already scaled to
 * about ±1 by an automatic gain, because a stem's level says nothing about its
 * shape and a quiet vocal should be as legible as a loud kick. [levelDb] is the
 * real level, for the readout.
 */
class ScopeTrace(
    val points: FloatArray,
    val windowMs: Float,
    val mode: TraceMode,
    val hz: Float,
    val note: String,
    val clarity: Float,
    val levelDb: Float,
    /** Periods averaged into the picture (1 = raw audio). */
    val folded: Int,
    /** True while this is a held copy of an older lock. */
    val stale: Boolean,
) {
    companion object {
        fun quiet(points: Int, windowMs: Float, levelDb: Float) =
            ScopeTrace(FloatArray(points), windowMs, TraceMode.QUIET, 0f, "", 0f, levelDb, 1, false)
    }
}

/**
 * Holds a waveform still, the way the channel scopes in chiptune videos do.
 *
 * The time window is fixed, so a higher note shows more, narrower cycles and a
 * lower one fewer, wider ones — the picture squeezes and stretches with the
 * melody instead of scrolling. What keeps it still is the anchor: the centre of
 * the window always sits on the same point of the note's cycle.
 *
 *  1. **Period.** McLeod's NSDF on a low-passed copy ([PitchDetector]).
 *  2. **Phase.** The argument of a single DFT bin at the fundamental, taken over
 *     several whole periods. It is continuous — a threshold trigger would add a
 *     sample of jitter every frame — and averaging over periods keeps whatever
 *     else is still in the lane from dragging the picture sideways.
 *  3. **Clean-up.** Optionally, every point on screen is the average of the
 *     same point in each period of the last `cleanMs`. That is a comb filter
 *     with its teeth on the note's harmonics: the note survives intact, corners
 *     and all, and anything not locked to it (the leftovers of the other stems)
 *     falls by √periods. Measured in time, not in cycles, so a high note and a
 *     low one are cleaned alike and follow a melody equally fast; the depth
 *     ramps back up after each note change, so a moving bass line is never
 *     smeared across two notes.
 *
 * Drums have no period, so their lane uses a trigger instead: an energy jump
 * marks a hit, and the window stays on that hit — frozen — until the next one.
 *
 * Not thread safe; the analysis thread owns one per lane.
 */
class ScopeLock(private val sampleRate: Int, val spec: ScopeLaneSpec) {

    private val detector = PitchDetector(sampleRate, spec.pitchWindow)

    private var gain = 1f
    private var previous: FloatArray? = null
    private var previousTau = -1.0
    private var lastTau = -1.0
    private var sinceChangeMs = 0f
    private var held: ScopeTrace? = null
    private var heldMs = 0f

    // Transient trigger state, all in absolute sample indices.
    private var scannedTo = Long.MIN_VALUE
    private var slowEnergy = 0.0
    private var onset = Long.MIN_VALUE
    private var previousOnset = Long.MIN_VALUE

    fun reset() {
        gain = 1f
        previous = null
        previousTau = -1.0
        lastTau = -1.0
        sinceChangeMs = 0f
        held = null
        heldMs = 0f
        scannedTo = Long.MIN_VALUE
        slowEnergy = 0.0
        onset = Long.MIN_VALUE
        previousOnset = Long.MIN_VALUE
    }

    /**
     * @param raw     the lane's newest audio, oldest first.
     * @param pitched the same audio low-passed for the detector, sample aligned.
     * @param end     absolute index one past `raw.last()`.
     * @param windowMs width of the picture.
     * @param cleanMs how much of the note's recent past to average in, 0 = off.
     * @param points  resolution of the trace handed to the painter.
     * @param dtMs    time since the previous call.
     * @param validFrom absolute index of the first sample worth measuring. The
     *                stem separator moves it forward whenever it resets, so a
     *                period is never measured across the seam.
     */
    fun analyze(
        raw: FloatArray,
        pitched: FloatArray,
        end: Long,
        windowMs: Float,
        cleanMs: Float,
        points: Int,
        dtMs: Float,
        validFrom: Long = Long.MIN_VALUE,
    ): ScopeTrace {
        val n = raw.size
        val firstValid = if (validFrom == Long.MIN_VALUE) 0 else (validFrom - (end - n)).coerceIn(0L, n.toLong()).toInt()
        val w = (windowMs * sampleRate / 1000f).toDouble().coerceIn(16.0, n / 2.0)
        val count = points.coerceIn(16, max(16, w.toInt()))
        val newest = n - 1 - EDGE

        var sum = 0.0
        val w0 = (newest - w).toInt().coerceAtLeast(0)
        for (i in w0 until newest) sum += raw[i].toDouble() * raw[i]
        val rms = sqrt(sum / max(1, newest - w0))
        val levelDb = if (rms <= 1e-9) -120f else (20.0 * log10(rms)).toFloat()

        // Every lane tracks hits, not just the drums: a pitched lane that loses
        // its note falls back to the last hit rather than to a scrolling wave.
        scanOnsets(raw, end)

        if (levelDb < QUIET_DB) {
            previous = null
            held = null
            lastTau = -1.0
            return ScopeTrace.quiet(count, windowMs, levelDb)
        }

        if (spec.trigger == LaneTrigger.PITCH) {
            // Too little clean audio since a reset to measure a period on: keep
            // the last good picture a while longer instead of guessing.
            if (n - firstValid < spec.pitchWindow + EDGE) {
                heldMs += dtMs
                val h = held
                if (h != null && heldMs <= SETTLE_HOLD_MS) return h.asStale()
                return triggered(raw, end, w, count, windowMs, levelDb)
            }
            val tau = detector.detect(pitched, spec.minHz, spec.maxHz)
            if (tau > 0.0 && detector.clarity >= CLARITY) {
                heldMs = 0f
                return pitchLocked(raw, tau, w, count, cleanMs, windowMs, levelDb, dtMs, firstValid).also { held = it }
            }
            // A breath, a consonant, the gap between two notes: keep the last
            // lock on screen briefly rather than let the picture jump to noise.
            heldMs += dtMs
            val h = held
            if (h != null && heldMs <= HOLD_MS) return h.asStale()
            held = null
        }

        return triggered(raw, end, w, count, windowMs, levelDb)
    }

    // ---- pitch lock --------------------------------------------------------

    private fun pitchLocked(
        raw: FloatArray,
        tau: Double,
        w: Double,
        count: Int,
        cleanMs: Float,
        windowMs: Float,
        levelDb: Float,
        dtMs: Float,
        firstValid: Int,
    ): ScopeTrace {
        val n = raw.size
        val newest = (n - 1 - EDGE).toDouble()

        // Note-change bookkeeping for the fold ramp.
        if (lastTau <= 0.0 || abs(tau / lastTau - 1.0) > NOTE_CHANGE) {
            sinceChangeMs = 0f
        } else {
            sinceChangeMs += dtMs
        }
        lastTau = tau
        val periodMs = (tau * 1000.0 / sampleRate).toFloat()
        val elapsedPeriods = floor(sinceChangeMs / periodMs).toInt() + 1
        val foldSetting = floor(cleanMs / periodMs).toInt().coerceIn(1, MAX_FOLD)

        // Phase of the fundamental over several whole periods ending at the
        // newest sample. Whole periods, so the bin's own image cancels.
        val phasePeriods = max(2, min(foldSetting, 4))
        val len = min((phasePeriods * tau).toInt(), (newest - firstValid).toInt())
        val i0 = (newest - len).toInt().coerceAtLeast(0)
        // The bin's rotation is stepped by complex multiplication rather than
        // a cos and a sin per sample: thousands of samples, four lanes, every
        // frame. Double precision keeps the drift far below anything visible.
        val omega = 2.0 * Math.PI / tau
        val stepRe = cos(omega)
        val stepIm = sin(omega)
        var rotRe = 1.0
        var rotIm = 0.0
        var c = 0.0
        var s = 0.0
        for (i in 0 until len) {
            val v = raw[i0 + i].toDouble()
            c += v * rotRe
            s -= v * rotIm
            val nr = rotRe * stepRe - rotIm * stepIm
            rotIm = rotRe * stepIm + rotIm * stepRe
            rotRe = nr
        }
        // A cosine peak of the fundamental; every other one is a whole period away.
        val peak0 = i0 - tau * atan2(s, c) / (2.0 * Math.PI)
        val half = w / 2.0
        val k = floor((newest - half - peak0) / tau)
        val anchor = peak0 + k * tau
        val start = anchor - half

        // How many periods can be averaged: the setting, the ramp after a note
        // change, and the history that is actually there.
        val available = floor((start - firstValid) / tau).toInt() + 1
        val folds = foldSetting.coerceAtLeast(1)
            .coerceAtMost(elapsedPeriods)
            .coerceAtMost(max(1, available))

        val out = FloatArray(count)
        val step = w / (count - 1)
        var peak = 0f
        val inv = 1f / folds
        for (i in 0 until count) {
            val t = start + i * step
            var v = 0f
            for (j in 0 until folds) v += sampleAt(raw, t - j * tau)
            v *= inv
            out[i] = v
            peak = max(peak, abs(v))
        }

        // Blend with the previous frame when the note has not moved. Both are
        // anchored to the same phase, so this only softens what is *not* the
        // note — the wobble of leftover bleed — and never smears the shape.
        val prev = previous
        if (prev != null && prev.size == count && previousTau > 0.0 &&
            abs(tau / previousTau - 1.0) < SAME_NOTE
        ) {
            for (i in 0 until count) out[i] = prev[i] * PERSISTENCE + out[i] * (1f - PERSISTENCE)
        }
        previous = out.copyOf()
        previousTau = tau

        val hz = (sampleRate / tau).toFloat()
        val scaled = normalise(out, peak, dtMs)
        return ScopeTrace(
            scaled, windowMs, TraceMode.PITCH, hz, Nes2A03.noteName(hz.toDouble()),
            detector.clarity, levelDb, folds, false,
        )
    }

    // ---- transient trigger -------------------------------------------------

    /**
     * Walks the audio that arrived since the last call in short blocks, marking
     * a hit wherever the block energy jumps well above its own recent average.
     */
    private fun scanOnsets(raw: FloatArray, end: Long) {
        val n = raw.size
        val first = end - n
        val block = max(8, sampleRate * BLOCK_MS / 1000)
        var from = if (scannedTo == Long.MIN_VALUE) end - block * 8L else max(scannedTo, first)
        val alpha = 1.0 - exp(-BLOCK_MS / SLOW_MS)
        val holdoff = sampleRate * HOLDOFF_MS / 1000L
        while (from + block <= end) {
            val base = (from - first).toInt()
            var e = 0.0
            for (i in 0 until block) {
                val v = raw[base + i].toDouble()
                e += v * v
            }
            e /= block
            val clear = onset == Long.MIN_VALUE || from - onset > holdoff
            if (clear && e > slowEnergy * ONSET_RATIO && e > ONSET_FLOOR) {
                previousOnset = onset
                onset = from
            }
            slowEnergy += (e - slowEnergy) * alpha
            from += block
        }
        scannedTo = from
    }

    private fun triggered(
        raw: FloatArray,
        end: Long,
        w: Double,
        count: Int,
        windowMs: Float,
        levelDb: Float,
    ): ScopeTrace {
        previous = null
        val n = raw.size
        val first = end - n
        val newest = n - 1 - EDGE
        val pre = w * PRE_TRIGGER

        // The newest hit whose whole window has arrived; failing that the one
        // before it, which is what stays on screen while a new hit fills in.
        var start = -1.0
        for (o in longArrayOf(onset, previousOnset)) {
            if (o == Long.MIN_VALUE) continue
            val s = (o - first) - pre
            if (s >= 0.0 && s + w <= newest && end - o <= MAX_HIT_AGE * sampleRate / 1000) {
                start = s
                break
            }
        }
        val mode = if (start >= 0.0) TraceMode.HIT else TraceMode.FREE
        if (start < 0.0) start = (newest - w).coerceAtLeast(0.0)

        val out = FloatArray(count)
        val step = w / (count - 1)
        var peak = 0f
        for (i in 0 until count) {
            val v = sampleAt(raw, start + i * step)
            out[i] = v
            peak = max(peak, abs(v))
        }
        return ScopeTrace(normalise(out, peak, 0f), windowMs, mode, 0f, "", 0f, levelDb, 1, false)
    }

    // ---- shared ------------------------------------------------------------

    /**
     * Automatic gain: instant when the picture would clip, slow when it would
     * grow, so a crescendo never runs off the lane and a quiet bar does not
     * pump. Capped, so a lane that is nearly silent is not blown up into fuzz.
     */
    private fun normalise(v: FloatArray, peak: Float, dtMs: Float): FloatArray {
        val target = if (peak <= 1e-6f) MAX_GAIN else (FILL / peak).coerceAtMost(MAX_GAIN)
        gain = if (target < gain || dtMs <= 0f) target else {
            gain + (target - gain) * (1f - exp(-dtMs / GAIN_RELEASE_MS))
        }
        for (i in v.indices) v[i] = (v[i] * gain).coerceIn(-1.2f, 1.2f)
        return v
    }

    private fun ScopeTrace.asStale() = ScopeTrace(
        points, windowMs, mode, hz, note, clarity, levelDb, folded, true,
    )

    /** Linear interpolation, clamped at both ends — the same thing a scope does. */
    private fun sampleAt(x: FloatArray, pos: Double): Float {
        if (pos <= 0.0) return x[0]
        val i = pos.toInt()
        if (i >= x.size - 1) return x[x.size - 1]
        val t = (pos - i).toFloat()
        return x[i] * (1f - t) + x[i + 1] * t
    }

    private companion object {
        /** Samples kept clear of the newest one, for interpolation. */
        const val EDGE = 2
        const val CLARITY = 0.6f
        const val QUIET_DB = -62f
        const val HOLD_MS = 220f
        /** How long to keep a picture while the separator re-learns after a reset. */
        const val SETTLE_HOLD_MS = 600f
        /** A period change bigger than this is a new note: restart the fold ramp. */
        const val NOTE_CHANGE = 0.03
        /** …and smaller than this is the same note, safe to blend across frames. */
        const val SAME_NOTE = 0.01
        /**
         * Cross-frame smoothing while a note is held. Half and half: frames are
         * ~20 ms apart, so this is another ~40 ms of averaging for the bleed
         * and none at all across a note change, where it is switched off.
         */
        const val PERSISTENCE = 0.5f
        const val MAX_FOLD = 32
        const val FILL = 0.9f
        /** 36 dB. Anything quieter than about −60 dBFS stays small. */
        const val MAX_GAIN = 63f
        const val GAIN_RELEASE_MS = 280f

        const val BLOCK_MS = 3
        const val SLOW_MS = 140.0
        const val ONSET_RATIO = 3.2
        const val ONSET_FLOOR = 1e-6
        const val HOLDOFF_MS = 45
        const val PRE_TRIGGER = 0.08
        const val MAX_HIT_AGE = 1500
    }
}
