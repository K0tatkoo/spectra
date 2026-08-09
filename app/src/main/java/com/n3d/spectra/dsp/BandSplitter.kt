package com.n3d.spectra.dsp

import com.n3d.spectra.settings.BandDef
import com.n3d.spectra.settings.Settings
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Independent per-band level meters built from real filters in the time domain,
 * not from summing FFT bins.
 *
 * Summing bins is cheaper and is what most phone "spectrum" apps do, but it
 * inherits the transform's time resolution: with a 4096-point window at 48 kHz
 * every band reading is smeared over 85 ms, which is longer than the whole
 * attack of an 808. A cascaded Butterworth band-pass per band gives sample-rate
 * envelope resolution, so the sub meter actually snaps.
 *
 * Cost is ~4 biquads per band per channel per sample: 7 bands × 2 ch × 4 at
 * 48 kHz is about 2.7 M biquad evaluations per second, which is nothing on this
 * class of device and buys a meter you can trust on transients.
 */
class BandSplitter(sampleRate: Int, settings: Settings, historyLength: Int) {

    private class Chain(
        val highPass: Array<Biquad>,
        val lowPass: Array<Biquad>,
    ) {
        fun process(x: Double): Double {
            var v = x
            for (b in highPass) v = b.process(v)
            for (b in lowPass) v = b.process(v)
            return v
        }

        fun reset() {
            highPass.forEach { it.reset() }
            lowPass.forEach { it.reset() }
        }
    }

    private class Band(
        val def: BandDef,
        val left: Chain,
        val right: Chain,
        val history: ScrollBuffer,
    ) {
        var sumSq = 0.0
        var count = 0
        var blockPeak = 0.0

        var rmsDb = PeakHold.FLOOR_DB
        var peakDb = PeakHold.FLOOR_DB
        var holdDb = PeakHold.FLOOR_DB
        var heldMs = 0f

        fun resetAccumulator() {
            sumSq = 0.0
            count = 0
            blockPeak = 0.0
        }
    }

    private var bands: List<Band> = emptyList()
    private val ballistics = Ballistics(0.016f)
    private var holdMs = 900f
    private var fallDbPerSec = 24f
    private var historyLen = historyLength
    private var rate = sampleRate

    init { configure(settings, sampleRate) }

    /**
     * Rebuilds the filter bank. Histories survive a rebuild when the band count
     * is unchanged, so tweaking a crossover does not blank the graphs.
     */
    fun configure(s: Settings, sampleRate: Int) {
        rate = sampleRate
        val nyquist = sampleRate / 2.0
        val qs = Biquad.butterworthQs(s.bandSlope.order)
        val previous = bands

        bands = s.bands.mapIndexed { index, def ->
            val lowNeeded = def.lowHz > 1f
            val highNeeded = def.highHz < nyquist * 0.98
            fun chain() = Chain(
                highPass = if (lowNeeded) {
                    Array(qs.size) { Biquad.highPass(sampleRate, def.lowHz.toDouble(), qs[it]) }
                } else emptyArray(),
                lowPass = if (highNeeded) {
                    Array(qs.size) { Biquad.lowPass(sampleRate, def.highHz.toDouble().coerceAtMost(nyquist * 0.98), qs[it]) }
                } else emptyArray(),
            )
            Band(
                def = def,
                left = chain(),
                right = chain(),
                history = previous.getOrNull(index)?.history?.takeIf { it.capacity == historyLen }
                    ?: ScrollBuffer(historyLen),
            )
        }

        ballistics.attackMs = s.bandAttackMs
        ballistics.releaseMs = s.bandReleaseMs
        ballistics.setFrameInterval(s.hopSize.toFloat() / sampleRate)
        holdMs = s.peakHoldMs
        fallDbPerSec = s.peakFallDbPerSec
    }

    /**
     * Streams [n] new samples through every band. Must be called once per
     * sample-run with no gaps and no repeats — the filters are stateful, and
     * feeding them overlapping blocks would double-count energy.
     */
    fun process(left: FloatArray, right: FloatArray?, n: Int) {
        for (band in bands) {
            var sumSq = band.sumSq
            var peak = band.blockPeak
            val hp = band.left
            for (i in 0 until n) {
                val y = hp.process(left[i].toDouble())
                sumSq += y * y
                val a = abs(y)
                if (a > peak) peak = a
            }
            if (right != null) {
                val rp = band.right
                for (i in 0 until n) {
                    val y = rp.process(right[i].toDouble())
                    sumSq += y * y
                    val a = abs(y)
                    if (a > peak) peak = a
                }
                band.count += n * 2
            } else {
                band.count += n
            }
            band.sumSq = sumSq
            band.blockPeak = peak
        }
    }

    /** Folds the samples accumulated since the last call into the meters. */
    fun tick(dtMs: Float) {
        for (band in bands) {
            if (band.count == 0) continue
            val rms = sqrt(band.sumSq / band.count)
            val targetDb = toDb(rms)
            band.rmsDb = ballistics.stepDt(band.rmsDb, targetDb, dtMs / 1000f)
            band.peakDb = toDb(band.blockPeak)

            if (band.peakDb >= band.holdDb) {
                band.holdDb = band.peakDb
                band.heldMs = 0f
            } else {
                band.heldMs += dtMs
                if (band.heldMs > holdMs) band.holdDb -= fallDbPerSec * dtMs / 1000f
                if (band.holdDb < PeakHold.FLOOR_DB) band.holdDb = PeakHold.FLOOR_DB
            }

            band.history.push(band.rmsDb)
            band.resetAccumulator()
        }
    }

    fun readings(): List<BandReading> = bands.map {
        BandReading(it.def.name, it.def.lowHz, it.def.highHz, it.rmsDb, it.peakDb, it.holdDb, it.history)
    }

    fun reset() {
        for (b in bands) {
            b.left.reset(); b.right.reset()
            b.resetAccumulator()
            b.rmsDb = PeakHold.FLOOR_DB
            b.peakDb = PeakHold.FLOOR_DB
            b.holdDb = PeakHold.FLOOR_DB
            b.history.clear()
        }
    }

    private fun toDb(linear: Double): Float {
        if (linear <= 1e-9) return PeakHold.FLOOR_DB
        val db = 20.0 * log10(linear)
        return if (db < PeakHold.FLOOR_DB) PeakHold.FLOOR_DB else db.toFloat()
    }
}
