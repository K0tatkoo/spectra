package com.n3d.spectra.stems

import ai.onnxruntime.OrtEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * The splitter model's contract, from the app's side of it: a tiny untrained
 * network exported by `train/export.py --contract-fixture`, loaded the way the
 * phone loads the real one, must read its layout from the metadata, carry its
 * state and answer every frame exactly as PyTorch did.
 */
class OnnxMaskNetTest {

    @Test
    fun `an exported network runs here as it did in training`() {
        val model = resource("net-fixture.onnx")
        assumeTrue("no contract fixture yet: run train/export.py --contract-fixture", model != null)
        val meta = resource("net-fixture.json")!!.toString(Charsets.UTF_8)
        val frames = Regex("\"frames\": (\\d+)").find(meta)!!.groupValues[1].toInt()
        val features = Regex("\"features\": (\\d+)").find(meta)!!.groupValues[1].toInt()
        val floats = ByteBuffer.wrap(resource("net-fixture.bin")!!).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val powers = Array(frames) { FloatArray(features).also { floats.get(it) } }
        val masks = Array(frames) { FloatArray(features).also { floats.get(it) } }

        OnnxMaskNet.open(OrtEnvironment.getEnvironment(), model!!).use { loaded ->
            assertEquals(listOf(Stem.VOCALS, Stem.OTHER), loaded.layout.inputs)
            assertEquals(features, loaded.layout.inputs.size * loaded.layout.bands)
            val out = FloatArray(features)
            var worst = 0.0
            for (f in 0 until frames) {
                loaded.net.step(powers[f], out)
                for (i in 0 until features) worst = maxOf(worst, abs(out[i] - masks[f][i]).toDouble())
            }
            println("contract fixture: worst mask difference $worst over $frames frames")
            // Frame after frame, so a state that was not carried shows up at once.
            assertTrue("masks differ from PyTorch by $worst", worst < 1e-5)
        }
    }

    private fun resource(name: String): ByteArray? =
        OnnxMaskNetTest::class.java.getResourceAsStream("/synthsplit/$name")?.use { it.readBytes() }
}
