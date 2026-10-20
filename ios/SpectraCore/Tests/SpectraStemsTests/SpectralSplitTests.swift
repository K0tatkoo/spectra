import Foundation
import Testing
@testable import SpectraStems

extension StemsTests {
    /// The synth splitter's signal path, without a trained network: against the
    /// training code's own reference (a fixture written by
    /// train/tools/make_dsp_fixture.py, shared with SpectralSplitTest.kt), and
    /// against the two answers that need no reference at all — a mask of 0 gives
    /// every input back untouched, a mask of 1 turns all of it into synth.
    @Suite struct SpectralSplitTests {

        @Test func windowsOverlapToExactlyOneAtTheHop() throws {
            let layout = try DspFixture.load().layout
            let a = layout.analysisWindow()
            let s = layout.synthesisWindow()
            let n = layout.nFft
            let hop = layout.hop
            for i in 0..<hop {
                let sum = a[n - 2 * hop + i] * s[n - 2 * hop + i] + a[n - hop + i] * s[n - hop + i]
                #expect(abs(Double(sum) - 1.0) <= 1e-6, "overlap at \(i)")
            }
            for i in 0..<(n - 2 * hop) { #expect(s[i] == 0) }
        }

        @Test func zeroMaskGivesTheInputsBackAndFullMaskMakesThemAllSynth() throws {
            let layout = try DspFixture.load().layout
            var rng = SplitMix(seed: 7)
            let inputs = (0..<2).map { _ in (0..<8192).map { _ in (rng.nextFloat() - 0.5) * 0.6 } }
            for full in [false, true] {
                let (synth, rests) = try stream(layout, ConstantNet(full ? 1 : 0), inputs)
                var worst = 0.0
                for i in synth.indices {
                    let sum = inputs[0][i] + inputs[1][i]
                    worst = max(worst, Double(abs(synth[i] - (full ? sum : 0))))
                    for k in 0..<2 { worst = max(worst, Double(abs(rests[k][i] - (full ? 0 : inputs[k][i])))) }
                }
                print("mask \(full ? 1 : 0): worst error \(worst) over \(synth.count) samples")
                // The Kotlin's bound, unchanged: vDSP's rounding fits in it too.
                #expect(worst < 2e-6, "mask \(full): reconstruction is off by \(worst)")
            }
        }

        @Test func matchesTheTrainingCodesReferenceSampleForSample() throws {
            let fx = try DspFixture.load()
            let net = PatternNet(fx)
            let (synth, rests) = try stream(fx.layout, net, fx.inputs)

            var powerWorst = 0.0
            for f in 0..<fx.frames {
                let got = net.powers[f]
                let want = fx.powers[f]
                let scale = Double(want.max()!)
                for i in want.indices {
                    let err = Double(abs(got[i] - want[i])) / max(Double(abs(want[i])), 1e-6 * scale)
                    powerWorst = max(powerWorst, err)
                }
            }
            var outWorst = 0.0
            for i in synth.indices {
                outWorst = max(outWorst, Double(abs(synth[i] - fx.synth[i])))
                for k in rests.indices { outWorst = max(outWorst, Double(abs(rests[k][i] - fx.rests[k][i]))) }
            }
            print("against the reference: band powers " + String(format: "%.2e", powerWorst)
                + " relative, outputs " + String(format: "%.2e", outWorst))
            #expect(net.powers.count == fx.frames)
            #expect(synth.count == fx.synth.count)
            // The Kotlin's bounds, unchanged.
            #expect(powerWorst < 1e-3, "band powers differ from the reference by \(powerWorst)")
            #expect(outWorst < 1e-5, "outputs differ from the reference by \(outWorst)")
        }

        @Test func layoutRejectsWhatItCannotRun() throws {
            #expect(throws: StemsError.self) {
                try SplitLayout(sampleRate: 44100, nFft: 1000, hop: 250, bandEdges: [0, 501], inputs: [.vocals])
            }
            #expect(throws: StemsError.self) {
                try SplitLayout(sampleRate: 44100, nFft: 1024, hop: 128, bandEdges: [0, 10, 10, 513], inputs: [.vocals])
            }
            #expect(throws: StemsError.self) {
                try SplitLayout(sampleRate: 44100, nFft: 1024, hop: 128, bandEdges: [0, 513], inputs: [.synth])
            }
            #expect(throws: StemsError.self) { try SplitLayout.fromMetadata(["spectra.format": "synth-split/2"]) }
            let meta = ["spectra.format": "synth-split/1", "sample_rate": "44100", "n_fft": " 1024 ", "hop": "256",
                        "band_edges": "0, 256, 513", "inputs": "vocals, other"]
            let layout = try SplitLayout.fromMetadata(meta)
            #expect(layout.nFft == 1024 && layout.bands == 2 && layout.inputs == [.vocals, .other])
        }
    }
}

/// The fixture's stand-in network: a fixed pattern over band, input and frame. It records what it was shown.
final class PatternNet: MaskNet {
    private let fx: DspFixture
    var powers: [[Float]] = []
    init(_ fx: DspFixture) { self.fx = fx }
    func step(_ power: UnsafeBufferPointer<Float>, _ mask: UnsafeMutableBufferPointer<Float>) throws {
        let frame = powers.count
        powers.append(Array(power))
        let bands = fx.layout.bands
        for k in fx.layout.inputs.indices {
            for b in 0..<bands {
                mask[k * bands + b] = Float(0.5 + 0.5 * sin(fx.aBand * Double(b) + fx.aInput * Double(k) + fx.aFrame * Double(frame)))
            }
        }
    }
}

/// train/tools/make_dsp_fixture.py's reference: inputs, the band powers each
/// frame shows the network, and the outputs for the pattern mask.
struct DspFixture {
    let layout: SplitLayout
    let frames: Int
    let aBand: Double
    let aInput: Double
    let aFrame: Double
    let inputs: [[Float]]
    let powers: [[Float]]
    let synth: [Float]
    let rests: [[Float]]

    static func load() throws -> DspFixture {
        let json = try JSONSerialization.jsonObject(with: Data(contentsOf: StemsFiles.synthsplit("dsp-fixture.json"))) as! [String: Any]
        func int(_ key: String) -> Int { (json[key] as! NSNumber).intValue }
        let layout = try SplitLayout(
            sampleRate: int("sample_rate"), nFft: int("n_fft"), hop: int("hop"),
            bandEdges: (json["band_edges"] as! [NSNumber]).map(\.intValue),
            inputs: (json["inputs"] as! [String]).map { Stem(rawValue: $0.uppercased())! }
        )
        let samples = int("samples")
        let frames = int("frames")
        let k = layout.inputs.count
        let floats = try StemsFiles.floats(StemsFiles.synthsplit("dsp-fixture.bin"))
        var at = 0
        func take(_ count: Int) -> [Float] {
            defer { at += count }
            return Array(floats[at ..< at + count])
        }
        let inputs = (0..<k).map { _ in take(samples) }
        let powers = (0..<frames).map { _ in take(k * layout.bands) }
        let synth = take(samples - layout.hop)
        let rests = (0..<k).map { _ in take(samples - layout.hop) }
        precondition(at == floats.count, "fixture has trailing data")
        let mask = json["mask"] as! [String: NSNumber]
        return DspFixture(layout: layout, frames: frames, aBand: mask["band"]!.doubleValue, aInput: mask["input"]!.doubleValue,
                          aFrame: mask["frame"]!.doubleValue, inputs: inputs, powers: powers, synth: synth, rests: rests)
    }
}
