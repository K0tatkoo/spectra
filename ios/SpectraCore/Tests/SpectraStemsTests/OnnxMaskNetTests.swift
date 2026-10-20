import Foundation
import Testing
import OnnxRuntimeBindings
@testable import SpectraStems

extension StemsTests {
    /// The splitter model's contract, from the app's side of it: a tiny untrained
    /// network exported by `train/export.py --contract-fixture`, loaded the way the
    /// phone loads the real one, must read its layout from the metadata, carry its
    /// state and answer every frame exactly as PyTorch did.
    @Suite struct OnnxMaskNetTests {

        @Test func anExportedNetworkRunsHereAsItDidInTraining() throws {
            let model = StemsFiles.synthsplit("net-fixture.onnx")
            let meta = try String(contentsOf: StemsFiles.synthsplit("net-fixture.json"), encoding: .utf8)
            let json = try JSONSerialization.jsonObject(with: Data(meta.utf8)) as! [String: Any]
            let frames = (json["frames"] as! NSNumber).intValue
            let features = (json["features"] as! NSNumber).intValue
            let floats = try StemsFiles.floats(StemsFiles.synthsplit("net-fixture.bin"))
            let powers = Array(floats[0 ..< frames * features])
            let masks = Array(floats[frames * features ..< 2 * frames * features])

            let loaded = try OnnxMaskNet.open(env: OrtEnvironment.getEnvironment(), modelFile: model)
            #expect(loaded.layout.inputs == [.vocals, .other])
            #expect(features == loaded.layout.inputs.count * loaded.layout.bands)
            var out = [Float](repeating: 0, count: features)
            var worst = 0.0
            for f in 0..<frames {
                let p = Array(powers[f * features ..< (f + 1) * features])
                try p.withUnsafeBufferPointer { pp in
                    try out.withUnsafeMutableBufferPointer { try loaded.net.step(pp, $0) }
                }
                for i in 0..<features { worst = max(worst, Double(abs(out[i] - masks[f * features + i]))) }
            }
            print("contract fixture: worst mask difference \(worst) over \(frames) frames")
            // Frame after frame, so a state that was not carried shows up at
            // once. The Kotlin's bound, unchanged.
            #expect(worst < 1e-5, "masks differ from PyTorch by \(worst)")
        }

        /// What the Java API reads off a session and this port reads from the
        /// file: the metadata and the shapes, for the model the app ships.
        @Test func modelInfoReadsTheShippedModel() throws {
            let info = try OnnxModelInfo(contentsOf: StemsFiles.synthModel)
            #expect(info.metadata[SplitLayout.formatKey] == SplitLayout.format)
            let layout = try SplitLayout.fromMetadata(info.metadata)
            let size = Int64(layout.inputs.count * layout.bands)
            #expect(info.inputs[OnnxMaskNet.inPower]?.reduce(1, *) == size)
            #expect(info.outputs[OnnxMaskNet.outMask] == info.inputs[OnnxMaskNet.inPower])
            for (name, shape) in info.inputs where name != OnnxMaskNet.inPower {
                #expect(info.outputs[OnnxMaskNet.nextPrefix + name] == shape, "\(name)")
            }
            print("synth model: \(layout.nFft)-point frames every \(layout.hop), \(layout.bands) bands, "
                + "inputs \(layout.inputs.map(\.rawValue)), states \(info.inputs.filter { $0.key != OnnxMaskNet.inPower })")
        }

        @Test func aFileThatIsNotASplitterSaysSo() throws {
            // The stem model's contract is not a splitter's: no metadata, no `power`.
            let url = StemsFiles.synthsplit("dsp-fixture.json")
            #expect(throws: (any Error).self) { _ = try OnnxMaskNet.open(env: OrtEnvironment.getEnvironment(), modelFile: url) }
        }
    }
}
