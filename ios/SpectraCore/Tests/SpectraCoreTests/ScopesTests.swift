import Foundation
import Testing
import SpectraFixtures
@testable import SpectraCore

/// The held-still scopes — `PitchDetector`, `PitchPreFilter` and `ScopeLock` —
/// against what the Kotlin engine computed (IosScopesFixtures.kt), and the
/// Kotlin unit tests for them (app/src/test/.../dsp/ScopeLockTest.kt).
///
/// Where the Swift and the Kotlin can differ at all: `Fft` is vDSP here and a
/// hand-written radix-2 there. Both compute the same transform, but not with
/// the same roundings, so every NSDF value carries a few float ulps of noise
/// (about 4e-7 on a peak of 1, measured). The parabolic peak interpolation
/// divides that by the peak's curvature, about (2π/period)², so the period's
/// relative error grows with the period itself: roughly noise × period / 80,
/// a millionth at 200 Hz and 5e-6 at 50 Hz. Everything that follows a
/// detected period inherits that, and nothing else differs: whatever does not
/// depend on a measured period is bit-exact.
@Suite struct ScopesTests {

    // ---- PitchDetector ---------------------------------------------------------

    @Test func pitchDetectorMatchesKotlin() throws {
        let f = try Fixture.load("pitchDetector")
        let names = f.strings("names")
        let rates = f.ints("rates")
        let windows = f.ints("windows")
        let minHz = f.doubles("minHz")
        let maxHz = f.doubles("maxHz")
        var tau: [Double] = []
        var clarity: [Float] = []
        for (i, name) in names.enumerated() {
            let d = PitchDetector(sampleRate: rates[i], window: windows[i])
            tau.append(d.detect(f.f32("\(name).x"), minHz: Float(minHz[i]), maxHz: Float(maxHz[i])))
            clarity.append(d.clarity)
        }
        // Periods agree to 1.2e-6 of themselves (measured 1.17e-6, on the 220 Hz
        // sine, whose round NSDF peak magnifies the FFT's noise the most; see
        // the suite's notes). The failures (−1) agree exactly.
        #expect(mismatch(tau, f.f64("tau"), abs: 0, rel: 1.2e-6) == nil, "periods")
        // Clarity is the peak height itself, so only the NSDF's own noise:
        // two float ulps at 1.0 (measured 2.4e-7).
        #expect(mismatch(clarity, f.f32("clarity"), abs: 2.5e-7) == nil, "clarity")
    }

    // ---- PitchPreFilter --------------------------------------------------------

    @Test func pitchPreFilterMatchesKotlin() throws {
        let f = try Fixture.load("pitchPreFilter")
        let input = f.f32("input")
        let n = input.count
        let a = PitchPreFilter(sampleRate: 48000, cornerHz: PitchPreFilter.cornerFor(200))
        var chunked = [Float](repeating: 0, count: n)
        var at = 0
        for c in f.ints("chunks") {
            let src = Array(input[at ..< at + c])
            var dst = [Float](repeating: 0, count: c)
            a.process(src, &dst, count: c)
            chunked.replaceSubrange(at ..< at + c, with: dst)
            at += c
        }
        let b = PitchPreFilter(sampleRate: 44100, cornerHz: PitchPreFilter.cornerFor(300))
        var reset = [Float](repeating: 0, count: n)
        for i in 0..<n {
            if i == n / 2 { b.reset() }
            reset[i] = b.process(input[i])
        }
        // Bit for bit: the same Double arithmetic in the same order, and the
        // coefficients' cos/sin come out the same in Darwin's libm and the JVM's.
        #expect(chunked == f.f32("chunked"), "48 kHz, in chunks")
        #expect(reset == f.f32("reset"), "44.1 kHz, reset halfway")
        #expect(f.f32("huntMax").map(PitchPreFilter.cornerFor) == f.f64("cornerFor"))
    }

    // ---- ScopeLock -------------------------------------------------------------

    @Test func scopeLockMatchesKotlin() throws {
        let f = try Fixture.load("scopeLock")
        let rate = f.int("rate")
        let block = f.int("block")
        let lane = f.int("lane")
        let dt = f.float("dtMs")
        let windowMs = f.float("windowMs")
        let cleanMs = f.float("cleanMs")
        let points = f.int("points")
        let triggers: [String: LaneTrigger] = ["PITCH": .pitch, "TRANSIENT": .transient]
        for name in ["bass", "vocal", "drums"] {
            let spec = ScopeLaneSpec(name: name, minHz: f.float("\(name).minHz"), maxHz: f.float("\(name).maxHz"),
                                     pitchWindow: f.int("\(name).pitchWindow"), trigger: triggers[f.string("\(name).trigger")]!)
            let raw = f.f32("\(name).raw")
            let pitched = f.has("\(name).pitched") ? f.f32("\(name).pitched") : raw
            let valid = f.has("\(name).validFrom") ? f.i64("\(name).validFrom") : nil
            let frames = f.i32("\(name).mode").count
            let lock = ScopeLock(sampleRate: rate, spec: spec)
            let traces = (0..<frames).map { k in
                let end = (k + 1) * block
                return lock.analyze(scopesWindow(raw, end: end, n: lane), scopesWindow(pitched, end: end, n: lane),
                                    end: Int64(end), windowMs: windowMs, cleanMs: cleanMs, points: points, dtMs: dt,
                                    validFrom: valid?[k] ?? .min)
            }
            // A drum lane never runs the detector, so its picture is bit-exact.
            try expectTraces(traces, f, name, points: spec.trigger == .pitch ? 2e-4 : 0)
        }
        // The bass stream again, free-running.
        let free = ScopeLock(sampleRate: rate, spec: ScopeLaneSpec(name: "free", minHz: 28, maxHz: 300, pitchWindow: 4096, trigger: .pitch))
        let bass = f.f32("bass.raw")
        let traces = (0..<f.i32("free.mode").count).map { k in
            free.freeRunning(scopesWindow(bass, end: (k + 1) * block, n: f.int("free")), windowMs: windowMs, points: points, dtMs: dt)
        }
        // No detector, no phase, no fold: bit-exact.
        try expectTraces(traces, f, "free", points: 0)
    }

    private func expectTraces(_ traces: [ScopeTrace], _ f: Fixture, _ name: String, points tolerance: Float) throws {
        let modes: [TraceMode] = [.pitch, .hit, .free, .quiet]
        #expect(traces.map { modes.firstIndex(of: $0.mode)! } == f.i32("\(name).mode").map(Int.init), "\(name) modes")
        #expect(traces.map(\.note) == f.strings("\(name).notes"), "\(name) notes")
        #expect(traces.map { Int32($0.folded) } == f.i32("\(name).folded"), "\(name) folds")
        #expect(traces.map { $0.stale ? 1 : 0 } == f.i32("\(name).stale").map(Int.init), "\(name) stale")
        #expect(traces.map(\.windowMs) == f.f32("\(name).windowMs"), "\(name) window")
        #expect(traces.map { Int32($0.points.count) } == f.i32("\(name).pointCount"), "\(name) point counts")
        // Hz is the sample rate over the detected period: the period's relative
        // error carried straight over. Measured 3.8e-6 on the 55 Hz bass, the
        // longest period and so the most magnified (see the suite's notes).
        #expect(mismatch(traces.map(\.hz), f.f32("\(name).hz"), abs: 0, rel: 4e-6) == nil, "\(name) Hz")
        // The NSDF peak height: three float ulps at 1.0 (measured 3e-7).
        #expect(mismatch(traces.map(\.clarity), f.f32("\(name).clarity"), abs: 4e-7) == nil, "\(name) clarity")
        // The RMS of the same samples in the same order: bit-exact.
        #expect(traces.map(\.levelDb) == f.f32("\(name).levelDb"), "\(name) level")
        // The picture. A period 4e-6 off moves the anchor by a few thousandths
        // of a sample, and each point by that times the wave's slope times the
        // lane's gain: worst on the staircase's step edges, measured 1.8e-4 on
        // a lane that spans ±0.9 — a ten-thousandth of its height.
        let got = traces.flatMap(\.points)
        let want = f.f32("\(name).points")
        #expect(mismatch(got, want, abs: tolerance) == nil, "\(name) points")
    }

    // ---- ScopeLockTest.kt ------------------------------------------------------
    //
    // The held-still scope, fed the way the app feeds it: blocks of audio into a
    // HistoryRing, one analysis per block. "Still" is measured directly — the
    // trace from one frame against the next. Both are normalised to about ±1, so
    // an RMS difference of 0.05 is a few pixels on a lane a hundred pixels tall.

    private let rate = 44100
    private let block = 1882

    @Test func aHeldNoteStandsStill() {
        var noise = ScopesRandom(seed: 1)
        let frames = run(2.0, spec(28, 300)) { t in 0.3 * ScopesSignals.nesTriangle(110, t) + 0.01 * noise.nextGaussian() }
        let locked = Array(frames.dropFirst(20))
        #expect(locked.allSatisfy { $0.mode == .pitch })
        #expect(abs(Double(locked.last!.hz) - 110.0) <= 110.0 * 0.005)
        #expect(locked.last!.note == "A2")
        let worst = zip(locked, locked.dropFirst()).map { rmsDiff($0.points, $1.points) }.max()!
        print("held note: worst frame-to-frame RMS change \(worst)")
        #expect(worst < 0.05, "picture moves between frames (\(worst))")
    }

    @Test func aNoteStaysStillUnderBleedFromAnotherInstrument() {
        // A vibrato tone 15 dB under the bass — the kind of leftover a
        // separated stem really carries. The default clean-up has to hold the
        // bass still through it.
        let frames = run(2.0, spec(28, 300)) { t in
            0.3 * ScopesSignals.nesTriangle(73.42, t) + 0.05 * ScopesSignals.vibrato(523.0, t)
        }
        let locked = Array(frames.dropFirst(25))
        #expect(locked.allSatisfy { $0.mode == .pitch })
        let worst = zip(locked, locked.dropFirst()).map { rmsDiff($0.points, $1.points) }.max()!
        print("with bleed: worst frame-to-frame RMS change \(worst)")
        #expect(worst < 0.08, "bleed drags the picture (\(worst))")
    }

    @Test func aHigherNoteSqueezesMoreCyclesIntoTheSameWindow() {
        let low = run(1.0, spec(28, 1000)) { t in 0.3 * ScopesSignals.saw(110, t) }.last!
        let high = run(1.0, spec(28, 1000)) { t in 0.3 * ScopesSignals.saw(220, t) }.last!
        let cLow = cycles(low.points)
        let cHigh = cycles(high.points)
        print("cycles in window: 110 Hz \(cLow), 220 Hz \(cHigh)")
        #expect(abs(Double(cHigh) / Double(cLow) - 2.0) <= 0.35)
    }

    @Test func aNewNoteIsFollowed() {
        let frames = run(2.0, spec(28, 300)) { t in
            let hz = t < 1.0 ? 55.0 : 82.41
            return 0.3 * ScopesSignals.nesTriangle(hz, t)
        }
        #expect(frames[frames.count / 2 - 3].note == "A1")
        #expect(frames.last!.note == "E2")
    }

    @Test func silenceIsQuietNotAmplifiedNoise() {
        var noise = ScopesRandom(seed: 2)
        let frames = run(0.5, spec(28, 300)) { _ in 1e-5 * noise.nextGaussian() }
        #expect(frames.allSatisfy { $0.mode == .quiet })
    }

    @Test func drumsFreezeOnEachHit() {
        let frames = run(2.0, ScopeLaneSpec(name: "Drums", minHz: 40, maxHz: 400, pitchWindow: 1024, trigger: .transient)) { t in
            0.5 * ScopesSignals.kick(t, every: 0.4)
        }
        let hits = frames.dropFirst(10).filter { $0.mode == .hit }
        #expect(hits.count > frames.count / 2, "drum lane never triggered")
        // Consecutive frames on the same hit are the same picture.
        var same = 0
        var total = 0
        let tail = Array(frames.dropFirst(10))
        for (a, b) in zip(tail, tail.dropFirst()) where a.mode == .hit && b.mode == .hit {
            total += 1
            if rmsDiff(a.points, b.points) < 0.02 { same += 1 }
        }
        print("drums: \(same) of \(total) consecutive hit frames identical")
        // A new hit replaces the picture a few times a second; everything else holds.
        #expect(Double(same) > Double(total) * 0.7)
    }

    @Test func aFreeRunningLaneShowsTheNewestAudioAndNeverLocks() {
        let lock = ScopeLock(sampleRate: rate, spec: spec(28, 300))
        let raw = HistoryRing(capacity: 65536)
        var a = [Float](repeating: 0, count: 8192)
        var chunk = [Float](repeating: 0, count: block)
        var frames: [ScopeTrace] = []
        var i = 0
        while i < rate {
            for k in 0..<block { chunk[k] = Float(0.3 * ScopesSignals.saw(110, Double(i + k) / Double(rate))) }
            raw.write(chunk, offset: 0, count: block)
            i += block
            #expect(raw.read(end: raw.written, into: &a, count: a.count))
            frames.append(lock.freeRunning(a, windowMs: 35, points: 512, dtMs: Float(block) * 1000 / Float(rate)))
        }
        #expect(frames.allSatisfy { $0.mode == .free && $0.folded == 1 && !$0.stale })
        // The same window a held lane shows: 35 ms of 110 Hz is 3.85 cycles.
        let c = cycles(frames.last!.points)
        #expect((3...4).contains(c), "cycles in window: \(c)")
        // Not held: a block is 4.69 periods long, so each frame starts at a
        // different point of the wave and the picture moves.
        let tail = Array(frames.dropFirst(5))
        let diffs = zip(tail, tail.dropFirst()).map { rmsDiff($0.points, $1.points) }
        let moved = diffs.reduce(0, +) / Double(diffs.count)
        print("free-running: mean frame-to-frame RMS change \(moved)")
        #expect(moved > 0.2, "a free lane is standing still (\(moved))")
        // The gain still settles to fill the lane, as it does for a held one.
        #expect(abs(Double(frames.last!.points.map(abs).max()!) - 0.9) <= 0.1)

        let silent = ScopeLock(sampleRate: rate, spec: spec(28, 300))
            .freeRunning([Float](repeating: 0, count: 8192), windowMs: 35, points: 512, dtMs: 20)
        #expect(silent.mode == .quiet)
    }

    private func spec(_ minHz: Float, _ maxHz: Float) -> ScopeLaneSpec {
        ScopeLaneSpec(name: "Test", minHz: minHz, maxHz: maxHz, pitchWindow: 4096, trigger: .pitch)
    }

    private func run(_ seconds: Double, _ spec: ScopeLaneSpec, cleanMs: Float = 60,
                     _ signal: (Double) -> Double) -> [ScopeTrace] {
        let lock = ScopeLock(sampleRate: rate, spec: spec)
        let raw = HistoryRing(capacity: 65536)
        let low = HistoryRing(capacity: 65536)
        let filter = Biquad.butterworthQs(order: 4).map { Biquad.lowPass(sampleRate: rate, freq: 1200, q: $0) }
        var a = [Float](repeating: 0, count: 16384)
        var b = [Float](repeating: 0, count: 16384)
        var chunk = [Float](repeating: 0, count: block)
        var lp = [Float](repeating: 0, count: block)
        var out: [ScopeTrace] = []
        var i = 0
        let total = Int(seconds * Double(rate))
        while i < total {
            for k in 0..<block {
                chunk[k] = Float(signal(Double(i + k) / Double(rate)))
                var v = Double(chunk[k])
                for f in filter { v = f.process(v) }
                lp[k] = Float(v)
            }
            raw.write(chunk, offset: 0, count: block)
            low.write(lp, offset: 0, count: block)
            i += block
            let end = raw.written
            #expect(raw.read(end: end, into: &a, count: a.count) && low.read(end: end, into: &b, count: b.count))
            out.append(lock.analyze(a, b, end: end, windowMs: 35, cleanMs: cleanMs, points: 512,
                                    dtMs: Float(block) * 1000 / Float(rate)))
        }
        return out
    }

    private func rmsDiff(_ a: [Float], _ b: [Float]) -> Double {
        var s = 0.0
        for i in a.indices {
            let d = Double(a[i] - b[i])
            s += d * d
        }
        return (s / Double(a.count)).squareRoot()
    }

    /// Rising zero crossings — whole cycles on screen.
    private func cycles(_ p: [Float]) -> Int {
        var n = 0
        for i in 1..<p.count where p[i - 1] < 0 && p[i] >= 0 { n += 1 }
        return n
    }
}

// ---- shared by the scopes, tracker, beam and CRT tests -------------------------

/// `stream[end − n, end)`, zero outside the stream: what IosScopesFixtures hands
/// each call, and what a `HistoryRing` returns before its first sample.
func scopesWindow(_ stream: [Float], end: Int, n: Int) -> [Float] {
    [Float](unsafeUninitializedCapacity: n) { buf, count in
        for k in 0..<n {
            let i = end - n + k
            buf[k] = i >= 0 && i < stream.count ? stream[i] : 0
        }
        count = n
    }
}

/// Deterministic noise for the ported unit tests. Not java.util.Random's
/// sequence: those tests' assertions are statistical, so any fixed seed does.
struct ScopesRandom {
    private var s: UInt64

    init(seed: UInt64) { s = seed &* 0x9E37_79B9_7F4A_7C15 | 1 }

    /// Uniform in [0, 1).
    mutating func nextDouble() -> Double {
        s ^= s << 13
        s ^= s >> 7
        s ^= s << 17
        return Double(s >> 11) / Double(UInt64(1) << 53)
    }

    /// Standard normal, by Box–Muller.
    mutating func nextGaussian() -> Double {
        let u = max(nextDouble(), 1e-300)
        let v = nextDouble()
        return (-2 * log(u)).squareRoot() * cos(2 * Double.pi * v)
    }
}

/// Synthetic sources the ported tests build mixes from (app/src/test/.../TestSignals.kt). All mono, −1…1.
enum ScopesSignals {
    static func saw(_ hz: Double, _ t: Double) -> Double { 2.0 * (t * hz - floor(0.5 + t * hz)) }

    /// The 2A03's 32-step triangle — the staircase, not a straight ramp.
    static func nesTriangle(_ hz: Double, _ t: Double) -> Double {
        var u = (t * hz).truncatingRemainder(dividingBy: 1)
        if u < 0 { u += 1 }
        let step = coerceIn(Int(u * 32), 0, 31)
        let level = step < 16 ? 15 - step : step - 16
        return (Double(level) - 7.5) / 7.5
    }

    /// A sung-ish tone: a sine with a 5 Hz vibrato of ±6 Hz.
    static func vibrato(_ hz: Double, _ t: Double) -> Double { sin(2 * Double.pi * hz * t - 1.2 * cos(2 * Double.pi * 5 * t)) }

    /// An 808-ish kick every `every` seconds: a falling sine with a fast decay.
    static func kick(_ t: Double, every: Double = 0.5) -> Double {
        let tt = t.truncatingRemainder(dividingBy: every)
        if tt > 0.25 { return 0 }
        return sin(2 * Double.pi * (50 * tt + 80 * (1 - exp(-tt * 30)) / 30)) * exp(-tt * 12)
    }
}
