package com.n3d.spectra.desktop.dev

import com.n3d.spectra.dsp.Ballistics
import com.n3d.spectra.dsp.BandSplitter
import com.n3d.spectra.dsp.LoudnessMeter
import com.n3d.spectra.dsp.PeakHold
import com.n3d.spectra.dsp.Resampler
import com.n3d.spectra.dsp.SpectrumAnalyzer
import com.n3d.spectra.dsp.StereoAnalyzer
import com.n3d.spectra.dsp.TruePeak
import com.n3d.spectra.dsp.WindowFunction
import com.n3d.spectra.settings.BandSlope
import com.n3d.spectra.settings.FreqScale
import com.n3d.spectra.settings.Settings
import com.n3d.spectra.settings.Weighting
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/**
 * Fixtures for the spectrum, band, loudness and stereo analysis, and the
 * resampler. Checked by ios/SpectraCore/Tests/SpectraCoreTests/LevelsTests.swift.
 *
 * Each one drives a class the way AudioEngine.runLoop does — a hop at a time for
 * the spectrum, a capture chunk at a time for the streaming meters, with the
 * same dt the engine passes — and changes settings part-way where a class has a
 * configure path worth checking.
 */
object IosLevelsFixtures {

    /** AudioEngine.SPECTROGRAM_ROWS and BAND_HISTORY. */
    private const val ROWS = 256
    private const val BAND_HISTORY = 256

    fun export(sink: (IosFixture) -> Unit) {
        sink(weighting())
        sink(ballistics())
        sink(spectrum())
        sink(bands())
        for (rate in intArrayOf(48_000, 44_100)) sink(loudness(rate))
        sink(truePeak())
        sink(stereo())
        sink(resampler())
    }

    // ---- spectrum ----------------------------------------------------------

    private fun weighting(): IosFixture {
        val fixed = floatArrayOf(
            0f, 5f, 10f, 20f, 31.5f, 63f, 100f, 250f, 500f, 997f, 1000f,
            2000f, 4000f, 8000f, 10000f, 12500f, 16000f, 20000f, 24000f,
        )
        val sweep = FloatArray(97) { (10.0 * 2400.0.pow(it / 96.0)).toFloat() } // 10 Hz … 24 kHz
        val hz = fixed + sweep
        return IosFixture("weighting").f32("hz", hz)
            .f32("A", FloatArray(hz.size) { SpectrumAnalyzer.weightingDb(Weighting.A, hz[it]) })
            .f32("C", FloatArray(hz.size) { SpectrumAnalyzer.weightingDb(Weighting.C, hz[it]) })
            .f32("Z", FloatArray(hz.size) { SpectrumAnalyzer.weightingDb(Weighting.Z, hz[it]) })
    }

    private fun ballistics(): IosFixture {
        val n = 300
        val wobble = IosSignals.noise(n, 1.0, seed = 11L)
        val jitter = IosSignals.noise(n, 1.0, seed = 12L)
        val targets = FloatArray(n) {
            if (it in 100 until 120) -150f // below the floor
            else (-70 + 50 * sin(it * 0.07) + 15 * wobble[it]).toFloat()
        }
        val dts = FloatArray(n) { 1f + 15f * (jitter[it] + 1f) } // 1…31 ms, like uneven capture chunks

        val step = FloatArray(n)
        val b = Ballistics(0.0213f)
        var cur = PeakHold.FLOOR_DB
        for (i in 0 until n) {
            if (i == 150) { b.attackMs = 0f; b.releaseMs = 50f; b.setFrameInterval(0.005f) }
            cur = b.step(cur, targets[i])
            step[i] = cur
        }

        val stepDt = FloatArray(n)
        val bd = Ballistics(0.016f).apply { attackMs = 8f; releaseMs = 220f }
        cur = PeakHold.FLOOR_DB
        for (i in 0 until n) {
            if (i == 150) bd.releaseMs = 0f
            cur = bd.stepDt(cur, targets[i], dts[i] / 1000f)
            stepDt[i] = cur
        }

        val ba = Ballistics(0.0213f)
        val current = FloatArray(64) { PeakHold.FLOOR_DB }
        val stepAll = FloatArray(20 * 64)
        for (s in 0 until 20) {
            ba.stepAll(current, targets.copyOfRange(s * 10, s * 10 + 64))
            current.copyInto(stepAll, s * 64)
        }

        val peaks = PeakHold(64).apply { holdMs = 50f; fallDbPerSec = 60f }
        val held = FloatArray(30 * 64)
        for (s in 0 until 30) {
            if (s == 12) peaks.enabled = false
            if (s == 14) peaks.enabled = true
            if (s == 22) peaks.reset()
            peaks.update(targets.copyOfRange(s * 7, s * 7 + 64), dts[s])
            peaks.values.copyInto(held, s * 64)
        }

        return IosFixture("ballistics").f32("targets", targets).f32("dts", dts)
            .f32("step", step).f32("stepDt", stepDt).f32("stepAll", stepAll).f32("peaks", held)
    }

    private class SpecPhase(val s: Settings, val hops: Int)

    private fun spectrum(): IosFixture {
        val rate = 48_000
        val n = 4096 + 7 * 1024
        val tone = IosSignals.sine(n, rate, 1000.0, 0.5)
        val hum = IosSignals.sine(n, rate, 60.0, 0.25)
        val high = IosSignals.sine(n, rate, 7300.0, 0.05, 0.3)
        val hiss = IosSignals.noise(n, 0.003, seed = 21L)
        // The 1 kHz tone stops halfway and a 7.3 kHz one starts, so the release,
        // the peak hold and its fall all have something to do.
        val input = FloatArray(n) { (if (it < n / 2) tone[it] else high[it]) + hum[it] + hiss[it] }

        val base = Settings()
        val cases = listOf(
            Triple("hann1024", listOf(12, 23), listOf(
                SpecPhase(base.copy(fftSize = 1024, overlap = 4, peakHoldMs = 60f, peakFallDbPerSec = 120f), 24),
            )),
            Triple("flatTop1024A", listOf(13), listOf(
                SpecPhase(base.copy(
                    fftSize = 1024, window = WindowFunction.FLAT_TOP, overlap = 2, weighting = Weighting.A,
                    freqScale = FreqScale.LINEAR, attackMs = 5f, releaseMs = 120f, peakHold = false,
                ), 14),
            )),
            Triple("hann4096C", listOf(3, 7), listOf(
                SpecPhase(base.copy(
                    fftSize = 4096, overlap = 4, weighting = Weighting.C, tiltDbPerOct = 3f,
                    peakHoldMs = 40f, peakFallDbPerSec = 200f,
                ), 8),
            )),
            Triple("flatTop4096A", listOf(7), listOf(
                SpecPhase(base.copy(
                    fftSize = 4096, window = WindowFunction.FLAT_TOP, overlap = 4, weighting = Weighting.A,
                    tiltDbPerOct = -1.5f, minHz = 50f, maxHz = 12000f,
                    spectrogramFloorDb = -120f, spectrogramCeilingDb = 0f,
                ), 8),
            )),
            // A settings change mid-stream that resizes everything: the transform,
            // the bins, the peak hold (which keeps its settings across the resize).
            Triple("reconfig", listOf(5, 11), listOf(
                SpecPhase(base.copy(fftSize = 1024, overlap = 4), 6),
                SpecPhase(base.copy(
                    fftSize = 4096, window = WindowFunction.BLACKMAN_HARRIS, overlap = 8, weighting = Weighting.A,
                    attackMs = 5f, releaseMs = 100f, peakHoldMs = 20f, peakFallDbPerSec = 300f,
                    freqScale = FreqScale.LINEAR, minHz = 0f, maxHz = 30000f,
                ), 6),
            )),
        )

        val f = IosFixture("spectrum").meta("rate", rate).meta("rows", ROWS).f32("input", input)
            .meta("cases", cases.map { it.first })
        for ((name, snaps, phases) in cases) {
            f.meta("$name.phases", phases.size).meta("$name.snaps", snaps)
            phases.forEachIndexed { i, ph -> settingsMeta(f, "$name.$i", ph.s).meta("$name.$i.hops", ph.hops) }

            val a = SpectrumAnalyzer(rate, phases[0].s, ROWS)
            val column = ByteArray(ROWS)
            val columns = ArrayList<Byte>()
            var p = 0
            var h = 0
            phases.forEachIndexed { i, ph ->
                if (i > 0) a.configure(ph.s, rate)
                val hop = ph.s.hopSize
                val dtMs = hop * 1000f / rate
                repeat(ph.hops) {
                    a.process(input.copyOfRange(p, p + ph.s.fftSize), dtMs)
                    a.fillSpectrogramColumn(column, ph.s.spectrogramFloorDb, ph.s.spectrogramCeilingDb)
                    columns.addAll(column.asList())
                    if (h in snaps) {
                        f.f32("$name.raw.$h", a.rawDb.copyOf())
                            .f32("$name.smoothed.$h", a.smoothedDb.copyOf())
                            .f32("$name.peaks.$h", a.peaks.values.copyOf())
                    }
                    p += hop
                    h++
                }
            }
            f.u8("$name.columns", columns.toByteArray())
        }
        return f
    }

    private fun settingsMeta(f: IosFixture, key: String, s: Settings): IosFixture = f
        .meta("$key.fftSize", s.fftSize).meta("$key.window", s.window).meta("$key.overlap", s.overlap)
        .meta("$key.weighting", s.weighting).meta("$key.tiltDbPerOct", s.tiltDbPerOct)
        .meta("$key.freqScale", s.freqScale).meta("$key.minHz", s.minHz).meta("$key.maxHz", s.maxHz)
        .meta("$key.attackMs", s.attackMs).meta("$key.releaseMs", s.releaseMs)
        .meta("$key.peakHold", s.peakHold).meta("$key.peakHoldMs", s.peakHoldMs)
        .meta("$key.peakFallDbPerSec", s.peakFallDbPerSec)
        .meta("$key.spectrogramFloorDb", s.spectrogramFloorDb).meta("$key.spectrogramCeilingDb", s.spectrogramCeilingDb)

    // ---- bands -------------------------------------------------------------

    private class BandCase(val name: String, val slope: BandSlope, val stereo: Boolean, val rate: Int, val reconf: Boolean = false)

    private fun bands(): IosFixture {
        val chunks = intArrayOf(1024, 1024, 480, 1024, 1024, 960, 1024, 1024)
        val n = chunks.sum()
        // One tone inside each of the seven default bands, louder at the bottom like music.
        val centres = doubleArrayOf(35.0, 100.0, 250.0, 800.0, 2500.0, 6500.0, 14000.0)
        val amps = doubleArrayOf(0.3, 0.2, 0.15, 0.1, 0.08, 0.05, 0.03)
        val tones = IosSignals.mix(*Array(7) { IosSignals.sine(n, 48_000, centres[it], amps[it], it * 0.5) })
        val hissL = IosSignals.noise(n, 0.02, seed = 31L)
        val hissR = IosSignals.noise(n, 0.2, seed = 32L)
        // The tones drop 30 dB halfway: the meters release and the hold markers fall.
        val left = FloatArray(n) { (if (it < n / 2) tones[it] else tones[it] * 0.0316f) + hissL[it] }
        val right = FloatArray(n) { hissR[it] + 0.5f * tones[(it + 37) % n] }

        val cases = listOf(
            BandCase("db12_mono", BandSlope.DB12, false, 48_000),
            BandCase("db12_stereo", BandSlope.DB12, true, 48_000),
            BandCase("db24_mono", BandSlope.DB24, false, 48_000),
            BandCase("db24_stereo", BandSlope.DB24, true, 48_000),
            BandCase("db48_mono", BandSlope.DB48, false, 48_000),
            BandCase("db48_stereo", BandSlope.DB48, true, 48_000),
            BandCase("db24_stereo_44k", BandSlope.DB24, true, 44_100),
            // A crossover moved after four chunks (the histories must survive it),
            // then a reset and an empty tick after six.
            BandCase("db48_stereo_reconf", BandSlope.DB48, true, 48_000, reconf = true),
        )
        // A short hold and a steep fall, so the hold markers move within eight ticks.
        val base = Settings(peakHoldMs = 30f, peakFallDbPerSec = 200f)

        val f = IosFixture("bands").meta("chunks", chunks.toList()).f32("left", left).f32("right", right)
            .meta("cases", cases.map { it.name }).meta("peakHoldMs", base.peakHoldMs)
            .meta("peakFallDbPerSec", base.peakFallDbPerSec).meta("historyLength", BAND_HISTORY)
        for (c in cases) {
            f.meta("${c.name}.slope", c.slope).meta("${c.name}.stereo", c.stereo)
                .meta("${c.name}.rate", c.rate).meta("${c.name}.reconf", c.reconf)
            val s = base.copy(bandSlope = c.slope)
            val bs = BandSplitter(c.rate, s, BAND_HISTORY)
            val ticks = ArrayList<Float>()
            var at = 0
            chunks.forEachIndexed { k, frames ->
                if (c.reconf && k == 4) {
                    bs.configure(
                        s.copy(
                            bands = s.bands.mapIndexed { i, b -> if (i == 3) b.copy(highHz = 2000f) else b },
                            bandReleaseMs = 100f,
                        ),
                        c.rate,
                    )
                }
                if (c.reconf && k == 6) {
                    bs.reset()
                    bs.tick(10f)
                }
                val l = left.copyOfRange(at, at + frames)
                val r = if (c.stereo) right.copyOfRange(at, at + frames) else null
                bs.process(l, r, frames)
                bs.tick(frames * 1000f / c.rate)
                for (rd in bs.readings()) { ticks += rd.rmsDb; ticks += rd.peakDb; ticks += rd.holdDb }
                at += frames
            }
            f.f32("${c.name}.ticks", ticks.toFloatArray())
            if (c.name == "db24_stereo" || c.reconf) {
                val hist = FloatArray(BAND_HISTORY)
                f.f32("${c.name}.history", bs.readings().flatMap { it.history.snapshot(hist); hist.asList() }.toFloatArray())
            }
        }
        return f
    }

    // ---- loudness ----------------------------------------------------------

    private class LoudnessRun(val name: String, val signal: String, val stereo: Boolean, val repeats: Int, val s: Settings)

    /**
     * Seconds of audio would be megabytes, so each signal is one 100 ms block
     * (a whole number of 1 kHz periods at both rates) fed again and again, the
     * noise scaled by a per-repeat gain so the loudness actually moves: loud,
     * quiet, louder, below the −70 LUFS gate, then steady.
     */
    private fun loudness(rate: Int): IosFixture {
        val block = rate / 10
        val sine = IosSignals.sine(block, rate, 1000.0, 0.1) // −20 dBFS
        val noiseL = IosSignals.noise(block, 1.0, seed = 41L)
        val noiseR = IosSignals.noise(block, 1.0, seed = 42L)
        val gains = FloatArray(100) { r ->
            when {
                r < 20 -> 0.3f
                r < 40 -> 0.05f
                r < 50 -> 0.6f
                r < 60 -> 0.0001f
                else -> 0.2f
            }
        }
        val runs = listOf(
            LoudnessRun("sine", "sine", true, 50, Settings()),
            LoudnessRun("noise", "noise", true, 100, Settings()),
            LoudnessRun("noise8x", "noise", true, 100, Settings(truePeakOversample = 8)),
            LoudnessRun("noiseMono2x", "noise", false, 100, Settings(truePeakOversample = 2)),
            LoudnessRun("noiseNoTp", "noise", true, 100, Settings(truePeakEnabled = false)),
        )
        val f = IosFixture("loudness_$rate").meta("rate", rate).meta("block", block)
            .f32("sine", sine).f32("noiseL", noiseL).f32("noiseR", noiseR).f32("gains", gains)
            .meta("runs", runs.map { it.name })
        for (run in runs) {
            f.meta("${run.name}.signal", run.signal).meta("${run.name}.stereo", run.stereo)
                .meta("${run.name}.repeats", run.repeats).meta("${run.name}.truePeakEnabled", run.s.truePeakEnabled)
                .meta("${run.name}.truePeakOversample", run.s.truePeakOversample)
            val m = LoudnessMeter(rate, run.s)
            val out = FloatArray(run.repeats * 6)
            for (r in 0 until run.repeats) {
                val l: FloatArray
                val rr: FloatArray
                if (run.signal == "sine") { l = sine; rr = sine } else {
                    val g = gains[r]
                    l = FloatArray(block) { noiseL[it] * g }
                    rr = FloatArray(block) { noiseR[it] * g }
                }
                m.process(l, if (run.stereo) rr else null, block)
                val rd = m.read()
                floatArrayOf(rd.momentary, rd.shortTerm, rd.integrated, rd.range, rd.truePeakDb, rd.integratedSeconds)
                    .copyInto(out, r * 6)
            }
            f.f32("${run.name}.readings", out)
            if (run.name == "sine") {
                val last = out.copyOfRange(out.size - 6, out.size)
                println("loudness_$rate: −20 dBFS 1 kHz stereo sine reads M ${last[0]} S ${last[1]} I ${last[2]} LUFS, TP ${last[4]} dBTP")
            }
        }
        return f
    }

    private fun truePeak(): IosFixture {
        val n = 512
        // fs/4 at 45°: every sample is ±0.636 but the wave peaks at 0.9 between them.
        val quarter = IosSignals.sine(n, 48_000, 12_000.0, 0.9, PI / 4)
        val nearNyquist = IosSignals.sine(n, 48_000, 21_000.0, 0.5, 0.2)
        val hiss = IosSignals.noise(n, 0.1, seed = 51L)
        val input = FloatArray(n) { if (it < 200) quarter[it] else nearNyquist[it] + hiss[it] }
        val configs = listOf(2 to 12, 4 to 12, 8 to 12, 4 to 16)
        val f = IosFixture("truePeak").f32("input", input)
            .meta("factors", configs.map { it.first }).meta("taps", configs.map { it.second })
        for ((factor, taps) in configs) {
            val tp = TruePeak(factor, taps)
            f.f32("x${factor}_$taps", FloatArray(n) { tp.process(input[it]) })
        }
        return f
    }

    // ---- stereo ------------------------------------------------------------

    private class StereoCase(val name: String, val right: FloatArray?, val windowMs: Float)

    private fun stereo(): IosFixture {
        val n = 4096
        // The 480 chunk is smaller than the 720-dot cloud; the 1568 one is decimated into it.
        val chunks = intArrayOf(1024, 480, 1024, 1568)
        val n1 = IosSignals.noise(n, 0.5, seed = 61L)
        val n2 = IosSignals.noise(n, 0.5, seed = 62L)
        val partial = FloatArray(n) { 0.6f * n1[it] + 0.8f * n2[it] }
        val anti = FloatArray(n) { -n1[it] }
        val cases = listOf(
            StereoCase("inPhase", n1, 300f),
            StereoCase("antiPhase", anti, 300f),
            StereoCase("decorrelated", n2, 300f),
            StereoCase("partial", partial, 50f),
            StereoCase("mono", null, 300f),
        )
        val f = IosFixture("stereo").meta("rate", 48_000).meta("chunks", chunks.toList())
            .f32("n1", n1).f32("n2", n2).f32("partial", partial)
            .meta("cases", cases.map { it.name }).meta("windowMs", cases.map { it.windowMs })
        for (c in cases) {
            val sa = StereoAnalyzer(48_000, Settings(correlationWindowMs = c.windowMs))
            val readings = ArrayList<Float>()
            val sizes = IntArray(chunks.size)
            var at = 0
            chunks.forEachIndexed { k, frames ->
                sa.process(n1.copyOfRange(at, at + frames), c.right?.copyOfRange(at, at + frames), frames)
                readings += listOf(sa.correlation, sa.width, sa.rmsL, sa.rmsR, sa.peakL, sa.peakR)
                sizes[k] = sa.cloudSize()
                if (c.right != null && (k == 1 || k == 3)) f.f32("${c.name}.cloud.$k", sa.snapshotCloud())
                at += frames
            }
            f.f32("${c.name}.readings", readings.toFloatArray()).i32("${c.name}.cloudSizes", sizes)
        }

        val levels = floatArrayOf(0f, -1f, 1e-8f, 1e-7f, 1.0000001e-7f, 2e-7f, 1f, 1.5f) +
            FloatArray(60) { 10.0.pow(-7.5 + it * 0.13).toFloat() }
        return f.f32("toDbIn", levels).f32("toDbOut", FloatArray(levels.size) { StereoAnalyzer.toDb(levels[it]) })
    }

    // ---- resampler ---------------------------------------------------------

    private class ResampleCase(val name: String, val inRate: Int, val outRate: Int, val chunks: IntArray)

    private fun resampler(): IosFixture {
        val n = 6000
        // Exponential chirp 50 Hz → 22 kHz at 48 kHz: deep bass up to above the new Nyquist.
        val k = ln(22_000.0 / 50.0) / n
        val chirp = FloatArray(n) { (0.8 * sin(2 * PI * 50.0 * (exp(k * it) - 1) / k / 48_000)).toFloat() }
        val hiss = IosSignals.noise(n, 0.4, seed = 71L)
        val cases = listOf(
            ResampleCase("down48to44", 48_000, 44_100, intArrayOf(1, 999, 4096, 17, 887)),
            ResampleCase("up44to48", 44_100, 48_000, intArrayOf(700, 1, 2299)),
            ResampleCase("up32to44", 32_000, 44_100, intArrayOf(400, 600)),
        )
        val f = IosFixture("resampler").f32("inL", chirp).f32("inR", hiss).meta("cases", cases.map { it.name })
        for (c in cases) {
            val r = Resampler(c.inRate, c.outRate)
            val outL = ArrayList<Float>()
            val outR = ArrayList<Float>()
            val produced = IntArray(c.chunks.size)
            var at = 0
            c.chunks.forEachIndexed { i, frames ->
                val l = FloatArray(r.maxOutput(frames))
                val rr = FloatArray(r.maxOutput(frames))
                produced[i] = r.process(chirp.copyOfRange(at, at + frames), hiss.copyOfRange(at, at + frames), frames, l, rr)
                for (j in 0 until produced[i]) { outL += l[j]; outR += rr[j] }
                at += frames
            }
            f.meta("${c.name}.inRate", c.inRate).meta("${c.name}.outRate", c.outRate)
                .meta("${c.name}.chunks", c.chunks.toList())
                .i32("${c.name}.produced", produced)
                .f32("${c.name}.outL", outL.toFloatArray()).f32("${c.name}.outR", outR.toFloatArray())
        }
        return f
    }
}
