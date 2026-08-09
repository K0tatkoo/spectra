package com.n3d.spectra.dsp

import com.n3d.spectra.settings.Settings
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/**
 * ITU-R BS.1770-4 loudness: momentary (400 ms), short-term (3 s), gated
 * integrated, loudness range, and inter-sample true peak.
 *
 * Structure follows the standard exactly:
 *  - each channel is K-weighted (a +4 dB shelf at 1.68 kHz followed by a 38 Hz
 *    high-pass), coefficients re-derived for the actual sample rate;
 *  - energy is accumulated into 100 ms grains, because every window the standard
 *    defines (400 ms momentary, 3 s short-term, both with 75 % overlap) is a
 *    whole number of them;
 *  - integrated loudness is gated twice, first at an absolute −70 LUFS and then
 *    at 10 LU below the mean of what survived.
 *
 * Integrated loudness and LRA are accumulated into histograms rather than a
 * growing list of blocks. That is what libebur128 does, and it is why this can
 * run for hours in a foreground service without its memory creeping.
 */
class LoudnessMeter(sampleRate: Int, settings: Settings) {

    private var rate = sampleRate
    private var shelfL = Biquad.kWeightingShelf(sampleRate)
    private var hpL = Biquad.kWeightingHighPass(sampleRate)
    private var shelfR = Biquad.kWeightingShelf(sampleRate)
    private var hpR = Biquad.kWeightingHighPass(sampleRate)

    private var grainSamples = sampleRate / 10
    private var grainAcc = 0.0
    private var grainCount = 0

    /** Ring of the last 30 grains (3 s) of Σ_ch G·meanSquare. */
    private val grains = DoubleArray(GRAINS_SHORT)
    private var grainHead = 0
    private var grainsFilled = 0

    private val integratedHist = IntArray(HIST_BINS)
    private val rangeHist = IntArray(HIST_BINS)
    private var integratedSeconds = 0f

    private var truePeak: TruePeak? = null
    private var truePeakR: TruePeak? = null
    private var maxTruePeak = 0f

    var enabled = true
        private set

    init { configure(settings, sampleRate) }

    fun configure(s: Settings, sampleRate: Int) {
        enabled = s.loudnessEnabled
        if (sampleRate != rate) {
            rate = sampleRate
            shelfL = Biquad.kWeightingShelf(sampleRate)
            hpL = Biquad.kWeightingHighPass(sampleRate)
            shelfR = Biquad.kWeightingShelf(sampleRate)
            hpR = Biquad.kWeightingHighPass(sampleRate)
            grainSamples = sampleRate / 10
            reset()
        }
        if (s.truePeakEnabled) {
            val factor = s.truePeakOversample.coerceIn(2, 8)
            if (truePeak?.factor != factor) {
                truePeak = TruePeak(factor)
                truePeakR = TruePeak(factor)
            }
        } else {
            truePeak = null
            truePeakR = null
        }
    }

    fun process(left: FloatArray, right: FloatArray?, n: Int) {
        if (!enabled) return
        val tp = truePeak
        val tpr = truePeakR

        for (i in 0 until n) {
            val l = left[i]
            var energy = kWeighted(l.toDouble(), shelfL, hpL).let { it * it }
            if (right != null) {
                val r = right[i]
                val kr = kWeighted(r.toDouble(), shelfR, hpR)
                energy += kr * kr
                if (tpr != null) {
                    val p = tpr.process(r)
                    if (p > maxTruePeak) maxTruePeak = p
                }
            }
            if (tp != null) {
                val p = tp.process(l)
                if (p > maxTruePeak) maxTruePeak = p
            }

            grainAcc += energy
            if (++grainCount >= grainSamples) {
                pushGrain(grainAcc / grainSamples)
                grainAcc = 0.0
                grainCount = 0
            }
        }
    }

    private fun kWeighted(x: Double, shelf: Biquad, hp: Biquad): Double = hp.process(shelf.process(x))

    private fun pushGrain(meanSquare: Double) {
        grains[grainHead] = meanSquare
        grainHead = (grainHead + 1) % GRAINS_SHORT
        if (grainsFilled < GRAINS_SHORT) grainsFilled++
        integratedSeconds += 0.1f

        // A 400 ms block becomes available every 100 ms — that is the 75 %
        // overlap the standard asks for, expressed as "one block per grain".
        if (grainsFilled >= GRAINS_MOMENTARY) {
            val z = meanOfLastGrains(GRAINS_MOMENTARY)
            addToHistogram(integratedHist, loudnessOf(z))
        }
        if (grainsFilled >= GRAINS_SHORT) {
            val z = meanOfLastGrains(GRAINS_SHORT)
            addToHistogram(rangeHist, loudnessOf(z))
        }
    }

    private fun meanOfLastGrains(count: Int): Double {
        var sum = 0.0
        var idx = grainHead - 1
        repeat(count) {
            if (idx < 0) idx += GRAINS_SHORT
            sum += grains[idx]
            idx--
        }
        return sum / count
    }

    fun read(): LoudnessReading {
        if (!enabled) return LoudnessReading.EMPTY
        val momentary: Float = if (grainsFilled >= GRAINS_MOMENTARY)
            loudnessOf(meanOfLastGrains(GRAINS_MOMENTARY)).toFloat() else LoudnessReading.SILENT
        val shortTerm: Float = if (grainsFilled >= GRAINS_SHORT)
            loudnessOf(meanOfLastGrains(GRAINS_SHORT)).toFloat() else LoudnessReading.SILENT
        val tpDb = if (maxTruePeak <= 0f) -144f else (20.0 * log10(maxTruePeak.toDouble())).toFloat()
        return LoudnessReading(
            momentary = momentary,
            shortTerm = shortTerm,
            integrated = gatedIntegrated(),
            range = loudnessRange(),
            truePeakDb = tpDb,
            integratedSeconds = integratedSeconds,
        )
    }

    fun reset() {
        java.util.Arrays.fill(grains, 0.0)
        grainHead = 0
        grainsFilled = 0
        grainAcc = 0.0
        grainCount = 0
        java.util.Arrays.fill(integratedHist, 0)
        java.util.Arrays.fill(rangeHist, 0)
        integratedSeconds = 0f
        maxTruePeak = 0f
        shelfL.reset(); hpL.reset(); shelfR.reset(); hpR.reset()
        truePeak?.reset(); truePeakR?.reset()
    }

    // ---- gating ------------------------------------------------------------

    /**
     * Two-pass gate. The relative threshold is derived from the mean energy of
     * everything above the absolute gate, then applied to the same histogram —
     * which is why the histogram has to hold energy, not loudness, when it is
     * averaged.
     */
    private fun gatedIntegrated(): Float {
        val firstPass = histogramMeanEnergy(integratedHist, 0) ?: return LoudnessReading.SILENT
        val relativeLufs = (loudnessOf(firstPass) - RELATIVE_GATE_LU).toFloat()
        val fromBin = binOf(relativeLufs) + 1
        val second = histogramMeanEnergy(integratedHist, fromBin) ?: return LoudnessReading.SILENT
        return loudnessOf(second).toFloat()
    }

    /** LRA: the 10th-to-95th percentile spread of short-term blocks, gated at −20 LU. */
    private fun loudnessRange(): Float {
        val mean = histogramMeanEnergy(rangeHist, 0) ?: return 0f
        val relative = (loudnessOf(mean) - LRA_GATE_LU).toFloat()
        val from = binOf(relative) + 1
        var total = 0L
        for (i in from until HIST_BINS) total += rangeHist[i]
        if (total < 10) return 0f

        val lowTarget = (total * 0.10).toLong()
        val highTarget = (total * 0.95).toLong()
        var running = 0L
        var low = Float.NaN
        var high = Float.NaN
        for (i in from until HIST_BINS) {
            running += rangeHist[i]
            if (low.isNaN() && running >= lowTarget) low = binLoudness(i)
            if (running >= highTarget) { high = binLoudness(i); break }
        }
        if (low.isNaN() || high.isNaN()) return 0f
        return (high - low).coerceAtLeast(0f)
    }

    private fun histogramMeanEnergy(hist: IntArray, fromBin: Int): Double? {
        var count = 0L
        var energy = 0.0
        for (i in fromBin.coerceAtLeast(0) until HIST_BINS) {
            val c = hist[i]
            if (c == 0) continue
            count += c
            energy += c * energyOfBin(i)
        }
        return if (count == 0L) null else energy / count
    }

    private fun addToHistogram(hist: IntArray, loudness: Double) {
        if (loudness <= ABSOLUTE_GATE_LUFS) return
        val idx = binOf(loudness.toFloat())
        if (idx in 0 until HIST_BINS) hist[idx]++
    }

    private fun binOf(loudness: Float): Int =
        ((loudness - HIST_MIN_LUFS) / HIST_STEP_LU).toInt().coerceIn(-1, HIST_BINS - 1)

    private fun binLoudness(i: Int): Float = HIST_MIN_LUFS + (i + 0.5f) * HIST_STEP_LU

    private fun energyOfBin(i: Int): Double = Math.pow(10.0, (binLoudness(i) + 0.691f).toDouble() / 10.0)

    private fun loudnessOf(meanSquare: Double): Double =
        if (meanSquare <= 1e-15) -200.0 else -0.691 + 10.0 * log10(meanSquare)

    companion object {
        private const val GRAINS_MOMENTARY = 4   // 400 ms
        private const val GRAINS_SHORT = 30      // 3 s
        private const val ABSOLUTE_GATE_LUFS = -70.0
        private const val RELATIVE_GATE_LU = 10.0
        private const val LRA_GATE_LU = 20.0
        private const val HIST_MIN_LUFS = -70f
        private const val HIST_STEP_LU = 0.075f  // 1000 bins covering −70..+5 LUFS
        private const val HIST_BINS = 1000
    }
}

/**
 * Polyphase inter-sample peak detector.
 *
 * The filter is generated from a windowed sinc at construction rather than
 * copied from a published coefficient table: a table is a hundred numbers that
 * cannot be verified by reading them, and a single transposed digit shows up as
 * a true-peak reading that is wrong by a fraction of a dB — small enough to
 * never be noticed and large enough to matter when you are checking a master
 * against −1 dBTP.
 */
class TruePeak(val factor: Int, private val tapsPerPhase: Int = 12) {

    private val phases: Array<FloatArray>
    private val history = FloatArray(tapsPerPhase)
    private var head = 0

    init {
        val length = factor * tapsPerPhase
        val h = DoubleArray(length)
        val centre = (length - 1) / 2.0
        for (n in 0 until length) {
            val x = (n - centre) / factor
            val sinc = if (abs(x) < 1e-9) 1.0 else sin(PI * x) / (PI * x)
            // Blackman window: −58 dB sidelobes, which is well below the
            // quantisation of anything this measurement is compared against.
            val w = 0.42 - 0.5 * cos(2.0 * PI * n / (length - 1)) + 0.08 * cos(4.0 * PI * n / (length - 1))
            h[n] = sinc * w
        }
        phases = Array(factor) { p ->
            val taps = DoubleArray(tapsPerPhase) { m -> h[m * factor + p] }
            val sum = taps.sum()
            // Normalising each phase to unity DC gain is what keeps a constant
            // input from reading as a peak above itself.
            FloatArray(tapsPerPhase) { (if (sum != 0.0) taps[it] / sum else taps[it]).toFloat() }
        }
    }

    /** Feeds one sample and returns the largest absolute value in its neighbourhood. */
    fun process(x: Float): Float {
        history[head] = x
        head = (head + 1) % tapsPerPhase
        var maxAbs = abs(x)
        for (phase in phases) {
            var acc = 0f
            var idx = head - 1
            for (m in 0 until tapsPerPhase) {
                if (idx < 0) idx += tapsPerPhase
                acc += phase[m] * history[idx]
                idx--
            }
            val a = abs(acc)
            if (a > maxAbs) maxAbs = a
        }
        return maxAbs
    }

    fun reset() {
        java.util.Arrays.fill(history, 0f)
        head = 0
    }
}
