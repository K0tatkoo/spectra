import Foundation
import Testing
import SpectraFixtures
@testable import SpectraCore

/// The 2A03 triangle tracker against what the Kotlin engine computed
/// (IosScopesFixtures.kt), and the results the desktop harness
/// desktop/.../dev/TrackerCheck.kt pins down — measured, hard-won numbers about
/// what the tracker can and cannot tell apart.
@Suite struct TrackerTests {

    @Test func trackerMatchesKotlin() throws {
        let f = try Fixture.load("triangleTracker")
        let n = f.int("history")
        let regions = Dictionary(uniqueKeysWithValues: Nes2A03.Region.allCases.map { ($0.rawValue, $0) })
        for name in f.strings("names") {
            var o = NesOptions()
            o.region = regions[f.string("\(name).region")]!
            o.lockToTimer = f.bool("\(name).lockToTimer")
            o.cycles = f.int("\(name).cycles")
            o.huntMinHz = f.float("\(name).huntMinHz")
            o.huntMaxHz = f.float("\(name).huntMaxHz")
            o.clarityThreshold = f.float("\(name).clarityThreshold")
            o.averaging = f.bool("\(name).averaging")
            o.foldPeriods = f.int("\(name).foldPeriods")
            o.persistence = f.float("\(name).persistence")
            o.holdMs = f.float("\(name).holdMs")
            let raw = f.f32("\(name).raw")
            let pitched = f.f32("\(name).pitched")
            let ends = f.i32("\(name).ends").map(Int.init)
            let dts = f.f32("\(name).dts")
            let tracker = TriangleTracker(sampleRate: 48000)
            let readings = ends.indices.map { k in
                tracker.analyze(scopesWindow(raw, end: ends[k], n: n), scopesWindow(pitched, end: ends[k], n: n), dtMs: dts[k], o)
            }
            try expectReadings(readings, f, name, lockToTimer: o.lockToTimer)
        }
    }

    private func expectReadings(_ r: [NesReading?], _ f: Fixture, _ name: String, lockToTimer: Bool) throws {
        func ints(_ get: (NesReading) -> Int) -> [Int32] { r.map { Int32($0.map(get) ?? 0) } }
        func bools(_ get: (NesReading) -> Bool) -> [Int32] { r.map { ($0.map(get) ?? false) ? 1 : 0 } }
        func f32s(_ get: (NesReading) -> Float) -> [Float] { r.map { $0.map(get) ?? 0 } }
        func f64s(_ get: (NesReading) -> Double) -> [Double] { r.map { $0.map(get) ?? 0 } }

        // Everything structural is exact: which frames lock, hold or let go,
        // the timer, the fold depth, the note, how many points are drawn.
        #expect(r.map { $0 == nil ? Int32(0) : 1 } == f.i32("\(name).present"), "\(name) present")
        #expect(bools(\.locked) == f.i32("\(name).locked"), "\(name) locked")
        #expect(bools(\.stale) == f.i32("\(name).stale"), "\(name) stale")
        #expect(ints(\.timer) == f.i32("\(name).timer"), "\(name) timer")
        #expect(ints(\.foldedPeriods) == f.i32("\(name).foldedPeriods"), "\(name) folded periods")
        #expect(ints(\.cycles) == f.i32("\(name).cycles"), "\(name) cycles")
        #expect(bools(\.audible) == f.i32("\(name).audible"), "\(name) audible")
        #expect(ints { $0.cycle.count } == f.i32("\(name).cycleCount"), "\(name) cycle lengths")
        #expect(ints { $0.live.count } == f.i32("\(name).liveCount"), "\(name) live lengths")
        #expect(r.map { $0?.note ?? "" } == f.strings("\(name).notes"), "\(name) notes")

        // Pure functions of the timer, Double arithmetic and log: bit-exact.
        #expect(f64s(\.nesHz) == f.f64("\(name).nesHz"), "\(name) chip Hz")
        #expect(f64s(\.cents) == f.f64("\(name).cents"), "\(name) cents")
        #expect(f64s(\.samplesPerStep) == f.f64("\(name).samplesPerStep"), "\(name) samples/step")
        #expect(f64s(\.gridStepCents) == f.f64("\(name).gridStepCents"), "\(name) grid step")

        // From the detected period. The FFT's float noise (vDSP against the
        // Kotlin radix-2; ScopesTests explains its size) goes through the NSDF
        // parabola and comes out as up to 5.6e-6 of the frequency on these
        // 50–110 Hz notes (measured on the PAL bass, the longest period). That
        // is 0.0097 cents, which is all the grid-cents readout moves by.
        #expect(mismatch(f64s(\.hz), f.f64("\(name).hz"), abs: 0, rel: 6e-6) == nil, "\(name) measured Hz")
        #expect(mismatch(f64s(\.gridCents), f.f64("\(name).gridCents"), abs: 0.01) == nil, "\(name) grid cents")
        // The NSDF peak height: three float ulps at 1.0 (measured 3.6e-7).
        #expect(mismatch(f32s(\.clarity), f.f32("\(name).clarity"), abs: 4e-7) == nil, "\(name) clarity")

        let cycle = r.flatMap { $0?.cycle ?? [] }
        let live = r.flatMap { $0?.live ?? [] }
        if lockToTimer {
            // Locked to the timer grid, the display period is the timer's own and
            // nothing downstream sees the measurement: the phase, the fold, the
            // fit and both traces are bit-exact. A difference here is a real change.
            #expect(f64s(\.periodSamples) == f.f64("\(name).periodSamples"), "\(name) period")
            #expect(f32s(\.match) == f.f32("\(name).match"), "\(name) match")
            #expect(f32s(\.stepMatch) == f.f32("\(name).stepMatch"), "\(name) stepMatch")
            #expect(f32s(\.levelDb) == f.f32("\(name).levelDb"), "\(name) level")
            #expect(f32s(\.livePhase0) == f.f32("\(name).livePhase0"), "\(name) live phase")
            #expect(cycle == f.f32("\(name).cycle"), "\(name) folded cycle")
            #expect(live == f.f32("\(name).live"), "\(name) live trace")
        } else {
            // Unlocked, the display period is the measured one (1.2e-6 of itself
            // apart, measured at 110 Hz), and the phase and the fold grid move
            // with it: a few float ulps in the fit and the traces (measured
            // 6e-7 in stepMatch and the phase, 1.2e-6 in the live trace).
            #expect(mismatch(f64s(\.periodSamples), f.f64("\(name).periodSamples"), abs: 0, rel: 2e-6) == nil, "\(name) period")
            #expect(mismatch(f32s(\.match), f.f32("\(name).match"), abs: 2e-6) == nil, "\(name) match")
            #expect(mismatch(f32s(\.stepMatch), f.f32("\(name).stepMatch"), abs: 2e-6) == nil, "\(name) stepMatch")
            // 20·log10 turns 1e-6 of amplitude into 9e-6 dB (measured 9.8e-6).
            #expect(mismatch(f32s(\.levelDb), f.f32("\(name).levelDb"), abs: 1.2e-5) == nil, "\(name) level")
            #expect(mismatch(f32s(\.livePhase0), f.f32("\(name).livePhase0"), abs: 2e-6) == nil, "\(name) live phase")
            #expect(mismatch(cycle, f.f32("\(name).cycle"), abs: 2e-6) == nil, "\(name) folded cycle")
            #expect(mismatch(live, f.f32("\(name).live"), abs: 2e-6) == nil, "\(name) live trace")
        }
    }

    // ---- TrackerCheck.kt -------------------------------------------------------

    private static let sr = 48000
    private static let n = TriangleTracker.history

    @Test func aCleanTriangleAtA1() {
        let opts = Self.opts()
        let timer = Nes2A03.timerFor(.ntsc, hz: 55.0)
        let expectedHz = Nes2A03.frequency(.ntsc, timer: timer)
        let audio = Self.renderTriangle(timer, .ntsc, Self.n + 20000)
        let tracker = TriangleTracker(sampleRate: Self.sr)
        var prev: [Float]? = nil
        var maxDrift = 0
        var reading: NesReading? = nil
        // Advance by an awkward, non-periodic number of samples each frame, the
        // way a real capture chunk would.
        for f in 0..<24 {
            let win = Array(audio[f * 733 ..< f * 733 + Self.n])
            reading = tracker.analyze(win, Self.pitched(win, opts), dtMs: 16, opts)
            if f >= 4, let r = reading {
                if let p = prev { maxDrift = max(maxDrift, abs(Self.shiftBetween(p, r.cycle))) }
                prev = r.cycle
            }
        }
        guard let r = reading else { Issue.record("no reading"); return }
        print("A1: clarity \(r.clarity), match \(r.match), stepMatch \(r.stepMatch), level \(r.levelDb), drift \(maxDrift)")
        #expect(r.locked)
        #expect(r.timer == timer)
        #expect(abs(r.hz - expectedHz) < 0.05)
        #expect(abs(r.gridCents) < 2.0, "on the 2A03 grid")
        #expect(r.match > 0.97, "matches the ideal wave")
        #expect(r.stepMatch > 0.99, "staircase detected")
        #expect((1.0...2.5).contains(r.gridStepCents), "grid step reported")
        #expect(abs(r.levelDb) < 0.6)
        #expect(abs(r.samplesPerStep - 27.3) < 0.2)
        #expect(r.note == "A1")
        #expect(maxDrift <= 1, "stationary: max drift \(maxDrift)/\(NesReading.grid) of a period")

        // The ceiling, for reference: the same note sampled straight off the DAC
        // with no anti-alias filter and no resampling. Whatever a real capture
        // scores, it cannot beat this, and the gap is the capture chain's, not
        // the tracker's.
        let direct = Self.direct(timer, Self.n + 4000)
        let t2 = TriangleTracker(sampleRate: Self.sr)
        var dr: NesReading? = nil
        for f in 0..<6 {
            let win = Array(direct[f * 500 ..< f * 500 + Self.n])
            dr = t2.analyze(win, Self.pitched(win, opts), dtMs: 16, opts)
        }
        #expect((dr?.stepMatch ?? 0) > 0.99, "unfiltered DAC ceiling")
    }

    @Test func aTriangleUnderAPulseMelody() {
        let opts = Self.opts()
        let timer = Nes2A03.timerFor(.ntsc, hz: 73.4)
        let len = Self.n + 20000
        let tri = Self.renderTriangle(timer, .ntsc, len)
        // Ratios of 5.66 and 7.55 to the bass: as far from harmonics as notes get.
        let p1 = Self.renderPulse(415.30, 0.5, 0.45, len)
        let p2 = Self.renderPulse(554.37, 0.25, 0.35, len)
        var rnd = ScopesRandom(seed: 7)
        let mix = (0..<len).map { (tri[$0] * 0.55 + p1[$0] + p2[$0] + (Float(rnd.nextDouble()) - 0.5) * 0.05) * 0.45 }

        func run(_ o: NesOptions) -> (NesReading?, Int) {
            let tracker = TriangleTracker(sampleRate: Self.sr)
            var reading: NesReading? = nil
            var prev: [Float]? = nil
            var drift = 0
            for f in 0..<24 {
                let win = Array(mix[f * 733 ..< f * 733 + Self.n])
                reading = tracker.analyze(win, Self.pitched(win, o), dtMs: 16, o)
                if f >= 6, let r = reading {
                    if let p = prev { drift = max(drift, abs(Self.shiftBetween(p, r.cycle))) }
                    prev = r.cycle
                }
            }
            return (reading, drift)
        }

        let (r, _) = run(opts)
        #expect(r?.timer == timer, "locks to the bass")
        #expect((r?.match ?? 0) > 0.8, "fits the triangle")
        // Measured with deep folding on purpose. At shallow depths the picture
        // legitimately changes between frames as the melody slides through it,
        // and a cross-correlation cannot tell that apart from the lock drifting.
        var deep = opts
        deep.foldPeriods = 32
        let (_, deepDrift) = run(deep)
        #expect(deepDrift <= 2, "stationary: max drift \(deepDrift)")

        // Rejection goes as the square root of the fold depth, so the staircase
        // has to come further out of the mix at every step. If it does not, the
        // fold is not doing what the comment claims it does.
        var o2 = opts
        o2.foldPeriods = 2
        var o8 = opts
        o8.foldPeriods = 8
        let shallow = run(o2).0?.stepMatch ?? 0
        let mid = run(o8).0?.stepMatch ?? 0
        let deepMatch = run(deep).0?.stepMatch ?? 0
        print("under a melody: stepMatch \(shallow) → \(mid) → \(deepMatch) at 2/8/32 periods")
        #expect(shallow < mid && mid < deepMatch, "deeper folding digs it out")
    }

    @Test func aMelodyNoteThatIsAHarmonicCannotBeUnmixed() {
        var opts = Self.opts()
        opts.foldPeriods = 32
        let timer = Nes2A03.timerFor(.ntsc, hz: 73.4)
        let bassHz = Nes2A03.frequency(.ntsc, timer: timer)
        let len = Self.n + 8000
        let tri = Self.renderTriangle(timer, .ntsc, len)
        let p1 = Self.renderPulse(bassHz * 8.0, 0.5, 0.5, len)
        let mix = (0..<len).map { (tri[$0] * 0.6 + p1[$0]) * 0.5 }
        let tracker = TriangleTracker(sampleRate: Self.sr)
        var r: NesReading? = nil
        for f in 0..<10 {
            let win = Array(mix[f * 733 ..< f * 733 + Self.n])
            r = tracker.analyze(win, Self.pitched(win, opts), dtMs: 16, opts)
        }
        // A signal that repeats at exactly the bass period is, to any method that
        // works in the period domain, part of the bass. No amount of folding
        // separates them, and the display correctly shows the sum. This is pinned
        // down so nobody later "fixes" the fold to chase it.
        #expect(r?.timer == timer, "still finds the period")
        #expect((r?.stepMatch ?? 1) < 0.5, "but cannot unmix it — by construction, not by defect")
    }

    @Test func thePalRegionChangesTheRegister() {
        let opts = Self.opts()
        let timer = 1016
        let audio = Self.renderTriangle(timer, .pal, Self.n + 4000)
        var pal = opts
        pal.region = .pal
        let tracker = TriangleTracker(sampleRate: Self.sr)
        var r: NesReading? = nil
        for f in 0..<8 {
            let win = Array(audio[f * 500 ..< f * 500 + Self.n])
            r = tracker.analyze(win, Self.pitched(win, opts), dtMs: 16, pal)
        }
        #expect(r?.timer == timer, "PAL timer")
        // The same register value read as NTSC must come out as a different,
        // non-integer timer — that is the whole point of the region switch.
        let trackerN = TriangleTracker(sampleRate: Self.sr)
        var rn: NesReading? = nil
        for f in 0..<8 {
            let win = Array(audio[f * 500 ..< f * 500 + Self.n])
            rn = trackerN.analyze(win, Self.pitched(win, opts), dtMs: 16, opts)
        }
        #expect(rn?.timer == 1094, "region changes the register")
        // Not a bug: at 51 Hz the NTSC grid is ~1.8 cents wide, so the wrong
        // region still lands within a cent of *some* timer. The tuning error
        // cannot reveal the mistake down here; only the register value does.
        #expect(abs(rn?.gridCents ?? 9) < 1.0, "bass grid is too fine to tell")
    }

    @Test func itRejectsWhatItShould() {
        let opts = Self.opts()
        let silence = [Float](repeating: 0, count: Self.n)
        #expect(TriangleTracker(sampleRate: Self.sr).analyze(silence, silence, dtMs: 16, opts) == nil, "silence does not lock")

        var rnd = ScopesRandom(seed: 3)
        let noise = (0..<Self.n).map { _ in (Float(rnd.nextDouble()) - 0.5) * 0.5 }
        let t2 = TriangleTracker(sampleRate: Self.sr)
        var got: NesReading? = nil
        for _ in 0..<3 { got = t2.analyze(noise, Self.pitched(noise, opts), dtMs: 16, opts) }
        #expect(got == nil, "white noise does not lock (clarity \(got?.clarity ?? 0))")

        // A sine at the same pitch locks (it is periodic) but must not claim to
        // be a 2A03 — that is what `match` is for.
        let sine = (0..<Self.n).map { Float(sin(2.0 * Double.pi * 55.0 * Double($0) / Double(Self.sr))) * 0.7 }
        let t3 = TriangleTracker(sampleRate: Self.sr)
        var sr: NesReading? = nil
        for _ in 0..<3 { sr = t3.analyze(sine, Self.pitched(sine, opts), dtMs: 16, opts) }
        print("sine: match \(sr?.match ?? -1), stepMatch \(sr?.stepMatch ?? -1)")
        #expect((sr?.match ?? 0) > 0.98, "a sine still correlates — which is why match alone proves nothing")
        #expect(sr != nil && sr!.stepMatch < 0.3, "but has no staircase")
    }

    /// The phase-alignment check.
    ///
    /// This is the one that fails if the fold-phase correction in the tracker is ever
    /// removed: aligning on the fundamental alone leaves the 31st harmonic — where the
    /// staircase's corners live — forty degrees out, while every amplitude still looks
    /// perfect. Amplitudes cannot catch it; only phases can.
    @Test func everyHarmonicIsAlignedNotJustTheFundamental() throws {
        let opts = Self.opts()
        let timer = Nes2A03.timerFor(.ntsc, hz: 55.0)
        let grid = NesReading.grid
        let idealGrid = (0..<grid).map { Nes2A03.levelAt((Float($0) + 0.5) / Float(grid)) }
        let direct = Self.direct(timer, Self.n + 4000)
        let tr = TriangleTracker(sampleRate: Self.sr)
        var r: NesReading? = nil
        for k in 0..<6 {
            let win = Array(direct[k * 500 ..< k * 500 + Self.n])
            r = tr.analyze(win, Self.pitched(win, opts), dtMs: 16, opts)
        }
        let cycle = try #require(r).cycle
        var worst = 0.0
        var worstH = 0
        for h in [1, 3, 5, 7, 9, 29, 31, 33, 63, 65] {
            let m = Self.harmonic(cycle, h)
            let i2 = Self.harmonic(idealGrid, h)
            var d = m.phaseDeg - i2.phaseDeg
            while d > 180 { d -= 360 }
            while d < -180 { d += 360 }
            if abs(d) > worst { worst = abs(d); worstH = h }
        }
        print("harmonic alignment: worst \(worst) deg at h\(worstH)")
        #expect(worst < 1.0, "all harmonics in phase: worst \(worst) deg at h\(worstH)")
        let h31 = Self.harmonic(cycle, 31).amplitude
        let i31 = Self.harmonic(idealGrid, 31).amplitude
        #expect(abs(h31 - i31) / i31 < 0.05, "step-rate harmonics present: \(h31) vs ideal \(i31)")
    }

    // ---- the harness's renderers ----------------------------------------------

    private static func opts() -> NesOptions {
        var o = NesOptions()
        o.persistence = 0
        o.cycles = 2
        return o
    }

    private static let oversample = 8

    /// A 2A03 triangle rendered the way the chip renders it: zero-order hold at CPU
    /// resolution, oversampled, low-passed, decimated. Not band-limited synthesis —
    /// the aliasing is part of what a capture actually contains.
    private static func renderTriangle(_ timer: Int, _ region: Nes2A03.Region, _ samples: Int) -> [Float] {
        let cyclesPerHiSample = region.cpuHz / (Double(sr) * Double(oversample))
        let hi = (0..<(samples * oversample)).map { i -> Float in
            let cycle = Double(i) * cyclesPerHiSample
            let step = (Int64(cycle / (Double(timer) + 1.0)) % 32 + 32) % 32
            return Nes2A03.wave[Int(step)]
        }
        return decimate(hi, samples)
    }

    private static func renderPulse(_ hz: Double, _ duty: Float, _ amp: Float, _ samples: Int) -> [Float] {
        let inc = hz / (Double(sr) * Double(oversample))
        var phase = 0.0
        let hi = (0..<(samples * oversample)).map { _ -> Float in
            let v = phase < Double(duty) ? amp : -amp
            phase += inc
            if phase >= 1.0 { phase -= 1.0 }
            return v
        }
        return decimate(hi, samples)
    }

    private static func decimate(_ hi: [Float], _ samples: Int) -> [Float] {
        // Two Butterworth sections at 18 kHz, then take every oversample-th sample.
        let stages = Biquad.butterworthQs(order: 4).map { Biquad.lowPass(sampleRate: sr * oversample, freq: 18000, q: $0) }
        var out = [Float](repeating: 0, count: samples)
        for i in hi.indices {
            var v = Double(hi[i])
            for s in stages { v = s.process(v) }
            if i % oversample == 0 && i / oversample < samples { out[i / oversample] = Float(v) }
        }
        return out
    }

    /// Straight off the DAC: no anti-alias filter, no resampling.
    private static func direct(_ timer: Int, _ count: Int) -> [Float] {
        (0..<count).map { i in
            let cyc = Double(i) * Nes2A03.Region.ntsc.cpuHz / Double(sr)
            return Nes2A03.wave[Int(Int64(cyc / (Double(timer) + 1.0)) % 32)]
        }
    }

    /// The same pre-filter the engine runs, so the harness tests what ships.
    /// Fresh per window, as the harness does it.
    private static func pitched(_ x: [Float], _ o: NesOptions) -> [Float] {
        let f = PitchPreFilter(sampleRate: sr, cornerHz: PitchPreFilter.cornerFor(o.huntMaxHz))
        return x.map { f.process($0) }
    }

    /// Grid-point shift between two folded periods, by cross-correlation. Drift, in grid units.
    private static func shiftBetween(_ a: [Float], _ b: [Float]) -> Int {
        let n = a.count
        var bestLag = 0
        var best = -Double.infinity
        for lag in -64...64 {
            var s = 0.0
            for i in stride(from: 0, to: n, by: 4) {
                s += Double(a[i] * b[((i + lag) % n + n) % n])
            }
            if s > best { best = s; bestLag = lag }
        }
        return bestLag
    }

    private static func harmonic(_ v: [Float], _ h: Int) -> (amplitude: Double, phaseDeg: Double) {
        var re = 0.0
        var im = 0.0
        for g in v.indices {
            let u = 2.0 * Double.pi * Double(h) * (Double(g) + 0.5) / Double(v.count)
            re += Double(v[g]) * cos(u)
            im += Double(v[g]) * sin(u)
        }
        return (2.0 * hypot(re, im) / Double(v.count), atan2(im, re) * 180 / Double.pi)
    }
}
