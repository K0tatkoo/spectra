package com.n3d.spectra.dsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class ResamplerTest {

    @Test
    fun `48k to 44k1 keeps pitch and level`() {
        val r = Resampler(48_000, 44_100)
        val input = FloatArray(48_000) { sin(2 * PI * 1000.0 * it / 48_000).toFloat() * 0.5f }
        val out = feed(r, input, Random(3))

        // The length follows the ratio, to within the filter's own delay.
        assertEquals(44_100.0, out.size.toDouble(), 20.0)

        // Frequency by counting rising zero crossings over the steady part.
        val steady = out.copyOfRange(1000, out.size - 1000)
        var crossings = 0
        for (i in 1 until steady.size) if (steady[i - 1] < 0f && steady[i] >= 0f) crossings++
        val hz = crossings * 44_100.0 / steady.size
        assertEquals(1000.0, hz, 2.0)

        // Level: a 0.5 sine has RMS 0.354.
        val rms = sqrt(steady.sumOf { (it * it).toDouble() } / steady.size)
        assertEquals(0.5 / sqrt(2.0), rms, 0.005)
    }

    @Test
    fun `block size does not change the output`() {
        val input = FloatArray(20_000) { sin(2 * PI * 440.0 * it / 48_000).toFloat() }
        val oneShot = feed(Resampler(48_000, 44_100), input, null)
        val chunked = feed(Resampler(48_000, 44_100), input, Random(4))
        assertEquals(oneShot.size, chunked.size)
        assertArrayEquals(oneShot, chunked, 1e-6f)
    }

    @Test
    fun `holds back what 44k1 cannot hold`() {
        // 23 kHz is above the new Nyquist and would fold down to 21.1 kHz. A
        // 32-tap kernel only has room for a gentle skirt up there, and that is
        // a deliberate trade: whatever folds lands above 20 kHz, far above
        // anything the stem model separates by. Knocked down, not removed.
        val r = Resampler(48_000, 44_100)
        val input = FloatArray(48_000) { sin(2 * PI * 23_000.0 * it / 48_000).toFloat() }
        val out = feed(r, input, null)
        val steady = out.copyOfRange(1000, out.size - 1000)
        val peak = steady.maxOf { abs(it) }
        assertTrue("alias at ${20 * kotlin.math.log10(peak.toDouble())} dB", peak < 0.1f)

        // …while the top of the band that matters is still flat.
        val r2 = Resampler(48_000, 44_100)
        val tone = FloatArray(48_000) { sin(2 * PI * 16_000.0 * it / 48_000).toFloat() }
        val passed = feed(r2, tone, null).let { it.copyOfRange(1000, it.size - 1000) }
        val rms = sqrt(passed.sumOf { (it * it).toDouble() } / passed.size)
        assertEquals(1 / sqrt(2.0), rms, 0.02)
    }

    @Test
    fun `same rate is a copy`() {
        val r = Resampler(44_100, 44_100)
        assertTrue(r.passthrough)
        val input = FloatArray(100) { it.toFloat() }
        val out = feed(r, input, null)
        assertArrayEquals(input, out, 0f)
    }

    private fun feed(r: Resampler, input: FloatArray, random: Random?): FloatArray {
        val out = ArrayList<Float>(input.size)
        var i = 0
        while (i < input.size) {
            val n = minOf(input.size - i, random?.let { 1 + it.nextInt(3000) } ?: input.size)
            val chunk = input.copyOfRange(i, i + n)
            val l = FloatArray(r.maxOutput(n))
            val rr = FloatArray(r.maxOutput(n))
            val produced = r.process(chunk, chunk, n, l, rr)
            for (k in 0 until produced) {
                assertEquals(l[k], rr[k], 0f)
                out += l[k]
            }
            i += n
        }
        return out.toFloatArray()
    }
}
