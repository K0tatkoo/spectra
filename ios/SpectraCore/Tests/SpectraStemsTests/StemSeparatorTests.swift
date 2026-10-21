import Foundation
import Synchronization
import Testing
@testable import SpectraStems

extension StemsTests {
    /// Runs the real `StemSeparator` — its worker thread, its state carrying, its
    /// ring buffers — against the real model on the Mac.
    ///
    /// The sources are synthetic, so what "correct" means is simple: a 2A03-style
    /// triangle bass should come out of the bass stem, a vibrato tone out of vocals,
    /// a kick out of drums, and the four stems must add back up to the input.
    @Suite(.enabled(if: StemsFiles.stemModel != nil, Comment(rawValue: StemsFiles.noStemModel)))
    struct StemSeparatorTests {
        private let rate = StemSeparator.sampleRate

        @Test func stemsAddBackUpToTheMixSampleAligned() throws {
            let mix = TestSignals.render(2.0, rate) {
                0.3 * TestSignals.nesTriangle(55.0, $0) + 0.2 * TestSignals.vibrato(330.0, $0) + 0.4 * TestSignals.kick($0)
            }
            let out = try run(mix)
            let n = out[0].count
            var worst = 0.0
            for i in 0..<n {
                var sum = 0.0
                for s in out { sum += Double(s[i]) }
                worst = max(worst, abs(sum - Double(mix[i])))
            }
            print("reconstruction: worst error \(worst) over \(n) samples")
            // The graph computes Other as the mix minus the rest, so this is exact
            // to rounding — *if* the hops are carried, ordered and aligned right.
            #expect(worst < 1e-4, "stems do not reconstruct the mix (worst \(worst))")
        }

        @Test func eachSourceLandsInItsOwnStem() throws {
            let bass = TestSignals.render(3.0, rate) { 0.3 * TestSignals.nesTriangle(55.0, $0) }
            let voice = TestSignals.render(3.0, rate) { 0.2 * TestSignals.vibrato(330.0, $0) }
            let kick = TestSignals.render(3.0, rate) { 0.4 * TestSignals.kick($0) }
            let mix = (0..<bass.count).map { bass[$0] + voice[$0] + kick[$0] }
            let out = try run(mix)

            // Skip the first half second: the model's memory starts from silence.
            let from = rate / 2
            let corrBass = correlation(out[Stem.bass.ordinal], bass, from)
            let corrVoice = correlation(out[Stem.vocals.ordinal], voice, from)
            let corrKick = correlation(out[Stem.drums.ordinal], kick, from)
            print("correlation with source: bass \(corrBass), vocals \(corrVoice), drums \(corrKick)")
            #expect(corrBass > 0.9, "bass stem is not the triangle (\(corrBass))")
            #expect(corrVoice > 0.9, "vocal stem is not the vibrato tone (\(corrVoice))")
            // Lower bar for the kick: an 808 is half drum, half bass note, and the
            // model — reasonably — hands some of its sub tail to the bass stem.
            // Alone it goes to drums almost entirely; in a mix it shares.
            #expect(corrKick > 0.7, "drum stem is not the kick (\(corrKick))")
        }

        @Test func fallsBehindGracefullyInsteadOfDrifting() throws {
            let sep = StemSeparator(modelFile: StemsFiles.stemModel!)
            sep.start()
            try awaitRunning(sep)
            // Three seconds dumped at once is ten times the backlog the worker will
            // accept: it must skip to the present rather than work through it all…
            let dump = TestSignals.render(3.0, rate) { 0.3 * TestSignals.nesTriangle(110.0, $0) }
            sep.push(left: dump, right: dump, frames: dump.count)
            // …and then carry on normally with audio that arrives at capture pace.
            let tail = TestSignals.render(0.5, rate) { 0.3 * TestSignals.nesTriangle(110.0, $0) }
            var pushed = 0
            while pushed < tail.count {
                let n = min(1882, tail.count - pushed)
                let l = Array(tail[pushed ..< pushed + n])
                sep.push(left: l, right: l, frames: n)
                pushed += n
                Thread.sleep(forTimeInterval: Double(n * 1000 / rate) / 1000) // real time: a block every 43 ms
            }
            _ = waitFor(seconds: 10) { sep.stems[0].written >= Int64(tail.count - 2048) }
            let st = sep.status
            let separated = sep.stems[0].written
            sep.stop()
            if case let .running(_, skips, _) = st {
                #expect(skips >= 1, "worker did not skip ahead: \(st)")
            } else {
                Issue.record("worker is not running: \(st)")
            }
            #expect(separated >= Int64(tail.count - 2048), "worker stalled after skipping (\(separated))")
            // It separated roughly the paced tail, not the three seconds it skipped.
            #expect(separated < Int64(tail.count + StemSeparator.maxBacklogFrames), "worker worked through the dump (\(separated))")
        }

        @Test func aMissingModelSaysSo() {
            let sep = StemSeparator(modelFile: URL(fileURLWithPath: "/nonexistent/stemgen-rt-hop128.onnx"))
            sep.start()
            _ = waitFor(seconds: 10) { if case .failed = sep.status { true } else { false } }
            let st = sep.status
            sep.stop()
            if case let .failed(message) = st {
                print("missing model: \(message)")
                #expect(message.hasPrefix("The stem model could not be loaded: "))
                #expect(!sep.isRunning)
            } else {
                Issue.record("expected a failure, got \(st)")
            }
        }

        // ------------------------------------------------------------------------

        /// Pushes `mono` as stereo in capture-sized blocks, paced like a real
        /// capture would be (never more than one block ahead of the worker), and
        /// returns the four stems, aligned with the input.
        private func run(_ mono: [Float]) throws -> [[Float]] {
            let sep = StemSeparator(modelFile: StemsFiles.stemModel!)
            sep.start()
            try awaitRunning(sep)
            let block = 1882 // 1024 frames at 48 kHz, resampled
            var out = (0..<StemSeparator.modelStems).map { _ in [Float](repeating: 0, count: mono.count) }
            var collected: Int64 = 0
            var pushed = 0
            let t0 = Date()
            while pushed < mono.count {
                let n = min(block, mono.count - pushed)
                let l = Array(mono[pushed ..< pushed + n])
                sep.push(left: l, right: l, frames: n)
                pushed += n
                // Wait for the worker to catch up to within one hop.
                let target = Int64((pushed / StemSeparator.hop) * StemSeparator.hop - StemSeparator.hop)
                _ = waitFor(seconds: 10) { sep.stems.allSatisfy { $0.written >= target } }
                // The rings only hold 1.5 s, so collect as we go.
                let end = sep.stems.map(\.written).min()!
                let count = Int(end - collected)
                if count > 0 {
                    for s in out.indices {
                        var chunk = [Float](repeating: 0, count: count)
                        #expect(sep.stems[s].read(end: end, into: &chunk, count: count))
                        out[s].replaceSubrange(Int(collected) ..< Int(end), with: chunk)
                    }
                    collected = end
                }
            }
            let elapsed = Date().timeIntervalSince(t0)
            let st = sep.status
            sep.stop()
            print("separated \(Double(mono.count) / Double(rate)) s in " + String(format: "%.2f", elapsed) + " s · \(st)")
            guard case let .running(_, skips, _) = st else {
                throw StemsError("separator failed: \(st)")
            }
            #expect(skips == 0, "the worker skipped while being fed at capture pace")
            return out.map { Array($0.prefix(Int(collected))) }
        }

        private func awaitRunning(_ sep: StemSeparator) throws {
            let deadline = Date().addingTimeInterval(30)
            while Date() < deadline {
                switch sep.status {
                case let .failed(message): throw StemsError(message)
                case .loading, .idle: Thread.sleep(forTimeInterval: 0.01)
                case .running: return
                }
            }
            throw StemsError("model did not load")
        }

        private func correlation(_ a: [Float], _ b: [Float], _ from: Int) -> Double {
            let n = min(a.count, b.count)
            var ab = 0.0, aa = 0.0, bb = 0.0
            for i in from..<n {
                ab += Double(a[i]) * Double(b[i])
                aa += Double(a[i]) * Double(a[i])
                bb += Double(b[i]) * Double(b[i])
            }
            return aa <= 0 || bb <= 0 ? 0 : ab / (aa * bb).squareRoot()
        }
    }
}

extension StemsTests {
    /// Needs no model, so it runs on every machine.
    struct StemSeparatorCallbackTests {
        private final class Count: Sendable {
            let n = Atomic<Int>(0)
        }

        /// The worker reads `onHop` once a hop. Kept in the Mutex as a bare
        /// function type, every read wrapped the stored closure two levels
        /// deeper, and on an iPhone the stems thread overflowed its stack within
        /// half a minute (SIGBUS in SynthSplitter.wake, or in the release chain
        /// when capture stopped). An hour of hops, on a worker-sized stack:
        @Test func onHopStaysShallowHowEverOftenItIsRead() {
            let sep = StemSeparator(modelFile: URL(fileURLWithPath: "/nonexistent/stemgen-rt-hop128.onnx"))
            let count = Count()
            sep.onHop = { _ in count.n.add(1, ordering: .relaxed) }
            let hops = 1_250_000 // 2.9 ms each
            let done = DispatchSemaphore(value: 0)
            let worker = Thread {
                for i in 0..<hops { sep.onHop?(Int64(i)) }
                done.signal()
            }
            worker.stackSize = 512 * 1024
            worker.start()
            done.wait()
            #expect(count.n.load(ordering: .relaxed) == hops)
            sep.onHop = nil
            #expect(sep.onHop == nil)
        }
    }
}
