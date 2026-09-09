package com.n3d.spectra.desktop.dev

import com.n3d.spectra.desktop.audio.DesktopEngine
import com.n3d.spectra.desktop.audio.SyntheticCapture
import com.n3d.spectra.desktop.state.DesktopPage
import com.n3d.spectra.desktop.state.DesktopState
import com.n3d.spectra.desktop.state.WaveMode
import com.n3d.spectra.desktop.ui.MainWindow
import com.n3d.spectra.settings.ThemeMode
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * Renders the window to PNGs without anyone having to look at a screen.
 *
 * `printAll` paints a laid-out component tree straight into an image, so the UI
 * can be reviewed — and regressions in it seen — from a build machine. The window
 * is positioned off-screen rather than hidden: a hidden window is never laid out,
 * and an unlaid-out tree prints as a grey rectangle.
 */
fun main(args: Array<String>) {
    val outDir = File(args.getOrElse(0) { "build/ui-shots" })
    outDir.mkdirs()

    val shots = listOf(
        "waveform-nes" to DesktopPage.WAVEFORM,
        "spectrum" to DesktopPage.SPECTRUM,
        "bands" to DesktopPage.BANDS,
        "spectrogram" to DesktopPage.SPECTROGRAM,
        "loudness" to DesktopPage.LOUDNESS,
        "stereo" to DesktopPage.STEREO,
    )

    var window: MainWindow? = null
    SwingUtilities.invokeAndWait {
        val state = DesktopState(
            deviceName = SyntheticCapture.NAME,
            page = DesktopPage.WAVEFORM,
            waveMode = WaveMode.NES,
            settings = DesktopState().settings.copy(theme = ThemeMode.DARK),
        )
        window = MainWindow(state).apply {
            setSize(1360, 860)
            setLocation(-4000, -4000)
            isVisible = true
        }
    }

    val frame = window!!
    // Let the engine fill a history and the tracker lock before anything is drawn.
    Thread.sleep(3_000)

    for ((name, page) in shots) {
        SwingUtilities.invokeAndWait { frame.showPageForShot(page) }
        Thread.sleep(if (page == DesktopPage.SPECTROGRAM) 2_500 else 900)
        val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_RGB)
        SwingUtilities.invokeAndWait {
            val g = image.createGraphics()
            frame.contentPane.printAll(g)
            g.dispose()
        }
        val file = File(outDir, "$name.png")
        ImageIO.write(image, "png", file)
        println("wrote ${file.absolutePath}")
    }

    // Light theme, one page, to prove the palette actually swaps.
    SwingUtilities.invokeAndWait { frame.setThemeForShot(ThemeMode.LIGHT) }
    Thread.sleep(1_200)
    val light = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_RGB)
    SwingUtilities.invokeAndWait {
        val g = light.createGraphics()
        frame.contentPane.printAll(g)
        g.dispose()
    }
    ImageIO.write(light, "png", File(outDir, "light.png"))
    println("wrote ${File(outDir, "light.png").absolutePath}")

    DesktopEngine.stop()
    exitProcess(0)
}
