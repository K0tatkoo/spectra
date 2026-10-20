package com.n3d.spectra.desktop.dev

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Development harness. Writes the reference results the iPhone port checks
 * itself against.
 *
 * Spectra for iPhone (`ios/`) is a Swift rewrite of this engine, not a build of
 * it — the owner chose native Swift there. Two copies of every algorithm drift
 * unless something holds them together, and this is that something: every
 * ported class has a Swift test (ios/SpectraCore/Tests) that runs the inputs
 * stored here through the Swift code and compares with what the Kotlin code
 * produced. After any change to the shared DSP:
 *
 *     ./gradlew :desktop:iosFixtures
 *     cd ios/SpectraCore && swift test
 *
 * A fixture is `<name>.json` (metadata and an index) plus `<name>.bin` (the
 * arrays, little-endian). The inputs go into the fixture with the outputs, so the
 * Swift side never has to regenerate a signal and agree with Kotlin about how.
 */
fun main(args: Array<String>) {
    val out = File(args.getOrElse(0) { "../ios/SpectraCore/Tests/SpectraCoreTests/Fixtures" })
    out.mkdirs()
    val written = mutableListOf<String>()
    val sink = { f: IosFixture -> f.write(out); written += f.name }
    IosBaseFixtures.export(sink)
    IosLevelsFixtures.export(sink)
    IosScopesFixtures.export(sink)
    println("Wrote ${written.size} fixtures to ${out.canonicalPath}: ${written.joinToString()}")
}

/** One fixture: metadata plus named arrays. */
class IosFixture(val name: String) {
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

/**
 * Deterministic test signals. The fixtures store the samples they were made
 * from, so these only have to be repeatable here, not reproducible in Swift.
 */
object IosSignals {
    fun sine(n: Int, rate: Int, hz: Double, amp: Double = 1.0, phase: Double = 0.0) =
        FloatArray(n) { (amp * kotlin.math.sin(2 * Math.PI * hz * it / rate + phase)).toFloat() }

    /** Uniform white noise in ±amp from a fixed-seed xorshift — the same samples every run. */
    fun noise(n: Int, amp: Double = 1.0, seed: Long = 0x5EC7_7A15L): FloatArray {
        var s = seed
        return FloatArray(n) {
            s = s xor (s shl 13); s = s xor (s ushr 7); s = s xor (s shl 17)
            (amp * (((s ushr 11).toDouble() / (1L shl 53)) * 2 - 1)).toFloat()
        }
    }

    fun mix(vararg parts: FloatArray): FloatArray {
        val out = FloatArray(parts.maxOf { it.size })
        for (p in parts) for (i in p.indices) out[i] += p[i]
        return out
    }
}
