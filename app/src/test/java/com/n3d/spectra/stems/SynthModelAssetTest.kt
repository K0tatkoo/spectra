package com.n3d.spectra.stems

import ai.onnxruntime.OrtEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.exp
import kotlin.random.Random

/**
 * The synth model the app ships (assets/stems/synth-split.onnx), opened the way
 * the phone opens it: its layout must be one this app can run, it must carry its
 * state frame after frame, and every mask must be a gain between 0 and 1. Prints
 * what a frame costs on this machine's CPU (the phone's middle core is slower;
 * the app runs it 86 times a second, off the core the stem model uses).
 */
class SynthModelAssetTest {

    @Test
    fun `the shipped synth model loads and answers sane masks`() {
        val file = listOf(File("src/main/assets/${SynthModel.ASSET}"), File("app/src/main/assets/${SynthModel.ASSET}"))
            .firstOrNull { it.isFile }
        assumeTrue("no synth model in the assets", file != null)
        OnnxMaskNet.open(OrtEnvironment.getEnvironment(), file!!.readBytes()).use { loaded ->
            assertEquals(listOf(Stem.VOCALS, Stem.OTHER), loaded.layout.inputs)
            val features = loaded.layout.inputs.size * loaded.layout.bands
            val rnd = Random(3)
            // Band powers that drift like music's: log-normal around plausible levels.
            val level = DoubleArray(features) { rnd.nextDouble(-8.0, 0.0) }
            val power = FloatArray(features)
            val mask = FloatArray(features)
            val frames = 2000
            var low = 1f
            var high = 0f
            var nanos = 0L
            for (f in 0 until frames) {
                for (i in 0 until features) {
                    level[i] += rnd.nextDouble(-0.3, 0.3)
                    power[i] = exp(level[i]).toFloat()
                }
                val t = System.nanoTime()
                loaded.net.step(power, mask)
                if (f >= 100) nanos += System.nanoTime() - t
                for (m in mask) {
                    assertTrue("mask $m is not a gain", m in 0f..1f && !m.isNaN())
                    low = minOf(low, m)
                    high = maxOf(high, m)
                }
            }
            val perFrame = nanos / (frames - 100) / 1000.0
            println("synth model: masks in [$low, $high], %.0f us a frame here (%.1f %% of one core at 86 fps)"
                .format(perFrame, perFrame * 86 / 1e4))
            assertTrue("the masks never move: is the state carried?", high - low > 0.05f)
        }
    }
}
