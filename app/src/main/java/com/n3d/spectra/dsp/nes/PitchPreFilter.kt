package com.n3d.spectra.dsp.nes

import com.n3d.spectra.dsp.Biquad

/**
 * The low-pass in front of the period detector.
 *
 * Without it the tracker locks to whatever is loudest, and in NES music that is
 * usually the pulse melody rather than the triangle bass — worse, two pulses a
 * fourth apart share a common subharmonic, so the detector finds a confident,
 * completely wrong period an octave or two below the tune. The bass is the only
 * thing under the hunt range, so removing everything above it is what makes the
 * question well posed.
 *
 * It is 8th order because 4th is not enough: a melody a fourth above the hunt
 * ceiling is only 17 dB down through a 4th-order skirt, which loses to a bass
 * line mixed 5 dB quieter. At 8th order the same note is 57 dB down and the
 * question is no longer close.
 *
 * This filters a *stream*, not a window. Re-filtering the same history every
 * frame would put a settling transient inside the analysis window and move the
 * detected period around as the transient slid through it.
 */
class PitchPreFilter(sampleRate: Int, cornerHz: Double) {

    private val stages = Biquad.butterworthQs(ORDER).map { Biquad.lowPass(sampleRate, cornerHz, it) }

    fun process(x: Float): Float {
        var v = x.toDouble()
        for (s in stages) v = s.process(v)
        return v.toFloat()
    }

    fun process(src: FloatArray, dst: FloatArray, count: Int) {
        for (i in 0 until count) dst[i] = process(src[i])
    }

    fun reset() = stages.forEach { it.reset() }

    companion object {
        private const val ORDER = 8

        /**
         * Corner for a given hunt ceiling. Just above it, so a bass note at the
         * very top of the range is still passed flat.
         */
        fun cornerFor(huntMaxHz: Float): Double = (huntMaxHz * 1.2f).toDouble().coerceIn(40.0, 4000.0)
    }
}
