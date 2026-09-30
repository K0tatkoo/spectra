package com.n3d.spectra.stems

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * The synth splitter's signal path, without a trained network: against the
 * training code's own reference (a fixture written by
 * train/tools/make_dsp_fixture.py), and against the two answers that need no
 * reference at all — a mask of 0 gives every input back untouched, a mask of 1
 * turns all of it into synth.
 */
class SpectralSplitTest {

    @Test
    fun `the windows overlap to exactly one at the hop`() {
        val layout = fixtureLayout()
        val a = layout.analysisWindow()
        val s = layout.synthesisWindow()
        val n = layout.nFft
        val hop = layout.hop
        for (i in 0 until hop) {
            val sum = a[n - 2 * hop + i] * s[n - 2 * hop + i] + a[n - hop + i] * s[n - hop + i]
            assertEquals("overlap at $i", 1.0, sum.toDouble(), 1e-6)
        }
        for (i in 0 until n - 2 * hop) assertEquals(0f, s[i])
    }

    @Test
    fun `a zero mask gives the inputs back and a full mask makes them all synth`() {
        val layout = fixtureLayout()
        val rng = Random(7)
        val inputs = Array(2) { FloatArray(8192) { (rng.nextFloat() - 0.5f) * 0.6f } }
        for (full in listOf(false, true)) {
            val (synth, rests) = stream(layout, ConstantNet(if (full) 1f else 0f), inputs)
            var worst = 0.0
            for (i in synth.indices) {
                val sum = inputs[0][i] + inputs[1][i]
                worst = maxOf(worst, abs(synth[i] - if (full) sum else 0f).toDouble())
                for (k in 0 until 2) worst = maxOf(worst, abs(rests[k][i] - if (full) 0f else inputs[k][i]).toDouble())
            }
            println("mask ${if (full) 1 else 0}: worst error $worst over ${synth.size} samples")
            assertTrue("mask $full: reconstruction is off by $worst", worst < 2e-6)
        }
    }

    @Test
    fun `matches the training code's reference sample for sample`() {
        val fx = Fixture.load()
        val net = PatternNet(fx)
        val (synth, rests) = stream(fx.layout, net, fx.inputs)

        var powerWorst = 0.0
        for (f in 0 until fx.frames) {
            val got = net.powers[f]
            val want = fx.powers[f]
            val scale = want.max().toDouble()
            for (i in want.indices) {
                val err = abs(got[i] - want[i]) / maxOf(abs(want[i].toDouble()), 1e-6 * scale)
                powerWorst = maxOf(powerWorst, err)
            }
        }
        var outWorst = 0.0
        for (i in synth.indices) {
            outWorst = maxOf(outWorst, abs(synth[i] - fx.synth[i]).toDouble())
            for (k in rests.indices) outWorst = maxOf(outWorst, abs(rests[k][i] - fx.rests[k][i]).toDouble())
        }
        println("against the reference: band powers ${"%.2e".format(powerWorst)} relative, outputs ${"%.2e".format(outWorst)}")
        assertEquals(fx.frames, net.powers.size)
        assertEquals(fx.synth.size, synth.size)
        assertTrue("band powers differ from the reference by $powerWorst", powerWorst < 1e-3)
        assertTrue("outputs differ from the reference by $outWorst", outWorst < 1e-5)
    }

    // ------------------------------------------------------------------------

    /**
     * Runs whole signals through [SpectralSplit] from their first sample, the
     * way the reference does: frames end at every hop, zeros before the start.
     * Returns synth and rests for [0, length − hop).
     */
    private fun stream(layout: SplitLayout, net: MaskNet, inputs: Array<FloatArray>): Pair<FloatArray, Array<FloatArray>> {
        val n = layout.nFft
        val hop = layout.hop
        val total = inputs[0].size
        val split = SpectralSplit(layout, net)
        val frames = Array(inputs.size) { FloatArray(n) }
        val synth = FloatArray(hop)
        val rest = Array(inputs.size) { FloatArray(hop) }
        val outSynth = FloatArray(total - hop)
        val outRest = Array(inputs.size) { FloatArray(total - hop) }
        var pos = hop
        while (pos <= total) {
            for (k in inputs.indices) {
                for (i in 0 until n) {
                    val t = pos - n + i
                    frames[k][i] = if (t < 0) 0f else inputs[k][t]
                }
            }
            split.frame(frames, synth, rest)
            val at = pos - 2 * hop
            if (at >= 0) {
                System.arraycopy(synth, 0, outSynth, at, hop)
                for (k in inputs.indices) System.arraycopy(rest[k], 0, outRest[k], at, hop)
            }
            pos += hop
        }
        return outSynth to outRest
    }

    private class ConstantNet(private val value: Float) : MaskNet {
        override fun step(power: FloatArray, mask: FloatArray) = mask.fill(value)
    }

    /** The fixture's stand-in network: a fixed pattern over band, input and frame. It records what it was shown. */
    private class PatternNet(private val fx: Fixture) : MaskNet {
        val powers = ArrayList<FloatArray>()
        override fun step(power: FloatArray, mask: FloatArray) {
            val frame = powers.size
            powers += power.copyOf()
            val bands = fx.layout.bands
            for (k in fx.layout.inputs.indices) for (b in 0 until bands) {
                mask[k * bands + b] = (0.5 + 0.5 * sin(fx.aBand * b + fx.aInput * k + fx.aFrame * frame)).toFloat()
            }
        }
    }

    private class Fixture(
        val layout: SplitLayout,
        val frames: Int,
        val aBand: Double,
        val aInput: Double,
        val aFrame: Double,
        val inputs: Array<FloatArray>,
        val powers: Array<FloatArray>,
        val synth: FloatArray,
        val rests: Array<FloatArray>,
    ) {
        companion object {
            fun load(): Fixture {
                val json = resource("dsp-fixture.json").toString(Charsets.UTF_8)
                fun num(key: String) = Regex("\"$key\": ([-0-9.e]+)").find(json)!!.groupValues[1]
                fun list(key: String) = Regex("\"$key\": \\[([^]]*)]").find(json)!!.groupValues[1]
                    .split(',').map { it.trim().trim('"') }
                val layout = SplitLayout(
                    num("sample_rate").toInt(),
                    num("n_fft").toInt(),
                    num("hop").toInt(),
                    list("band_edges").map { it.toInt() }.toIntArray(),
                    list("inputs").map { Stem.valueOf(it.uppercase()) },
                )
                val samples = num("samples").toInt()
                val frames = num("frames").toInt()
                val k = layout.inputs.size
                val floats = ByteBuffer.wrap(resource("dsp-fixture.bin")).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                fun take(count: Int) = FloatArray(count).also { floats.get(it) }
                val inputs = Array(k) { take(samples) }
                val powers = Array(frames) { take(k * layout.bands) }
                val synth = take(samples - layout.hop)
                val rests = Array(k) { take(samples - layout.hop) }
                check(!floats.hasRemaining()) { "fixture has trailing data" }
                val mask = Regex("\"mask\": \\{([^}]*)}").find(json)!!.groupValues[1]
                fun coef(key: String) = Regex("\"$key\": ([-0-9.e]+)").find(mask)!!.groupValues[1].toDouble()
                return Fixture(layout, frames, coef("band"), coef("input"), coef("frame"), inputs, powers, synth, rests)
            }

            private fun resource(name: String): ByteArray =
                SpectralSplitTest::class.java.getResourceAsStream("/synthsplit/$name")!!.use { it.readBytes() }
        }
    }

    private fun fixtureLayout(): SplitLayout = Fixture.load().layout
}
