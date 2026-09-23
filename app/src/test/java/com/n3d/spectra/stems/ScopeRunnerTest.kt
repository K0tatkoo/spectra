package com.n3d.spectra.stems

import com.n3d.spectra.TestSignals
import com.n3d.spectra.dsp.ScopeFrame
import com.n3d.spectra.dsp.StemsState
import com.n3d.spectra.dsp.TraceMode
import com.n3d.spectra.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The whole Stems page pipeline as the engine drives it: 48 kHz capture blocks
 * in, resampled, separated by the real model, and held still lane by lane.
 */
class ScopeRunnerTest {

    @Test
    fun `a synthetic band comes out as held-still stems`() {
        val path = System.getProperty("spectra.stemModel")
        assumeTrue(path != null && File(path).length() == StemSeparator.BYTES)

        val rate = 48_000
        val block = 1024
        val runner = ScopeRunner(rate, modelFile = { File(path!!) }, hooks = { StemWorkerHooks.NONE })
        val wants = ScopeRunner.Wants(stems = true, hold = true, nes = false)
        val settings = Settings()
        val blockMs = block * 1000f / rate

        var frame: ScopeFrame? = null
        val frames = ArrayList<ScopeFrame>()
        var t = 0L
        val total = rate * 4L
        val left = FloatArray(block)
        while (t < total) {
            for (i in 0 until block) {
                val s = (t + i).toDouble() / rate
                left[i] = (
                    0.3 * TestSignals.nesTriangle(55.0, s) +
                        0.2 * TestSignals.vibrato(330.0, s) +
                        0.4 * TestSignals.kick(s)
                    ).toFloat()
            }
            runner.process(left, left, block, wants, settings, blockMs)
            t += block
            frame = runner.latest
            frame?.let { frames += it }
            // Real time, near enough: the worker has to keep up with a capture,
            // not with a loop that hands it four seconds at once.
            Thread.sleep(blockMs.toLong())
        }
        runner.release()

        assertNotNull(frame)
        val info = frame!!.stemsInfo!!
        println("stems: ${info.state} load ${info.load} · ${info.msPerHop} ms/hop · skips ${info.skips}")
        assertEquals(StemsState.RUNNING, info.state)

        // Judge the last second, once the model and the locks have settled.
        val settled = frames.takeLast(40)
        fun lane(name: String) = settled.map { f -> f.stems.first { it.name == name }.trace }

        val bass = lane("Bass")
        val bassLocked = bass.count { it.mode == TraceMode.PITCH && it.note == "A1" }
        println("bass: $bassLocked/${bass.size} frames locked to A1 · last ${bass.last().note} ${bass.last().hz} Hz")
        assertTrue(bassLocked > bass.size * 0.8)

        val vocals = lane("Vocals")
        val vocalsLocked = vocals.count { it.mode == TraceMode.PITCH && it.note == "E4" }
        println("vocals: $vocalsLocked/${vocals.size} frames locked to E4 · last ${vocals.last().note} ${vocals.last().hz} Hz")
        assertTrue(vocalsLocked > vocals.size * 0.8)

        val drums = lane("Drums")
        val drumHits = drums.count { it.mode == TraceMode.HIT }
        println("drums: $drumHits/${drums.size} frames frozen on a hit")
        assertTrue(drumHits > drums.size * 0.6)

        // And the mix, for the Waveform page's "hold still": the loudest
        // periodic thing in it is the bass.
        val hold = settled.mapNotNull { it.hold }
        println("hold: ${hold.last().mode} ${hold.last().note} ${hold.last().hz} Hz")
        assertTrue(hold.count { it.mode == TraceMode.PITCH } > hold.size / 2)
    }
}

/** No model needed: the Waveform page's two modes, and switching between them. */
class ScopeRunnerModesTest {

    @Test
    fun `switching from 2A03 to hold still keeps drawing`() {
        val rate = 48_000
        val block = 1024
        val runner = ScopeRunner(rate, modelFile = { error("stems are not wanted here") }, hooks = { StemWorkerHooks.NONE })
        val settings = Settings()
        val blockMs = block * 1000f / rate
        val buf = FloatArray(block)
        var t = 0L
        fun play(wants: ScopeRunner.Wants, seconds: Double) {
            val until = t + (seconds * rate).toLong()
            while (t < until) {
                for (i in 0 until block) buf[i] = (0.4 * TestSignals.nesTriangle(55.0, (t + i).toDouble() / rate)).toFloat()
                runner.process(buf, null, block, wants, settings, blockMs)
                t += block
            }
        }
        play(ScopeRunner.Wants(stems = false, hold = false, nes = true), 1.5)
        val nes = runner.latestNes
        assertNotNull("2A03 mode found no triangle", nes)
        assertEquals("A1", nes!!.note)

        play(ScopeRunner.Wants(stems = false, hold = true, nes = false), 1.0)
        val hold = runner.latest?.hold
        assertNotNull("hold still drew nothing after the switch", hold)
        assertEquals(TraceMode.PITCH, hold!!.mode)
        assertEquals("A1", hold.note)
    }
}
