import Foundation
import Testing
import SpectraCore
@testable import SpectraStems

/// Every stems test, one at a time. Several of them run the real stem model on
/// worker threads and require it to keep up with a paced feed, which it does
/// with cores to spare — unless the other stems tests are running beside it.
@Suite(.serialized) struct StemsTests {}

/// Where the tests find what they need outside this folder: the models and the
/// training code's fixtures. Located from this file, never by absolute path.
enum StemsFiles {
    /// The repository root: the first folder above this file with settings.gradle.kts.
    static let repoRoot: URL = {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        while dir.path != "/" {
            if FileManager.default.fileExists(atPath: dir.appendingPathComponent("settings.gradle.kts").path) { return dir }
            dir = dir.deletingLastPathComponent()
        }
        fatalError("not inside the Spectra repository: \(#filePath)")
    }()

    /// The StemgenRT model, if this machine has fetched it: not in git (37.5 MB).
    /// The Android build caches it in ~/.gradle/caches/spectra/ (so does
    /// ios/tools/stem-model.sh); SPECTRA_STEM_MODEL overrides.
    static let stemModel: URL? = {
        var candidates: [URL] = []
        if let env = ProcessInfo.processInfo.environment["SPECTRA_STEM_MODEL"] { candidates.append(URL(fileURLWithPath: env)) }
        candidates.append(FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent(".gradle/caches/spectra/stemgen-rt-\(StemSeparator.sha256.prefix(16)).onnx"))
        candidates.append(repoRoot.appendingPathComponent("app/build/generated/stemModel/stems/\(StemSeparator.asset)"))
        return candidates.first { url in
            let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.int64Value
            return size == StemSeparator.bytes
        }
    }()

    static let noStemModel = "the StemgenRT model is not on this Mac (~/.gradle/caches/spectra/stemgen-rt-08424ca91feae8d4.onnx): "
        + "run ios/tools/stem-model.sh or ./gradlew :app:fetchStemModel, or set SPECTRA_STEM_MODEL"

    /// The synth model the app ships (in git).
    static var synthModel: URL { repoRoot.appendingPathComponent("app/src/main/assets/stems/synth-split.onnx") }

    /// The training code's fixtures, shared with the Kotlin tests.
    static func synthsplit(_ name: String) -> URL {
        repoRoot.appendingPathComponent("app/src/test/resources/synthsplit/\(name)")
    }

    /// A little-endian float32 file.
    static func floats(_ url: URL) throws -> [Float] {
        let data = try Data(contentsOf: url)
        return data.withUnsafeBytes { raw in
            (0..<data.count / 4).map { Float(bitPattern: UInt32(littleEndian: raw.loadUnaligned(fromByteOffset: $0 * 4, as: UInt32.self))) }
        }
    }
}

/// Synthetic sources the tests build mixes from — TestSignals.kt. All mono, −1…1.
enum TestSignals {
    static func saw(_ hz: Double, _ t: Double) -> Double { 2.0 * (t * hz - (0.5 + t * hz).rounded(.down)) }

    /// The 2A03's 32-step triangle — the staircase, not a straight ramp.
    static func nesTriangle(_ hz: Double, _ t: Double) -> Double {
        var u = (t * hz).truncatingRemainder(dividingBy: 1.0)
        if u < 0 { u += 1.0 }
        let step = coerceIn(ktToInt(u * 32), 0, 31)
        let level = step < 16 ? 15 - step : step - 16
        return (Double(level) - 7.5) / 7.5
    }

    /// A sung-ish tone: a sine with a 5 Hz vibrato of ±6 Hz.
    static func vibrato(_ hz: Double, _ t: Double) -> Double { sin(2 * Double.pi * hz * t - 1.2 * cos(2 * Double.pi * 5 * t)) }

    /// An 808-ish kick every `every` seconds: a falling sine with a fast decay.
    static func kick(_ t: Double, every: Double = 0.5) -> Double {
        let tt = t.truncatingRemainder(dividingBy: every)
        if tt > 0.25 { return 0.0 }
        return sin(2 * Double.pi * (50 * tt + 80 * (1 - exp(-tt * 30)) / 30)) * exp(-tt * 12)
    }

    static func render(_ seconds: Double, _ rate: Int, _ f: (Double) -> Double) -> [Float] {
        (0..<Int(seconds * Double(rate))).map { Float(f(Double($0) / Double(rate))) }
    }
}

/// A seeded generator for the tests' random inputs. Not kotlin.random.Random —
/// these tests only need *some* noise, not the Kotlin's exact samples.
struct SplitMix {
    private var state: UInt64
    init(seed: UInt64) { state = seed }

    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }

    /// Uniform in [0, 1).
    mutating func nextFloat() -> Float { Float(next() >> 40) / Float(1 << 24) }

    /// Uniform in [lo, hi).
    mutating func nextDouble(_ lo: Double, _ hi: Double) -> Double { lo + (hi - lo) * Double(next() >> 11) / Double(1 << 53) }
}

/// Buffers the tests hand to the pointer-based API, freed together.
final class Scratch {
    private var all: [UnsafeMutableBufferPointer<Float>] = []

    func floats(_ count: Int) -> UnsafeMutableBufferPointer<Float> {
        let b = UnsafeMutableBufferPointer<Float>.allocate(capacity: count)
        b.initialize(repeating: 0)
        all.append(b)
        return b
    }

    deinit { for b in all { b.deallocate() } }
}

/// Polls `condition` every millisecond until it holds or `seconds` pass.
func waitFor(seconds: Double, _ condition: () -> Bool) -> Bool {
    let deadline = Date().addingTimeInterval(seconds)
    while !condition() {
        if Date() > deadline { return false }
        Thread.sleep(forTimeInterval: 0.001)
    }
    return true
}

/// Worst |a − b| and the RMS of `b`, for printing how close two runs are.
func worst(_ a: [Float], _ b: [Float]) -> (abs: Float, rms: Float) {
    var w: Float = 0
    var e = 0.0
    for i in 0..<min(a.count, b.count) {
        w = max(w, abs(a[i] - b[i]))
        e += Double(b[i]) * Double(b[i])
    }
    return (w, Float((e / Double(max(1, b.count))).squareRoot()))
}

// ---- the synth splitter's signal path ---------------------------------------

/// Runs whole signals through `SpectralSplit` from their first sample, the way
/// the training code's reference does: frames end at every hop, zeros before
/// the start. Returns synth and rests for [0, length − hop).
func stream(_ layout: SplitLayout, _ net: MaskNet, _ inputs: [[Float]]) throws -> (synth: [Float], rests: [[Float]]) {
    let n = layout.nFft
    let hop = layout.hop
    let total = inputs[0].count
    let split = SpectralSplit(layout: layout, net: net)
    let s = Scratch()
    let frames = inputs.map { _ in s.floats(n) }
    let synth = s.floats(hop)
    let rest = inputs.map { _ in s.floats(hop) }
    var outSynth = [Float](repeating: 0, count: total - hop)
    var outRest = inputs.map { _ in [Float](repeating: 0, count: total - hop) }
    var pos = hop
    while pos <= total {
        for k in inputs.indices {
            for i in 0..<n {
                let t = pos - n + i
                frames[k][i] = t < 0 ? 0 : inputs[k][t]
            }
        }
        try split.frame(frames, synth: synth, rest: rest)
        let at = pos - 2 * hop
        if at >= 0 {
            for i in 0..<hop {
                outSynth[at + i] = synth[i]
                for k in inputs.indices { outRest[k][at + i] = rest[k][i] }
            }
        }
        pos += hop
    }
    return (outSynth, outRest)
}

/// Every band of every input the same share.
final class ConstantNet: MaskNet {
    private let value: Float
    init(_ value: Float) { self.value = value }
    func step(_ power: UnsafeBufferPointer<Float>, _ mask: UnsafeMutableBufferPointer<Float>) throws {
        mask.update(repeating: value)
    }
}

/// Everything in the first input (vocals) is synth, nothing in the others.
final class VocalsAreSynth: MaskNet {
    private let bands: Int
    init(bands: Int) { self.bands = bands }
    func step(_ power: UnsafeBufferPointer<Float>, _ mask: UnsafeMutableBufferPointer<Float>) throws {
        for i in mask.indices { mask[i] = i < bands ? 1 : 0 }
    }
}

/// Passes every frame to `inner`, keeping a copy of what went in and what came out.
final class RecordingNet: MaskNet {
    private let inner: MaskNet
    var powers: [[Float]] = []
    var masks: [[Float]] = []
    init(_ inner: MaskNet) { self.inner = inner }
    func step(_ power: UnsafeBufferPointer<Float>, _ mask: UnsafeMutableBufferPointer<Float>) throws {
        powers.append(Array(power))
        try inner.step(power, mask)
        masks.append(Array(mask))
    }
}

/// Answers frame f with `masks[f]`, whatever it is shown — to hold the signal
/// path to a reference without the network's own rounding in the way.
final class ReplayNet: MaskNet {
    private let masks: [Float]
    private let size: Int
    var powers: [[Float]] = []
    init(masks: [Float], size: Int) {
        self.masks = masks
        self.size = size
    }
    func step(_ power: UnsafeBufferPointer<Float>, _ mask: UnsafeMutableBufferPointer<Float>) throws {
        let f = powers.count
        powers.append(Array(power))
        for i in 0..<size { mask[i] = masks[f * size + i] }
    }
}
