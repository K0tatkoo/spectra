package com.n3d.spectra.dsp

import com.n3d.spectra.TestSignals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The held-still scope, fed the way the app feeds it: blocks of audio into a
 * [HistoryRing], one analysis per block.
 *
 * "Still" is measured directly — the trace from one frame against the next.
 * Both are normalised to about ±1, so an RMS difference of 0.05 is a few
 * pixels on a lane a hundred pixels tall.
 */
class ScopeLockTest {

    private val rate = 44_100
    private val block = 1882

    @Test
    fun `a held note stands still`() {
        val noise = Random(1)
        val frames = run(2.0, spec(28f, 300f)) { t ->
            0.3 * TestSignals.nesTriangle(110.0, t) + 0.01 * noise.nextGaussian()
        }
        val locked = frames.drop(20)
        assertTrue(locked.all { it.mode == TraceMode.PITCH })
        assertEquals(110.0, locked.last().hz.toDouble(), 110.0 * 0.005)
        assertEquals("A2", locked.last().note)
        val worst = locked.zipWithNext { a, b -> rmsDiff(a.points, b.points) }.max()
        println("held note: worst frame-to-frame RMS change $worst")
        assertTrue("picture moves between frames ($worst)", worst < 0.05)
    }

    @Test
    fun `a note stays still under bleed from another instrument`() {
        // A vibrato tone 15 dB under the bass — the kind of leftover a
        // separated stem really carries. The default clean-up has to hold the
        // bass still through it.
        val frames = run(2.0, spec(28f, 300f)) { t ->
            0.3 * TestSignals.nesTriangle(73.42, t) + 0.05 * TestSignals.vibrato(523.0, t)
        }
        val locked = frames.drop(25)
        assertTrue(locked.all { it.mode == TraceMode.PITCH })
        val worst = locked.zipWithNext { a, b -> rmsDiff(a.points, b.points) }.max()
        println("with bleed: worst frame-to-frame RMS change $worst")
        assertTrue("bleed drags the picture ($worst)", worst < 0.08)
    }

    @Test
    fun `a higher note squeezes more cycles into the same window`() {
        val low = run(1.0, spec(28f, 1000f)) { t -> 0.3 * TestSignals.saw(110.0, t) }.last()
        val high = run(1.0, spec(28f, 1000f)) { t -> 0.3 * TestSignals.saw(220.0, t) }.last()
        val cLow = cycles(low.points)
        val cHigh = cycles(high.points)
        println("cycles in window: 110 Hz $cLow, 220 Hz $cHigh")
        assertEquals(2.0, cHigh.toDouble() / cLow, 0.35)
    }

    @Test
    fun `a new note is followed`() {
        val frames = run(2.0, spec(28f, 300f)) { t ->
            val hz = if (t < 1.0) 55.0 else 82.41
            0.3 * TestSignals.nesTriangle(hz, t)
        }
        val before = frames[frames.size / 2 - 3]
        val after = frames.last()
        assertEquals("A1", before.note)
        assertEquals("E2", after.note)
    }

    @Test
    fun `silence is quiet, not amplified noise`() {
        val noise = Random(2)
        val frames = run(0.5, spec(28f, 300f)) { 1e-5 * noise.nextGaussian() }
        assertTrue(frames.all { it.mode == TraceMode.QUIET })
    }

    @Test
    fun `drums freeze on each hit`() {
        val frames = run(2.0, ScopeLaneSpec("Drums", 40f, 400f, 1024, LaneTrigger.TRANSIENT)) { t ->
            0.5 * TestSignals.kick(t, every = 0.4)
        }
        val hits = frames.drop(10).filter { it.mode == TraceMode.HIT }
        assertTrue("drum lane never triggered", hits.size > frames.size / 2)
        // Consecutive frames on the same hit are the same picture.
        var same = 0
        var total = 0
        frames.drop(10).zipWithNext().forEach { (a, b) ->
            if (a.mode == TraceMode.HIT && b.mode == TraceMode.HIT) {
                total++
                if (rmsDiff(a.points, b.points) < 0.02) same++
            }
        }
        println("drums: $same of $total consecutive hit frames identical")
        // A new hit replaces the picture a few times a second; everything else holds.
        assertTrue(same > total * 0.7)
    }

    // ------------------------------------------------------------------------

    private fun spec(minHz: Float, maxHz: Float) =
        ScopeLaneSpec("Test", minHz, maxHz, 4096, LaneTrigger.PITCH)

    private fun run(
        seconds: Double,
        spec: ScopeLaneSpec,
        cleanMs: Float = 60f,
        signal: (Double) -> Double,
    ): List<ScopeTrace> {
        val lock = ScopeLock(rate, spec)
        val raw = HistoryRing(65_536)
        val low = HistoryRing(65_536)
        val filter = Biquad.butterworthQs(4).map { Biquad.lowPass(rate, 1200.0, it) }
        val a = FloatArray(16_384)
        val b = FloatArray(16_384)
        val chunk = FloatArray(block)
        val lp = FloatArray(block)
        val out = ArrayList<ScopeTrace>()
        var i = 0L
        val total = (seconds * rate).toLong()
        while (i < total) {
            for (k in 0 until block) {
                chunk[k] = signal((i + k).toDouble() / rate).toFloat()
                var v = chunk[k].toDouble()
                for (f in filter) v = f.process(v)
                lp[k] = v.toFloat()
            }
            raw.write(chunk, 0, block)
            low.write(lp, 0, block)
            i += block
            val end = raw.written
            assertTrue(raw.read(end, a, a.size) && low.read(end, b, b.size))
            out += lock.analyze(a, b, end, 35f, cleanMs, 512, block * 1000f / rate)
        }
        return out
    }

    private fun rmsDiff(a: FloatArray, b: FloatArray): Double {
        var s = 0.0
        for (i in a.indices) {
            val d = (a[i] - b[i]).toDouble()
            s += d * d
        }
        return sqrt(s / a.size)
    }

    /** Rising zero crossings — whole cycles on screen. */
    private fun cycles(p: FloatArray): Int {
        var n = 0
        for (i in 1 until p.size) if (p[i - 1] < 0f && p[i] >= 0f) n++
        return n
    }

    @Suppress("unused")
    private fun maxAbs(p: FloatArray) = p.maxOf { abs(it) }
}
