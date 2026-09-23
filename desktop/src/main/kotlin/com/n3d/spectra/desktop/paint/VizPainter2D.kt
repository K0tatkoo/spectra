package com.n3d.spectra.desktop.paint

import com.n3d.spectra.desktop.audio.DesktopEngine
import com.n3d.spectra.desktop.audio.DesktopFrame
import com.n3d.spectra.dsp.nes.Nes2A03
import com.n3d.spectra.dsp.nes.NesOptions
import com.n3d.spectra.dsp.nes.NesReading
import com.n3d.spectra.desktop.state.WaveMode
import com.n3d.spectra.dsp.AnalysisFrame
import com.n3d.spectra.dsp.PeakHold
import com.n3d.spectra.dsp.ScrollBuffer
import com.n3d.spectra.dsp.SpectrogramBuffer
import com.n3d.spectra.paint.Palette
import com.n3d.spectra.settings.FreqScale
import com.n3d.spectra.settings.Settings
import com.n3d.spectra.settings.SpectrumStyle
import com.n3d.spectra.settings.VizPage
import java.awt.BasicStroke
import java.awt.Graphics2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Every visualisation, drawn onto a [Graphics2D].
 *
 * A port of the Android `VizPainter`, kept structurally identical — same method
 * names, same order, same constants — so the two can be read side by side and a
 * fix to one is obviously transferable to the other. The differences are only
 * those Java2D forces: `Paint`+`Canvas` become `paint`+`stroke` on the graphics,
 * `Bitmap` becomes `BufferedImage`, and text is measured through `FontMetrics`
 * instead of a `Paint.Align`.
 *
 * [drawNesTriangle] started here and was ported to Android afterwards; the two
 * now share the tracker itself (`dsp/nes`) and should keep sharing a drawing.
 *
 * One instance per surface: it caches images and bin maps sized for that surface,
 * and it is not thread safe.
 */
class VizPainter2D(var palette: Palette = Palette.DARK) {

    /** Multiplies every dp. 1.15 puts desktop text at roughly the phone's apparent size. */
    var density: Float = 1.15f

    private val path = Path2D.Float()

    private var mapWidth = 0
    private var mapKey = 0
    private var binLo = IntArray(0)
    private var binHi = IntArray(0)

    private var spectrogramImage: BufferedImage? = null
    private var spectrogramPixels: IntArray = IntArray(0)
    private var spectrogramLastHead = -1
    private var spectrogramLut: IntArray? = null

    private var gonioImage: BufferedImage? = null
    private var gonioGraphics: Graphics2D? = null

    private val historyScratch = FloatArray(DesktopEngine.BAND_HISTORY)
    private val loudnessScratch = FloatArray(DesktopEngine.BAND_HISTORY)

    // ------------------------------------------------------------------------

    fun draw(
        g: Graphics2D,
        area: Box,
        page: VizPage,
        waveMode: WaveMode,
        frame: DesktopFrame?,
        s: Settings,
        nes: NesOptions,
        compact: Boolean = false,
    ) {
        if (area.width() < 4f || area.height() < 4f) return
        val old = g.clip
        g.clip(java.awt.geom.Rectangle2D.Float(area.left, area.top, area.width(), area.height()))
        val f = frame?.analysis
        if (f == null) {
            drawIdle(g, area, compact)
        } else {
            when (page) {
                VizPage.SPECTRUM -> drawSpectrum(g, area, f, s, compact)
                VizPage.BANDS -> drawBands(g, area, f, s, compact)
                VizPage.SPECTROGRAM -> drawSpectrogram(g, area, f, s, compact)
                VizPage.LOUDNESS -> drawLoudness(g, area, f, s, compact)
                VizPage.STEREO -> drawStereo(g, area, f, s, compact)
                VizPage.WAVEFORM ->
                    if (waveMode == WaveMode.NES) drawNesTriangle(g, area, frame.nes, nes, f, compact)
                    else drawWaveform(g, area, f, compact)
                // Separation needs the ONNX runtime and a device-audio capture,
                // and this build has neither. The desktop never offers the page.
                VizPage.STEMS -> drawIdleText(g, area, "stems run in the Android app", compact)
            }
            if (f.clipped) drawClipFlag(g, area, compact)
        }
        g.clip = old
    }

    private fun drawIdle(g: Graphics2D, area: Box, compact: Boolean) {
        g.text(
            "no signal", area.centerX(), area.centerY() + sp(if (compact) 11f else 14f) / 3f,
            Fonts.sans(sp(if (compact) 11f else 14f)), palette.textFaint, Align.CENTER,
        )
    }

    private fun drawClipFlag(g: Graphics2D, area: Box, compact: Boolean) {
        val h = dp(if (compact) 10f else 16f)
        val w = dp(if (compact) 26f else 40f)
        val r = Box(area.right - w - dp(4f), area.top + dp(4f), area.right - dp(4f), area.top + dp(4f) + h)
        g.fillRound(r, h / 2f, palette.bad)
        g.text("CLIP", r.centerX(), r.centerY() + h * 0.24f, Fonts.sans(h * 0.66f, bold = true), 0xFFFFFFFF.toInt(), Align.CENTER)
    }

    // ---- spectrum ----------------------------------------------------------

    private fun drawSpectrum(g: Graphics2D, area: Box, f: AnalysisFrame, s: Settings, compact: Boolean) {
        val labelPad = if (compact || !s.showLabels) 0f else dp(16f)
        val plot = Box(area.left, area.top, area.right, area.bottom - labelPad)
        if (s.showGrid && !compact) drawSpectrumGrid(g, plot, s)

        val plotWidth = plot.width().roundToInt()
        if (plotWidth <= 1) return
        ensureBinMap(plotWidth, f, s)
        val w = binLo.size
        if (w <= 1) return

        val bottom = plot.bottom
        when (s.spectrumStyle) {
            SpectrumStyle.BARS -> {
                val barW = max(dp(2f), plot.width() / 96f)
                val gap = barW * 0.35f
                g.paint = spectrumShader(plot)
                var x = plot.left
                while (x < plot.right) {
                    val i0 = (x - plot.left).roundToInt().coerceIn(0, w - 1)
                    val i1 = (x + barW - plot.left).roundToInt().coerceIn(0, w - 1)
                    var db = PeakHold.FLOOR_DB
                    for (i in i0..i1) db = max(db, magnitudeAt(f, i))
                    val y = yForDb(db, plot, s)
                    g.fillRound(Box(x, y, x + barW - gap, bottom), barW * 0.25f)
                    x += barW
                }
            }
            SpectrumStyle.LINE, SpectrumStyle.FILLED -> {
                path.reset()
                for (i in 0 until w) {
                    val x = plot.left + i
                    val y = yForDb(magnitudeAt(f, i), plot, s)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                if (s.spectrumStyle == SpectrumStyle.FILLED) {
                    val fill = Path2D.Float(path)
                    fill.lineTo(plot.left + w - 1, bottom)
                    fill.lineTo(plot.left, bottom)
                    fill.closePath()
                    g.paint = spectrumShader(plot)
                    g.fill(fill)
                    // Re-stroke the top edge: a filled area alone loses the fine
                    // detail that makes a resonance visible.
                }
                g.useColor(palette.accent2)
                g.stroke = BasicStroke(dp(if (compact) 1.2f else 1.6f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.draw(path)
            }
        }

        if (s.peakHold && f.peakDb.isNotEmpty()) {
            path.reset()
            for (i in 0 until w) {
                val x = plot.left + i
                var db = PeakHold.FLOOR_DB
                for (b in binLo[i]..binHi[i]) {
                    if (b < f.peakDb.size) db = max(db, f.peakDb[b])
                }
                val y = yForDb(db, plot, s)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            g.useColor(Palette.withAlpha(palette.text, 0.45f))
            g.stroke = BasicStroke(dp(1f))
            g.draw(path)
        }

        if (!compact && s.showLabels) drawFrequencyLabels(g, plot, area, s)
    }

    private fun spectrumShader(plot: Box) = vGradient(
        plot.top, plot.bottom,
        intArrayOf(
            Palette.withAlpha(palette.accent2, 0.95f),
            Palette.withAlpha(palette.accent, 0.75f),
            Palette.withAlpha(palette.gradA, 0.25f),
        ),
        floatArrayOf(0f, 0.55f, 1f),
    )

    private fun magnitudeAt(f: AnalysisFrame, i: Int): Float {
        var db = PeakHold.FLOOR_DB
        val hi = min(binHi[i], f.magnitudesDb.size - 1)
        for (b in binLo[i]..hi) db = max(db, f.magnitudesDb[b])
        return db
    }

    /** Maps every pixel column to the FFT bins behind it, once per resize. */
    private fun ensureBinMap(width: Int, f: AnalysisFrame, s: Settings) {
        if (width <= 1) return
        val key = (f.fftSize * 31 + s.minHz.toInt()) * 31 + s.maxHz.toInt() + s.freqScale.ordinal * 7 + f.sampleRate
        if (width == mapWidth && key == mapKey) return
        mapWidth = width
        mapKey = key
        binLo = IntArray(width)
        binHi = IntArray(width)
        val maxBin = f.magnitudesDb.size - 1
        for (x in 0 until width) {
            val f0 = freqForFraction(x.toFloat() / width, s)
            val f1 = freqForFraction((x + 1f) / width, s)
            var b0 = (f0 / f.binHz).toInt()
            var b1 = (f1 / f.binHz).toInt()
            if (b1 < b0) b1 = b0
            b0 = b0.coerceIn(0, maxBin)
            b1 = b1.coerceIn(b0, maxBin)
            binLo[x] = b0
            binHi[x] = b1
        }
    }

    private fun freqForFraction(t: Float, s: Settings): Float {
        val lo = s.minHz.coerceAtLeast(1f)
        val hi = s.maxHz.coerceAtLeast(lo + 1f)
        return if (s.freqScale == FreqScale.LOG) lo * exp(t * ln(hi / lo)) else lo + (hi - lo) * t
    }

    private fun fractionForFreq(freq: Float, s: Settings): Float {
        val lo = s.minHz.coerceAtLeast(1f)
        val hi = s.maxHz.coerceAtLeast(lo + 1f)
        return if (s.freqScale == FreqScale.LOG) ln(freq / lo) / ln(hi / lo) else (freq - lo) / (hi - lo)
    }

    private fun yForDb(db: Float, plot: Box, s: Settings): Float {
        val span = (s.ceilingDb - s.floorDb).coerceAtLeast(1f)
        val t = ((db - s.floorDb) / span).coerceIn(0f, 1f)
        return plot.bottom - t * plot.height()
    }

    private fun drawSpectrumGrid(g: Graphics2D, plot: Box, s: Settings) {
        val color = Palette.withAlpha(palette.line, if (palette.isDark) 0.9f else 0.8f)
        for (freq in GRID_FREQS) {
            if (freq < s.minHz || freq > s.maxHz) continue
            val x = plot.left + fractionForFreq(freq, s) * plot.width()
            g.line(x, plot.top, x, plot.bottom, color, dp(0.8f))
        }
        var db = s.ceilingDb
        while (db >= s.floorDb) {
            val y = yForDb(db, plot, s)
            g.line(plot.left, y, plot.right, y, color, dp(0.8f))
            db -= 20f
        }
        if (s.showLabels) {
            val font = Fonts.sans(sp(9f))
            var d = s.ceilingDb
            while (d >= s.floorDb) {
                val y = yForDb(d, plot, s)
                if (y > plot.top + sp(9f)) g.text("${d.toInt()}", plot.left + dp(3f), y - dp(2f), font, palette.textFaint)
                d -= 20f
            }
        }
    }

    private fun drawFrequencyLabels(g: Graphics2D, plot: Box, area: Box, s: Settings) {
        val font = Fonts.sans(sp(9f))
        for (freq in GRID_FREQS) {
            if (freq < s.minHz || freq > s.maxHz) continue
            val x = plot.left + fractionForFreq(freq, s) * plot.width()
            if (x < plot.left + dp(10f) || x > plot.right - dp(10f)) continue
            g.text(freqLabel(freq), x, area.bottom - dp(3f), font, palette.textFaint, Align.CENTER)
        }
    }

    private fun freqLabel(f: Float): String = when {
        f >= 1000f -> {
            val k = f / 1000f
            if (k == k.toInt().toFloat()) "${k.toInt()}k" else "${(k * 10).roundToInt() / 10f}k"
        }
        else -> "${f.toInt()}"
    }

    // ---- bands -------------------------------------------------------------

    private fun drawBands(g: Graphics2D, area: Box, f: AnalysisFrame, s: Settings, compact: Boolean) {
        val bands = f.bands.filterIndexed { i, _ -> s.bands.getOrNull(i)?.enabled ?: true }
        if (bands.isEmpty()) return

        val vertical = compact || area.height() < dp(150f) || bands.size > 9
        if (vertical) {
            val gap = dp(3f)
            val slot = (area.width() - gap * (bands.size - 1)) / bands.size
            val font = Fonts.sans(sp(if (compact) 7f else 9f))
            var x = area.left
            for (band in bands) {
                val r = Box(x, area.top, x + slot, area.bottom - (if (compact) dp(8f) else dp(12f)))
                drawVerticalBandBar(g, r, band.rmsDb, band.holdDb, s, compact)
                g.text(shortName(band.name), r.centerX(), area.bottom - dp(1f), font, palette.textFaint, Align.CENTER)
                x += slot + gap
            }
            return
        }

        val rowGap = dp(6f)
        val rowH = (area.height() - rowGap * (bands.size - 1)) / bands.size
        var y = area.top
        val labelW = dp(58f)
        val readoutW = dp(46f)
        for (band in bands) {
            val row = Box(area.left, y, area.right, y + rowH)
            g.text(band.name, row.left, row.centerY() - sp(1f), Fonts.sans(sp(10f)), palette.textDim)
            g.text(
                "${band.lowHz.toInt()}–${freqLabel(band.highHz)}",
                row.left, row.centerY() + sp(9f), Fonts.sans(sp(7.5f)), palette.textFaint,
            )
            val graph = Box(row.left + labelW, row.top, row.right - readoutW, row.bottom)
            drawBandHistory(g, graph, band.history, s)
            drawBandLevelBar(g, Box(graph.right + dp(4f), row.top, row.right, row.bottom), band.rmsDb, band.holdDb, s)
            y += rowH + rowGap
        }
    }

    private fun shortName(name: String): String =
        if (name.length <= 5) name else name.split(" ").joinToString("") { it.take(1).uppercase() }

    private fun drawBandHistory(g: Graphics2D, r: Box, history: ScrollBuffer, s: Settings) {
        if (r.width() < 4f) return
        val n = min(historyScratch.size, history.capacity)
        history.snapshot(historyScratch)
        path.reset()
        path.moveTo(r.left, r.bottom)
        for (i in 0 until n) {
            path.lineTo(r.left + r.width() * i / (n - 1f), yForDb(historyScratch[i], r, s))
        }
        path.lineTo(r.right, r.bottom)
        path.closePath()
        g.paint = vGradient(
            r.top, r.bottom,
            Palette.withAlpha(palette.accent, 0.85f), Palette.withAlpha(palette.gradA, 0.1f),
        )
        g.fill(path)
    }

    private fun drawBandLevelBar(g: Graphics2D, r: Box, rmsDb: Float, holdDb: Float, s: Settings) {
        val trackW = min(r.width() * 0.42f, dp(10f))
        val track = Box(r.left, r.top, r.left + trackW, r.bottom)
        g.fillRound(track, trackW / 2f, palette.bgDeep)

        val y = yForDb(rmsDb, track, s)
        g.paint = levelGradient(track)
        g.fillRound(Box(track.left, y, track.right, track.bottom), trackW / 2f)

        val hy = yForDb(holdDb, track, s)
        g.fillBox(Box(track.left, hy - dp(1f), track.right, hy + dp(1f)), palette.text)
        g.text(dbText(rmsDb), r.right, r.centerY() + sp(3f), Fonts.mono(sp(9f)), palette.textDim, Align.RIGHT)
    }

    private fun levelGradient(track: Box) = vGradient(
        track.top, track.bottom,
        intArrayOf(palette.bad, palette.warn, palette.accent2, palette.accent),
        floatArrayOf(0f, 0.18f, 0.55f, 1f),
    )

    private fun drawVerticalBandBar(g: Graphics2D, r: Box, rmsDb: Float, holdDb: Float, s: Settings, compact: Boolean) {
        val radius = min(r.width(), dp(6f)) / 2f
        g.fillRound(r, radius, palette.bgDeep)
        val y = yForDb(rmsDb, r, s)
        g.paint = levelGradient(r)
        g.fillRound(Box(r.left, y, r.right, r.bottom), radius)
        if (!compact) {
            val hy = yForDb(holdDb, r, s)
            g.fillBox(Box(r.left, hy - dp(1f), r.right, hy + dp(1f)), palette.text)
        }
    }

    // ---- spectrogram -------------------------------------------------------

    private fun drawSpectrogram(g: Graphics2D, area: Box, f: AnalysisFrame, s: Settings, compact: Boolean) {
        val buffer = f.spectrogram
        val rows = buffer.rows
        val cols = buffer.columns
        val existing = spectrogramImage
        val img: BufferedImage
        if (existing == null || existing.width != cols || existing.height != rows) {
            img = BufferedImage(cols, rows, BufferedImage.TYPE_INT_ARGB)
            spectrogramImage = img
            spectrogramPixels = (img.raster.dataBuffer as DataBufferInt).data
            spectrogramLastHead = -1
        } else {
            img = existing
        }

        val lut = palette.colorMap(s.colorMap)
        if (lut !== spectrogramLut) {
            spectrogramLut = lut
            spectrogramLastHead = -1
        }

        // Only convert the columns that arrived since the last draw.
        val head = buffer.head
        val from = spectrogramLastHead
        if (from < 0) {
            convertColumns(buffer, 0, cols, lut)
        } else if (from != head) {
            var c = from
            while (c != head) {
                convertColumns(buffer, c, 1, lut)
                c = (c + 1) % cols
            }
        }
        spectrogramLastHead = head

        // Unwrap the ring into two blits, oldest on the left.
        val sx = area.width() / cols
        val firstWidth = cols - head
        if (firstWidth > 0) {
            g.drawImage(
                img,
                area.left.roundToInt(), area.top.roundToInt(),
                (area.left + firstWidth * sx).roundToInt(), area.bottom.roundToInt(),
                head, 0, cols, rows, null,
            )
        }
        if (head > 0) {
            g.drawImage(
                img,
                (area.left + firstWidth * sx).roundToInt(), area.top.roundToInt(),
                area.right.roundToInt(), area.bottom.roundToInt(),
                0, 0, head, rows, null,
            )
        }

        if (!compact && s.showLabels) {
            val font = Fonts.sans(sp(8.5f))
            for (freq in GRID_FREQS) {
                if (freq < s.minHz || freq > s.maxHz) continue
                val y = area.bottom - fractionForFreq(freq, s) * area.height()
                if (y < area.top + sp(9f) || y > area.bottom - dp(2f)) continue
                g.text(freqLabel(freq), area.left + dp(3f), y - dp(1.5f), font, Palette.withAlpha(palette.text, 0.75f))
            }
        }
    }

    private fun convertColumns(buffer: SpectrogramBuffer, startColumn: Int, count: Int, lut: IntArray) {
        val rows = buffer.rows
        val cols = buffer.columns
        val px = spectrogramPixels
        for (n in 0 until count) {
            val c = (startColumn + n) % cols
            val base = c * rows
            for (r in 0 until rows) {
                // Row 0 of the buffer is the lowest frequency; row 0 of the image
                // is the top of the picture, so the axis is flipped here.
                px[(rows - 1 - r) * cols + c] = lut[buffer.data[base + r].toInt() and 0xFF]
            }
        }
    }

    // ---- loudness ----------------------------------------------------------

    private fun drawLoudness(g: Graphics2D, area: Box, f: AnalysisFrame, s: Settings, compact: Boolean) {
        val l = f.loudness
        if (compact) {
            drawLoudnessBar(g, area, l.momentary, l.shortTerm, s)
            return
        }
        val topH = area.height() * 0.44f
        val top = Box(area.left, area.top, area.right, area.top + topH)
        val cellW = top.width() / 4f
        drawStat(g, Box(top.left, top.top, top.left + cellW, top.bottom), "M", lufsText(l.momentary), "LUFS")
        drawStat(g, Box(top.left + cellW, top.top, top.left + cellW * 2, top.bottom), "S", lufsText(l.shortTerm), "LUFS")
        drawStat(g, Box(top.left + cellW * 2, top.top, top.left + cellW * 3, top.bottom), "I", lufsText(l.integrated), "LUFS")
        drawStat(
            g, Box(top.left + cellW * 3, top.top, top.right, top.bottom), "TP",
            if (l.truePeakDb <= -140f) "—" else String.format(Locale.US, "%.1f", l.truePeakDb), "dBTP",
            valueColor = if (l.truePeakDb > -1f) palette.bad else palette.text,
        )

        drawLoudnessHistory(g, Box(area.left, top.bottom + dp(4f), area.right, area.bottom - dp(26f)), s)
        drawLoudnessBar(g, Box(area.left, area.bottom - dp(22f), area.right, area.bottom), l.momentary, l.shortTerm, s)

        val delta = if (l.integrated <= -70f) "—" else String.format(Locale.US, "%+.1f LU", l.integrated - s.loudnessTargetLufs)
        g.text(
            "target ${s.loudnessTargetLufs.toInt()} LUFS · Δ $delta · LRA ${String.format(Locale.US, "%.1f", l.range)} LU",
            area.left, area.bottom - dp(26f) - dp(2f), Fonts.sans(sp(8.5f)), palette.textFaint,
        )
    }

    private fun drawStat(g: Graphics2D, r: Box, label: String, value: String, unit: String, valueColor: Int = palette.text) {
        g.text(label, r.centerX(), r.top + sp(10f), Fonts.sans(sp(9f)), palette.textFaint, Align.CENTER)
        val size = min(sp(22f), r.width() * 0.34f)
        g.text(value, r.centerX(), r.centerY() + size * 0.35f, Fonts.mono(size), valueColor, Align.CENTER)
        g.text(unit, r.centerX(), r.bottom - dp(2f), Fonts.sans(sp(7.5f)), palette.textFaint, Align.CENTER)
    }

    private fun drawLoudnessHistory(g: Graphics2D, r: Box, s: Settings) {
        if (r.height() < dp(12f)) return
        DesktopEngine.loudnessHistory.snapshot(loudnessScratch)
        val n = loudnessScratch.size
        val lo = -40f
        val hi = 0f
        fun yFor(v: Float) = r.bottom - ((v.coerceIn(lo, hi) - lo) / (hi - lo)) * r.height()

        val ty = yFor(s.loudnessTargetLufs)
        g.line(r.left, ty, r.right, ty, Palette.withAlpha(palette.accent, 0.55f), dp(0.8f))

        path.reset()
        path.moveTo(r.left, r.bottom)
        for (i in 0 until n) path.lineTo(r.left + r.width() * i / (n - 1f), yFor(loudnessScratch[i]))
        path.lineTo(r.right, r.bottom)
        path.closePath()
        g.paint = vGradient(
            r.top, r.bottom,
            Palette.withAlpha(palette.accent2, 0.7f), Palette.withAlpha(palette.accent2, 0.05f),
        )
        g.fill(path)
    }

    private fun drawLoudnessBar(g: Graphics2D, r: Box, momentary: Float, shortTerm: Float, s: Settings) {
        val lo = -40f
        val hi = 0f
        fun xFor(v: Float) = r.left + ((v.coerceIn(lo, hi) - lo) / (hi - lo)) * r.width()

        val h = min(r.height(), dp(14f))
        val track = Box(r.left, r.centerY() - h / 2f, r.right, r.centerY() + h / 2f)
        g.fillRound(track, h / 2f, palette.bgDeep)
        g.paint = hGradient(
            track.left, track.right,
            intArrayOf(palette.accent, palette.accent2, palette.warn, palette.bad),
            floatArrayOf(0f, 0.55f, 0.82f, 1f),
        )
        g.fillRound(Box(track.left, track.top, max(xFor(momentary), track.left + h), track.bottom), h / 2f)

        val sx = xFor(shortTerm)
        g.fillBox(Box(sx - dp(1f), track.top - dp(2f), sx + dp(1f), track.bottom + dp(2f)), palette.text)
        val tx = xFor(s.loudnessTargetLufs)
        g.fillBox(Box(tx - dp(1f), track.top - dp(3f), tx + dp(1f), track.bottom + dp(3f)), Palette.withAlpha(palette.good, 0.9f))
    }

    // ---- stereo ------------------------------------------------------------

    private fun drawStereo(g: Graphics2D, area: Box, f: AnalysisFrame, s: Settings, compact: Boolean) {
        if (f.gonio.isEmpty()) {
            drawIdleText(g, area, "mono source — no stereo image", compact)
            return
        }
        val size = min(area.width(), area.height() - (if (compact) 0f else dp(30f)))
        val cx = area.centerX()
        val cy = area.top + size / 2f
        val radius = size / 2f - dp(4f)

        val dim = size.roundToInt().coerceAtLeast(8)
        val existing = gonioImage
        val img: BufferedImage
        if (existing == null || existing.width != dim) {
            gonioGraphics?.dispose()
            img = BufferedImage(dim, dim, BufferedImage.TYPE_INT_ARGB)
            gonioImage = img
            gonioGraphics = img.createGraphics().quality()
        } else {
            img = existing
        }
        val gc = gonioGraphics ?: return

        // Fading towards the well colour instead of clearing is what leaves the
        // familiar smear behind a transient.
        val fade = (1f - s.goniometerPersistence).coerceIn(0.02f, 1f)
        gc.paint = Colors.of(Palette.withAlpha(palette.bgDeep, fade))
        gc.composite = java.awt.AlphaComposite.SrcOver
        gc.fillRect(0, 0, dim, dim)

        val r2 = dim / 2f
        val dot = max(1f, dp(1.1f))
        val dotColor = Palette.withAlpha(palette.accent2, 0.9f)
        var i = 0
        while (i < f.gonio.size - 1) {
            gc.circle(r2 + f.gonio[i] * r2 * 0.92f, r2 - f.gonio[i + 1] * r2 * 0.92f, dot, dotColor)
            i += 2
        }

        g.strokeCircle(cx, cy, radius, palette.line, dp(0.8f))
        g.line(cx - radius * 0.7f, cy + radius * 0.7f, cx + radius * 0.7f, cy - radius * 0.7f, palette.line, dp(0.8f))
        g.line(cx - radius * 0.7f, cy - radius * 0.7f, cx + radius * 0.7f, cy + radius * 0.7f, palette.line, dp(0.8f))

        val oldClip = g.clip
        g.clip(java.awt.geom.Ellipse2D.Float(cx - radius, cy - radius, radius * 2f, radius * 2f))
        g.drawImage(
            img, (cx - radius).roundToInt(), (cy - radius).roundToInt(),
            (cx + radius).roundToInt(), (cy + radius).roundToInt(), 0, 0, dim, dim, null,
        )
        g.clip = oldClip

        if (compact) return
        drawCorrelationMeter(g, Box(area.left, area.bottom - dp(22f), area.right, area.bottom - dp(8f)), f.correlation)
        g.text(
            "corr ${String.format(Locale.US, "%+.2f", f.correlation)} · S/M ${String.format(Locale.US, "%.2f", f.stereoWidth)}",
            area.left, area.bottom - dp(1f), Fonts.sans(sp(8.5f)), palette.textFaint,
        )
    }

    private fun drawCorrelationMeter(g: Graphics2D, r: Box, correlation: Float) {
        g.fillRound(r, r.height() / 2f, palette.bgDeep)
        val centre = r.centerX()
        val x = centre + correlation.coerceIn(-1f, 1f) * r.width() / 2f
        val color = when {
            correlation < -0.2f -> palette.bad
            correlation < 0.4f -> palette.warn
            else -> palette.good
        }
        g.fillRound(Box(min(centre, x), r.top, max(centre, x), r.bottom), r.height() / 2f, color)
        g.fillBox(Box(centre - dp(0.7f), r.top, centre + dp(0.7f), r.bottom), Palette.withAlpha(palette.text, 0.6f))
    }

    // ---- waveform ----------------------------------------------------------

    private fun drawWaveform(g: Graphics2D, area: Box, f: AnalysisFrame, compact: Boolean) {
        val stereo = f.waveR.isNotEmpty()
        if (stereo) {
            val gap = dp(4f)
            val h = (area.height() - gap) / 2f
            drawTrace(g, Box(area.left, area.top, area.right, area.top + h), f.waveL, palette.accent, compact)
            drawTrace(g, Box(area.left, area.top + h + gap, area.right, area.bottom), f.waveR, palette.accent2, compact)
        } else {
            drawTrace(g, area, f.waveL, palette.accent, compact)
        }
    }

    private fun drawTrace(g: Graphics2D, r: Box, samples: FloatArray, color: Int, compact: Boolean) {
        if (samples.isEmpty()) return
        g.line(r.left, r.centerY(), r.right, r.centerY(), Palette.withAlpha(palette.line, 0.9f), dp(0.7f))
        path.reset()
        val half = r.height() / 2f
        for (i in samples.indices) {
            val x = r.left + r.width() * i / (samples.size - 1f)
            val y = r.centerY() - samples[i].coerceIn(-1f, 1f) * half * 0.94f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        g.useColor(color)
        g.stroke = BasicStroke(dp(if (compact) 1.1f else 1.5f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(path)
    }

    // ---- 2A03 triangle -----------------------------------------------------

    /**
     * The NES triangle view: the captured wave, held still, against the chip's
     * own 32-step staircase.
     *
     * Everything difficult happens in `TriangleTracker`; by the time a
     * [NesReading] arrives, the horizontal axis is *phase*, not time — 0 is the
     * first DAC step of a period and the reading is normalised to the chip's own
     * ±1. That is what lets the ideal wave be drawn as a fixed shape rather than
     * being fitted to the trace, and it is why the two can be compared by eye.
     */
    private fun drawNesTriangle(
        g: Graphics2D,
        area: Box,
        reading: NesReading?,
        o: NesOptions,
        f: AnalysisFrame,
        compact: Boolean,
    ) {
        val headerH = if (compact) 0f else dp(26f)
        val footerH = if (compact) 0f else dp(20f)
        val axisW = if (compact) 0f else dp(22f)
        val plot = Box(area.left + axisW, area.top + headerH, area.right, area.bottom - footerH)
        if (plot.width() < 8f || plot.height() < 8f) return

        val cycles = (reading?.cycles ?: o.cycles).coerceIn(1, 8)
        drawNesGrid(g, plot, area, cycles, o, compact)

        if (reading == null) {
            drawNesSearching(g, plot, o, compact)
            if (!compact) drawNesHeader(g, Box(area.left, area.top, area.right, area.top + headerH), null, o)
            return
        }

        val half = plot.height() / 2f * 0.92f
        fun yFor(v: Float) = plot.centerY() - v.coerceIn(-1.15f, 1.15f) * half
        fun xFor(phase: Float) = plot.left + plot.width() * phase / cycles

        // Clipped to the well. The vertical scale leaves 15 % of headroom so a
        // trace that overshoots the chip's own range still shows its shape, and
        // without a clip that overshoot is drawn straight across the readouts.
        val outerClip = g.clip
        g.clip(java.awt.geom.Rectangle2D.Float(plot.left, plot.top, plot.width(), plot.height()))

        // The chip's own wave, in the same place the trace will land. Drawn first
        // and underneath: it is the reference, not the measurement.
        if (o.showIdeal) drawNesIdeal(g, plot, cycles, ::xFor, ::yFor, reading.stale)

        val traceColor = if (reading.stale) Palette.withAlpha(palette.accent2, 0.35f) else palette.accent2
        if (o.averaging && reading.cycle.isNotEmpty()) {
            drawNesCycle(g, plot, reading.cycle, cycles, o, traceColor, ::xFor, ::yFor)
        } else {
            drawNesLive(g, plot, reading, cycles, o, traceColor, ::xFor, ::yFor)
        }
        g.clip = outerClip

        if (!compact) {
            drawNesHeader(g, Box(area.left, area.top, area.right, area.top + headerH), reading, o)
            drawNesFooter(g, Box(area.left, area.bottom - footerH, area.right, area.bottom), reading, o, f)
            drawNesLevelLabels(g, Box(area.left, plot.top, area.left + axisW, plot.bottom), ::yFor)
        }
    }

    /** The 4-bit ladder and the 32-step time grid: the chip's two quantisations, drawn. */
    private fun drawNesGrid(g: Graphics2D, plot: Box, area: Box, cycles: Int, o: NesOptions, compact: Boolean) {
        Neu2D.inset(g, plot, Neu2D.RADIUS_SM * density, palette, dp(3f))

        val half = plot.height() / 2f * 0.92f
        if (o.showLevels) {
            for (level in 0 until Nes2A03.LEVELS) {
                val v = Nes2A03.valueOfLevel(level)
                val y = plot.centerY() - v * half
                // The two levels either side of the middle are the ones the chip
                // cannot straddle: there is no zero, so they get the stronger line.
                val emphasis = if (level == 7 || level == 8) 0.55f else 0.24f
                g.line(plot.left, y, plot.right, y, Palette.withAlpha(palette.line, emphasis), dp(0.7f))
            }
        }
        if (o.showSteps) {
            val total = Nes2A03.STEPS * cycles
            val stepW = plot.width() / total
            // Below about three pixels a step, the grid stops being information
            // and becomes texture.
            if (stepW >= dp(2.5f)) {
                for (i in 0..total) {
                    val x = plot.left + i * stepW
                    val boundary = i % Nes2A03.STEPS == 0
                    val mid = i % (Nes2A03.STEPS / 2) == 0
                    val alpha = if (boundary) 0.6f else if (mid) 0.35f else 0.16f
                    g.line(x, plot.top, x, plot.bottom, Palette.withAlpha(palette.line, alpha), dp(if (boundary) 1f else 0.7f))
                }
            } else if (!compact) {
                for (i in 0..cycles) {
                    val x = plot.left + i * plot.width() / cycles
                    g.line(x, plot.top, x, plot.bottom, Palette.withAlpha(palette.line, 0.6f), dp(1f))
                }
            }
        }
    }

    private fun drawNesIdeal(
        g: Graphics2D,
        plot: Box,
        cycles: Int,
        xFor: (Float) -> Float,
        yFor: (Float) -> Float,
        dim: Boolean,
    ) {
        path.reset()
        val total = Nes2A03.STEPS * cycles
        for (i in 0 until total) {
            val v = yFor(Nes2A03.WAVE[i % Nes2A03.STEPS])
            val x0 = xFor(i.toFloat() / Nes2A03.STEPS)
            val x1 = xFor((i + 1f) / Nes2A03.STEPS)
            if (i == 0) path.moveTo(x0, v) else path.lineTo(x0, v)
            path.lineTo(x1, v)
        }
        g.useColor(Palette.withAlpha(palette.accent, if (dim) 0.22f else 0.5f))
        g.stroke = BasicStroke(dp(1.4f), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER)
        g.draw(path)
    }

    private fun drawNesCycle(
        g: Graphics2D,
        plot: Box,
        cycle: FloatArray,
        cycles: Int,
        o: NesOptions,
        color: Int,
        xFor: (Float) -> Float,
        yFor: (Float) -> Float,
    ) {
        // One point per pixel column is all a stroke can show; the grid is 2048
        // points per period and a window is rarely that wide.
        val columns = plot.width().roundToInt().coerceAtLeast(2)
        val perCycle = max(2, columns / cycles)
        path.reset()
        var first = true
        for (c in 0 until cycles) {
            for (i in 0..perCycle) {
                val u = i.toFloat() / perCycle
                var v = cycle[((u * cycle.size).toInt()).coerceIn(0, cycle.size - 1)]
                if (o.quantizeToDac) v = quantize(v)
                val x = xFor(c + u)
                val y = yFor(v)
                if (first) {
                    path.moveTo(x, y)
                    first = false
                } else {
                    path.lineTo(x, y)
                }
            }
        }
        g.useColor(color)
        g.stroke = BasicStroke(dp(1.7f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(path)
    }

    private fun drawNesLive(
        g: Graphics2D,
        plot: Box,
        r: NesReading,
        cycles: Int,
        o: NesOptions,
        color: Int,
        xFor: (Float) -> Float,
        yFor: (Float) -> Float,
    ) {
        val live = r.live
        if (live.isEmpty()) return
        val period = r.periodSamples.toFloat()
        path.reset()
        var first = true
        for (i in live.indices) {
            val phase = r.livePhase0 + i / period
            if (phase < -0.02f) continue
            if (phase > cycles + 0.02f) break
            var v = live[i]
            if (o.quantizeToDac) v = quantize(v)
            val x = xFor(phase)
            val y = yFor(v)
            if (first) {
                path.moveTo(x, y)
                first = false
            } else {
                path.lineTo(x, y)
            }
        }
        g.useColor(color)
        g.stroke = BasicStroke(dp(1.5f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(path)

        // The capture's own time grid. Only worth drawing when the dots are far
        // enough apart to be read as samples rather than as a thicker line.
        if (o.showSamples) {
            val spacing = plot.width() / (period * cycles)
            if (spacing >= dp(4f)) {
                val dotColor = Palette.withAlpha(palette.text, 0.75f)
                for (i in live.indices) {
                    val phase = r.livePhase0 + i / period
                    if (phase < 0f || phase > cycles) continue
                    var v = live[i]
                    if (o.quantizeToDac) v = quantize(v)
                    g.circle(xFor(phase), yFor(v), dp(1.5f), dotColor)
                }
            }
        }
    }

    /** Snaps a normalised value to the nearest of the sixteen levels the DAC has. */
    private fun quantize(v: Float): Float {
        val level = (v * 7.5f + 7.5f).roundToInt().coerceIn(0, Nes2A03.LEVELS - 1)
        return Nes2A03.valueOfLevel(level)
    }

    private fun drawNesLevelLabels(g: Graphics2D, r: Box, yFor: (Float) -> Float) {
        val font = Fonts.mono(sp(7.5f), bold = false)
        for (level in intArrayOf(0, 4, 8, 11, 15)) {
            val y = yFor(Nes2A03.valueOfLevel(level))
            g.text("$level", r.right - dp(3f), y + sp(2.6f), font, palette.textFaint, Align.RIGHT)
        }
    }

    private fun drawNesHeader(g: Graphics2D, r: Box, reading: NesReading?, o: NesOptions) {
        val baseline = r.centerY() + sp(4f)
        if (reading == null) {
            g.text("2A03 triangle", r.left, baseline, Fonts.sans(sp(11f), bold = true), palette.textFaint)
            g.text(o.region.label, r.right, baseline, Fonts.mono(sp(9f)), palette.textFaint, Align.RIGHT)
            return
        }
        val noteColor = if (reading.stale) palette.textFaint else palette.text
        var x = r.left
        g.text(reading.note, x, baseline, Fonts.sans(sp(15f), bold = true), noteColor)
        x += g.textWidth(reading.note, Fonts.sans(sp(15f), bold = true)) + dp(8f)

        // Rounded first, then signed: formatting −0.2 with %+.0f prints "-0 ct",
        // which reads as a tuning error that is not there.
        val roundedCents = reading.cents.roundToInt()
        val cents = if (roundedCents == 0) "in tune" else String.format(Locale.US, "%+d ct", roundedCents)
        g.text(cents, x, baseline, Fonts.mono(sp(9f)), palette.textFaint)
        x += g.textWidth(cents, Fonts.mono(sp(9f))) + dp(12f)

        // The register value is the point of the whole view: this is the number a
        // tracker would have written to $400A/$400B to make this sound.
        val timer = "\$${reading.timer.toString(16).uppercase().padStart(3, '0')}"
        g.text(timer, x, baseline, Fonts.mono(sp(12f)), palette.accent)
        x += g.textWidth(timer, Fonts.mono(sp(12f))) + dp(6f)
        val detail = "(${reading.timer}) · ${String.format(Locale.US, "%.2f", reading.nesHz)} Hz"
        g.text(detail, x, baseline, Fonts.mono(sp(9f)), palette.textDim)

        val right = if (reading.stale) "${o.region.label} · holding" else o.region.label
        g.text(right, r.right, baseline, Fonts.mono(sp(9f)), if (reading.stale) palette.warn else palette.textFaint, Align.RIGHT)
    }

    private fun drawNesFooter(g: Graphics2D, r: Box, reading: NesReading, o: NesOptions, f: AnalysisFrame) {
        val font = Fonts.mono(sp(8.5f))
        val baseline = r.bottom - dp(4f)

        val sps = reading.samplesPerStep
        val parts = buildString {
            append(String.format(Locale.US, "%.1f samples/step", sps))
            append(" · fold ")
            append(reading.foldedPeriods)
            // The setting is shown too whenever the ramp has not caught up with
            // it, so a shallow fold reads as "still settling" rather than as the
            // control having been ignored.
            if (reading.foldedPeriods < o.foldPeriods) {
                append("/")
                append(o.foldPeriods)
            }
            append(" · fit ")
            append(String.format(Locale.US, "%.2f", reading.stepMatch))
            append(" · ")
            append(if (reading.levelDb <= -119f) "—" else String.format(Locale.US, "%.1f dBFS", reading.levelDb))
            append(" · grid ")
            append(String.format(Locale.US, "%+.2f", reading.gridCents))
            append("/")
            append(String.format(Locale.US, "%.1f ct", reading.gridStepCents))
        }
        g.text(parts, r.left, baseline, font, palette.textFaint)

        // One short verdict on the right, because the numbers above only mean
        // something to someone who already knows what they mean.
        val (verdict, color) = when {
            !reading.audible -> "timer below the audible floor" to palette.warn
            sps < 4.0 -> "too high for a visible staircase" to palette.warn
            reading.stepMatch < 0.35f -> "buried — raise fold, or solo the channel" to palette.warn
            reading.stepMatch < 0.7f -> "partly buried under other channels" to palette.textDim
            else -> "clean 2A03 staircase" to palette.good
        }
        g.text(verdict, r.right, baseline, font, color, Align.RIGHT)
    }

    private fun drawNesSearching(g: Graphics2D, plot: Box, o: NesOptions, compact: Boolean) {
        val msg = "listening for a triangle between ${o.huntMinHz.toInt()} and ${o.huntMaxHz.toInt()} Hz"
        drawIdleText(g, plot, msg, compact)
    }

    private fun drawIdleText(g: Graphics2D, area: Box, message: String, compact: Boolean) {
        g.text(
            message, area.centerX(), area.centerY(),
            Fonts.sans(sp(if (compact) 9f else 11f)), palette.textFaint, Align.CENTER,
        )
    }

    // ---- helpers -----------------------------------------------------------

    fun release() {
        gonioGraphics?.dispose()
        gonioGraphics = null
        gonioImage = null
        spectrogramImage = null
        spectrogramPixels = IntArray(0)
    }

    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = v * density

    private fun dbText(db: Float): String =
        if (db <= PeakHold.FLOOR_DB + 1f) "-inf" else String.format(Locale.US, "%.0f", db)

    private fun lufsText(v: Float): String =
        if (v <= -70f) "—" else String.format(Locale.US, "%.1f", v)

    companion object {
        private val GRID_FREQS = floatArrayOf(
            20f, 30f, 50f, 100f, 200f, 300f, 500f, 1000f, 2000f, 3000f, 5000f, 10000f, 20000f,
        )
    }
}
