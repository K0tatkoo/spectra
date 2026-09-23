package com.n3d.spectra.paint

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.n3d.spectra.audio.AudioEngine
import com.n3d.spectra.dsp.AnalysisFrame
import com.n3d.spectra.dsp.PeakHold
import com.n3d.spectra.dsp.ScopeTrace
import com.n3d.spectra.dsp.StemsInfo
import com.n3d.spectra.dsp.StemsState
import com.n3d.spectra.dsp.TraceMode
import com.n3d.spectra.dsp.nes.Nes2A03
import com.n3d.spectra.dsp.nes.NesOptions
import com.n3d.spectra.dsp.nes.NesReading
import com.n3d.spectra.settings.FreqScale
import com.n3d.spectra.settings.Settings
import com.n3d.spectra.settings.SpectrumStyle
import com.n3d.spectra.settings.VizPage
import com.n3d.spectra.settings.WaveformMode
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Every visualisation, drawn onto a plain [Canvas].
 *
 * This is the reason the app looks the same in four places. Compose hands it a
 * native canvas, the overlay View hands it its own, and the notification and
 * widget hand it one backed by a bitmap — none of them re-implements a graph.
 *
 * One instance per surface: it caches bitmaps and bin maps sized for that
 * surface, and it is not thread safe.
 */
class VizPainter(var palette: Palette = Palette.DARK) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val monoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val path = Path()

    /** px per dp for the surface being drawn — the notification is not the screen. */
    var density: Float = 3f

    // Cached per-pixel bin mapping for the spectrum.
    private var mapWidth = 0
    private var mapKey = 0
    private var binLo = IntArray(0)
    private var binHi = IntArray(0)

    private var spectrogramBitmap: Bitmap? = null
    private var spectrogramColumnPixels = IntArray(0)
    private var spectrogramLastHead = -1
    private var spectrogramLut: IntArray? = null

    private var gonioBitmap: Bitmap? = null
    private var gonioCanvas: Canvas? = null

    private val historyScratch = FloatArray(AudioEngine.BAND_HISTORY)
    private val loudnessScratch = FloatArray(AudioEngine.BAND_HISTORY)

    // ------------------------------------------------------------------------

    fun draw(
        canvas: Canvas,
        area: RectF,
        page: VizPage,
        frame: AnalysisFrame?,
        s: Settings,
        compact: Boolean = false,
    ) {
        if (area.width() < 4f || area.height() < 4f) return
        canvas.save()
        canvas.clipRect(area)
        if (frame == null) {
            drawIdle(canvas, area, compact)
        } else {
            when (page) {
                VizPage.SPECTRUM -> drawSpectrum(canvas, area, frame, s, compact)
                VizPage.BANDS -> drawBands(canvas, area, frame, s, compact)
                VizPage.SPECTROGRAM -> drawSpectrogram(canvas, area, frame, s, compact)
                VizPage.LOUDNESS -> drawLoudness(canvas, area, frame, s, compact)
                VizPage.STEREO -> drawStereo(canvas, area, frame, s, compact)
                VizPage.WAVEFORM -> when (s.waveformMode) {
                    WaveformMode.FREE -> drawWaveform(canvas, area, frame, s, compact)
                    WaveformMode.HOLD -> drawHold(canvas, area, frame, s, compact)
                    WaveformMode.NES -> drawNesTriangle(canvas, area, frame.nes, NesOptions(region = s.nesRegion), frame, compact)
                }
                VizPage.STEMS -> drawStems(canvas, area, frame, s, compact)
            }
            if (frame.clipped) drawClipFlag(canvas, area, compact)
        }
        canvas.restore()
    }

    private fun drawIdle(canvas: Canvas, area: RectF, compact: Boolean) {
        textPaint.color = palette.textFaint
        textPaint.textSize = sp(if (compact) 11f else 14f)
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(
            "no signal",
            area.centerX(),
            area.centerY() + textPaint.textSize / 3f,
            textPaint,
        )
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawClipFlag(canvas: Canvas, area: RectF, compact: Boolean) {
        val h = dp(if (compact) 10f else 16f)
        val w = dp(if (compact) 26f else 40f)
        val r = RectF(area.right - w - dp(4f), area.top + dp(4f), area.right - dp(4f), area.top + dp(4f) + h)
        paint.reset()
        paint.isAntiAlias = true
        paint.color = palette.bad
        canvas.drawRoundRect(r, h / 2f, h / 2f, paint)
        textPaint.color = 0xFFFFFFFF.toInt()
        textPaint.textSize = h * 0.66f
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("CLIP", r.centerX(), r.centerY() + h * 0.24f, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
    }

    // ---- spectrum ----------------------------------------------------------

    private fun drawSpectrum(canvas: Canvas, area: RectF, f: AnalysisFrame, s: Settings, compact: Boolean) {
        val labelPad = if (compact || !s.showLabels) 0f else dp(16f)
        val plot = RectF(area.left, area.top, area.right, area.bottom - labelPad)
        if (s.showGrid && !compact) drawSpectrumGrid(canvas, plot, s)

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
                paint.reset()
                paint.isAntiAlias = true
                paint.shader = spectrumShader(plot)
                var x = plot.left
                while (x < plot.right) {
                    val i0 = ((x - plot.left)).roundToInt().coerceIn(0, w - 1)
                    val i1 = ((x + barW - plot.left)).roundToInt().coerceIn(0, w - 1)
                    var db = PeakHold.FLOOR_DB
                    for (i in i0..i1) db = max(db, magnitudeAt(f, i))
                    val y = yForDb(db, plot, s)
                    canvas.drawRoundRect(
                        RectF(x, y, x + barW - gap, bottom), barW * 0.25f, barW * 0.25f, paint,
                    )
                    x += barW
                }
                paint.shader = null
            }
            SpectrumStyle.LINE, SpectrumStyle.FILLED -> {
                path.reset()
                for (i in 0 until w) {
                    val x = plot.left + i
                    val y = yForDb(magnitudeAt(f, i), plot, s)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                if (s.spectrumStyle == SpectrumStyle.FILLED) {
                    path.lineTo(plot.left + w - 1, bottom)
                    path.lineTo(plot.left, bottom)
                    path.close()
                    paint.reset()
                    paint.isAntiAlias = true
                    paint.style = Paint.Style.FILL
                    paint.shader = spectrumShader(plot)
                    canvas.drawPath(path, paint)
                    paint.shader = null
                    // Re-stroke the top edge: a filled area alone loses the fine
                    // detail that makes a resonance visible.
                    path.reset()
                    for (i in 0 until w) {
                        val x = plot.left + i
                        val y = yForDb(magnitudeAt(f, i), plot, s)
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                }
                paint.reset()
                paint.isAntiAlias = true
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(if (compact) 1.2f else 1.6f)
                paint.color = palette.accent2
                canvas.drawPath(path, paint)
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
            paint.reset()
            paint.isAntiAlias = true
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1f)
            paint.color = Palette.withAlpha(palette.text, 0.45f)
            canvas.drawPath(path, paint)
        }

        if (!compact && s.showLabels) drawFrequencyLabels(canvas, plot, area, s)
    }

    private fun spectrumShader(plot: RectF) = LinearGradient(
        0f, plot.top, 0f, plot.bottom,
        intArrayOf(
            Palette.withAlpha(palette.accent2, 0.95f),
            Palette.withAlpha(palette.accent, 0.75f),
            Palette.withAlpha(palette.gradA, 0.25f),
        ),
        floatArrayOf(0f, 0.55f, 1f),
        Shader.TileMode.CLAMP,
    )

    private fun magnitudeAt(f: AnalysisFrame, i: Int): Float {
        var db = PeakHold.FLOOR_DB
        val hi = min(binHi[i], f.magnitudesDb.size - 1)
        for (b in binLo[i]..hi) db = max(db, f.magnitudesDb[b])
        return db
    }

    /**
     * Maps every pixel column to the FFT bins behind it, once per resize.
     *
     * Taking the maximum of the bins under a pixel rather than the mean is
     * deliberate: at 20 kHz on a log axis a single pixel can span forty bins,
     * and a mean turns every narrow peak into a shrug.
     */
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
        return if (s.freqScale == FreqScale.LOG) {
            (lo * exp(t * ln(hi / lo)))
        } else {
            lo + (hi - lo) * t
        }
    }

    private fun fractionForFreq(freq: Float, s: Settings): Float {
        val lo = s.minHz.coerceAtLeast(1f)
        val hi = s.maxHz.coerceAtLeast(lo + 1f)
        return if (s.freqScale == FreqScale.LOG) {
            (ln(freq / lo) / ln(hi / lo))
        } else {
            (freq - lo) / (hi - lo)
        }
    }

    private fun yForDb(db: Float, plot: RectF, s: Settings): Float {
        val span = (s.ceilingDb - s.floorDb).coerceAtLeast(1f)
        val t = ((db - s.floorDb) / span).coerceIn(0f, 1f)
        return plot.bottom - t * plot.height()
    }

    private fun drawSpectrumGrid(canvas: Canvas, plot: RectF, s: Settings) {
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(0.8f)
        paint.color = Palette.withAlpha(palette.line, if (palette.isDark) 0.9f else 0.8f)

        for (freq in GRID_FREQS) {
            if (freq < s.minHz || freq > s.maxHz) continue
            val x = plot.left + fractionForFreq(freq, s) * plot.width()
            canvas.drawLine(x, plot.top, x, plot.bottom, paint)
        }
        var db = s.ceilingDb
        while (db >= s.floorDb) {
            val y = yForDb(db, plot, s)
            canvas.drawLine(plot.left, y, plot.right, y, paint)
            db -= 20f
        }

        if (s.showLabels) {
            textPaint.color = palette.textFaint
            textPaint.textSize = sp(9f)
            textPaint.textAlign = Paint.Align.LEFT
            var d = s.ceilingDb
            while (d >= s.floorDb) {
                val y = yForDb(d, plot, s)
                if (y > plot.top + sp(9f)) {
                    canvas.drawText("${d.toInt()}", plot.left + dp(3f), y - dp(2f), textPaint)
                }
                d -= 20f
            }
        }
    }

    private fun drawFrequencyLabels(canvas: Canvas, plot: RectF, area: RectF, s: Settings) {
        textPaint.color = palette.textFaint
        textPaint.textSize = sp(9f)
        textPaint.textAlign = Paint.Align.CENTER
        for (freq in GRID_FREQS) {
            if (freq < s.minHz || freq > s.maxHz) continue
            val x = plot.left + fractionForFreq(freq, s) * plot.width()
            if (x < plot.left + dp(10f) || x > plot.right - dp(10f)) continue
            canvas.drawText(freqLabel(freq), x, area.bottom - dp(3f), textPaint)
        }
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun freqLabel(f: Float): String = when {
        f >= 1000f -> {
            val k = f / 1000f
            if (k == k.toInt().toFloat()) "${k.toInt()}k" else "${(k * 10).roundToInt() / 10f}k"
        }
        else -> "${f.toInt()}"
    }

    // ---- bands -------------------------------------------------------------

    /**
     * The independent per-band graphs.
     *
     * Wide layouts get one row per band — history on the left, live bar and
     * numeric readout on the right — because that is where a band's *shape over
     * time* is legible. Compact layouts (the collapsed notification, the widget)
     * drop to vertical bars, where the only readable quantity is the current
     * level.
     */
    private fun drawBands(canvas: Canvas, area: RectF, f: AnalysisFrame, s: Settings, compact: Boolean) {
        val bands = f.bands.filterIndexed { i, _ -> s.bands.getOrNull(i)?.enabled ?: true }
        if (bands.isEmpty()) return

        val vertical = compact || area.height() < dp(150f) || bands.size > 9
        if (vertical) {
            val gap = dp(3f)
            val slot = (area.width() - gap * (bands.size - 1)) / bands.size
            var x = area.left
            for (band in bands) {
                val r = RectF(x, area.top, x + slot, area.bottom - (if (compact) dp(8f) else dp(12f)))
                drawVerticalBandBar(canvas, r, band.rmsDb, band.holdDb, s, compact)
                textPaint.color = palette.textFaint
                textPaint.textSize = sp(if (compact) 7f else 9f)
                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(shortName(band.name), r.centerX(), area.bottom - dp(1f), textPaint)
                textPaint.textAlign = Paint.Align.LEFT
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
            val row = RectF(area.left, y, area.right, y + rowH)

            textPaint.color = palette.textDim
            textPaint.textSize = sp(10f)
            canvas.drawText(band.name, row.left, row.centerY() - sp(1f), textPaint)
            textPaint.color = palette.textFaint
            textPaint.textSize = sp(7.5f)
            canvas.drawText(
                "${band.lowHz.toInt()}–${freqLabel(band.highHz)}",
                row.left, row.centerY() + sp(9f), textPaint,
            )

            val graph = RectF(row.left + labelW, row.top, row.right - readoutW, row.bottom)
            drawBandHistory(canvas, graph, band.history, s)
            drawBandLevelBar(canvas, RectF(graph.right + dp(4f), row.top, row.right, row.bottom), band.rmsDb, band.holdDb, s)

            y += rowH + rowGap
        }
    }

    private fun shortName(name: String): String =
        if (name.length <= 5) name else name.split(" ").joinToString("") { it.take(1).uppercase() }

    private fun drawBandHistory(canvas: Canvas, r: RectF, history: com.n3d.spectra.dsp.ScrollBuffer, s: Settings) {
        if (r.width() < 4f) return
        val n = min(historyScratch.size, history.capacity)
        history.snapshot(historyScratch)
        path.reset()
        path.moveTo(r.left, r.bottom)
        for (i in 0 until n) {
            val x = r.left + r.width() * i / (n - 1f)
            val y = yForDb(historyScratch[i], r, s)
            path.lineTo(x, y)
        }
        path.lineTo(r.right, r.bottom)
        path.close()
        paint.reset()
        paint.isAntiAlias = true
        paint.shader = LinearGradient(
            0f, r.top, 0f, r.bottom,
            Palette.withAlpha(palette.accent, 0.85f),
            Palette.withAlpha(palette.gradA, 0.1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(path, paint)
        paint.shader = null
    }

    private fun drawBandLevelBar(canvas: Canvas, r: RectF, rmsDb: Float, holdDb: Float, s: Settings) {
        val trackW = min(r.width() * 0.42f, dp(10f))
        val track = RectF(r.left, r.top, r.left + trackW, r.bottom)
        paint.reset()
        paint.isAntiAlias = true
        paint.color = palette.bgDeep
        canvas.drawRoundRect(track, trackW / 2f, trackW / 2f, paint)

        val y = yForDb(rmsDb, track, s)
        paint.shader = LinearGradient(
            0f, track.top, 0f, track.bottom,
            intArrayOf(palette.bad, palette.warn, palette.accent2, palette.accent),
            floatArrayOf(0f, 0.18f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(RectF(track.left, y, track.right, track.bottom), trackW / 2f, trackW / 2f, paint)
        paint.shader = null

        val hy = yForDb(holdDb, track, s)
        paint.color = palette.text
        canvas.drawRect(track.left, hy - dp(1f), track.right, hy + dp(1f), paint)

        monoPaint.color = palette.textDim
        monoPaint.textSize = sp(9f)
        monoPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(dbText(rmsDb), r.right, r.centerY() + sp(3f), monoPaint)
        monoPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawVerticalBandBar(canvas: Canvas, r: RectF, rmsDb: Float, holdDb: Float, s: Settings, compact: Boolean) {
        val radius = min(r.width(), dp(6f)) / 2f
        paint.reset()
        paint.isAntiAlias = true
        paint.color = palette.bgDeep
        canvas.drawRoundRect(r, radius, radius, paint)

        val y = yForDb(rmsDb, r, s)
        paint.shader = LinearGradient(
            0f, r.top, 0f, r.bottom,
            intArrayOf(palette.bad, palette.warn, palette.accent2, palette.accent),
            floatArrayOf(0f, 0.18f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(RectF(r.left, y, r.right, r.bottom), radius, radius, paint)
        paint.shader = null

        if (!compact) {
            val hy = yForDb(holdDb, r, s)
            paint.color = palette.text
            canvas.drawRect(r.left, hy - dp(1f), r.right, hy + dp(1f), paint)
        }
    }

    // ---- spectrogram -------------------------------------------------------

    private fun drawSpectrogram(canvas: Canvas, area: RectF, f: AnalysisFrame, s: Settings, compact: Boolean) {
        val buffer = f.spectrogram
        val rows = buffer.rows
        val cols = buffer.columns
        val existing = spectrogramBitmap
        val bmp: Bitmap
        if (existing == null || existing.width != cols || existing.height != rows) {
            existing?.recycle()
            bmp = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)
            spectrogramBitmap = bmp
            spectrogramColumnPixels = IntArray(rows)
            spectrogramLastHead = -1
        } else {
            bmp = existing
        }

        val lut = palette.colorMap(s.colorMap)
        if (lut !== spectrogramLut) {
            spectrogramLut = lut
            spectrogramLastHead = -1
        }

        // Only convert the columns that arrived since the last draw. A full
        // rebuild is 128 k pixels; a frame usually adds one or two columns.
        val head = buffer.head
        val from = spectrogramLastHead
        if (from < 0) {
            convertColumns(bmp, buffer, 0, cols, lut)
        } else if (from != head) {
            var c = from
            while (c != head) {
                convertColumns(bmp, buffer, c, 1, lut)
                c = (c + 1) % cols
            }
        }
        spectrogramLastHead = head

        // Unwrap the ring into two blits, oldest on the left.
        val sx = area.width() / cols
        val firstWidth = cols - head
        paint.reset()
        paint.isFilterBitmap = true
        if (firstWidth > 0) {
            canvas.drawBitmap(
                bmp,
                Rect(head, 0, cols, rows),
                RectF(area.left, area.top, area.left + firstWidth * sx, area.bottom),
                paint,
            )
        }
        if (head > 0) {
            canvas.drawBitmap(
                bmp,
                Rect(0, 0, head, rows),
                RectF(area.left + firstWidth * sx, area.top, area.right, area.bottom),
                paint,
            )
        }

        if (!compact && s.showLabels) {
            textPaint.color = Palette.withAlpha(palette.text, 0.75f)
            textPaint.textSize = sp(8.5f)
            for (freq in GRID_FREQS) {
                if (freq < s.minHz || freq > s.maxHz) continue
                val t = fractionForFreq(freq, s)
                val y = area.bottom - t * area.height()
                if (y < area.top + sp(9f) || y > area.bottom - dp(2f)) continue
                canvas.drawText(freqLabel(freq), area.left + dp(3f), y - dp(1.5f), textPaint)
            }
        }
    }

    private fun convertColumns(
        bmp: Bitmap,
        buffer: com.n3d.spectra.dsp.SpectrogramBuffer,
        startColumn: Int,
        count: Int,
        lut: IntArray,
    ) {
        val rows = buffer.rows
        val pixels = spectrogramColumnPixels
        for (n in 0 until count) {
            val c = (startColumn + n) % buffer.columns
            val base = c * rows
            for (r in 0 until rows) {
                // Row 0 of the buffer is the lowest frequency; row 0 of the
                // bitmap is the top of the picture, so the axis is flipped here.
                pixels[rows - 1 - r] = lut[buffer.data[base + r].toInt() and 0xFF]
            }
            bmp.setPixels(pixels, 0, 1, c, 0, 1, rows)
        }
    }

    // ---- loudness ----------------------------------------------------------

    private fun drawLoudness(canvas: Canvas, area: RectF, f: AnalysisFrame, s: Settings, compact: Boolean) {
        val l = f.loudness
        if (compact) {
            drawLoudnessBar(canvas, area, l.momentary, l.shortTerm, s)
            return
        }

        val topH = area.height() * 0.44f
        val top = RectF(area.left, area.top, area.right, area.top + topH)
        val cellW = top.width() / 4f
        drawStat(canvas, RectF(top.left, top.top, top.left + cellW, top.bottom), "M", lufsText(l.momentary), "LUFS")
        drawStat(canvas, RectF(top.left + cellW, top.top, top.left + cellW * 2, top.bottom), "S", lufsText(l.shortTerm), "LUFS")
        drawStat(canvas, RectF(top.left + cellW * 2, top.top, top.left + cellW * 3, top.bottom), "I", lufsText(l.integrated), "LUFS")
        drawStat(
            canvas,
            RectF(top.left + cellW * 3, top.top, top.right, top.bottom),
            "TP",
            if (l.truePeakDb <= -140f) "—" else String.format(Locale.US, "%.1f", l.truePeakDb),
            "dBTP",
            valueColor = if (l.truePeakDb > -1f) palette.bad else palette.text,
        )

        val mid = RectF(area.left, top.bottom + dp(4f), area.right, area.bottom - dp(26f))
        drawLoudnessHistory(canvas, mid, s)

        val bottom = RectF(area.left, area.bottom - dp(22f), area.right, area.bottom)
        drawLoudnessBar(canvas, bottom, l.momentary, l.shortTerm, s)

        textPaint.color = palette.textFaint
        textPaint.textSize = sp(8.5f)
        val delta = if (l.integrated <= -70f) "—" else String.format(Locale.US, "%+.1f LU", l.integrated - s.loudnessTargetLufs)
        canvas.drawText(
            "target ${s.loudnessTargetLufs.toInt()} LUFS · Δ $delta · LRA ${String.format(Locale.US, "%.1f", l.range)} LU",
            area.left, area.bottom - dp(26f) - dp(2f), textPaint,
        )
    }

    private fun drawStat(
        canvas: Canvas,
        r: RectF,
        label: String,
        value: String,
        unit: String,
        valueColor: Int = palette.text,
    ) {
        textPaint.color = palette.textFaint
        textPaint.textSize = sp(9f)
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(label, r.centerX(), r.top + sp(10f), textPaint)

        monoPaint.color = valueColor
        monoPaint.textSize = min(sp(22f), r.width() * 0.34f)
        monoPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(value, r.centerX(), r.centerY() + monoPaint.textSize * 0.35f, monoPaint)
        monoPaint.textAlign = Paint.Align.LEFT

        textPaint.color = palette.textFaint
        textPaint.textSize = sp(7.5f)
        canvas.drawText(unit, r.centerX(), r.bottom - dp(2f), textPaint)
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawLoudnessHistory(canvas: Canvas, r: RectF, s: Settings) {
        if (r.height() < dp(12f)) return
        val history = AudioEngine.loudnessHistory
        history.snapshot(loudnessScratch)
        val n = loudnessScratch.size
        val lo = -40f
        val hi = 0f
        fun yFor(v: Float) = r.bottom - ((v.coerceIn(lo, hi) - lo) / (hi - lo)) * r.height()

        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(0.8f)
        paint.color = Palette.withAlpha(palette.accent, 0.55f)
        val ty = yFor(s.loudnessTargetLufs)
        canvas.drawLine(r.left, ty, r.right, ty, paint)

        path.reset()
        path.moveTo(r.left, r.bottom)
        for (i in 0 until n) {
            val x = r.left + r.width() * i / (n - 1f)
            path.lineTo(x, yFor(loudnessScratch[i]))
        }
        path.lineTo(r.right, r.bottom)
        path.close()
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            0f, r.top, 0f, r.bottom,
            Palette.withAlpha(palette.accent2, 0.7f),
            Palette.withAlpha(palette.accent2, 0.05f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(path, paint)
        paint.shader = null
    }

    private fun drawLoudnessBar(canvas: Canvas, r: RectF, momentary: Float, shortTerm: Float, s: Settings) {
        val lo = -40f
        val hi = 0f
        fun xFor(v: Float) = r.left + ((v.coerceIn(lo, hi) - lo) / (hi - lo)) * r.width()

        val h = min(r.height(), dp(14f))
        val track = RectF(r.left, r.centerY() - h / 2f, r.right, r.centerY() + h / 2f)
        paint.reset()
        paint.isAntiAlias = true
        paint.color = palette.bgDeep
        canvas.drawRoundRect(track, h / 2f, h / 2f, paint)

        paint.shader = LinearGradient(
            track.left, 0f, track.right, 0f,
            intArrayOf(palette.accent, palette.accent2, palette.warn, palette.bad),
            floatArrayOf(0f, 0.55f, 0.82f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(RectF(track.left, track.top, xFor(momentary), track.bottom), h / 2f, h / 2f, paint)
        paint.shader = null

        val sx = xFor(shortTerm)
        paint.color = palette.text
        canvas.drawRect(sx - dp(1f), track.top - dp(2f), sx + dp(1f), track.bottom + dp(2f), paint)

        val tx = xFor(s.loudnessTargetLufs)
        paint.color = Palette.withAlpha(palette.good, 0.9f)
        canvas.drawRect(tx - dp(1f), track.top - dp(3f), tx + dp(1f), track.bottom + dp(3f), paint)
    }

    // ---- stereo ------------------------------------------------------------

    private fun drawStereo(canvas: Canvas, area: RectF, f: AnalysisFrame, s: Settings, compact: Boolean) {
        if (f.gonio.isEmpty()) {
            drawIdleText(canvas, area, "mono source — no stereo image", compact)
            return
        }
        val size = min(area.width(), area.height() - (if (compact) 0f else dp(30f)))
        val cx = area.centerX()
        val cy = area.top + size / 2f
        val radius = size / 2f - dp(4f)

        // Persistence needs somewhere to persist, so the cloud is composited on
        // its own bitmap that is faded rather than cleared.
        val dim = size.roundToInt().coerceAtLeast(8)
        val existing = gonioBitmap
        val bmp: Bitmap
        if (existing == null || existing.width != dim) {
            existing?.recycle()
            bmp = Bitmap.createBitmap(dim, dim, Bitmap.Config.ARGB_8888)
            gonioBitmap = bmp
            gonioCanvas = Canvas(bmp)
        } else {
            bmp = existing
        }
        val gc = gonioCanvas ?: return

        // Fading towards the well colour instead of clearing is what leaves the
        // familiar smear behind a transient.
        val fade = (1f - s.goniometerPersistence).coerceIn(0.02f, 1f)
        paint.reset()
        paint.color = Palette.withAlpha(palette.bgDeep, fade)
        gc.drawRect(0f, 0f, dim.toFloat(), dim.toFloat(), paint)

        paint.reset()
        paint.isAntiAlias = true
        paint.color = Palette.withAlpha(palette.accent2, 0.9f)
        val r2 = dim / 2f
        val dot = max(1f, dp(1.1f))
        var i = 0
        while (i < f.gonio.size - 1) {
            val gx = r2 + f.gonio[i] * r2 * 0.92f
            val gy = r2 - f.gonio[i + 1] * r2 * 0.92f
            gc.drawCircle(gx, gy, dot, paint)
            i += 2
        }

        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(0.8f)
        paint.color = palette.line
        canvas.drawCircle(cx, cy, radius, paint)
        canvas.drawLine(cx - radius * 0.7f, cy + radius * 0.7f, cx + radius * 0.7f, cy - radius * 0.7f, paint)
        canvas.drawLine(cx - radius * 0.7f, cy - radius * 0.7f, cx + radius * 0.7f, cy + radius * 0.7f, paint)

        canvas.save()
        path.reset()
        path.addCircle(cx, cy, radius, Path.Direction.CW)
        canvas.clipPath(path)
        paint.reset()
        paint.isFilterBitmap = true
        canvas.drawBitmap(bmp, null, RectF(cx - radius, cy - radius, cx + radius, cy + radius), paint)
        canvas.restore()

        if (compact) return

        val bar = RectF(area.left, area.bottom - dp(22f), area.right, area.bottom - dp(8f))
        drawCorrelationMeter(canvas, bar, f.correlation)

        textPaint.color = palette.textFaint
        textPaint.textSize = sp(8.5f)
        canvas.drawText(
            "corr ${String.format(Locale.US, "%+.2f", f.correlation)} · S/M ${String.format(Locale.US, "%.2f", f.stereoWidth)}",
            area.left, area.bottom - dp(1f), textPaint,
        )
    }

    private fun drawCorrelationMeter(canvas: Canvas, r: RectF, correlation: Float) {
        paint.reset()
        paint.isAntiAlias = true
        paint.color = palette.bgDeep
        canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, paint)

        val centre = r.centerX()
        val x = centre + (correlation.coerceIn(-1f, 1f)) * r.width() / 2f
        paint.color = when {
            correlation < -0.2f -> palette.bad
            correlation < 0.4f -> palette.warn
            else -> palette.good
        }
        val from = min(centre, x)
        val to = max(centre, x)
        canvas.drawRoundRect(RectF(from, r.top, to, r.bottom), r.height() / 2f, r.height() / 2f, paint)
        paint.color = Palette.withAlpha(palette.text, 0.6f)
        canvas.drawRect(centre - dp(0.7f), r.top, centre + dp(0.7f), r.bottom, paint)
    }

    // ---- waveform ----------------------------------------------------------

    private fun drawWaveform(canvas: Canvas, area: RectF, f: AnalysisFrame, s: Settings, compact: Boolean) {
        val stereo = f.waveR.isNotEmpty()
        if (stereo) {
            val gap = dp(4f)
            val h = (area.height() - gap) / 2f
            drawTrace(canvas, RectF(area.left, area.top, area.right, area.top + h), f.waveL, palette.accent, compact)
            drawTrace(canvas, RectF(area.left, area.top + h + gap, area.right, area.bottom), f.waveR, palette.accent2, compact)
        } else {
            drawTrace(canvas, area, f.waveL, palette.accent, compact)
        }
    }

    private fun drawTrace(canvas: Canvas, r: RectF, samples: FloatArray, color: Int, compact: Boolean) {
        if (samples.isEmpty()) return
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(0.7f)
        paint.color = Palette.withAlpha(palette.line, 0.9f)
        canvas.drawLine(r.left, r.centerY(), r.right, r.centerY(), paint)

        path.reset()
        val half = r.height() / 2f
        for (i in samples.indices) {
            val x = r.left + r.width() * i / (samples.size - 1f)
            val y = r.centerY() - samples[i].coerceIn(-1f, 1f) * half * 0.94f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        paint.strokeWidth = dp(if (compact) 1.1f else 1.5f)
        paint.color = color
        paint.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(path, paint)
    }

    // ---- held-still scopes ------------------------------------------------

    /**
     * The Stems page: one held-still lane per separated stem, stacked, each in
     * its own colour — the chiptune channel-scope look, applied to a real mix.
     */
    private fun drawStems(canvas: Canvas, area: RectF, f: AnalysisFrame, s: Settings, compact: Boolean) {
        val scopes = f.scopes
        val lanes = scopes?.stems.orEmpty()
        val info = scopes?.stemsInfo
        if (lanes.isEmpty()) {
            val message = when {
                info == null -> "starting the stem model…"
                info.state == StemsState.FAILED -> info.message ?: "stem separation failed"
                info.state == StemsState.LOADING -> "loading the stem model…"
                else -> "separating…"
            }
            drawWrappedText(canvas, area, message, compact, info?.state == StemsState.FAILED)
            return
        }

        val footerH = if (compact || info == null) 0f else dp(15f)
        val body = RectF(area.left, area.top, area.right, area.bottom - footerH)
        val gap = dp(if (compact) 2f else 6f)
        val laneH = (body.height() - gap * (lanes.size - 1)) / lanes.size
        var y = body.top
        for ((i, lane) in lanes.withIndex()) {
            val r = RectF(body.left, y, body.right, y + laneH)
            drawScopeLane(canvas, r, lane.name, lane.trace, laneColor(i), compact)
            if (i < lanes.size - 1 && !compact) {
                paint.reset()
                paint.isAntiAlias = true
                paint.color = Palette.withAlpha(palette.line, 0.9f)
                paint.strokeWidth = dp(0.8f)
                canvas.drawLine(r.left, r.bottom + gap / 2f, r.right, r.bottom + gap / 2f, paint)
            }
            y += laneH + gap
        }
        if (footerH > 0f && info != null) drawStemsFooter(canvas, RectF(area.left, body.bottom, area.right, area.bottom), info, s)
    }

    /** Vocals, other, bass, drums — distinct at a glance, from the site's own tokens. */
    private fun laneColor(index: Int): Int = when (index) {
        0 -> palette.accent2
        1 -> palette.accent
        2 -> palette.warn
        else -> palette.good
    }

    private fun drawStemsFooter(canvas: Canvas, r: RectF, info: StemsInfo, s: Settings) {
        monoPaint.textSize = sp(8.5f)
        monoPaint.textAlign = Paint.Align.LEFT
        val load = String.format(Locale.US, "%.2f", info.load)
        // Above 1 the phone separates a second of music in more than a second.
        // It still works — the separator skips to the present — but say so,
        // rather than let a stutter look like a bug.
        val slow = info.load > 1f
        val left = if (slow) {
            "phone slower than the music — skipping to keep up"
        } else {
            "separated on this phone · ${String.format(Locale.US, "%.1f", info.msPerHop)} ms per 2.9 ms"
        }
        monoPaint.color = if (slow) palette.warn else palette.textFaint
        canvas.drawText(left, r.left, r.bottom - dp(2f), monoPaint)
        monoPaint.textAlign = Paint.Align.RIGHT
        monoPaint.color = palette.textFaint
        val right = if (slow) "load $load" else "load $load · ${s.scopeWindowMs.roundToInt()} ms"
        canvas.drawText(right, r.right, r.bottom - dp(2f), monoPaint)
        monoPaint.textAlign = Paint.Align.LEFT
    }

    /** The Waveform page in "hold still" mode: one lane, the mix, locked to its loudest note. */
    private fun drawHold(canvas: Canvas, area: RectF, f: AnalysisFrame, s: Settings, compact: Boolean) {
        val trace = f.scopes?.hold
        if (trace == null) {
            drawIdleText(canvas, area, "listening for a note to hold", compact)
            return
        }
        drawScopeLane(canvas, area, "Mix", trace, palette.accent2, compact, large = true)
    }

    /**
     * One held-still lane. The trace is already normalised, so the lane only
     * has to place it: centre line, the wave, and a label saying what it is
     * locked to — the note while there is one, the hit it froze on otherwise.
     */
    private fun drawScopeLane(
        canvas: Canvas,
        r: RectF,
        name: String,
        trace: ScopeTrace,
        color: Int,
        compact: Boolean,
        large: Boolean = false,
    ) {
        if (r.height() < 4f) return
        val labelH = if (compact) 0f else sp(if (large) 16f else 12f)
        val plot = RectF(r.left, r.top + labelH, r.right, r.bottom)
        val half = plot.height() / 2f * 0.92f

        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(0.7f)
        paint.color = Palette.withAlpha(palette.line, 0.9f)
        canvas.drawLine(plot.left, plot.centerY(), plot.right, plot.centerY(), paint)

        val pts = trace.points
        if (trace.mode != TraceMode.QUIET && pts.size > 1) {
            path.reset()
            for (i in pts.indices) {
                val x = plot.left + plot.width() * i / (pts.size - 1f)
                val y = plot.centerY() - pts[i].coerceIn(-1.1f, 1.1f) * half
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val alpha = when {
                trace.stale -> 0.45f
                trace.mode == TraceMode.FREE -> 0.7f
                else -> 1f
            }
            paint.color = Palette.withAlpha(color, alpha)
            paint.strokeWidth = dp(if (compact) 1.1f else if (large) 1.8f else 1.5f)
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawPath(path, paint)
        }

        if (compact) return

        val base = r.top + labelH - sp(3f)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = sp(if (large) 13f else 10f)
        textPaint.color = if (trace.mode == TraceMode.QUIET) palette.textFaint else color
        canvas.drawText(name, r.left, base, textPaint)

        monoPaint.textAlign = Paint.Align.RIGHT
        monoPaint.textSize = sp(if (large) 11f else 9f)
        monoPaint.color = if (trace.stale || trace.mode == TraceMode.QUIET) palette.textFaint else palette.textDim
        val right = when (trace.mode) {
            TraceMode.PITCH -> buildString {
                append(trace.note)
                append(" · ")
                append(if (trace.hz >= 100f) "${trace.hz.roundToInt()}" else String.format(Locale.US, "%.1f", trace.hz))
                append(" Hz")
                if (trace.folded > 1) append(" · ×${trace.folded}")
            }
            TraceMode.HIT -> "hit"
            TraceMode.FREE -> "free"
            TraceMode.QUIET -> "quiet"
        }
        canvas.drawText(right, r.right, base, monoPaint)
        monoPaint.textAlign = Paint.Align.LEFT
    }

    /** A message that may be a whole sentence, wrapped to the area and centred. */
    private fun drawWrappedText(canvas: Canvas, area: RectF, message: String, compact: Boolean, bad: Boolean) {
        textPaint.textSize = sp(if (compact) 9f else 11f)
        textPaint.color = if (bad) palette.bad else palette.textFaint
        textPaint.textAlign = Paint.Align.CENTER
        val maxW = area.width() - dp(24f)
        val lines = ArrayList<String>()
        var line = ""
        for (word in message.split(' ')) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (textPaint.measureText(candidate) > maxW && line.isNotEmpty()) {
                lines += line
                line = word
            } else {
                line = candidate
            }
        }
        if (line.isNotEmpty()) lines += line
        val lh = textPaint.textSize * 1.35f
        var y = area.centerY() - lh * (lines.size - 1) / 2f + textPaint.textSize / 3f
        for (l in lines) {
            canvas.drawText(l, area.centerX(), y, textPaint)
            y += lh
        }
        textPaint.textAlign = Paint.Align.LEFT
    }

    // ---- 2A03 triangle -----------------------------------------------------

    /**
     * The NES triangle view: the captured wave, held still, against the chip's
     * own 32-step staircase. A port of the Windows build's drawing, kept
     * structurally identical to it.
     *
     * Everything difficult happens in `TriangleTracker`; by the time a
     * [NesReading] arrives, the horizontal axis is *phase*, not time — 0 is the
     * first DAC step of a period and the reading is normalised to the chip's own
     * ±1. That is what lets the ideal wave be drawn as a fixed shape rather than
     * fitted to the trace, and why the two can be compared by eye.
     */
    private fun drawNesTriangle(
        canvas: Canvas,
        area: RectF,
        reading: NesReading?,
        o: NesOptions,
        f: AnalysisFrame,
        compact: Boolean,
    ) {
        val headerH = if (compact) 0f else dp(26f)
        // Two lines, not the desktop's one: a phone is too narrow for the
        // readout and the verdict side by side.
        val footerH = if (compact) 0f else dp(32f)
        val axisW = if (compact) 0f else dp(22f)
        val plot = RectF(area.left + axisW, area.top + headerH, area.right, area.bottom - footerH)
        if (plot.width() < 8f || plot.height() < 8f) return

        val cycles = (reading?.cycles ?: o.cycles).coerceIn(1, 8)
        drawNesGrid(canvas, plot, cycles, o, compact)

        if (reading == null) {
            drawIdleText(
                canvas, plot,
                "listening for a triangle between ${o.huntMinHz.toInt()} and ${o.huntMaxHz.toInt()} Hz",
                compact,
            )
            if (!compact) drawNesHeader(canvas, RectF(area.left, area.top, area.right, area.top + headerH), null, o)
            return
        }

        val half = plot.height() / 2f * 0.92f
        fun yFor(v: Float) = plot.centerY() - v.coerceIn(-1.15f, 1.15f) * half
        fun xFor(phase: Float) = plot.left + plot.width() * phase / cycles

        // Clipped to the well: the scale leaves 15 % of headroom so an overshoot
        // still shows its shape, and without a clip it is drawn over the readouts.
        canvas.save()
        canvas.clipRect(plot)
        if (o.showIdeal) drawNesIdeal(canvas, cycles, ::xFor, ::yFor, reading.stale)
        val traceColor = if (reading.stale) Palette.withAlpha(palette.accent2, 0.35f) else palette.accent2
        if (o.averaging && reading.cycle.isNotEmpty()) {
            drawNesCycle(canvas, plot, reading.cycle, cycles, o, traceColor, ::xFor, ::yFor)
        } else {
            drawNesLive(canvas, plot, reading, cycles, o, traceColor, ::xFor, ::yFor)
        }
        canvas.restore()

        if (!compact) {
            drawNesHeader(canvas, RectF(area.left, area.top, area.right, area.top + headerH), reading, o)
            drawNesFooter(canvas, RectF(area.left, area.bottom - footerH, area.right, area.bottom), reading, o)
            drawNesLevelLabels(canvas, RectF(area.left, plot.top, area.left + axisW, plot.bottom), ::yFor)
        }
    }

    /** The 4-bit ladder and the 32-step time grid: the chip's two quantisations, drawn. */
    private fun drawNesGrid(canvas: Canvas, plot: RectF, cycles: Int, o: NesOptions, compact: Boolean) {
        paint.reset()
        paint.isAntiAlias = true
        paint.color = palette.bgDeep
        canvas.drawRoundRect(plot, dp(Neu.RADIUS_SM), dp(Neu.RADIUS_SM), paint)

        paint.style = Paint.Style.STROKE
        val half = plot.height() / 2f * 0.92f
        if (o.showLevels) {
            for (level in 0 until Nes2A03.LEVELS) {
                val y = plot.centerY() - Nes2A03.valueOfLevel(level) * half
                // The chip has no zero: the two levels either side of the middle
                // are the ones it can never straddle, so they get the stronger line.
                val emphasis = if (level == 7 || level == 8) 0.55f else 0.24f
                paint.color = Palette.withAlpha(palette.line, emphasis)
                paint.strokeWidth = dp(0.7f)
                canvas.drawLine(plot.left, y, plot.right, y, paint)
            }
        }
        if (o.showSteps) {
            val total = Nes2A03.STEPS * cycles
            val stepW = plot.width() / total
            // Below about three pixels a step the grid stops being information
            // and becomes texture.
            if (stepW >= dp(2.5f)) {
                for (i in 0..total) {
                    val x = plot.left + i * stepW
                    val boundary = i % Nes2A03.STEPS == 0
                    val mid = i % (Nes2A03.STEPS / 2) == 0
                    paint.color = Palette.withAlpha(palette.line, if (boundary) 0.6f else if (mid) 0.35f else 0.16f)
                    paint.strokeWidth = dp(if (boundary) 1f else 0.7f)
                    canvas.drawLine(x, plot.top, x, plot.bottom, paint)
                }
            } else if (!compact) {
                paint.color = Palette.withAlpha(palette.line, 0.6f)
                paint.strokeWidth = dp(1f)
                for (i in 0..cycles) {
                    val x = plot.left + i * plot.width() / cycles
                    canvas.drawLine(x, plot.top, x, plot.bottom, paint)
                }
            }
        }
    }

    private fun drawNesIdeal(
        canvas: Canvas,
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
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.4f)
        paint.color = Palette.withAlpha(palette.accent, if (dim) 0.22f else 0.5f)
        canvas.drawPath(path, paint)
    }

    private fun drawNesCycle(
        canvas: Canvas,
        plot: RectF,
        cycle: FloatArray,
        cycles: Int,
        o: NesOptions,
        color: Int,
        xFor: (Float) -> Float,
        yFor: (Float) -> Float,
    ) {
        // One point per pixel column is all a stroke can show.
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
        strokeTrace(canvas, color, 1.7f)
    }

    private fun drawNesLive(
        canvas: Canvas,
        plot: RectF,
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
        strokeTrace(canvas, color, 1.5f)

        // The capture's own time grid, when the samples are far enough apart to
        // read as samples rather than as a thicker line.
        if (o.showSamples && plot.width() / (period * cycles) >= dp(4f)) {
            paint.reset()
            paint.isAntiAlias = true
            paint.color = Palette.withAlpha(palette.text, 0.75f)
            for (i in live.indices) {
                val phase = r.livePhase0 + i / period
                if (phase < 0f || phase > cycles) continue
                var v = live[i]
                if (o.quantizeToDac) v = quantize(v)
                canvas.drawCircle(xFor(phase), yFor(v), dp(1.5f), paint)
            }
        }
    }

    private fun strokeTrace(canvas: Canvas, color: Int, widthDp: Float) {
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(widthDp)
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = color
        canvas.drawPath(path, paint)
    }

    /** Snaps a normalised value to the nearest of the sixteen levels the DAC has. */
    private fun quantize(v: Float): Float {
        val level = (v * 7.5f + 7.5f).roundToInt().coerceIn(0, Nes2A03.LEVELS - 1)
        return Nes2A03.valueOfLevel(level)
    }

    private fun drawNesLevelLabels(canvas: Canvas, r: RectF, yFor: (Float) -> Float) {
        monoPaint.textSize = sp(7.5f)
        monoPaint.color = palette.textFaint
        monoPaint.textAlign = Paint.Align.RIGHT
        for (level in intArrayOf(0, 4, 8, 11, 15)) {
            val y = yFor(Nes2A03.valueOfLevel(level))
            canvas.drawText("$level", r.right - dp(3f), y + sp(2.6f), monoPaint)
        }
        monoPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawNesHeader(canvas: Canvas, r: RectF, reading: NesReading?, o: NesOptions) {
        val baseline = r.centerY() + sp(4f)
        if (reading == null) {
            textPaint.textSize = sp(11f)
            textPaint.color = palette.textFaint
            canvas.drawText("2A03 triangle", r.left, baseline, textPaint)
            monoPaint.textSize = sp(9f)
            monoPaint.color = palette.textFaint
            monoPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(o.region.label, r.right, baseline, monoPaint)
            monoPaint.textAlign = Paint.Align.LEFT
            return
        }
        var x = r.left
        textPaint.textSize = sp(15f)
        textPaint.color = if (reading.stale) palette.textFaint else palette.text
        canvas.drawText(reading.note, x, baseline, textPaint)
        x += textPaint.measureText(reading.note) + dp(8f)

        // Rounded first, then signed: %+.0f of −0.2 prints "-0 ct", which reads
        // as a tuning error that is not there.
        val roundedCents = reading.cents.roundToInt()
        val cents = if (roundedCents == 0) "in tune" else String.format(Locale.US, "%+d ct", roundedCents)
        monoPaint.textSize = sp(9f)
        monoPaint.color = palette.textFaint
        canvas.drawText(cents, x, baseline, monoPaint)
        x += monoPaint.measureText(cents) + dp(12f)

        // The register value is the point of the whole view: the number a tracker
        // would have written to $400A/$400B to make this sound.
        val timer = "\$${reading.timer.toString(16).uppercase().padStart(3, '0')}"
        monoPaint.textSize = sp(12f)
        monoPaint.color = palette.accent
        canvas.drawText(timer, x, baseline, monoPaint)
        x += monoPaint.measureText(timer) + dp(6f)
        monoPaint.textSize = sp(9f)
        monoPaint.color = palette.textDim
        canvas.drawText("(${reading.timer}) · ${String.format(Locale.US, "%.2f", reading.nesHz)} Hz", x, baseline, monoPaint)

        monoPaint.textAlign = Paint.Align.RIGHT
        monoPaint.color = if (reading.stale) palette.warn else palette.textFaint
        canvas.drawText(if (reading.stale) "${o.region.label} · holding" else o.region.label, r.right, baseline, monoPaint)
        monoPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawNesFooter(canvas: Canvas, r: RectF, reading: NesReading, o: NesOptions) {
        monoPaint.textSize = sp(8.5f)
        val baseline = r.bottom - dp(3f)
        val sps = reading.samplesPerStep
        val parts = buildString {
            append(String.format(Locale.US, "%.1f smp/step", sps))
            append(" · fold ")
            append(reading.foldedPeriods)
            if (reading.foldedPeriods < o.foldPeriods) append("/${o.foldPeriods}")
            append(" · fit ")
            append(String.format(Locale.US, "%.2f", reading.stepMatch))
            append(" · grid ")
            append(String.format(Locale.US, "%+.2f", reading.gridCents))
            append("/")
            append(String.format(Locale.US, "%.1f ct", reading.gridStepCents))
        }
        monoPaint.color = palette.textFaint
        canvas.drawText(parts, r.left, baseline, monoPaint)

        // One short verdict, because the numbers only mean something to someone
        // who already knows what they mean.
        val (verdict, color) = when {
            !reading.audible -> "below audible" to palette.warn
            sps < 4.0 -> "too high to show steps" to palette.warn
            reading.stepMatch < 0.35f -> "buried" to palette.warn
            reading.stepMatch < 0.7f -> "partly buried" to palette.textDim
            else -> "clean staircase" to palette.good
        }
        monoPaint.color = color
        canvas.drawText(verdict, r.left, r.top + sp(12f), monoPaint)
    }

    private fun drawIdleText(canvas: Canvas, area: RectF, message: String, compact: Boolean) {
        textPaint.color = palette.textFaint
        textPaint.textSize = sp(if (compact) 9f else 11f)
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(message, area.centerX(), area.centerY(), textPaint)
        textPaint.textAlign = Paint.Align.LEFT
    }

    // ---- helpers -----------------------------------------------------------

    fun release() {
        spectrogramBitmap?.recycle()
        spectrogramBitmap = null
        gonioBitmap?.recycle()
        gonioBitmap = null
        gonioCanvas = null
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
