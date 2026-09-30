package com.n3d.spectra.stems

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * The synth splitter's network, run by ONNX Runtime: one call per frame.
 *
 * The contract, set by train/synthsplit/export.py: an input `power` of shape
 * [1, 1, inputs × bands], an output `mask` of the same shape, and any number of
 * carried states — every other input `x` has an output `next_x` of the same
 * shape, fed back on the next call. Like [StemSession], every tensor is
 * allocated once and the states ping-pong between two pinned sets, so a call
 * allocates nothing.
 *
 * No Android types, so the unit tests can run it on the desktop JVM.
 * Not thread safe: the splitter worker owns it.
 */
class OnnxMaskNet private constructor(
    private val session: OrtSession,
    private val tensors: List<OnnxTensor>,
    private val powerIn: FloatBuffer,
    private val maskOut: FloatBuffer,
    private val size: Int,
    private val feeds: Array<Map<String, OnnxTensor>>,
    private val pins: Array<Map<String, OnnxValue>>,
) : MaskNet {

    private var current = 0

    override fun step(power: FloatArray, mask: FloatArray) {
        powerIn.rewind()
        powerIn.put(power, 0, size)
        session.run(feeds[current], pins[current]).close()
        current = 1 - current
        maskOut.rewind()
        maskOut.get(mask, 0, size)
    }

    override fun close() {
        tensors.forEach { runCatching { it.close() } }
        runCatching { session.close() }
    }

    companion object {
        const val IN_POWER = "power"
        const val OUT_MASK = "mask"
        const val NEXT_PREFIX = "next_"

        /** Loads a splitter model from its bytes. Throws, naming the problem, if it cannot. */
        fun open(env: OrtEnvironment, model: ByteArray, threads: Int = 1): SplitModel {
            val session = OrtSession.SessionOptions().use { o ->
                o.setIntraOpNumThreads(threads.coerceIn(1, 4))
                o.setInterOpNumThreads(1)
                o.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                o.addConfigEntry("session.intra_op.allow_spinning", "0")
                env.createSession(model, o)
            }
            val tensors = ArrayList<OnnxTensor>()
            try {
                val layout = SplitLayout.fromMetadata(session.metadata.customMetadata)
                val size = layout.inputs.size * layout.bands
                fun tensor(shape: LongArray): Pair<OnnxTensor, FloatBuffer> {
                    val elements = shape.fold(1L) { a, b -> a * b }.toInt()
                    val buffer = ByteBuffer.allocateDirect(elements * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                    return OnnxTensor.createTensor(env, buffer, shape).also { tensors += it } to buffer
                }
                fun shapeOf(name: String, from: Map<String, ai.onnxruntime.NodeInfo>): LongArray {
                    val info = from[name]?.info as? TensorInfo ?: throw IllegalArgumentException("model has no tensor $name")
                    val shape = info.shape
                    require(shape.all { it > 0 }) { "$name has a dynamic shape ${shape.contentToString()}" }
                    return shape
                }
                val powerShape = shapeOf(IN_POWER, session.inputInfo)
                require(powerShape.fold(1L) { a, b -> a * b } == size.toLong()) {
                    "power is ${powerShape.contentToString()}, the layout needs $size values"
                }
                require(shapeOf(OUT_MASK, session.outputInfo).contentEquals(powerShape)) { "mask and power differ in shape" }
                val (powerT, powerBuf) = tensor(powerShape)
                val (maskT, maskBuf) = tensor(powerShape)

                val states = session.inputNames.filter { it != IN_POWER }.map { it to shapeOf(it, session.inputInfo) }
                for ((name, shape) in states) {
                    require(shapeOf(NEXT_PREFIX + name, session.outputInfo).contentEquals(shape)) { "$name has no matching $NEXT_PREFIX$name" }
                }
                // Direct buffers start zeroed: the network's memory starts empty.
                val sets = Array(2) { states.map { (name, shape) -> name to tensor(shape).first } }
                val feeds = Array<Map<String, OnnxTensor>>(2) { i ->
                    HashMap<String, OnnxTensor>().apply {
                        put(IN_POWER, powerT)
                        sets[i].forEach { (name, t) -> put(name, t) }
                    }
                }
                val pins = Array<Map<String, OnnxValue>>(2) { i ->
                    HashMap<String, OnnxValue>().apply {
                        put(OUT_MASK, maskT)
                        sets[1 - i].forEach { (name, t) -> put(NEXT_PREFIX + name, t) }
                    }
                }
                return SplitModel(layout, OnnxMaskNet(session, tensors, powerBuf, maskBuf, size, feeds, pins))
            } catch (t: Throwable) {
                tensors.forEach { runCatching { it.close() } }
                runCatching { session.close() }
                throw t
            }
        }
    }
}
