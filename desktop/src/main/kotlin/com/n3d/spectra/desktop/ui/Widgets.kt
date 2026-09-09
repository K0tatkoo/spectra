package com.n3d.spectra.desktop.ui

import com.n3d.spectra.desktop.paint.Align
import com.n3d.spectra.desktop.paint.Box
import com.n3d.spectra.desktop.paint.Colors
import com.n3d.spectra.desktop.paint.Fonts
import com.n3d.spectra.desktop.paint.Neu2D
import com.n3d.spectra.desktop.paint.ellipsize
import com.n3d.spectra.desktop.paint.fillBox
import com.n3d.spectra.desktop.paint.fillRound
import com.n3d.spectra.desktop.paint.quality
import com.n3d.spectra.desktop.paint.text
import com.n3d.spectra.desktop.paint.useColor
import com.n3d.spectra.desktop.paint.textWidth
import com.n3d.spectra.paint.Palette
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.JComponent
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The controls, drawn rather than themed.
 *
 * Swing's look and feels cannot produce a neumorphic surface — the whole idea is
 * that a control is the *same* colour as what is behind it and is separated from
 * it only by two shadows, which is the opposite of how every stock L&F draws a
 * border. So each control here is a bare [JComponent] that paints itself through
 * [Neu2D], exactly as the Android build's Compose controls do.
 */
abstract class NeuComponent(protected var palette: Palette) : JComponent() {

    protected var hovered = false
    protected var pressed = false

    /** Multiplies every dimension, so the whole UI can be scaled from settings. */
    var scale: Float = 1f
        set(value) {
            field = value
            revalidate()
            repaint()
        }

    init {
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val handler = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hovered = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hovered = false; pressed = false; repaint() }
            override fun mousePressed(e: MouseEvent) {
                if (!isEnabled) return
                pressed = true
                onPress(e)
                repaint()
            }
            override fun mouseDragged(e: MouseEvent) {
                if (!isEnabled) return
                onDrag(e)
            }
            override fun mouseReleased(e: MouseEvent) {
                if (!isEnabled) return
                val was = pressed
                pressed = false
                if (was) onRelease(e)
                repaint()
            }
        }
        addMouseListener(handler)
        addMouseMotionListener(handler)
    }

    open fun onPress(e: MouseEvent) {}
    open fun onDrag(e: MouseEvent) {}
    open fun onRelease(e: MouseEvent) {}

    fun applyPalette(p: Palette) {
        palette = p
        repaint()
    }

    protected fun dp(v: Float) = v * scale
    protected fun bounds0() = Box(0f, 0f, width.toFloat(), height.toFloat())

    override fun paintComponent(gr: Graphics) {
        val g = (gr as Graphics2D).quality()
        paintNeu(g)
    }

    protected abstract fun paintNeu(g: Graphics2D)

    protected fun sized(h: Float): NeuComponent {
        val d = Dimension(10, dp(h).roundToInt())
        preferredSize = d
        minimumSize = Dimension(10, d.height)
        maximumSize = Dimension(Int.MAX_VALUE, d.height)
        return this
    }
}

/** A section heading in the sidebar. Not interactive. */
class SectionLabel(private var title: String, palette: Palette) : NeuComponent(palette) {
    init {
        cursor = Cursor.getDefaultCursor()
        sized(26f)
    }

    fun setTitle(t: String) { title = t; repaint() }

    override fun paintNeu(g: Graphics2D) {
        g.text(
            title.uppercase(), dp(2f), height - dp(7f),
            Fonts.sans(dp(9.5f), bold = true), palette.textFaint,
        )
        val y = height - dp(3f)
        g.fillBox(Box(dp(2f), y, width - dp(2f), y + 1f), Palette.withAlpha(palette.line, 0.8f))
    }
}

/** A plain paragraph of explanation. Wraps, and sizes itself to what it wrapped to. */
class NoteLabel(private var text: String, palette: Palette, private val warn: Boolean = false) : NeuComponent(palette) {
    private var lines: List<String> = emptyList()
    private var lastWidth = -1

    init {
        cursor = Cursor.getDefaultCursor()
        sized(30f)
    }

    fun setText(t: String) {
        if (t == text) return
        text = t
        lastWidth = -1
        revalidate()
        repaint()
    }

    private fun wrap(g: Graphics2D) {
        if (width == lastWidth) return
        lastWidth = width
        val font = Fonts.sans(dp(9.5f))
        val maxW = width - dp(4f)
        val out = ArrayList<String>()
        var line = StringBuilder()
        for (word in text.split(" ")) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (g.textWidth(candidate, font) > maxW && line.isNotEmpty()) {
                out += line.toString()
                line = StringBuilder(word)
            } else {
                line = StringBuilder(candidate)
            }
        }
        if (line.isNotEmpty()) out += line.toString()
        lines = out
        val h = dp(4f) + out.size * dp(13f)
        val d = Dimension(10, h.roundToInt())
        preferredSize = d
        maximumSize = Dimension(Int.MAX_VALUE, d.height)
        minimumSize = Dimension(10, d.height)
        SwingUtilities.invokeLater { revalidate() }
    }

    override fun paintNeu(g: Graphics2D) {
        wrap(g)
        val font = Fonts.sans(dp(9.5f))
        val color = if (warn) palette.warn else palette.textFaint
        var y = dp(11f)
        for (l in lines) {
            g.text(l, dp(2f), y, font, color)
            y += dp(13f)
        }
    }
}

/** Label on the left, a pill switch on the right. */
class NeuToggle(
    private val label: String,
    initial: Boolean,
    palette: Palette,
    private val onChange: (Boolean) -> Unit,
) : NeuComponent(palette) {

    var value: Boolean = initial
        set(v) {
            if (field == v) return
            field = v
            repaint()
        }

    init { sized(32f) }

    override fun onRelease(e: MouseEvent) {
        value = !value
        onChange(value)
    }

    override fun paintNeu(g: Graphics2D) {
        val h = dp(18f)
        val w = dp(34f)
        val track = Box(width - w - dp(2f), (height - h) / 2f, width - dp(2f), (height + h) / 2f)
        g.text(
            g.ellipsize(label, Fonts.sans(dp(10.5f)), track.left - dp(8f)),
            dp(2f), height / 2f + dp(3.5f), Fonts.sans(dp(10.5f)),
            if (isEnabled) palette.text else palette.textFaint,
        )
        Neu2D.inset(g, track, h / 2f, palette, dp(2f), if (value) Palette.withAlpha(palette.gradA, 0.9f) else palette.bgDeep)
        val knobR = h / 2f - dp(3f)
        val cx = if (value) track.right - knobR - dp(3f) else track.left + knobR + dp(3f)
        g.fillRound(
            Box(cx - knobR, track.centerY() - knobR, cx + knobR, track.centerY() + knobR),
            knobR, if (value) 0xFFFFFFFF.toInt() else palette.textFaint,
        )
    }
}

/** A raised pill that does something when clicked. */
class NeuButton(
    private var label: String,
    palette: Palette,
    private val accent: Boolean = false,
    private val onClick: () -> Unit,
) : NeuComponent(palette) {

    init { sized(34f) }

    fun setLabel(t: String) { label = t; repaint() }

    override fun onRelease(e: MouseEvent) {
        if (contains(e.x, e.y)) onClick()
    }

    override fun paintNeu(g: Graphics2D) {
        val b = bounds0().inset(dp(2f), dp(2f))
        val radius = b.height() / 2f
        if (pressed) {
            Neu2D.inset(g, b, radius, palette, dp(3f), if (accent) palette.gradA else palette.bg)
        } else {
            Neu2D.raised(
                g, b, radius, palette, dp(if (hovered) 5f else 4f),
                fillFrom = if (accent) palette.gradB else palette.surfaceHigh,
                fillTo = if (accent) palette.gradA else palette.surfaceLow,
            )
        }
        val color = when {
            !isEnabled -> palette.textFaint
            accent -> 0xFFFFFFFF.toInt()
            else -> palette.text
        }
        g.text(label, width / 2f, height / 2f + dp(4f), Fonts.sans(dp(11f), bold = true), color, Align.CENTER)
    }
}

/** One row of mutually exclusive choices, wrapping to more rows if they do not fit. */
class NeuSegmented<T>(
    private val label: String?,
    private var items: List<T>,
    initial: T,
    palette: Palette,
    private val render: (T) -> String,
    private val onChange: (T) -> Unit,
) : NeuComponent(palette) {

    var value: T = initial
        set(v) {
            if (field == v) return
            field = v
            repaint()
        }

    private var rows: List<List<Int>> = emptyList()
    private var layoutWidth = -1
    private val cells = HashMap<Int, Box>()

    init { sized(46f) }

    fun setItems(next: List<T>, selected: T) {
        items = next
        value = selected
        layoutWidth = -1
        revalidate()
        repaint()
    }

    private fun labelH() = if (label == null) 0f else dp(15f)

    private fun relayout(g: Graphics2D) {
        if (width == layoutWidth) return
        layoutWidth = width
        val font = Fonts.sans(dp(10f), bold = true)
        val pad = dp(11f)
        val maxW = width - dp(4f)
        val out = ArrayList<List<Int>>()
        var row = ArrayList<Int>()
        var rowW = 0f
        for (i in items.indices) {
            val w = g.textWidth(render(items[i]), font) + pad * 2
            if (rowW + w > maxW && row.isNotEmpty()) {
                out += row
                row = ArrayList()
                rowW = 0f
            }
            row += i
            rowW += w + dp(4f)
        }
        if (row.isNotEmpty()) out += row
        rows = out
        val h = labelH() + out.size * dp(30f) + dp(4f)
        preferredSize = Dimension(10, h.roundToInt())
        maximumSize = Dimension(Int.MAX_VALUE, h.roundToInt())
        minimumSize = Dimension(10, h.roundToInt())
        SwingUtilities.invokeLater { revalidate() }
    }

    override fun onRelease(e: MouseEvent) {
        for ((i, box) in cells) {
            if (box.contains(e.x, e.y)) {
                val item = items.getOrNull(i) ?: return
                value = item
                onChange(item)
                return
            }
        }
    }

    override fun paintNeu(g: Graphics2D) {
        relayout(g)
        cells.clear()
        if (label != null) {
            g.text(label, dp(2f), dp(11f), Fonts.sans(dp(9.5f)), palette.textDim)
        }
        val font = Fonts.sans(dp(10f), bold = true)
        val pad = dp(11f)
        var y = labelH()
        for (row in rows) {
            var x = dp(2f)
            for (i in row) {
                val text = render(items[i])
                val w = g.textWidth(text, font) + pad * 2
                val box = Box(x, y + dp(2f), x + w, y + dp(28f))
                cells[i] = box
                val selected = items[i] == value
                if (selected) {
                    Neu2D.raised(
                        g, box, box.height() / 2f, palette, dp(3f),
                        fillFrom = palette.gradB, fillTo = palette.gradA,
                    )
                } else {
                    Neu2D.inset(g, box, box.height() / 2f, palette, dp(2f))
                }
                g.text(
                    text, box.centerX(), box.centerY() + dp(3.5f), font,
                    if (selected) 0xFFFFFFFF.toInt() else palette.textDim, Align.CENTER,
                )
                x += w + dp(4f)
            }
            y += dp(30f)
        }
    }
}

/**
 * A labelled value with a track.
 *
 * Drag anywhere on the track, or use the wheel — the wheel matters because most
 * of these are fine adjustments where a drag of one pixel is a full unit.
 */
class NeuSlider(
    private val label: String,
    private val min: Float,
    private val max: Float,
    initial: Float,
    palette: Palette,
    private val step: Float = 0f,
    private val format: (Float) -> String,
    private val onChange: (Float) -> Unit,
) : NeuComponent(palette) {

    var value: Float = initial
        set(v) {
            val c = snap(v)
            if (abs(field - c) < 1e-6f) return
            field = c
            repaint()
        }

    init {
        sized(44f)
        addMouseWheelListener { e: MouseWheelEvent ->
            if (!isEnabled) return@addMouseWheelListener
            val delta = (if (step > 0f) step else (max - min) / 100f) * -e.wheelRotation
            set(value + delta)
        }
    }

    private fun snap(v: Float): Float {
        val c = v.coerceIn(min, max)
        if (step <= 0f) return c
        return (Math.round(((c - min) / step).toDouble()) * step + min).toFloat().coerceIn(min, max)
    }

    private fun set(v: Float) {
        val c = snap(v)
        if (abs(value - c) < 1e-6f) return
        value = c
        onChange(c)
    }

    private fun track() = Box(dp(2f), height - dp(14f), width - dp(2f), height - dp(6f))

    override fun onPress(e: MouseEvent) = seek(e)
    override fun onDrag(e: MouseEvent) = seek(e)

    private fun seek(e: MouseEvent) {
        val t = track()
        val f = ((e.x - t.left) / t.width()).coerceIn(0f, 1f)
        set(min + (max - min) * f)
    }

    override fun paintNeu(g: Graphics2D) {
        val enabled = isEnabled
        g.text(label, dp(2f), dp(13f), Fonts.sans(dp(10f)), if (enabled) palette.textDim else palette.textFaint)
        g.text(
            format(value), width - dp(2f), dp(13f), Fonts.mono(dp(10f)),
            if (enabled) palette.text else palette.textFaint, Align.RIGHT,
        )
        val t = track()
        Neu2D.inset(g, t, t.height() / 2f, palette, dp(2f))
        val f = ((value - min) / (max - min)).coerceIn(0f, 1f)
        val fillTo = t.left + t.width() * f
        if (fillTo > t.left + 1f) {
            g.paint = com.n3d.spectra.desktop.paint.hGradient(
                t.left, t.right, intArrayOf(palette.gradA, palette.accent2), floatArrayOf(0f, 1f),
            )
            g.fillRound(Box(t.left, t.top, max(fillTo, t.left + t.height()), t.bottom), t.height() / 2f)
        }
        val knobR = dp(6.5f)
        val cx = (t.left + t.width() * f).coerceIn(t.left + knobR, t.right - knobR)
        Neu2D.raised(
            g, Box(cx - knobR, t.centerY() - knobR, cx + knobR, t.centerY() + knobR), knobR, palette, dp(2.5f),
            fillFrom = palette.light, fillTo = palette.bg,
        )
    }
}

/** A raised button that drops a menu. For lists too long to be segments. */
class NeuDropdown<T>(
    private val label: String,
    private var items: List<T>,
    initial: T?,
    palette: Palette,
    private val render: (T) -> String,
    private val onChange: (T) -> Unit,
) : NeuComponent(palette) {

    var value: T? = initial
        set(v) { field = v; repaint() }

    init { sized(50f) }

    fun setItems(next: List<T>, selected: T?) {
        items = next
        value = selected
        repaint()
    }

    override fun onRelease(e: MouseEvent) {
        if (items.isEmpty()) return
        val menu = JPopupMenu()
        menu.background = Colors.of(palette.bg)
        for (item in items) {
            val entry = javax.swing.JMenuItem(render(item))
            entry.background = Colors.of(palette.bg)
            entry.foreground = Colors.of(if (item == value) palette.accent else palette.text)
            entry.font = Fonts.sans(dp(11f))
            entry.isOpaque = true
            entry.addActionListener {
                value = item
                onChange(item)
            }
            menu.add(entry)
        }
        menu.show(this, 0, height)
    }

    override fun paintNeu(g: Graphics2D) {
        g.text(label, dp(2f), dp(11f), Fonts.sans(dp(9.5f)), palette.textDim)
        val b = Box(dp(2f), dp(16f), width - dp(2f), height - dp(2f))
        if (pressed) Neu2D.inset(g, b, dp(9f), palette, dp(3f)) else Neu2D.raised(g, b, dp(9f), palette, dp(if (hovered) 5f else 4f))
        val font = Fonts.sans(dp(10.5f))
        val caret = dp(16f)
        val text = value?.let { render(it) } ?: "—"
        g.text(g.ellipsize(text, font, b.width() - caret - dp(18f)), b.left + dp(10f), b.centerY() + dp(4f), font, palette.text)
        // A caret drawn as two strokes rather than a glyph: no font is guaranteed
        // to have one that sits on the same baseline.
        val cx = b.right - dp(14f)
        val cy = b.centerY()
        g.useColor(palette.textDim)
        g.stroke = java.awt.BasicStroke(dp(1.6f), java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
        val p = java.awt.geom.Path2D.Float()
        p.moveTo(cx - dp(4f), cy - dp(1.5f))
        p.lineTo(cx, cy + dp(2.5f))
        p.lineTo(cx + dp(4f), cy - dp(1.5f))
        g.draw(p)
    }
}

/** A row of page tabs along the bottom of the graph. */
class PageBar<T>(
    private val items: List<T>,
    initial: T,
    palette: Palette,
    private val render: (T) -> String,
    private val onChange: (T) -> Unit,
) : NeuComponent(palette) {

    var value: T = initial
        set(v) { field = v; repaint() }

    private val cells = HashMap<Int, Box>()

    init { sized(40f) }

    override fun onRelease(e: MouseEvent) {
        for ((i, box) in cells) {
            if (box.contains(e.x, e.y)) {
                val item = items[i]
                value = item
                onChange(item)
                return
            }
        }
    }

    override fun paintNeu(g: Graphics2D) {
        cells.clear()
        val font = Fonts.sans(dp(10.5f), bold = true)
        val gap = dp(5f)
        val slot = (width - gap * (items.size - 1)) / items.size.toFloat()
        var x = 0f
        for (i in items.indices) {
            val box = Box(x, dp(3f), x + slot, height - dp(3f))
            cells[i] = box
            val selected = items[i] == value
            if (selected) {
                Neu2D.raised(g, box, box.height() / 2f, palette, dp(4f), fillFrom = palette.gradB, fillTo = palette.gradA)
            } else {
                Neu2D.inset(g, box, box.height() / 2f, palette, dp(2f))
            }
            g.text(
                g.ellipsize(render(items[i]), font, box.width() - dp(10f)),
                box.centerX(), box.centerY() + dp(4f), font,
                if (selected) 0xFFFFFFFF.toInt() else palette.textDim, Align.CENTER,
            )
            x += slot + gap
        }
    }
}

/** Small live numeric readouts under the graph: peak and RMS per channel. */
class MeterStrip(palette: Palette) : NeuComponent(palette) {

    private var peakL = -144f
    private var peakR = -144f
    private var rmsL = -144f
    private var rmsR = -144f
    private var stereo = false

    init {
        cursor = Cursor.getDefaultCursor()
        sized(30f)
    }

    fun update(pl: Float, pr: Float, rl: Float, rr: Float, isStereo: Boolean) {
        peakL = pl; peakR = pr; rmsL = rl; rmsR = rr; stereo = isStereo
        repaint()
    }

    override fun paintNeu(g: Graphics2D) {
        val font = Fonts.mono(dp(9.5f))
        val small = Fonts.sans(dp(8.5f))
        val cells = if (stereo) 4 else 2
        val w = width / cells.toFloat()
        val labels = if (stereo) {
            listOf("peak L" to peakL, "peak R" to peakR, "rms L" to rmsL, "rms R" to rmsR)
        } else {
            listOf("peak" to peakL, "rms" to rmsL)
        }
        for ((i, entry) in labels.withIndex()) {
            val x = i * w
            g.text(entry.first, x + dp(2f), dp(11f), small, palette.textFaint)
            val v = entry.second
            val color = when {
                v > -1f -> palette.bad
                v > -6f -> palette.warn
                else -> palette.text
            }
            g.text(
                if (v <= -143f) "—" else String.format(java.util.Locale.US, "%.1f", v),
                x + dp(2f), dp(25f), font, color, Align.LEFT,
            )
        }
    }
}

/** Utility: clamp a component to its preferred height inside a BoxLayout column. */
fun JComponent.fixHeight(h: Int) {
    preferredSize = Dimension(10, h)
    maximumSize = Dimension(Int.MAX_VALUE, h)
    minimumSize = Dimension(10, min(h, 10_000))
}


/**
 * A scroll bar that stays out of the way.
 *
 * Swing's stock bar draws arrow buttons and a bevelled track, both of which read
 * as a different product next to everything else on this window. This is the
 * smallest possible override: no buttons, no track, one rounded thumb.
 */
class ThinScrollBarUI(private var palette: Palette) : javax.swing.plaf.basic.BasicScrollBarUI() {

    fun applyPalette(p: Palette) {
        palette = p
        scrollbar?.repaint()
    }

    override fun createDecreaseButton(orientation: Int) = zeroButton()
    override fun createIncreaseButton(orientation: Int) = zeroButton()

    private fun zeroButton() = javax.swing.JButton().apply {
        preferredSize = Dimension(0, 0)
        minimumSize = Dimension(0, 0)
        maximumSize = Dimension(0, 0)
        isVisible = false
    }

    override fun paintTrack(g: Graphics, c: JComponent, r: java.awt.Rectangle) = Unit

    override fun paintThumb(g: Graphics, c: JComponent, r: java.awt.Rectangle) {
        if (r.isEmpty || !scrollbar.isEnabled) return
        val g2 = (g as Graphics2D).quality()
        val inset = 3
        val box = Box(
            (r.x + inset).toFloat(), (r.y + inset).toFloat(),
            (r.x + r.width - inset).toFloat(), (r.y + r.height - inset).toFloat(),
        )
        val alpha = if (isDragging || isThumbRollover) 0.55f else 0.3f
        g2.fillRound(box, box.width() / 2f, Palette.withAlpha(palette.text, alpha))
    }
}
