package com.n3d.spectra.dsp

import com.n3d.spectra.settings.FreqScale
import com.n3d.spectra.settings.Settings
import com.n3d.spectra.settings.Weighting
import kotlin.math.log10
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Windowed STFT magnitude spectrum with display corrections and ballistics.
 *
 * Normalisation is chosen so a full-scale sine sitting on a bin reads exactly
 * 0 dBFS with any window: magnitude is divided by N and by the window's coherent
 * gain, and doubled for every bin except DC and Nyquist (which have no mirror
 * to fold in). Everything downstream — grid labels, band readouts, the
 * spectrogram range — assumes that reference.
 */
class SpectrumAnalyzer(sampleRate: Int, settings: Settings, spectrogramRows: Int) {

    var sampleRate: Int = sampleRate
        private set
    var fftSize: Int = settings.fftSize
        private set

    private var fft = Fft(fftSize)
    private var win = settings.window.build(fftSize)

    private var re = FloatArray(fftSize)
    private var im = FloatArray(fftSize)

    /** Raw, corrected magnitudes for the newest block. */
    var rawDb = FloatArray(fftSize / 2 + 1) { PeakHold.FLOOR_DB }
        private set
    /** Ballistically smoothed magnitudes — what actually gets drawn. */
    var smoothedDb = FloatArray(fftSize / 2 + 1) { PeakHold.FLOOR_DB }
        private set

    /** Per-bin weighting + tilt, precomputed because it never changes per frame. */
    private var correctionDb = FloatArray(fftSize / 2 + 1)

    private val ballistics = Ballistics(0.016f)
    var peaks = PeakHold(fftSize / 2 + 1)
        private set

    /** For each spectrogram row, the half-open bin range that feeds it. */
    private var rowBinStart = IntArray(spectrogramRows)
    private var rowBinEnd = IntArray(spectrogramRows)
    private var rows = spectrogramRows

    init { configure(settings, sampleRate) }

    /**
     * Applies a settings change. Reallocates only when the transform size or the
     * window actually changed, so dragging a ballistics slider is free.
     */
    fun configure(s: Settings, rate: Int) {
        val sizeChanged = s.fftSize != fftSize || rate != sampleRate
        sampleRate = rate

        if (sizeChanged) {
            fftSize = s.fftSize
            fft = Fft(fftSize)
            re = FloatArray(fftSize)
            im = FloatArray(fftSize)
            val bins = fftSize / 2 + 1
            rawDb = FloatArray(bins) { PeakHold.FLOOR_DB }
            smoothedDb = FloatArray(bins) { PeakHold.FLOOR_DB }
            correctionDb = FloatArray(bins)
            peaks = peaks.resize(bins)
        }
        win = s.window.build(fftSize)

        ballistics.attackMs = s.attackMs
        ballistics.releaseMs = s.releaseMs
        ballistics.setFrameInterval(s.hopSize.toFloat() / sampleRate)

        peaks.enabled = s.peakHold
        peaks.holdMs = s.peakHoldMs
        peaks.fallDbPerSec = s.peakFallDbPerSec

        val binHz = sampleRate.toFloat() / fftSize
        for (k in correctionDb.indices) {
            val f = k * binHz
            var c = weightingDb(s.weighting, f)
            if (s.tiltDbPerOct != 0f && f > 0f) {
                c += s.tiltDbPerOct * (ln(f / 1000f) / LN2).toFloat()
            }
            correctionDb[k] = c
        }

        buildSpectrogramRows(s)
    }

    private fun buildSpectrogramRows(s: Settings) {
        val binHz = sampleRate.toFloat() / fftSize
        val nyquistBin = fftSize / 2
        val lo = s.minHz.coerceAtLeast(1f)
        val hi = s.maxHz.coerceAtMost(sampleRate / 2f)
        rowBinStart = IntArray(rows)
        rowBinEnd = IntArray(rows)
        for (r in 0 until rows) {
            // Row 0 is the bottom of the picture and the bottom of the range.
            val t0 = r.toFloat() / rows
            val t1 = (r + 1).toFloat() / rows
            val f0: Float
            val f1: Float
            if (s.freqScale == FreqScale.LOG) {
                f0 = lo * (hi / lo).pow(t0)
                f1 = lo * (hi / lo).pow(t1)
            } else {
                f0 = lo + (hi - lo) * t0
                f1 = lo + (hi - lo) * t1
            }
            var b0 = (f0 / binHz).toInt().coerceIn(0, nyquistBin)
            var b1 = (f1 / binHz).toInt().coerceIn(0, nyquistBin)
            // At the bottom of a log scale many rows land inside one bin; give
            // each row at least that bin so the picture has no dead stripes.
            if (b1 <= b0) b1 = b0 + 1
            if (b1 > nyquistBin) { b1 = nyquistBin + 1; b0 = (b1 - 1).coerceAtLeast(0) }
            rowBinStart[r] = b0
            rowBinEnd[r] = b1
        }
    }

    /**
     * Runs one transform over [block] (already windowed-length, oldest sample
     * first) and advances the ballistics by [dtMs].
     */
    fun process(block: FloatArray, dtMs: Float) {
        val n = fftSize
        val w = win.samples
        for (i in 0 until n) {
            re[i] = block[i] * w[i]
            im[i] = 0f
        }
        fft.transform(re, im)

        val norm = 1f / (n * win.coherentGain)
        val bins = n / 2
        for (k in 0..bins) {
            val mag = sqrt(re[k] * re[k] + im[k] * im[k])
            val scaled = if (k == 0 || k == bins) mag * norm else 2f * mag * norm
            val db = 20f * log10(scaled + 1e-12f) + correctionDb[k]
            rawDb[k] = if (db < PeakHold.FLOOR_DB) PeakHold.FLOOR_DB else db
        }

        ballistics.stepAll(smoothedDb, rawDb)
        peaks.update(smoothedDb, dtMs)
    }

    /**
     * Quantises the current spectrum into one spectrogram column, bottom row
     * first. Rows that span several bins take the maximum, not the mean: a
     * narrow resonance that averages away is exactly what the user is looking
     * for.
     */
    fun fillSpectrogramColumn(out: ByteArray, floorDb: Float, ceilDb: Float) {
        val span = (ceilDb - floorDb).coerceAtLeast(1f)
        for (r in 0 until rows) {
            var peak = PeakHold.FLOOR_DB
            for (b in rowBinStart[r] until rowBinEnd[r]) {
                val v = smoothedDb[b]
                if (v > peak) peak = v
            }
            val t = ((peak - floorDb) / span).coerceIn(0f, 1f)
            out[r] = (t * 255f).toInt().toByte()
        }
    }

    companion object {
        private const val LN2 = 0.6931471805599453

        /**
         * IEC 61672 A and C weighting from the analogue pole definitions. These
         * are the response of the weighting network, not a table interpolation,
         * so they stay correct at any bin spacing.
         */
        fun weightingDb(weighting: Weighting, f: Float): Float {
            if (weighting == Weighting.Z || f <= 0f) return 0f
            val f2 = f.toDouble() * f
            val c1 = 20.598997 * 20.598997
            val c2 = 107.65265 * 107.65265
            val c3 = 737.86223 * 737.86223
            val c4 = 12194.217 * 12194.217
            return when (weighting) {
                Weighting.A -> {
                    val num = c4 * f2 * f2
                    val den = (f2 + c1) * sqrt((f2 + c2) * (f2 + c3)) * (f2 + c4)
                    (20.0 * log10(num / den) + 2.0).toFloat()
                }
                Weighting.C -> {
                    val num = c4 * f2
                    val den = (f2 + c1) * (f2 + c4)
                    (20.0 * log10(num / den) + 0.06).toFloat()
                }
                Weighting.Z -> 0f
            }
        }
    }
}
