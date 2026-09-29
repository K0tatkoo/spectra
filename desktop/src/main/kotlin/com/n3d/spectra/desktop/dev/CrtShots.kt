package com.n3d.spectra.desktop.dev

import com.n3d.spectra.desktop.audio.DesktopFrame
import com.n3d.spectra.desktop.audio.OscDemo
import com.n3d.spectra.desktop.paint.Box
import com.n3d.spectra.desktop.paint.Neu2D
import com.n3d.spectra.desktop.paint.VizPainter2D
import com.n3d.spectra.desktop.paint.quality
import com.n3d.spectra.desktop.paint.useColor
import com.n3d.spectra.desktop.state.WaveMode
import com.n3d.spectra.dsp.AnalysisFrame
import com.n3d.spectra.dsp.LoudnessReading
import com.n3d.spectra.dsp.SpectrogramBuffer
import com.n3d.spectra.dsp.StereoFeed
import com.n3d.spectra.dsp.nes.NesOptions
import com.n3d.spectra.paint.Palette
import com.n3d.spectra.settings.OscMode
import com.n3d.spectra.settings.Phosphor
import com.n3d.spectra.settings.Settings
import com.n3d.spectra.settings.VizPage
import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import java.util.Random
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.sin
import kotlin.system.exitProcess

/**
 * The Oscilloscope page, rendered from known signals on a simulated clock.
 *
 * No capture and no window: each scene writes its audio into a [StereoFeed] in
 * the bursts the phone's capture delivers, and the real painter draws it sixty
 * times a simulated second, so the pacing, the fade and the develop pass all
 * run exactly as they would live. Deterministic, so two runs can be compared,
 * and it reports what a frame costs on this machine.
 */
fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "true")
    val outDir = File(args.getOrElse(0) { "build/crt-shots" })
    outDir.mkdirs()
    val base = Settings()

    val scenes = listOf(
        Scene("xy-cube", base, demoAt(2.0)),
        Scene("xy-lissajous-amber", base.copy(oscPhosphor = Phosphor.AMBER), demoAt(8.0)),
        Scene("xy-word-spectra", base.copy(oscPhosphor = Phosphor.SPECTRA), demoAt(14.0)),
        Scene("xy-circle-blue", base.copy(oscPhosphor = Phosphor.BLUE), stereoTone(220.0, 0.7)),
        Scene("xy-silence", base, { _, _, _, _ -> }),
        Scene("xy-music", base, music()),
        Scene("yt-sine-1ms", base.copy(oscMode = OscMode.YT), stereoTone(440.0, 0.6)),
        Scene("yt-bass-lead-2ms", base.copy(oscMode = OscMode.YT, oscTimeDivMs = 2f), bassAndLead()),
        Scene("yt-music-1ms", base.copy(oscMode = OscMode.YT), music()),
        Scene("xy-cube-light", base, demoAt(2.0), palette = Palette.LIGHT),
        Scene("xy-cube-desktop", base, demoAt(2.0), density = 1.15f, width = 1000, height = 720),
    )
    for (scene in scenes) render(scene, outDir)
    benchTube()
    exitProcess(0)
}

private typealias Source = (left: FloatArray, right: FloatArray, frames: Int, startFrame: Long) -> Unit

private class Scene(
    val name: String,
    val settings: Settings,
    val source: Source,
    val palette: Palette = Palette.DARK,
    val density: Float = 2.8f,
    val width: Int = 1060,
    val height: Int = 1150,
)

private const val RATE = 48_000
private const val BURST = 1024

private fun render(scene: Scene, outDir: File) {
    val feed = StereoFeed(32_768)
    val painter = VizPainter2D(scene.palette)
    painter.density = scene.density
    var simNanos = 0L
    painter.nanoTime = { simNanos }
    val frame = DesktopFrame(emptyFrame(feed), null)
    val left = FloatArray(BURST)
    val right = FloatArray(BURST)
    var written = 0L
    val image = BufferedImage(scene.width, scene.height, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics().quality()

    val frameNs = 1_000_000_000L / 60
    val frames = 90
    var drawNs = 0L
    var timed = 0
    for (f in 0 until frames) {
        simNanos += frameNs
        // The capture delivers whole bursts, late, like the real one.
        while (written + BURST <= simNanos * RATE / 1_000_000_000L) {
            scene.source(left, right, BURST, written)
            feed.write(left, right, BURST)
            written += BURST
        }
        g.useColor(scene.palette.bg)
        g.fillRect(0, 0, scene.width, scene.height)
        val well = Box(8f, 8f, scene.width - 8f, scene.height - 8f)
        Neu2D.inset(g, well, Neu2D.RADIUS_LG, scene.palette, 5f)
        val t0 = System.nanoTime()
        painter.draw(
            g, well.inset(10f * scene.density, 10f * scene.density), VizPage.OSCILLOSCOPE, WaveMode.FREE,
            frame, scene.settings, NesOptions(),
        )
        val dt = System.nanoTime() - t0
        if (f >= frames - 40) {
            drawNs += dt
            timed++
        }
    }
    g.dispose()
    val file = File(outDir, "${scene.name}.png")
    ImageIO.write(image, "png", file)
    println(String.format(Locale.US, "%-22s %6.2f ms a frame  (%s)", scene.name, drawNs / 1e6 / timed, file.name))
}

private fun emptyFrame(feed: StereoFeed) = AnalysisFrame(
    seq = 0,
    sampleRate = RATE,
    fftSize = 4096,
    binHz = RATE / 4096f,
    magnitudesDb = FloatArray(2049),
    peakDb = FloatArray(0),
    bands = emptyList(),
    waveL = FloatArray(4),
    waveR = FloatArray(4),
    gonio = FloatArray(0),
    correlation = 1f,
    stereoWidth = 0f,
    loudness = LoudnessReading.EMPTY,
    rmsDbL = -20f,
    rmsDbR = -20f,
    peakDbL = -6f,
    peakDbR = -6f,
    clipped = false,
    silent = false,
    spectrogram = SpectrogramBuffer(1, 1),
    beam = feed,
)

/** The built-in demo, fast-forwarded to [seconds] so a chosen scene is on. */
private fun demoAt(seconds: Double): Source {
    val demo = OscDemo(RATE)
    var skipped = false
    return { left, right, frames, _ ->
        if (!skipped) {
            val scratchL = FloatArray(4096)
            val scratchR = FloatArray(4096)
            var todo = ((seconds - 1.2) * RATE).toLong()
            while (todo > 0) {
                val n = minOf(todo, 4096L).toInt()
                demo.render(scratchL, scratchR, n)
                todo -= n
            }
            skipped = true
        }
        demo.render(left, right, frames)
        for (i in 0 until frames) {
            left[i] *= 0.95f
            right[i] *= 0.95f
        }
    }
}

/** A circle: a sine on one side and its cosine on the other. */
private fun stereoTone(hz: Double, amp: Double): Source = { left, right, frames, start ->
    for (i in 0 until frames) {
        val t = (start + i).toDouble() / RATE
        left[i] = (amp * sin(2 * PI * hz * t)).toFloat()
        right[i] = (amp * sin(2 * PI * hz * t + PI / 2)).toFloat()
    }
}

private fun bassAndLead(): Source = { left, right, frames, start ->
    for (i in 0 until frames) {
        val t = (start + i).toDouble() / RATE
        val bass = 0.45 * sin(2 * PI * 55.0 * t)
        var lead = 0.0
        for (h in 1..6) lead += sin(2 * PI * 440.0 * h * t) / h
        val v = (bass + 0.18 * lead).toFloat()
        left[i] = v
        right[i] = v
    }
}

/** Something like a real mix: a bass, chords, hats as noise, the two sides not quite alike. */
private fun music(): Source {
    val rnd = Random(7)
    return { left, right, frames, start ->
        for (i in 0 until frames) {
            val t = (start + i).toDouble() / RATE
            val bass = 0.35 * sin(2 * PI * 49.0 * t)
            val chord = 0.12 * (sin(2 * PI * 293.7 * t) + sin(2 * PI * 370.0 * t) + sin(2 * PI * 440.0 * t))
            val beat = (t * 4) % 1.0
            val hat = if (beat < 0.08) rnd.nextGaussian() * 0.12 else 0.0
            val side = 0.08 * sin(2 * PI * 659.3 * t + 1.1)
            left[i] = (bass + chord + hat + side).toFloat()
            right[i] = (bass + chord * 0.9 + hat * 0.7 - side).toFloat()
        }
    }
}

/**
 * Where a frame's time goes at phone size: the tube alone, against the whole
 * painter (the tube plus the Java2D blit, graticule and glass).
 */
internal fun benchTube() {
    fun run(label: String, s: Settings, source: Source) {
        val feed = StereoFeed(32_768)
        val crt = com.n3d.spectra.paint.Crt()
        val layout = com.n3d.spectra.paint.CrtLayout()
        layout.compute(0f, 0f, 1000f, 1100f, 2.8f, s.oscMode, compact = false)
        val left = FloatArray(BURST)
        val right = FloatArray(BURST)
        var written = 0L
        var sim = 0L
        var total = 0L
        val frames = 900
        for (f in 0 until frames) {
            sim += 1_000_000_000L / 60
            while (written + BURST <= sim * RATE / 1_000_000_000L) {
                source(left, right, BURST, written)
                feed.write(left, right, BURST)
                written += BURST
            }
            val t0 = System.nanoTime()
            crt.render(feed, RATE, s, layout, sim)
            if (f >= 300) total += System.nanoTime() - t0
        }
        println(String.format(Locale.US, "tube, phone size, %-26s %.2f ms a frame at %d×%d", label, total / 1e6 / (frames - 300), crt.width, crt.height))
    }
    val s = Settings()
    run("cube, glow on", s, demoAt(2.0))
    run("cube, glow off", s.copy(oscGlow = 0f), demoAt(2.0))
    run("silence (develop only)", s, { _, _, _, _ -> })
    run("silence, glow off", s.copy(oscGlow = 0f), { _, _, _, _ -> })
    run("music, glow on", s, music())
}
