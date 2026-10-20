package com.n3d.spectra.desktop.dev

import com.n3d.spectra.desktop.audio.OscDemo
import com.n3d.spectra.dsp.BeamClock
import com.n3d.spectra.dsp.BeamInterpolator
import com.n3d.spectra.dsp.BeamSweep
import com.n3d.spectra.dsp.Biquad
import com.n3d.spectra.dsp.LaneTrigger
import com.n3d.spectra.dsp.PitchDetector
import com.n3d.spectra.dsp.ScopeLaneSpec
import com.n3d.spectra.dsp.ScopeLock
import com.n3d.spectra.dsp.ScopeTrace
import com.n3d.spectra.dsp.StereoFeed
import com.n3d.spectra.dsp.nes.Nes2A03
import com.n3d.spectra.dsp.nes.NesOptions
import com.n3d.spectra.dsp.nes.NesReading
import com.n3d.spectra.dsp.nes.PitchPreFilter
import com.n3d.spectra.dsp.nes.TriangleTracker
import com.n3d.spectra.paint.Crt
import com.n3d.spectra.paint.CrtLayout
import com.n3d.spectra.settings.OscMode
import com.n3d.spectra.settings.Phosphor
import com.n3d.spectra.settings.Settings
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

/**
 * Fixtures for the held-still scopes, the 2A03 tracker, the beam and the CRT.
 * Checked by ios/SpectraCore/Tests/SpectraCoreTests/ScopesTests.swift (and the
 * Tracker/Beam/Crt test files beside it).
 *
 * Every class here keeps state from one call to the next, so each fixture is a
 * *run*: a stream of input, the calls made on it in order, and what every call
 * returned. A window handed to a class is `stream[end − n, end)`, with anything
 * outside the stored stream reading as zero — the same thing a `HistoryRing`
 * returns before its first sample — so long silences cost nothing to store.
 */
object IosScopesFixtures {

    private const val SR = 48_000

    fun export(sink: (IosFixture) -> Unit) {
        sink(pitchDetector())
        sink(pitchPreFilter())
        sink(triangleTracker())
        sink(scopeLock())
        sink(beam())
        sink(crt())
    }

    // ---- signals ----------------------------------------------------------

    /** The 2A03's 32-step staircase, ideal: no capture chain. */
    private fun staircase(n: Int, rate: Int, hz: Double, amp: Double = 1.0) = FloatArray(n) {
        var u = (it.toDouble() / rate * hz) % 1.0
        if (u < 0) u += 1.0
        (amp * Nes2A03.WAVE[(u * 32).toInt().coerceIn(0, 31)]).toFloat()
    }

    private fun saw(n: Int, rate: Int, hz: Double, amp: Double) = FloatArray(n) {
        val t = it.toDouble() / rate
        (amp * 2.0 * (t * hz - floor(0.5 + t * hz))).toFloat()
    }

    /**
     * A 2A03 triangle rendered the way the chip renders it (TrackerCheck's
     * renderer): zero-order hold at CPU resolution, oversampled 8×, low-passed,
     * decimated. The aliasing is part of what a capture contains.
     */
    private fun chipTriangle(timer: Int, region: Nes2A03.Region, samples: Int): FloatArray {
        val hi = FloatArray(samples * 8)
        val cyclesPerHi = region.cpuHz / (SR.toDouble() * 8)
        for (i in hi.indices) {
            val step = ((i * cyclesPerHi / (timer + 1.0)).toLong() % 32L + 32L) % 32L
            hi[i] = Nes2A03.WAVE[step.toInt()]
        }
        return decimate(hi, samples)
    }

    private fun chipPulse(hz: Double, duty: Float, amp: Float, samples: Int): FloatArray {
        val hi = FloatArray(samples * 8)
        val inc = hz / (SR.toDouble() * 8)
        var phase = 0.0
        for (i in hi.indices) {
            hi[i] = if (phase < duty) amp else -amp
            phase += inc
            if (phase >= 1.0) phase -= 1.0
        }
        return decimate(hi, samples)
    }

    private fun decimate(hi: FloatArray, samples: Int): FloatArray {
        val stages = Biquad.butterworthQs(4).map { Biquad.lowPass(SR * 8, 18000.0, it) }
        val out = FloatArray(samples)
        for (i in hi.indices) {
            var v = hi[i].toDouble()
            for (s in stages) v = s.process(v)
            if (i % 8 == 0 && i / 8 < samples) out[i / 8] = v.toFloat()
        }
        return out
    }

    /** `stream[end − n, end)`, zero outside the stream. */
    private fun window(stream: FloatArray, end: Int, n: Int) = FloatArray(n) {
        val i = end - n + it
        if (i >= 0 && i < stream.size) stream[i] else 0f
    }

    private fun floats(parts: List<FloatArray>) = parts.flatMap { it.asIterable() }.toFloatArray()

    // ---- PitchDetector ----------------------------------------------------

    private fun pitchDetector(): IosFixture {
        class Case(val name: String, val rate: Int, val window: Int, val minHz: Float, val maxHz: Float, val x: FloatArray)
        val n = 8192
        val timer55 = Nes2A03.timerFor(Nes2A03.Region.NTSC, 55.0)
        val cases = listOf(
            Case("sine220", SR, 4096, 50f, 1000f, IosSignals.mix(IosSignals.sine(n, SR, 220.0, 0.6), IosSignals.noise(n, 0.01))),
            Case("saw110", SR, 4096, 28f, 1000f, saw(n, SR, 110.0, 0.4)),
            Case("chip55", SR, 8192, 25f, 200f, chipTriangle(timer55, Nes2A03.Region.NTSC, n)),
            Case("chip55narrow", SR, 4096, 28f, 300f, chipTriangle(timer55, Nes2A03.Region.NTSC, n)),
            Case("stair82at44k", 44_100, 4096, 28f, 300f, staircase(n, 44_100, 82.41, 0.3)),
            Case("noise", SR, 4096, 28f, 1000f, IosSignals.noise(n, 0.3, seed = 11L)),
            Case("silence", SR, 4096, 28f, 1000f, FloatArray(n)),
            Case("short", SR, 4096, 28f, 1000f, FloatArray(1000) { 0.5f }),
        )
        val f = IosFixture("pitchDetector").meta("names", cases.map { it.name })
            .meta("rates", cases.map { it.rate }).meta("windows", cases.map { it.window })
            .meta("minHz", cases.map { it.minHz }).meta("maxHz", cases.map { it.maxHz })
        val tau = DoubleArray(cases.size)
        val clarity = FloatArray(cases.size)
        for ((i, c) in cases.withIndex()) {
            val d = PitchDetector(c.rate, c.window)
            tau[i] = d.detect(c.x, c.minHz, c.maxHz)
            clarity[i] = d.clarity
            f.f32("${c.name}.x", c.x)
        }
        return f.f64("tau", tau).f32("clarity", clarity)
    }

    // ---- PitchPreFilter ---------------------------------------------------

    private fun pitchPreFilter(): IosFixture {
        val n = 6000
        val input = IosSignals.mix(
            IosSignals.noise(n, 0.3, seed = 5L),
            IosSignals.sine(n, SR, 73.0, 0.5),
            IosSignals.sine(n, SR, 523.0, 0.4, 1.0),
        )
        // In awkward chunks through the array entry point.
        val chunks = listOf(1000, 3000, 2000)
        val a = PitchPreFilter(SR, PitchPreFilter.cornerFor(200f))
        val chunked = FloatArray(n)
        var at = 0
        for (c in chunks) {
            val src = input.copyOfRange(at, at + c)
            val dst = FloatArray(c)
            a.process(src, dst, c)
            System.arraycopy(dst, 0, chunked, at, c)
            at += c
        }
        // Sample by sample at 44.1 kHz, reset halfway.
        val b = PitchPreFilter(44_100, PitchPreFilter.cornerFor(300f))
        val reset = FloatArray(n) { i ->
            if (i == n / 2) b.reset()
            b.process(input[i])
        }
        val huntMax = floatArrayOf(10f, 25f, 200f, 333.3f, 5000f)
        return IosFixture("pitchPreFilter").meta("chunks", chunks).f32("input", input)
            .f32("chunked", chunked).f32("reset", reset)
            .f32("huntMax", huntMax).f64("cornerFor", DoubleArray(huntMax.size) { PitchPreFilter.cornerFor(huntMax[it]) })
    }

    // ---- TriangleTracker --------------------------------------------------

    private class TrackerRun(val name: String, val o: NesOptions, val raw: FloatArray, val ends: IntArray, val dts: FloatArray)

    private fun triangleTracker(): IosFixture {
        val n = TriangleTracker.HISTORY
        val ntsc = Nes2A03.Region.NTSC
        val runs = ArrayList<TrackerRun>()

        // A1 on NTSC through the chip's own capture chain, default options:
        // persistence on, so the smoothing across frames is in the result too.
        run {
            val timer = Nes2A03.timerFor(ntsc, 55.0)
            val frames = 10
            val raw = chipTriangle(timer, ntsc, n + frames * 733)
            runs += TrackerRun("a1", NesOptions(), raw, IntArray(frames) { n + it * 733 }, FloatArray(frames) { 733f / 48f })
        }
        // PAL, three cycles, no persistence.
        run {
            val pal = Nes2A03.Region.PAL
            val frames = 6
            val raw = chipTriangle(1016, pal, n + frames * 500)
            runs += TrackerRun(
                "pal", NesOptions(region = pal, persistence = 0f, cycles = 3), raw,
                IntArray(frames) { n + it * 500 }, FloatArray(frames) { 500f / 48f },
            )
        }
        // A bass under a two-pulse melody and noise, folded deep.
        run {
            val timer = Nes2A03.timerFor(ntsc, 73.4)
            val frames = 8
            val len = n + frames * 733
            val tri = chipTriangle(timer, ntsc, len)
            val p1 = chipPulse(415.30, 0.5f, 0.45f, len)
            val p2 = chipPulse(554.37, 0.25f, 0.35f, len)
            val noise = IosSignals.noise(len, 0.025, seed = 7L)
            val raw = FloatArray(len) { (tri[it] * 0.55f + p1[it] + p2[it] + noise[it]) * 0.45f }
            runs += TrackerRun(
                "melody", NesOptions(foldPeriods = 16, persistence = 0.3f), raw,
                IntArray(frames) { n + it * 733 }, FloatArray(frames) { 733f / 48f },
            )
        }
        // Unlocked from the timer grid and not averaged (the live trace), then
        // the note stops: held twice, then let go. The silence is past the end
        // of the stored stream, so it reads as zeros.
        run {
            val timer = Nes2A03.timerFor(ntsc, 110.0)
            val tone = 6
            val raw = chipTriangle(timer, ntsc, n + tone * 733)
            val silent = raw.size + n + 4096
            runs += TrackerRun(
                "release", NesOptions(lockToTimer = false, averaging = false, cycles = 2, huntMaxHz = 300f), raw,
                IntArray(tone + 3) { if (it < tone) n + it * 733 else silent + (it - tone) * 733 },
                FloatArray(tone + 3) { if (it < tone) 733f / 48f else floatArrayOf(300f, 300f, 400f)[it - tone] },
            )
        }
        // A plain sine: periodic, so it locks, but it has no staircase.
        run {
            val frames = 3
            val raw = IosSignals.sine(n + frames * 600, SR, 55.0, 0.7)
            runs += TrackerRun("sine", NesOptions(persistence = 0f), raw, IntArray(frames) { n + it * 600 }, FloatArray(frames) { 12.5f })
        }

        val f = IosFixture("triangleTracker").meta("names", runs.map { it.name }).meta("history", n)
        for (r in runs) {
            val o = r.o
            f.meta("${r.name}.region", o.region).meta("${r.name}.lockToTimer", o.lockToTimer)
                .meta("${r.name}.cycles", o.cycles).meta("${r.name}.huntMinHz", o.huntMinHz)
                .meta("${r.name}.huntMaxHz", o.huntMaxHz).meta("${r.name}.clarityThreshold", o.clarityThreshold)
                .meta("${r.name}.averaging", o.averaging).meta("${r.name}.foldPeriods", o.foldPeriods)
                .meta("${r.name}.persistence", o.persistence).meta("${r.name}.holdMs", o.holdMs)
            // The pitched copy is a stream, filtered once, as the engine does it.
            val pre = PitchPreFilter(SR, PitchPreFilter.cornerFor(o.huntMaxHz))
            val pitched = FloatArray(r.raw.size) { pre.process(r.raw[it]) }
            f.f32("${r.name}.raw", r.raw).f32("${r.name}.pitched", pitched)
                .i32("${r.name}.ends", r.ends).f32("${r.name}.dts", r.dts)

            val tracker = TriangleTracker(SR)
            val readings = r.ends.indices.map { k ->
                tracker.analyze(window(r.raw, r.ends[k], n), window(pitched, r.ends[k], n), r.dts[k], o)
            }
            fun ints(get: (NesReading) -> Int) = IntArray(readings.size) { readings[it]?.let(get) ?: 0 }
            fun bools(get: (NesReading) -> Boolean) = ints { if (get(it)) 1 else 0 }
            fun f32s(get: (NesReading) -> Float) = FloatArray(readings.size) { readings[it]?.let(get) ?: 0f }
            fun f64s(get: (NesReading) -> Double) = DoubleArray(readings.size) { readings[it]?.let(get) ?: 0.0 }
            f.i32("${r.name}.present", IntArray(readings.size) { if (readings[it] != null) 1 else 0 })
                .i32("${r.name}.locked", bools { it.locked }).i32("${r.name}.stale", bools { it.stale })
                .f64("${r.name}.hz", f64s { it.hz }).i32("${r.name}.timer", ints { it.timer })
                .f64("${r.name}.nesHz", f64s { it.nesHz }).f64("${r.name}.cents", f64s { it.cents })
                .f64("${r.name}.gridCents", f64s { it.gridCents }).f64("${r.name}.samplesPerStep", f64s { it.samplesPerStep })
                .f64("${r.name}.periodSamples", f64s { it.periodSamples }).f64("${r.name}.gridStepCents", f64s { it.gridStepCents })
                .i32("${r.name}.foldedPeriods", ints { it.foldedPeriods })
                .f32("${r.name}.clarity", f32s { it.clarity }).f32("${r.name}.match", f32s { it.match })
                .f32("${r.name}.stepMatch", f32s { it.stepMatch }).f32("${r.name}.levelDb", f32s { it.levelDb })
                .f32("${r.name}.livePhase0", f32s { it.livePhase0 }).i32("${r.name}.cycles", ints { it.cycles })
                .i32("${r.name}.audible", bools { it.audible })
                .i32("${r.name}.cycleCount", ints { it.cycle.size }).i32("${r.name}.liveCount", ints { it.live.size })
                .f32("${r.name}.cycle", floats(readings.map { it?.cycle ?: FloatArray(0) }))
                .f32("${r.name}.live", floats(readings.map { it?.live ?: FloatArray(0) }))
            f.meta("${r.name}.notes", readings.map { it?.note ?: "" })
        }
        return f
    }

    // ---- ScopeLock --------------------------------------------------------

    private fun scopeLock(): IosFixture {
        val rate = 44_100
        val block = 1882
        val lane = 16_384
        val free = 8_192
        val f = IosFixture("scopeLock").meta("rate", rate).meta("block", block).meta("lane", lane).meta("free", free)
        // The pitched copy the app gives a "hold still" lane: 4th-order Butterworth at 1200 Hz.
        fun lowPassed(x: FloatArray): FloatArray {
            val st = Biquad.butterworthQs(4).map { Biquad.lowPass(rate, 1200.0, it) }
            return FloatArray(x.size) { i -> var v = x[i].toDouble(); for (s in st) v = s.process(v); v.toFloat() }
        }

        // A bass lane: A1, then E2 (a note change), then the stream ends and the
        // lane goes quiet. Partway through, the stem separator "skips": the
        // valid-from index jumps to just behind the newest sample.
        val bassLen = (1.0 * rate).toInt()
        val noise = IosSignals.noise(bassLen, 0.01, seed = 21L)
        val a1 = staircase(bassLen, rate, 55.0, 0.3)
        val e2 = staircase(bassLen, rate, 82.41, 0.3)
        val bass = FloatArray(bassLen) { (if (it < (0.6 * rate).toInt()) a1[it] else e2[it]) + noise[it] }
        val bassFrames = (1.25 * rate / block).toInt()
        val bassValid = LongArray(bassFrames) { k ->
            if (k in 12..14) (k + 1L) * block - 1000 else Long.MIN_VALUE
        }

        // A vocal lane: a sung tone with vibrato and some bleed under it.
        val vocalLen = (0.5 * rate).toInt()
        val vocal = FloatArray(vocalLen) { i ->
            val t = i.toDouble() / rate
            (0.4 * sin(2 * PI * 330.0 * t - 1.2 * kotlin.math.cos(2 * PI * 5 * t)) +
                0.08 * (2.0 * (t * 196.0 - floor(0.5 + t * 196.0)))).toFloat()
        }

        // A drum lane: an 808-ish kick every 0.4 s.
        val drumLen = (1.2 * rate).toInt()
        val drums = FloatArray(drumLen) { i ->
            val t = i.toDouble() / rate
            val tt = t % 0.4
            if (tt > 0.25) 0f else (0.5 * sin(2 * PI * (50 * tt + 80 * (1 - exp(-tt * 30)) / 30)) * exp(-tt * 12)).toFloat()
        }

        class Lane(val name: String, val spec: ScopeLaneSpec, val raw: FloatArray, val frames: Int, val valid: LongArray?)
        val lanes = listOf(
            Lane("bass", ScopeLaneSpec("Bass", 28f, 300f, 4096, LaneTrigger.PITCH), bass, bassFrames, bassValid),
            Lane("vocal", ScopeLaneSpec("Vocals", 80f, 1000f, 2048, LaneTrigger.PITCH), vocal, (0.5 * rate / block).toInt(), null),
            Lane("drums", ScopeLaneSpec("Drums", 40f, 400f, 1024, LaneTrigger.TRANSIENT), drums, (1.2 * rate / block).toInt(), null),
        )
        f.meta("names", lanes.map { it.name } + "free")
        val dt = block * 1000f / rate
        f.meta("dtMs", dt).meta("windowMs", 35f).meta("cleanMs", 60f).meta("points", 512)
        for (l in lanes) {
            f.meta("${l.name}.minHz", l.spec.minHz).meta("${l.name}.maxHz", l.spec.maxHz)
                .meta("${l.name}.pitchWindow", l.spec.pitchWindow).meta("${l.name}.trigger", l.spec.trigger)
            // A transient lane never looks at its pitched copy, so it is given the raw one.
            val pitched = if (l.spec.trigger == LaneTrigger.TRANSIENT) l.raw else lowPassed(l.raw)
            f.f32("${l.name}.raw", l.raw)
            if (pitched !== l.raw) f.f32("${l.name}.pitched", pitched)
            val lock = ScopeLock(rate, l.spec)
            val traces = (0 until l.frames).map { k ->
                val end = (k + 1) * block
                val valid = l.valid?.get(k) ?: Long.MIN_VALUE
                lock.analyze(window(l.raw, end, lane), window(pitched, end, lane), end.toLong(), 35f, 60f, 512, dt, valid)
            }
            if (l.valid != null) f.i64("${l.name}.validFrom", l.valid)
            putTraces(f, l.name, traces)
        }
        // The bass stream again, free-running: the newest window as it arrives.
        val freeLock = ScopeLock(rate, lanes[0].spec)
        putTraces(f, "free", (0 until bassFrames).map { k -> freeLock.freeRunning(window(bass, (k + 1) * block, free), 35f, 512, dt) })
        return f
    }

    private fun putTraces(f: IosFixture, name: String, traces: List<ScopeTrace>) {
        f.i32("$name.mode", IntArray(traces.size) { traces[it].mode.ordinal })
            .f32("$name.hz", FloatArray(traces.size) { traces[it].hz })
            .f32("$name.clarity", FloatArray(traces.size) { traces[it].clarity })
            .f32("$name.levelDb", FloatArray(traces.size) { traces[it].levelDb })
            .f32("$name.windowMs", FloatArray(traces.size) { traces[it].windowMs })
            .i32("$name.folded", IntArray(traces.size) { traces[it].folded })
            .i32("$name.stale", IntArray(traces.size) { if (traces[it].stale) 1 else 0 })
            .i32("$name.pointCount", IntArray(traces.size) { traces[it].points.size })
            .f32("$name.points", floats(traces.map { it.points }))
            .meta("$name.notes", traces.map { it.note })
    }

    // ---- Beam -------------------------------------------------------------

    private fun beam(): IosFixture {
        val f = IosFixture("beam")
        val ctx = BeamInterpolator.CONTEXT

        // Interpolation: a tone with noise on it, so every tap matters.
        val count = 300
        val src = IosSignals.mix(IosSignals.sine(count + 2 * ctx, SR, 1000.0, 0.7), IosSignals.noise(count + 2 * ctx, 0.2, seed = 9L))
        val out = FloatArray(count * BeamInterpolator.FACTOR)
        BeamInterpolator().interpolate(src, ctx, count, out)
        f.meta("interpCount", count).f32("interpSrc", src).f32("interpOut", out)

        // The clock: 60 fps with a millisecond of jitter against 1024-frame
        // bursts; a pause; a surface that was away for three seconds; and a
        // change of sample rate.
        val jitter = IosSignals.noise(480, 1.0, seed = 4L)
        val clock = BeamClock()
        val frames = 480
        val written = LongArray(frames)
        val now = LongArray(frames)
        val rates = IntArray(frames)
        val from = LongArray(frames)
        val to = LongArray(frames)
        val jumped = IntArray(frames)
        val lag = DoubleArray(frames)
        var w = 0L
        var t = 0L
        var captured = 0L
        for (k in 0 until frames) {
            val rate = if (k < 440) SR else 44_100
            val step = 16_666_667L + (jitter[k] * 1_000_000).toLong()
            t += step
            if (k == 400) {
                t += 3_000_000_000L
                captured += 3_000_000_000L
            }
            if (k !in 300 until 360) captured += step
            while (w + 1024 <= captured * rate / 1_000_000_000L) w += 1024
            clock.advance(w, rate, t)
            written[k] = w
            now[k] = t
            rates[k] = rate
            from[k] = clock.from
            to[k] = clock.to
            jumped[k] = if (clock.jumped) 1 else 0
            lag[k] = clock.targetLag
        }
        f.i64("clockWritten", written).i64("clockNow", now).i32("clockRate", rates)
            .i64("clockFrom", from).i64("clockTo", to).i32("clockJumped", jumped).f64("clockTargetLag", lag)

        // The sweep: a sine, silence, then a bass under a bright lead, at 1 ms
        // and then 2 ms a division, with a reset in the middle.
        val n = 36_000
        val signal = FloatArray(n) { i ->
            val tt = i.toDouble() / SR
            when {
                i < 12_000 -> (0.6 * sin(2 * PI * 440.0 * tt)).toFloat()
                i < 18_000 -> 0f
                else -> {
                    var lead = 0.0
                    for (h in 1..6) lead += sin(2 * PI * 440.0 * h * tt) / h
                    (0.45 * sin(2 * PI * 55.0 * tt) + 0.18 * lead).toFloat()
                }
            }
        }
        val sweep = BeamSweep()
        val eventN = ArrayList<Long>()
        val eventStart = ArrayList<Double>()
        val eventTriggered = ArrayList<Int>()
        var sweepingCount = 0
        var lastStart = Double.NaN
        for (i in 0 until n) {
            if (i == 30_000) sweep.reset()
            val sweepFrames = if (i < 18_000) 480.0 else 960.0
            sweep.configure(SR, sweepFrames)
            sweep.step(i.toLong(), signal[i], maxOf(SR * 0.06, sweepFrames * 1.5))
            if (sweep.sweeping) sweepingCount++
            if (sweep.sweeping && sweep.lastStart != lastStart) {
                lastStart = sweep.lastStart
                eventN += i.toLong()
                eventStart += lastStart
                eventTriggered += if (sweep.triggered) 1 else 0
            }
            if (sweep.sweeping && sweep.x(i + 1.0, sweepFrames) > 1.0) sweep.end()
        }
        return f.f32("sweepSignal", signal).meta("sweepingCount", sweepingCount)
            .i64("sweepEventN", eventN.toLongArray()).f64("sweepEventStart", eventStart.toDoubleArray())
            .i32("sweepEventTriggered", eventTriggered.toIntArray())
    }

    // ---- Crt --------------------------------------------------------------

    private fun crt(): IosFixture {
        val f = IosFixture("crt")

        val energies = floatArrayOf(0f, 1e-4f, 1f / 2048f, 0.01f, 0.1f, 0.5f, 1f, 2f, 5f, 20f, 100f, 300f)
        f.f32("energies", energies).meta("phosphors", Phosphor.entries.map { it.name })
        for (p in Phosphor.entries) f.i32("colorOf.${p.name}", IntArray(energies.size) { Crt.colorOf(p, energies[it]) })

        val scaleCases = floatArrayOf(1000f, 1100f, 2.8f, 160f, 176f, 1f, 1000f, 720f, 1.15f, 100f, 80f, 3f, 3000f, 2000f, 1f, 0f, 0f, 2f)
        f.f32("scaleCases", scaleCases)
            .f32("internalScale", FloatArray(scaleCases.size / 3) { Crt.internalScale(scaleCases[3 * it], scaleCases[3 * it + 1], scaleCases[3 * it + 2]) })

        // Layouts: left, top, right, bottom, density, mode (0 X-Y, 1 Y-T), compact.
        val layoutCases = floatArrayOf(
            0f, 0f, 1000f, 1100f, 2.8f, 0f, 0f,
            0f, 0f, 1000f, 1100f, 2.8f, 1f, 0f,
            12f, 30f, 412f, 230f, 2f, 0f, 1f,
            5f, 5f, 600f, 300f, 1.15f, 1f, 1f,
        )
        val layoutOut = ArrayList<Float>()
        for (k in 0 until layoutCases.size / 7) {
            val c = layoutCases.copyOfRange(7 * k, 7 * k + 7)
            val lay = CrtLayout()
            lay.compute(c[0], c[1], c[2], c[3], c[4], if (c[5] == 0f) OscMode.XY else OscMode.YT, c[6] != 0f)
            layoutOut += listOf(
                lay.faceL, lay.faceT, lay.faceR, lay.faceB, lay.gratL, lay.gratT, lay.gratR, lay.gratB,
                lay.divisionsX.toFloat(), lay.divisionsY.toFloat(), lay.radius, lay.readoutY, lay.density,
            )
        }
        f.f32("layoutCases", layoutCases).f32("layouts", layoutOut.toFloatArray())

        // Two tubes on a simulated 60 fps clock, fed in the phone's 1024-frame
        // bursts. X-Y: the demo's cube, with a pause and a change of phosphor
        // and glow partway. Y-T: a bass under a lead, glow off (compact).
        crtRun(
            f, "xy", 0f, 0f, 160f, 176f, 1f, compact = false, frames = 30,
            before = Settings(), after = Settings(oscPhosphor = Phosphor.SPECTRA, oscGlow = 0.8f), switchAt = 24,
            capturing = { k -> k !in 18..21 }, keep = intArrayOf(12, 21, 29), source = demoSource(2.0),
        )
        val yt = Settings(oscMode = OscMode.YT, oscTimeDivMs = 2f, oscPhosphor = Phosphor.AMBER, oscIntensity = 1.5f, oscZoom = 0.8f)
        crtRun(
            f, "yt", 0f, 0f, 200f, 150f, 1f, compact = true, frames = 30,
            before = yt, after = yt, switchAt = 30,
            capturing = { true }, keep = intArrayOf(15, 29), source = { left, right, frames, start ->
                for (i in 0 until frames) {
                    val t = (start + i).toDouble() / SR
                    var lead = 0.0
                    for (h in 1..6) lead += sin(2 * PI * 440.0 * h * t) / h
                    val v = (0.45 * sin(2 * PI * 55.0 * t) + 0.18 * lead).toFloat()
                    left[i] = v
                    right[i] = v
                }
            },
        )
        return f
    }

    private fun demoSource(seconds: Double): (FloatArray, FloatArray, Int, Long) -> Unit {
        val demo = OscDemo(SR)
        var skipped = false
        return { left, right, frames, _ ->
            if (!skipped) {
                val l = FloatArray(4096)
                val r = FloatArray(4096)
                var todo = ((seconds - 1.2) * SR).toLong()
                while (todo > 0) {
                    val k = minOf(todo, 4096L).toInt()
                    demo.render(l, r, k)
                    todo -= k
                }
                skipped = true
            }
            demo.render(left, right, frames)
        }
    }

    private fun crtRun(
        f: IosFixture,
        name: String,
        l: Float, t: Float, r: Float, b: Float, density: Float,
        compact: Boolean,
        frames: Int,
        before: Settings,
        after: Settings,
        switchAt: Int,
        capturing: (Int) -> Boolean,
        keep: IntArray,
        source: (FloatArray, FloatArray, Int, Long) -> Unit,
    ) {
        val feed = StereoFeed(32_768)
        val crt = Crt()
        val layout = CrtLayout()
        val left = FloatArray(1024)
        val right = FloatArray(1024)
        val fedL = ArrayList<Float>()
        val fedR = ArrayList<Float>()
        var written = 0L
        var captured = 0L
        var now = 0L
        val writtenAt = LongArray(frames)
        val returned = IntArray(frames)
        val triggered = IntArray(frames)
        val kept = ArrayList<IntArray>()
        for (k in 0 until frames) {
            val s = if (k < switchAt) before else after
            layout.compute(l, t, r, b, density, s.oscMode, compact)
            now += 16_666_667L
            if (capturing(k)) {
                captured += 16_666_667L
                while (written + 1024 <= captured * SR / 1_000_000_000L) {
                    source(left, right, 1024, written)
                    feed.write(left, right, 1024)
                    for (i in 0 until 1024) {
                        fedL += left[i]
                        fedR += right[i]
                    }
                    written += 1024
                }
            }
            writtenAt[k] = written
            returned[k] = if (crt.render(feed, SR, s, layout, now, glowAllowed = !compact)) 1 else 0
            triggered[k] = if (crt.triggered) 1 else 0
            if (k in keep) kept += crt.pixels.copyOf()
        }
        for ((tag, s) in listOf("before" to before, "after" to after)) {
            f.meta("$name.$tag.oscMode", s.oscMode).meta("$name.$tag.oscPhosphor", s.oscPhosphor)
                .meta("$name.$tag.oscIntensity", s.oscIntensity).meta("$name.$tag.oscPersistenceMs", s.oscPersistenceMs)
                .meta("$name.$tag.oscGlow", s.oscGlow).meta("$name.$tag.oscZoom", s.oscZoom)
                .meta("$name.$tag.oscTimeDivMs", s.oscTimeDivMs)
        }
        f.meta("$name.switchAt", switchAt)
        f.meta("$name.layout", listOf(l, t, r, b, density)).meta("$name.compact", compact).meta("$name.frames", frames)
            .meta("$name.keep", keep.toList()).meta("$name.width", crt.width).meta("$name.height", crt.height)
            .f32("$name.left", fedL.toFloatArray()).f32("$name.right", fedR.toFloatArray())
            .i64("$name.written", writtenAt).i32("$name.returned", returned).i32("$name.triggered", triggered)
            .i32("$name.pixels", kept.flatMap { it.asIterable() }.toIntArray())
    }
}
