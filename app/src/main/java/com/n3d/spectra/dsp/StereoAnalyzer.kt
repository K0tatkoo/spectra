package com.n3d.spectra.dsp

import com.n3d.spectra.settings.Settings
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Phase correlation, stereo width and the goniometer cloud.
 *
 * Correlation is computed from exponentially-decaying sums rather than from a
 * hard sliding window, so the reading settles rather than jumping when a
 * boundary sample falls out of the window — that flicker is what makes most
 * phone correlation meters unreadable.
 *
 * The goniometer axes are the usual 45° rotation: a mono signal (L = R) lands on
 * the vertical, an out-of-phase signal on the horizontal.
 */
class StereoAnalyzer(sampleRate: Int, settings: Settings, private val cloudPoints: Int = 720) {

    private var rate = sampleRate
    private var decay = 0.0

    private var sumLL = 0.0
    private var sumRR = 0.0
    private var sumLR = 0.0
    private var sumMM = 0.0
    private var sumSS = 0.0

    /** Interleaved x,y pairs; overwritten in place every block. */
    val cloud = FloatArray(cloudPoints * 2)
    private var cloudCount = 0

    var correlation = 0f
        private set
    var width = 0f
        private set
    var rmsL = 0f
        private set
    var rmsR = 0f
        private set
    var peakL = 0f
        private set
    var peakR = 0f
        private set

    init { configure(settings, sampleRate) }

    fun configure(s: Settings, sampleRate: Int) {
        rate = sampleRate
        val tau = (s.correlationWindowMs / 1000.0).coerceAtLeast(0.005)
        // Per-sample decay for a first-order average with time constant tau.
        decay = exp(-1.0 / (tau * sampleRate))
    }

    fun process(left: FloatArray, right: FloatArray?, n: Int) {
        if (n <= 0) return

        var pl = 0f
        var pr = 0f
        var ll = sumLL
        var rr = sumRR
        var lr = sumLR
        var mm = sumMM
        var ss = sumSS
        val d = decay

        for (i in 0 until n) {
            val l = left[i].toDouble()
            val r = (right?.get(i) ?: left[i]).toDouble()
            ll = ll * d + l * l
            rr = rr * d + r * r
            lr = lr * d + l * r
            val mid = (l + r) * 0.5
            val side = (l - r) * 0.5
            mm = mm * d + mid * mid
            ss = ss * d + side * side

            val al = abs(left[i]); if (al > pl) pl = al
            val ar = abs(right?.get(i) ?: left[i]); if (ar > pr) pr = ar
        }

        sumLL = ll; sumRR = rr; sumLR = lr; sumMM = mm; sumSS = ss

        val denom = sqrt(ll * rr)
        correlation = if (denom < 1e-12) 1f else (lr / denom).toFloat().coerceIn(-1f, 1f)

        val midRms = sqrt(mm)
        val sideRms = sqrt(ss)
        width = if (midRms + sideRms < 1e-12) 0f else (sideRms / (midRms + 1e-12)).toFloat().coerceIn(0f, 4f)

        // Scale factor: these sums are decaying, so their steady-state value for
        // a signal of mean square m is m/(1−d). Undo that to get a real RMS.
        val norm = (1.0 - d).coerceAtLeast(1e-9)
        rmsL = sqrt(ll * norm).toFloat()
        rmsR = sqrt(rr * norm).toFloat()
        peakL = pl
        peakR = pr

        fillCloud(left, right, n)
    }

    /**
     * Decimates the block down to [cloudPoints] dots. Older dots are kept and
     * faded by the painter rather than being erased, which is what gives a
     * goniometer its characteristic smear on transients.
     */
    private fun fillCloud(left: FloatArray, right: FloatArray?, n: Int) {
        if (right == null) {
            cloudCount = 0
            return
        }
        val take = minOf(cloudPoints, n)
        val stride = (n.toFloat() / take).coerceAtLeast(1f)
        var w = 0
        var pos = 0f
        while (w < take) {
            val i = pos.toInt().coerceIn(0, n - 1)
            val l = left[i]
            val r = right[i]
            cloud[w * 2] = (r - l) * SQRT1_2
            cloud[w * 2 + 1] = (r + l) * SQRT1_2
            pos += stride
            w++
        }
        cloudCount = take
    }

    fun cloudSize(): Int = cloudCount

    fun snapshotCloud(): FloatArray =
        if (cloudCount == 0) FloatArray(0) else cloud.copyOf(cloudCount * 2)

    fun reset() {
        sumLL = 0.0; sumRR = 0.0; sumLR = 0.0; sumMM = 0.0; sumSS = 0.0
        correlation = 0f; width = 0f
        cloudCount = 0
    }

    companion object {
        private const val SQRT1_2 = 0.70710677f

        fun toDb(linear: Float): Float {
            if (linear <= 1e-7f) return PeakHold.FLOOR_DB
            val db = 20f * log10(linear)
            return if (db < PeakHold.FLOOR_DB) PeakHold.FLOOR_DB else db
        }
    }
}
