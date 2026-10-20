import Foundation
import OnnxRuntimeBindings

/// One ONNX Runtime session of the stem model, and every tensor it will ever touch.
///
/// The model is called 345 times a second, so whatever a call costs besides the
/// network itself adds up. Everything is allocated once, here: the audio goes into
/// one fixed input, the stems come out of one fixed output, and the eight carried
/// states ping-pong between two fixed sets — each call reads one set, and ONNX
/// Runtime writes the next states straight into the other ("pinned" outputs). The
/// obvious way, a fresh input tensor and a fresh result every call, measured about
/// 5 % slower per call on an M1 for bit-identical output (in the Kotlin, on the
/// JVM), and every one of those objects is an allocation on the worker.
///
/// Here the tensors are ORTValues over NSMutableData that this class writes
/// and reads in place, and the run is `runPinned`: the Objective-C
/// `runWithInputs:outputs:runOptions:error:` with dictionaries and run options
/// built once.
///
/// The model's memory carries from one call to the next, including across a jump
/// in the input: `StemSeparator` relies on that when it skips ahead.
///
/// No app types, so the unit tests drive this directly on the Mac.
/// Not thread safe: the stem worker owns it. Dropping the last reference
/// releases the session and its tensors (Kotlin's `close()`).
public final class StemSession {
    private let session: ORTSession
    private let runOptions: ORTRunOptions
    private let audio: UnsafeMutablePointer<Float>
    private let separated: UnsafeMutablePointer<Float>
    private let feeds: [NSDictionary]
    private let pins: [NSDictionary]

    private var current = 0

    private init(session: ORTSession, runOptions: ORTRunOptions, audio: UnsafeMutablePointer<Float>,
                 separated: UnsafeMutablePointer<Float>, feeds: [NSDictionary], pins: [NSDictionary]) {
        self.session = session
        self.runOptions = runOptions
        self.audio = audio
        self.separated = separated
        self.feeds = feeds
        self.pins = pins
    }

    /// Separates one hop. `planar` holds `StemSeparator.hop` left samples, then as
    /// many right; `out` receives `outSize` floats — four stems, in the model's
    /// order, each two channels of one hop. The stems lag the input by one call.
    public func separate(_ planar: UnsafePointer<Float>, _ out: UnsafeMutablePointer<Float>) throws {
        audio.update(from: planar, count: StemSession.audioSize)
        try runPinned(session, feeds[current], pins[current], runOptions)
        current = 1 - current
        out.update(from: separated, count: StemSession.outSize)
    }

    /// The same, on arrays: `planar` at least 2 × hop long, `out` at least `outSize`.
    public func separate(_ planar: [Float], _ out: inout [Float]) throws {
        precondition(planar.count >= StemSession.audioSize && out.count >= StemSession.outSize)
        try planar.withUnsafeBufferPointer { p in
            try out.withUnsafeMutableBufferPointer { o in try separate(p.baseAddress!, o.baseAddress!) }
        }
    }

    /// Floats per call handed back by `separate`.
    public static let outSize = 4 * 2 * StemSeparator.hop
    static let audioSize = 2 * StemSeparator.hop

    // The model's contract. Internal so the tests can call the model the
    // plain way and hold this class to the same answer.
    static let inAudio = "audio_chunk"
    static let outSeparated = "separated_chunk"
    static let nextPrefix = "next_"
    static let audioShape: [Int64] = [1, 2, Int64(StemSeparator.hop)]
    private static let separatedShape: [Int64] = [1, 4, 2, Int64(StemSeparator.hop)]

    /// The eight carried states, as the model's contract names them.
    static let stateShapes: [(name: String, shape: [Int64])] = [
        ("audio_history", [1, 2, 896]),
        ("fusion_hidden", [2, 1, 1000]),
        ("spectral_numerator_tail", [1, 4, 2, 128]),
        ("waveform_tail", [1, 4, 2, 128]),
        ("attention_keys", [1, 31, 64]),
        ("attention_values", [1, 31, 128]),
        ("spec_memory_hidden", [1, 1, 500]),
        ("waveform_memory_hidden", [1, 1, 500]),
    ]

    /// Loads the model and allocates everything a call needs. Throws if it cannot.
    public static func open(env: ORTEnv, modelFile: URL, threads: Int) throws -> StemSession {
        let session = try ORTSession(env: env, modelPath: modelFile.path, sessionOptions: sessionOptions(threads: threads))
        let audioIn = try FloatTensor(shape: audioShape)
        let separatedOut = try FloatTensor(shape: separatedShape)
        // Fresh tensors start zeroed, which is exactly the model's initial state.
        let sets = try (0..<2).map { _ in try stateShapes.map { ($0.name, try FloatTensor(shape: $0.shape)) } }
        // Call i reads set i and writes set 1 - i.
        let feeds = (0..<2).map { i -> NSDictionary in
            let d = NSMutableDictionary()
            d[inAudio] = audioIn.value
            for (name, t) in sets[i] { d[name] = t.value }
            return d.copy() as! NSDictionary
        }
        let pins = (0..<2).map { i -> NSDictionary in
            let d = NSMutableDictionary()
            d[outSeparated] = separatedOut.value
            for (name, t) in sets[1 - i] { d[nextPrefix + name] = t.value }
            return d.copy() as! NSDictionary
        }
        return StemSession(session: session, runOptions: try ORTRunOptions(), audio: audioIn.data,
                           separated: separatedOut.data, feeds: feeds, pins: pins)
    }
}
