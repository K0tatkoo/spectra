package com.n3d.spectra.desktop.audio

import com.n3d.spectra.audio.AudioCapture
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Oscilloscope music, made up on the spot: the thing the Oscilloscope page's
 * X-Y mode exists for, as a signal that is definitely there.
 *
 * Three scenes, six seconds each: a wireframe cube turning in perspective, a
 * Lissajous figure drifting through its phases, and the word SPECTRA in stroke
 * letters. Each is traced once per figure — the figure rate is also the pitch
 * you hear — at an even speed along its lines and a fast one across the gaps
 * between them. That is how the genre hides its pen-up moves: a tube barely
 * lights where the beam hurries.
 */
class OscDemo(private val rate: Int) {

    private val figure = Figure()
    private var sample = 0L
    private var inFigure = 0
    private var figureLength = 0

    /** Seconds of demo rendered so far. */
    val seconds: Double get() = sample.toDouble() / rate

    fun render(left: FloatArray, right: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            if (inFigure >= figureLength) nextFigure()
            val u = (inFigure + 0.5) / figureLength
            figure.at(u)
            left[i] = figure.x
            right[i] = figure.y
            inFigure++
            sample++
        }
    }

    private fun nextFigure() {
        val t = seconds
        figure.clear()
        val hz = when (((t / SCENE_S).toInt()) % 3) {
            0 -> { cube(t); CUBE_HZ }
            1 -> { lissajous(t); LISSAJOUS_HZ }
            else -> { word(t); WORD_HZ }
        }
        figure.close()
        figureLength = max(8, (rate / hz).toInt())
        inFigure = 0
    }

    private fun cube(t: Double) {
        val a = t * 0.45
        val b = t * 0.28
        val px = DoubleArray(8)
        val py = DoubleArray(8)
        for (v in 0 until 8) {
            var x = if (v and 1 == 0) -1.0 else 1.0
            var y = if (v and 2 == 0) -1.0 else 1.0
            var z = if (v and 4 == 0) -1.0 else 1.0
            // Turn about y, then about x.
            val x1 = x * cos(a) + z * sin(a)
            val z1 = -x * sin(a) + z * cos(a)
            val y1 = y * cos(b) - z1 * sin(b)
            val z2 = y * sin(b) + z1 * cos(b)
            x = x1
            y = y1
            z = z2
            val depth = 2.4 / (z + 4.0)
            px[v] = x * depth * 0.9
            py[v] = y * depth * 0.9
        }
        // Every edge of a cube in one closed walk: the four it has to cross
        // twice are drawn fast, so they are not twice as bright.
        for ((i, v) in CUBE_WALK.withIndex()) {
            figure.add(px[v].toFloat(), py[v].toFloat(), fast = i in CUBE_RETRACE)
        }
    }

    private fun lissajous(t: Double) {
        val phase = t * 0.35
        val n = 240
        for (i in 0..n) {
            val u = i.toDouble() / n
            figure.add(
                (0.8 * sin(2 * PI * 3 * u + phase)).toFloat(),
                (0.8 * sin(2 * PI * 2 * u)).toFloat(),
                fast = false,
            )
        }
    }

    private fun word(t: Double) {
        val text = "SPECTRA"
        val size = 0.21
        val advance = 0.27
        val x0 = -advance * (text.length - 1) / 2.0 - size / 2.0
        for ((k, c) in text.withIndex()) {
            val strokes = GLYPHS[c] ?: continue
            for (stroke in strokes) {
                for (j in 0 until stroke.size / 2) {
                    val gx = x0 + k * advance + stroke[j * 2] * size
                    val gy = (stroke[j * 2 + 1] - 0.5) * size * 1.5
                    // A slow wave through the word, so it is plainly live.
                    val wave = 0.06 * sin(2 * PI * 0.4 * t + gx * 4.0)
                    figure.add(gx.toFloat(), (gy + wave).toFloat(), fast = j == 0)
                }
            }
        }
    }

    /** A closed walk of points, each edge marked as a slow stroke or a fast jump. */
    private class Figure {
        private var xs = FloatArray(512)
        private var ys = FloatArray(512)
        private var fast = BooleanArray(512)
        /** Cumulative traversal time to each point, 0 to 1. */
        private var at = FloatArray(512)
        private var n = 0
        private var cursor = 0

        var x = 0f
            private set
        var y = 0f
            private set

        fun clear() {
            n = 0
            cursor = 0
        }

        fun add(px: Float, py: Float, fast: Boolean) {
            if (n == xs.size) {
                xs = xs.copyOf(n * 2)
                ys = ys.copyOf(n * 2)
                this.fast = this.fast.copyOf(n * 2)
                at = at.copyOf(n * 2)
            }
            xs[n] = px
            ys[n] = py
            this.fast[n] = fast
            n++
        }

        /** Joins the end back to the start, fast, and spreads the figure's time over its edges. */
        fun close() {
            if (n == 0) add(0f, 0f, false)
            add(xs[0], ys[0], fast = true)
            at[0] = 0f
            var total = 0.0
            for (i in 1 until n) {
                val dx = (xs[i] - xs[i - 1]).toDouble()
                val dy = (ys[i] - ys[i - 1]).toDouble()
                val len = sqrt(dx * dx + dy * dy)
                total += max(1e-4, len) * (if (fast[i]) JUMP_SHARE else 1.0)
                at[i] = total.toFloat()
            }
            for (i in 1 until n) at[i] = (at[i] / total).toFloat()
        }

        /** Moves (x, y) to where the walk is at [u] of the way round, 0 to 1. */
        fun at(u: Double) {
            val target = u.toFloat()
            while (cursor < n - 2 && at[cursor + 1] < target) cursor++
            val a = at[cursor]
            val b = at[min(cursor + 1, n - 1)]
            val f = if (b > a) ((target - a) / (b - a)).coerceIn(0f, 1f) else 0f
            val j = min(cursor + 1, n - 1)
            x = xs[cursor] + (xs[j] - xs[cursor]) * f
            y = ys[cursor] + (ys[j] - ys[cursor]) * f
        }
    }

    companion object {
        const val SCENE_S = 6.0
        private const val CUBE_HZ = 90.0
        private const val LISSAJOUS_HZ = 60.0
        private const val WORD_HZ = 45.0
        /** A jump costs this share of the time the same length of stroke does. */
        private const val JUMP_SHARE = 0.08

        private val CUBE_WALK = intArrayOf(0, 1, 3, 2, 0, 4, 5, 7, 6, 4, 5, 1, 3, 7, 6, 2, 0)
        /** Indices into [CUBE_WALK] whose incoming edge was already drawn. */
        private val CUBE_RETRACE = setOf(10, 12, 14, 16)

        /** Stroke letters on a unit box, y up; each stroke is x0,y0,x1,y1,… */
        private val GLYPHS: Map<Char, List<DoubleArray>> = mapOf(
            'S' to listOf(doubleArrayOf(1.0, 1.0, 0.0, 1.0, 0.0, 0.5, 1.0, 0.5, 1.0, 0.0, 0.0, 0.0)),
            'P' to listOf(doubleArrayOf(0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0, 0.5, 0.0, 0.5)),
            'E' to listOf(doubleArrayOf(1.0, 1.0, 0.0, 1.0, 0.0, 0.0, 1.0, 0.0), doubleArrayOf(0.0, 0.5, 0.75, 0.5)),
            'C' to listOf(doubleArrayOf(1.0, 1.0, 0.0, 1.0, 0.0, 0.0, 1.0, 0.0)),
            'T' to listOf(doubleArrayOf(0.0, 1.0, 1.0, 1.0), doubleArrayOf(0.5, 1.0, 0.5, 0.0)),
            'R' to listOf(doubleArrayOf(0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0, 0.5, 0.0, 0.5, 1.0, 0.0)),
            'A' to listOf(doubleArrayOf(0.0, 0.0, 0.5, 1.0, 1.0, 0.0), doubleArrayOf(0.25, 0.5, 0.75, 0.5)),
        )
    }
}

/** [OscDemo] as a capture source, paced to real time like the 2A03 demo. */
class OscDemoCapture(override val sampleRate: Int, stereo: Boolean) : AudioCapture {

    override val channelCount: Int = if (stereo) 2 else 1
    override val describe: String = "Built-in oscilloscope demo · $sampleRate Hz"

    private val demo = OscDemo(sampleRate)
    private var left = FloatArray(4096)
    private var right = FloatArray(4096)
    private var sampleIndex = 0L
    private var startedAt = 0L

    override fun start() {
        startedAt = System.nanoTime()
        sampleIndex = 0
    }

    override fun read(out: FloatArray): Int {
        val frames = out.size / channelCount
        val dueAt = startedAt + (sampleIndex + frames) * 1_000_000_000L / sampleRate
        val waitNs = dueAt - System.nanoTime()
        if (waitNs > 0) {
            try {
                Thread.sleep(waitNs / 1_000_000L, (waitNs % 1_000_000L).toInt())
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return -1
            }
        }
        if (left.size < frames) {
            left = FloatArray(frames)
            right = FloatArray(frames)
        }
        demo.render(left, right, frames)
        var w = 0
        for (i in 0 until frames) {
            out[w++] = left[i] * LEVEL
            if (channelCount == 2) out[w++] = right[i] * LEVEL
        }
        sampleIndex += frames
        return w
    }

    override fun stop() = Unit
    override fun release() = Unit

    companion object {
        const val NAME = "Built-in oscilloscope demo"
        private const val LEVEL = 0.95f
    }
}
