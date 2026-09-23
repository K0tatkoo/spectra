package com.n3d.spectra.dsp

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * McLeod's normalised square difference function, computed through the FFT.
 *
 * The same method the 2A03 tracker uses (see `TriangleTracker.detectPeriod`),
 * lifted out so every held-still scope can share it. The autocorrelation is the
 * inverse transform of the power spectrum and the normalising term is two
 * prefix sums, so the cost is two transforms rather than a multiply-add per lag
 * per sample. The peak is chosen the MPM way — the *first* local maximum within
 * 90 % of the global one — because every multiple of the period is also a
 * near-perfect match and taking the tallest drops an octave at random.
 *
 * Not thread safe; one per analysis lane.
 */
class PitchDetector(private val sampleRate: Int, val window: Int) {

    private val fftN = window * 2
    private val fft = Fft(fftN)
    private val re = FloatArray(fftN)
    private val im = FloatArray(fftN)
    private val prefix = DoubleArray(window + 1)
    private val nsdf = FloatArray(window)

    /** Peak height of the last detection, 0…1 — how periodic the input was. */
    var clarity: Float = 0f
        private set

    /**
     * The period, in samples (fractional), of the newest [window] samples of [x],
     * searching only between [minHz] and [maxHz]. Returns −1 when there is no
     * usable peak; [clarity] says how strong it was either way.
     */
    fun detect(x: FloatArray, minHz: Float, maxHz: Float): Double {
        clarity = 0f
        val n = window
        if (x.size < n) return -1.0
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
        if (prefix[n] < 1e-10) return -1.0

        fft.transform(re, im)
        for (k in 0 until fftN) {
            re[k] = re[k] * re[k] + im[k] * im[k]
            im[k] = 0f
        }
        fft.transform(re, im)
        val scale = 1f / fftN

        val minLag = max(2, (sampleRate / maxHz.coerceAtLeast(1f)).toInt())
        // Two whole periods have to fit, or the "match" is against nothing.
        val maxLag = min(n / 2, (sampleRate / minHz.coerceAtLeast(1f)).toInt())
        if (maxLag <= minLag + 2) return -1.0

        val total = prefix[n]
        for (tau in minLag - 1..maxLag + 1) {
            val m = prefix[n - tau] + total - prefix[tau]
            nsdf[tau] = if (m <= 1e-12) 0f else (2f * re[tau] * scale / m.toFloat())
        }

        var globalMax = 0f
        for (tau in minLag until maxLag) if (nsdf[tau] > globalMax) globalMax = nsdf[tau]
        if (globalMax <= 0f) return -1.0
        val threshold = globalMax * KEY_MAX_RATIO

        for (tau in minLag until maxLag) {
            val v = nsdf[tau]
            if (v < threshold) continue
            if (v < nsdf[tau - 1] || v < nsdf[tau + 1]) continue
            // Parabolic interpolation: without it the period is quantised to
            // whole samples, which at 55 Hz is enough to slide the wave visibly.
            val a = nsdf[tau - 1]
            val c = nsdf[tau + 1]
            val denom = a - 2f * v + c
            val delta = if (abs(denom) < 1e-9f) 0f else 0.5f * (a - c) / denom
            clarity = (v - 0.25f * (a - c) * delta).coerceIn(0f, 1f)
            return tau + delta.coerceIn(-1f, 1f).toDouble()
        }
        return -1.0
    }

    private companion object {
        const val KEY_MAX_RATIO = 0.9f
    }
}
