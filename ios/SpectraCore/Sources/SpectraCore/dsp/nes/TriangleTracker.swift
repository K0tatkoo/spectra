import Foundation

// TriangleTracker.kt, part 1: the options and the reading. The pages and
// AnalysisFrame need these before the tracker itself (part 2, below) exists.

/// How the 2A03 waveform view behaves. All of it is display, none of it is capture.
public struct NesOptions: Sendable, Equatable {
    public var region: Nes2A03.Region = .ntsc
    /// Snap the display period to the nearest 2A03 timer value. This is what makes it stand still.
    public var lockToTimer: Bool = true
    /// Periods drawn across the plot.
    public var cycles: Int = 2
    public var huntMinHz: Float = 25
    public var huntMaxHz: Float = 200
    /// NSDF peak below which the detection is not trusted.
    public var clarityThreshold: Float = 0.55
    /// Fold every period in the window onto one. Rejects the other four channels.
    public var averaging: Bool = true
    /// How many periods to fold together. Rejection of anything not harmonically
    /// locked to the note goes as √n, so 8 is about 9 dB — enough to lift the
    /// triangle out from under a melody, while still following a bass line that
    /// changes note every eighth.
    public var foldPeriods: Int = 8
    /// Cross-frame smoothing of the folded period, 0…0.95.
    public var persistence: Float = 0.55
    public var showIdeal: Bool = true
    public var showLevels: Bool = true
    public var showSteps: Bool = true
    /// Snap the trace to the sixteen DAC levels the chip can actually output.
    public var quantizeToDac: Bool = false
    /// Mark the individual captured samples, so the time grid is visible too.
    public var showSamples: Bool = true
    /// Keep the last good lock on screen this long after the signal goes.
    public var holdMs: Float = 900

    public init() {}
}

/// One frame of 2A03 triangle analysis. Everything the painter needs, already in
/// chip units — phase 0…1, amplitude normalised to the DAC's own ±1.
public struct NesReading: Sendable {
    public let locked: Bool
    /// True while this is a held copy of an older lock rather than a live one.
    public let stale: Bool
    public let region: Nes2A03.Region
    /// Measured fundamental, Hz.
    public let hz: Double
    /// The 11-bit timer that would produce it.
    public let timer: Int
    /// What that timer actually plays.
    public let nesHz: Double
    public let note: String
    /// Cents from `nesHz` to equal temperament.
    public let cents: Double
    /// Cents between the measurement and the chip's grid — large means this is not a 2A03.
    public let gridCents: Double
    public let samplesPerStep: Double
    public let periodSamples: Double
    /// Cents between this timer and the next one — how much the chip's grid is worth here.
    public let gridStepCents: Double
    /// Periods actually folded. Below the setting while a note is still new.
    public let foldedPeriods: Int
    /// NSDF peak, 0…1. How periodic the input is.
    public let clarity: Float
    /// Correlation with the ideal 32-step wave, −1…1. Dominated by the fundamental.
    public let match: Float
    /// Correlation with the ideal wave *after the fundamental is removed*, −1…1.
    ///
    /// This is the number that says "2A03" and `match` is not. A plain sine
    /// correlates 0.991 with a triangle — the triangle's own fundamental carries
    /// 99 % of its energy — so a high `match` proves only that something periodic
    /// is there. What is left after the fundamental is subtracted is the staircase
    /// and the odd-harmonic series, and that is the chip's signature.
    public let stepMatch: Float
    /// Fitted amplitude of the triangle component, dBFS.
    public let levelDb: Float
    /// One period folded and averaged, `grid` points, normalised to ±1. Empty when not averaging.
    public let cycle: [Float]
    /// Raw samples across the drawn span, normalised to ±1.
    public let live: [Float]
    /// Phase of `live[0]`, in periods. Usually a small negative number.
    public let livePhase0: Float
    public let cycles: Int
    public let audible: Bool

    public init(
        locked: Bool, stale: Bool, region: Nes2A03.Region, hz: Double, timer: Int, nesHz: Double,
        note: String, cents: Double, gridCents: Double, samplesPerStep: Double, periodSamples: Double,
        gridStepCents: Double, foldedPeriods: Int, clarity: Float, match: Float, stepMatch: Float,
        levelDb: Float, cycle: [Float], live: [Float], livePhase0: Float, cycles: Int, audible: Bool
    ) {
        self.locked = locked
        self.stale = stale
        self.region = region
        self.hz = hz
        self.timer = timer
        self.nesHz = nesHz
        self.note = note
        self.cents = cents
        self.gridCents = gridCents
        self.samplesPerStep = samplesPerStep
        self.periodSamples = periodSamples
        self.gridStepCents = gridStepCents
        self.foldedPeriods = foldedPeriods
        self.clarity = clarity
        self.match = match
        self.stepMatch = stepMatch
        self.levelDb = levelDb
        self.cycle = cycle
        self.live = live
        self.livePhase0 = livePhase0
        self.cycles = cycles
        self.audible = audible
    }

    /// Points in `cycle`.
    public static let grid = 2048
}

// TriangleTracker.kt, part 2: the TriangleTracker class.

/// Finds a 2A03 triangle in captured audio and holds it still.
///
/// Three problems have to be solved to draw a stationary staircase, and they are
/// solved in this order:
///
///  1. **What is the period?** An FFT-based normalised square-difference function
///     (McLeod) over a low-passed copy of the input. Restricting the lag search to
///     a bass hunt range is what stops the pulse channels' melody from winning.
///  2. **What period would the chip have used?** The measurement is rounded to the
///     nearest 11-bit timer value and the *exact* frequency of that timer is used
///     for the display. This is the single thing that makes the picture stand
///     still: a period taken from a measurement wanders by a fraction of a sample
///     every frame and the wave crawls sideways, while a period taken from the
///     timer grid is a constant for as long as the note is held. The distance
///     between the two is reported rather than hidden — if it is large, the source
///     is not a 2A03 and the lock is a lie.
///  3. **Where does a period start?** The argument of a single DFT bin at the
///     fundamental, which is continuous, so there is no quantisation to jitter,
///     followed by a short parabolic search against the ideal wave to absorb the
///     playback chain's phase shift and to resolve a polarity flip.
///
/// Not thread safe; the analysis thread owns one. Every buffer is allocated up
/// front; the only allocations per call are the reading's own `cycle` and `live`
/// arrays, which cross to the painter's thread.
public final class TriangleTracker {

    private let sampleRate: Int

    private let fft: Fft
    private let re: UnsafeMutableBufferPointer<Float>
    private let im: UnsafeMutableBufferPointer<Float>
    private let prefix: UnsafeMutableBufferPointer<Double>
    private let nsdf: UnsafeMutableBufferPointer<Float>

    /// The ideal wave sampled on the display grid, built once.
    private let ideal: UnsafeMutableBufferPointer<Float>
    private let idealEnergy: Float

    /// Phase of the ideal wave's own fundamental. Zero by symmetry; measured, not assumed.
    private let idealPhase: Double

    /// The ideal wave with DC and its own fundamental taken out — the chip's signature.
    private let idealDetail: UnsafeMutableBufferPointer<Float>
    private let idealDetailEnergy: Float
    private let detail: UnsafeMutableBufferPointer<Float>

    private let folded: UnsafeMutableBufferPointer<Float>
    private let smoothed: UnsafeMutableBufferPointer<Float>
    private var smoothedTimer = -1
    private var smoothedValid = false

    private var heldMs: Float = 0
    private var held: NesReading? = nil

    /// Milliseconds since the timer last changed, for the fold-depth ramp.
    private var sinceNoteChange: Float = 0
    private var lastTimer = -1

    /// `Nes2A03.levelAt((i + 0.5) / corrPoints)` for each correlation point: the
    /// same values `correlate` would look up per point, looked up once.
    private let corrWave: UnsafeMutableBufferPointer<Float>
    /// `refine`'s per-offset scores, one search at a time.
    private let scores: UnsafeMutableBufferPointer<Float>

    public init(sampleRate: Int) {
        self.sampleRate = sampleRate
        let grid = NesReading.grid
        let fftN = TriangleTracker.fftN
        fft = Fft(size: fftN)
        re = .allocate(capacity: fftN)
        re.initialize(repeating: 0)
        im = .allocate(capacity: fftN)
        im.initialize(repeating: 0)
        prefix = .allocate(capacity: TriangleTracker.pitchN + 1)
        prefix.initialize(repeating: 0)
        nsdf = .allocate(capacity: TriangleTracker.pitchN)
        nsdf.initialize(repeating: 0)

        ideal = .allocate(capacity: grid)
        for i in 0..<grid { ideal.initializeElement(at: i, to: Nes2A03.levelAt((Float(i) + 0.5) / Float(grid))) }
        var e = 0.0
        for v in ideal { e += Double(v * v) }
        idealEnergy = Float(e)
        idealPhase = TriangleTracker.fundamentalPhase(UnsafeBufferPointer(ideal))

        idealDetail = .allocate(capacity: grid)
        _ = idealDetail.initialize(fromContentsOf: ideal)
        TriangleTracker.stripFundamental(idealDetail)
        var d = 0.0
        for v in idealDetail { d += Double(v * v) }
        idealDetailEnergy = Float(d)
        detail = .allocate(capacity: grid)
        detail.initialize(repeating: 0)

        folded = .allocate(capacity: grid)
        folded.initialize(repeating: 0)
        smoothed = .allocate(capacity: grid)
        smoothed.initialize(repeating: 0)

        corrWave = .allocate(capacity: TriangleTracker.corrPoints)
        for i in 0..<TriangleTracker.corrPoints {
            corrWave.initializeElement(at: i, to: Nes2A03.levelAt((Float(i) + 0.5) / Float(TriangleTracker.corrPoints)))
        }
        scores = .allocate(capacity: TriangleTracker.refineSteps)
        scores.initialize(repeating: 0)
    }

    deinit {
        re.deallocate()
        im.deallocate()
        prefix.deallocate()
        nsdf.deallocate()
        ideal.deallocate()
        idealDetail.deallocate()
        detail.deallocate()
        folded.deallocate()
        smoothed.deallocate()
        corrWave.deallocate()
        scores.deallocate()
    }

    public func reset() {
        smoothedValid = false
        smoothedTimer = -1
        held = nil
        heldMs = 0
        sinceNoteChange = 0
        lastTimer = -1
    }

    /// - Parameters:
    ///   - raw: full-rate mono history, oldest first, newest last.
    ///   - pitched: the same history low-passed for pitch detection.
    ///   - dtMs: milliseconds since the previous call, for the hold timer.
    public func analyze(_ raw: UnsafeBufferPointer<Float>, _ pitched: UnsafeBufferPointer<Float>,
                        dtMs: Float, _ o: NesOptions) -> NesReading? {
        let n = raw.count
        if n < TriangleTracker.pitchN + 16 { return nil }

        let lag = detectPeriod(pitched, o)
        let clarity = lastClarity

        if lag <= 0.0 || clarity < o.clarityThreshold {
            // Hold the last good lock briefly. A triangle that is retriggering,
            // or a rest between notes, should not make the whole display jump.
            heldMs += dtMs
            if let h = held, heldMs <= o.holdMs { return h.asStale() }
            reset()
            return nil
        }
        heldMs = 0

        let measuredHz = Double(sampleRate) / lag
        let timer = Nes2A03.timerFor(o.region, hz: measuredHz)
        let nesHz = Nes2A03.frequency(o.region, timer: timer)
        let period = o.lockToTimer ? Double(sampleRate) / nesHz : lag
        if period < 8.0 { return nil }

        // Two spare periods of slack behind the drawn span: the phase correction
        // can move the window by half a period, the polarity search by another
        // half, and both have to stay inside the history that is actually there.
        let slack = 2.0 * period
        let cycles = min(coerceIn(o.cycles, 1, 8), ktToInt(floor((Double(n - 2) - slack) / period)))
        if cycles < 1 { return nil }
        let span = period * Double(cycles)

        var start = Double(n - 2) - span - slack

        // How deep the fold is allowed to go *right now*.
        //
        // Folding sixteen periods together is worth nine decibels of rejection
        // and about a third of a second of audio, and a bass line that moves
        // every quarter note would spend a quarter of its life averaging two
        // different notes into one smeared picture. So the depth is capped at the
        // number of whole periods that have elapsed since the timer last changed
        // and grows back to the setting as the note is held. The count starts
        // when the *tracker* first sees the new timer, which is already after the
        // note began, so the cap only ever errs towards the newer audio.
        if timer != lastTimer {
            lastTimer = timer
            sinceNoteChange = 0
        } else {
            sinceNoteChange += dtMs
        }
        let periodMs = period * 1000.0 / Double(sampleRate)
        let sinceChange = ktToInt(floor(Double(sinceNoteChange) / periodMs))
        let want = min(coerceIn(o.foldPeriods, 1, TriangleTracker.maxFold), max(1, sinceChange))
        start += phaseOffset(raw, start, period, foldWindow(n, start, period, want))
        start = refine(raw, start, period, want)
        start = coerceIn(start, 0.0, max(Double(n - 2) - span, 0.0))

        return build(raw, start, period, cycles, measuredHz, timer, nesHz, clarity, o)
    }

    /// The same, on arrays.
    public func analyze(_ raw: [Float], _ pitched: [Float], dtMs: Float, _ o: NesOptions) -> NesReading? {
        raw.withUnsafeBufferPointer { r in
            pitched.withUnsafeBufferPointer { p in analyze(r, p, dtMs: dtMs, o) }
        }
    }

    private func build(
        _ raw: UnsafeBufferPointer<Float>,
        _ startIn: Double,
        _ period: Double,
        _ cycles: Int,
        _ measuredHz: Double,
        _ timer: Int,
        _ nesHz: Double,
        _ clarity: Float,
        _ o: NesOptions
    ) -> NesReading {
        let grid = NesReading.grid
        var start = startIn

        // Fold whole periods onto one grid. This is a comb filter with teeth
        // exactly on the harmonics of the note, which is why it can pull the
        // triangle out from under a pulse melody that no low-pass could separate
        // — and why the staircase survives it intact, corners and all.
        //
        // Periods *behind* the drawn window count as much as the ones inside it:
        // the signal is periodic, so folding backwards is free rejection, and
        // there are only two or three periods in front of `start` to work with.
        let want = coerceIn(o.foldPeriods, 1, TriangleTracker.maxFold)
        var periods = fold(raw, start, period, want)

        // Fold once, read the fundamental's phase off the result, move the window
        // by exactly that, fold again.
        //
        // Everything upstream aligns on the fundamental, and that is not good
        // enough: a residual of one degree at the fundamental is forty degrees at
        // the 31st harmonic, which is where the staircase's corners live. The
        // trace would still sit on the ghost to the eye and every step edge would
        // be in the wrong place. The folded array is exactly one period on a
        // fixed grid, so its own fundamental phase is exact — no window
        // truncation, no leakage, nothing to bias it — and one correction from it
        // brings every harmonic into line at once.
        let drift = TriangleTracker.wrapPi(TriangleTracker.fundamentalPhase(UnsafeBufferPointer(folded)) - idealPhase)
        let corrected = start + drift * period / (2.0 * Double.pi)
        if corrected >= 0.0 && corrected + period * Double(cycles) < Double(raw.count - 1) {
            start = corrected
            periods = fold(raw, start, period, want)
        }

        // Least-squares fit of the folded period to the chip's own wave. The
        // scale factor is the amplitude of the triangle component; the normalised
        // dot product is how much of what is on screen is actually a triangle.
        var dot = 0.0
        var energy = 0.0
        for g in 0..<grid {
            dot += Double(folded[g] * ideal[g])
            energy += Double(folded[g] * folded[g])
        }
        let amplitude = Float(dot / Double(idealEnergy))
        let match: Float = energy <= 1e-12 ? 0 : Float(dot / (energy * Double(idealEnergy)).squareRoot())

        // The same correlation again with both fundamentals removed. See the note
        // on NesReading.stepMatch: this is the one that can tell a chip from a
        // sine, and it is computed before the amplitude normalisation so a quiet
        // note is not flattered by it.
        _ = detail.update(fromContentsOf: folded)
        TriangleTracker.stripFundamental(detail)
        var dDot = 0.0
        var dEnergy = 0.0
        for g in 0..<grid {
            dDot += Double(detail[g] * idealDetail[g])
            dEnergy += Double(detail[g] * detail[g])
        }
        let stepMatch: Float =
            dEnergy <= 1e-14 ? 0 : Float(dDot / (dEnergy * Double(idealDetailEnergy)).squareRoot())
        // The 2A03 has no volume control, so amplitude carries no information and
        // normalising it away is free. Guard the divide: a rest fits amplitude 0.
        let norm: Float = abs(amplitude) < 1e-6 ? 0 : 1 / amplitude

        for g in 0..<grid { folded[g] *= norm }

        let outCycle: [Float]
        if o.averaging {
            let alpha: Float = smoothedValid && smoothedTimer == timer ? 1 - coerceIn(o.persistence, 0, 0.95) : 1
            for g in 0..<grid {
                smoothed[g] = smoothed[g] * (1 - alpha) + folded[g] * alpha
            }
            smoothedValid = true
            smoothedTimer = timer
            outCycle = Array(smoothed)
        } else {
            smoothedValid = false
            outCycle = []
        }

        let first = max(ktToInt(floor(start)), 0)
        let count = min(ktToInt(ceil(period * Double(cycles))) + 2, raw.count - first)
        let live = [Float](unsafeUninitializedCapacity: count) { buf, initialized in
            for i in 0..<count { buf[i] = norm != 0 ? raw[first + i] * norm : raw[first + i] }
            initialized = count
        }

        let samplesPerStep = Nes2A03.samplesPerStep(o.region, timer: timer, sampleRate: sampleRate)
        let gridCents = 1200.0 * log(measuredHz / nesHz) / log(2.0)

        let reading = NesReading(
            locked: true,
            stale: false,
            region: o.region,
            hz: measuredHz,
            timer: timer,
            nesHz: nesHz,
            note: Nes2A03.noteName(nesHz),
            cents: Nes2A03.centsOff(nesHz),
            gridCents: gridCents,
            samplesPerStep: samplesPerStep,
            periodSamples: period,
            gridStepCents: gridStepCents(o.region, timer),
            foldedPeriods: periods,
            clarity: clarity,
            match: match,
            stepMatch: stepMatch,
            levelDb: abs(amplitude) < 1e-6 ? -120 : Float(20.0 * log10(Double(abs(amplitude)))),
            cycle: outCycle,
            live: live,
            livePhase0: Float((Double(first) - start) / period),
            cycles: cycles,
            audible: timer >= Nes2A03.timerMinAudible
        )
        held = reading
        return reading
    }

    // ---- period detection --------------------------------------------------

    private var lastClarity: Float = 0

    /// McLeod's normalised square difference, computed through the FFT.
    ///
    /// The autocorrelation is the inverse transform of the power spectrum, and
    /// the normalising term is two prefix sums, so the whole thing is two
    /// transforms rather than a quarter of a million multiply-adds per lag. The
    /// peak is picked the way MPM prescribes — the *first* local maximum within
    /// 90 % of the global one — because for a periodic signal every multiple of
    /// the period is also a near-perfect peak, and taking the tallest would drop
    /// an octave at random.
    private func detectPeriod(_ x: UnsafeBufferPointer<Float>, _ o: NesOptions) -> Double {
        lastClarity = 0
        let n = TriangleTracker.pitchN
        let from = x.count - n

        var mean = 0.0
        for i in 0..<n { mean += Double(x[from + i]) }
        mean /= Double(n)

        re.update(repeating: 0)
        im.update(repeating: 0)
        prefix[0] = 0.0
        for i in 0..<n {
            let v = Float(Double(x[from + i]) - mean)
            re[i] = v
            prefix[i + 1] = prefix[i] + Double(v) * Double(v)
        }
        if prefix[n] < 1e-9 { return -1.0 }

        fft.transform(re.baseAddress!, im.baseAddress!)
        for k in 0..<TriangleTracker.fftN {
            re[k] = re[k] * re[k] + im[k] * im[k]
            im[k] = 0
        }
        fft.transform(re.baseAddress!, im.baseAddress!)
        let scale = 1 / Float(TriangleTracker.fftN)

        let minLag = max(2, ktToInt(Float(sampleRate) / max(o.huntMaxHz, 1)))
        let maxLag = min(n - 2, ktToInt(Float(sampleRate) / max(o.huntMinHz, 1)))
        if maxLag <= minLag + 2 { return -1.0 }

        let total = prefix[n]
        for tau in minLag...maxLag {
            let m = prefix[n - tau] + total - prefix[tau]
            nsdf[tau] = m <= 1e-12 ? 0 : (2 * re[tau] * scale / Float(m))
        }

        var globalMax: Float = 0
        for tau in (minLag + 1)..<maxLag where nsdf[tau] > globalMax { globalMax = nsdf[tau] }
        if globalMax <= 0 { return -1.0 }
        let threshold = globalMax * TriangleTracker.keyMaxRatio

        for tau in (minLag + 1)..<maxLag {
            let v = nsdf[tau]
            if v < threshold { continue }
            if v < nsdf[tau - 1] || v < nsdf[tau + 1] { continue }
            // Parabolic interpolation over the three samples around the peak.
            // Without it the period is quantised to whole samples, which at
            // 55 Hz is 0.1 % of a period — enough to slide the wave visibly.
            let a = nsdf[tau - 1]
            let c = nsdf[tau + 1]
            let denom = a - 2 * v + c
            let delta: Float = abs(denom) < 1e-9 ? 0 : 0.5 * (a - c) / denom
            lastClarity = coerceIn(v - 0.25 * (a - c) * delta, 0, 1)
            return Double(tau) + Double(coerceIn(delta, -1, 1))
        }
        return -1.0
    }

    // ---- phase -------------------------------------------------------------

    /// How far the window start has to move to land on a peak of the fundamental.
    ///
    /// A single DFT bin at the detected period. The chip's own wave is even about
    /// step 0 (level 15 is held either side of phase zero), so its fundamental has
    /// zero phase by construction and the argument of this bin *is* the offset.
    /// Continuous, so unlike a trigger threshold it adds no jitter of its own.
    private func phaseOffset(_ x: UnsafeBufferPointer<Float>, _ start: Double, _ period: Double,
                             _ window: (m0: Int, count: Int)) -> Double {
        let m0 = window.m0
        // Integrate over every folded period, not just the drawn ones. It is the
        // same averaging the fold does, and it is what keeps a pulse channel from
        // dragging the picture sideways.
        let base = start + Double(m0) * period
        let i0 = coerceIn(ktRoundToInt(base), 0, x.count - 1)
        let count = min(ktToInt(Double(window.count) * period), x.count - i0)
        if count < 8 { return 0.0 }
        var sumRe = 0.0
        var sumIm = 0.0
        let w = 2.0 * Double.pi / period
        for i in 0..<count {
            let v = Double(x[i0 + i])
            sumRe += v * cos(w * Double(i))
            sumIm -= v * sin(w * Double(i))
        }
        if abs(sumRe) < 1e-12 && abs(sumIm) < 1e-12 { return 0.0 }
        // The bin measures the phase at i0. Whole periods later the phase is the
        // same, so stepping back by m0 of them lands the peak next to `start`.
        let peak = Double(i0) - period * atan2(sumIm, sumRe) / (2.0 * Double.pi)
        return (peak - Double(m0) * period) - start
    }

    /// Slides the window against the ideal wave to absorb the playback chain's
    /// phase shift, and to catch a polarity flip that the fundamental's argument
    /// cannot see — a capture path that inverts would otherwise draw the chip's
    /// staircase upside down and never say so. Parabolic interpolation keeps the
    /// result continuous, so nothing here can add jitter of its own.
    private func refine(_ x: UnsafeBufferPointer<Float>, _ start: Double, _ period: Double, _ want: Int) -> Double {
        let steps = TriangleTracker.refineSteps
        let reach = period / 16.0
        let mid = Double(steps - 1) / 2.0
        let spacing = 2.0 * reach / Double(steps - 1)
        var best = start
        var bestScore = -Float.infinity
        for flip in 0...1 {
            let centre = start + Double(flip) * period / 2.0
            var bestI = 0
            var bestV = -Float.infinity
            for i in 0..<steps {
                let off = centre + (Double(i) - mid) * spacing
                scores[i] = correlate(x, off, period, want)
                if scores[i] > bestV { bestV = scores[i]; bestI = i }
            }
            var refined = centre + (Double(bestI) - mid) * spacing
            if bestI >= 1 && bestI < steps - 1 {
                let a = scores[bestI - 1]
                let c = scores[bestI + 1]
                let denom = a - 2 * bestV + c
                if abs(denom) > 1e-9 {
                    refined += Double(0.5 * (a - c) / denom) * spacing
                }
            }
            if bestV > bestScore { bestScore = bestV; best = refined }
        }
        return best
    }

    /// Correlation of the ideal wave, repeated, against every whole period in the
    /// history. Correlating one period would let whatever else is playing decide
    /// the alignment; repeating it is the same averaging the fold does.
    private func correlate(_ x: UnsafeBufferPointer<Float>, _ start: Double, _ period: Double, _ want: Int) -> Float {
        let window = foldWindow(x.count, start, period, want)
        let m0 = window.m0
        let count = window.count
        var sum: Float = 0
        let points = TriangleTracker.corrPoints
        let step = period / Double(points)
        for m in m0..<(m0 + count) {
            let base = start + Double(m) * period
            for i in 0..<points {
                sum += TriangleTracker.sampleAt(x, base + Double(i) * step) * corrWave[i]
            }
        }
        return sum / Float(count)
    }

    /// Which whole periods around `start` to fold, as `(firstIndex, count)`.
    ///
    /// Forward periods are preferred — they are the freshest audio — and the rest
    /// are taken from behind the window, which is where nearly all the history is.
    private func foldWindow(_ n: Int, _ start: Double, _ period: Double, _ want: Int) -> (m0: Int, count: Int) {
        let forward = max(ktToInt(floor((Double(n - 1) - start) / period)), 1)
        let backward = max(ktToInt(floor(start / period)), 0)
        let fwd = min(forward, want)
        let back = min(backward, want - fwd)
        return (-back, back + fwd)
    }

    /// Removes DC and the first harmonic from one period on the display grid.
    ///
    /// A single-bin projection rather than a filter: the array *is* exactly one
    /// period long, so the fundamental is a known basis vector and subtracting its
    /// projection is exact, with none of a filter's edge effects.
    private static func stripFundamental(_ v: UnsafeMutableBufferPointer<Float>) {
        let n = v.count
        var dc = 0.0
        var cRe = 0.0
        var cIm = 0.0
        for g in 0..<n {
            let u = 2.0 * Double.pi * (Double(g) + 0.5) / Double(n)
            dc += Double(v[g])
            cRe += Double(v[g]) * cos(u)
            cIm += Double(v[g]) * sin(u)
        }
        dc /= Double(n)
        cRe *= 2.0 / Double(n)
        cIm *= 2.0 / Double(n)
        for g in 0..<n {
            let u = 2.0 * Double.pi * (Double(g) + 0.5) / Double(n)
            v[g] = Float(Double(v[g]) - dc - (cRe * cos(u) + cIm * sin(u)))
        }
    }

    /// Folds `want` whole periods around `start` into `folded`. Returns how many it used.
    private func fold(_ raw: UnsafeBufferPointer<Float>, _ start: Double, _ period: Double, _ want: Int) -> Int {
        let grid = NesReading.grid
        let window = foldWindow(raw.count, start, period, want)
        let m0 = window.m0
        let periods = window.count
        folded.update(repeating: 0)
        for m in m0..<(m0 + periods) {
            let base = start + Double(m) * period
            for g in 0..<grid {
                folded[g] += TriangleTracker.sampleAt(raw, base + Double(g) * period / Double(grid))
            }
        }
        let inv = 1 / Float(periods)
        for g in 0..<grid { folded[g] *= inv }
        return periods
    }

    /// Phase of the first harmonic of one period sampled on the display grid.
    private static func fundamentalPhase(_ v: UnsafeBufferPointer<Float>) -> Double {
        var re = 0.0
        var im = 0.0
        for g in 0..<v.count {
            let u = 2.0 * Double.pi * (Double(g) + 0.5) / Double(v.count)
            re += Double(v[g]) * cos(u)
            im += Double(v[g]) * sin(u)
        }
        return atan2(im, re)
    }

    private static func wrapPi(_ a: Double) -> Double {
        var v = a
        while v > Double.pi { v -= 2.0 * Double.pi }
        while v < -Double.pi { v += 2.0 * Double.pi }
        return v
    }

    /// Linear interpolation, clamped at both ends — the same thing a scope does.
    @inline(__always)
    private static func sampleAt(_ x: UnsafeBufferPointer<Float>, _ pos: Double) -> Float {
        if pos <= 0.0 { return x[0] }
        let i = ktToInt(pos)
        if i >= x.count - 1 { return x[x.count - 1] }
        let t = Float(pos - Double(i))
        return x[i] * (1 - t) + x[i + 1] * t
    }

    /// Cents between `timer` and the next timer up — the resolution of the chip's
    /// own tuning grid at this pitch.
    ///
    /// It matters because "the measurement lands on the 2A03 grid" is nearly
    /// meaningless in the bass: at 55 Hz consecutive timer values are 1.7 cents
    /// apart, so *any* frequency down there is within a cent of some timer, region
    /// regardless. Above a kilohertz the same grid is tens of cents wide and the
    /// claim starts to mean something. Reporting the step alongside the error is
    /// what stops the readout implying evidence it does not have.
    private func gridStepCents(_ region: Nes2A03.Region, _ timer: Int) -> Double {
        if timer >= Nes2A03.timerMax { return 0.0 }
        let a = Nes2A03.frequency(region, timer: timer)
        let b = Nes2A03.frequency(region, timer: timer + 1)
        return abs(1200.0 * log(a / b) / log(2.0))
    }

    /// Pitch window. 8192 at 48 kHz is 170 ms — four periods of the lowest note the chip has.
    public static let pitchN = 8192
    private static let fftN = pitchN * 2
    private static let keyMaxRatio: Float = 0.9
    private static let refineSteps = 17
    private static let corrPoints = 128
    private static let maxFold = 48

    /// Full-rate history the tracker needs behind it: eight periods of the
    /// lowest note (27 Hz) plus the pitch window, rounded up.
    public static let history = 32768
}

extension NesReading {
    /// This reading, held: the same lock marked as a copy of an older one.
    func asStale() -> NesReading {
        NesReading(
            locked: true, stale: true, region: region, hz: hz, timer: timer, nesHz: nesHz,
            note: note, cents: cents, gridCents: gridCents, samplesPerStep: samplesPerStep,
            periodSamples: periodSamples, gridStepCents: gridStepCents,
            foldedPeriods: foldedPeriods, clarity: clarity,
            match: match, stepMatch: stepMatch, levelDb: levelDb,
            cycle: cycle, live: live, livePhase0: livePhase0, cycles: cycles, audible: audible
        )
    }
}
