package com.n3d.spectra.paint

import com.n3d.spectra.dsp.StereoFeed
import com.n3d.spectra.settings.OscMode
import com.n3d.spectra.settings.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The tube, fed the way the app feeds it: the capture's bursts into a feed, a
 * surface drawing sixty times a (simulated) second, at phone size and density.
 */
class CrtTest {

    private val rate = 48_000

    /** One surface: a tube, its feed and a simulated clock. */
    private inner class Rig(val settings: Settings, val source: (Long) -> Pair<Float, Float>) {
        val feed = StereoFeed(32_768)
        val crt = Crt()
        val layout = CrtLayout().apply { compute(0f, 0f, 1000f, 1100f, 2.8f, settings.oscMode, compact = false) }
        private val l = FloatArray(1024)
        private val r = FloatArray(1024)
        var written = 0L
        var captured = 0L
        var now = 0L

        fun run(frames: Int, capturing: Boolean = true) {
            repeat(frames) {
                now += 16_666_667L
                if (capturing) {
                    captured += 16_666_667L
                    while (written + 1024 <= captured * rate / 1_000_000_000L) {
                        for (i in 0 until 1024) {
                            val (x, y) = source(written + i)
                            l[i] = x
                            r[i] = y
                        }
                        feed.write(l, r, 1024)
                        written += 1024
                    }
                }
                crt.render(feed, rate, settings, layout, now)
            }
        }

        /** Green channel at a point on the glass, in the layout's pixels. */
        fun green(x: Float, y: Float): Int {
            val px = (x / 1000f * crt.width).toInt().coerceIn(0, crt.width - 1)
            val py = (y / 1100f * crt.height).toInt().coerceIn(0, crt.height - 1)
            return Palette.green(crt.pixels[py * crt.width + px])
        }

        /** Where full-scale (x, y) lands on the glass, in the layout's pixels. */
        fun at(x: Float, y: Float): Pair<Float, Float> {
            val cx = (layout.gratL + layout.gratR) / 2f
            val cy = (layout.gratT + layout.gratB) / 2f
            return (cx + x * (layout.gratR - layout.gratL) / 2f) to (cy - y * (layout.gratB - layout.gratT) / 2f)
        }

        /** The brightest green within a few pixels of full-scale (x, y). */
        fun peakNear(x: Float, y: Float): Int {
            val (gx, gy) = at(x, y)
            var best = 0
            for (dy in -6..6) for (dx in -6..6) best = max(best, green(gx + dx, gy + dy))
            return best
        }
    }

    @Test
    fun `brightness is time — the beam's dwell points outshine its jumps`() {
        // A square wave across: the beam rests at each end and flies between.
        val rig = Rig(Settings()) { n ->
            val x = if ((n / 120) % 2 == 0L) -0.5f else 0.5f
            x to 0f
        }
        rig.run(60)
        val end = rig.peakNear(0.5f, 0f)
        val middle = rig.peakNear(0f, 0f)
        println("dwell point $end, mid-jump $middle")
        assertTrue(end > 200)
        assertTrue("a jump as bright as a dwell ($middle vs $end)", middle < end * 0.6)
    }

    @Test
    fun `a circle comes out as a ring of even brightness`() {
        val rig = Rig(Settings()) { n ->
            val t = n.toDouble() / rate
            (0.6 * cos(2 * PI * 220 * t)).toFloat() to (0.6 * sin(2 * PI * 220 * t)).toFloat()
        }
        rig.run(60)
        val ring = (0 until 32).map { k ->
            val a = 2 * PI * k / 32
            rig.peakNear((0.6 * cos(a)).toFloat(), (0.6 * sin(a)).toFloat())
        }
        val centre = rig.peakNear(0f, 0f)
        println("ring ${ring.min()}..${ring.max()}, centre $centre")
        assertTrue(ring.min() > 120)
        assertTrue("uneven ring: ${ring.min()}..${ring.max()}", ring.min() > ring.max() * 0.8)
        assertTrue("the centre of a ring lit up ($centre)", centre < ring.min() / 3)
    }

    @Test
    fun `silence rests the beam in the middle`() {
        val rig = Rig(Settings()) { 0f to 0f }
        rig.run(30)
        val centre = rig.peakNear(0f, 0f)
        val away = rig.peakNear(0.3f, 0.3f)
        assertTrue(centre > 240)
        assertTrue(away < 40)
    }

    @Test
    fun `a paused capture freezes the picture instead of fading it`() {
        val rig = Rig(Settings()) { n ->
            val t = n.toDouble() / rate
            (0.5 * sin(2 * PI * 150 * t)).toFloat() to (0.5 * sin(2 * PI * 225 * t)).toFloat()
        }
        rig.run(60)
        rig.run(10, capturing = false)
        val frozen = rig.crt.pixels.copyOf()
        rig.run(30, capturing = false)
        assertFalse(rig.crt.changed)
        assertTrue(frozen.contentEquals(rig.crt.pixels))
        assertTrue(frozen.count { Palette.green(it) > 150 } > 500)
    }

    @Test
    fun `a Y-T sine stands still from frame to frame`() {
        val rig = Rig(Settings(oscMode = OscMode.YT, oscTimeDivMs = 1f)) { n ->
            val v = (0.6 * sin(2 * PI * 440 * n.toDouble() / rate)).toFloat()
            v to v
        }
        rig.run(60)
        var worst = 0.0
        var worstLit = 0.0
        repeat(40) {
            val before = rig.crt.pixels.copyOf()
            rig.run(1)
            val after = rig.crt.pixels
            var diff = 0L
            var lit = 0L
            var litDiff = 0L
            for (i in before.indices) {
                val a = Palette.green(before[i])
                val b = Palette.green(after[i])
                diff += abs(a - b)
                if (a > 120) {
                    lit += a
                    litDiff += abs(a - b)
                }
            }
            worst = max(worst, diff.toDouble() / before.size)
            worstLit = max(worstLit, litDiff.toDouble() / lit)
        }
        println("Y-T sine: worst mean change per pixel between frames $worst / 255; on the trace itself ${worstLit * 100} %")
        // What does change is brightness, not position: 88 sweeps a second
        // against 60 frames means some frames get one new sweep and some two,
        // and the 25 ms afterglow smooths that beat to about 7 %. A real tube
        // has the same beat, only faster than the eye.
        assertTrue("the trace moves ($worst)", worst < 1.5)
        assertTrue("the trace flickers (${worstLit * 100} %)", worstLit < 0.10)
        assertTrue(rig.crt.triggered)
    }
}
