package com.n3d.spectra.stems

import com.n3d.spectra.TestSignals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Runs the real [StemSeparator] — its worker thread, its state carrying, its
 * ring buffers — against the real model on the desktop JVM.
 *
 * The sources are synthetic, so what "correct" means is simple: a 2A03-style
 * triangle bass should come out of the bass stem, a vibrato tone out of vocals,
 * a kick out of drums, and the four stems must add back up to the input.
 */
class StemSeparatorTest {

    private val rate = StemSeparator.SAMPLE_RATE

    @Test
    fun `stems add back up to the mix, sample aligned`() {
        val mix = TestSignals.render(2.0, rate) {
            0.3 * TestSignals.nesTriangle(55.0, it) + 0.2 * TestSignals.vibrato(330.0, it) + 0.4 * TestSignals.kick(it)
        }
        val out = run(mix)
        val n = out[0].size
        var worst = 0.0
        for (i in 0 until n) {
            var sum = 0.0
            for (s in out) sum += s[i]
            worst = maxOf(worst, abs(sum - mix[i]))
        }
        println("reconstruction: worst error $worst over $n samples")
        // The graph computes Other as the mix minus the rest, so this is exact
        // to rounding — *if* the hops are carried, ordered and aligned right.
        assertTrue("stems do not reconstruct the mix (worst $worst)", worst < 1e-4)
    }

    @Test
    fun `each source lands in its own stem`() {
        val bass = TestSignals.render(3.0, rate) { 0.3 * TestSignals.nesTriangle(55.0, it) }
        val voice = TestSignals.render(3.0, rate) { 0.2 * TestSignals.vibrato(330.0, it) }
        val kick = TestSignals.render(3.0, rate) { 0.4 * TestSignals.kick(it) }
        val mix = FloatArray(bass.size) { bass[it] + voice[it] + kick[it] }
        val out = run(mix)

        // Skip the first half second: the model's memory starts from silence.
        val from = rate / 2
        val corrBass = correlation(out[Stem.BASS.ordinal], bass, from)
        val corrVoice = correlation(out[Stem.VOCALS.ordinal], voice, from)
        val corrKick = correlation(out[Stem.DRUMS.ordinal], kick, from)
        println("correlation with source: bass $corrBass, vocals $corrVoice, drums $corrKick")
        assertTrue("bass stem is not the triangle ($corrBass)", corrBass > 0.9)
        assertTrue("vocal stem is not the vibrato tone ($corrVoice)", corrVoice > 0.9)
        // Lower bar for the kick: an 808 is half drum, half bass note, and the
        // model — reasonably — hands some of its sub tail to the bass stem.
        // Alone it goes to drums almost entirely; in a mix it shares.
        assertTrue("drum stem is not the kick ($corrKick)", corrKick > 0.7)
    }

    @Test
    fun `falls behind gracefully instead of drifting`() {
        val sep = StemSeparator(model)
        sep.start()
        awaitRunning(sep)
        // Three seconds dumped at once is ten times the backlog the worker will
        // accept: it must skip to the present rather than work through it all…
        val dump = TestSignals.render(3.0, rate) { 0.3 * TestSignals.nesTriangle(110.0, it) }
        sep.push(dump, dump, dump.size)
        // …and then carry on normally with audio that arrives at capture pace.
        val tail = TestSignals.render(0.5, rate) { 0.3 * TestSignals.nesTriangle(110.0, it) }
        var pushed = 0
        while (pushed < tail.size) {
            val n = minOf(1882, tail.size - pushed)
            val l = tail.copyOfRange(pushed, pushed + n)
            sep.push(l, l, n)
            pushed += n
            Thread.sleep(n * 1000L / rate) // real time: a block every 43 ms
        }
        val deadline = System.currentTimeMillis() + 10_000
        while (sep.stems[0].written < tail.size - 2048 && System.currentTimeMillis() < deadline) Thread.sleep(5)
        val st = sep.status
        val separated = sep.stems[0].written
        sep.stop()
        assertTrue("worker did not skip ahead: $st", st is StemSeparator.Status.Running && st.skips >= 1)
        assertTrue("worker stalled after skipping ($separated)", separated >= tail.size - 2048)
        // It separated roughly the paced tail, not the three seconds it skipped.
        assertTrue("worker worked through the dump ($separated)", separated < tail.size + StemSeparator.MAX_BACKLOG_FRAMES)
    }

    // ------------------------------------------------------------------------

    /**
     * Pushes [mono] as stereo in capture-sized blocks, paced like a real
     * capture would be (never more than one block ahead of the worker), and
     * returns the four stems, aligned with the input.
     */
    private fun run(mono: FloatArray): Array<FloatArray> {
        val sep = StemSeparator(model)
        sep.start()
        awaitRunning(sep)
        val block = 1882 // 1024 frames at 48 kHz, resampled
        val out = Array(Stem.entries.size) { FloatArray(mono.size) }
        var collected = 0L
        var pushed = 0
        val t0 = System.nanoTime()
        while (pushed < mono.size) {
            val n = minOf(block, mono.size - pushed)
            val l = mono.copyOfRange(pushed, pushed + n)
            sep.push(l, l, n)
            pushed += n
            // Wait for the worker to catch up to within one hop.
            val target = (pushed / StemSeparator.HOP) * StemSeparator.HOP - StemSeparator.HOP
            val deadline = System.currentTimeMillis() + 10_000
            while (sep.stems.any { it.written < target } && System.currentTimeMillis() < deadline) Thread.sleep(1)
            // The rings only hold 1.5 s, so collect as we go.
            val end = sep.stems.minOf { it.written }
            val count = (end - collected).toInt()
            if (count > 0) {
                for (s in out.indices) {
                    val chunk = FloatArray(count)
                    assertTrue(sep.stems[s].read(end, chunk, count))
                    System.arraycopy(chunk, 0, out[s], collected.toInt(), count)
                }
                collected = end
            }
        }
        val elapsed = (System.nanoTime() - t0) / 1e9
        val st = sep.status
        sep.stop()
        println("separated ${mono.size / rate.toDouble()} s in ${"%.2f".format(elapsed)} s · $st")
        assertTrue("separator failed: $st", st is StemSeparator.Status.Running)
        assertEquals("the worker skipped while being fed at capture pace", 0, (st as StemSeparator.Status.Running).skips)
        return Array(out.size) { out[it].copyOf(collected.toInt()) }
    }

    private fun awaitRunning(sep: StemSeparator) {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            when (val st = sep.status) {
                is StemSeparator.Status.Failed -> throw AssertionError(st.message)
                is StemSeparator.Status.Loading, StemSeparator.Status.Idle -> Thread.sleep(10)
                is StemSeparator.Status.Running -> return
            }
        }
        throw AssertionError("model did not load")
    }

    private fun correlation(a: FloatArray, b: FloatArray, from: Int): Double {
        val n = minOf(a.size, b.size)
        var ab = 0.0
        var aa = 0.0
        var bb = 0.0
        for (i in from until n) {
            ab += a[i].toDouble() * b[i]
            aa += a[i].toDouble() * a[i]
            bb += b[i].toDouble() * b[i]
        }
        return if (aa <= 0 || bb <= 0) 0.0 else ab / sqrt(aa * bb)
    }

    companion object {
        private lateinit var model: File

        @BeforeClass
        @JvmStatic
        fun locateModel() {
            val path = System.getProperty("spectra.stemModel")
            assumeTrue("no model path given", path != null)
            model = File(path!!)
            assumeTrue("model not fetched: $path", model.length() == StemSeparator.BYTES)
        }
    }
}
