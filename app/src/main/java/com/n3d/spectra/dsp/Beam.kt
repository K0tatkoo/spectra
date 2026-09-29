package com.n3d.spectra.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Paces the Oscilloscope page's beam through the capture.
 *
 * Audio arrives in bursts — 1024 frames every 21 ms on the phone, 2048 on
 * Windows — while a surface draws on its own clock, sixty or more times a
 * second. Drawing "whatever arrived since the last frame" would hand one frame
 * a whole burst and the next nothing, and the picture would pulse between the
 * two. So the beam runs a little behind the newest sample and moves through the
 * audio exactly as fast as time passes, like a jitter buffer: each frame draws
 * an even slice, the slices meet end to end, and every sample is drawn once.
 *
 * The lag is measured rather than assumed — a little more than the largest
 * burst seen lately — so one clock suits both captures. Only a surface that
 * draws faster than 20 fps measures it: the widget, at 2 fps, sees whole
 * bursts pile up between its frames and would learn nothing true from them.
 */
class BeamClock {

    private var head = Double.NaN
    private var rate = 0
    private var lastNanos = 0L
    private var lastWritten = 0L
    private var burst = 0.0

    /** The capture to draw this frame: absolute frames [from, to). */
    var from = 0L
        private set
    var to = 0L
        private set

    /**
     * True when this slice does not continue the last one — the first frame,
     * a stall long enough to fall far behind, a new sample rate. Whatever is on
     * the tube belongs to another moment and should go.
     */
    var jumped = true
        private set

    /** How far behind the newest sample the beam aims to run, in frames. */
    val targetLag: Double
        get() = (burst * 1.25 + rate * SLACK_S).coerceIn(rate * MIN_LAG_S, rate * MAX_TARGET_S)

    fun advance(written: Long, sampleRate: Int, nowNanos: Long) {
        // The newest frame the interpolator can finish: it needs a few after it.
        val limit = (written - BeamInterpolator.CONTEXT).toDouble()
        if (head.isNaN() || sampleRate != rate || written < lastWritten) {
            rate = sampleRate
            burst = sampleRate * FIRST_BURST_S
            head = maxOf(0.0, minOf(written - targetLag, limit))
            lastNanos = nowNanos
            lastWritten = written
            from = floor(head).toLong()
            to = from
            jumped = true
            return
        }

        val elapsed = ((nowNanos - lastNanos) * 1e-9).coerceIn(0.0, MAX_STEP_S)
        lastNanos = nowNanos
        val arrived = written - lastWritten
        lastWritten = written
        if (arrived > 0 && elapsed < FAST_SURFACE_S) burst = maxOf(arrived.toDouble(), burst * BURST_DECAY)

        var next = head + elapsed * rate
        val lag = written - next
        jumped = lag > rate * MAX_LAG_S
        if (jumped) {
            next = written - targetLag
        } else {
            // Ease towards the target instead of snapping to it: a correction
            // spread over a second is invisible, one made in a frame is a hiccup.
            next += (lag - targetLag) * CATCH_UP
        }
        if (next > limit) next = limit
        if (next < head && !jumped) next = head
        from = if (jumped) floor(next).toLong() else floor(head).toLong()
        head = next
        to = maxOf(from, floor(head).toLong())
    }

    fun reset() {
        head = Double.NaN
    }

    private companion object {
        /** What the phone's capture delivers per read; replaced by what is measured. */
        const val FIRST_BURST_S = 0.022
        const val SLACK_S = 0.006
        const val MIN_LAG_S = 0.010
        const val MAX_TARGET_S = 0.150
        /** Further behind than this and the beam jumps to the present rather than catching up. */
        const val MAX_LAG_S = 0.300
        /** A frame longer than this is a surface that was away, not a slow frame. */
        const val MAX_STEP_S = 1.0
        const val FAST_SURFACE_S = 0.050
        /** Forgets a big burst over about ten seconds at 60 fps. */
        const val BURST_DECAY = 0.998
        const val CATCH_UP = 0.02
    }
}

/**
 * Band-limited 4× interpolation of the capture: the path a real beam takes
 * behind a DAC, rather than straight lines from sample to sample.
 *
 * At 48 kHz a figure drawn at 200 Hz has 240 samples to its whole outline, and
 * joined with straight lines its curves come out as polygons. A converter's
 * reconstruction filter is what makes a real tube draw them smooth — and what
 * makes a sharp corner ring a little — so the beam is interpolated the same
 * way: a Kaiser-windowed sinc, four points to every sample, which also puts it
 * at the 192 kHz that oscilloscope music is mastered at.
 *
 * Stateless: every call is given the samples either side of what it draws, so
 * a surface can start and stop anywhere in the capture.
 */
class BeamInterpolator {

    private val taps = HALF_TAPS * 2
    private val kernel = FloatArray(FACTOR * taps)

    init {
        val i0Beta = besselI0(KAISER_BETA)
        for (p in 0 until FACTOR) {
            val frac = p.toDouble() / FACTOR
            var sum = 0.0
            for (k in 0 until taps) {
                // Offset of input sample k from the output instant, in samples.
                val t = (k - HALF_TAPS + 1) - frac
                val x = CUTOFF * t
                val sinc = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
                val r = t / HALF_TAPS
                val w = if (r * r >= 1.0) 0.0 else besselI0(KAISER_BETA * sqrt(1.0 - r * r)) / i0Beta
                val v = CUTOFF * sinc * w
                kernel[p * taps + k] = v.toFloat()
                sum += v
            }
            // Unity DC gain in every phase, or a still beam would buzz between
            // four slightly different places.
            for (k in 0 until taps) kernel[p * taps + k] = (kernel[p * taps + k] / sum).toFloat()
        }
    }

    /**
     * Interpolates [count] samples of [src] from [start]: writes [FACTOR] points
     * per sample into [dst], sample i's own instant first and then the three
     * after it. [src] must hold [CONTEXT] samples before [start] and [CONTEXT]
     * after the last one.
     */
    fun interpolate(src: FloatArray, start: Int, count: Int, dst: FloatArray) {
        var o = 0
        for (i in 0 until count) {
            val base = start + i - HALF_TAPS + 1
            for (p in 0 until FACTOR) {
                val k0 = p * taps
                var acc = 0f
                for (k in 0 until taps) acc += src[base + k] * kernel[k0 + k]
                dst[o++] = acc
            }
        }
    }

    companion object {
        const val FACTOR = 4
        private const val HALF_TAPS = 8
        /** Samples needed on each side of what is interpolated. */
        const val CONTEXT = HALF_TAPS
        /** Just under the capture's Nyquist, where a converter's own filter sits. */
        private const val CUTOFF = 0.94
        private const val KAISER_BETA = 8.0
    }
}

/**
 * The Y-T time base, triggered the way a bench scope's AUTO mode is: a sweep
 * starts where the signal next crosses zero going up, and if nothing crosses
 * for a while it sweeps anyway, so that silence still draws its flat line.
 *
 * Two concessions to music. The trigger listens to a low-passed copy, cornered
 * by the time base: a mix crosses zero many times a period, once for every
 * loud harmonic, and each of those is a different place to start — low-passed,
 * it crosses once, on whatever is lowest and loudest on screen. And the
 * crossing is found between samples: at 0.1 ms per division a sample is a fifth
 * of one, and a sweep snapped to whole samples would make the trace shiver.
 */
class BeamSweep {

    private var lp: Biquad? = null
    private var lpRate = 0
    private var lpCorner = 0.0
    private var release = 0.0
    private var prev = 0.0
    private var envelope = 0.0
    private var armed = false
    private var waited = 0.0
    private var start = 0.0

    /** A sweep is crossing the screen; the beam is lit. */
    var sweeping = false
        private set

    /** The sweep on screen was started by the signal, not by the AUTO timer. */
    var triggered = false
        private set

    /** Where the latest sweep started, in absolute frames. */
    var lastStart = Double.NaN
        private set

    /** Sets the trigger's low-pass for [sweepFrames] of sweep at [rate]. Cheap to call every frame. */
    fun configure(rate: Int, sweepFrames: Double) {
        val corner = (TRIGGER_CYCLES * rate / sweepFrames).coerceIn(MIN_CORNER_HZ, MAX_CORNER_HZ)
        if (rate != lpRate || corner != lpCorner) {
            lp = Biquad.lowPass(rate, corner)
            lpRate = rate
            lpCorner = corner
            release = exp(-1.0 / (rate * ENVELOPE_RELEASE_S))
            prev = 0.0
        }
    }

    /**
     * Frame [n] of the mix, [v]. Starts a sweep when it crosses, or when
     * [autoFrames] have gone by without one.
     */
    fun step(n: Long, v: Float, autoFrames: Double) {
        val y = lp?.process(v.toDouble()) ?: v.toDouble()
        val a = abs(y)
        envelope = if (a > envelope) a else envelope * release
        if (!sweeping) {
            // Re-armed only once the signal has been properly below zero, so
            // noise around the crossing cannot start a second sweep.
            if (y < -envelope * HYSTERESIS) armed = true
            waited += 1.0
            if (armed && prev < 0.0 && y >= 0.0 && envelope > MIN_LEVEL) {
                begin(n - 1 + prev / (prev - y), true)
            } else if (waited >= autoFrames) {
                begin(n.toDouble(), false)
            }
        }
        prev = y
    }

    /** Position across the screen, 0 to 1, of the instant [t] (absolute frames). */
    fun x(t: Double, sweepFrames: Double): Double = (t - start) / sweepFrames

    /** The sweep reached the right-hand edge: retrace, and wait for the next one. */
    fun end() {
        sweeping = false
        waited = 0.0
    }

    fun reset() {
        lp?.reset()
        prev = 0.0
        envelope = 0.0
        armed = false
        waited = 0.0
        sweeping = false
        triggered = false
    }

    private fun begin(at: Double, byTrigger: Boolean) {
        sweeping = true
        triggered = byTrigger
        start = at
        lastStart = at
        armed = false
        waited = 0.0
    }

    private companion object {
        /** The trigger's corner sits this many cycles per sweep up. */
        const val TRIGGER_CYCLES = 3.0
        const val MIN_CORNER_HZ = 40.0
        const val MAX_CORNER_HZ = 2000.0
        const val ENVELOPE_RELEASE_S = 0.3
        const val HYSTERESIS = 0.1
        /** −80 dBFS: below this there is nothing to trigger on. */
        const val MIN_LEVEL = 1e-4
    }
}
