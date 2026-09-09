package com.n3d.spectra.desktop.dev

import com.n3d.spectra.desktop.nes.Nes2A03
import com.n3d.spectra.desktop.nes.NesOptions
import com.n3d.spectra.desktop.nes.PitchPreFilter
import com.n3d.spectra.desktop.nes.TriangleTracker
import com.n3d.spectra.dsp.Biquad
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/** Development harness. Synthesises 2A03 audio and checks the tracker on it. */
private const val SR = 48000
private const val OVERSAMPLE = 8

/**
 * A 2A03 triangle rendered the way the chip renders it: zero-order hold at CPU
 * resolution, oversampled, low-passed, decimated. Not band-limited synthesis —
 * the aliasing is part of what a capture actually contains.
 */
private fun renderTriangle(timer: Int, region: Nes2A03.Region, samples: Int, startCycle: Double = 0.0): FloatArray {
    val hi = FloatArray(samples * OVERSAMPLE)
    val cyclesPerHiSample = region.cpuHz / (SR.toDouble() * OVERSAMPLE)
    for (i in hi.indices) {
        val cycle = startCycle + i * cyclesPerHiSample
        val step = ((cycle / (timer + 1.0)).toLong() % 32L + 32L) % 32L
        hi[i] = Nes2A03.WAVE[step.toInt()]
    }
    return decimate(hi, samples)
}

private fun renderPulse(hz: Double, duty: Float, amp: Float, samples: Int): FloatArray {
    val hi = FloatArray(samples * OVERSAMPLE)
    val inc = hz / (SR.toDouble() * OVERSAMPLE)
    var phase = 0.0
    for (i in hi.indices) {
        hi[i] = if (phase < duty) amp else -amp
        phase += inc
        if (phase >= 1.0) phase -= 1.0
    }
    return decimate(hi, samples)
}

private fun decimate(hi: FloatArray, samples: Int): FloatArray {
    // Two Butterworth sections at 18 kHz, then take every OVERSAMPLE-th sample.
    val qs = Biquad.butterworthQs(4)
    val stages = qs.map { Biquad.lowPass(SR * OVERSAMPLE, 18000.0, it) }
    val out = FloatArray(samples)
    for (i in hi.indices) {
        var v = hi[i].toDouble()
        for (s in stages) v = s.process(v)
        if (i % OVERSAMPLE == 0 && i / OVERSAMPLE < samples) out[i / OVERSAMPLE] = v.toFloat()
    }
    return out
}

/** The same pre-filter the engine runs, so the harness tests what ships. */
private fun pitched(x: FloatArray, o: NesOptions): FloatArray {
    val f = PitchPreFilter(SR, PitchPreFilter.cornerFor(o.huntMaxHz))
    return FloatArray(x.size) { f.process(x[it]) }
}

/** Grid-point shift between two folded periods, by cross-correlation. Drift, in grid units. */
private fun shiftBetween(a: FloatArray, b: FloatArray): Int {
    val n = a.size
    var bestLag = 0
    var best = Double.NEGATIVE_INFINITY
    for (lag in -64..64) {
        var s = 0.0
        for (i in 0 until n step 4) {
            s += a[i] * b[((i + lag) % n + n) % n]
        }
        if (s > best) { best = s; bestLag = lag }
    }
    return bestLag
}

private var failures = 0

private fun check(name: String, ok: Boolean, detail: String) {
    println((if (ok) "  ok   " else "  FAIL ") + name.padEnd(38) + detail)
    if (!ok) failures++
}

fun main() {
    val opts = NesOptions(persistence = 0f, cycles = 2)
    val n = TriangleTracker.HISTORY

    // ---- 1. a clean triangle at A1 -------------------------------------
    run {
        val timer = Nes2A03.timerFor(Nes2A03.Region.NTSC, 55.0)
        val expectedHz = Nes2A03.frequency(Nes2A03.Region.NTSC, timer)
        println("1. clean NTSC triangle, timer=$timer (${"%.3f".format(expectedHz)} Hz, ${Nes2A03.noteName(expectedHz)})")
        val audio = renderTriangle(timer, Nes2A03.Region.NTSC, n + 20000)
        val tracker = TriangleTracker(SR)

        var prev: FloatArray? = null
        var maxDrift = 0
        var reading: com.n3d.spectra.desktop.nes.NesReading? = null
        // Advance by an awkward, non-periodic number of samples each frame, the
        // way a real capture chunk would.
        for (f in 0 until 24) {
            val off = f * 733
            val win = audio.copyOfRange(off, off + n)
            reading = tracker.analyze(win, pitched(win, opts), 16f, opts)
            if (f >= 4 && reading != null) {
                prev?.let { maxDrift = maxOf(maxDrift, abs(shiftBetween(it, reading!!.cycle))) }
                prev = reading.cycle.copyOf()
            }
        }
        val r = reading
        check("locks", r != null && r.locked, if (r == null) "no reading" else "clarity ${"%.3f".format(r.clarity)}")
        if (r != null) {
            check("timer", r.timer == timer, "got ${r.timer}, want $timer")
            check("frequency", abs(r.hz - expectedHz) < 0.05, "${"%.3f".format(r.hz)} Hz vs ${"%.3f".format(expectedHz)}")
            check("on the 2A03 grid", abs(r.gridCents) < 2.0, "${"%.2f".format(r.gridCents)} cents")
            check("matches the ideal wave", r.match > 0.97f, "match ${"%.4f".format(r.match)}")
            check("staircase detected", r.stepMatch > 0.99f, "stepMatch ${"%.4f".format(r.stepMatch)}")
            check("grid step reported", r.gridStepCents in 1.0..2.5, "${"%.2f".format(r.gridStepCents)} ct between timers")
            check("level", abs(r.levelDb) < 0.6f, "${"%.2f".format(r.levelDb)} dBFS")
            check("samples/step", abs(r.samplesPerStep - 27.3) < 0.2, "${"%.2f".format(r.samplesPerStep)}")
            check("note", r.note == "A1", r.note)
        }
        check("stationary", maxDrift <= 1, "max drift $maxDrift/${com.n3d.spectra.desktop.nes.NesReading.GRID} of a period")

        // The ceiling, for reference: the same note sampled straight off the DAC
        // with no anti-alias filter and no resampling. Whatever a real capture
        // scores, it cannot beat this, and the gap is the capture chain's, not
        // the tracker's.
        val direct = FloatArray(n + 4000) { i ->
            val cyc = i * Nes2A03.Region.NTSC.cpuHz / SR
            Nes2A03.WAVE[((cyc / (timer + 1.0)).toLong() % 32L).toInt()]
        }
        val t2 = TriangleTracker(SR)
        var dr: com.n3d.spectra.desktop.nes.NesReading? = null
        for (f in 0 until 6) {
            val win = direct.copyOfRange(f * 500, f * 500 + n)
            dr = t2.analyze(win, pitched(win, opts), 16f, opts)
        }
        check("unfiltered DAC ceiling", (dr?.stepMatch ?: 0f) > 0.99f, "stepMatch ${"%.4f".format(dr?.stepMatch ?: 0f)}")
    }

    // ---- 2. triangle under a pulse melody and noise ----------------------
    run {
        println("2. triangle bass under a pulse melody, non-harmonic")
        val timer = Nes2A03.timerFor(Nes2A03.Region.NTSC, 73.4)
        val tri = renderTriangle(timer, Nes2A03.Region.NTSC, n + 20000)
        // Ratios of 5.66 and 7.55 to the bass: as far from harmonics as notes get.
        val p1 = renderPulse(415.30, 0.5f, 0.45f, n + 20000)
        val p2 = renderPulse(554.37, 0.25f, 0.35f, n + 20000)
        val rnd = Random(7)
        val mix = FloatArray(tri.size) {
            (tri[it] * 0.55f + p1[it] + p2[it] + (rnd.nextFloat() - 0.5f) * 0.05f) * 0.45f
        }

        fun run(o: NesOptions): Pair<com.n3d.spectra.desktop.nes.NesReading?, Int> {
            val tracker = TriangleTracker(SR)
            var reading: com.n3d.spectra.desktop.nes.NesReading? = null
            var prev: FloatArray? = null
            var drift = 0
            for (f in 0 until 24) {
                val win = mix.copyOfRange(f * 733, f * 733 + n)
                reading = tracker.analyze(win, pitched(win, o), 16f, o)
                if (f >= 6 && reading != null) {
                    prev?.let { drift = maxOf(drift, abs(shiftBetween(it, reading!!.cycle))) }
                    prev = reading.cycle.copyOf()
                }
            }
            return reading to drift
        }

        val (r, _) = run(opts)
        check("locks to the bass", r != null && r.timer == timer, "got ${r?.timer}, want $timer")
        check("fits the triangle", (r?.match ?: 0f) > 0.8f, "match ${"%.4f".format(r?.match ?: 0f)}")
        // Measured with deep folding on purpose. At shallow depths the picture
        // legitimately changes between frames as the melody slides through it,
        // and a cross-correlation cannot tell that apart from the lock drifting.
        val (_, deepDrift) = run(opts.copy(foldPeriods = 32))
        check("stationary", deepDrift <= 2, "max drift $deepDrift")

        // Rejection goes as the square root of the fold depth, so the staircase
        // has to come further out of the mix at every step. If it does not, the
        // fold is not doing what the comment claims it does.
        val shallow = run(opts.copy(foldPeriods = 2)).first?.stepMatch ?: 0f
        val mid = run(opts.copy(foldPeriods = 8)).first?.stepMatch ?: 0f
        val deep = run(opts.copy(foldPeriods = 32)).first?.stepMatch ?: 0f
        check(
            "deeper folding digs it out",
            shallow < mid && mid < deep,
            "stepMatch ${"%.3f".format(shallow)} → ${"%.3f".format(mid)} → ${"%.3f".format(deep)} at 2/8/32 periods",
        )
    }

    // ---- 2b. the limit that cannot be engineered away --------------------
    run {
        println("2b. a melody note that IS a harmonic of the bass")
        val timer = Nes2A03.timerFor(Nes2A03.Region.NTSC, 73.4)
        val bassHz = Nes2A03.frequency(Nes2A03.Region.NTSC, timer)
        val tri = renderTriangle(timer, Nes2A03.Region.NTSC, n + 8000)
        val p1 = renderPulse(bassHz * 8.0, 0.5f, 0.5f, n + 8000)
        val mix = FloatArray(tri.size) { (tri[it] * 0.6f + p1[it]) * 0.5f }
        val tracker = TriangleTracker(SR)
        var r: com.n3d.spectra.desktop.nes.NesReading? = null
        for (f in 0 until 10) {
            val win = mix.copyOfRange(f * 733, f * 733 + n)
            r = tracker.analyze(win, pitched(win, opts.copy(foldPeriods = 32)), 16f, opts.copy(foldPeriods = 32))
        }
        // A signal that repeats at exactly the bass period is, to any method that
        // works in the period domain, part of the bass. No amount of folding
        // separates them, and the display correctly shows the sum. This is pinned
        // down so nobody later "fixes" the fold to chase it.
        check("still finds the period", r != null && r.timer == timer, "timer ${r?.timer}")
        check("but cannot unmix it", (r?.stepMatch ?: 1f) < 0.5f, "stepMatch ${"%.3f".format(r?.stepMatch ?: 0f)} — by construction, not by defect")
    }

    // ---- 3. PAL ----------------------------------------------------------
    run {
        println("3. PAL region")
        val timer = 1016
        val palHz = Nes2A03.frequency(Nes2A03.Region.PAL, timer)
        val audio = renderTriangle(timer, Nes2A03.Region.PAL, n + 4000)
        val tracker = TriangleTracker(SR)
        var r: com.n3d.spectra.desktop.nes.NesReading? = null
        for (f in 0 until 8) {
            val win = audio.copyOfRange(f * 500, f * 500 + n)
            r = tracker.analyze(win, pitched(win, opts), 16f, opts.copy(region = Nes2A03.Region.PAL))
        }
        check("PAL timer", r?.timer == timer, "got ${r?.timer}, want $timer at ${"%.3f".format(palHz)} Hz")
        // The same register value read as NTSC must come out as a different,
        // non-integer timer — that is the whole point of the region switch.
        val trackerN = TriangleTracker(SR)
        var rn: com.n3d.spectra.desktop.nes.NesReading? = null
        for (f in 0 until 8) {
            val win = audio.copyOfRange(f * 500, f * 500 + n)
            rn = trackerN.analyze(win, pitched(win, opts), 16f, opts)
        }
        check("region changes the register", rn?.timer == 1094, "NTSC reading is timer ${rn?.timer}, PAL is $timer")
        // Not a bug: at 51 Hz the NTSC grid is ~1.8 cents wide, so the wrong
        // region still lands within a cent of *some* timer. The tuning error
        // cannot reveal the mistake down here; only the register value does.
        check("bass grid is too fine to tell", abs(rn?.gridCents ?: 9.0) < 1.0, "${"%.2f".format(rn?.gridCents ?: 0.0)} ct off a ${"%.2f".format(rn?.gridStepCents ?: 0.0)} ct grid")
    }

    // ---- 4. not a triangle ------------------------------------------------
    run {
        println("4. rejects what it should")
        val tracker = TriangleTracker(SR)
        val silence = FloatArray(n)
        check("silence does not lock", tracker.analyze(silence, silence, 16f, opts) == null, "")

        val rnd = Random(3)
        val noise = FloatArray(n) { (rnd.nextFloat() - 0.5f) * 0.5f }
        val t2 = TriangleTracker(SR)
        var got: com.n3d.spectra.desktop.nes.NesReading? = null
        for (f in 0 until 3) got = t2.analyze(noise, pitched(noise, opts), 16f, opts)
        check("white noise does not lock", got == null, if (got == null) "" else "clarity ${"%.3f".format(got.clarity)}")

        // A sine at the same pitch locks (it is periodic) but must not claim to
        // be a 2A03 — that is what `match` is for.
        val sine = FloatArray(n) { sin(2.0 * Math.PI * 55.0 * it / SR).toFloat() * 0.7f }
        val t3 = TriangleTracker(SR)
        var sr: com.n3d.spectra.desktop.nes.NesReading? = null
        for (f in 0 until 3) sr = t3.analyze(sine, pitched(sine, opts), 16f, opts)
        check("a sine still correlates", sr != null && sr.match > 0.98f, "match ${"%.4f".format(sr?.match ?: -1f)} — which is why match alone proves nothing")
        check("but has no staircase", sr != null && sr.stepMatch < 0.3f, "stepMatch ${"%.4f".format(sr?.stepMatch ?: -1f)}")
    }

    checkHarmonicAlignment()
    checkThroughEngine()
    println()
    println(if (failures == 0) "all checks passed" else "$failures CHECK(S) FAILED")
}


private fun harmonic(v: FloatArray, h: Int): DoubleArray {
    var re = 0.0; var im = 0.0
    for (g in v.indices) {
        val u = 2.0 * Math.PI * h * (g + 0.5) / v.size
        re += v[g] * Math.cos(u); im += v[g] * Math.sin(u)
    }
    return doubleArrayOf(2.0 * Math.hypot(re, im) / v.size, Math.toDegrees(Math.atan2(im, re)))
}

/**
 * The phase-alignment check.
 *
 * This is the one that fails if the fold-phase correction in the tracker is ever
 * removed: aligning on the fundamental alone leaves the 31st harmonic — where the
 * staircase's corners live — forty degrees out, while every amplitude still looks
 * perfect. Amplitudes cannot catch it; only phases can.
 */
private fun checkHarmonicAlignment() {
    println("5. every harmonic aligned, not just the fundamental")
    val timer = Nes2A03.timerFor(Nes2A03.Region.NTSC, 55.0)
    val opts = NesOptions(persistence = 0f, cycles = 2)
    val n = TriangleTracker.HISTORY
    val idealGrid = FloatArray(com.n3d.spectra.desktop.nes.NesReading.GRID) {
        Nes2A03.levelAt((it + 0.5f) / com.n3d.spectra.desktop.nes.NesReading.GRID)
    }
    val direct = FloatArray(n + 4000) { i ->
        val cyc = i * Nes2A03.Region.NTSC.cpuHz / SR
        Nes2A03.WAVE[((cyc / (timer + 1.0)).toLong() % 32L).toInt()]
    }
    val tr = TriangleTracker(SR)
    var r: com.n3d.spectra.desktop.nes.NesReading? = null
    for (k in 0 until 6) {
        val win = direct.copyOfRange(k * 500, k * 500 + n)
        r = tr.analyze(win, pitched(win, opts), 16f, opts)
    }
    val cycle = r!!.cycle
    var worst = 0.0
    var worstH = 0
    for (h in intArrayOf(1, 3, 5, 7, 9, 29, 31, 33, 63, 65)) {
        val m = harmonic(cycle, h)
        val i2 = harmonic(idealGrid, h)
        var d = m[1] - i2[1]
        while (d > 180) d -= 360
        while (d < -180) d += 360
        if (abs(d) > worst) { worst = abs(d); worstH = h }
    }
    check("all harmonics in phase", worst < 1.0, "worst ${"%.2f".format(worst)} deg at h$worstH")
    val h31 = harmonic(cycle, 31)[0]
    val i31 = harmonic(idealGrid, 31)[0]
    check("step-rate harmonics present", abs(h31 - i31) / i31 < 0.05, "h31 ${"%.5f".format(h31)} vs ideal ${"%.5f".format(i31)}")
}


/**
 * The same claim, but through the shipping path.
 *
 * [TrackerCheck]'s other sections feed the tracker arrays directly. This one runs
 * the real engine on the real demo source and watches what the painter would
 * actually receive, so it also covers the ring buffers, the streaming pre-filter
 * and the once-per-16-ms scheduling — none of which the unit-level checks touch.
 */
private fun checkThroughEngine() {
    println("6. end to end, through the engine")
    com.n3d.spectra.desktop.audio.DesktopEngine.nesActive = true
    com.n3d.spectra.desktop.audio.DesktopEngine.updateNes(NesOptions(persistence = 0f, foldPeriods = 16))
    com.n3d.spectra.desktop.audio.DesktopEngine.start(
        com.n3d.spectra.desktop.audio.SyntheticCapture(SR, stereo = true),
    )
    Thread.sleep(2_500)

    val readings = ArrayList<com.n3d.spectra.desktop.nes.NesReading>()
    val timers = ArrayList<Int>()
    // Sample across a single held note: the demo plays quarter notes at 120 bpm,
    // so 300 ms of samples sits inside one.
    repeat(10) {
        com.n3d.spectra.desktop.audio.DesktopEngine.frame?.nes?.let {
            readings += it
            timers += it.timer
        }
        Thread.sleep(30)
    }
    com.n3d.spectra.desktop.audio.DesktopEngine.stop()

    check("engine publishes readings", readings.size >= 8, "${readings.size} of 10 polls")
    if (readings.size < 2) return

    val held = timers.groupingBy { it }.eachCount().maxByOrNull { it.value }
    check("one steady timer across the note", (held?.value ?: 0) >= readings.size - 2, "timer ${held?.key} on ${held?.value}/${readings.size} polls")

    val sameNote = readings.filter { it.timer == held?.key && it.cycle.isNotEmpty() }
    var drift = 0
    for (i in 1 until sameNote.size) {
        drift = maxOf(drift, abs(shiftBetween(sameNote[0].cycle, sameNote[i].cycle)))
    }
    // Free-running, 300 ms at 46 Hz is fourteen periods: the phase would be
    // somewhere else entirely. Stationary means somewhere it already was.
    check("stationary over 300 ms of live capture", drift <= 3, "max drift $drift/2048 of a period across ${sameNote.size} frames")
    check("staircase visible on the demo", (sameNote.lastOrNull()?.stepMatch ?: 0f) > 0.6f, "stepMatch ${"%.3f".format(sameNote.lastOrNull()?.stepMatch ?: 0f)}")
}
