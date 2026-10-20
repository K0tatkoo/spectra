import Foundation
import Testing
import SpectraFixtures
@testable import SpectraCore

/// The Oscilloscope page's beam, before any pixels: the interpolation it draws,
/// the clock that paces it through bursty capture, and the Y-T trigger —
/// against the Kotlin engine (IosScopesFixtures.kt), and the Kotlin unit tests
/// (app/src/test/.../dsp/BeamTest.kt).
@Suite struct BeamTests {

    private let rate = 48000

    @Test func interpolatorMatchesKotlin() throws {
        let f = try Fixture.load("beam")
        let count = f.int("interpCount")
        var out = [Float](repeating: 0, count: count * BeamInterpolator.factor)
        BeamInterpolator().interpolate(f.f32("interpSrc"), start: BeamInterpolator.context, count: count, dst: &out)
        // Bit for bit: the kernel is built in Double and stored as Float, and the
        // sum runs tap by tap in the same order as the Kotlin.
        #expect(out == f.f32("interpOut"))
    }

    @Test func clockMatchesKotlin() throws {
        let f = try Fixture.load("beam")
        let written = f.i64("clockWritten")
        let now = f.i64("clockNow")
        let rates = f.i32("clockRate")
        let clock = BeamClock()
        var from: [Int64] = []
        var to: [Int64] = []
        var jumped: [Int32] = []
        var lag: [Double] = []
        for k in written.indices {
            clock.advance(written: written[k], sampleRate: Int(rates[k]), nowNanos: now[k])
            from.append(clock.from)
            to.append(clock.to)
            jumped.append(clock.jumped ? 1 : 0)
            lag.append(clock.targetLag)
        }
        // Integer slices from Double arithmetic with no libm in it: exact.
        #expect(from == f.i64("clockFrom"))
        #expect(to == f.i64("clockTo"))
        #expect(jumped == f.i32("clockJumped"))
        #expect(lag == f.f64("clockTargetLag"))
    }

    @Test func sweepMatchesKotlin() throws {
        let f = try Fixture.load("beam")
        let signal = f.f32("sweepSignal")
        let sweep = BeamSweep()
        var eventN: [Int64] = []
        var eventStart: [Double] = []
        var eventTriggered: [Int32] = []
        var sweepingCount = 0
        var lastStart = Double.nan
        for i in signal.indices {
            if i == 30000 { sweep.reset() }
            let sweepFrames = i < 18000 ? 480.0 : 960.0
            sweep.configure(rate: rate, sweepFrames: sweepFrames)
            sweep.step(Int64(i), signal[i], autoFrames: max(Double(rate) * 0.06, sweepFrames * 1.5))
            if sweep.sweeping { sweepingCount += 1 }
            if sweep.sweeping && sweep.lastStart != lastStart {
                lastStart = sweep.lastStart
                eventN.append(Int64(i))
                eventStart.append(lastStart)
                eventTriggered.append(sweep.triggered ? 1 : 0)
            }
            if sweep.sweeping && sweep.x(Double(i) + 1.0, sweepFrames: sweepFrames) > 1.0 { sweep.end() }
        }
        #expect(sweepingCount == f.int("sweepingCount"))
        #expect(eventN == f.i64("sweepEventN"))
        #expect(eventTriggered == f.i32("sweepEventTriggered"))
        // Where between two samples the low-passed mix crossed zero. The filter's
        // coefficients come from libm's cos/sin, an ulp apart from the JVM's.
        #expect(mismatch(eventStart, f.f64("sweepEventStart"), abs: 1e-9) == nil)
    }

    // ---- BeamTest.kt -----------------------------------------------------------

    @Test func interpolationFollowsTheBandLimitedCurveBetweenSamples() {
        let interpolator = BeamInterpolator()
        let context = BeamInterpolator.context
        for (hz, tolerance) in [(1000.0, 2e-3), (5000.0, 1e-2)] {
            let n = 480
            let src = (0..<(n + 2 * context)).map { Float(sin(2 * Double.pi * hz * Double($0 - context) / Double(rate))) }
            var out = [Float](repeating: 0, count: n * BeamInterpolator.factor)
            interpolator.interpolate(src, start: context, count: n, dst: &out)
            var worst = 0.0
            for k in out.indices {
                let t = Double(k) / Double(BeamInterpolator.factor) / Double(rate)
                worst = max(worst, abs(Double(out[k]) - sin(2 * Double.pi * hz * t)))
            }
            print("interpolation at \(hz) Hz: worst error \(worst)")
            #expect(worst < tolerance, "\(hz) Hz is off the curve by \(worst)")
        }
    }

    @Test func aStillBeamStaysExactlyStill() {
        // Every phase has unity gain, or a constant would buzz between four places.
        let interpolator = BeamInterpolator()
        let context = BeamInterpolator.context
        let src = [Float](repeating: 0.37, count: 64 + 2 * context)
        var out = [Float](repeating: 0, count: 64 * BeamInterpolator.factor)
        interpolator.interpolate(src, start: context, count: 64, dst: &out)
        for v in out { #expect(abs(v - 0.37) <= 1e-6) }
    }

    @Test func theBeamDrawsEverySampleOnceInEvenSlices() {
        // The phone's capture: 1024 frames at a time. A surface drawing at
        // 60 fps with a millisecond of jitter either way.
        let clock = BeamClock()
        var jitter = ScopesRandom(seed: 1)
        var written: Int64 = 0
        var now: Int64 = 0
        var drawnTo: Int64 = -1
        var worstPacing = 0.0
        var worstLag = 0.0
        for frame in 0..<900 {
            let step = 16_666_667 + Int64(jitter.nextGaussian() * 1_000_000)
            now += step
            while written + 1024 <= now * Int64(rate) / 1_000_000_000 { written += 1024 }
            clock.advance(written: written, sampleRate: rate, nowNanos: now)
            if frame == 0 {
                #expect(clock.jumped)
            } else {
                #expect(!clock.jumped, "jumped at frame \(frame)")
                #expect(drawnTo == clock.from, "gap or overlap at frame \(frame)")
                if frame > 120 {
                    // Each slice is the time that frame took, not whatever burst
                    // happened to land: that is what keeps the picture from pulsing.
                    let expected = Double(step) * Double(rate) / 1e9
                    worstPacing = max(worstPacing, abs(Double(clock.to - clock.from) - expected))
                    worstLag = max(worstLag, Double(written - clock.to) * 1000.0 / Double(rate))
                }
            }
            // Before the first burst there is nothing to draw, and the clock sits at 0.
            if written > 0 {
                #expect(clock.to <= written - Int64(BeamInterpolator.context), "drew past what can be interpolated at frame \(frame)")
            }
            drawnTo = clock.to
        }
        print("worst slice off its frame's own time by \(worstPacing) frames, worst lag \(worstLag) ms")
        #expect(worstPacing < 40.0, "uneven slices: \(worstPacing) frames off")
        #expect(worstLag < 60.0, "runs too far behind: \(worstLag) ms")
    }

    @Test func aPausedCaptureHoldsTheBeamAndItCarriesOnWithoutAJump() {
        // While paused the engine stops writing the feed, so the capture's own
        // clock stops; the surface's does not.
        let clock = BeamClock()
        var written: Int64 = 0
        var now: Int64 = 0
        var captured: Int64 = 0
        func frames(_ count: Int, capturing: Bool) -> [Int64] {
            (0..<count).map { _ in
                now += 16_666_667
                if capturing {
                    captured += 16_666_667
                    while written + 1024 <= captured * Int64(rate) / 1_000_000_000 { written += 1024 }
                }
                clock.advance(written: written, sampleRate: rate, nowNanos: now)
                // Only the very first frame has nothing to continue from.
                if now > 16_666_667 { #expect(!clock.jumped, "jumped") }
                return clock.to - clock.from
            }
        }
        _ = frames(120, capturing: true)
        let paused = frames(60, capturing: false)
        // It drains what it was behind by, then stops, so the picture freezes.
        #expect(paused.suffix(40).allSatisfy { $0 == 0 })
        let resumed = frames(120, capturing: true)
        #expect(resumed.suffix(60).allSatisfy { (650...950).contains($0) })
    }

    @Test func aSurfaceThatWasAwayJumpsToThePresent() {
        let clock = BeamClock()
        var written: Int64 = 0
        var now: Int64 = 0
        for _ in 0..<60 {
            now += 16_666_667
            while written + 1024 <= now * Int64(rate) / 1_000_000_000 { written += 1024 }
            clock.advance(written: written, sampleRate: rate, nowNanos: now)
        }
        now += 3_000_000_000
        written += 3 * Int64(rate)
        clock.advance(written: written, sampleRate: rate, nowNanos: now)
        #expect(clock.jumped)
        #expect(written - clock.to < Int64(rate / 10))
    }

    @Test func aSineTriggersAtTheSamePointOfItsCycleEverySweep() {
        let sweep = BeamSweep()
        let sweepFrames = 10 * 1.0 * Double(rate) / 1000.0
        sweep.configure(rate: rate, sweepFrames: sweepFrames)
        let hz = 440.0
        var phases: [Double] = []
        var lastStart = Double.nan
        for n in 0..<Int64(rate) {
            let v = Float(0.6 * sin(2 * Double.pi * hz * Double(n) / Double(rate)))
            sweep.step(n, v, autoFrames: Double(rate) * 0.06)
            if sweep.sweeping && sweep.lastStart != lastStart {
                lastStart = sweep.lastStart
                if n > Int64(rate / 10) {
                    #expect(sweep.triggered, "a sweep started by AUTO on a clean sine")
                    let cycles = lastStart * hz / Double(rate)
                    phases.append(cycles - floor(cycles))
                }
            }
            if sweep.sweeping && sweep.x(Double(n) + 1.0, sweepFrames: sweepFrames) > 1.0 { sweep.end() }
        }
        let spread = phases.max()! - phases.min()!
        print("\(phases.count) sweeps, trigger phase spread \(spread * 360) degrees")
        #expect(phases.count > 50)
        // A thousandth of a cycle: under a pixel on any screen.
        #expect(spread < 1e-3, "the trace would shiver: spread \(spread) cycles")
    }

    @Test func silenceStillSweepsOnTheAutoTimer() {
        let sweep = BeamSweep()
        sweep.configure(rate: rate, sweepFrames: 480)
        var sweeps = 0
        var lastStart = Double.nan
        for n in 0..<Int64(rate) {
            sweep.step(n, 0, autoFrames: Double(rate) * 0.06)
            if sweep.sweeping && sweep.lastStart != lastStart {
                lastStart = sweep.lastStart
                sweeps += 1
                #expect(!sweep.triggered)
            }
            if sweep.sweeping && sweep.x(Double(n) + 1.0, sweepFrames: 480) > 1.0 { sweep.end() }
        }
        // A 60 ms wait and a 10 ms sweep: about fourteen a second.
        #expect((12...16).contains(sweeps), "only \(sweeps) sweeps")
    }
}
