package com.n3d.spectra.dsp

import kotlin.math.cos

/**
 * Analysis windows, with the two normalisation constants that actually matter
 * computed from the generated samples rather than from a table of published
 * values — a table is one more thing that can silently disagree with the code
 * that generates the window.
 *
 *  - [coherentGain] (mean of the window) corrects the *amplitude* of a sinusoid
 *    sitting exactly on a bin. Divide magnitudes by it and a full-scale sine
 *    reads 0 dBFS whatever window is selected.
 *  - [enbw] (equivalent noise bandwidth, in bins) corrects *power* summed across
 *    bins, which is what a band or noise-floor measurement needs. Using coherent
 *    gain there instead is the classic way to get band levels a few dB wrong.
 */
enum class WindowFunction(val label: String, val blurb: String) {

    /** No window. Only correct when the block is exactly periodic — otherwise leaks badly. */
    RECTANGULAR("Rectangular", "No leakage suppression. Highest resolution, worst sidelobes."),

    /** The default: −31 dB sidelobes, 1.5-bin main lobe. What most analysers use. */
    HANN("Hann", "The default. Good balance of resolution and leakage."),

    HAMMING("Hamming", "Lower first sidelobe than Hann, slower far-field rolloff."),

    /** −92 dB sidelobes. Use when looking for a quiet tone next to a loud one. */
    BLACKMAN_HARRIS("Blackman–Harris 4", "−92 dB sidelobes. Best dynamic range, widest main lobe."),

    /** Amplitude-accurate to ~0.01 dB regardless of where the tone falls between bins. */
    FLAT_TOP("Flat top", "Amplitude-accurate for level calibration. Poor frequency resolution.");

    /** Builds the window and its normalisation constants for a block of [n] samples. */
    fun build(n: Int): Windowed {
        val w = FloatArray(n)
        // Periodic (÷n) rather than symmetric (÷(n−1)): the periodic form is the
        // correct one for spectral analysis of a continuous stream.
        for (i in 0 until n) {
            val x = 2.0 * Math.PI * i / n
            w[i] = when (this) {
                RECTANGULAR -> 1.0
                HANN -> 0.5 - 0.5 * cos(x)
                HAMMING -> 0.54 - 0.46 * cos(x)
                BLACKMAN_HARRIS ->
                    0.35875 - 0.48829 * cos(x) + 0.14128 * cos(2 * x) - 0.01168 * cos(3 * x)
                FLAT_TOP ->
                    0.21557895 - 0.41663158 * cos(x) + 0.277263158 * cos(2 * x) -
                        0.083578947 * cos(3 * x) + 0.006947368 * cos(4 * x)
            }.toFloat()
        }

        var sum = 0.0
        var sumSq = 0.0
        for (v in w) {
            sum += v
            sumSq += v.toDouble() * v
        }
        val coherentGain = (sum / n).toFloat()
        val enbw = (n * sumSq / (sum * sum)).toFloat()
        return Windowed(w, coherentGain, enbw, sumSq.toFloat())
    }

    class Windowed(
        val samples: FloatArray,
        val coherentGain: Float,
        val enbw: Float,
        /** Σw², the denominator of a power-spectral-density normalisation. */
        val sumSquares: Float,
    )
}
