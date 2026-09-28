package com.n3d.spectra.stems

import ai.onnxruntime.OrtEnvironment
import com.n3d.spectra.dsp.Biquad
import com.n3d.spectra.dsp.HistoryRing
import com.n3d.spectra.dsp.StereoFeed
import java.io.File
import java.util.concurrent.locks.LockSupport

/** The four outputs, in the model's own order. */
enum class Stem(val label: String) {
    DRUMS("Drums"),
    BASS("Bass"),
    VOCALS("Vocals"),
    OTHER("Other"),
}

/**
 * Live source separation: a stereo mix in, four stems out, 128 samples at a time.
 *
 * The model is the streaming HS-TasNet release shipped with StemgenRT (MIT,
 * github.com/sweetspotsoundsystem/stemgen-rt): a recurrent network that looks at
 * a 1024-sample window, emits 128 new samples per call and carries eight state
 * tensors from one call to the next. Its output lags its input by one call —
 * 2.9 ms — which is why a live display is possible at all. The better-known
 * separators (Demucs and friends) need seconds of audio on both sides of the
 * moment they separate, and could only ever show the past.
 *
 * It is not a clean separation. The release scores about 4.5 dB SDR: every stem
 * carries some of the others. That is fine for what this feeds, because the
 * held-still scopes fold whole periods of the note on top of each other, and
 * bleed that is not locked to the note averages away.
 *
 * Threading: [push] is called by the analysis thread and never blocks. A single
 * worker thread owns the [StemSession] and does nothing else. If the phone cannot
 * keep up, the worker does not queue audio forever — it skips to the present,
 * keeping the model's memory, and counts the skip, so the picture is late by at
 * most [MAX_BACKLOG_FRAMES] rather than by more every second.
 *
 * No Android types, deliberately: the unit tests run this exact class on the
 * desktop JVM against the same model file.
 */
class StemSeparator(
    private val modelFile: File,
    private val threads: Int = 1,
    /** Priority and performance hints — whatever the platform can offer the worker. */
    private val hooks: StemWorkerHooks = StemWorkerHooks.NONE,
) {

    sealed class Status {
        data object Idle : Status()
        data object Loading : Status()
        /**
         * @param load   compute time ÷ audio time, smoothed. Above 1 the phone
         *               is slower than the music.
         * @param skips  times the worker fell behind and jumped to the present.
         */
        data class Running(val load: Float, val skips: Int, val msPerHop: Float) : Status()
        data class Failed(val message: String) : Status()
    }

    @Volatile var status: Status = Status.Idle
        private set

    /** The mix, at [SAMPLE_RATE], from the analysis thread. */
    val input = StereoFeed(FEED_FRAMES)

    /** Each stem as mono, at [SAMPLE_RATE], indexed like [input]. */
    val stems: Array<HistoryRing> = Array(Stem.entries.size) { HistoryRing(HISTORY) }

    /** The same stems low-passed for pitch detection, sample-aligned with [stems]. */
    val pitched: Array<HistoryRing> = Array(Stem.entries.size) { HistoryRing(HISTORY) }

    /**
     * Absolute stem index from which the output can be trusted: past the
     * model's warm-up when it starts, then past the seam of its latest skip.
     * Before it is either the model learning the song from silence or audio
     * from both sides of a jump, and a scope that measures a period across that
     * seam gets it wrong.
     */
    @Volatile var cleanFrom: Long = WARMUP_FRAMES.toLong()
        private set

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        status = Status.Loading
        thread = Thread({ runWorker() }, "spectra-stems").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        thread?.let {
            LockSupport.unpark(it)
            runCatching { it.join(2_000) }
        }
        thread = null
        if (status !is Status.Failed) status = Status.Idle
    }

    val isRunning: Boolean get() = running

    /** Queues audio for separation. Never blocks. Both arrays at [SAMPLE_RATE]. */
    fun push(left: FloatArray, right: FloatArray, frames: Int) {
        if (!running || frames <= 0) return
        input.write(left, right, frames)
        thread?.let { LockSupport.unpark(it) }
    }

    // ------------------------------------------------------------------------

    private fun runWorker() {
        runCatching { hooks.onStart() }
        try {
            runSession()
        } finally {
            runCatching { hooks.onStop() }
        }
    }

    private fun runSession() {
        val env = try {
            OrtEnvironment.getEnvironment()
        } catch (t: Throwable) {
            fail("The separation runtime could not start on this device (${t.javaClass.simpleName}).")
            return
        }
        val session = try {
            StemSession.open(env, modelFile, threads)
        } catch (t: Throwable) {
            fail("The stem model could not be loaded: ${t.message ?: t.javaClass.simpleName}")
            return
        }

        try {
            loop(session)
        } catch (t: Throwable) {
            fail("Stem separation stopped: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            session.close()
            running = false
        }
    }

    private fun loop(session: StemSession) {
        val hopPlanar = FloatArray(HOP * 2)
        val separated = FloatArray(StemSession.OUT_SIZE)
        val mono = FloatArray(HOP)
        val low = FloatArray(HOP)
        val filters = Array(Stem.entries.size) { i -> pitchFilter(Stem.entries[i]) }

        // The first call carries nothing but the zero history.
        var skipNext = true
        var readPos = input.written
        var skips = 0
        // Ready from here on: audio pushed after this point is separated. The
        // load figures fill in once there is audio to measure them on.
        status = Status.Running(0f, 0, 0f)

        var busyNs = 0.0
        var hops = 0L
        var load = 0f
        var msPerHop = 0f
        var lastReport = System.nanoTime()

        while (running) {
            var backlog = input.written - readPos
            if (backlog > MAX_BACKLOG_FRAMES) {
                // Behind by more than we are willing to show late: jump to the
                // present, and keep the model's memory across the jump. What is
                // on the far side is nearly always the song it was just hearing.
                // Measured against a run that never skipped, it is back within
                // 10–20 ms; starting over from silence, which this used to do,
                // left loud garbage in drums and other for a third of a second,
                // and a skip every 2.5 s cost 10–50× the error over a whole run.
                readPos = input.written - HOP
                skips++
                cleanFrom = stems[0].written + SPLICE_GUARD_FRAMES
                status = Status.Running(load, skips, msPerHop)
                backlog = input.written - readPos
            }
            if (backlog < HOP) {
                LockSupport.parkNanos(PARK_NS)
                continue
            }
            if (!input.readPlanar(readPos, HOP, hopPlanar)) {
                readPos = input.written - HOP
                continue
            }
            readPos += HOP

            val t0 = System.nanoTime()
            session.separate(hopPlanar, separated)
            val work = System.nanoTime() - t0
            busyNs += work.toDouble()
            hops++
            hooks.onWork(work, HOP_NANOS)

            if (skipNext) {
                skipNext = false
            } else {
                for (s in Stem.entries.indices) {
                    val base = s * 2 * HOP
                    for (i in 0 until HOP) mono[i] = 0.5f * (separated[base + i] + separated[base + HOP + i])
                    stems[s].write(mono, 0, HOP)
                    val chain = filters[s]
                    for (i in 0 until HOP) {
                        var v = mono[i].toDouble()
                        for (f in chain) v = f.process(v)
                        low[i] = v.toFloat()
                    }
                    pitched[s].write(low, 0, HOP)
                }
            }

            val now = System.nanoTime()
            if (now - lastReport >= REPORT_NS && hops > 0) {
                val audioNs = hops * HOP * 1e9 / SAMPLE_RATE
                val instant = (busyNs / audioNs).toFloat()
                load = if (load == 0f) instant else load * 0.6f + instant * 0.4f
                msPerHop = (busyNs / hops / 1e6).toFloat()
                status = Status.Running(load, skips, msPerHop)
                busyNs = 0.0
                hops = 0
                lastReport = now
            }
        }
    }

    private fun fail(message: String) {
        status = Status.Failed(message)
        running = false
    }

    companion object {
        /** The model is trained at, and only valid at, exactly this rate. */
        const val SAMPLE_RATE = 44_100
        const val HOP = 128

        /** File name of the model inside the APK's assets and on disk. */
        const val ASSET = "stems/stemgen-rt-hop128.onnx"
        /** SHA-256 of that file. The build refuses any other bytes. */
        const val SHA256 = "08424ca91feae8d4746442a35ebf70489dea70ea6e81401b39483cf02d497748"
        const val BYTES = 37_532_574L

        /** Stem history: 1.5 s, several times what the deepest scope reads. */
        const val HISTORY = 65_536
        private const val FEED_FRAMES = 131_072
        /**
         * How late the picture may fall before the worker skips to the present.
         * Short on purpose: the model keeps its memory across a skip and is back
         * on track within 10–20 ms, so a phone that is slightly too slow does
         * better skipping now and then than showing everything late.
         */
        const val MAX_BACKLOG_FRAMES = 6_615 // 150 ms
        /** Output after the model starts from silence not yet worth measuring: 100 ms. */
        const val WARMUP_FRAMES = 4_410
        /**
         * Output after a skip not yet worth measuring: one analysis window of the
         * model, 23 ms. Until then what it is looking at still straddles the jump.
         */
        const val SPLICE_GUARD_FRAMES = 1_024
        private const val PARK_NS = 1_500_000L
        private const val HOP_NANOS = HOP * 1_000_000_000L / SAMPLE_RATE
        private const val REPORT_NS = 500_000_000L

        /**
         * The low-pass in front of each stem's pitch detector: just above the
         * top of the range that lane hunts in, so a note's own harmonics cannot
         * out-vote its fundamental.
         */
        fun pitchCornerHz(stem: Stem): Double = when (stem) {
            Stem.BASS -> 360.0
            Stem.DRUMS -> 2000.0
            Stem.VOCALS, Stem.OTHER -> 1200.0
        }

        private fun pitchFilter(stem: Stem): Array<Biquad> =
            Biquad.butterworthQs(4).map { Biquad.lowPass(SAMPLE_RATE, pitchCornerHz(stem), it) }.toTypedArray()
    }
}
