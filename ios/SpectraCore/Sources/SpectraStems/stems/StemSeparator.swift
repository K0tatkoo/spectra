import Foundation
import OnnxRuntimeBindings
import SpectraCore
import Synchronization

/// What a Stems lane can show. The first four are the stem model's outputs, in
/// its own order — `StemSeparator.stems` is indexed by their ordinal. `synth` is
/// not the model's: `SynthSplitter` takes it out of the others.
///
/// Keep the case order: the lanes are coloured by it (drums, bass, vocals,
/// other, synth), and the raw values are the Kotlin names.
public enum Stem: String, CaseIterable, Sendable {
    case drums = "DRUMS"
    case bass = "BASS"
    case vocals = "VOCALS"
    case other = "OTHER"
    case synth = "SYNTH"

    public var label: String {
        switch self {
        case .drums: "Drums"
        case .bass: "Bass"
        case .vocals: "Vocals"
        case .other: "Other"
        case .synth: "Synth"
        }
    }

    /// Kotlin's `ordinal`: the position in the declaration above.
    public var ordinal: Int {
        switch self {
        case .drums: 0
        case .bass: 1
        case .vocals: 2
        case .other: 3
        case .synth: 4
        }
    }

    /// True for the stems the stem model itself produces.
    public var fromModel: Bool { ordinal < StemSeparator.modelStems }
}

/// Live source separation: a stereo mix in, four stems out, 128 samples at a time.
///
/// The model is the streaming HS-TasNet release shipped with StemgenRT (MIT,
/// github.com/sweetspotsoundsystem/stemgen-rt): a recurrent network that looks at
/// a 1024-sample window, emits 128 new samples per call and carries eight state
/// tensors from one call to the next. Its output lags its input by one call —
/// 2.9 ms — which is why a live display is possible at all. The better-known
/// separators (Demucs and friends) need seconds of audio on both sides of the
/// moment they separate, and could only ever show the past.
///
/// It is not a clean separation. The release scores about 4.5 dB SDR: every stem
/// carries some of the others. That is fine for what this feeds, because the
/// held-still scopes fold whole periods of the note on top of each other, and
/// bleed that is not locked to the note averages away.
///
/// Threading: `push` is called by the analysis thread and never blocks. A single
/// worker thread owns the `StemSession` and does nothing else. If the phone cannot
/// keep up, the worker does not queue audio forever — it skips to the present,
/// keeping the model's memory, and counts the skip, so the picture is late by at
/// most `maxBacklogFrames` rather than by more every second.
///
/// `start`, `stop` and `push` belong to one thread (the analysis thread); the
/// status, `cleanFrom` and `onHop` may be read or set from any. The worker
/// holds the separator until it exits, so call `stop()` before letting go.
///
/// No app types, deliberately: the unit tests run this exact class on the Mac
/// against the same model file.
public final class StemSeparator: @unchecked Sendable {

    public enum Status: Sendable, Equatable {
        case idle
        case loading
        /// `load`: compute time ÷ audio time, smoothed. Above 1 the phone is
        /// slower than the music. `skips`: times the worker fell behind and
        /// jumped to the present.
        case running(load: Float, skips: Int, msPerHop: Float)
        case failed(message: String)
    }

    private let modelFile: URL
    private let threads: Int
    /// Priority and performance hints — whatever the platform can offer the worker.
    private let hooks: any StemWorkerHooks

    /// - Parameters:
    ///   - modelFile: the StemgenRT model, `stemgen-rt-hop128.onnx` (`asset`).
    ///   - threads: ONNX Runtime's intra-op threads, 1 to 4.
    public init(modelFile: URL, threads: Int = 1, hooks: any StemWorkerHooks = .none) {
        self.modelFile = modelFile
        self.threads = threads
        self.hooks = hooks
    }

    private let statusBox = Mutex<Status>(.idle)
    public var status: Status { statusBox.withLock { $0 } }
    private func setStatus(_ s: Status) { statusBox.withLock { $0 = s } }

    /// The mix, at `sampleRate`, from the analysis thread.
    public let input = StereoFeed(capacityFrames: StemSeparator.feedFrames)

    /// Each of the model's stems as mono, at `sampleRate`, indexed like `input` and by `Stem.ordinal`.
    public let stems: [HistoryRing] = (0..<StemSeparator.modelStems).map { _ in HistoryRing(capacity: StemSeparator.history) }

    /// The same stems low-passed for pitch detection, sample-aligned with `stems`.
    public let pitched: [HistoryRing] = (0..<StemSeparator.modelStems).map { _ in HistoryRing(capacity: StemSeparator.history) }

    private let onHopBox = Mutex<(@Sendable (Int64) -> Void)?>(nil)
    /// Called on the worker thread after every hop, once all the stems are
    /// written, with their new length — the synth splitter's cue.
    public var onHop: (@Sendable (Int64) -> Void)? {
        get { onHopBox.withLock { $0 } }
        set { onHopBox.withLock { $0 = newValue } }
    }

    private let _cleanFrom = Atomic<Int64>(Int64(StemSeparator.warmupFrames))
    /// Absolute stem index from which the output can be trusted: past the
    /// model's warm-up when it starts, then past the seam of its latest skip.
    /// Before it is either the model learning the song from silence or audio
    /// from both sides of a jump, and a scope that measures a period across that
    /// seam gets it wrong.
    public var cleanFrom: Int64 { _cleanFrom.load(ordering: .acquiring) }

    private let running = Atomic<Bool>(false)
    private let parker = Parker()
    /// Signalled by the worker as it exits: Kotlin's `thread.join`.
    private var finished: DispatchSemaphore?

    public func start() {
        if running.load(ordering: .acquiring) { return }
        running.store(true, ordering: .releasing)
        setStatus(.loading)
        let done = DispatchSemaphore(value: 0)
        finished = done
        let thread = Thread { [self] in
            runWorker()
            done.signal()
        }
        thread.name = "spectra-stems"
        // No core pinning on iOS: the scheduler's highest class is what there is.
        thread.qualityOfService = .userInteractive
        thread.start()
    }

    public func stop() {
        running.store(false, ordering: .releasing)
        if let done = finished {
            parker.unpark()
            _ = done.wait(timeout: .now() + 2)
        }
        finished = nil
        if case .failed = status {} else { setStatus(.idle) }
    }

    public var isRunning: Bool { running.load(ordering: .acquiring) }

    /// Queues audio for separation. Never blocks. Both channels at `sampleRate`.
    public func push(left: UnsafePointer<Float>, right: UnsafePointer<Float>, frames: Int) {
        if !running.load(ordering: .relaxed) || frames <= 0 { return }
        input.write(left: left, right: right, count: frames)
        parker.unpark()
    }

    public func push(left: [Float], right: [Float], frames: Int) {
        precondition(frames <= left.count && frames <= right.count, "push past the end of the source")
        left.withUnsafeBufferPointer { l in
            right.withUnsafeBufferPointer { r in push(left: l.baseAddress!, right: r.baseAddress!, frames: frames) }
        }
    }

    // ------------------------------------------------------------------------

    private func runWorker() {
        hooks.onStart()
        defer { hooks.onStop() }
        runSession()
    }

    private func runSession() {
        let env: ORTEnv
        do {
            env = try OrtEnvironment.getEnvironment()
        } catch {
            fail("The separation runtime could not start on this device (\(typeNameOf(error))).")
            return
        }
        let session: StemSession
        do {
            session = try StemSession.open(env: env, modelFile: modelFile, threads: threads)
        } catch {
            fail("The stem model could not be loaded: \(messageOf(error))")
            return
        }
        do {
            try loop(session)
        } catch {
            fail("Stem separation stopped: \(messageOf(error))")
        }
        running.store(false, ordering: .releasing)
    }

    private func loop(_ session: StemSession) throws {
        let hop = StemSeparator.hop
        let hop64 = Int64(hop)
        let hopPlanar = floats(hop * 2)
        let separated = floats(StemSession.outSize)
        let mono = floats(hop)
        let low = floats(hop)
        defer {
            hopPlanar.deallocate()
            separated.deallocate()
            mono.deallocate()
            low.deallocate()
        }
        let filters = (0..<StemSeparator.modelStems).map { StemSeparator.pitchFilter(Stem.allCases[$0]) }

        // The first call carries nothing but the zero history.
        var skipNext = true
        var readPos = input.written
        var skips = 0
        // Ready from here on: audio pushed after this point is separated. The
        // load figures fill in once there is audio to measure them on.
        setStatus(.running(load: 0, skips: 0, msPerHop: 0))

        var busyNs = 0.0
        var hops: Int64 = 0
        var load: Float = 0
        var msPerHop: Float = 0
        var lastReport = nanoTime()

        while running.load(ordering: .relaxed) {
            var backlog = input.written - readPos
            if backlog > Int64(StemSeparator.maxBacklogFrames) {
                // Behind by more than we are willing to show late: jump to the
                // present, and keep the model's memory across the jump. What is
                // on the far side is nearly always the song it was just hearing.
                // Measured against a run that never skipped, it is back within
                // 10–20 ms; starting over from silence, which this used to do,
                // left loud garbage in drums and other for a third of a second,
                // and a skip every 2.5 s cost 10–50× the error over a whole run.
                readPos = input.written - hop64
                skips += 1
                _cleanFrom.store(stems[0].written + Int64(StemSeparator.spliceGuardFrames), ordering: .releasing)
                setStatus(.running(load: load, skips: skips, msPerHop: msPerHop))
                backlog = input.written - readPos
            }
            if backlog < hop64 {
                parker.park(nanos: StemSeparator.parkNs)
                continue
            }
            if !input.readPlanar(from: readPos, count: hop, into: hopPlanar.baseAddress!) {
                readPos = input.written - hop64
                continue
            }
            readPos += hop64

            let t0 = nanoTime()
            try session.separate(hopPlanar.baseAddress!, separated.baseAddress!)
            let work = nanoTime() - t0
            busyNs += Double(work)
            hops += 1
            hooks.onWork(workNanos: work, audioNanos: StemSeparator.hopNanos)

            if skipNext {
                skipNext = false
            } else {
                let out = separated.baseAddress!, m = mono.baseAddress!, l = low.baseAddress!
                for s in 0..<StemSeparator.modelStems {
                    let base = s * 2 * hop
                    for i in 0..<hop { m[i] = 0.5 * (out[base + i] + out[base + hop + i]) }
                    stems[s].write(m, offset: 0, count: hop)
                    let chain = filters[s]
                    for i in 0..<hop {
                        var v = Double(m[i])
                        for f in chain { v = f.process(v) }
                        l[i] = Float(v)
                    }
                    pitched[s].write(l, offset: 0, count: hop)
                }
                onHop?(stems[0].written)
            }

            let now = nanoTime()
            if now - lastReport >= StemSeparator.reportNs && hops > 0 {
                let audioNs = Double(hops * hop64) * 1e9 / Double(StemSeparator.sampleRate)
                let instant = Float(busyNs / audioNs)
                load = load == 0 ? instant : load * 0.6 + instant * 0.4
                msPerHop = Float(busyNs / Double(hops) / 1e6)
                setStatus(.running(load: load, skips: skips, msPerHop: msPerHop))
                busyNs = 0
                hops = 0
                lastReport = now
            }
        }
    }

    private func fail(_ message: String) {
        setStatus(.failed(message: message))
        running.store(false, ordering: .releasing)
    }

    /// The model is trained at, and only valid at, exactly this rate.
    public static let sampleRate = 44_100
    public static let hop = 128
    /// The stems the model outputs: the first four `Stem`s.
    public static let modelStems = 4

    /// File name of the model in the app bundle (ios/tools/stem-model.sh puts
    /// it there; the Android build's is assets/stems/ with the same name).
    public static let asset = "stemgen-rt-hop128.onnx"
    /// SHA-256 of that file. The build refuses any other bytes.
    public static let sha256 = "08424ca91feae8d4746442a35ebf70489dea70ea6e81401b39483cf02d497748"
    public static let bytes: Int64 = 37_532_574

    /// Stem history: 1.5 s, several times what the deepest scope reads.
    public static let history = 65_536
    private static let feedFrames = 131_072
    /// How late the picture may fall before the worker skips to the present.
    /// Short on purpose: the model keeps its memory across a skip and is back
    /// on track within 10–20 ms, so a phone that is slightly too slow does
    /// better skipping now and then than showing everything late.
    public static let maxBacklogFrames = 6_615 // 150 ms
    /// Output after the model starts from silence not yet worth measuring: 100 ms.
    public static let warmupFrames = 4_410
    /// Output after a skip not yet worth measuring: one analysis window of the
    /// model, 23 ms. Until then what it is looking at still straddles the jump.
    public static let spliceGuardFrames = 1_024
    private static let parkNs: Int64 = 1_500_000
    private static let hopNanos = Int64(hop) * 1_000_000_000 / Int64(sampleRate)
    private static let reportNs: Int64 = 500_000_000

    /// The low-pass in front of each stem's pitch detector: just above the
    /// top of the range that lane hunts in, so a note's own harmonics cannot
    /// out-vote its fundamental.
    public static func pitchCornerHz(_ stem: Stem) -> Double {
        switch stem {
        case .bass: 360.0
        case .drums: 2000.0
        case .vocals, .other, .synth: 1200.0
        }
    }

    static func pitchFilter(_ stem: Stem) -> [Biquad] {
        Biquad.butterworthQs(order: 4).map { Biquad.lowPass(sampleRate: sampleRate, freq: pitchCornerHz(stem), q: $0) }
    }
}
