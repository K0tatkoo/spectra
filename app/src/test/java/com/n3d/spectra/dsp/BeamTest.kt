package com.n3d.spectra.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * The Oscilloscope page's beam, before any pixels: the interpolation it draws,
 * the clock that paces it through bursty capture, and the Y-T trigger.
 */
class BeamTest {

    private val rate = 48_000

    @Test
    fun `interpolation follows the band-limited curve between samples`() {
        val interpolator = BeamInterpolator()
        val context = BeamInterpolator.CONTEXT
        for ((hz, tolerance) in listOf(1_000.0 to 2e-3, 5_000.0 to 1e-2)) {
            val n = 480
            val src = FloatArray(n + 2 * context) { i -> sin(2 * PI * hz * (i - context) / rate).toFloat() }
            val out = FloatArray(n * BeamInterpolator.FACTOR)
            interpolator.interpolate(src, context, n, out)
            var worst = 0.0
            for (k in out.indices) {
                val t = k.toDouble() / BeamInterpolator.FACTOR / rate
                worst = max(worst, abs(out[k] - sin(2 * PI * hz * t)))
            }
            println("interpolation at $hz Hz: worst error $worst")
            assertTrue("$hz Hz is off the curve by $worst", worst < tolerance)
        }
    }

    @Test
    fun `a still beam stays exactly still`() {
        // Every phase has unity gain, or a constant would buzz between four places.
        val interpolator = BeamInterpolator()
        val context = BeamInterpolator.CONTEXT
        val src = FloatArray(64 + 2 * context) { 0.37f }
        val out = FloatArray(64 * BeamInterpolator.FACTOR)
        interpolator.interpolate(src, context, 64, out)
        for (v in out) assertEquals(0.37f, v, 1e-6f)
    }

    @Test
    fun `the beam draws every sample once, in even slices`() {
        // The phone's capture: 1024 frames at a time. A surface drawing at
        // 60 fps with a millisecond of jitter either way.
        val clock = BeamClock()
        val jitter = Random(1)
        var written = 0L
        var now = 0L
        var drawnTo = -1L
        var worstPacing = 0.0
        var worstLag = 0.0
        for (frame in 0 until 900) {
            val step = 16_666_667L + (jitter.nextGaussian() * 1_000_000).toLong()
            now += step
            while (written + 1024 <= now * rate / 1_000_000_000L) written += 1024
            clock.advance(written, rate, now)
            if (frame == 0) {
                assertTrue(clock.jumped)
            } else {
                assertFalse("jumped at frame $frame", clock.jumped)
                assertEquals("gap or overlap at frame $frame", drawnTo, clock.from)
                if (frame > 120) {
                    // Each slice is the time that frame took, not whatever burst
                    // happened to land: that is what keeps the picture from pulsing.
                    val expected = step * rate / 1e9
                    worstPacing = max(worstPacing, abs(clock.to - clock.from - expected))
                    worstLag = max(worstLag, (written - clock.to) * 1000.0 / rate)
                }
            }
            // Before the first burst there is nothing to draw, and the clock sits at 0.
            if (written > 0) {
                assertTrue("drew past what can be interpolated at frame $frame", clock.to <= written - BeamInterpolator.CONTEXT)
            }
            drawnTo = clock.to
        }
        println("worst slice off its frame's own time by $worstPacing frames, worst lag $worstLag ms")
        assertTrue("uneven slices: $worstPacing frames off", worstPacing < 40.0)
        assertTrue("runs too far behind: $worstLag ms", worstLag < 60.0)
    }

    @Test
    fun `a paused capture holds the beam, and it carries on without a jump`() {
        // While paused the engine stops writing the feed, so the capture's own
        // clock stops; the surface's does not.
        val clock = BeamClock()
        var written = 0L
        var now = 0L
        var captured = 0L
        fun frames(count: Int, capturing: Boolean): List<Long> = List(count) {
            now += 16_666_667L
            if (capturing) {
                captured += 16_666_667L
                while (written + 1024 <= captured * rate / 1_000_000_000L) written += 1024
            }
            clock.advance(written, rate, now)
            // Only the very first frame has nothing to continue from.
            if (now > 16_666_667L) assertFalse("jumped", clock.jumped)
            clock.to - clock.from
        }
        frames(120, capturing = true)
        val paused = frames(60, capturing = false)
        // It drains what it was behind by, then stops, so the picture freezes.
        assertTrue(paused.takeLast(40).all { it == 0L })
        val resumed = frames(120, capturing = true)
        assertTrue(resumed.takeLast(60).all { it in 650L..950L })
    }

    @Test
    fun `a surface that was away jumps to the present`() {
        val clock = BeamClock()
        var written = 0L
        var now = 0L
        repeat(60) {
            now += 16_666_667L
            while (written + 1024 <= now * rate / 1_000_000_000L) written += 1024
            clock.advance(written, rate, now)
        }
        now += 3_000_000_000L
        written += 3L * rate
        clock.advance(written, rate, now)
        assertTrue(clock.jumped)
        assertTrue(written - clock.to < rate / 10)
    }

    @Test
    fun `a sine triggers at the same point of its cycle every sweep`() {
        val sweep = BeamSweep()
        val sweepFrames = 10 * 1.0 * rate / 1000.0
        sweep.configure(rate, sweepFrames)
        val hz = 440.0
        val phases = ArrayList<Double>()
        var lastStart = Double.NaN
        for (n in 0L until rate.toLong()) {
            val v = (0.6 * sin(2 * PI * hz * n / rate)).toFloat()
            sweep.step(n, v, autoFrames = rate * 0.06)
            if (sweep.sweeping && sweep.lastStart != lastStart) {
                lastStart = sweep.lastStart
                if (n > rate / 10) {
                    assertTrue("a sweep started by AUTO on a clean sine", sweep.triggered)
                    val cycles = lastStart * hz / rate
                    phases += cycles - floor(cycles)
                }
            }
            if (sweep.sweeping && sweep.x(n + 1.0, sweepFrames) > 1.0) sweep.end()
        }
        val spread = phases.max() - phases.min()
        println("${phases.size} sweeps, trigger phase spread ${spread * 360} degrees")
        assertTrue(phases.size > 50)
        // A thousandth of a cycle: under a pixel on any screen.
        assertTrue("the trace would shiver: spread $spread cycles", spread < 1e-3)
    }

    @Test
    fun `silence still sweeps, on the AUTO timer`() {
        val sweep = BeamSweep()
        sweep.configure(rate, 480.0)
        var sweeps = 0
        var lastStart = Double.NaN
        for (n in 0L until rate.toLong()) {
            sweep.step(n, 0f, autoFrames = rate * 0.06)
            if (sweep.sweeping && sweep.lastStart != lastStart) {
                lastStart = sweep.lastStart
                sweeps++
                assertFalse(sweep.triggered)
            }
            if (sweep.sweeping && sweep.x(n + 1.0, 480.0) > 1.0) sweep.end()
        }
        // A 60 ms wait and a 10 ms sweep: about fourteen a second.
        assertTrue("only $sweeps sweeps", sweeps in 12..16)
    }
}
