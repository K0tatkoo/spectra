import Foundation

/// How the synth splitter cuts audio into frames, and which stems it reads —
/// everything the app and the training code have to agree on, sample for
/// sample. It travels inside the model file (as ONNX metadata), so a retrained
/// model with a different geometry needs no app change.
///
/// Frames are `nFft` long and end every `hop` samples, at whole multiples of it
/// in the stems' own absolute index. The windows are the asymmetric pair of a
/// low-delay filter bank: a long analysis window, for the frequency resolution
/// that tells two notes apart, and a synthesis window only 2 × `hop` long at its
/// end. An output sample is final once the two frames whose synthesis windows
/// cover it are in, so the splitter lags its input by one to two hops (12–23 ms
/// at the defaults), instead of by most of the long window as with a symmetric
/// pair. The two windows multiply to a Hann of 2 × `hop`, which overlaps to
/// exactly 1 at this hop: an all-pass mask gives the input back unchanged.
///
/// `bandEdges` group the nFft / 2 + 1 bins into the bands the network sees and
/// masks: one bin per band low down, where notes are close together, wider
/// bands above.
public struct SplitLayout: Sendable {
    public let sampleRate: Int
    public let nFft: Int
    public let hop: Int
    public let bandEdges: [Int]
    /// The stems the splitter takes synth out of, in the model's order.
    public let inputs: [Stem]

    public var bins: Int { nFft / 2 + 1 }
    public var bands: Int { bandEdges.count - 1 }

    /// Throws, naming the problem, when the geometry is not one the splitter
    /// can run — it comes from a model file, so that is a load failure, not a
    /// programming error.
    public init(sampleRate: Int, nFft: Int, hop: Int, bandEdges: [Int], inputs: [Stem]) throws {
        self.sampleRate = sampleRate
        self.nFft = nFft
        self.hop = hop
        self.bandEdges = bandEdges
        self.inputs = inputs
        try require(nFft >= 2 && nFft & (nFft - 1) == 0, "nFft must be a power of two: \(nFft)")
        try require(hop > 0 && 2 * hop <= nFft, "hop must be at most half the frame: \(hop) of \(nFft)")
        try require(bandEdges.count >= 2 && bandEdges.first == 0 && bandEdges.last == nFft / 2 + 1,
                    "band edges must run from bin 0 to \(nFft / 2 + 1)")
        for i in 1..<bandEdges.count { try require(bandEdges[i] > bandEdges[i - 1], "band edges must rise") }
        try require(!inputs.isEmpty && !inputs.contains(.synth) && Set(inputs).count == inputs.count,
                    "inputs must be distinct model stems: \(inputs.map(\.rawValue))")
    }

    /// The long, asymmetric analysis window: a slow rise, then the last half of a Hann of 2 × hop.
    public func analysisWindow() -> [Float] {
        let rise = nFft - hop
        return (0..<nFft).map { n in
            let w = n < rise ? SplitLayout.hann(2 * rise, n) : SplitLayout.hann(2 * hop, n - (nFft - 2 * hop))
            return Float(w.squareRoot())
        }
    }

    /// Zero but for the last 2 × hop samples, where analysis × synthesis is a Hann of that length.
    public func synthesisWindow() -> [Float] {
        let analysis = analysisWindow()
        let start = nFft - 2 * hop
        return (0..<nFft).map { n in
            if n < start { return 0 }
            // Only zero where the product is too (the symmetric case, n = 0).
            if n < nFft - hop {
                return analysis[n] == 0 ? 0 : Float(SplitLayout.hann(2 * hop, n - start) / Double(analysis[n]))
            }
            return Float(SplitLayout.hann(2 * hop, n - start).squareRoot())
        }
    }

    /// The metadata key that marks a model file as a synth splitter, and its contract version.
    public static let formatKey = "spectra.format"
    public static let format = "synth-split/1"

    /// Periodic Hann: the one whose copies a hop of half its length apart sum to exactly 1.
    public static func hann(_ length: Int, _ n: Int) -> Double {
        0.5 - 0.5 * cos(2.0 * Double.pi * Double(n) / Double(length))
    }

    /// Reads a layout from the model's metadata. Throws, naming the problem,
    /// if the file is not a splitter this app understands.
    public static func fromMetadata(_ meta: [String: String]) throws -> SplitLayout {
        let format = meta[formatKey]
        try require(format == SplitLayout.format,
                    "not a synth-split model this app can run (format \(format ?? "null"))")
        func int(_ key: String) throws -> Int {
            // Kotlin's toIntOrNull: a 32-bit Int or nothing.
            guard let s = meta[key], let v = Int32(s.trimmingCharacters(in: .whitespacesAndNewlines)) else {
                throw StemsError("model metadata lacks \(key)")
            }
            return Int(v)
        }
        guard let edgeList = meta["band_edges"] else { throw StemsError("model metadata lacks band_edges") }
        let edges = try edgeList.split(separator: ",", omittingEmptySubsequences: false).map { part -> Int in
            let s = part.trimmingCharacters(in: .whitespacesAndNewlines)
            guard let v = Int32(s) else { throw StemsError("For input string: \"\(s)\"") }
            return Int(v)
        }
        guard let inputList = meta["inputs"] else { throw StemsError("model metadata lacks inputs") }
        let inputs = try inputList.split(separator: ",", omittingEmptySubsequences: false).map { part -> Stem in
            let name = part.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
            guard let stem = Stem(rawValue: name) else { throw StemsError("No enum constant Stem.\(name)") }
            return stem
        }
        return try SplitLayout(sampleRate: int("sample_rate"), nFft: int("n_fft"), hop: int("hop"),
                               bandEdges: edges, inputs: inputs)
    }
}
