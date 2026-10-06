package com.n3d.spectra.desktop.dev

import com.n3d.spectra.desktop.audio.Devices
import com.n3d.spectra.desktop.audio.LoopbackCapture
import com.n3d.spectra.desktop.audio.wasapi.PcmConverter
import com.n3d.spectra.desktop.audio.wasapi.PcmFormat
import com.n3d.spectra.desktop.audio.wasapi.SystemAudioSupport
import com.n3d.spectra.desktop.audio.wasapi.Wasapi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.system.exitProcess

/**
 * Checks for system-audio capture.
 *
 * The conversion half runs anywhere: hand-built WAVEFORMATEX(TENSIBLE) headers
 * and sample bytes, checked against values worked out by hand — the 24-bit sign,
 * the 5.1 fold, the 24-in-32 container are the parts that go wrong quietly.
 *
 * The live half runs only on Windows (or under Wine, against the cross-built
 * runtime): it lists the outputs, opens "System audio · default output", checks
 * that silence still arrives at the stream's rate, and with `--tone` plays a
 * quiet 997 Hz sine through the default output and checks the capture hears it.
 */
fun main(args: Array<String>) {
    var failures = 0
    fun check(what: String, ok: Boolean, detail: String = "") {
        println("${if (ok) "  ok  " else "  FAIL"}  $what${if (detail.isNotEmpty()) "  ($detail)" else ""}")
        if (!ok) failures++
    }
    fun near(a: Float, b: Float, eps: Float = 1e-4f) = abs(a - b) <= eps

    println("conversion")

    // Float stereo, as almost every Windows mix format is.
    run {
        val f = PcmFormat.parse(extensible(48000, 2, 32, 32, 0x3, float = true))
        check("float stereo parses", f.isFloat && f.channels == 2 && f.sampleRate == 48000 && f.blockAlign == 8)
        val src = le { putFloat(0.5f); putFloat(-0.25f); putFloat(1f); putFloat(0f) }
        val st = FloatArray(4).also { PcmConverter(f, 2).convert(src, 2, it, 0) }
        check("float stereo copies straight", st.contentEquals(floatArrayOf(0.5f, -0.25f, 1f, 0f)), st.joinToString())
        val mono = FloatArray(2).also { PcmConverter(f, 1).convert(src, 2, it, 0) }
        check("float stereo folds to mono as (L+R)/2", near(mono[0], 0.125f) && near(mono[1], 0.5f), mono.joinToString())
    }

    // Plain 16-bit WAVEFORMATEX, no extension.
    run {
        val f = PcmFormat.parse(plain(44100, 2, 16, tag = 1))
        check("16-bit plain parses", !f.isFloat && f.bitsPerSample == 16 && f.blockAlign == 4)
        val src = le { putShort(16384); putShort(-32768) }
        val out = FloatArray(2).also { PcmConverter(f, 2).convert(src, 1, it, 0) }
        check("16-bit scales to ±1", near(out[0], 0.5f) && near(out[1], -1f), out.joinToString())
    }

    // Packed 24-bit 5.1 (KSAUDIO_SPEAKER_5POINT1_SURROUND: FL FR FC LFE SL SR).
    run {
        val f = PcmFormat.parse(extensible(48000, 6, 24, 24, 0x60F, float = false))
        check("24-bit 5.1 parses", f.channels == 6 && f.blockAlign == 18 && f.channelMask == 0x60F)
        val src = ByteArray(18 * 2)
        // frame 0: FL .5, FR 0, FC .25, LFE .9 (must vanish), SL 0, SR .5
        floatArrayOf(0.5f, 0f, 0.25f, 0.9f, 0f, 0.5f).forEachIndexed { c, v -> put24(src, c * 3, v) }
        // frame 1: FL −.5 only — the sign of a packed 24-bit sample
        put24(src, 18, -0.5f)
        val out = FloatArray(4).also { PcmConverter(f, 2).convert(src, 2, it, 0) }
        val g = 0.70710677f
        check("5.1 folds with ITU weights, LFE dropped", near(out[0], 0.5f + g * 0.25f, 1e-3f) && near(out[1], g * 0.25f + g * 0.5f, 1e-3f), "${out[0]}, ${out[1]}")
        check("negative 24-bit keeps its sign", near(out[2], -0.5f, 1e-3f) && near(out[3], 0f), "${out[2]}, ${out[3]}")
    }

    // 24 valid bits in a 32-bit container, left-justified.
    run {
        val f = PcmFormat.parse(extensible(96000, 2, 32, 24, 0x3, float = false))
        check("24-in-32 parses as a 32-bit container", f.bitsPerSample == 32 && !f.isFloat && f.blockAlign == 8)
        val src = le { putInt(0x40000000); putInt(0xC0000000.toInt()) }
        val out = FloatArray(2).also { PcmConverter(f, 2).convert(src, 1, it, 0) }
        check("24-in-32 scales by 2^31", near(out[0], 0.5f) && near(out[1], -0.5f), out.joinToString())
    }

    // A mono endpoint must not come out 3 dB down.
    run {
        val f = PcmFormat.parse(plain(48000, 1, 32, tag = 3))
        val src = le { putFloat(0.4f) }
        val st = FloatArray(2).also { PcmConverter(f, 2).convert(src, 1, it, 0) }
        val mo = FloatArray(1).also { PcmConverter(f, 1).convert(src, 1, it, 0) }
        check("mono source reaches both sides at full level", near(st[0], 0.4f) && near(st[1], 0.4f) && near(mo[0], 0.4f), "${st.joinToString()} / ${mo[0]}")
    }

    // The format we ask Windows for has to read back as itself.
    run {
        val asked = PcmFormat.float32(44100, 2)
        val back = PcmFormat.parse(asked.toWaveFormatEx())
        check("requested format round-trips", back.isFloat && back.sampleRate == 44100 && back.channels == 2 && back.blockAlign == 8)
        val rejected = runCatching { PcmFormat.parse(extensible(48000, 2, 32, 32, 0x3, float = false, subtype = 0x92)) }.isFailure
        check("an unknown sample format is refused, not misread", rejected)
    }

    if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) {
        println("\nlive capture: skipped (not Windows)")
        finish(failures)
    }

    println("\nlive capture")
    check("WASAPI reachable through JNA", SystemAudioSupport.available)
    if (!SystemAudioSupport.available) finish(failures)

    val outputs = runCatching { Wasapi.outputs() }
    check("outputs enumerate", outputs.isSuccess, outputs.exceptionOrNull()?.message ?: "")
    outputs.getOrNull()?.forEach { println("        · ${it.name}   [${it.id}]") }
    val listed = Devices.list().filter { it.outputId != null }.map { it.name }
    check("the Source list offers system audio", Devices.SYSTEM_DEFAULT.name in listed, listed.joinToString(" | "))

    // 48 kHz stereo is usually the mix format already; 44.1 kHz mono makes
    // Windows resample and down-mix. The third run refuses Windows' conversion,
    // which is what a driver that will not convert gets: the raw mix format,
    // converted by PcmConverter.
    class Run(val rate: Int, val stereo: Boolean, val convert: Boolean)
    for (run in listOf(Run(48000, true, true), Run(44100, false, true), Run(44100, true, false))) {
        val label = "${run.rate} Hz ${if (run.stereo) "stereo" else "mono"}${if (run.convert) "" else ", mix format"}"
        val cap = LoopbackCapture(Devices.SYSTEM_DEFAULT, run.rate, requestedStereo = run.stereo, allowConversion = run.convert)
        val started = runCatching { cap.start() }
        check("default output opens in loopback at $label", started.isSuccess, started.exceptionOrNull()?.message ?: cap.describe)
        if (started.isFailure) continue
        try {
            if (run.convert) {
                check("the stream runs at the rate asked for ($label)", cap.sampleRate == run.rate && cap.channelCount == (if (run.stereo) 2 else 1),
                    "${cap.sampleRate} Hz, ${cap.channelCount} ch")
            } else {
                check("the stream runs at the endpoint's own rate ($label)", cap.sampleRate > 0 && cap.channelCount == 2 && cap.describe.contains(" · ${cap.sampleRate} Hz"),
                    cap.describe)
            }
            // However quiet the machine, the stream has to keep time.
            val quiet = capture(cap, 1.0)
            val seconds = quiet.size.toDouble() / cap.channelCount / cap.sampleRate
            check("delivers samples in real time with nothing playing ($label)", seconds in 0.8..1.25, "%.2f s of audio in 1.00 s".format(seconds))

            if ("--tone" in args) {
                val hz = 997.0
                val amplitude = 0.00316 // −50 dBFS: plainly measurable, barely audible
                val player = Thread { playTone(hz, amplitude, 2.0) }.apply { isDaemon = true; start() }
                Thread.sleep(400)
                val heard = capture(cap, 1.0)
                player.join(3000)
                val ch = cap.channelCount
                val first = FloatArray(heard.size / ch) { heard[it * ch] }
                val rms = sqrt(first.fold(0.0) { a, v -> a + v * v } / first.size.coerceAtLeast(1))
                val at = goertzel(first, cap.sampleRate, hz)
                val off = maxOf(goertzel(first, cap.sampleRate, 600.0), goertzel(first, cap.sampleRate, 1500.0), 1e-12)
                val db = 10 * log10(at / off)
                check("a tone played through the default output is captured ($label)", rms > 1e-4 && db > 20,
                    "rms %.5f (%.1f dBFS), 997 Hz is %.1f dB above its neighbours".format(rms, 20 * log10(rms * sqrt(2.0)), db))
            }
        } finally {
            cap.stop()
            cap.release()
        }
    }
    finish(failures)
}

private fun finish(failures: Int): Nothing {
    println(if (failures == 0) "\nall checks passed" else "\n$failures check(s) FAILED")
    exitProcess(if (failures == 0) 0 else 1)
}

private fun capture(cap: LoopbackCapture, seconds: Double): FloatArray {
    val out = ArrayList<Float>()
    val buf = FloatArray(4096)
    val until = System.nanoTime() + (seconds * 1e9).toLong()
    while (System.nanoTime() < until) {
        val n = cap.read(buf)
        if (n < 0) break
        for (i in 0 until n) out += buf[i]
    }
    return out.toFloatArray()
}

private fun playTone(hz: Double, amplitude: Double, seconds: Double) {
    val rate = 48000f
    val format = AudioFormat(rate, 16, 2, true, false)
    val line = AudioSystem.getSourceDataLine(format)
    line.open(format, 9600)
    line.start()
    val frames = (rate * seconds).toInt()
    val bytes = ByteArray(frames * 4)
    for (i in 0 until frames) {
        val v = (sin(2 * PI * hz * i / rate) * amplitude * 32767).toInt()
        bytes[i * 4] = v.toByte(); bytes[i * 4 + 1] = (v shr 8).toByte()
        bytes[i * 4 + 2] = v.toByte(); bytes[i * 4 + 3] = (v shr 8).toByte()
    }
    line.write(bytes, 0, bytes.size)
    line.drain()
    line.close()
}

/** Power at one frequency. */
private fun goertzel(x: FloatArray, rate: Int, hz: Double): Double {
    val k = 2 * cos(2 * PI * hz / rate)
    var s1 = 0.0
    var s2 = 0.0
    for (v in x) {
        val s0 = v + k * s1 - s2
        s2 = s1
        s1 = s0
    }
    return s1 * s1 + s2 * s2 - k * s1 * s2
}

private fun le(block: ByteBuffer.() -> Unit): ByteArray {
    val b = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
    b.block()
    return b.array().copyOf(b.position())
}

private fun put24(dst: ByteArray, at: Int, v: Float) {
    val i = (v * 8388608f).toInt().coerceIn(-8388608, 8388607)
    dst[at] = i.toByte()
    dst[at + 1] = (i shr 8).toByte()
    dst[at + 2] = (i shr 16).toByte()
}

private fun plain(rate: Int, channels: Int, bits: Int, tag: Int): ByteArray = le {
    val block = channels * bits / 8
    putShort(tag.toShort()); putShort(channels.toShort()); putInt(rate); putInt(rate * block)
    putShort(block.toShort()); putShort(bits.toShort()); putShort(0)
}

private fun extensible(rate: Int, channels: Int, bits: Int, valid: Int, mask: Int, float: Boolean, subtype: Int = if (float) 3 else 1): ByteArray = le {
    val block = channels * bits / 8
    putShort(0xFFFE.toShort()); putShort(channels.toShort()); putInt(rate); putInt(rate * block)
    putShort(block.toShort()); putShort(bits.toShort()); putShort(22)
    putShort(valid.toShort()); putInt(mask)
    // KSDATAFORMAT_SUBTYPE_xxx = {0000000x-0000-0010-8000-00AA00389B71}
    putInt(subtype); putShort(0); putShort(0x10)
    put(byteArrayOf(0x80.toByte(), 0, 0, 0xAA.toByte(), 0, 0x38, 0x9B.toByte(), 0x71))
}
