import Foundation
import Testing
@testable import SpectraStems

extension StemsTests {
    /// The synth model the app ships (app/src/main/assets/stems/synth-split.onnx,
    /// the same file the iPhone bundles), opened the way the phone opens it: its
    /// layout must be one this app can run, it must carry its state frame after
    /// frame, and every mask must be a gain between 0 and 1. Prints what a frame
    /// costs on this Mac (the app runs it 86 times a second, on its own thread).
    @Suite struct SynthModelAssetTests {

        @Test func theShippedSynthModelLoadsAndAnswersSaneMasks() throws {
            let loaded = try OnnxMaskNet.open(env: OrtEnvironment.getEnvironment(), modelFile: StemsFiles.synthModel)
            #expect(loaded.layout.inputs == [.vocals, .other])
            let features = loaded.layout.inputs.count * loaded.layout.bands
            var rnd = SplitMix(seed: 3)
            // Band powers that drift like music's: log-normal around plausible levels.
            var level = (0..<features).map { _ in rnd.nextDouble(-8.0, 0.0) }
            var power = [Float](repeating: 0, count: features)
            var mask = [Float](repeating: 0, count: features)
            let frames = 2000
            var low: Float = 1
            var high: Float = 0
            var nanos: Int64 = 0
            var sane = true
            for f in 0..<frames {
                for i in 0..<features {
                    level[i] += rnd.nextDouble(-0.3, 0.3)
                    power[i] = Float(exp(level[i]))
                }
                let t = nanoTime()
                try power.withUnsafeBufferPointer { p in try mask.withUnsafeMutableBufferPointer { try loaded.net.step(p, $0) } }
                if f >= 100 { nanos += nanoTime() - t }
                for m in mask {
                    if !(m >= 0 && m <= 1) { sane = false }
                    low = min(low, m)
                    high = max(high, m)
                }
            }
            let perFrame = Double(nanos) / Double(frames - 100) / 1000.0
            print("synth model: masks in [\(low), \(high)], " + String(format: "%.0f us a frame here (%.1f %% of one core at 86 fps)", perFrame, perFrame * 86 / 1e4))
            #expect(sane, "a mask is not a gain")
            #expect(high - low > 0.05, "the masks never move: is the state carried?")
        }
    }
}
