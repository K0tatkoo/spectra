package com.n3d.spectra.stems

import com.n3d.spectra.dsp.Fft

/**
 * The network half of the synth splitter: band powers in, masks out, one frame
 * at a time, carrying whatever memory it has between calls. Kept apart from the
 * signal path so the tests can check that path against a stand-in whose answer
 * is known.
 */
interface MaskNet : AutoCloseable {
    /**
     * [power] holds each input's band powers — inputs × bands, in
     * [SplitLayout.inputs] order — and [mask] receives, in the same layout, the
     * share of each band that is synth, 0 to 1.
     */
    fun step(power: FloatArray, mask: FloatArray)

    override fun close() {}
}

/** A loaded splitter: what its frames look like, and the network that masks them. */
class SplitModel(val layout: SplitLayout, val net: MaskNet) : AutoCloseable {
    override fun close() = net.close()
}

/**
 * The synth splitter's signal path, one frame at a time: each input stem is
 * windowed and transformed, the network is shown its band powers and answers
 * with a mask per band, and the masked spectra are transformed back and
 * overlap-added into the synth stem. What each input gives up to the synth is
 * subtracted from it, so the outputs add back up to the inputs exactly — the
 * same "nothing lost, nothing invented" rule the stem model follows.
 *
 * A mask never lets more of a band through than was there. The worst a wrong
 * answer can do is put the right sound in the wrong lane.
 *
 * No Android types: the unit tests run this on the desktop JVM, and it is the
 * exact arithmetic the training code (train/synthsplit/dsp.py) reproduces in
 * batch. Not thread safe: the splitter worker owns it.
 */
class SpectralSplit(val layout: SplitLayout, private val net: MaskNet) {

    private val n = layout.nFft
    private val hop = layout.hop
    private val count = layout.inputs.size
    private val bins = layout.bins
    private val bands = layout.bands

    private val fft = Fft(n)
    private val analysis = layout.analysisWindow()
    private val synthesis = layout.synthesisWindow()
    private val re = FloatArray(n)
    private val im = FloatArray(n)
    private val specRe = Array(count) { FloatArray(bins) }
    private val specIm = Array(count) { FloatArray(bins) }
    private val power = FloatArray(count * bands)
    private val mask = FloatArray(count * bands)
    private val bandOf = IntArray(bins).also { b ->
        for (band in 0 until bands) for (bin in layout.bandEdges[band] until layout.bandEdges[band + 1]) b[bin] = band
    }
    private val bandWidth = FloatArray(bands) { (layout.bandEdges[it + 1] - layout.bandEdges[it]).toFloat() }

    /** The second half of the last frame's synthesis, per input, waiting for the next frame's first half. */
    private val pending = Array(count) { FloatArray(hop) }
    /** What each input gives to the synth, final, for the hop this frame completes. */
    private val part = Array(count) { FloatArray(hop) }

    /**
     * Takes one frame — the [SplitLayout.nFft] newest samples of each input,
     * oldest first, ending at a multiple of the hop — and completes the hop
     * that lies two hops before its end: [synth] gets the synth there, and
     * [rest] each input with its synth taken out, all [SplitLayout.hop] long.
     */
    fun frame(frames: Array<FloatArray>, synth: FloatArray, rest: Array<FloatArray>) {
        // Two real inputs share one complex transform, one as the real part and
        // one as the imaginary, and are told apart again by symmetry.
        var a = 0
        while (a < count) {
            val b = a + 1
            val xa = frames[a]
            for (i in 0 until n) re[i] = xa[i] * analysis[i]
            if (b < count) {
                val xb = frames[b]
                for (i in 0 until n) im[i] = xb[i] * analysis[i]
            } else {
                java.util.Arrays.fill(im, 0f)
            }
            fft.transform(re, im)
            unpack(a, if (b < count) b else -1)
            a += 2
        }

        java.util.Arrays.fill(power, 0f)
        for (input in 0 until count) {
            val off = input * bands
            val sr = specRe[input]
            val si = specIm[input]
            for (bin in 0 until bins) power[off + bandOf[bin]] += sr[bin] * sr[bin] + si[bin] * si[bin]
            for (band in 0 until bands) power[off + band] /= bandWidth[band]
        }
        net.step(power, mask)

        a = 0
        while (a < count) {
            val b = a + 1
            inverse(a, if (b < count) b else -1)
            overlapAdd(a, re)
            if (b < count) overlapAdd(b, im)
            a += 2
        }

        val start = n - 2 * hop
        for (i in 0 until hop) {
            var s = 0f
            for (input in 0 until count) s += part[input][i]
            synth[i] = s
        }
        for (input in 0 until count) {
            val x = frames[input]
            val p = part[input]
            val r = rest[input]
            for (i in 0 until hop) r[i] = x[start + i] - p[i]
        }
    }

    /**
     * Forgets the half-finished overlap from before a jump in the input. The
     * network keeps its memory — what follows is nearly always the same song.
     */
    fun resetOverlap() {
        for (p in pending) java.util.Arrays.fill(p, 0f)
    }

    /** Splits the packed transform in [re]/[im] into the spectra of inputs [a] and [b] (b < 0: none). */
    private fun unpack(a: Int, b: Int) {
        val ar = specRe[a]
        val ai = specIm[a]
        for (k in 0 until bins) {
            val j = (n - k) and (n - 1)
            val zr = re[k]
            val zi = im[k]
            val cr = re[j]
            val ci = im[j]
            ar[k] = 0.5f * (zr + cr)
            ai[k] = 0.5f * (zi - ci)
            if (b >= 0) {
                specRe[b][k] = 0.5f * (zi + ci)
                specIm[b][k] = 0.5f * (cr - zr)
            }
        }
    }

    /**
     * Masks inputs [a] and [b] and transforms both back at once: the real part
     * of the result is [a]'s synth, the imaginary part [b]'s. Scaled by 1 / n.
     */
    private fun inverse(a: Int, b: Int) {
        val ar = specRe[a]
        val ai = specIm[a]
        val offA = a * bands
        val offB = b * bands
        for (k in 0 until bins) {
            val ma = mask[offA + bandOf[k]]
            val yar = ma * ar[k]
            val yai = ma * ai[k]
            var ybr = 0f
            var ybi = 0f
            if (b >= 0) {
                val mb = mask[offB + bandOf[k]]
                ybr = mb * specRe[b][k]
                ybi = mb * specIm[b][k]
            }
            // W = Ya + i·Yb, then its conjugate for the inverse by forward transform.
            re[k] = yar - ybi
            im[k] = -(yai + ybr)
            if (k in 1 until n / 2) {
                re[n - k] = yar + ybi
                im[n - k] = -(ybr - yai)
            }
        }
        fft.transform(re, im)
        val scale = 1f / n
        for (i in 0 until n) {
            re[i] *= scale
            im[i] *= -scale
        }
    }

    /** Adds one input's windowed synthesis into its overlap, completing one hop of [part]. */
    private fun overlapAdd(input: Int, y: FloatArray) {
        val start = n - 2 * hop
        val pend = pending[input]
        val out = part[input]
        for (i in 0 until hop) {
            out[i] = pend[i] + y[start + i] * synthesis[start + i]
            pend[i] = y[start + hop + i] * synthesis[start + hop + i]
        }
    }
}
