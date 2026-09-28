package com.n3d.spectra.stems

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.n3d.spectra.TestSignals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * [StemSession] driven directly — no worker thread, so every hop is where the
 * test put it — against the real model on the desktop JVM.
 */
class StemSessionTest {

    private val hop = StemSeparator.HOP
    private val rate = StemSeparator.SAMPLE_RATE
    private val env = OrtEnvironment.getEnvironment()

    @Test
    fun `pinned tensors give exactly what a fresh result every call gives`() {
        val song = song(2.0)
        val pinned = StemSession.open(env, model, 1).use { separate(it, song) }
        val plain = separatePlainly(song)
        var worst = 0f
        for (h in plain.indices) {
            val a = pinned[h] ?: continue
            for (i in a.indices) worst = maxOf(worst, abs(a[i] - plain[h][i]))
        }
        println("pinned vs plain: worst difference $worst over ${plain.size} hops")
        assertEquals("the reused tensors change the answer", 0f, worst, 0f)
    }

    @Test
    fun `a skip keeps the model's memory and the stems are back at once`() {
        // What the worker does when the phone falls behind: jump 150 ms ahead
        // without resetting anything. Held against a run that never skipped,
        // from one model window (23 ms) after the jump to 300 ms after it.
        // Starting over from silence, which the worker used to do, measured
        // 0.3–1.0 for drums and 1.3–3.8 for other in this window, across five
        // jump points in a similar mix; this jump keeps all four near 0.02.
        val song = song(4.0)
        val at = 2 * rate / hop
        val jump = StemSeparator.MAX_BACKLOG_FRAMES / hop
        val straight = StemSession.open(env, model, 1).use { separate(it, song) }
        val skipped = StemSession.open(env, model, 1).use { separate(it, song, at, jump) }
        val from = at + jump + StemSeparator.SPLICE_GUARD_FRAMES / hop
        val to = at + jump + (0.3 * rate / hop).toInt()
        val limits = mapOf(Stem.DRUMS to 0.1, Stem.BASS to 0.05, Stem.VOCALS to 0.05, Stem.OTHER to 0.3)
        for ((stem, limit) in limits) {
            var err = 0.0
            var ref = 0.0
            for (h in from until to) {
                val a = skipped[h] ?: continue
                val b = straight[h] ?: continue
                for (i in 0 until 2 * hop) {
                    val k = stem.ordinal * 2 * hop + i
                    err += (a[k] - b[k]).toDouble() * (a[k] - b[k])
                    ref += b[k].toDouble() * b[k]
                }
            }
            val rel = err / ref
            println("after a skip: ${stem.label} error ${"%.4f".format(rel)} of its energy (limit $limit)")
            assertTrue("${stem.label} has not recovered from the skip ($rel)", rel < limit)
        }
    }

    // ------------------------------------------------------------------------

    /** Kick, a 2A03-style bass, a voice and a lead. */
    private fun song(seconds: Double): FloatArray = TestSignals.render(seconds, rate) { t ->
        0.3 * TestSignals.nesTriangle(55.0, t) + 0.2 * TestSignals.vibrato(330.0, t) +
            0.15 * TestSignals.saw(440.0, t) + 0.4 * TestSignals.kick(t)
    }

    /**
     * Every call's output, indexed by the input hop the call was given; null
     * for the hops a jump at [skipAt] of [jump] hops passed over.
     */
    private fun separate(s: StemSession, mono: FloatArray, skipAt: Int = -1, jump: Int = 0): Array<FloatArray?> {
        val hops = mono.size / hop
        val out = arrayOfNulls<FloatArray>(hops)
        val planar = FloatArray(2 * hop)
        var h = 0
        while (h < hops) {
            if (h == skipAt) h += jump
            if (h >= hops) break
            for (i in 0 until hop) {
                planar[i] = mono[h * hop + i]
                planar[hop + i] = mono[h * hop + i]
            }
            out[h] = FloatArray(StemSession.OUT_SIZE).also { s.separate(planar, it) }
            h++
        }
        return out
    }

    /** The model called the plain way: a fresh input and a fresh result every call. */
    private fun separatePlainly(mono: FloatArray): Array<FloatArray> {
        val session = OrtSession.SessionOptions().use { o ->
            o.setIntraOpNumThreads(1)
            o.setInterOpNumThreads(1)
            o.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            env.createSession(model.absolutePath, o)
        }
        return session.use { s ->
            var states: Map<String, OnnxTensor> = StemSession.STATE_SHAPES.associate { (name, shape) ->
                val n = shape.fold(1L) { a, b -> a * b }.toInt()
                name to OnnxTensor.createTensor(env, ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer(), shape)
            }
            var previous: OrtSession.Result? = null
            val hops = mono.size / hop
            Array(hops) { h ->
                val planar = FloatArray(2 * hop) { i -> mono[h * hop + i % hop] }
                val audio = OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(planar), StemSession.AUDIO_SHAPE)
                val result = s.run(states + (StemSession.IN_AUDIO to audio))
                audio.close()
                if (previous == null) states.values.forEach { it.close() } else previous!!.close()
                previous = result
                states = StemSession.STATE_SHAPES.associate { (name, _) ->
                    name to result.get(StemSession.NEXT_PREFIX + name).get() as OnnxTensor
                }
                val out = FloatArray(StemSession.OUT_SIZE)
                (result.get(StemSession.OUT_SEPARATED).get() as OnnxTensor).floatBuffer.get(out)
                out
            }.also { previous?.close() }
        }
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
