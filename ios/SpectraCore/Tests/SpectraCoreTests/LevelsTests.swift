import Foundation
import Testing
import SpectraFixtures
@testable import SpectraCore

/// The spectrum, band, loudness and stereo analysis and the resampler, against
/// what the Kotlin engine computed (IosLevelsFixtures.kt). Each harness drives
/// the class the way AudioEngine.runLoop does, mirroring the Kotlin exporter
/// line for line.
///
/// Everything except the spectrum matches the Kotlin bit for bit (abs: 0), in
/// debug and in release: the same Float/Double steps in the same order, Kotlin's
/// Float log10/ln/pow done in Double and rounded to Float, and Darwin's libm
/// agreeing with the JVM's on every input here. A nonzero difference in any of
/// those is a porting mistake, not rounding.
@Suite struct LevelsTests {

    // ---- spectrum ----------------------------------------------------------

    @Test func weightingMatchesKotlin() throws {
        let f = try Fixture.load("weighting")
        let hz = f.f32("hz")
        for w in Weighting.allCases {
            #expect(mismatch(hz.map { SpectrumAnalyzer.weightingDb(w, $0) }, f.f32(w.rawValue), abs: 0) == nil, "\(w.rawValue)")
        }
    }

    @Test func ballisticsMatchKotlin() throws {
        let f = try Fixture.load("ballistics")
        let targets = f.f32("targets")
        let dts = f.f32("dts")
        let n = targets.count

        var step = [Float](repeating: 0, count: n)
        let b = Ballistics(frameIntervalSec: 0.0213)
        var cur = PeakHold.floorDb
        for i in 0..<n {
            if i == 150 { b.attackMs = 0; b.releaseMs = 50; b.setFrameInterval(0.005) }
            cur = b.step(cur, targets[i])
            step[i] = cur
        }
        #expect(mismatch(step, f.f32("step"), abs: 0) == nil, "step")

        var stepDt = [Float](repeating: 0, count: n)
        let bd = Ballistics(frameIntervalSec: 0.016)
        bd.attackMs = 8
        bd.releaseMs = 220
        cur = PeakHold.floorDb
        for i in 0..<n {
            if i == 150 { bd.releaseMs = 0 }
            cur = bd.stepDt(cur, targets[i], dtSec: dts[i] / 1000)
            stepDt[i] = cur
        }
        #expect(mismatch(stepDt, f.f32("stepDt"), abs: 0) == nil, "stepDt")

        let ba = Ballistics(frameIntervalSec: 0.0213)
        var current = [Float](repeating: PeakHold.floorDb, count: 64)
        var stepAll: [Float] = []
        for s in 0..<20 {
            ba.stepAll(&current, Array(targets[s * 10 ..< s * 10 + 64]))
            stepAll += current
        }
        #expect(mismatch(stepAll, f.f32("stepAll"), abs: 0) == nil, "stepAll")

        let peaks = PeakHold(size: 64)
        peaks.holdMs = 50
        peaks.fallDbPerSec = 60
        var held: [Float] = []
        for s in 0..<30 {
            if s == 12 { peaks.enabled = false }
            if s == 14 { peaks.enabled = true }
            if s == 22 { peaks.reset() }
            peaks.update(Array(targets[s * 7 ..< s * 7 + 64]), dtMs: dts[s])
            held += peaks.values
        }
        #expect(mismatch(held, f.f32("peaks"), abs: 0) == nil, "peaks")
    }

    /// The spectrum is the one class here that does not match bit for bit, and
    /// the whole reason is the FFT: `Fft` hands the transform to vDSP, which
    /// rounds differently from Fft.kt's radix-2. With a line-for-line Swift port
    /// of Fft.kt swapped in (tried 2026-10-20), every raw bin, smoothed bin, peak
    /// and spectrogram byte below matched the Kotlin exactly, at abs: 0.
    @Test func spectrumMatchesKotlin() throws {
        let f = try Fixture.load("spectrum")
        let input = f.f32("input")
        let rate = f.int("rate")
        let rows = f.int("rows")
        for name in f.strings("cases") {
            let snaps = Set(f.ints("\(name).snaps"))
            let a = SpectrumAnalyzer(sampleRate: rate, settings: spectrumSettings(f, "\(name).0"), spectrogramRows: rows)
            var column = [UInt8](repeating: 0, count: rows)
            var columns: [UInt8] = []
            var p = 0
            var h = 0
            for i in 0..<f.int("\(name).phases") {
                let s = spectrumSettings(f, "\(name).\(i)")
                if i > 0 { a.configure(s, rate: rate) }
                let hop = s.hopSize
                let dtMs = Float(hop) * 1000 / Float(rate)
                for _ in 0..<f.int("\(name).\(i).hops") {
                    a.process(Array(input[p ..< p + s.fftSize]), dtMs: dtMs)
                    a.fillSpectrogramColumn(&column, floorDb: s.spectrogramFloorDb, ceilDb: s.spectrogramCeilingDb)
                    columns += column
                    if snaps.contains(h) {
                        // Raw bins straight out of the transform get the
                        // level-scaled FFT allowance. Worst seen: k = 3.4e-7, so
                        // 4e-7 dB at full scale — under one float ulp of full
                        // scale in amplitude, 8e-7 dB on a −6 dBFS bin, 0.04 dB on
                        // a bin 100 dB below full scale.
                        #expect(fftMismatch(a.rawDb, f.f32("\(name).raw.\(h)"), k: 4e-7) == nil, "\(name) raw, hop \(h)")
                        // The ballistics and the hold average raw dB from earlier
                        // frames, including bins that were 100 dB down and carried
                        // 0.01 dB of FFT rounding, so their difference no longer
                        // follows the current level and the bound has to be flat.
                        // Worst seen 0.0015 dB; 0.002 dB is a 0.02 % amplitude difference.
                        #expect(mismatch(a.smoothedDb, f.f32("\(name).smoothed.\(h)"), abs: 0.002) == nil, "\(name) smoothed, hop \(h)")
                        #expect(mismatch(a.peaks.values, f.f32("\(name).peaks.\(h)"), abs: 0.002) == nil, "\(name) peaks, hop \(h)")
                    }
                    p += hop
                    h += 1
                }
            }
            // Each byte quantises a smoothed value to a ~0.3 dB step, so the
            // 0.002 dB above tips one across a step now and then: 4 of 12 800
            // bytes over all the cases, each by one count. Allow one count, on at
            // most 0.1 % of a case's bytes.
            let expected = f.u8("\(name).columns")
            #expect(columns.count == expected.count)
            let flips = zip(columns, expected).filter { $0 != $1 }
            #expect(flips.allSatisfy { abs(Int($0) - Int($1)) <= 1 }, "\(name) spectrogram: a byte more than one count off")
            #expect(flips.count * 1000 <= columns.count, "\(name) spectrogram: \(flips.count) of \(columns.count) bytes differ")
        }
    }

    /// Where two spectra (dB) that went through different float FFTs disagree
    /// by more than rounding explains — nil when they agree.
    ///
    /// An FFT's rounding is a fixed *amplitude*, a fraction of full scale,
    /// whatever a bin holds. In dB that is nothing on a loud bin and grows as a
    /// bin gets quieter, so a bin `L` dB below full scale may move by
    /// `k·10^(−L/20)` dB. A flat dB tolerance would have to be loose enough for
    /// the quietest bin and would then wave through a real error on a loud one.
    private func fftMismatch(_ a: [Float], _ b: [Float], k: Float) -> String? {
        if a.count != b.count { return "length \(a.count) vs \(b.count)" }
        var failures = 0
        var worst = 0
        var worstOver: Float = 0
        for i in a.indices {
            let allowed = k * pow(10, -b[i] / 20)
            let d = abs(a[i] - b[i])
            if !(d <= allowed) {
                failures += 1
                if d / allowed > worstOver { worstOver = d / allowed; worst = i }
            }
        }
        guard failures > 0 else { return nil }
        return "\(failures) of \(a.count) differ; worst at [\(worst)]: \(a[worst]) vs \(b[worst]), \(worstOver)× the allowance"
    }

    private func spectrumSettings(_ f: Fixture, _ key: String) -> Settings {
        var s = Settings()
        s.fftSize = f.int("\(key).fftSize")
        s.window = WindowFunction(rawValue: f.string("\(key).window"))!
        s.overlap = f.int("\(key).overlap")
        s.weighting = Weighting(rawValue: f.string("\(key).weighting"))!
        s.tiltDbPerOct = f.float("\(key).tiltDbPerOct")
        s.freqScale = FreqScale(rawValue: f.string("\(key).freqScale"))!
        s.minHz = f.float("\(key).minHz")
        s.maxHz = f.float("\(key).maxHz")
        s.attackMs = f.float("\(key).attackMs")
        s.releaseMs = f.float("\(key).releaseMs")
        s.peakHold = f.bool("\(key).peakHold")
        s.peakHoldMs = f.float("\(key).peakHoldMs")
        s.peakFallDbPerSec = f.float("\(key).peakFallDbPerSec")
        s.spectrogramFloorDb = f.float("\(key).spectrogramFloorDb")
        s.spectrogramCeilingDb = f.float("\(key).spectrogramCeilingDb")
        return s
    }

    // ---- bands -------------------------------------------------------------

    @Test func bandsMatchKotlin() throws {
        let f = try Fixture.load("bands")
        let left = f.f32("left")
        let right = f.f32("right")
        let chunks = f.ints("chunks")
        let historyLength = f.int("historyLength")
        var base = Settings()
        base.peakHoldMs = f.float("peakHoldMs")
        base.peakFallDbPerSec = f.float("peakFallDbPerSec")

        for name in f.strings("cases") {
            var s = base
            s.bandSlope = BandSlope(rawValue: f.string("\(name).slope"))!
            let stereo = f.bool("\(name).stereo")
            let rate = f.int("\(name).rate")
            let reconf = f.bool("\(name).reconf")
            let bs = BandSplitter(sampleRate: rate, settings: s, historyLength: historyLength)
            var ticks: [Float] = []
            var at = 0
            for (k, frames) in chunks.enumerated() {
                if reconf && k == 4 {
                    var moved = s
                    moved.bands[3].highHz = 2000
                    moved.bandReleaseMs = 100
                    bs.configure(moved, sampleRate: rate)
                }
                if reconf && k == 6 {
                    bs.reset()
                    bs.tick(10)
                }
                left.withUnsafeBufferPointer { l in
                    right.withUnsafeBufferPointer { r in
                        bs.process(l.baseAddress! + at, stereo ? r.baseAddress! + at : nil, frames: frames)
                    }
                }
                bs.tick(Float(frames) * 1000 / Float(rate))
                for rd in bs.readings() { ticks += [rd.rmsDb, rd.peakDb, rd.holdDb] }
                at += frames
            }
            #expect(mismatch(ticks, f.f32("\(name).ticks"), abs: 0) == nil, "\(name) readings")
            if f.has("\(name).history") {
                var hist = [Float](repeating: 0, count: historyLength)
                var all: [Float] = []
                for rd in bs.readings() {
                    rd.history.snapshot(&hist)
                    all += hist
                }
                #expect(mismatch(all, f.f32("\(name).history"), abs: 0) == nil, "\(name) history")
            }
            #expect(bs.readings().map(\.name) == s.bands.map(\.name))
        }
    }

    // ---- loudness ----------------------------------------------------------

    @Test(arguments: [48000, 44100])
    func loudnessMatchesKotlin(rate: Int) throws {
        let f = try Fixture.load("loudness_\(rate)")
        let block = f.int("block")
        let sine = f.f32("sine")
        let noiseL = f.f32("noiseL")
        let noiseR = f.f32("noiseR")
        let gains = f.f32("gains")
        for run in f.strings("runs") {
            var s = Settings()
            s.truePeakEnabled = f.bool("\(run).truePeakEnabled")
            s.truePeakOversample = f.int("\(run).truePeakOversample")
            let stereo = f.bool("\(run).stereo")
            let isSine = f.string("\(run).signal") == "sine"
            let m = LoudnessMeter(sampleRate: rate, settings: s)
            var l = [Float](repeating: 0, count: block)
            var r = [Float](repeating: 0, count: block)
            var out: [Float] = []
            for rep in 0..<f.int("\(run).repeats") {
                if isSine {
                    l = sine
                    r = sine
                } else {
                    let g = gains[rep]
                    for i in 0..<block {
                        l[i] = noiseL[i] * g
                        r[i] = noiseR[i] * g
                    }
                }
                m.process(l, stereo ? r : nil, frames: block)
                let rd = m.read()
                out += [rd.momentary, rd.shortTerm, rd.integrated, rd.range, rd.truePeakDb, rd.integratedSeconds]
            }
            #expect(mismatch(out, f.f32("\(run).readings"), abs: 0) == nil, "\(run)")
            if isSine {
                // Not parity but meaning: BS.1770's −0.691 cancels the K-weighting
                // gain at 1 kHz, so a −20 dBFS sine in both channels reads −20 LUFS
                // (the integrated value is a 0.075 LU histogram bin centre), and
                // its true peak is its sample peak.
                let last = Array(out.suffix(6))
                #expect(abs(last[0] + 20) < 0.02, "momentary \(last[0])")
                #expect(abs(last[2] + 20) < 0.02, "integrated \(last[2])")
                #expect(abs(last[4] + 20) < 0.001, "true peak \(last[4])")
            }
        }
    }

    @Test func truePeakMatchesKotlin() throws {
        let f = try Fixture.load("truePeak")
        let input = f.f32("input")
        for (factor, taps) in zip(f.ints("factors"), f.ints("taps")) {
            let tp = TruePeak(factor: factor, tapsPerPhase: taps)
            let first = input.map { tp.process($0) }
            #expect(mismatch(first, f.f32("x\(factor)_\(taps)"), abs: 0) == nil, "\(factor)x, \(taps) taps")
            tp.reset()
            #expect(input.map { tp.process($0) } == first, "\(factor)x after reset")
        }
    }

    // ---- stereo ------------------------------------------------------------

    @Test func stereoMatchesKotlin() throws {
        let f = try Fixture.load("stereo")
        let rate = f.int("rate")
        let chunks = f.ints("chunks")
        let n1 = f.f32("n1")
        let rights: [String: [Float]?] = [
            "inPhase": n1,
            "antiPhase": n1.map { -$0 },
            "decorrelated": f.f32("n2"),
            "partial": f.f32("partial"),
            "mono": nil,
        ]
        for (name, windowMs) in zip(f.strings("cases"), f.doubles("windowMs")) {
            var s = Settings()
            s.correlationWindowMs = Float(windowMs)
            let right = try #require(rights[name])
            let sa = StereoAnalyzer(sampleRate: rate, settings: s)
            var readings: [Float] = []
            var sizes: [Int32] = []
            var at = 0
            for (k, frames) in chunks.enumerated() {
                sa.process(Array(n1[at ..< at + frames]), right.map { Array($0[at ..< at + frames]) }, frames: frames)
                readings += [sa.correlation, sa.width, sa.rmsL, sa.rmsR, sa.peakL, sa.peakR]
                sizes.append(Int32(sa.cloudSize()))
                if f.has("\(name).cloud.\(k)") {
                    #expect(mismatch(sa.snapshotCloud(), f.f32("\(name).cloud.\(k)"), abs: 0) == nil, "\(name) cloud \(k)")
                }
                at += frames
            }
            #expect(mismatch(readings, f.f32("\(name).readings"), abs: 0) == nil, "\(name) readings")
            #expect(sizes == f.i32("\(name).cloudSizes"), "\(name) cloud sizes")
            // And what the meters mean: mono and in-phase read +1, anti-phase −1.
            switch name {
            case "inPhase", "mono": #expect(sa.correlation == 1 && sa.width == 0)
            case "antiPhase": #expect(sa.correlation == -1 && sa.width == 4)
            case "decorrelated": #expect(abs(sa.correlation) < 0.1)
            default: break
            }
        }
        let levels = f.f32("toDbIn")
        #expect(mismatch(levels.map(StereoAnalyzer.toDb), f.f32("toDbOut"), abs: 0) == nil, "toDb")
    }

    // ---- resampler ---------------------------------------------------------

    @Test func resamplerMatchesKotlin() throws {
        let f = try Fixture.load("resampler")
        let inL = f.f32("inL")
        let inR = f.f32("inR")
        for name in f.strings("cases") {
            let r = Resampler(inRate: f.int("\(name).inRate"), outRate: f.int("\(name).outRate"))
            var outL: [Float] = []
            var outR: [Float] = []
            var produced: [Int32] = []
            var at = 0
            for frames in f.ints("\(name).chunks") {
                var l = [Float](repeating: 0, count: r.maxOutput(frames))
                var rr = [Float](repeating: 0, count: r.maxOutput(frames))
                let n = r.process(Array(inL[at ..< at + frames]), Array(inR[at ..< at + frames]), frames: frames, outL: &l, outR: &rr)
                produced.append(Int32(n))
                outL += l[0 ..< n]
                outR += rr[0 ..< n]
                at += frames
            }
            #expect(produced == f.i32("\(name).produced"), "\(name) frames produced")
            #expect(mismatch(outL, f.f32("\(name).outL"), abs: 0) == nil, "\(name) left")
            #expect(mismatch(outR, f.f32("\(name).outR"), abs: 0) == nil, "\(name) right")
        }
    }

    // ---- what the numbers mean ---------------------------------------------
    //
    // Parity alone would pass a mistake the two engines share. These pin the
    // readings to their definitions instead.

    @Test func weightingFollowsIec61672() {
        // IEC 61672-1's table, rounded to 0.1 dB, so ±0.05 dB is the table's own
        // rounding. Its rows are labelled 31.5, 63, 125 … but defined at the
        // exact base-10 frequencies 1000·10^(n/10) Hz (31.62, 63.10, 125.9 …);
        // at the labels themselves A reads up to 0.1 dB off the table.
        let table: [(n: Int, a: Float, c: Float)] = [
            (-15, -39.4, -3.0), (-12, -26.2, -0.8), (-9, -16.1, -0.2), (-6, -8.6, 0.0), (-3, -3.2, 0.0),
            (0, 0.0, 0.0), (3, 1.2, -0.2), (6, 1.0, -0.8), (9, -1.1, -3.0), (12, -6.6, -8.5),
        ]
        for row in table {
            let hz = Float(1000 * pow(10, Double(row.n) / 10))
            #expect(abs(SpectrumAnalyzer.weightingDb(.a, hz) - row.a) <= 0.05, "A at \(hz) Hz")
            #expect(abs(SpectrumAnalyzer.weightingDb(.c, hz) - row.c) <= 0.05, "C at \(hz) Hz")
        }
        #expect(SpectrumAnalyzer.weightingDb(.z, 1234) == 0)
        #expect(SpectrumAnalyzer.weightingDb(.a, 0) == 0)
    }

    @Test(arguments: WindowFunction.allCases)
    func fullScaleSineOnABinReadsZeroDbfs(window: WindowFunction) {
        var s = Settings()
        s.fftSize = 1024
        s.window = window
        let a = SpectrumAnalyzer(sampleRate: 48000, settings: s, spectrogramRows: 64)
        // Bin 64 of 1024 at 48 kHz is 3 kHz.
        let block = (0..<1024).map { Float(sin(2 * Double.pi * 64 * Double($0) / 1024)) }
        a.process(block, dtMs: 5)
        #expect(abs(a.rawDb[64]) < 0.001, "\(window): \(a.rawDb[64]) dBFS")
    }

    @Test func fullScaleSineInABandReadsMinusThreeDbfs() {
        let rate = 48000
        let bs = BandSplitter(sampleRate: rate, settings: Settings(), historyLength: 16)
        // 775 Hz is the geometric centre of "Mid" (400–1500 Hz), where each
        // 4th-order Butterworth skirt still takes 0.011 dB: −3.03 dBFS in all.
        let sine = (0..<rate).map { Float(sin(2 * Double.pi * 775 * Double($0) / Double(rate))) }
        var at = 0
        while at < sine.count {
            bs.process(Array(sine[at ..< at + 1000]), nil, frames: 1000)
            bs.tick(1000 * 1000 / Float(rate))
            at += 1000
        }
        let mid = bs.readings()[3]
        #expect(mid.name == "Mid")
        #expect(abs(mid.rmsDb + 3.03) < 0.01, "Mid reads \(mid.rmsDb) dBFS")
        #expect(abs(mid.peakDb) < 0.1, "Mid peak \(mid.peakDb) dBFS")
        #expect(bs.readings()[0].rmsDb < -60, "Sub reads \(bs.readings()[0].rmsDb) dBFS")
    }

    // ---- behaviour the fixtures don't cover ----------------------------------

    @Test func peakHoldResizeKeepsItsSettings() {
        let p = PeakHold(size: 8)
        p.holdMs = 12
        p.fallDbPerSec = 34
        p.enabled = false
        #expect(p.resize(8) === p)
        let q = p.resize(16)
        #expect(q !== p && q.values.count == 16)
        #expect(q.holdMs == 12 && q.fallDbPerSec == 34 && !q.enabled)
        #expect(q.values.allSatisfy { $0 == PeakHold.floorDb })
    }

    @Test func loudnessMeterOffReadsEmpty() {
        var s = Settings()
        s.loudnessEnabled = false
        let m = LoudnessMeter(sampleRate: 48000, settings: s)
        m.process([Float](repeating: 0.5, count: 48000), nil, frames: 48000)
        #expect(m.read() == .empty)

        // A new sample rate starts the measurement over.
        s.loudnessEnabled = true
        m.configure(s, sampleRate: 48000)
        m.process([Float](repeating: 0.5, count: 48000), nil, frames: 48000)
        #expect(m.read().integratedSeconds > 0.9)
        m.configure(s, sampleRate: 44100)
        #expect(m.read().integratedSeconds == 0)
        #expect(m.read().truePeakDb == -144)
    }

    // ---- ResamplerTest.kt ----------------------------------------------------

    @Test func resampler48kTo44k1KeepsPitchAndLevel() {
        let r = Resampler(inRate: 48000, outRate: 44100)
        let input = (0..<48000).map { Float(sin(2 * Double.pi * 1000 * Double($0) / 48000)) * 0.5 }
        let out = feed(r, input, chunkSeed: 3)

        // The length follows the ratio, to within the filter's own delay.
        #expect(abs(out.count - 44100) <= 20)

        // Frequency by counting rising zero crossings over the steady part.
        let steady = Array(out[1000 ..< out.count - 1000])
        var crossings = 0
        for i in 1..<steady.count where steady[i - 1] < 0 && steady[i] >= 0 { crossings += 1 }
        let hz = Double(crossings) * 44100 / Double(steady.count)
        #expect(abs(hz - 1000) <= 2, "\(hz) Hz")

        // Level: a 0.5 sine has RMS 0.354.
        #expect(abs(rms(steady) - 0.5 / 2.0.squareRoot()) <= 0.005)
    }

    @Test func resamplerBlockSizeDoesNotChangeTheOutput() {
        let input = (0..<20000).map { Float(sin(2 * Double.pi * 440 * Double($0) / 48000)) }
        let oneShot = feed(Resampler(inRate: 48000, outRate: 44100), input, chunkSeed: nil)
        let chunked = feed(Resampler(inRate: 48000, outRate: 44100), input, chunkSeed: 4)
        #expect(oneShot.count == chunked.count)
        #expect(mismatch(oneShot, chunked, abs: 1e-6) == nil)
    }

    @Test func resamplerHoldsBackWhat44k1CannotHold() {
        // 23 kHz is above the new Nyquist and would fold down to 21.1 kHz. A
        // 32-tap kernel only has room for a gentle skirt up there, and that is a
        // deliberate trade: whatever folds lands above 20 kHz, far above anything
        // the stem model separates by. Knocked down, not removed.
        let input = (0..<48000).map { Float(sin(2 * Double.pi * 23000 * Double($0) / 48000)) }
        let out = feed(Resampler(inRate: 48000, outRate: 44100), input, chunkSeed: nil)
        let peak = out[1000 ..< out.count - 1000].map(abs).max()!
        #expect(peak < 0.1, "alias at \(20 * log10(Double(peak))) dB")

        // …while the top of the band that matters is still flat.
        let tone = (0..<48000).map { Float(sin(2 * Double.pi * 16000 * Double($0) / 48000)) }
        let passed = feed(Resampler(inRate: 48000, outRate: 44100), tone, chunkSeed: nil)
        #expect(abs(rms(Array(passed[1000 ..< passed.count - 1000])) - 1 / 2.0.squareRoot()) <= 0.02)
    }

    @Test func resamplerAtTheSameRateIsACopy() {
        let r = Resampler(inRate: 44100, outRate: 44100)
        #expect(r.passthrough)
        let input = (0..<100).map(Float.init)
        #expect(feed(r, input, chunkSeed: nil) == input)
    }

    /// ResamplerTest.kt's feeder: chunks of 1…3000 frames when `chunkSeed` is set,
    /// one call otherwise; left and right get the same chunk and must come out equal.
    private func feed(_ r: Resampler, _ input: [Float], chunkSeed: UInt64?) -> [Float] {
        var random = chunkSeed.map(SplitMix.init(seed:))
        var out: [Float] = []
        var i = 0
        while i < input.count {
            var n = input.count - i
            if random != nil { n = min(n, 1 + random!.nextInt(3000)) }
            let chunk = Array(input[i ..< i + n])
            var l = [Float](repeating: 0, count: r.maxOutput(n))
            var rr = [Float](repeating: 0, count: r.maxOutput(n))
            let produced = r.process(chunk, chunk, frames: n, outL: &l, outR: &rr)
            #expect(l[0 ..< produced] == rr[0 ..< produced])
            out += l[0 ..< produced]
            i += n
        }
        return out
    }

    private func rms(_ x: [Float]) -> Double {
        (x.reduce(0.0) { $0 + Double($1 * $1) } / Double(x.count)).squareRoot()
    }
}

/// A seeded generator for chunk sizes and timing noise. Not java.util.Random —
/// the Kotlin test only needs *some* uneven chunking, not those exact sizes.
private struct SplitMix {
    private var state: UInt64
    init(seed: UInt64) { state = seed }
    mutating func nextInt(_ bound: Int) -> Int {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return Int((z ^ (z >> 31)) % UInt64(bound))
    }
}

/// What the hot paths cost in a release build, as ms per call:
///
///     swift test -c release --filter Timing
///
/// A debug `swift test` runs them too, with a fiftieth of the iterations, so they
/// cannot rot; their numbers mean nothing there. The budget a block has is its
/// own duration: 1024 frames at 48 kHz is 21.3 ms.
@Suite(.serialized) struct LevelsTiming {
    #if DEBUG
    private static let scale = 0.02
    #else
    private static let scale = 1.0
    #endif

    private func msPerCall(_ iterations: Int, _ body: () -> Void) -> Double {
        let n = max(1, Int(Double(iterations) * LevelsTiming.scale))
        body() // warm up: first-touch page faults and lazy setup
        let d = ContinuousClock().measure { for _ in 0..<n { body() } }
        return (Double(d.components.seconds) * 1e3 + Double(d.components.attoseconds) * 1e-15) / Double(n)
    }

    private func noise(_ n: Int, seed: UInt64) -> [Float] {
        var r = SplitMix(seed: seed)
        return (0..<n).map { _ in Float(r.nextInt(2_000_001) - 1_000_000) / 2_000_000 }
    }

    /// `perSecond` calls a second in real time; ms × calls/s ÷ 10 is the percent of one core.
    private func report(_ what: String, _ ms: Double, perSecond: Double) {
        print("Timing: \(what): " + String(format: "%.4f ms per call, %.2f %% of one core in real time", ms, ms * perSecond / 10))
    }

    @Test(arguments: [4096, 16384])
    func spectrumTiming(fftSize: Int) {
        var s = Settings()
        s.fftSize = fftSize
        let a = SpectrumAnalyzer(sampleRate: 48000, settings: s, spectrogramRows: 256)
        let block = noise(fftSize, seed: 1)
        var column = [UInt8](repeating: 0, count: 256)
        let process = msPerCall(fftSize == 4096 ? 4000 : 1000) { a.process(block, dtMs: 21.3) }
        let fill = msPerCall(4000) { a.fillSpectrogramColumn(&column, floorDb: -90, ceilDb: -6) }
        // Hops per second at 48 kHz with the default overlap of 4, and at 8.
        for overlap in [4, 8] {
            let hops = 48000.0 / Double(fftSize / overlap)
            report("SpectrumAnalyzer.process + fillSpectrogramColumn, fftSize \(fftSize), overlap \(overlap)",
                   process + fill, perSecond: hops)
        }
        print(String(format: "Timing:   (process %.4f ms, fillSpectrogramColumn %.4f ms)", process, fill))
    }

    @Test func streamingMetersTiming() {
        let rate = 48000
        let frames = 1024
        let blocksPerSecond = Double(rate) / Double(frames)
        let left = noise(frames, seed: 2)
        let right = noise(frames, seed: 3)
        let blockMs = Float(frames) * 1000 / Float(rate)

        for slope in [BandSlope.db24, .db48] {
            var s = Settings()
            s.bandSlope = slope
            let bs = BandSplitter(sampleRate: rate, settings: s, historyLength: 256)
            let ms = msPerCall(2000) {
                bs.process(left, right, frames: frames)
                bs.tick(blockMs)
            }
            report("BandSplitter.process + tick, 1024 stereo frames, \(slope.label)", ms, perSecond: blocksPerSecond)
        }

        for factor in [4, 8] {
            var s = Settings()
            s.truePeakOversample = factor
            let m = LoudnessMeter(sampleRate: rate, settings: s)
            let ms = msPerCall(2000) { m.process(left, right, frames: frames) }
            report("LoudnessMeter.process, 1024 stereo frames, true peak \(factor)×", ms, perSecond: blocksPerSecond)
        }

        let sa = StereoAnalyzer(sampleRate: rate, settings: Settings())
        let stereo = msPerCall(4000) { sa.process(left, right, frames: frames) }
        report("StereoAnalyzer.process, 1024 stereo frames", stereo, perSecond: blocksPerSecond)

        // Everything above that one capture block costs at the defaults
        // (24 dB/oct, true peak 4×, fftSize 4096 at overlap 4 = one hop per block).
        let s = Settings()
        let bands = BandSplitter(sampleRate: rate, settings: s, historyLength: 256)
        let loudness = LoudnessMeter(sampleRate: rate, settings: s)
        let spectrum = SpectrumAnalyzer(sampleRate: rate, settings: s, spectrogramRows: 256)
        let block = noise(s.fftSize, seed: 4)
        var column = [UInt8](repeating: 0, count: 256)
        let total = msPerCall(1000) {
            bands.process(left, right, frames: frames)
            bands.tick(blockMs)
            loudness.process(left, right, frames: frames)
            sa.process(left, right, frames: frames)
            spectrum.process(block, dtMs: blockMs)
            spectrum.fillSpectrogramColumn(&column, floorDb: -90, ceilDb: -6)
            _ = loudness.read()
            _ = bands.readings()
        }
        report("all of the above per 1024-frame stereo block, defaults", total, perSecond: blocksPerSecond)
    }
}
