package com.n3d.spectra.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Transposed direct form II biquad. TDF-II is chosen over DF-I because it needs
 * two state variables instead of four and is better behaved in floating point at
 * the low corner frequencies this app uses (a 20 Hz high-pass at 48 kHz is a
 * very short distance from the unit circle).
 *
 * State is per instance, so a stereo band needs two.
 */
class Biquad(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double,
) {
    private var z1 = 0.0
    private var z2 = 0.0

    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }

    /** A structural copy with fresh state — used to build the second channel. */
    fun copyShape() = Biquad(b0, b1, b2, a1, a2)

    companion object {
        /**
         * RBJ cookbook low-pass. [q] = 1/√2 gives Butterworth; cascade two of
         * those for a 24 dB/oct Linkwitz–Riley.
         */
        fun lowPass(sampleRate: Int, freq: Double, q: Double = SQRT1_2): Biquad {
            val w0 = 2.0 * PI * freq / sampleRate
            val cosW = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha
            return Biquad(
                b0 = ((1.0 - cosW) / 2.0) / a0,
                b1 = (1.0 - cosW) / a0,
                b2 = ((1.0 - cosW) / 2.0) / a0,
                a1 = (-2.0 * cosW) / a0,
                a2 = (1.0 - alpha) / a0,
            )
        }

        fun highPass(sampleRate: Int, freq: Double, q: Double = SQRT1_2): Biquad {
            val w0 = 2.0 * PI * freq / sampleRate
            val cosW = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha
            return Biquad(
                b0 = ((1.0 + cosW) / 2.0) / a0,
                b1 = (-(1.0 + cosW)) / a0,
                b2 = ((1.0 + cosW) / 2.0) / a0,
                a1 = (-2.0 * cosW) / a0,
                a2 = (1.0 - alpha) / a0,
            )
        }

        /**
         * Stage 1 of ITU-R BS.1770 K-weighting: the "head shelf", a +4 dB high
         * shelf at ~1681 Hz. The published coefficients are for 48 kHz only, so
         * they are re-derived here from the analogue prototype with a bilinear
         * transform — the same derivation libebur128 uses, which is what makes
         * LUFS readings correct at 44.1 kHz as well.
         */
        fun kWeightingShelf(sampleRate: Int): Biquad {
            val f0 = 1681.974450955533
            val g = 3.999843853973347
            val q = 0.7071752369554196
            val k = tan(PI * f0 / sampleRate)
            val vh = Math.pow(10.0, g / 20.0)
            val vb = Math.pow(vh, 0.4996667741545416)
            val a0 = 1.0 + k / q + k * k
            return Biquad(
                b0 = (vh + vb * k / q + k * k) / a0,
                b1 = 2.0 * (k * k - vh) / a0,
                b2 = (vh - vb * k / q + k * k) / a0,
                a1 = 2.0 * (k * k - 1.0) / a0,
                a2 = (1.0 - k / q + k * k) / a0,
            )
        }

        /** Stage 2 of K-weighting: a 2nd-order high-pass at ~38 Hz (unity numerator). */
        fun kWeightingHighPass(sampleRate: Int): Biquad {
            val f0 = 38.13547087602444
            val q = 0.5003270373238773
            val k = tan(PI * f0 / sampleRate)
            val denom = 1.0 + k / q + k * k
            return Biquad(
                b0 = 1.0,
                b1 = -2.0,
                b2 = 1.0,
                a1 = 2.0 * (k * k - 1.0) / denom,
                a2 = (1.0 - k / q + k * k) / denom,
            )
        }

        val SQRT1_2 = 1.0 / sqrt(2.0)

        /**
         * Butterworth Q values for a cascade of [order]/2 biquads. A 4th-order
         * Butterworth is NOT two Q=0.707 sections (that is Linkwitz–Riley, which
         * is −6 dB at the corner); it is Q = 0.5412 and 1.3066.
         */
        fun butterworthQs(order: Int): DoubleArray {
            val sections = order / 2
            return DoubleArray(sections) { i ->
                1.0 / (2.0 * cos(PI * (2.0 * i + 1.0) / (2.0 * order)))
            }
        }
    }
}
