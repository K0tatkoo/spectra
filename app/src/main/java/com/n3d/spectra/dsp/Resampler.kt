package com.n3d.spectra.dsp

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Streaming stereo sample-rate converter, polyphase windowed sinc.
 *
 * It exists for one reason: the stem model is trained at exactly 44.1 kHz and
 * the capture usually runs at 48. Feeding it 48 kHz audio would not fail — it
 * would quietly shift every pitch the model knows up by 1.5 semitones and every
 * spectral boundary it learned with it, which is the kind of wrong that looks
 * like "the model is just not very good".
 *
 * The ratio is reduced to L/M (48000 → 44100 is 147/160) and one short sinc
 * kernel per output phase is precomputed, so each output sample is a single
 * 32-tap dot product per channel. The cut-off sits a little under the lower
 * Nyquist, Kaiser-windowed: separation does not need mastering-grade
 * conversion, it needs nothing folded back into the band it listens to.
 *
 * Not thread safe; the analysis thread owns one.
 */
class Resampler(val inRate: Int, val outRate: Int, private val halfTaps: Int = 16) {

    val passthrough = inRate == outRate

    private val up: Int
    private val down: Int
    private val taps = halfTaps * 2
    private val kernel: FloatArray

    private var bufL = FloatArray(8192)
    private var bufR = FloatArray(8192)
    private var bufLen = halfTaps - 1
    private var pos = halfTaps - 1
    private var phase = 0

    init {
        val g = gcd(inRate, outRate)
        up = outRate / g
        down = inRate / g
        kernel = FloatArray(if (passthrough) 0 else up * taps)
        if (!passthrough) {
            // Cut-off as a fraction of the *input* rate.
            val fc = 0.5 * minOf(1.0, outRate.toDouble() / inRate) * CUTOFF
            val beta = KAISER_BETA
            val i0Beta = besselI0(beta)
            for (p in 0 until up) {
                val frac = p.toDouble() / up
                var sum = 0.0
                for (k in 0 until taps) {
                    // Offset of input sample k from the output instant, in input samples.
                    val t = (k - halfTaps + 1) - frac
                    val x = 2.0 * fc * t
                    val sinc = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
                    val r = t / halfTaps
                    val w = if (r * r >= 1.0) 0.0 else besselI0(beta * sqrt(1.0 - r * r)) / i0Beta
                    val v = 2.0 * fc * sinc * w
                    kernel[p * taps + k] = v.toFloat()
                    sum += v
                }
                // Normalise each phase to unity DC gain. Without it the phases
                // disagree by a fraction of a dB and the output grows a faint
                // tone at the ratio's beat frequency.
                for (k in 0 until taps) kernel[p * taps + k] = (kernel[p * taps + k] / sum).toFloat()
            }
        }
    }

    /** Upper bound on the output of one [process] call with [frames] of input. */
    fun maxOutput(frames: Int): Int = if (passthrough) frames else (frames.toLong() * up / down).toInt() + 2

    /**
     * Converts [frames] of input. [inR] may be the same array as [inL] for mono.
     * Returns the number of frames written to [outL]/[outR].
     */
    fun process(inL: FloatArray, inR: FloatArray, frames: Int, outL: FloatArray, outR: FloatArray): Int {
        if (passthrough) {
            System.arraycopy(inL, 0, outL, 0, frames)
            System.arraycopy(inR, 0, outR, 0, frames)
            return frames
        }
        ensure(bufLen + frames)
        System.arraycopy(inL, 0, bufL, bufLen, frames)
        System.arraycopy(inR, 0, bufR, bufLen, frames)
        bufLen += frames

        var out = 0
        while (pos + halfTaps < bufLen) {
            val k0 = phase * taps
            val base = pos - halfTaps + 1
            var l = 0f
            var r = 0f
            for (k in 0 until taps) {
                val c = kernel[k0 + k]
                l += bufL[base + k] * c
                r += bufR[base + k] * c
            }
            outL[out] = l
            outR[out] = r
            out++
            phase += down
            while (phase >= up) {
                phase -= up
                pos++
            }
        }

        // Keep only what the next output still needs.
        val drop = pos - (halfTaps - 1)
        if (drop > 0) {
            System.arraycopy(bufL, drop, bufL, 0, bufLen - drop)
            System.arraycopy(bufR, drop, bufR, 0, bufLen - drop)
            bufLen -= drop
            pos -= drop
        }
        return out
    }

    fun reset() {
        java.util.Arrays.fill(bufL, 0f)
        java.util.Arrays.fill(bufR, 0f)
        bufLen = halfTaps - 1
        pos = halfTaps - 1
        phase = 0
    }

    private fun ensure(size: Int) {
        if (size <= bufL.size) return
        var n = bufL.size
        while (n < size) n *= 2
        bufL = bufL.copyOf(n)
        bufR = bufR.copyOf(n)
    }

    private companion object {
        const val CUTOFF = 0.94
        const val KAISER_BETA = 8.0

        tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

        /** Zeroth-order modified Bessel function, by its power series. */
        fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            val half = x / 2.0
            var k = 1
            while (k < 64) {
                term *= (half / k) * (half / k)
                sum += term
                if (term < sum * 1e-12) break
                k++
            }
            return sum
        }
    }
}
