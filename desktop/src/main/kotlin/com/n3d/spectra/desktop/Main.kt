package com.n3d.spectra.desktop

import com.n3d.spectra.desktop.state.StateStore
import com.n3d.spectra.desktop.ui.showMainWindow
import javax.swing.UIManager

/**
 * Spectra for Windows.
 *
 * The same analyser as the Android app — literally the same DSP sources, compiled
 * from `app/src/main/java` — behind a Swing shell, plus a waveform mode for the
 * NES 2A03's triangle channel that the phone build does not have.
 */
fun main() {
    // Every control is custom-painted, so the look and feel only ever shows
    // through in the popup menus and the scroll bar. System is the least
    // surprising choice for those on Windows.
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }

    System.setProperty("sun.java2d.opengl", "false")
    // Java2D's Direct3D pipeline still has visible seams on some Intel drivers
    // when blitting a scaled sub-image every frame, which is exactly what the
    // spectrogram does. The software loops are more than fast enough here.
    System.setProperty("sun.java2d.d3d", "false")

    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        System.err.println("Spectra: uncaught error on ${thread.name}")
        error.printStackTrace()
    }

    showMainWindow(StateStore.load())
}
