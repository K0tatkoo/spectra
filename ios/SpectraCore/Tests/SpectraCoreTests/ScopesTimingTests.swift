import Foundation
import Testing
import SpectraFixtures
@testable import SpectraCore

/// What the expensive per-frame classes cost, in ms per call. Meaningful only
/// in a release build:
///
///     swift test -c release --filter Timing
///
/// A debug build runs a few iterations so `swift test` stays quick, and says so.
/// Serialized, so the measurements do not compete with each other for cores.
@Suite(.serialized) struct ScopesTimingTests {

    #if DEBUG
    private static let build = "debug build, not representative"
    private static let scale = 30
    #else
    private static let build = "release build"
    private static let scale = 1
    #endif

    /// The tube at phone size, the way the Kotlin bench (desktop/.../dev/CrtShots.kt,
    /// `benchTube`) measures it: a 1000 × 1100 px face at 2.8 px/dp, which the
    /// tube draws at half resolution (500 × 550), 60 frames a simulated second
    /// fed in 1024-frame bursts, the first 300 frames left out.
    @Test func crtAtPhoneSize() throws {
        let f = try Fixture.load("crt")
        // The demo's cube: the Kotlin bench's "cube" scene, the same 2.0 s into the demo.
        let cubeL = f.f32("xy.left")
        let cubeR = f.f32("xy.right")
        let cube: (Int64) -> (Float, Float) = { n in
            let i = Int(n % Int64(cubeL.count))
            return (cubeL[i] * 0.95, cubeR[i] * 0.95)
        }
        var rnd = ScopesRandom(seed: 7)
        let music: (Int64) -> (Float, Float) = { n in
            // Something like a real mix: a bass, chords, hats as noise, the two sides not quite alike.
            let t = Double(n) / 48000
            let bass = 0.35 * sin(2 * Double.pi * 49.0 * t)
            let chord = 0.12 * (sin(2 * Double.pi * 293.7 * t) + sin(2 * Double.pi * 370.0 * t) + sin(2 * Double.pi * 440.0 * t))
            let beat = (t * 4).truncatingRemainder(dividingBy: 1)
            let hat = beat < 0.08 ? rnd.nextGaussian() * 0.12 : 0
            let side = 0.08 * sin(2 * Double.pi * 659.3 * t + 1.1)
            return (Float(bass + chord + hat + side), Float(bass + chord * 0.9 + hat * 0.7 - side))
        }
        var glowOff = Settings()
        glowOff.oscGlow = 0
        var lines: [String] = []
        for (label, s, source) in [
            ("cube, glow on", Settings(), Optional(cube)),
            ("cube, glow off", glowOff, cube),
            ("silence (develop only)", Settings(), nil),
            ("silence, glow off", glowOff, nil),
            ("music, glow on", Settings(), music),
        ] as [(String, Settings, ((Int64) -> (Float, Float))?)] {
            let (ms, w, h) = benchTube(s, source)
            lines.append("Crt, phone size, \(label.padding(toLength: 24, withPad: " ", startingAt: 0))"
                + String(format: "%.3f ms a frame at %d×%d", ms, w, h))
        }
        print("Timing (\(Self.build)):\n  " + lines.joined(separator: "\n  "))
    }

    private func benchTube(_ s: Settings, _ source: ((Int64) -> (Float, Float))?) -> (Double, Int, Int) {
        let rate = 48000
        let feed = StereoFeed(capacityFrames: 32768)
        let crt = Crt()
        let layout = CrtLayout()
        layout.compute(left: 0, top: 0, right: 1000, bottom: 1100, density: 2.8, mode: s.oscMode, compact: false)
        var l = [Float](repeating: 0, count: 1024)
        var r = [Float](repeating: 0, count: 1024)
        var written: Int64 = 0
        var sim: Int64 = 0
        var total: UInt64 = 0
        let frames = 900 / Self.scale
        let skip = 300 / Self.scale
        for k in 0..<frames {
            sim += 1_000_000_000 / 60
            while written + 1024 <= sim * Int64(rate) / 1_000_000_000 {
                if let source {
                    for i in 0..<1024 { (l[i], r[i]) = source(written + Int64(i)) }
                }
                feed.write(left: l, right: r, count: 1024)
                written += 1024
            }
            let t0 = DispatchTime.now().uptimeNanoseconds
            crt.render(feed: feed, rate: rate, settings: s, layout: layout, nowNanos: sim)
            if k >= skip { total += DispatchTime.now().uptimeNanoseconds - t0 }
        }
        return (Double(total) / 1e6 / Double(frames - skip), crt.width, crt.height)
    }

    /// The 2A03 tracker once per frame on a 32768-sample history: a bass under a
    /// two-pulse melody (the tracker fixture's), sliding by 733 samples a frame.
    @Test func triangleTrackerPerFrame() throws {
        let f = try Fixture.load("triangleTracker")
        let raw = f.f32("melody.raw")
        let pitched = f.f32("melody.pitched")
        let n = TriangleTracker.history
        var lines: [String] = []
        for fold in [8, 16, 48] {
            var o = NesOptions()
            o.foldPeriods = fold
            let tracker = TriangleTracker(sampleRate: 48000)
            let frames = 200 / Self.scale
            var total: UInt64 = 0
            var locked = 0
            var rawWin = [Float](repeating: 0, count: n)
            var lowWin = [Float](repeating: 0, count: n)
            for k in 0..<(frames + 20) {
                let start = (k * 733) % (raw.count - n)
                rawWin.withUnsafeMutableBufferPointer { _ = $0.update(fromContentsOf: raw[start ..< start + n]) }
                lowWin.withUnsafeMutableBufferPointer { _ = $0.update(fromContentsOf: pitched[start ..< start + n]) }
                let t0 = DispatchTime.now().uptimeNanoseconds
                let r = tracker.analyze(rawWin, lowWin, dtMs: 15.27, o)
                if k >= 20 {
                    total += DispatchTime.now().uptimeNanoseconds - t0
                    if r?.stale == false { locked += 1 }
                }
            }
            #expect(locked == frames, "the timing ran on a lock")
            lines.append(String(format: "TriangleTracker, fold %2d: %.3f ms a frame", fold, Double(total) / 1e6 / Double(frames)))
        }
        print("Timing (\(Self.build)):\n  " + lines.joined(separator: "\n  "))
    }

    /// One held-still lane per 1024-frame block at 44.1 kHz, the way the Stems
    /// page runs them: a 16384-sample history, 35 ms window, 60 ms clean-up,
    /// 512 points.
    @Test func scopeLockPerBlock() {
        let rate = 44100
        let block = 1024
        let lane = 16384
        let seconds = 3.0
        let total = Int(seconds * Double(rate))
        var noise = ScopesRandom(seed: 5)
        let bass = (0..<total).map { Float(0.3 * ScopesSignals.nesTriangle($0 < total / 2 ? 55 : 82.41, Double($0) / Double(rate)) + 0.01 * noise.nextGaussian()) }
        let vocal = (0..<total).map { Float(0.4 * ScopesSignals.vibrato(330, Double($0) / Double(rate)) + 0.08 * ScopesSignals.saw(196, Double($0) / Double(rate))) }
        let drums = (0..<total).map { Float(0.5 * ScopesSignals.kick(Double($0) / Double(rate), every: 0.4)) }
        func lowPassed(_ x: [Float]) -> [Float] {
            let st = Biquad.butterworthQs(order: 4).map { Biquad.lowPass(sampleRate: rate, freq: 1200, q: $0) }
            return x.map { v in var y = Double(v); for s in st { y = s.process(y) }; return Float(y) }
        }
        var lines: [String] = []
        for (label, spec, raw, free) in [
            ("bass (4096 window)", ScopeLaneSpec(name: "Bass", minHz: 28, maxHz: 300, pitchWindow: 4096, trigger: .pitch), bass, false),
            ("vocal (2048 window)", ScopeLaneSpec(name: "Vocals", minHz: 80, maxHz: 1000, pitchWindow: 2048, trigger: .pitch), vocal, false),
            ("drums (transient)", ScopeLaneSpec(name: "Drums", minHz: 40, maxHz: 400, pitchWindow: 1024, trigger: .transient), drums, false),
            ("bass, free-running", ScopeLaneSpec(name: "Bass", minHz: 28, maxHz: 300, pitchWindow: 4096, trigger: .pitch), bass, true),
        ] {
            let pitched = lowPassed(raw)
            let lock = ScopeLock(sampleRate: rate, spec: spec)
            var a = [Float](repeating: 0, count: lane)
            var b = [Float](repeating: 0, count: lane)
            var free8k = [Float](repeating: 0, count: 8192)
            var spent: UInt64 = 0
            var calls = 0
            var end = lane
            var modes: [TraceMode: Int] = [:]
            while end <= total {
                let t0: UInt64
                let trace: ScopeTrace
                if free {
                    free8k.withUnsafeMutableBufferPointer { _ = $0.update(fromContentsOf: raw[end - 8192 ..< end]) }
                    t0 = DispatchTime.now().uptimeNanoseconds
                    trace = lock.freeRunning(free8k, windowMs: 35, points: 512, dtMs: 23.2)
                } else {
                    a.withUnsafeMutableBufferPointer { _ = $0.update(fromContentsOf: raw[end - lane ..< end]) }
                    b.withUnsafeMutableBufferPointer { _ = $0.update(fromContentsOf: pitched[end - lane ..< end]) }
                    t0 = DispatchTime.now().uptimeNanoseconds
                    trace = lock.analyze(a, b, end: Int64(end), windowMs: 35, cleanMs: 60, points: 512, dtMs: 23.2)
                }
                spent += DispatchTime.now().uptimeNanoseconds - t0
                calls += 1
                modes[trace.mode, default: 0] += 1
                end += block * Self.scale
            }
            let seen = [TraceMode.pitch, .hit, .free, .quiet].compactMap { m in modes[m].map { "\(m) \($0)" } }
            lines.append("ScopeLock, \(label.padding(toLength: 20, withPad: " ", startingAt: 0))"
                + String(format: "%.3f ms a block", Double(spent) / 1e6 / Double(calls))
                + " (\(calls) calls: \(seen.joined(separator: ", ")))")
        }
        print("Timing (\(Self.build)):\n  " + lines.joined(separator: "\n  "))
    }
}
