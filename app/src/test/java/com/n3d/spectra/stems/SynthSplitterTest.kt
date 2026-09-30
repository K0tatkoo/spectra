package com.n3d.spectra.stems

import com.n3d.spectra.dsp.HistoryRing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * The splitter's worker — its thread, its wake-ups, its jumps — with a
 * stand-in network that calls everything in vocals synth and nothing in other.
 * With that answer the synth lane must be the vocals stem, sample for sample,
 * on the stems' own index: which is what keeps a held-still lane's trigger
 * pointing at the right sample.
 */
class SynthSplitterTest {

    private val layout = SplitLayout(
        StemSeparator.SAMPLE_RATE, 2048, 512,
        (IntArray(129) { it * 8 } + intArrayOf(1025)),
        listOf(Stem.VOCALS, Stem.OTHER),
    )

    private class VocalsAreSynth(private val bands: Int) : MaskNet {
        override fun step(power: FloatArray, mask: FloatArray) {
            for (i in mask.indices) mask[i] = if (i < bands) 1f else 0f
        }
    }

    private val vocals = HistoryRing(StemSeparator.HISTORY)
    private val other = HistoryRing(StemSeparator.HISTORY)
    private val rng = Random(11)
    private val v = FloatArray(StemSeparator.HOP)
    private val o = FloatArray(StemSeparator.HOP)
    /** Every sample ever written, so the outputs can be checked at any index. */
    private val allV = ArrayList<Float>()
    private val allO = ArrayList<Float>()

    private fun splitter() = SynthSplitter(
        open = { SplitModel(layout, VocalsAreSynth(layout.bands)) },
        source = { stem -> if (stem == Stem.VOCALS) vocals else other },
    )

    /** One stem-worker hop, then the wake the stem worker sends. */
    private fun hop(split: SynthSplitter) {
        for (i in v.indices) {
            v[i] = rng.nextFloat() - 0.5f
            o[i] = rng.nextFloat() - 0.5f
            allV += v[i]
            allO += o[i]
        }
        vocals.write(v, 0, v.size)
        other.write(o, 0, o.size)
        split.wake(vocals.written)
    }

    private fun awaitRunning(split: SynthSplitter) {
        val deadline = System.currentTimeMillis() + 10_000
        while (split.status !is SynthSplitter.Status.Running && System.currentTimeMillis() < deadline) Thread.sleep(2)
        assertTrue("splitter did not start: ${split.status}", split.status is SynthSplitter.Status.Running)
    }

    private fun awaitOutput(out: HistoryRing, until: Long) {
        val deadline = System.currentTimeMillis() + 10_000
        while (out.written < until && System.currentTimeMillis() < deadline) Thread.sleep(1)
        assertTrue("splitter stalled at ${out.written}, wanted $until", out.written >= until)
    }

    /** Worst error of the three outputs over [from, to) against the vocals-are-synth answer. */
    private fun worstError(outputs: SynthSplitter.Outputs, from: Long, to: Long): Double {
        val count = (to - from).toInt()
        val synth = FloatArray(count)
        val restV = FloatArray(count)
        val restO = FloatArray(count)
        assertTrue(outputs.raw.getValue(Stem.SYNTH).read(to, synth, count))
        assertTrue(outputs.raw.getValue(Stem.VOCALS).read(to, restV, count))
        assertTrue(outputs.raw.getValue(Stem.OTHER).read(to, restO, count))
        var worst = 0.0
        for (i in 0 until count) {
            val at = (from + i).toInt()
            worst = maxOf(worst, abs(synth[i] - allV[at]).toDouble(), abs(restV[i]).toDouble(), abs(restO[i] - allO[at]).toDouble())
        }
        return worst
    }

    @Test
    fun `the synth lane is on the stems' own index`() {
        val split = splitter()
        split.start()
        awaitRunning(split)
        repeat(400) {
            hop(split)
            if (it % 4 == 3) Thread.sleep(1)
        }
        val outputs = split.outputs!!
        assertEquals(listOf(Stem.SYNTH, Stem.VOCALS, Stem.OTHER), outputs.stems)
        val end = vocals.written - 2 * layout.hop
        awaitOutput(outputs.raw.getValue(Stem.SYNTH), end)
        val from = maxOf(split.cleanFrom, end - 20_000)
        val worst = worstError(outputs, from, end)
        val st = split.status
        split.stop()
        println("aligned run: worst error $worst over ${end - from} samples · $st")
        assertTrue("outputs are off the stems' index (worst $worst)", worst < 1e-5)
        assertEquals(0, (st as SynthSplitter.Status.Running).skips)
        // It trails the stems by one to two hops, never more.
        assertTrue(outputs.raw.getValue(Stem.SYNTH).written >= vocals.written - 2 * layout.hop)
    }

    @Test
    fun `falls behind gracefully and stays aligned after the jump`() {
        val split = splitter()
        split.start()
        awaitRunning(split)
        repeat(50) { hop(split) }
        // A second of stems at once: far more than the splitter will work through.
        val burst = StemSeparator.SAMPLE_RATE / StemSeparator.HOP
        for (i in 0 until burst) {
            for (j in v.indices) {
                v[j] = rng.nextFloat() - 0.5f
                o[j] = rng.nextFloat() - 0.5f
                allV += v[j]
                allO += o[j]
            }
            vocals.write(v, 0, v.size)
            other.write(o, 0, o.size)
        }
        split.wake(vocals.written)
        repeat(200) {
            hop(split)
            if (it % 4 == 3) Thread.sleep(1)
        }
        val outputs = split.outputs!!
        val end = vocals.written - 2 * layout.hop
        awaitOutput(outputs.raw.getValue(Stem.SYNTH), end)
        val st = split.status as SynthSplitter.Status.Running
        val from = split.cleanFrom
        assertTrue("clean point $from is not after the jump", from > 50L * StemSeparator.HOP + StemSeparator.MAX_BACKLOG_FRAMES)
        val worst = worstError(outputs, from, end)
        split.stop()
        println("after a jump: ${st.skips} skip(s), worst error $worst from $from to $end")
        assertTrue("the splitter did not jump", st.skips >= 1)
        assertTrue("outputs are off the stems' index after the jump (worst $worst)", worst < 1e-5)
    }

    @Test
    fun `a model that will not load says so`() {
        val split = SynthSplitter(open = { error("no such model") }, source = { vocals })
        split.start()
        val deadline = System.currentTimeMillis() + 5_000
        while (split.status !is SynthSplitter.Status.Failed && System.currentTimeMillis() < deadline) Thread.sleep(2)
        val st = split.status
        split.stop()
        assertTrue("expected a failure, got $st", st is SynthSplitter.Status.Failed && "no such model" in st.message)
    }
}
