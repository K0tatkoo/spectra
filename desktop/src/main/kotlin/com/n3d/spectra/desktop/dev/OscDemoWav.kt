package com.n3d.spectra.desktop.dev

import com.n3d.spectra.desktop.audio.OscDemo
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * Writes the built-in oscilloscope demo to a 16-bit stereo WAV, one loop of
 * its three scenes: known X-Y audio to play into the phone's Device audio
 * capture, where the desktop's synthetic input cannot reach.
 */
fun main(args: Array<String>) {
    val out = File(args.getOrElse(0) { "build/osc-demo.wav" })
    out.parentFile?.mkdirs()
    val rate = 48_000
    val frames = (OscDemo.SCENE_S * 3 * rate).toInt()
    val demo = OscDemo(rate)
    val left = FloatArray(frames)
    val right = FloatArray(frames)
    demo.render(left, right, frames)
    DataOutputStream(FileOutputStream(out).buffered()).use { o ->
        fun int(v: Int) = o.writeInt(Integer.reverseBytes(v))
        fun short(v: Int) = o.writeShort(java.lang.Short.reverseBytes(v.toShort()).toInt())
        val bytes = frames * 4
        o.writeBytes("RIFF"); int(36 + bytes); o.writeBytes("WAVE")
        o.writeBytes("fmt "); int(16); short(1); short(2); int(rate); int(rate * 4); short(4); short(16)
        o.writeBytes("data"); int(bytes)
        for (i in 0 until frames) {
            short((left[i] * 0.95f * 32767f).roundToInt().coerceIn(-32768, 32767))
            short((right[i] * 0.95f * 32767f).roundToInt().coerceIn(-32768, 32767))
        }
    }
    println("wrote ${out.absolutePath} (${out.length() / 1024} KB, ${frames / rate} s)")
}
