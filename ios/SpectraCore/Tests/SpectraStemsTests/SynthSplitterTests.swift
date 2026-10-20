import Foundation
import Testing
import SpectraCore
@testable import SpectraStems

extension StemsTests {
    /// The splitter's worker — its thread, its wake-ups, its jumps — with a
    /// stand-in network that calls everything in vocals synth and nothing in other.
    /// With that answer the synth lane must be the vocals stem, sample for sample,
    /// on the stems' own index: which is what keeps a held-still lane's trigger
    /// pointing at the right sample.
    @Suite struct SynthSplitterTests {

        /// Hops written between the test's 1 ms rests. The Kotlin's pace, a rest
        /// every four hops, feeds the splitter about eleven times faster than real
        /// time, and a release build keeps up with that easily (144 µs a frame,
        /// StemsTiming). A debug build's signal path is some fifty times slower
        /// (0.86 ms a frame) and could not, so there the test rests after every
        /// hop: still three times real time, and still nothing but the wake to
        /// get the splitter going again.
        #if DEBUG
        static let hopsPerRest = 1
        #else
        static let hopsPerRest = 4
        #endif

        /// The stem worker's side of it: two source rings, every sample kept.
        final class Feed: @unchecked Sendable {
            let layout = try! SplitLayout(sampleRate: StemSeparator.sampleRate, nFft: 2048, hop: 512,
                                          bandEdges: (0..<129).map { $0 * 8 } + [1025], inputs: [.vocals, .other])
            let vocals = HistoryRing(capacity: StemSeparator.history)
            let other = HistoryRing(capacity: StemSeparator.history)
            var rng = SplitMix(seed: 11)
            var v = [Float](repeating: 0, count: StemSeparator.hop)
            var o = [Float](repeating: 0, count: StemSeparator.hop)
            /// Every sample ever written, so the outputs can be checked at any index.
            var allV: [Float] = []
            var allO: [Float] = []

            func splitter() -> SynthSplitter {
                let layout = self.layout
                return SynthSplitter(open: { SplitModel(layout: layout, net: VocalsAreSynth(bands: layout.bands)) },
                                     source: { [vocals, other] stem in stem == .vocals ? vocals : other })
            }

            /// One stem-worker hop, without the wake.
            func write() {
                for i in v.indices {
                    v[i] = rng.nextFloat() - 0.5
                    o[i] = rng.nextFloat() - 0.5
                }
                allV += v
                allO += o
                vocals.write(v, offset: 0, count: v.count)
                other.write(o, offset: 0, count: o.count)
            }

            /// One stem-worker hop, then the wake the stem worker sends.
            func hop(_ split: SynthSplitter) {
                write()
                split.wake(vocals.written)
            }

            /// Worst error of the three outputs over [from, to) against the vocals-are-synth answer.
            func worstError(_ outputs: SynthSplitter.Outputs, _ from: Int64, _ to: Int64) -> Double {
                let count = Int(to - from)
                if count <= 0 { return .infinity }
                var synth = [Float](repeating: 0, count: count)
                var restV = [Float](repeating: 0, count: count)
                var restO = [Float](repeating: 0, count: count)
                guard outputs.raw[.synth]!.read(end: to, into: &synth, count: count),
                      outputs.raw[.vocals]!.read(end: to, into: &restV, count: count),
                      outputs.raw[.other]!.read(end: to, into: &restO, count: count) else { return .infinity }
                var worst = 0.0
                for i in 0..<count {
                    let at = Int(from) + i
                    worst = max(worst, Double(abs(synth[i] - allV[at])), Double(abs(restV[i])), Double(abs(restO[i] - allO[at])))
                }
                return worst
            }
        }

        private func awaitRunning(_ split: SynthSplitter) {
            _ = waitFor(seconds: 10) { if case .running = split.status { true } else { false } }
        }

        @Test func theSynthLaneIsOnTheStemsOwnIndex() throws {
            let feed = Feed()
            let split = feed.splitter()
            split.start()
            awaitRunning(split)
            for i in 0..<400 {
                feed.hop(split)
                if i % Self.hopsPerRest == Self.hopsPerRest - 1 { Thread.sleep(forTimeInterval: 0.001) }
            }
            let outputs = try #require(split.outputs)
            #expect(outputs.stems == [.synth, .vocals, .other])
            let end = feed.vocals.written - 2 * Int64(feed.layout.hop)
            #expect(waitFor(seconds: 10) { outputs.raw[.synth]!.written >= end }, "splitter stalled")
            let from = max(split.cleanFrom, end - 20_000)
            let worst = feed.worstError(outputs, from, end)
            let st = split.status
            split.stop()
            print("aligned run: worst error \(worst) over \(end - from) samples · \(st)")
            #expect(worst < 1e-5, "outputs are off the stems' index (worst \(worst))")
            if case let .running(_, skips) = st { #expect(skips == 0) } else { Issue.record("splitter not running: \(st)") }
            // It trails the stems by one to two hops, never more.
            #expect(outputs.raw[.synth]!.written >= feed.vocals.written - 2 * Int64(feed.layout.hop))
        }

        @Test func fallsBehindGracefullyAndStaysAlignedAfterTheJump() throws {
            let feed = Feed()
            let split = feed.splitter()
            split.start()
            awaitRunning(split)
            for _ in 0..<50 { feed.hop(split) }
            // A second of stems at once: far more than the splitter will work through.
            for _ in 0..<(StemSeparator.sampleRate / StemSeparator.hop) { feed.write() }
            split.wake(feed.vocals.written)
            for i in 0..<200 {
                feed.hop(split)
                if i % Self.hopsPerRest == Self.hopsPerRest - 1 { Thread.sleep(forTimeInterval: 0.001) }
            }
            let outputs = try #require(split.outputs)
            let end = feed.vocals.written - 2 * Int64(feed.layout.hop)
            #expect(waitFor(seconds: 10) { outputs.raw[.synth]!.written >= end }, "splitter stalled")
            let st = split.status
            let from = split.cleanFrom
            #expect(from > 50 * Int64(StemSeparator.hop) + Int64(StemSeparator.maxBacklogFrames), "clean point \(from) is not after the jump")
            let worst = feed.worstError(outputs, from, end)
            split.stop()
            guard case let .running(_, skips) = st else {
                Issue.record("splitter not running: \(st)")
                return
            }
            print("after a jump: \(skips) skip(s), worst error \(worst) from \(from) to \(end)")
            #expect(skips >= 1, "the splitter did not jump")
            #expect(worst < 1e-5, "outputs are off the stems' index after the jump (worst \(worst))")
        }

        @Test func aModelThatWillNotLoadSaysSo() {
            let ring = HistoryRing(capacity: 1024)
            let split = SynthSplitter(open: { throw StemsError("no such model") }, source: { _ in ring })
            split.start()
            _ = waitFor(seconds: 5) { if case .failed = split.status { true } else { false } }
            let st = split.status
            split.stop()
            if case let .failed(message) = st {
                #expect(message.contains("no such model"), "\(message)")
            } else {
                Issue.record("expected a failure, got \(st)")
            }
        }
    }
}
