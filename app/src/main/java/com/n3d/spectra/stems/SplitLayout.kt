package com.n3d.spectra.stems

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * How the synth splitter cuts audio into frames, and which stems it reads —
 * everything the app and the training code have to agree on, sample for
 * sample. It travels inside the model file (as ONNX metadata), so a retrained
 * model with a different geometry needs no app change.
 *
 * Frames are [nFft] long and end every [hop] samples, at whole multiples of it
 * in the stems' own absolute index. The windows are the asymmetric pair of a
 * low-delay filter bank: a long analysis window, for the frequency resolution
 * that tells two notes apart, and a synthesis window only 2 × [hop] long at its
 * end. An output sample is final once the two frames whose synthesis windows
 * cover it are in, so the splitter lags its input by one to two hops (12–23 ms
 * at the defaults), instead of by most of the long window as with a symmetric
 * pair. The two windows multiply to a Hann of 2 × [hop], which overlaps to
 * exactly 1 at this hop: an all-pass mask gives the input back unchanged.
 *
 * [bandEdges] group the nFft / 2 + 1 bins into the bands the network sees and
 * masks: one bin per band low down, where notes are close together, wider
 * bands above.
 */
class SplitLayout(
    val sampleRate: Int,
    val nFft: Int,
    val hop: Int,
    val bandEdges: IntArray,
    /** The stems the splitter takes synth out of, in the model's order. */
    val inputs: List<Stem>,
) {
    val bins: Int get() = nFft / 2 + 1
    val bands: Int get() = bandEdges.size - 1

    init {
        require(nFft >= 2 && nFft and (nFft - 1) == 0) { "nFft must be a power of two: $nFft" }
        require(hop > 0 && 2 * hop <= nFft) { "hop must be at most half the frame: $hop of $nFft" }
        require(bandEdges.size >= 2 && bandEdges.first() == 0 && bandEdges.last() == bins) {
            "band edges must run from bin 0 to ${bins}"
        }
        for (i in 1 until bandEdges.size) require(bandEdges[i] > bandEdges[i - 1]) { "band edges must rise" }
        require(inputs.isNotEmpty() && inputs.none { it == Stem.SYNTH } && inputs.distinct().size == inputs.size) {
            "inputs must be distinct model stems: $inputs"
        }
    }

    /** The long, asymmetric analysis window: a slow rise, then the last half of a Hann of 2 × hop. */
    fun analysisWindow(): FloatArray {
        val rise = nFft - hop
        return FloatArray(nFft) { n ->
            val w = if (n < rise) hann(2 * rise, n) else hann(2 * hop, n - (nFft - 2 * hop))
            sqrt(w).toFloat()
        }
    }

    /** Zero but for the last 2 × hop samples, where analysis × synthesis is a Hann of that length. */
    fun synthesisWindow(): FloatArray {
        val analysis = analysisWindow()
        val start = nFft - 2 * hop
        return FloatArray(nFft) { n ->
            when {
                n < start -> 0f
                // Only zero where the product is too (the symmetric case, n = 0).
                n < nFft - hop -> if (analysis[n] == 0f) 0f else (hann(2 * hop, n - start) / analysis[n]).toFloat()
                else -> sqrt(hann(2 * hop, n - start)).toFloat()
            }
        }
    }

    companion object {
        /** The metadata key that marks a model file as a synth splitter, and its contract version. */
        const val FORMAT_KEY = "spectra.format"
        const val FORMAT = "synth-split/1"

        /** Periodic Hann: the one whose copies a hop of half its length apart sum to exactly 1. */
        fun hann(length: Int, n: Int): Double = 0.5 - 0.5 * cos(2.0 * PI * n / length)

        /**
         * Reads a layout from the model's metadata. Throws, naming the problem,
         * if the file is not a splitter this app understands.
         */
        fun fromMetadata(meta: Map<String, String>): SplitLayout {
            val format = meta[FORMAT_KEY]
            require(format == FORMAT) { "not a synth-split model this app can run (format $format)" }
            fun int(key: String) = meta[key]?.trim()?.toIntOrNull() ?: throw IllegalArgumentException("model metadata lacks $key")
            val edges = meta["band_edges"]?.split(',')?.map { it.trim().toInt() }?.toIntArray()
                ?: throw IllegalArgumentException("model metadata lacks band_edges")
            val inputs = meta["inputs"]?.split(',')?.map { Stem.valueOf(it.trim().uppercase()) }
                ?: throw IllegalArgumentException("model metadata lacks inputs")
            return SplitLayout(int("sample_rate"), int("n_fft"), int("hop"), edges, inputs)
        }
    }
}
