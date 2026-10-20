import Dispatch
import Foundation
import OnnxRuntimeBindings
import SpectraCore
import Synchronization

// What the Kotlin stems code gets from the JVM and from ONNX Runtime's Java API,
// and the Objective-C API this port runs on does not give it in the same shape:
// an exception with a message, one process-wide environment, a run whose
// outputs land in buffers allocated once, LockSupport's park/unpark and
// System.nanoTime. Kept in one place so the ported files read like the Kotlin.

/// Kotlin's `require(…) { message }` and `error(message)`: what went wrong, in
/// the words the status line shows ("The synth model could not be loaded: …").
public struct StemsError: Error, CustomStringConvertible, Sendable {
    public let message: String
    public init(_ message: String) { self.message = message }
    public var description: String { message }
}

/// Kotlin `require`: throws `StemsError(message)` unless `condition` holds.
@inline(__always)
func require(_ condition: Bool, _ message: @autoclosure () -> String) throws {
    if !condition { throw StemsError(message()) }
}

/// Kotlin's `t.message ?: t.javaClass.simpleName`, for a Swift error. ONNX
/// Runtime's NSErrors carry its message as their localized description.
func messageOf(_ error: Error) -> String {
    if let e = error as? StemsError { return e.message }
    let ns = error as NSError
    let text = ns.localizedDescription
    return text.isEmpty ? typeNameOf(error) : text
}

/// Kotlin's `t.javaClass.simpleName`.
func typeNameOf(_ error: Error) -> String {
    error is StemsError ? "StemsError" : String(describing: type(of: error as Any))
}

// ONNX Runtime's environment is thread safe and, underneath, one per process:
// the C API's CreateEnv hands every caller the same reference-counted OrtEnv.
extension ORTEnv: @retroactive @unchecked Sendable {}

/// ONNX Runtime's Java `OrtEnvironment.getEnvironment()`: one environment for
/// the whole process, created on first use and never torn down. Tearing it
/// down when the last separator stops and building it again on the next start
/// buys nothing, and ONNX Runtime has a history of not liking it.
public enum OrtEnvironment {
    private static let shared = Result { try ORTEnv(loggingLevel: .warning) }

    /// The process's environment. Throws if ONNX Runtime cannot start here.
    public static func getEnvironment() throws -> ORTEnv { try shared.get() }
}

/// The session options both models are opened with, as the Kotlin sets them.
///
/// Two Kotlin settings have no Objective-C counterpart and need none: the
/// execution mode (sequential is ONNX Runtime's default) and the inter-op
/// thread count, which only a parallel-mode session uses.
func sessionOptions(threads: Int) throws -> ORTSessionOptions {
    let o = try ORTSessionOptions()
    try o.setIntraOpNumThreads(Int32(coerceIn(threads, 1, 4)))
    try o.setGraphOptimizationLevel(.all)
    // Workers that spin between calls burn a core for nothing: there is a gap
    // after every block while the next one is captured.
    try o.addConfigEntry(withKey: "session.intra_op.allow_spinning", value: "0")
    return o
}

/// A float tensor whose memory this side owns: ONNX Runtime reads an input
/// from it, or writes an output straight into it, and `data` is where.
///
/// The ORTValue wraps the NSMutableData's bytes without copying them (it
/// keeps the NSMutableData alive), so writing through `data` before a run is
/// writing the input, and reading it after is reading the output. A fresh
/// NSMutableData is zeroed — exactly the models' initial state.
struct FloatTensor {
    let value: ORTValue
    let data: UnsafeMutablePointer<Float>
    let count: Int

    init(shape: [Int64]) throws {
        count = Int(shape.reduce(1, *))
        guard let bytes = NSMutableData(length: count * MemoryLayout<Float>.size) else {
            throw StemsError("could not allocate a tensor of \(count) floats")
        }
        data = bytes.mutableBytes.assumingMemoryBound(to: Float.self)
        value = try ORTValue(tensorData: bytes, elementType: .float, shape: shape.map { NSNumber(value: $0) })
    }
}

/// `ORTSession.run(withInputs:outputs:runOptions:)` — the run into outputs
/// allocated beforehand — with its dictionaries passed through untouched.
///
/// The imported method takes Swift dictionaries, and a Swift `[String: ORTValue]`
/// is re-bridged to a new NSDictionary on every call (its String keys are not
/// bridged verbatim), which is an allocation per hop on the worker. Declaring
/// the same Objective-C selector with NSDictionary parameters, and sending it
/// to the session, hands over dictionaries built once. The run options are
/// passed too: given none, the Objective-C side allocates a fresh set per call.
@objc private protocol PinnedRun {
    @objc(runWithInputs:outputs:runOptions:error:)
    func run(inputs: NSDictionary, outputs: NSDictionary, runOptions: ORTRunOptions?) throws
}

/// One run of `session` from `inputs` into the pre-allocated `outputs`.
@inline(__always)
func runPinned(_ session: ORTSession, _ inputs: NSDictionary, _ outputs: NSDictionary, _ options: ORTRunOptions) throws {
    // An @objc protocol existential is the bare object pointer, so this is a
    // plain message send of the selector the header declares.
    let runner = unsafeBitCast(session, to: PinnedRun.self)
    // The workers are plain threads that call this for as long as the page is
    // open: anything the Objective-C side autoreleases (an NSError, at least)
    // must go at the end of the call, not pile up until the thread exits.
    try autoreleasepool {
        try runner.run(inputs: inputs, outputs: outputs, runOptions: options)
    }
}

/// Kotlin's `System.nanoTime()`: a monotonic clock in nanoseconds.
@inline(__always)
func nanoTime() -> Int64 { Int64(bitPattern: DispatchTime.now().uptimeNanoseconds) }

/// Java's `LockSupport.park`/`unpark` for one worker thread: a single permit,
/// which `unpark` grants (from any thread, never blocking) and `park` takes,
/// waiting up to a time limit for it if there is none. Like park, it can
/// return without the permit — the workers re-check their condition anyway.
///
/// A plain semaphore would count every `unpark`, and a worker that had been
/// busy through a hundred pushes would then wake a hundred times for nothing.
/// Here the semaphore is signalled only on the permit's false → true edge,
/// and the parked thread takes the permit back down, so it never holds more
/// than the one signal the permit stands for.
final class Parker: Sendable {
    private let permit = Atomic<Bool>(false)
    private let semaphore = DispatchSemaphore(value: 0)

    /// Grants the permit, waking the worker if it is parked.
    func unpark() {
        if !permit.exchange(true, ordering: .acquiringAndReleasing) { semaphore.signal() }
    }

    /// Takes the permit, waiting up to `nanos` for one. Only the worker calls this.
    func park(nanos: Int64) {
        if permit.exchange(false, ordering: .acquiringAndReleasing) {
            // The unpark that set it has signalled, or is about to: consume that.
            semaphore.wait()
            return
        }
        if semaphore.wait(timeout: .now() + .nanoseconds(Int(nanos))) == .success {
            // Signalled on a false → true edge that nobody has taken down yet.
            _ = permit.exchange(false, ordering: .acquiringAndReleasing)
        }
    }
}

/// A preallocated, zeroed float buffer for a worker's per-hop scratch.
func floats(_ count: Int) -> UnsafeMutableBufferPointer<Float> {
    let b = UnsafeMutableBufferPointer<Float>.allocate(capacity: count)
    b.initialize(repeating: 0)
    return b
}
