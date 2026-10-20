package com.n3d.spectra.ios

import ai.onnxruntime.OrtEnvironment
import com.n3d.spectra.TestSignals
import com.n3d.spectra.dsp.HistoryRing
import com.n3d.spectra.stems.MaskNet
import com.n3d.spectra.stems.OnnxMaskNet
import com.n3d.spectra.stems.SpectralSplit
import com.n3d.spectra.stems.SplitLayout
import com.n3d.spectra.stems.SplitModel
import com.n3d.spectra.stems.Stem
import com.n3d.spectra.stems.StemSeparator
import com.n3d.spectra.stems.StemSession
import com.n3d.spectra.stems.SynthSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * Development harness, not a test: writes the reference results the iPhone
 * port's stems (ios/SpectraCore/Sources/SpectraStems) checks itself against.
 *
 * It lives here rather than beside the desktop's IosFixtures.kt because the
 * stems need ONNX Runtime, which only this module's JVM unit tests have. It
 * skips unless asked:
 *
 *     SPECTRA_IOS_FIXTURES=1 ./gradlew :app:testDebugUnitTest --tests 'com.n3d.spectra.ios.*' --rerun
 *     cd ios/SpectraCore && swift test
 *
 * Every fixture stores the inputs it was made from, so the Swift side never
 * regenerates a signal. Checked by Tests/SpectraStemsTests/StemsFixtureTests.swift.
 */
class StemsFixtureExport {

    private val env = OrtEnvironment.getEnvironment()

    @Test
    fun `write the stems fixtures for the iPhone port`() {
        assumeTrue("set SPECTRA_IOS_FIXTURES to write the iPhone fixtures", System.getenv("SPECTRA_IOS_FIXTURES") != null)
        val path = System.getProperty("spectra.stemModel")
        assumeTrue("no stem model path given", path != null)
        val model = File(path!!)
        assumeTrue("stem model not fetched: $path", model.length() == StemSeparator.BYTES)
        val out = File(repoRoot(), "ios/SpectraCore/Tests/SpectraStemsTests/Fixtures")
        out.mkdirs()

        val written = mutableListOf<String>()
        val sink = { f: IosFixture -> f.write(out); written += f.name }
        exportStems(model, sink)
        exportSynth(sink)
        println("Wrote ${written.size} fixtures to ${out.canonicalPath}: ${written.joinToString()}")
    }

    // ---- the stem model ------------------------------------------------------

    /**
     * `stemSession`: [StemSession] driven hop by hop on a stereo mix whose two
     * channels differ, so a swapped or interleaved layout shows. `stemSeparator`:
     * the threaded [StemSeparator] on the same input, fed at a pace it keeps up
     * with, so it never skips and its rings are deterministic.
     */
    private fun exportStems(model: File, sink: (IosFixture) -> Unit) {
        val hop = StemSeparator.HOP
        val rate = StemSeparator.SAMPLE_RATE
        val hops = 200
        val n = hops * hop
        val noiseL = noise(n, 0.02, 0x51EDL)
        val noiseR = noise(n, 0.02, 0x7A3BL)
        val left = FloatArray(n) { i ->
            val t = i.toDouble() / rate
            (0.3 * TestSignals.nesTriangle(55.0, t) + 0.2 * TestSignals.vibrato(330.0, t) +
                0.15 * TestSignals.saw(440.0, t) + 0.4 * TestSignals.kick(t)).toFloat() + noiseL[i]
        }
        val right = FloatArray(n) { i ->
            val t = i.toDouble() / rate
            (0.3 * TestSignals.nesTriangle(55.0, t) + 0.1 * TestSignals.vibrato(330.0, t) +
                0.25 * TestSignals.saw(660.0, t) + 0.35 * TestSignals.kick(t)).toFloat() + noiseR[i]
        }

        // Planar per hop: hop left samples, then hop right — what separate() takes.
        val input = FloatArray(hops * 2 * hop)
        val output = FloatArray(hops * StemSession.OUT_SIZE)
        StemSession.open(env, model, 1).use { s ->
            val planar = FloatArray(2 * hop)
            val sep = FloatArray(StemSession.OUT_SIZE)
            for (h in 0 until hops) {
                for (i in 0 until hop) {
                    planar[i] = left[h * hop + i]
                    planar[hop + i] = right[h * hop + i]
                }
                System.arraycopy(planar, 0, input, h * 2 * hop, 2 * hop)
                s.separate(planar, sep)
                System.arraycopy(sep, 0, output, h * StemSession.OUT_SIZE, StemSession.OUT_SIZE)
            }
        }
        sink(IosFixture("stemSession").meta("hops", hops).meta("hop", hop).meta("threads", 1)
            .f32("input", input).f32("output", output))

        // For the Swift timing test (StemsTimingTests.swift) to compare with:
        // the same session, the same way, on this machine's JVM.
        StemSession.open(env, model, 1).use { s ->
            val planar = FloatArray(2 * hop)
            val sep = FloatArray(StemSession.OUT_SIZE)
            var h = 0
            fun next() {
                System.arraycopy(input, (h % hops) * 2 * hop, planar, 0, 2 * hop)
                s.separate(planar, sep)
                h++
            }
            repeat(200) { next() }
            val times = DoubleArray(1500) {
                val t0 = System.nanoTime()
                next()
                (System.nanoTime() - t0) / 1e6
            }
            val sorted = times.sorted()
            println("Timing: stem model on this JVM (StemSession.separate, 1 thread): %.3f ms per hop mean, %.3f median, %.3f p99"
                .format(times.average(), sorted[sorted.size / 2], sorted[sorted.size * 99 / 100]))
        }

        val separator = StemSeparator(model)
        separator.start()
        val deadline = System.currentTimeMillis() + 30_000
        while (separator.status !is StemSeparator.Status.Running && System.currentTimeMillis() < deadline) {
            val st = separator.status
            if (st is StemSeparator.Status.Failed) throw AssertionError(st.message)
            Thread.sleep(10)
        }
        var pushed = 0
        val block = 1882 // 1024 frames at 48 kHz, resampled
        while (pushed < n) {
            val count = minOf(block, n - pushed)
            separator.push(left.copyOfRange(pushed, pushed + count), right.copyOfRange(pushed, pushed + count), count)
            pushed += count
            // Wait for the worker to catch up to within one hop.
            val target = (pushed / hop) * hop - hop
            val until = System.currentTimeMillis() + 10_000
            while (separator.stems.any { it.written < target } && System.currentTimeMillis() < until) Thread.sleep(1)
        }
        val end = ((n / hop) - 1L) * hop
        val until = System.currentTimeMillis() + 10_000
        while (separator.pitched.any { it.written < end } && System.currentTimeMillis() < until) Thread.sleep(1)
        val st = separator.status
        separator.stop()
        assertTrue("separator: $st", st is StemSeparator.Status.Running && st.skips == 0)
        val fx = IosFixture("stemSeparator").meta("written", end)
        for (s in 0 until StemSeparator.MODEL_STEMS) {
            assertEquals(end, separator.stems[s].written)
            assertEquals(end, separator.pitched[s].written)
            fx.f32("stems.$s", FloatArray(end.toInt()).also { assertTrue(separator.stems[s].read(end, it, it.size)) })
            fx.f32("pitched.$s", FloatArray(end.toInt()).also { assertTrue(separator.pitched[s].read(end, it, it.size)) })
        }
        sink(fx)
    }

    // ---- the synth splitter ---------------------------------------------------

    /**
     * `synthSplit`: the shipped synth model (OnnxMaskNet) behind [SpectralSplit],
     * whole signals streamed from their first sample the way SpectralSplitTest
     * streams them, with every frame's band powers and masks recorded.
     * `synthSplitter`: the threaded [SynthSplitter] on the same inputs, fed hop
     * by hop like the stem worker feeds it, never skipping.
     */
    private fun exportSynth(sink: (IosFixture) -> Unit) {
        val bytes = File(repoRoot(), "app/src/main/assets/stems/synth-split.onnx").readBytes()
        val rate = StemSeparator.SAMPLE_RATE
        val samples = 32_768
        // A detuned saw stack and a vibrato voice in vocals; a pad and a bass
        // saw in other: things the network should want some of, and some not.
        val nv = noise(samples, 0.01, 0x3311L)
        val no = noise(samples, 0.01, 0x9F27L)
        val vocals = FloatArray(samples) { i ->
            val t = i.toDouble() / rate
            (0.12 * (TestSignals.saw(220.0, t) + TestSignals.saw(221.3, t) + TestSignals.saw(218.8, t)) +
                0.2 * TestSignals.vibrato(330.0, t) * (0.5 + 0.5 * sin(2 * PI * 0.7 * t))).toFloat() + nv[i]
        }
        val other = FloatArray(samples) { i ->
            val t = i.toDouble() / rate
            (0.2 * sin(2 * PI * 110.0 * t) + 0.15 * sin(2 * PI * 164.8 * t) + 0.12 * sin(2 * PI * 261.6 * t) +
                0.1 * TestSignals.saw(55.0, t)).toFloat() + no[i]
        }

        OnnxMaskNet.open(env, bytes).use { loaded ->
            val layout = loaded.layout
            assertEquals(listOf(Stem.VOCALS, Stem.OTHER), layout.inputs)
            val inputs = arrayOf(vocals, other)
            val recorder = RecordingNet(loaded.net)
            val (synth, rests) = stream(layout, recorder, inputs)
            val fx = IosFixture("synthSplit")
                .meta("samples", samples).meta("frames", recorder.powers.size)
                .meta("sampleRate", layout.sampleRate).meta("nFft", layout.nFft).meta("hop", layout.hop)
                .meta("bandEdges", layout.bandEdges.toList()).meta("inputs", layout.inputs.map { it.name })
            for (k in inputs.indices) fx.f32("input.$k", inputs[k])
            fx.f32("powers", flatten(recorder.powers)).f32("masks", flatten(recorder.masks))
            fx.f32("synth", synth)
            for (k in rests.indices) fx.f32("rest.$k", rests[k])
            sink(fx)

            // The threaded splitter, with a network of its own (fresh memory).
            val rings = mapOf(Stem.VOCALS to HistoryRing(StemSeparator.HISTORY), Stem.OTHER to HistoryRing(StemSeparator.HISTORY))
            val splitter = SynthSplitter(open = { OnnxMaskNet.open(env, bytes) }, source = { rings.getValue(it) })
            splitter.start()
            val deadline = System.currentTimeMillis() + 10_000
            while (splitter.status !is SynthSplitter.Status.Running && System.currentTimeMillis() < deadline) Thread.sleep(2)
            assertTrue("splitter: ${splitter.status}", splitter.status is SynthSplitter.Status.Running)
            val outputs = splitter.outputs!!
            val hop = StemSeparator.HOP
            var written = 0
            while (written < samples) {
                rings.getValue(Stem.VOCALS).write(vocals, written, hop)
                rings.getValue(Stem.OTHER).write(other, written, hop)
                written += hop
                splitter.wake(written.toLong())
                if (written % layout.hop == 0) {
                    val want = written - 2L * layout.hop
                    val until = System.currentTimeMillis() + 10_000
                    while (outputs.raw.values.any { it.written < want } && System.currentTimeMillis() < until) Thread.sleep(1)
                }
            }
            // The frame that ends at the last sample completes the hop before it.
            val end = samples - layout.hop.toLong()
            val until = System.currentTimeMillis() + 10_000
            while ((outputs.raw.values + outputs.pitched.values).any { it.written < end } && System.currentTimeMillis() < until) Thread.sleep(1)
            val st = splitter.status
            splitter.stop()
            assertTrue("splitter skipped: $st", st is SynthSplitter.Status.Running && st.skips == 0)
            val sx = IosFixture("synthSplitter").meta("end", end).meta("cleanFrom", splitter.cleanFrom)
                .meta("lanes", outputs.stems.map { it.name })
            for (stem in outputs.stems) {
                val raw = outputs.raw.getValue(stem)
                val pitched = outputs.pitched.getValue(stem)
                assertEquals(end, raw.written)
                assertEquals(end, pitched.written)
                sx.f32("raw.${stem.name}", FloatArray(end.toInt()).also { assertTrue(raw.read(end, it, it.size)) })
                sx.f32("pitched.${stem.name}", FloatArray(end.toInt()).also { assertTrue(pitched.read(end, it, it.size)) })
            }
            sink(sx)
        }
    }

    /** Passes every frame to [inner], keeping a copy of what went in and what came out. */
    private class RecordingNet(private val inner: MaskNet) : MaskNet {
        val powers = ArrayList<FloatArray>()
        val masks = ArrayList<FloatArray>()
        override fun step(power: FloatArray, mask: FloatArray) {
            powers += power.copyOf()
            inner.step(power, mask)
            masks += mask.copyOf()
        }
    }

    /**
     * Runs whole signals through [SpectralSplit] from their first sample, the
     * way SpectralSplitTest does: frames end at every hop, zeros before the
     * start. Returns synth and rests for [0, length − hop).
     */
    private fun stream(layout: SplitLayout, net: MaskNet, inputs: Array<FloatArray>): Pair<FloatArray, Array<FloatArray>> {
        val n = layout.nFft
        val hop = layout.hop
        val total = inputs[0].size
        val split = SpectralSplit(layout, net)
        val frames = Array(inputs.size) { FloatArray(n) }
        val synth = FloatArray(hop)
        val rest = Array(inputs.size) { FloatArray(hop) }
        val outSynth = FloatArray(total - hop)
        val outRest = Array(inputs.size) { FloatArray(total - hop) }
        var pos = hop
        while (pos <= total) {
            for (k in inputs.indices) {
                for (i in 0 until n) {
                    val t = pos - n + i
                    frames[k][i] = if (t < 0) 0f else inputs[k][t]
                }
            }
            split.frame(frames, synth, rest)
            val at = pos - 2 * hop
            if (at >= 0) {
                System.arraycopy(synth, 0, outSynth, at, hop)
                for (k in inputs.indices) System.arraycopy(rest[k], 0, outRest[k], at, hop)
            }
            pos += hop
        }
        return outSynth to outRest
    }

    private fun flatten(rows: List<FloatArray>): FloatArray {
        val out = FloatArray(rows.sumOf { it.size })
        var at = 0
        for (r in rows) {
            System.arraycopy(r, 0, out, at, r.size)
            at += r.size
        }
        return out
    }

    /** Uniform noise in ±amp from a fixed-seed xorshift, as IosSignals.noise. */
    private fun noise(n: Int, amp: Double, seed: Long): FloatArray {
        var s = seed
        return FloatArray(n) {
            s = s xor (s shl 13); s = s xor (s ushr 7); s = s xor (s shl 17)
            (amp * (((s ushr 11).toDouble() / (1L shl 53)) * 2 - 1)).toFloat()
        }
    }

    /** The repository root: the unit tests run in app/. */
    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return dir ?: error("not inside the Spectra repository")
    }
}

/**
 * One fixture: metadata plus named arrays — a copy of the desktop module's
 * IosFixture (desktop/src/main/kotlin/com/n3d/spectra/desktop/dev/IosFixtures.kt),
 * which is not on this module's test classpath. Same format, same reader
 * (ios/SpectraCore/Tests/SpectraFixtures/Fixture.swift).
 */
private class IosFixture(val name: String) {
    private val meta = LinkedHashMap<String, Any>()
    private val arrays = LinkedHashMap<String, Pair<String, ByteArray>>()

    /** A number, a boolean, a string, or a list of those. Not NaN or infinite: JSON has neither. */
    fun meta(key: String, value: Any): IosFixture = apply { meta[key] = value }

    fun f32(key: String, v: FloatArray) = put(key, "f32", v.size * 4) { b -> v.forEach { b.putFloat(it) } }
    fun f64(key: String, v: DoubleArray) = put(key, "f64", v.size * 8) { b -> v.forEach { b.putDouble(it) } }
    fun i32(key: String, v: IntArray) = put(key, "i32", v.size * 4) { b -> v.forEach { b.putInt(it) } }
    fun i64(key: String, v: LongArray) = put(key, "i64", v.size * 8) { b -> v.forEach { b.putLong(it) } }
    fun u8(key: String, v: ByteArray) = put(key, "u8", v.size) { b -> b.put(v) }

    private fun put(key: String, type: String, bytes: Int, fill: (ByteBuffer) -> Unit): IosFixture {
        require(key !in arrays) { "$name: '$key' written twice" }
        val b = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fill(b)
        arrays[key] = type to b.array()
        return this
    }

    fun write(dir: File) {
        val json = StringBuilder()
        json.append("{\n \"name\": ").append(quote(name)).append(",\n \"meta\": {")
        meta.entries.forEachIndexed { i, (k, v) ->
            json.append(if (i == 0) "\n  " else ",\n  ").append(quote(k)).append(": ").append(value(v))
        }
        json.append("\n },\n \"arrays\": {")
        var offset = 0
        arrays.entries.forEachIndexed { i, (k, v) ->
            val (type, bytes) = v
            val count = bytes.size / width(type)
            json.append(if (i == 0) "\n  " else ",\n  ").append(quote(k))
                .append(": {\"type\": \"$type\", \"offset\": $offset, \"count\": $count}")
            offset += bytes.size
        }
        json.append("\n }\n}\n")
        File(dir, "$name.json").writeText(json.toString())
        File(dir, "$name.bin").outputStream().use { o -> arrays.values.forEach { o.write(it.second) } }
    }

    private fun width(type: String) = when (type) {
        "f32", "i32" -> 4
        "f64", "i64" -> 8
        else -> 1
    }

    private fun value(v: Any): String = when (v) {
        is String -> quote(v)
        is Boolean -> v.toString()
        is Float -> { require(v.isFinite()) { "$name: $v in meta" }; v.toDouble().toString() }
        is Double -> { require(v.isFinite()) { "$name: $v in meta" }; v.toString() }
        is Number -> v.toString()
        is List<*> -> v.joinToString(", ", "[", "]") { value(it!!) }
        is Enum<*> -> quote(v.name)
        else -> error("$name: can't write ${v::class} to JSON")
    }

    private fun quote(s: String): String {
        val b = StringBuilder("\"")
        for (c in s) when {
            c == '"' -> b.append("\\\"")
            c == '\\' -> b.append("\\\\")
            c < ' ' -> b.append(String.format("\\u%04x", c.code))
            else -> b.append(c)
        }
        return b.append('"').toString()
    }
}
