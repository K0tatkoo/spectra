package com.n3d.spectra.dsp

import kotlin.math.cos
import kotlin.math.sin

/**
 * In-place iterative radix-2 Cooley–Tukey FFT with precomputed twiddle factors
 * and a precomputed bit-reversal permutation.
 *
 * The twiddle sign convention here is the forward transform, X[k] = Σ x[n]·e^(-j2πkn/N),
 * expressed with positive-angle tables and the sign folded into the butterfly.
 * That is worth spelling out because getting it backwards is invisible in a
 * magnitude plot (|X| is unchanged) and only shows up later, in the phase and in
 * anything that inverse-transforms.
 *
 * One instance is bound to one size and is NOT thread safe — the analysis thread
 * owns it exclusively.
 */
class Fft(val size: Int) {

    private val levels: Int
    private val cosTable: FloatArray
    private val sinTable: FloatArray
    private val reverse: IntArray

    init {
        require(size >= 2 && (size and (size - 1)) == 0) { "FFT size must be a power of two, was $size" }
        levels = Integer.numberOfTrailingZeros(size)
        cosTable = FloatArray(size / 2)
        sinTable = FloatArray(size / 2)
        for (i in 0 until size / 2) {
            val angle = 2.0 * Math.PI * i / size
            cosTable[i] = cos(angle).toFloat()
            sinTable[i] = sin(angle).toFloat()
        }
        reverse = IntArray(size) { Integer.reverse(it) ushr (32 - levels) }
    }

    /** Transforms [re]/[im] in place. Both arrays must be [size] long. */
    fun transform(re: FloatArray, im: FloatArray) {
        require(re.size == size && im.size == size)

        for (i in 0 until size) {
            val j = reverse[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }

        var half = 1
        while (half < size) {
            val step = size / (half * 2)
            var i = 0
            while (i < size) {
                var j = i
                var k = 0
                while (j < i + half) {
                    val l = j + half
                    val c = cosTable[k]
                    val s = sinTable[k]
                    val tre = re[l] * c + im[l] * s
                    val tim = -re[l] * s + im[l] * c
                    re[l] = re[j] - tre
                    im[l] = im[j] - tim
                    re[j] += tre
                    im[j] += tim
                    j++
                    k += step
                }
                i += half * 2
            }
            half *= 2
        }
    }
}
