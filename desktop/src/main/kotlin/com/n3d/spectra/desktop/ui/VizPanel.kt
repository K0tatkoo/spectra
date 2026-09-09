package com.n3d.spectra.desktop.ui

import com.n3d.spectra.desktop.audio.DesktopEngine
import com.n3d.spectra.desktop.paint.Align
import com.n3d.spectra.desktop.paint.Box
import com.n3d.spectra.desktop.paint.Fonts
import com.n3d.spectra.desktop.paint.Neu2D
import com.n3d.spectra.desktop.paint.VizPainter2D
import com.n3d.spectra.desktop.paint.quality
import com.n3d.spectra.desktop.paint.text
import com.n3d.spectra.desktop.state.DesktopState
import com.n3d.spectra.paint.Palette
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Graphics2D
import javax.swing.JComponent

/**
 * The graph surface.
 *
 * A single component that hands its `Graphics2D` to [VizPainter2D]. The window
 * does not know what a spectrum is, and the painter does not know what Swing is —
 * the same split the Android build has between its Compose `VizSurface` and the
 * painter the notification and the widget also use.
 */
class VizPanel(
    private val painter: VizPainter2D,
    private var palette: Palette,
    private var state: DesktopState,
) : JComponent() {

    init {
        isOpaque = false
        cursor = Cursor.getDefaultCursor()
    }

    fun update(state: DesktopState, palette: Palette) {
        this.state = state
        this.palette = palette
        painter.palette = palette
        painter.density = 1.15f * state.uiScale
        repaint()
    }

    override fun paintComponent(gr: Graphics) {
        val g = (gr as Graphics2D).quality()
        val outer = Box(0f, 0f, width.toFloat(), height.toFloat())
        val pad = 6f * state.uiScale
        val well = outer.inset(pad, pad)
        if (well.width() < 8f || well.height() < 8f) return

        Neu2D.inset(g, well, Neu2D.RADIUS_LG * state.uiScale, palette, 5f * state.uiScale)

        val inner = well.inset(10f * state.uiScale, 10f * state.uiScale)
        val engineState = DesktopEngine.state
        if (engineState is DesktopEngine.State.Failed) {
            drawMessage(g, inner, engineState.message, palette.bad)
            return
        }
        if (!DesktopEngine.isRunning()) {
            drawMessage(g, inner, "Not capturing. Pick an input on the right and press Start.", palette.textFaint)
            return
        }

        painter.draw(
            g, inner, state.page.page, state.waveMode,
            DesktopEngine.frame, state.settings, state.nes,
        )
    }

    private fun drawMessage(g: Graphics2D, area: Box, message: String, color: Int) {
        val font = Fonts.sans(12f * state.uiScale)
        val maxW = area.width() - 20f
        val words = message.split(" ")
        val lines = ArrayList<String>()
        var line = StringBuilder()
        g.font = font
        for (w in words) {
            val cand = if (line.isEmpty()) w else "$line $w"
            if (g.fontMetrics.stringWidth(cand) > maxW && line.isNotEmpty()) {
                lines += line.toString()
                line = StringBuilder(w)
            } else {
                line = StringBuilder(cand)
            }
        }
        if (line.isNotEmpty()) lines += line.toString()
        val lh = 18f * state.uiScale
        var y = area.centerY() - (lines.size - 1) * lh / 2f
        for (l in lines) {
            g.text(l, area.centerX(), y, font, color, Align.CENTER)
            y += lh
        }
    }
}
