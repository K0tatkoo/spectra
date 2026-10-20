import Foundation
import Testing
import OnnxRuntimeBindings
@testable import SpectraStems

extension StemsTests {
    /// `StemSession` driven directly — no worker thread, so every hop is where the
    /// test put it — against the real model on the Mac.
    @Suite(.enabled(if: StemsFiles.stemModel != nil, Comment(rawValue: StemsFiles.noStemModel)))
    struct StemSessionTests {
        private let hop = StemSeparator.hop
        private let rate = StemSeparator.sampleRate

        @Test func pinnedTensorsGiveExactlyWhatAFreshResultEveryCallGives() throws {
            let model = StemsFiles.stemModel!
            let song = song(2.0)
            let pinned = try separate(StemSession.open(env: OrtEnvironment.getEnvironment(), modelFile: model, threads: 1), song)
            let plain = try separatePlainly(model, song)
            var worst: Float = 0
            for h in plain.indices {
                guard let a = pinned[h] else { continue }
                for i in a.indices { worst = max(worst, abs(a[i] - plain[h][i])) }
            }
            print("pinned vs plain: worst difference \(worst) over \(plain.count) hops")
            #expect(worst == 0, "the reused tensors change the answer")
        }

        @Test func aSkipKeepsTheModelsMemoryAndTheStemsAreBackAtOnce() throws {
            // What the worker does when the phone falls behind: jump 150 ms ahead
            // without resetting anything. Held against a run that never skipped,
            // from one model window (23 ms) after the jump to 300 ms after it.
            // Starting over from silence, which the worker used to do, measured
            // 0.3–1.0 for drums and 1.3–3.8 for other in this window, across five
            // jump points in a similar mix; this jump keeps all four near 0.02.
            let model = StemsFiles.stemModel!
            let env = try OrtEnvironment.getEnvironment()
            let song = song(4.0)
            let at = 2 * rate / hop
            let jump = StemSeparator.maxBacklogFrames / hop
            let straight = try separate(StemSession.open(env: env, modelFile: model, threads: 1), song)
            let skipped = try separate(StemSession.open(env: env, modelFile: model, threads: 1), song, skipAt: at, jump: jump)
            let from = at + jump + StemSeparator.spliceGuardFrames / hop
            let to = at + jump + Int(0.3 * Double(rate) / Double(hop))
            let limits: [(Stem, Double)] = [(.drums, 0.1), (.bass, 0.05), (.vocals, 0.05), (.other, 0.3)]
            for (stem, limit) in limits {
                var err = 0.0
                var ref = 0.0
                for h in from..<to {
                    guard let a = skipped[h], let b = straight[h] else { continue }
                    for i in 0..<(2 * hop) {
                        let k = stem.ordinal * 2 * hop + i
                        err += Double(a[k] - b[k]) * Double(a[k] - b[k])
                        ref += Double(b[k]) * Double(b[k])
                    }
                }
                let rel = err / ref
                print("after a skip: \(stem.label) error " + String(format: "%.4f", rel) + " of its energy (limit \(limit))")
                #expect(rel < limit, "\(stem.label) has not recovered from the skip (\(rel))")
            }
        }

        // ------------------------------------------------------------------------

        /// Kick, a 2A03-style bass, a voice and a lead.
        private func song(_ seconds: Double) -> [Float] {
            TestSignals.render(seconds, rate) { t in
                0.3 * TestSignals.nesTriangle(55.0, t) + 0.2 * TestSignals.vibrato(330.0, t) +
                    0.15 * TestSignals.saw(440.0, t) + 0.4 * TestSignals.kick(t)
            }
        }

        /// Every call's output, indexed by the input hop the call was given; nil
        /// for the hops a jump at `skipAt` of `jump` hops passed over.
        private func separate(_ s: StemSession, _ mono: [Float], skipAt: Int = -1, jump: Int = 0) throws -> [[Float]?] {
            let hops = mono.count / hop
            var out = [[Float]?](repeating: nil, count: hops)
            var planar = [Float](repeating: 0, count: 2 * hop)
            var h = 0
            while h < hops {
                if h == skipAt { h += jump }
                if h >= hops { break }
                for i in 0..<hop {
                    planar[i] = mono[h * hop + i]
                    planar[hop + i] = mono[h * hop + i]
                }
                var o = [Float](repeating: 0, count: StemSession.outSize)
                try s.separate(planar, &o)
                out[h] = o
                h += 1
            }
            return out
        }

        /// The model called the plain way: a fresh input and a fresh result every call.
        private func separatePlainly(_ model: URL, _ mono: [Float]) throws -> [[Float]] {
            let env = try OrtEnvironment.getEnvironment()
            let options = try ORTSessionOptions()
            try options.setIntraOpNumThreads(1)
            try options.setGraphOptimizationLevel(.all)
            let session = try ORTSession(env: env, modelPath: model.path, sessionOptions: options)
            func tensor(_ values: [Float], _ shape: [Int64]) throws -> ORTValue {
                let data = values.withUnsafeBytes { NSMutableData(bytes: $0.baseAddress!, length: $0.count) }
                return try ORTValue(tensorData: data, elementType: .float, shape: shape.map { NSNumber(value: $0) })
            }
            var states: [String: ORTValue] = [:]
            for (name, shape) in StemSession.stateShapes {
                states[name] = try tensor([Float](repeating: 0, count: Int(shape.reduce(1, *))), shape)
            }
            let outputNames = Set([StemSession.outSeparated] + StemSession.stateShapes.map { StemSession.nextPrefix + $0.name })
            let hops = mono.count / hop
            return try (0..<hops).map { h in
                let planar = (0..<(2 * hop)).map { mono[h * hop + $0 % hop] }
                var feeds = states
                feeds[StemSession.inAudio] = try tensor(planar, StemSession.audioShape)
                let result = try session.run(withInputs: feeds, outputNames: outputNames, runOptions: nil)
                for (name, _) in StemSession.stateShapes { states[name] = result[StemSession.nextPrefix + name]! }
                let data = try result[StemSession.outSeparated]!.tensorData()
                return Array(UnsafeBufferPointer(start: data.bytes.assumingMemoryBound(to: Float.self), count: StemSession.outSize))
            }
        }
    }
}
