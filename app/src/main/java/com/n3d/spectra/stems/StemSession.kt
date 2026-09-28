package com.n3d.spectra.stems

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * One ONNX Runtime session of the stem model, and every tensor it will ever touch.
 *
 * The model is called 345 times a second, so whatever a call costs besides the
 * network itself adds up. Everything is allocated once, here: the audio goes into
 * one fixed input, the stems come out of one fixed output, and the eight carried
 * states ping-pong between two fixed sets — each call reads one set, and ONNX
 * Runtime writes the next states straight into the other ("pinned" outputs). The
 * obvious way, a fresh input tensor and a fresh result every call, measured about
 * 5 % slower per call on an M1 for bit-identical output, and ART pays more than
 * HotSpot does for each of those native objects.
 *
 * The model's memory carries from one call to the next, including across a jump
 * in the input: [StemSeparator] relies on that when it skips ahead.
 *
 * No Android types, so the unit tests drive this directly on the desktop JVM.
 * Not thread safe: the stem worker owns it.
 */
class StemSession private constructor(
    private val session: OrtSession,
    private val tensors: List<OnnxTensor>,
    private val audio: FloatBuffer,
    private val separated: FloatBuffer,
    private val feeds: Array<Map<String, OnnxTensor>>,
    private val pins: Array<Map<String, OnnxValue>>,
) : AutoCloseable {

    private var current = 0

    /**
     * Separates one hop. [planar] holds [StemSeparator.HOP] left samples, then as
     * many right; [out] receives [OUT_SIZE] floats — four stems, in the model's
     * order, each two channels of one hop. The stems lag the input by one call.
     */
    fun separate(planar: FloatArray, out: FloatArray) {
        audio.rewind()
        audio.put(planar, 0, AUDIO_SIZE)
        session.run(feeds[current], pins[current]).close()
        current = 1 - current
        separated.rewind()
        separated.get(out, 0, OUT_SIZE)
    }

    override fun close() {
        tensors.forEach { runCatching { it.close() } }
        runCatching { session.close() }
    }

    companion object {
        /** Floats per call handed back by [separate]. */
        const val OUT_SIZE = 4 * 2 * StemSeparator.HOP
        private const val AUDIO_SIZE = 2 * StemSeparator.HOP

        // The model's contract. Internal so the tests can call the model the
        // plain way and hold this class to the same answer.
        internal const val IN_AUDIO = "audio_chunk"
        internal const val OUT_SEPARATED = "separated_chunk"
        internal const val NEXT_PREFIX = "next_"
        internal val AUDIO_SHAPE = longArrayOf(1, 2, StemSeparator.HOP.toLong())
        private val SEPARATED_SHAPE = longArrayOf(1, 4, 2, StemSeparator.HOP.toLong())

        /** The eight carried states, as the model's contract names them. */
        internal val STATE_SHAPES: List<Pair<String, LongArray>> = listOf(
            "audio_history" to longArrayOf(1, 2, 896),
            "fusion_hidden" to longArrayOf(2, 1, 1000),
            "spectral_numerator_tail" to longArrayOf(1, 4, 2, 128),
            "waveform_tail" to longArrayOf(1, 4, 2, 128),
            "attention_keys" to longArrayOf(1, 31, 64),
            "attention_values" to longArrayOf(1, 31, 128),
            "spec_memory_hidden" to longArrayOf(1, 1, 500),
            "waveform_memory_hidden" to longArrayOf(1, 1, 500),
        )

        /** Loads the model and allocates everything a call needs. Throws if it cannot. */
        fun open(env: OrtEnvironment, modelFile: File, threads: Int): StemSession {
            val session = OrtSession.SessionOptions().use { o ->
                o.setIntraOpNumThreads(threads.coerceIn(1, 4))
                o.setInterOpNumThreads(1)
                o.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                // Workers that spin between calls burn a core for nothing: there
                // is a gap after every block while the next one is captured.
                o.addConfigEntry("session.intra_op.allow_spinning", "0")
                env.createSession(modelFile.absolutePath, o)
            }
            val tensors = ArrayList<OnnxTensor>()
            fun tensor(buffer: FloatBuffer, shape: LongArray) =
                OnnxTensor.createTensor(env, buffer, shape).also { tensors += it }
            try {
                val audio = floats(AUDIO_SIZE)
                val separated = floats(OUT_SIZE)
                val audioIn = tensor(audio, AUDIO_SHAPE)
                val separatedOut = tensor(separated, SEPARATED_SHAPE)
                // Direct buffers start zeroed, which is exactly the model's initial state.
                val sets = Array(2) {
                    STATE_SHAPES.map { (name, shape) -> name to tensor(floats(shape.fold(1L) { a, b -> a * b }.toInt()), shape) }
                }
                // Call i reads set i and writes set 1 - i.
                val feeds = Array<Map<String, OnnxTensor>>(2) { i ->
                    HashMap<String, OnnxTensor>().apply {
                        put(IN_AUDIO, audioIn)
                        sets[i].forEach { (name, t) -> put(name, t) }
                    }
                }
                val pins = Array<Map<String, OnnxValue>>(2) { i ->
                    HashMap<String, OnnxValue>().apply {
                        put(OUT_SEPARATED, separatedOut)
                        sets[1 - i].forEach { (name, t) -> put(NEXT_PREFIX + name, t) }
                    }
                }
                return StemSession(session, tensors, audio, separated, feeds, pins)
            } catch (t: Throwable) {
                tensors.forEach { runCatching { it.close() } }
                runCatching { session.close() }
                throw t
            }
        }

        private fun floats(n: Int): FloatBuffer =
            ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    }
}
