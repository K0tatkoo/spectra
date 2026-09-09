package com.n3d.spectra.desktop.nes

import com.n3d.spectra.dsp.Fft
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** How the 2A03 waveform view behaves. All of it is display, none of it is capture. */
data class NesOptions(
    val region: Nes2A03.Region = Nes2A03.Region.NTSC,
    /** Snap the display period to the nearest 2A03 timer value. This is what makes it stand still. */
    val lockToTimer: Boolean = true,
    /** Periods drawn across the plot. */
    val cycles: Int = 2,
    val huntMinHz: Float = 25f,
    val huntMaxHz: Float = 200f,
    /** NSDF peak below which the detection is not trusted. */
    val clarityThreshold: Float = 0.55f,
    /** Fold every period in the window onto one. Rejects the other four channels. */
    val averaging: Boolean = true,
    /**
     * How many periods to fold together. Rejection of anything not harmonically
     * locked to the note goes as √n, so 8 is about 9 dB — enough to lift the
     * triangle out from under a melody, while still following a bass line that
     * changes note every eighth.
     */
    val foldPeriods: Int = 8,
    /** Cross-frame smoothing of the folded period, 0…0.95. */
    val persistence: Float = 0.55f,
    val showIdeal: Boolean = true,
    val showLevels: Boolean = true,
    val showSteps: Boolean = true,
    /** Snap the trace to the sixteen DAC levels the chip can actually output. */
    val quantizeToDac: Boolean = false,
    /** Mark the individual captured samples, so the time grid is visible too. */
    val showSamples: Boolean = true,
    /** Keep the last good lock on screen this long after the signal goes. */
    val holdMs: Float = 900f,
)

/**
 * One frame of 2A03 triangle analysis. Everything the painter needs, already in
 * chip units — phase 0…1, amplitude normalised to the DAC's own ±1.
 */
class NesReading(
    val locked: Boolean,
    /** True while this is a held copy of an older lock rather than a live one. */
    val stale: Boolean,
    val region: Nes2A03.Region,
    /** Measured fundamental, Hz. */
    val hz: Double,
    /** The 11-bit timer that would produce it. */
    val timer: Int,
    /** What that timer actually plays. */
    val nesHz: Double,
    val note: String,
    /** Cents from [nesHz] to equal temperament. */
    val cents: Double,
    /** Cents between the measurement and the chip's grid — large means this is not a 2A03. */
    val gridCents: Double,
    val samplesPerStep: Double,
    val periodSamples: Double,
    /** Cents between this timer and the next one — how much the chip's grid is worth here. */
    val gridStepCents: Double,
    /** Periods actually folded. Below the setting while a note is still new. */
    val foldedPeriods: Int,
    /** NSDF peak, 0…1. How periodic the input is. */
    val clarity: Float,
    /** Correlation with the ideal 32-step wave, −1…1. Dominated by the fundamental. */
    val match: Float,
    /**
     * Correlation with the ideal wave *after the fundamental is removed*, −1…1.
     *
     * This is the number that says "2A03" and [match] is not. A plain sine
     * correlates 0.991 with a triangle — the triangle's own fundamental carries
     * 99 % of its energy — so a high [match] proves only that something periodic
     * is there. What is left after the fundamental is subtracted is the staircase
     * and the odd-harmonic series, and that is the chip's signature.
     */
    val stepMatch: Float,
    /** Fitted amplitude of the triangle component, dBFS. */
    val levelDb: Float,
    /** One period folded and averaged, [GRID] points, normalised to ±1. Empty when not averaging. */
    val cycle: FloatArray,
    /** Raw samples across the drawn span, normalised to ±1. */
    val live: FloatArray,
    /** Phase of `live[0]`, in periods. Usually a small negative number. */
    val livePhase0: Float,
    val cycles: Int,
    val audible: Boolean,
) {
    companion object {
        const val GRID = 2048
    }
}

/**
 * Finds a 2A03 triangle in captured audio and holds it still.
 *
 * Three problems have to be solved to draw a stationary staircase, and they are
 * solved in this order:
 *
 *  1. **What is the period?** An FFT-based normalised square-difference function
 *     (McLeod) over a low-passed copy of the input. Restricting the lag search to
 *     a bass hunt range is what stops the pulse channels' melody from winning.
 *  2. **What period would the chip have used?** The measurement is rounded to the
 *     nearest 11-bit timer value and the *exact* frequency of that timer is used
 *     for the display. This is the single thing that makes the picture stand
 *     still: a period taken from a measurement wanders by a fraction of a sample
 *     every frame and the wave crawls sideways, while a period taken from the
 *     timer grid is a constant for as long as the note is held. The distance
 *     between the two is reported rather than hidden — if it is large, the source
 *     is not a 2A03 and the lock is a lie.
 *  3. **Where does a period start?** The argument of a single DFT bin at the
 *     fundamental, which is continuous, so there is no quantisation to jitter,
 *     followed by a short parabolic search against the ideal wave to absorb the
 *     playback chain's phase shift and to resolve a polarity flip.
 *
 * Not thread safe; the analysis thread owns one.
 */
class TriangleTracker(private val sampleRate: Int) {

    private val fft = Fft(FFT_N)
    private val re = FloatArray(FFT_N)
    private val im = FloatArray(FFT_N)
    private val prefix = DoubleArray(PITCH_N + 1)
    private val nsdf = FloatArray(PITCH_N)

    /** The ideal wave sampled on the display grid, built once. */
    private val ideal = FloatArray(NesReading.GRID) {
        Nes2A03.levelAt((it + 0.5f) / NesReading.GRID)
    }
    private val idealEnergy: Float = ideal.sumOf { (it * it).toDouble() }.toFloat()

    /** Phase of the ideal wave's own fundamental. Zero by symmetry; measured, not assumed. */
    private val idealPhase: Double = fundamentalPhase(ideal)

    /** The ideal wave with DC and its own fundamental taken out — the chip's signature. */
    private val idealDetail = ideal.copyOf().also { stripFundamental(it) }
    private val idealDetailEnergy: Float = idealDetail.sumOf { (it * it).toDouble() }.toFloat()
    private val detail = FloatArray(NesReading.GRID)

    private val folded = FloatArray(NesReading.GRID)
    private val smoothed = FloatArray(NesReading.GRID)
    private var smoothedTimer = -1
    private var smoothedValid = false

    private var heldMs = 0f
    private var held: NesReading? = null

    /** Milliseconds since the timer last changed, for the fold-depth ramp. */
    private var sinceNoteChange = 0f
    private var lastTimer = -1

    fun reset() {
        smoothedValid = false
        smoothedTimer = -1
        held = null
        heldMs = 0f
        sinceNoteChange = 0f
        lastTimer = -1
    }

    /**
     * @param raw     full-rate mono history, oldest first, newest last.
     * @param pitched the same history low-passed for pitch detection.
     * @param dtMs    milliseconds since the previous call, for the hold timer.
     */
    fun analyze(raw: FloatArray, pitched: FloatArray, dtMs: Float, o: NesOptions): NesReading? {
        val n = raw.size
        if (n < PITCH_N + 16) return null

        val lag = detectPeriod(pitched, o)
        val clarity = lastClarity

        if (lag <= 0.0 || clarity < o.clarityThreshold) {
            // Hold the last good lock briefly. A triangle that is retriggering,
            // or a rest between notes, should not make the whole display jump.
            heldMs += dtMs
            val h = held
            if (h != null && heldMs <= o.holdMs) return h.asStale()
            reset()
            return null
        }
        heldMs = 0f

        val measuredHz = sampleRate / lag
        val timer = Nes2A03.timerFor(o.region, measuredHz)
        val nesHz = Nes2A03.frequency(o.region, timer)
        val period = if (o.lockToTimer) sampleRate / nesHz else lag
        if (period < 8.0) return null

        // Two spare periods of slack behind the drawn span: the phase correction
        // can move the window by half a period, the polarity search by another
        // half, and both have to stay inside the history that is actually there.
        val slack = 2.0 * period
        val cycles = o.cycles.coerceIn(1, 8)
            .coerceAtMost(floor((n - 2 - slack) / period).toInt())
        if (cycles < 1) return null
        val span = period * cycles

        var start = n - 2 - span - slack

        // How deep the fold is allowed to go *right now*.
        //
        // Folding sixteen periods together is worth nine decibels of rejection
        // and about a third of a second of audio, and a bass line that moves
        // every quarter note would spend a quarter of its life averaging two
        // different notes into one smeared picture. So the depth is capped at the
        // number of whole periods that have elapsed since the timer last changed
        // and grows back to the setting as the note is held. The count starts
        // when the *tracker* first sees the new timer, which is already after the
        // note began, so the cap only ever errs towards the newer audio.
        if (timer != lastTimer) {
            lastTimer = timer
            sinceNoteChange = 0f
        } else {
            sinceNoteChange += dtMs
        }
        val periodMs = period * 1000.0 / sampleRate
        val sinceChange = floor(sinceNoteChange / periodMs).toInt()
        val want = o.foldPeriods.coerceIn(1, MAX_FOLD).coerceAtMost(maxOf(1, sinceChange))
        start += phaseOffset(raw, start, period, foldWindow(n, start, period, want))
        start = refine(raw, start, period, want)
        start = start.coerceIn(0.0, (n - 2 - span).coerceAtLeast(0.0))

        return build(raw, start, period, cycles, measuredHz, timer, nesHz, clarity, o)
    }

    private fun build(
        raw: FloatArray,
        startIn: Double,
        period: Double,
        cycles: Int,
        measuredHz: Double,
        timer: Int,
        nesHz: Double,
        clarity: Float,
        o: NesOptions,
    ): NesReading {
        val grid = NesReading.GRID
        var start = startIn

        // Fold whole periods onto one grid. This is a comb filter with teeth
        // exactly on the harmonics of the note, which is why it can pull the
        // triangle out from under a pulse melody that no low-pass could separate
        // — and why the staircase survives it intact, corners and all.
        //
        // Periods *behind* the drawn window count as much as the ones inside it:
        // the signal is periodic, so folding backwards is free rejection, and
        // there are only two or three periods in front of `start` to work with.
        val want = o.foldPeriods.coerceIn(1, MAX_FOLD)
        var periods = fold(raw, start, period, want)

        // Fold once, read the fundamental's phase off the result, move the window
        // by exactly that, fold again.
        //
        // Everything upstream aligns on the fundamental, and that is not good
        // enough: a residual of one degree at the fundamental is forty degrees at
        // the 31st harmonic, which is where the staircase's corners live. The
        // trace would still sit on the ghost to the eye and every step edge would
        // be in the wrong place. The folded array is exactly one period on a
        // fixed grid, so its own fundamental phase is exact — no window
        // truncation, no leakage, nothing to bias it — and one correction from it
        // brings every harmonic into line at once.
        val drift = wrapPi(fundamentalPhase(folded) - idealPhase)
        val corrected = start + drift * period / (2.0 * Math.PI)
        if (corrected >= 0.0 && corrected + period * cycles < raw.size - 1) {
            start = corrected
            periods = fold(raw, start, period, want)
        }

        // Least-squares fit of the folded period to the chip's own wave. The
        // scale factor is the amplitude of the triangle component; the normalised
        // dot product is how much of what is on screen is actually a triangle.
        var dot = 0.0
        var energy = 0.0
        for (g in 0 until grid) {
            dot += folded[g] * ideal[g]
            energy += folded[g] * folded[g]
        }
        val amplitude = (dot / idealEnergy).toFloat()
        val match = if (energy <= 1e-12) 0f else (dot / sqrt(energy * idealEnergy)).toFloat()

        // The same correlation again with both fundamentals removed. See the note
        // on NesReading.stepMatch: this is the one that can tell a chip from a
        // sine, and it is computed before the amplitude normalisation so a quiet
        // note is not flattered by it.
        System.arraycopy(folded, 0, detail, 0, grid)
        stripFundamental(detail)
        var dDot = 0.0
        var dEnergy = 0.0
        for (g in 0 until grid) {
            dDot += detail[g] * idealDetail[g]
            dEnergy += detail[g] * detail[g]
        }
        val stepMatch =
            if (dEnergy <= 1e-14) 0f else (dDot / sqrt(dEnergy * idealDetailEnergy)).toFloat()
        // The 2A03 has no volume control, so amplitude carries no information and
        // normalising it away is free. Guard the divide: a rest fits amplitude 0.
        val norm = if (abs(amplitude) < 1e-6f) 0f else 1f / amplitude

        for (g in 0 until grid) folded[g] *= norm

        val outCycle: FloatArray
        if (o.averaging) {
            val alpha = if (smoothedValid && smoothedTimer == timer) 1f - o.persistence.coerceIn(0f, 0.95f) else 1f
            for (g in 0 until grid) {
                smoothed[g] = smoothed[g] * (1f - alpha) + folded[g] * alpha
            }
            smoothedValid = true
            smoothedTimer = timer
            outCycle = smoothed.copyOf()
        } else {
            smoothedValid = false
            outCycle = FloatArray(0)
        }

        val first = floor(start).toInt().coerceAtLeast(0)
        val count = (ceil(period * cycles).toInt() + 2).coerceAtMost(raw.size - first)
        val live = FloatArray(count)
        System.arraycopy(raw, first, live, 0, count)
        if (norm != 0f) for (i in live.indices) live[i] *= norm

        val samplesPerStep = Nes2A03.samplesPerStep(o.region, timer, sampleRate)
        val gridCents = 1200.0 * kotlin.math.ln(measuredHz / nesHz) / kotlin.math.ln(2.0)

        val reading = NesReading(
            locked = true,
            stale = false,
            region = o.region,
            hz = measuredHz,
            timer = timer,
            nesHz = nesHz,
            note = Nes2A03.noteName(nesHz),
            cents = Nes2A03.centsOff(nesHz),
            gridCents = gridCents,
            samplesPerStep = samplesPerStep,
            periodSamples = period,
            gridStepCents = gridStepCents(o.region, timer),
            foldedPeriods = periods,
            clarity = clarity,
            match = match,
            stepMatch = stepMatch,
            levelDb = if (abs(amplitude) < 1e-6f) -120f else (20.0 * log10(abs(amplitude).toDouble())).toFloat(),
            cycle = outCycle,
            live = live,
            livePhase0 = ((first - start) / period).toFloat(),
            cycles = cycles,
            audible = timer >= Nes2A03.TIMER_MIN_AUDIBLE,
        )
        held = reading
        return reading
    }

    // ---- period detection --------------------------------------------------

    private var lastClarity = 0f

    /**
     * McLeod's normalised square difference, computed through the FFT.
     *
     * The autocorrelation is the inverse transform of the power spectrum, and
     * the normalising term is two prefix sums, so the whole thing is two
     * transforms rather than a quarter of a million multiply-adds per lag. The
     * peak is picked the way MPM prescribes — the *first* local maximum within
     * 90 % of the global one — because for a periodic signal every multiple of
     * the period is also a near-perfect peak, and taking the tallest would drop
     * an octave at random.
     */
    private fun detectPeriod(x: FloatArray, o: NesOptions): Double {
        lastClarity = 0f
        val n = PITCH_N
        val from = x.size - n

        var mean = 0.0
        for (i in 0 until n) mean += x[from + i]
        mean /= n

        java.util.Arrays.fill(re, 0f)
        java.util.Arrays.fill(im, 0f)
        prefix[0] = 0.0
        for (i in 0 until n) {
            val v = (x[from + i] - mean).toFloat()
            re[i] = v
            prefix[i + 1] = prefix[i] + v.toDouble() * v
        }
        if (prefix[n] < 1e-9) return -1.0

        fft.transform(re, im)
        for (k in 0 until FFT_N) {
            re[k] = re[k] * re[k] + im[k] * im[k]
            im[k] = 0f
        }
        fft.transform(re, im)
        val scale = 1f / FFT_N

        val minLag = max(2, (sampleRate / o.huntMaxHz.coerceAtLeast(1f)).toInt())
        val maxLag = min(n - 2, (sampleRate / o.huntMinHz.coerceAtLeast(1f)).toInt())
        if (maxLag <= minLag + 2) return -1.0

        val total = prefix[n]
        for (tau in minLag..maxLag) {
            val m = prefix[n - tau] + total - prefix[tau]
            nsdf[tau] = if (m <= 1e-12) 0f else (2f * re[tau] * scale / m.toFloat())
        }

        var globalMax = 0f
        for (tau in minLag + 1 until maxLag) {
            if (nsdf[tau] > globalMax) globalMax = nsdf[tau]
        }
        if (globalMax <= 0f) return -1.0
        val threshold = globalMax * KEY_MAX_RATIO

        for (tau in minLag + 1 until maxLag) {
            val v = nsdf[tau]
            if (v < threshold) continue
            if (v < nsdf[tau - 1] || v < nsdf[tau + 1]) continue
            // Parabolic interpolation over the three samples around the peak.
            // Without it the period is quantised to whole samples, which at
            // 55 Hz is 0.1 % of a period — enough to slide the wave visibly.
            val a = nsdf[tau - 1]
            val c = nsdf[tau + 1]
            val denom = a - 2f * v + c
            val delta = if (abs(denom) < 1e-9f) 0f else 0.5f * (a - c) / denom
            lastClarity = (v - 0.25f * (a - c) * delta).coerceIn(0f, 1f)
            return tau + delta.coerceIn(-1f, 1f).toDouble()
        }
        return -1.0
    }

    // ---- phase -------------------------------------------------------------

    /**
     * How far the window start has to move to land on a peak of the fundamental.
     *
     * A single DFT bin at the detected period. The chip's own wave is even about
     * step 0 (level 15 is held either side of phase zero), so its fundamental has
     * zero phase by construction and the argument of this bin *is* the offset.
     * Continuous, so unlike a trigger threshold it adds no jitter of its own.
     */
    private fun phaseOffset(x: FloatArray, start: Double, period: Double, window: IntArray): Double {
        val m0 = window[0]
        // Integrate over every folded period, not just the drawn ones. It is the
        // same averaging the fold does, and it is what keeps a pulse channel from
        // dragging the picture sideways.
        val base = start + m0 * period
        val i0 = base.roundToInt().coerceIn(0, x.size - 1)
        val count = min((window[1] * period).toInt(), x.size - i0)
        if (count < 8) return 0.0
        var sumRe = 0.0
        var sumIm = 0.0
        val w = 2.0 * Math.PI / period
        for (i in 0 until count) {
            val v = x[i0 + i].toDouble()
            sumRe += v * cos(w * i)
            sumIm -= v * sin(w * i)
        }
        if (abs(sumRe) < 1e-12 && abs(sumIm) < 1e-12) return 0.0
        // The bin measures the phase at i0. Whole periods later the phase is the
        // same, so stepping back by m0 of them lands the peak next to `start`.
        val peak = i0 - period * atan2(sumIm, sumRe) / (2.0 * Math.PI)
        return (peak - m0 * period) - start
    }

    /**
     * Slides the window against the ideal wave to absorb the playback chain's
     * phase shift, and to catch a polarity flip that the fundamental's argument
     * cannot see — a capture path that inverts would otherwise draw the chip's
     * staircase upside down and never say so. Parabolic interpolation keeps the
     * result continuous, so nothing here can add jitter of its own.
     */
    private fun refine(x: FloatArray, start: Double, period: Double, want: Int): Double {
        val reach = period / 16.0
        var best = start
        var bestScore = Float.NEGATIVE_INFINITY
        for (flip in 0..1) {
            val centre = start + flip * period / 2.0
            var bestI = 0
            var bestV = Float.NEGATIVE_INFINITY
            val scores = FloatArray(REFINE_STEPS)
            for (i in 0 until REFINE_STEPS) {
                val off = centre + (i - (REFINE_STEPS - 1) / 2.0) * (2.0 * reach / (REFINE_STEPS - 1))
                scores[i] = correlate(x, off, period, want)
                if (scores[i] > bestV) { bestV = scores[i]; bestI = i }
            }
            var refined = centre + (bestI - (REFINE_STEPS - 1) / 2.0) * (2.0 * reach / (REFINE_STEPS - 1))
            if (bestI in 1 until REFINE_STEPS - 1) {
                val a = scores[bestI - 1]
                val c = scores[bestI + 1]
                val denom = a - 2f * bestV + c
                if (abs(denom) > 1e-9f) {
                    refined += (0.5f * (a - c) / denom) * (2.0 * reach / (REFINE_STEPS - 1))
                }
            }
            if (bestV > bestScore) { bestScore = bestV; best = refined }
        }
        return best
    }

    /**
     * Correlation of the ideal wave, repeated, against every whole period in the
     * history. Correlating one period would let whatever else is playing decide
     * the alignment; repeating it is the same averaging the fold does.
     */
    private fun correlate(x: FloatArray, start: Double, period: Double, want: Int): Float {
        val window = foldWindow(x.size, start, period, want)
        val m0 = window[0]
        val count = window[1]
        var sum = 0f
        val step = period / CORR_POINTS
        for (m in m0 until m0 + count) {
            val base = start + m * period
            for (i in 0 until CORR_POINTS) {
                sum += sampleAt(x, base + i * step) * Nes2A03.levelAt((i + 0.5f) / CORR_POINTS)
            }
        }
        return sum / count
    }

    /**
     * Which whole periods around [start] to fold, as `[firstIndex, count]`.
     *
     * Forward periods are preferred — they are the freshest audio — and the rest
     * are taken from behind the window, which is where nearly all the history is.
     */
    private fun foldWindow(n: Int, start: Double, period: Double, want: Int): IntArray {
        val forward = floor((n - 1 - start) / period).toInt().coerceAtLeast(1)
        val backward = floor(start / period).toInt().coerceAtLeast(0)
        val fwd = min(forward, want)
        val back = min(backward, want - fwd)
        return intArrayOf(-back, back + fwd)
    }

    /**
     * Removes DC and the first harmonic from one period on the display grid.
     *
     * A single-bin projection rather than a filter: the array *is* exactly one
     * period long, so the fundamental is a known basis vector and subtracting its
     * projection is exact, with none of a filter's edge effects.
     */
    private fun stripFundamental(v: FloatArray) {
        val n = v.size
        var dc = 0.0
        var cRe = 0.0
        var cIm = 0.0
        for (g in 0 until n) {
            val u = 2.0 * Math.PI * (g + 0.5) / n
            dc += v[g]
            cRe += v[g] * cos(u)
            cIm += v[g] * sin(u)
        }
        dc /= n
        cRe *= 2.0 / n
        cIm *= 2.0 / n
        for (g in 0 until n) {
            val u = 2.0 * Math.PI * (g + 0.5) / n
            v[g] = (v[g] - dc - (cRe * cos(u) + cIm * sin(u))).toFloat()
        }
    }

    /** Folds `want` whole periods around [start] into [folded]. Returns how many it used. */
    private fun fold(raw: FloatArray, start: Double, period: Double, want: Int): Int {
        val grid = NesReading.GRID
        val window = foldWindow(raw.size, start, period, want)
        val m0 = window[0]
        val periods = window[1]
        java.util.Arrays.fill(folded, 0f)
        for (m in m0 until m0 + periods) {
            val base = start + m * period
            for (g in 0 until grid) {
                folded[g] += sampleAt(raw, base + g * period / grid)
            }
        }
        val inv = 1f / periods
        for (g in 0 until grid) folded[g] *= inv
        return periods
    }

    /** Phase of the first harmonic of one period sampled on the display grid. */
    private fun fundamentalPhase(v: FloatArray): Double {
        var re = 0.0
        var im = 0.0
        for (g in v.indices) {
            val u = 2.0 * Math.PI * (g + 0.5) / v.size
            re += v[g] * cos(u)
            im += v[g] * sin(u)
        }
        return atan2(im, re)
    }

    private fun wrapPi(a: Double): Double {
        var v = a
        while (v > Math.PI) v -= 2.0 * Math.PI
        while (v < -Math.PI) v += 2.0 * Math.PI
        return v
    }

    /** Linear interpolation, clamped at both ends — the same thing a scope does. */
    private fun sampleAt(x: FloatArray, pos: Double): Float {
        if (pos <= 0.0) return x[0]
        val i = pos.toInt()
        if (i >= x.size - 1) return x[x.size - 1]
        val t = (pos - i).toFloat()
        return x[i] * (1f - t) + x[i + 1] * t
    }

    private fun NesReading.asStale() = NesReading(
        locked = true, stale = true, region = region, hz = hz, timer = timer, nesHz = nesHz,
        note = note, cents = cents, gridCents = gridCents, samplesPerStep = samplesPerStep,
        periodSamples = periodSamples, gridStepCents = gridStepCents,
        foldedPeriods = foldedPeriods, clarity = clarity,
        match = match, stepMatch = stepMatch, levelDb = levelDb,
        cycle = cycle, live = live, livePhase0 = livePhase0, cycles = cycles, audible = audible,
    )

    /**
     * Cents between [timer] and the next timer up — the resolution of the chip's
     * own tuning grid at this pitch.
     *
     * It matters because "the measurement lands on the 2A03 grid" is nearly
     * meaningless in the bass: at 55 Hz consecutive timer values are 1.7 cents
     * apart, so *any* frequency down there is within a cent of some timer, region
     * regardless. Above a kilohertz the same grid is tens of cents wide and the
     * claim starts to mean something. Reporting the step alongside the error is
     * what stops the readout implying evidence it does not have.
     */
    private fun gridStepCents(region: Nes2A03.Region, timer: Int): Double {
        if (timer >= Nes2A03.TIMER_MAX) return 0.0
        val a = Nes2A03.frequency(region, timer)
        val b = Nes2A03.frequency(region, timer + 1)
        return abs(1200.0 * kotlin.math.ln(a / b) / kotlin.math.ln(2.0))
    }

    companion object {
        /** Pitch window. 8192 at 48 kHz is 170 ms — four periods of the lowest note the chip has. */
        const val PITCH_N = 8192
        private const val FFT_N = PITCH_N * 2
        private const val KEY_MAX_RATIO = 0.9f
        private const val REFINE_STEPS = 17
        private const val CORR_POINTS = 128
        private const val MAX_FOLD = 48

        /**
         * Full-rate history the tracker needs behind it: eight periods of the
         * lowest note (27 Hz) plus the pitch window, rounded up.
         */
        const val HISTORY = 32768
    }
}
