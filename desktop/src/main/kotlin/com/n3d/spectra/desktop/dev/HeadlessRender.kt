package com.n3d.spectra.desktop.dev

import com.n3d.spectra.desktop.audio.DesktopEngine
import com.n3d.spectra.desktop.audio.SyntheticCapture
import com.n3d.spectra.dsp.nes.NesOptions
import com.n3d.spectra.desktop.paint.Box
import com.n3d.spectra.desktop.paint.Fonts
import com.n3d.spectra.desktop.paint.Neu2D
import com.n3d.spectra.desktop.paint.VizPainter2D
import com.n3d.spectra.desktop.paint.quality
import com.n3d.spectra.desktop.paint.useColor
import com.n3d.spectra.desktop.state.DesktopState
import com.n3d.spectra.desktop.state.WaveMode
import com.n3d.spectra.paint.Palette
import com.n3d.spectra.settings.VizPage
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/**
 * Draws every page into a PNG with no window and no display.
 *
 * This is the check that can be run against the cross-built Windows runtime: it
 * exercises Java2D, the font lookup and the whole painter — everything the graphs
 * depend on — while staying inside `java.awt.headless`, which Swing itself is
 * not. Comparing what this produces on the build machine with what it produces
 * on the target runtime is how the Windows build gets looked at before anyone
 * has a Windows machine to look at it on.
 */
fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "true")
    val outDir = File(args.getOrElse(0) { "build/headless" })
    outDir.mkdirs()

    val state = DesktopState(waveMode = WaveMode.NES)
    DesktopEngine.nesActive = true
    DesktopEngine.updateSettings(state.settings)
    DesktopEngine.updateNes(NesOptions())
    DesktopEngine.start(SyntheticCapture(state.settings.sampleRate, stereo = true))

    println("fonts: sans=${Fonts.sans} mono=${Fonts.mono}")
    Thread.sleep(6_000)

    val painter = VizPainter2D(Palette.DARK)
    painter.density = 1.15f
    val w = 1000
    val h = 620

    // STEMS is Android-only (it needs the ONNX runtime); the desktop never shows it.
    for (page in VizPage.entries.filter { it != VizPage.STEMS }) {
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics().quality()
        g.useColor(Palette.DARK.bg)
        g.fillRect(0, 0, w, h)
        val well = Box(8f, 8f, w - 8f, h - 8f)
        Neu2D.inset(g, well, Neu2D.RADIUS_LG, Palette.DARK, 5f)
        painter.draw(
            g, well.inset(12f, 12f), page, WaveMode.NES,
            DesktopEngine.frame, state.settings, state.nes,
        )
        g.dispose()
        val name = if (page == VizPage.WAVEFORM) "waveform-nes" else page.name.lowercase()
        val file = File(outDir, "$name.png")
        ImageIO.write(image, "png", file)
        println("wrote ${file.name} (${file.length()} bytes)")
    }

    val reading = DesktopEngine.frame?.nes
    println(
        if (reading == null) {
            "NO LOCK — the 2A03 page rendered its searching state"
        } else {
            "locked: ${reading.note} timer ${reading.timer} " +
                "(${"%.2f".format(reading.nesHz)} Hz) fit ${"%.2f".format(reading.stepMatch)}"
        },
    )
    DesktopEngine.stop()
    exitProcess(0)
}
