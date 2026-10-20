import Foundation
import Testing
import SpectraCore
@testable import SpectraStems

extension StemsTests {
    /// What the stems cost on this Mac in a release build:
    ///
    ///     swift test -c release --filter StemsTiming
    ///
    /// A debug `swift test` runs them too, with a fiftieth of the iterations, so
    /// they cannot rot; the model's numbers barely move there (ONNX Runtime is
    /// the same native code either way), the Swift around it does.
    ///
    /// The budgets: one 128-sample hop is 2.9 ms of audio at 44.1 kHz; the
    /// Kotlin measured 1.6 ms a hop on an M1 JVM and 2.4 ms in Android's ONNX
    /// Runtime build. The synth splitter runs 86 frames a second.
    @Suite struct StemsTiming {
        #if DEBUG
        private static let scale = 0.02
        #else
        private static let scale = 1.0
        #endif

        @Test(.enabled(if: StemsFiles.stemModel != nil, Comment(rawValue: StemsFiles.noStemModel)))
        func stemModelMsPerHop() throws {
            let session = try StemSession.open(env: OrtEnvironment.getEnvironment(), modelFile: StemsFiles.stemModel!, threads: 1)
            let hop = StemSeparator.hop
            let song = TestSignals.render(4.0, StemSeparator.sampleRate) { t in
                0.3 * TestSignals.nesTriangle(55.0, t) + 0.2 * TestSignals.vibrato(330.0, t) +
                    0.15 * TestSignals.saw(440.0, t) + 0.4 * TestSignals.kick(t)
            }
            let s = Scratch()
            let planar = s.floats(2 * hop)
            let out = s.floats(StemSession.outSize)
            let hops = max(50, Int(1500 * StemsTiming.scale))
            var h = 0
            func next() throws {
                let at = (h % (song.count / hop - 1)) * hop
                for i in 0..<hop {
                    planar[i] = song[at + i]
                    planar[hop + i] = song[at + i]
                }
                try session.separate(planar.baseAddress!, out.baseAddress!)
                h += 1
            }
            for _ in 0..<50 { try next() } // warm up: first-touch page faults and the allocator
            var times: [Double] = []
            times.reserveCapacity(hops)
            for _ in 0..<hops {
                let t0 = nanoTime()
                try next()
                times.append(Double(nanoTime() - t0) / 1e6)
            }
            let mean = times.reduce(0, +) / Double(times.count)
            let sorted = times.sorted()
            let p50 = sorted[sorted.count / 2]
            let p99 = sorted[min(sorted.count - 1, sorted.count * 99 / 100)]
            let hopMs = Double(hop) * 1000 / Double(StemSeparator.sampleRate)
            print("Timing: stem model (StemSession.separate, 1 thread): " + String(format: "%.3f ms per hop mean, %.3f median, %.3f p99 — load %.2f of real time (a hop is %.2f ms)", mean, p50, p99, mean / hopMs, hopMs))
            #expect(mean < hopMs * 4, "the stem model is far slower than expected here")
        }

        @Test func synthSplitterUsPerFrame() throws {
            let loaded = try OnnxMaskNet.open(env: OrtEnvironment.getEnvironment(), modelFile: StemsFiles.synthModel)
            let layout = loaded.layout
            let net = loaded.net
            let s = Scratch()
            let frames = layout.inputs.map { _ in s.floats(layout.nFft) }
            var rng = SplitMix(seed: 5)
            for f in frames { for i in f.indices { f[i] = (rng.nextFloat() - 0.5) * 0.5 } }
            let synth = s.floats(layout.hop)
            let rest = layout.inputs.map { _ in s.floats(layout.hop) }
            let power = s.floats(layout.inputs.count * layout.bands)
            let mask = s.floats(layout.inputs.count * layout.bands)
            for i in power.indices { power[i] = Float(exp(rng.nextDouble(-8, 0))) }

            let split = SpectralSplit(layout: layout, net: net)
            let n = max(50, Int(4000 * StemsTiming.scale))
            for _ in 0..<50 { try split.frame(frames, synth: synth, rest: rest) }
            var t0 = nanoTime()
            for _ in 0..<n { try split.frame(frames, synth: synth, rest: rest) }
            let whole = Double(nanoTime() - t0) / Double(n) / 1000
            t0 = nanoTime()
            for _ in 0..<n { try net.step(UnsafeBufferPointer(power), mask) }
            let network = Double(nanoTime() - t0) / Double(n) / 1000
            let dsp = SpectralSplit(layout: layout, net: ConstantNet(0.5))
            t0 = nanoTime()
            for _ in 0..<n { try dsp.frame(frames, synth: synth, rest: rest) }
            let signal = Double(nanoTime() - t0) / Double(n) / 1000
            let fps = Double(StemSeparator.sampleRate) / Double(layout.hop)
            print("Timing: synth splitter (SpectralSplit.frame + OnnxMaskNet): " + String(format: "%.0f us per frame, %.1f %% of one core at %.0f fps", whole, whole * fps / 1e4, fps))
            print(String(format: "Timing:   (network %.0f us, signal path %.0f us)", network, signal))
        }
    }
}
