package com.n3d.spectra.paint

import com.n3d.spectra.dsp.BeamClock
import com.n3d.spectra.dsp.BeamInterpolator
import com.n3d.spectra.dsp.BeamSweep
import com.n3d.spectra.dsp.StereoFeed
import com.n3d.spectra.settings.OscMode
import com.n3d.spectra.settings.Phosphor
import com.n3d.spectra.settings.Settings
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Where the Oscilloscope page puts its tube, in a surface's own pixels.
 *
 * Shared by both painters, so a division is the same share of the glass on
 * the phone and on Windows. X-Y gets a square graticule — a circle has to come
 * out round — while Y-T spreads its ten divisions over whatever room there is.
 */
class CrtLayout {
    var faceL = 0f; private set
    var faceT = 0f; private set
    var faceR = 0f; private set
    var faceB = 0f; private set
    var gratL = 0f; private set
    var gratT = 0f; private set
    var gratR = 0f; private set
    var gratB = 0f; private set
    var divisionsX = 8; private set
    var divisionsY = 8; private set
    /** Corner radius of the glass. */
    var radius = 0f; private set
    /** Baseline of the readout under the graticule; NaN on a compact surface. */
    var readoutY = Float.NaN; private set
    /** Pixels per dp on the surface: what the tube's own resolution is chosen from. */
    var density = 1f; private set

    fun compute(left: Float, top: Float, right: Float, bottom: Float, density: Float, mode: OscMode, compact: Boolean) {
        this.density = density
        faceL = left
        faceT = top
        faceR = right
        faceB = bottom
        radius = density * if (compact) 6f else 12f
        val pad = density * if (compact) 3f else 9f
        val band = if (compact) 0f else density * 18f
        readoutY = if (compact) Float.NaN else bottom - density * 8f
        divisionsX = if (mode == OscMode.XY) 8 else Crt.TIME_DIVISIONS
        divisionsY = 8
        val l = left + pad
        val t = top + pad
        val r = right - pad
        val b = bottom - pad - band
        if (mode == OscMode.XY) {
            val side = max(0f, min(r - l, b - t))
            gratL = (l + r - side) / 2f
            gratT = (t + b - side) / 2f
            gratR = gratL + side
            gratB = gratT + side
        } else {
            gratL = l
            gratT = t
            gratR = max(l, r)
            gratB = max(t, b)
        }
    }
}

/**
 * The Oscilloscope page's tube: a beam, a phosphor, and the glass in front of it.
 *
 * What makes a real scope look like one is that brightness is *time*: the
 * phosphor glows in proportion to how long the beam spends on it. A slow stroke
 * is bright, a fast jump across the screen is a faint thread, and a beam at
 * rest burns a white-hot dot — which is exactly why oscilloscope music can hide
 * the moves between its shapes. So the beam here deposits energy along its
 * path, the same amount for every instant, spread over however much glass that
 * instant covers. The picture is that energy fading with the phosphor's
 * persistence, developed through a curve that saturates the phosphor's own
 * colour first and white after, so the core of a bright stroke goes pale while
 * its edges stay green. The glow is a blur of the same energy: the light a
 * bright stroke throws on the glass around it.
 *
 * It runs on the surface's own thread and keeps its own state — the phosphor
 * remembers — so each surface has one. Pure arithmetic into an int array, so
 * the phone and Windows draw the same tube.
 */
class Crt {

    var width = 0
        private set
    var height = 0
        private set

    /** The developed picture: opaque ARGB, row by row, [width] × [height]. */
    var pixels = IntArray(0)
        private set

    /** True when the last [render] changed [pixels]. */
    var changed = false
        private set

    /** Whether the Y-T sweep on screen was started by the signal (false: AUTO). */
    val triggered: Boolean get() = sweep.triggered

    private var energy = FloatArray(0)
    /**
     * The glow, at a quarter of the tube's resolution — it is a blur several
     * pixels wide, and a quarter of the pixels is a quarter of the blurring.
     * [glow] is what this frame shows, blurred from what the last one
     * gathered; [glowNext] is what this frame gathers.
     */
    private var glow = FloatArray(0)
    private var glowNext = FloatArray(0)
    private var glowTmp = FloatArray(0)
    private var glowW = 0
    private var glowH = 0
    /** For each column of the tube, the glow column left of it and how far towards the next. */
    private var glowCol = IntArray(0)
    private var glowFrac = FloatArray(0)
    /** One row of glow, interpolated to the tube row being developed. */
    private var glowRow = FloatArray(0)

    private val clock = BeamClock()
    private val interpolator = BeamInterpolator()
    private val sweep = BeamSweep()
    private var planar = FloatArray(0)
    private var mix = FloatArray(0)
    private var pointsX = FloatArray(0)
    private var pointsY = FloatArray(0)

    private var glowRadius = 1
    private var lut = IntArray(0)
    private var lutPhosphor: Phosphor? = null
    private var face = 0
    private var mode: OscMode? = null
    private var lastGlowGain = -1f
    private var dirty = true

    // The graticule in the tube's own pixels.
    private var gridL = 0f
    private var gridR = 0f
    private var centreX = 0f
    private var centreY = 0f
    private var halfW = 1f
    private var halfH = 1f

    // Where the beam was at the end of the last frame, so strokes join up.
    private var beamX = 0f
    private var beamY = 0f
    private var beamOn = false

    /**
     * Moves the beam on to [nowNanos] and develops the picture into [pixels].
     *
     * Returns false when nothing arrived to draw — the capture is paused, or
     * this frame fell between two of its bursts — and [pixels] still holds the
     * previous picture, unfaded: the phosphor fades with audio time, not wall
     * time, so a paused scope freezes the way every other page does.
     */
    fun render(
        feed: StereoFeed,
        rate: Int,
        s: Settings,
        layout: CrtLayout,
        nowNanos: Long,
        glowAllowed: Boolean = true,
    ): Boolean {
        changed = false
        val fw = layout.faceR - layout.faceL
        val fh = layout.faceB - layout.faceT
        if (fw < 2f || fh < 2f || rate <= 0) return false
        val scale = internalScale(fw, fh, layout.density)
        val w = max(2, ceil(fw * scale).toInt())
        val h = max(2, ceil(fh * scale).toInt())
        if (w != width || h != height) resize(w, h)

        // The halo is a few dp wide on every surface: the same soft edge on a
        // phone and on a monitor, where the glass is twice the size in dp.
        glowRadius = max(1, (GLOW_SIGMA_DP * layout.density * scale / GLOW_CELL * 0.8f).roundToInt())
        val sx = w / fw
        val sy = h / fh
        val gl = (layout.gratL - layout.faceL) * sx
        val gr = (layout.gratR - layout.faceL) * sx
        val gt = (layout.gratT - layout.faceT) * sy
        val gb = (layout.gratB - layout.faceT) * sy
        if (gl != gridL || gr != gridR || (gt + gb) / 2f != centreY || (gb - gt) / 2f != halfH) {
            gridL = gl
            gridR = gr
            centreX = (gl + gr) / 2f
            centreY = (gt + gb) / 2f
            halfW = max(1f, (gr - gl) / 2f)
            halfH = max(1f, (gb - gt) / 2f)
            // The old picture is in the old coordinates.
            clearEnergy()
            beamOn = false
        }
        if (s.oscMode != mode) {
            mode = s.oscMode
            clearEnergy()
            sweep.reset()
            beamOn = false
        }
        if (s.oscPhosphor != lutPhosphor) buildLut(s.oscPhosphor)
        val glowGain = if (glowAllowed) s.oscGlow.coerceIn(0f, 1f) * GLOW_GAIN else 0f
        if (glowGain != lastGlowGain) {
            // Switched back on, the glow would show whatever it last gathered.
            if (lastGlowGain <= 0f) java.util.Arrays.fill(glow, 0f)
            lastGlowGain = glowGain
            dirty = true
        }

        clock.advance(feed.written, rate, nowNanos)
        val tauFrames = max(1.0, rate * s.oscPersistenceMs / 1000.0)
        // Further back than this the picture has faded below what shows.
        val reach = min((tauFrames * FADE_TAUS).toLong() + rate / 30, (rate * MAX_DRAW_S).toLong())
            .coerceAtMost(feed.capacity - 4L * BeamInterpolator.CONTEXT)
        val to = clock.to
        var from = clock.from
        var decay = 1.0
        if (clock.jumped) {
            clearEnergy()
            sweep.reset()
            beamOn = false
            from = max(0L, to - reach)
        } else {
            decay = exp(-(to - from) / tauFrames)
            if (to - from > reach) {
                // A slow surface: its frame starts too long ago to show.
                from = to - reach
                sweep.reset()
                beamOn = false
            }
        }
        val count = (to - from).toInt()
        if (count <= 0 && !dirty) return false
        if (decay < 1e-6) {
            clearEnergy()
            decay = 1.0
        }
        if (count > 0) trace(feed, rate, s, from, count, tauFrames, decay)
        develop(decay.toFloat(), glowGain)
        dirty = false
        changed = true
        return true
    }

    fun release() {
        width = 0
        height = 0
        pixels = IntArray(0)
        energy = FloatArray(0)
        glow = FloatArray(0)
        glowNext = FloatArray(0)
        glowTmp = FloatArray(0)
        planar = FloatArray(0)
        mix = FloatArray(0)
        pointsX = FloatArray(0)
        pointsY = FloatArray(0)
        clock.reset()
        sweep.reset()
        beamOn = false
        dirty = true
    }

    // ---- the beam ----------------------------------------------------------

    private fun trace(feed: StereoFeed, rate: Int, s: Settings, from: Long, count: Int, tauFrames: Double, decay: Double) {
        val context = BeamInterpolator.CONTEXT
        val n = count + 2 * context
        if (planar.size < 2 * n) planar = FloatArray(2 * n + 1024)
        if (!feed.readPlanar(from - context, n, planar)) {
            // Overwritten or not there yet: start again from the present.
            clock.reset()
            return
        }
        val points = count * BeamInterpolator.FACTOR
        if (pointsY.size < points) {
            pointsX = FloatArray(points + 4096)
            pointsY = FloatArray(points + 4096)
        }
        // Every instant of beam gets the same energy; scaled with the graticule
        // so a figure is as bright on a small surface as on a big one, and
        // pre-divided by the fade develop() is about to apply to everything.
        val e0 = s.oscIntensity.coerceIn(0.01f, 100f) * ENERGY_PER_SECOND * (halfH / REF_HALF) /
            (rate.toDouble() * BeamInterpolator.FACTOR) / decay
        if (s.oscMode == OscMode.YT) {
            if (mix.size < n) mix = FloatArray(n + 1024)
            for (i in 0 until n) mix[i] = 0.5f * (planar[i] + planar[n + i])
            interpolator.interpolate(mix, context, count, pointsY)
            traceYT(rate, s, from, count, tauFrames, e0)
        } else {
            interpolator.interpolate(planar, context, count, pointsX)
            interpolator.interpolate(planar, n + context, count, pointsY)
            traceXY(s, count, tauFrames, e0)
        }
    }

    private fun traceXY(s: Settings, count: Int, tauFrames: Double, e0: Double) {
        val zx = s.oscZoom * halfW
        val zy = s.oscZoom * halfH
        var x0 = beamX
        var y0 = beamY
        var on = beamOn
        var k = 0
        for (i in 0 until count) {
            // Older instants have been fading for longer.
            val e = (e0 * exp(-(count - 1 - i) / tauFrames)).toFloat()
            for (p in 0 until BeamInterpolator.FACTOR) {
                val x1 = centreX + pointsX[k] * zx
                val y1 = centreY - pointsY[k] * zy
                if (on) segment(x0, y0, x1, y1, e) else splat(x1 - 0.5f, y1 - 0.5f, e)
                x0 = x1
                y0 = y1
                on = true
                k++
            }
        }
        beamX = x0
        beamY = y0
        beamOn = on
    }

    private fun traceYT(rate: Int, s: Settings, from: Long, count: Int, tauFrames: Double, e0: Double) {
        val context = BeamInterpolator.CONTEXT
        val factor = BeamInterpolator.FACTOR
        val sweepFrames = TIME_DIVISIONS * s.oscTimeDivMs * rate / 1000.0
        sweep.configure(rate, sweepFrames)
        val autoFrames = max(rate * AUTO_S, sweepFrames * 1.5)
        val zy = s.oscZoom * halfH
        val span = gridR - gridL
        var x0 = beamX
        var y0 = beamY
        var on = beamOn
        for (i in 0 until count) {
            val frame = from + i
            sweep.step(frame, mix[context + i], autoFrames)
            if (!sweep.sweeping) {
                on = false
                continue
            }
            val e = (e0 * exp(-(count - 1 - i) / tauFrames)).toFloat()
            for (p in 0 until factor) {
                val u = sweep.x(frame + p.toDouble() / factor, sweepFrames)
                if (u < 0.0) continue
                if (u > 1.0) {
                    // Retrace, blanked, as on the real thing.
                    sweep.end()
                    on = false
                    break
                }
                val x1 = gridL + (u * span).toFloat()
                val y1 = centreY - pointsY[i * factor + p] * zy
                if (on) segment(x0, y0, x1, y1, e) else splat(x1 - 0.5f, y1 - 0.5f, e)
                x0 = x1
                y0 = y1
                on = true
            }
        }
        beamX = x0
        beamY = y0
        beamOn = on
    }

    /**
     * One instant of beam, from (x0, y0) to (x1, y1) in the tube's pixels:
     * [e] spread evenly along it, so energy per pixel falls as the beam speeds up.
     */
    private fun segment(x0: Float, y0: Float, x1: Float, y1: Float, e: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        if ((x0 < -2f && x1 < -2f) || (x0 > w + 2f && x1 > w + 2f) ||
            (y0 < -2f && y1 < -2f) || (y0 > h + 2f && y1 > h + 2f)
        ) {
            return
        }
        val dx = x1 - x0
        val dy = y1 - y0
        val len = sqrt(dx * dx + dy * dy)
        var steps = (len * STEPS_PER_PX).toInt() + 1
        if (steps > MAX_STEPS) steps = MAX_STEPS
        val es = e / steps
        val stepX = dx / steps
        val stepY = dy / steps
        // Pixel i's centre is at i + 0.5; splat works in centre coordinates.
        var x = x0 + stepX * 0.5f - 0.5f
        var y = y0 + stepY * 0.5f - 0.5f
        for (i in 0 until steps) {
            splat(x, y, es)
            x += stepX
            y += stepY
        }
    }

    /** Deposits [e] at (x, y), in pixel-centre coordinates, shared bilinearly between four pixels. */
    private fun splat(x: Float, y: Float, e: Float) {
        val fx0 = floor(x)
        val fy0 = floor(y)
        val ix = fx0.toInt()
        val iy = fy0.toInt()
        val fx = x - fx0
        val fy = y - fy0
        val w = width
        val h = height
        if (ix >= 0 && iy >= 0 && ix < w - 1 && iy < h - 1) {
            val i = iy * w + ix
            val gx = 1f - fx
            val gy = 1f - fy
            energy[i] += e * gx * gy
            energy[i + 1] += e * fx * gy
            energy[i + w] += e * gx * fy
            energy[i + w + 1] += e * fx * fy
        } else if (ix >= -1 && iy >= -1 && ix < w && iy < h) {
            add(ix, iy, e * (1f - fx) * (1f - fy))
            add(ix + 1, iy, e * fx * (1f - fy))
            add(ix, iy + 1, e * (1f - fx) * fy)
            add(ix + 1, iy + 1, e * fx * fy)
        }
    }

    private fun add(x: Int, y: Int, e: Float) {
        if (x < 0 || y < 0 || x >= width || y >= height) return
        energy[y * width + x] += e
    }

    // ---- the phosphor ------------------------------------------------------

    /**
     * Fades everything by [decay] and develops it, with the glass's glow, into
     * [pixels] — in one pass over the tube, the work that has to be done for
     * every pixel of every frame.
     *
     * Two things keep it cheap. The glow shown is the one gathered on the last
     * frame, blurred: a sixtieth of a second behind the stroke, which nobody
     * can see, and it saves reading the whole tube a second time. And the tone
     * curve is looked up by the energy's own float bits — exponent and top of
     * the mantissa — which is a logarithmic table with no division in it: fine
     * steps where faint trails need them, coarse ones where it is all white.
     */
    private fun develop(decay: Float, glowGain: Float) {
        val w = width
        val h = height
        val e = energy
        val out = pixels
        val table = lut
        val dark = face
        if (glowGain > 0f) {
            val shown = glow
            val next = glowNext
            java.util.Arrays.fill(next, 0f)
            val gw = glowW
            val gh = glowH
            val col = glowCol
            val frac = glowFrac
            val line = glowRow
            // The glow was gathered before this frame's fade.
            val gain = glowGain * decay
            for (y in 0 until h) {
                val row = y * w
                // Bilinear, so the halo is smooth rather than made of blocks:
                // this row's glow, interpolated once, then across it per pixel.
                val fy = (y + 0.5f) / GLOW_CELL - 0.5f
                var y0 = floor(fy).toInt()
                var ty = fy - y0
                if (y0 < 0) { y0 = 0; ty = 0f }
                if (y0 > gh - 2) { y0 = gh - 2; ty = 1f }
                val g0 = y0 * gw
                for (c in 0 until gw) line[c] = shown[g0 + c] + (shown[g0 + gw + c] - shown[g0 + c]) * ty
                // Gathered a cell at a time: adding each pixel straight into
                // the array makes every add wait for the one before it.
                var cell = (y shr GLOW_SHIFT) * gw
                var x = 0
                while (x < w) {
                    val end = if (x + GLOW_CELL < w) x + GLOW_CELL else w
                    var gathered = 0f
                    while (x < end) {
                        val i = row + x
                        var v = e[i] * decay
                        if (v < FLOOR) v = 0f
                        e[i] = v
                        // Capped, because a phosphor cannot give out more light than it
                        // has: a resting beam piles thousands of strokes into one pixel.
                        gathered += if (v < GLOW_CAP) v else GLOW_CAP
                        val c = col[x]
                        val g = line[c] + (line[c + 1] - line[c]) * frac[x]
                        out[i] = table[toneIndex(v + g * gain)]
                        x++
                    }
                    next[cell++] += gathered
                }
            }
            // Swap: what was gathered is blurred into next frame's glow.
            glowNext = shown
            glow = next
            val norm = 1f / (GLOW_CELL * GLOW_CELL)
            for (k in next.indices) next[k] *= norm
            blur(next, glowTmp, gw, gh, glowRadius)
        } else {
            for (i in 0 until w * h) {
                var v = e[i] * decay
                if (v < FLOOR) v = 0f
                e[i] = v
                out[i] = table[toneIndex(v)]
            }
        }
        if (dark != table[0]) face = table[0]
    }

    /**
     * Three box passes each way — close enough to a Gaussian that a single
     * bright dot throws a round halo, at a fixed cost per pixel whatever the
     * radius. Both directions walk the rows in order; a column-by-column
     * vertical pass spends its time waiting on memory.
     */
    private fun blur(a: FloatArray, tmp: FloatArray, w: Int, h: Int, r: Int) {
        repeat(3) {
            boxRows(a, tmp, w, h, r)
            boxColumns(tmp, a, w, h, r)
        }
    }

    private fun boxRows(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        val norm = 1f / (2 * r + 1)
        for (y in 0 until h) {
            val row = y * w
            var acc = 0f
            for (k in -r..r) acc += src[row + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                dst[row + x] = acc * norm
                acc += src[row + min(x + r + 1, w - 1)] - src[row + max(x - r, 0)]
            }
        }
    }

    private fun boxColumns(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        val norm = 1f / (2 * r + 1)
        val acc = columnAcc.let { if (it.size >= w) it else FloatArray(w).also { a -> columnAcc = a } }
        java.util.Arrays.fill(acc, 0, w, 0f)
        for (k in -r..r) {
            val row = k.coerceIn(0, h - 1) * w
            for (x in 0 until w) acc[x] += src[row + x]
        }
        for (y in 0 until h) {
            val row = y * w
            val add = min(y + r + 1, h - 1) * w
            val sub = max(y - r, 0) * w
            for (x in 0 until w) {
                dst[row + x] = acc[x] * norm
                acc[x] += src[add + x] - src[sub + x]
            }
        }
    }

    private var columnAcc = FloatArray(0)

    private fun resize(w: Int, h: Int) {
        width = w
        height = h
        energy = FloatArray(w * h)
        pixels = IntArray(w * h)
        // One spare cell each way, so the interpolation at the far edges has a neighbour.
        glowW = (w + GLOW_CELL - 1) / GLOW_CELL + 1
        glowH = (h + GLOW_CELL - 1) / GLOW_CELL + 1
        glow = FloatArray(glowW * glowH)
        glowNext = FloatArray(glowW * glowH)
        glowTmp = FloatArray(glowW * glowH)
        glowRow = FloatArray(glowW)
        glowCol = IntArray(w)
        glowFrac = FloatArray(w)
        for (x in 0 until w) {
            val f = (x + 0.5f) / GLOW_CELL - 0.5f
            val c = floor(f).toInt().coerceIn(0, glowW - 2)
            glowCol[x] = c
            glowFrac[x] = (f - c).coerceIn(0f, 1f)
        }
        beamOn = false
        dirty = true
    }

    private fun clearEnergy() {
        java.util.Arrays.fill(energy, 0f)
        java.util.Arrays.fill(glow, 0f)
        dirty = true
    }

    private fun buildLut(p: Phosphor) {
        // Entry 0 is the dark glass; entry i the energy at the middle of its
        // bucket of float bits.
        lut = IntArray(LUT_SIZE) { i -> if (i == 0) colorOf(p, 0f) else colorOf(p, energyOfIndex(i)) }
        face = lut[0]
        lutPhosphor = p
        dirty = true
    }

    companion object {
        /**
         * The tube's resolution relative to the surface: about 1.4 tube pixels
         * a dp. That is half resolution on a phone — a trace still about a dp
         * wide, a soft edge the glow wants anyway, a quarter of the work — and
         * one to one on a desktop monitor, where it saves stretching the
         * picture in software, which costs far more than drawing it. Small
         * surfaces keep enough pixels to draw with; big ones are capped.
         */
        fun internalScale(faceW: Float, faceH: Float, density: Float): Float {
            val maxDim = max(faceW, faceH)
            if (maxDim <= 0f) return 1f
            var scale = min(1f, TUBE_PX_PER_DP / density.coerceAtLeast(0.5f))
            scale = max(scale, min(1f, MIN_TUBE_PX / maxDim))
            if (maxDim * scale > MAX_TUBE_PX) scale = MAX_TUBE_PX / maxDim
            return scale
        }

        /** The phosphor lit to [x]: 0 is the dark glass, about 1 a steady stroke, 20 and up white. */
        fun colorOf(p: Phosphor, x: Float): Int {
            val spectrum = spectrumOf(p)
            val glass = glassOf(p)
            return Palette.argb(
                255,
                channel(x, spectrum[0], Palette.red(glass)),
                channel(x, spectrum[1], Palette.green(glass)),
                channel(x, spectrum[2], Palette.blue(glass)),
            )
        }

        /**
         * How strongly each of red, green and blue answers the beam. The main
         * one saturates first and the faint ones only at high energy, which is
         * what turns the core of a bright stroke white while its edges keep
         * the phosphor's colour.
         */
        private fun spectrumOf(p: Phosphor): FloatArray = when (p) {
            Phosphor.GREEN -> floatArrayOf(0.030f, 1f, 0.075f)
            Phosphor.AMBER -> floatArrayOf(1f, 0.40f, 0.020f)
            Phosphor.BLUE -> floatArrayOf(0.050f, 0.26f, 1f)
            Phosphor.SPECTRA -> floatArrayOf(0.035f, 0.80f, 1f)
        }

        /** The unlit glass: nearly black, tinted by the phosphor coating behind it. */
        private fun glassOf(p: Phosphor): Int = when (p) {
            Phosphor.GREEN -> 0xFF090E0B.toInt()
            Phosphor.AMBER -> 0xFF0E0B08.toInt()
            Phosphor.BLUE -> 0xFF080A10.toInt()
            Phosphor.SPECTRA -> 0xFF080C10.toInt()
        }

        private fun channel(x: Float, weight: Float, glass: Int): Int {
            // Light is linear in the energy until the phosphor saturates, and
            // is encoded for the display the way sRGB is. Without the encoding
            // every faint stroke would vanish into the glass; with a plain
            // power curve instead, its steep foot would draw a visible ring
            // where the outermost glow starts.
            val light = 1.0 - exp(-(x * weight).toDouble())
            val lit = if (light <= 0.0031308) 12.92 * light else 1.055 * light.pow(1.0 / 2.4) - 0.055
            val base = glass / 255.0
            val v = 1.0 - (1.0 - base) * (1.0 - lit)
            return (v * 255.0 + 0.5).toInt().coerceIn(0, 255)
        }

        /**
         * The tone table's index for energy [v]: its float bits shifted down to
         * the exponent and the top 8 bits of the mantissa, counted from
         * [LUT_FLOOR]. Floats are ordered like their bits, so this is a log
         * scale: 256 steps to every doubling, from 1/2048 of a steady stroke
         * to 256 times one, clamped at both ends.
         */
        @Suppress("NOTHING_TO_INLINE")
        private inline fun toneIndex(v: Float): Int {
            // Negative (a blur's rounding) and anything under the floor are the dark glass.
            val i = (java.lang.Float.floatToRawIntBits(v) shr LUT_SHIFT) - LUT_BASE
            return if (i < 1) 0 else if (i >= LUT_SIZE) LUT_SIZE - 1 else i
        }

        private fun energyOfIndex(i: Int): Float =
            java.lang.Float.intBitsToFloat(((i + LUT_BASE) shl LUT_SHIFT) or (1 shl (LUT_SHIFT - 1)))

        private const val TUBE_PX_PER_DP = 1.4f
        private const val MIN_TUBE_PX = 220f
        private const val MAX_TUBE_PX = 1000f

        private const val LUT_SHIFT = 15
        private const val LUT_FLOOR = 1f / 2048f
        private val LUT_BASE = java.lang.Float.floatToRawIntBits(LUT_FLOOR) shr LUT_SHIFT
        /** Nineteen doublings, 1/2048 to 256. */
        private const val LUT_SIZE = 19 * 256

        /** Energy the beam lays down per second of audio, at unit intensity. */
        private const val ENERGY_PER_SECOND = 1.1e5
        /** The graticule half-height, in tube pixels, that [ENERGY_PER_SECOND] was tuned at. */
        private const val REF_HALF = 225f
        /** Beam steps per pixel of path: close enough that the bilinear footprints overlap. */
        private const val STEPS_PER_PX = 1.4f
        /** A jump across the whole face is about 900 steps; nothing needs more. */
        private const val MAX_STEPS = 1200
        private const val FADE_TAUS = 6.0
        private const val MAX_DRAW_S = 0.5
        private const val GLOW_GAIN = 0.8f
        /** How soft the halo is, in dp. */
        private const val GLOW_SIGMA_DP = 4f
        /** Tube pixels to a glow cell, each way. */
        private const val GLOW_CELL = 4
        private const val GLOW_SHIFT = 2
        /** The brightest a pixel counts for in the glow: a few times a steady stroke. */
        private const val GLOW_CAP = 6f
        private const val FLOOR = 1e-7f
        const val TIME_DIVISIONS = 10
        /** A bench scope's AUTO waits about this long for a trigger before sweeping anyway. */
        private const val AUTO_S = 0.06
    }
}
