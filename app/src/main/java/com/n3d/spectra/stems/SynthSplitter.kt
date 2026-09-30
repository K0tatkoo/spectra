package com.n3d.spectra.stems

import com.n3d.spectra.dsp.Biquad
import com.n3d.spectra.dsp.HistoryRing
import java.util.concurrent.locks.LockSupport

/**
 * Splits synths out of the stems the model already made, live.
 *
 * The stem model has no synth stem: trained on four, it puts a synth wherever
 * it sounds most at home. Measured on synthetic ones, a supersaw lead lands 99 %
 * in vocals and a pad 86 % in other, so the splitter reads both of those (the
 * model file says which, [SplitLayout.inputs]) and hands back three lanes:
 * synth, and each input with its synth taken out.
 *
 * It trails the stem worker on its own thread, a frame of [SplitLayout.hop]
 * samples at a time, and keeps its output rings on the stems' absolute index,
 * so sample i of the synth ring is the synth of sample i of the mix. If it
 * falls behind it jumps to the present, keeping the network's memory, and
 * writes silence over what it skipped so the index stays true.
 *
 * Its own thread rather than the stem worker's: the stem model already fills
 * the phone's fastest core, and the splitter must never be the reason the
 * other stems start skipping.
 *
 * No Android types: the tests run it on the desktop JVM with a stand-in network.
 */
class SynthSplitter(
    /** Loads the model. Called once, on the splitter's own thread; throws if it cannot. */
    private val open: () -> SplitModel,
    /** The stem model's mono output ring for each stem. */
    private val source: (Stem) -> HistoryRing,
    private val hooks: StemWorkerHooks = StemWorkerHooks.NONE,
) {

    sealed class Status {
        data object Idle : Status()
        data object Loading : Status()
        /** [load]: compute time ÷ audio time, smoothed. [skips]: jumps to the present. */
        data class Running(val load: Float, val skips: Int) : Status()
        data class Failed(val message: String) : Status()
    }

    /**
     * What the splitter writes, once its model is loaded: the synth and each
     * input with the synth taken out, as mono rings on the stems' index, and the
     * same low-passed for the pitch detectors — written together, like the
     * stem worker's.
     */
    class Outputs(val layout: SplitLayout, val raw: Map<Stem, HistoryRing>, val pitched: Map<Stem, HistoryRing>) {
        /** The lanes this splitter feeds: synth, then its inputs. */
        val stems: List<Stem> get() = listOf(Stem.SYNTH) + layout.inputs
    }

    @Volatile var status: Status = Status.Idle
        private set

    @Volatile var outputs: Outputs? = null
        private set

    /**
     * Absolute index from which the output can be trusted: past the network's
     * warm-up after it starts, then past the seam of its latest jump. The
     * stems' own seams come on top; [ScopeRunner] adds those.
     */
    @Volatile var cleanFrom: Long = Long.MAX_VALUE
        private set

    @Volatile private var running = false
    @Volatile private var waitingFor = Long.MAX_VALUE
    private var thread: Thread? = null

    val isRunning: Boolean get() = running

    fun start() {
        if (running) return
        running = true
        status = Status.Loading
        thread = Thread({ runWorker() }, "spectra-synth").apply {
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

    /**
     * Called by the stem worker after each hop it writes, with its rings'
     * new length. Wakes the splitter only once a whole frame is there.
     */
    fun wake(written: Long) {
        if (written >= waitingFor) thread?.let { LockSupport.unpark(it) }
    }

    // ------------------------------------------------------------------------

    private fun runWorker() {
        runCatching { hooks.onStart() }
        try {
            val model = try {
                open()
            } catch (t: Throwable) {
                fail("The synth model could not be loaded: ${t.message ?: t.javaClass.simpleName}")
                return
            }
            try {
                loop(model)
            } catch (t: Throwable) {
                fail("Synth splitting stopped: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                model.close()
                running = false
            }
        } finally {
            runCatching { hooks.onStop() }
        }
    }

    private fun loop(model: SplitModel) {
        val layout = model.layout
        require(layout.sampleRate == StemSeparator.SAMPLE_RATE) {
            "the synth model is for ${layout.sampleRate} Hz audio, the stems are ${StemSeparator.SAMPLE_RATE} Hz"
        }
        val split = SpectralSplit(layout, model.net)
        val hop = layout.hop
        val inputs = layout.inputs
        val rings = inputs.map(source)
        val frames = Array(inputs.size) { FloatArray(layout.nFft) }
        val synth = FloatArray(hop)
        val rest = Array(inputs.size) { FloatArray(hop) }
        val low = FloatArray(hop)

        val lanes = listOf(Stem.SYNTH) + inputs
        val raw = lanes.associateWith { HistoryRing(StemSeparator.HISTORY) }
        val pitched = lanes.associateWith { HistoryRing(StemSeparator.HISTORY) }
        val filters = lanes.associateWith { stem ->
            Biquad.butterworthQs(4).map { Biquad.lowPass(StemSeparator.SAMPLE_RATE, StemSeparator.pitchCornerHz(stem), it) }
        }
        fun emit(stem: Stem, samples: FloatArray) {
            raw.getValue(stem).write(samples, 0, hop)
            val chain = filters.getValue(stem)
            for (i in 0 until hop) {
                var v = samples[i].toDouble()
                for (f in chain) v = f.process(v)
                low[i] = v.toFloat()
            }
            pitched.getValue(stem).write(low, 0, hop)
        }

        // The frame that ends at `next` completes the hop two hops before it,
        // so the rings always stand 2 × hop behind `next` when a frame starts.
        var next = 0L
        fun jumpTo(available: Long, guard: Long) {
            next = maxOf(2L * hop, available / hop * hop)
            split.resetOverlap()
            for (r in raw.values) r.advanceTo(next - 2 * hop)
            for (r in pitched.values) r.advanceTo(next - 2 * hop)
            cleanFrom = next + guard
        }

        jumpTo(rings.minOf { it.written }, WARMUP_FRAMES.toLong())
        outputs = Outputs(layout, raw, pitched)
        var skips = 0
        status = Status.Running(0f, 0)

        val frameNanos = hop * 1_000_000_000L / StemSeparator.SAMPLE_RATE
        var busyNs = 0.0
        var done = 0
        var load = 0f
        var lastReport = System.nanoTime()

        while (running) {
            val available = rings.minOf { it.written }
            if (available - next > MAX_BACKLOG_FRAMES) {
                // Behind by more than is worth showing late: jump, keep the
                // network's memory — what follows is the song it was hearing.
                jumpTo(available, hop.toLong())
                skips++
                status = Status.Running(load, skips)
                continue
            }
            if (available < next) {
                waitingFor = next
                // Re-check after publishing what we wait for: the stem worker
                // may have written the frame in between, and then no wake comes.
                if (rings.minOf { it.written } < next) LockSupport.parkNanos(PARK_NS)
                waitingFor = Long.MAX_VALUE
                continue
            }
            var ok = true
            for (i in inputs.indices) ok = ok && rings[i].read(next, frames[i], layout.nFft)
            if (!ok) {
                jumpTo(rings.minOf { it.written }, hop.toLong())
                skips++
                continue
            }

            val t0 = System.nanoTime()
            split.frame(frames, synth, rest)
            val work = System.nanoTime() - t0
            emit(Stem.SYNTH, synth)
            for (i in inputs.indices) emit(inputs[i], rest[i])
            next += hop
            busyNs += work.toDouble()
            done++
            hooks.onWork(work, frameNanos)

            val now = System.nanoTime()
            if (now - lastReport >= REPORT_NS && done > 0) {
                val instant = (busyNs / (done.toDouble() * frameNanos)).toFloat()
                load = if (load == 0f) instant else load * 0.6f + instant * 0.4f
                status = Status.Running(load, skips)
                busyNs = 0.0
                done = 0
                lastReport = now
            }
        }
    }

    private fun fail(message: String) {
        status = Status.Failed(message)
        running = false
    }

    companion object {
        /** How late the splitter may fall before it jumps to the present: 150 ms, like the stem worker. */
        const val MAX_BACKLOG_FRAMES = StemSeparator.MAX_BACKLOG_FRAMES
        /** Output after the network starts from silence not yet worth measuring: 250 ms. */
        const val WARMUP_FRAMES = 11_025
        /** A fallback only: the stem worker wakes the splitter when a frame is ready. */
        private const val PARK_NS = 20_000_000L
        private const val REPORT_NS = 500_000_000L
    }
}
