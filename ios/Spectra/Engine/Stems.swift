import Foundation
import OnnxRuntimeBindings
import SpectraCore
import SpectraStems

/// The stems and the held-still scopes, as the engine drives them: the ported
/// ScopeRunner, with its models from the app bundle. The StemgenRT model is
/// put there by the build (tools/stem-model.sh); the synth model and the
/// licences are the Android app's own files.
enum StemModels {
    struct Missing: Error, CustomStringConvertible {
        let description: String
    }

    /// The synth splitter's model, or nil in a build without one.
    static let synthURL = Bundle.main.url(forResource: "synth-split", withExtension: "onnx")

    static var hasSynth: Bool { synthURL != nil }

    static func makeRunner(rate: Int) -> HeldScopes {
        let modelURL: () throws -> URL = {
            guard let url = Bundle.main.url(forResource: "stemgen-rt-hop128", withExtension: "onnx") else {
                throw Missing(description: "The stem model is missing from this build.")
            }
            return url
        }
        var synthModel: (@Sendable () throws -> SplitModel)? = nil
        if let url = synthURL {
            synthModel = { @Sendable () throws -> SplitModel in
                let env: ORTEnv = try OrtEnvironment.getEnvironment()
                return try OnnxMaskNet.open(env: env, modelFile: url)
            }
        }
        return ScopeRunner(rate: rate, modelURL: modelURL, synthModel: synthModel)
    }
}

extension ScopeRunner: HeldScopes {
    func process(_ left: UnsafePointer<Float>, _ right: UnsafePointer<Float>?, frames: Int,
                 wants: ScopeWants, settings: Settings, blockMs: Float) {
        process(left, right, frames: frames, wants: Wants(stems: wants.stems, hold: wants.hold, nes: wants.nes),
                settings: settings, blockMs: blockMs)
    }
}
