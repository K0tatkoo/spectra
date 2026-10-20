import Foundation
import Testing
import SpectraCore
import SpectraFixtures
@testable import SpectraStems

extension StemsTests {
    /// The stems against what the Kotlin engine computed on the same inputs
    /// (app/src/test/java/com/n3d/spectra/ios/StemsFixtureExport.kt, run with
    /// SPECTRA_IOS_FIXTURES=1). The Kotlin runs ONNX Runtime 1.26 through its
    /// Java API; this runs 1.24.2 through the Objective-C one.
    ///
    /// Both networks answer bit for bit (abs: 0) when shown the same numbers:
    /// on this Mac the two runtimes run the same CPU kernels, measured
    /// 2026-10-21. If a runtime upgrade on either side changes a kernel, expect
    /// differences of a few float steps and re-measure; a stem that differs by
    /// whole percent is a bug. The one place the two engines compute differently
    /// is the synth splitter's FFT — vDSP here, Fft.kt's radix-2 there — and
    /// every tolerance below that is not 0 is that rounding, carried on.
    @Suite struct StemsFixtureTests {

        // ---- the stem model --------------------------------------------------

        @Test(.enabled(if: StemsFiles.stemModel != nil, Comment(rawValue: StemsFiles.noStemModel)))
        func stemSessionMatchesKotlin() throws {
            let f = try Fixture.load("stemSession")
            let out = try runSession(f)
            let want = f.f32("output")
            let (w, rms) = worst(out, want)
            print("stemSession: worst difference \(w) over \(f.int("hops")) hops (stems' RMS \(rms))")
            // Bit for bit, all 200 hops: state carried, planar layout, pinned outputs.
            #expect(mismatch(out, want, abs: 0) == nil, "stem session")
        }

        @Test(.enabled(if: StemsFiles.stemModel != nil, Comment(rawValue: StemsFiles.noStemModel)))
        func stemSeparatorMatchesKotlin() throws {
            let session = try Fixture.load("stemSession")
            let f = try Fixture.load("stemSeparator")
            let hop = StemSeparator.hop
            let hops = session.int("hops")
            let input = session.f32("input")
            var left = [Float](repeating: 0, count: hops * hop)
            var right = left
            for h in 0..<hops {
                for i in 0..<hop {
                    left[h * hop + i] = input[h * 2 * hop + i]
                    right[h * hop + i] = input[h * 2 * hop + hop + i]
                }
            }
            let end = Int64(f.int("written"))

            let sep = StemSeparator(modelFile: StemsFiles.stemModel!)
            sep.start()
            #expect(waitFor(seconds: 30) { if case .running = sep.status { true } else { false } }, "the model did not load")
            var pushed = 0
            while pushed < left.count {
                let count = min(1882, left.count - pushed)
                sep.push(left: Array(left[pushed ..< pushed + count]), right: Array(right[pushed ..< pushed + count]), frames: count)
                pushed += count
                let target = Int64((pushed / hop) * hop - hop)
                _ = waitFor(seconds: 10) { sep.stems.allSatisfy { $0.written >= target } }
            }
            _ = waitFor(seconds: 10) { sep.pitched.allSatisfy { $0.written >= end } }
            let st = sep.status
            sep.stop()
            guard case let .running(_, skips, _) = st else {
                Issue.record("separator: \(st)")
                return
            }
            #expect(skips == 0)

            // The worker's own arithmetic, held to this port's StemSession run
            // the plain way: the mono mix-down and the rings are exact.
            let direct = try runSession(session)
            for s in 0..<StemSeparator.modelStems {
                var stems = [Float](repeating: 0, count: Int(end))
                var pitched = [Float](repeating: 0, count: Int(end))
                #expect(sep.stems[s].written == end)
                #expect(sep.stems[s].read(end: end, into: &stems, count: Int(end)))
                #expect(sep.pitched[s].read(end: end, into: &pitched, count: Int(end)))
                var mono = [Float](repeating: 0, count: Int(end))
                for h in 1..<hops {
                    let base = (h * 4 + s) * 2 * hop
                    for i in 0..<hop { mono[(h - 1) * hop + i] = 0.5 * (direct[base + i] + direct[base + hop + i]) }
                }
                #expect(mismatch(stems, mono, abs: 0) == nil, "stem \(s): worker vs session")

                let (ws, rs) = worst(stems, f.f32("stems.\(s)"))
                let (wp, rp) = worst(pitched, f.f32("pitched.\(s)"))
                print("stemSeparator \(Stem.allCases[s].label): worst stems \(ws) (RMS \(rs)), pitched \(wp) (RMS \(rp))")
                // Bit for bit: the session is, and the mix-down and the pitch
                // filters are the same Float/Double steps as the Kotlin's.
                #expect(mismatch(stems, f.f32("stems.\(s)"), abs: 0) == nil, "stem \(s)")
                #expect(mismatch(pitched, f.f32("pitched.\(s)"), abs: 0) == nil, "pitched \(s)")
            }
        }

        // ---- the synth splitter ----------------------------------------------

        @Test func synthModelLayoutMatchesKotlin() throws {
            let f = try Fixture.load("synthSplit")
            let loaded = try OnnxMaskNet.open(env: OrtEnvironment.getEnvironment(), modelFile: StemsFiles.synthModel)
            let l = loaded.layout
            #expect(l.sampleRate == f.int("sampleRate") && l.nFft == f.int("nFft") && l.hop == f.int("hop"))
            #expect(l.bandEdges == f.ints("bandEdges"))
            #expect(l.inputs.map(\.rawValue) == f.strings("inputs"))
        }

        /// The signal path alone: shown the Kotlin network's masks, frame by
        /// frame, the Swift SpectralSplit must compute the same band powers and
        /// the same outputs.
        @Test func spectralSplitMatchesKotlin() throws {
            let f = try Fixture.load("synthSplit")
            let layout = try fixtureLayout(f)
            let inputs = (0..<layout.inputs.count).map { f.f32("input.\($0)") }
            let net = ReplayNet(masks: f.f32("masks"), size: layout.inputs.count * layout.bands)
            let (synth, rests) = try stream(layout, net, inputs)
            let powers = net.powers.flatMap { $0 }
            report("synthSplit powers (replayed masks)", powers, f.f32("powers"))
            report("synthSplit synth (replayed masks)", synth, f.f32("synth"))
            // An FFT's rounding is a fixed fraction of full scale, so it shows
            // most in the quietest bands: worst 1.8e-4 of a band's power, on a
            // band near a millionth of its frame's loudest. vDSP is the more
            // accurate of the two transforms (3.8e-5 against the training code's
            // numpy, SpectralSplitTests; the Kotlin's bound there is 1e-3).
            let pe = powerError(powers, f.f32("powers"), size: layout.inputs.count * layout.bands)
            #expect(pe < 2e-4, "band powers differ by \(pe) relative")
            // The same rounding in the outputs: worst 1.5e-7, about two float
            // steps at the signals' level of 0.5.
            #expect(mismatch(synth, f.f32("synth"), abs: 2e-7) == nil, "synth")
            for k in rests.indices {
                report("synthSplit rest.\(k) (replayed masks)", rests[k], f.f32("rest.\(k)"))
                #expect(mismatch(rests[k], f.f32("rest.\(k)"), abs: 2e-7) == nil, "rest \(k)")
            }
        }

        /// The network alone: the Kotlin's band powers in, frame after frame,
        /// the same masks out.
        @Test func synthNetMatchesKotlin() throws {
            let f = try Fixture.load("synthSplit")
            let loaded = try OnnxMaskNet.open(env: OrtEnvironment.getEnvironment(), modelFile: StemsFiles.synthModel)
            let size = loaded.layout.inputs.count * loaded.layout.bands
            let powers = f.f32("powers")
            var masks = [Float](repeating: 0, count: powers.count)
            try powers.withUnsafeBufferPointer { p in
                try masks.withUnsafeMutableBufferPointer { m in
                    for frame in 0..<f.int("frames") {
                        try loaded.net.step(UnsafeBufferPointer(rebasing: p[frame * size ..< (frame + 1) * size]),
                                            UnsafeMutableBufferPointer(rebasing: m[frame * size ..< (frame + 1) * size]))
                    }
                }
            }
            report("synthSplit masks (Kotlin powers)", masks, f.f32("masks"))
            // Bit for bit, frame after frame: the state is carried the same way.
            #expect(mismatch(masks, f.f32("masks"), abs: 0) == nil, "masks")
        }

        /// Both together, as the splitter runs them.
        @Test func spectralSplitWithTheShippedModelMatchesKotlin() throws {
            let f = try Fixture.load("synthSplit")
            let loaded = try OnnxMaskNet.open(env: OrtEnvironment.getEnvironment(), modelFile: StemsFiles.synthModel)
            let inputs = (0..<loaded.layout.inputs.count).map { f.f32("input.\($0)") }
            let net = RecordingNet(loaded.net)
            let (synth, rests) = try stream(loaded.layout, net, inputs)
            #expect(net.powers.count == f.int("frames"))
            report("synthSplit masks (end to end)", net.masks.flatMap { $0 }, f.f32("masks"))
            report("synthSplit synth (end to end)", synth, f.f32("synth"))
            for k in rests.indices { report("synthSplit rest.\(k) (end to end)", rests[k], f.f32("rest.\(k)")) }
            let pe = powerError(net.powers.flatMap { $0 }, f.f32("powers"), size: loaded.layout.inputs.count * loaded.layout.bands)
            #expect(pe < 2e-4, "band powers differ by \(pe) relative")
            // The network's answer to those slightly different powers: worst
            // 1.0e-5 of a gain, on masks whose RMS is 0.67. Shown the Kotlin's
            // powers it answers bit for bit (synthNetMatchesKotlin), so this is
            // all FFT rounding seen through the network.
            #expect(mismatch(net.masks.flatMap { $0 }, f.f32("masks"), abs: 2e-5) == nil, "masks")
            // …and in what the masks let through: worst 2.5e-6 on outputs of RMS 0.05–0.2.
            #expect(mismatch(synth, f.f32("synth"), abs: 3e-6) == nil, "synth")
            for k in rests.indices { #expect(mismatch(rests[k], f.f32("rest.\(k)"), abs: 3e-6) == nil, "rest \(k)") }
        }

        @Test func synthSplitterMatchesKotlin() throws {
            let input = try Fixture.load("synthSplit")
            let f = try Fixture.load("synthSplitter")
            let vocals = input.f32("input.0")
            let other = input.f32("input.1")
            let rings: [Stem: HistoryRing] = [.vocals: HistoryRing(capacity: StemSeparator.history),
                                              .other: HistoryRing(capacity: StemSeparator.history)]
            let model = StemsFiles.synthModel
            let splitter = SynthSplitter(open: { try OnnxMaskNet.open(env: OrtEnvironment.getEnvironment(), modelFile: model) },
                                         source: { rings[$0]! })
            splitter.start()
            #expect(waitFor(seconds: 10) { if case .running = splitter.status { true } else { false } }, "the model did not load")
            let outputs = try #require(splitter.outputs)
            let layoutHop = outputs.layout.hop
            let hop = StemSeparator.hop
            var written = 0
            while written < vocals.count {
                rings[.vocals]!.write(vocals, offset: written, count: hop)
                rings[.other]!.write(other, offset: written, count: hop)
                written += hop
                splitter.wake(Int64(written))
                if written % layoutHop == 0 {
                    let want = Int64(written - 2 * layoutHop)
                    _ = waitFor(seconds: 10) { outputs.raw.values.allSatisfy { $0.written >= want } }
                }
            }
            let end = Int64(f.int("end"))
            _ = waitFor(seconds: 10) { (Array(outputs.raw.values) + Array(outputs.pitched.values)).allSatisfy { $0.written >= end } }
            let st = splitter.status
            splitter.stop()
            if case let .running(_, skips) = st { #expect(skips == 0) } else { Issue.record("splitter: \(st)") }
            #expect(splitter.cleanFrom == Int64(f.int("cleanFrom")))
            #expect(outputs.stems.map(\.rawValue) == f.strings("lanes"))
            for stem in outputs.stems {
                var raw = [Float](repeating: 0, count: Int(end))
                var pitched = [Float](repeating: 0, count: Int(end))
                #expect(outputs.raw[stem]!.written == end)
                #expect(outputs.raw[stem]!.read(end: end, into: &raw, count: Int(end)))
                #expect(outputs.pitched[stem]!.read(end: end, into: &pitched, count: Int(end)))
                report("synthSplitter raw \(stem.rawValue)", raw, f.f32("raw.\(stem.rawValue)"))
                report("synthSplitter pitched \(stem.rawValue)", pitched, f.f32("pitched.\(stem.rawValue)"))
                // The same FFT rounding through the network as end to end
                // above, from the splitter's own start: worst 7.0e-7 raw, 5.8e-7
                // pitched (the low-pass only smooths it).
                #expect(mismatch(raw, f.f32("raw.\(stem.rawValue)"), abs: 1e-6) == nil, "raw \(stem)")
                #expect(mismatch(pitched, f.f32("pitched.\(stem.rawValue)"), abs: 1e-6) == nil, "pitched \(stem)")
            }
        }

        // ------------------------------------------------------------------------

        /// Runs the fixture's input through a fresh StemSession, hop by hop.
        private func runSession(_ f: Fixture) throws -> [Float] {
            let hops = f.int("hops")
            let input = f.f32("input")
            let session = try StemSession.open(env: OrtEnvironment.getEnvironment(), modelFile: StemsFiles.stemModel!,
                                               threads: f.int("threads"))
            var out = [Float](repeating: 0, count: hops * StemSession.outSize)
            try input.withUnsafeBufferPointer { inp in
                try out.withUnsafeMutableBufferPointer { o in
                    for h in 0..<hops {
                        try session.separate(inp.baseAddress! + h * StemSession.audioSize, o.baseAddress! + h * StemSession.outSize)
                    }
                }
            }
            return out
        }

        private func fixtureLayout(_ f: Fixture) throws -> SplitLayout {
            try SplitLayout(sampleRate: f.int("sampleRate"), nFft: f.int("nFft"), hop: f.int("hop"),
                            bandEdges: f.ints("bandEdges"), inputs: f.strings("inputs").map { Stem(rawValue: $0)! })
        }

        /// The worst error of band powers relative to each value, floored at a
        /// millionth of its frame's loudest band — the measure the Kotlin's test
        /// against the training code uses. An FFT's rounding is a fixed fraction
        /// of full scale, so a band far below the frame's loudest one carries a
        /// relative error no comparison of the rounding itself would.
        private func powerError(_ got: [Float], _ want: [Float], size: Int) -> Double {
            var worst = 0.0
            for f in 0..<(want.count / size) {
                let frame = want[f * size ..< (f + 1) * size]
                let scale = Double(frame.max()!)
                for i in frame.indices {
                    worst = max(worst, Double(abs(got[i] - want[i])) / max(Double(abs(want[i])), 1e-6 * scale))
                }
            }
            return worst
        }

        private func report(_ what: String, _ a: [Float], _ b: [Float]) {
            let (w, rms) = worst(a, b)
            var rel: Float = 0
            for i in 0..<min(a.count, b.count) where b[i] != 0 { rel = max(rel, abs(a[i] - b[i]) / abs(b[i])) }
            print("\(what): worst \(w) abs, \(rel) rel (reference RMS \(rms))")
        }
    }
}
