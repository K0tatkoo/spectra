package com.n3d.spectra.dsp

/**
 * One immutable snapshot of everything the four surfaces might want to draw.
 *
 * Arrays that are cheap and change every frame (the spectrum, the scope trace,
 * the goniometer cloud) are copied into the frame. Arrays that are large and
 * mostly unchanged (the spectrogram, the band histories) are *referenced* — the
 * frame carries a pointer to the engine's own ring buffers and the painter
 * snapshots them itself. Copying 128 KB of spectrogram sixty times a second to
 * redraw the two columns that changed would be the single most expensive thing
 * in the app.
 */
class AnalysisFrame(
    val seq: Long,
    val sampleRate: Int,
    val fftSize: Int,
    /** Hz per FFT bin. */
    val binHz: Float,

    /** Display-corrected magnitudes, dBFS, index 0..fftSize/2. */
    val magnitudesDb: FloatArray,
    /** Peak-hold trace over [magnitudesDb], or empty when peak hold is off. */
    val peakDb: FloatArray,

    val bands: List<BandReading>,

    /** Decimated scope traces, −1..1. [waveR] is empty for a mono source. */
    val waveL: FloatArray,
    val waveR: FloatArray,

    /** Goniometer cloud as interleaved x,y in −1..1. Empty for mono. */
    val gonio: FloatArray,
    val correlation: Float,
    val stereoWidth: Float,

    val loudness: LoudnessReading,

    val rmsDbL: Float,
    val rmsDbR: Float,
    val peakDbL: Float,
    val peakDbR: Float,

    val clipped: Boolean,
    /** True once the input has been digital silence for long enough to be suspicious. */
    val silent: Boolean,

    val spectrogram: SpectrogramBuffer,
) {
    val binCount: Int get() = magnitudesDb.size

    /** Centre frequency of bin [i]. */
    fun freqOf(i: Int): Float = i * binHz
}

class BandReading(
    val name: String,
    val lowHz: Float,
    val highHz: Float,
    /** Smoothed band RMS in dBFS (full-scale sine in band ≈ −3 dBFS). */
    val rmsDb: Float,
    /** Sample-accurate band peak in dBFS, unsmoothed. */
    val peakDb: Float,
    /** Peak-hold marker in dBFS. */
    val holdDb: Float,
    /** Shared scrolling history of [rmsDb]; snapshot it, do not mutate it. */
    val history: ScrollBuffer,
)

class LoudnessReading(
    /** 400 ms window, LUFS. */
    val momentary: Float,
    /** 3 s window, LUFS. */
    val shortTerm: Float,
    /** Gated integrated loudness since the last reset, LUFS. */
    val integrated: Float,
    /** Loudness range, LU. */
    val range: Float,
    /** Inter-sample true peak since reset, dBTP. */
    val truePeakDb: Float,
    /** Seconds of audio folded into [integrated]. */
    val integratedSeconds: Float,
) {
    companion object {
        /** BS.1770 uses −∞ for "not enough audio yet"; −70 is the absolute gate. */
        const val SILENT = -70f
        val EMPTY = LoudnessReading(SILENT, SILENT, SILENT, 0f, -144f, 0f)
    }
}
