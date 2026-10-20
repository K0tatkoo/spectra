import Foundation
import SpectraCore
import Synchronization

/// Everything the held-still pages need, run from the analysis thread.
///
/// Three consumers, each only while something on screen shows it:
///
///  - **Stems** — resamples the capture to 44.1 kHz, feeds the `StemSeparator`,
///    splits synths out of its stems with a `SynthSplitter` when the build has
///    that model, and holds each lane still with its own `ScopeLock`.
///  - **Hold still** — one `ScopeLock` on the mix, for the Waveform page.
///  - **2A03 triangle** — the same `TriangleTracker` the Windows build uses.
///
/// The separator is kept alive for a while after its page goes away, because
/// loading the model takes a noticeable moment and flicking between pages should
/// not pay it every time.
///
/// `process`, `reset` and `release` belong to the analysis thread; `latest` and
/// `latestNes` may be read from any (the Kotlin's @Volatile).
public final class ScopeRunner: @unchecked Sendable {

    public struct Wants: Sendable, Equatable {
        public var stems: Bool
        public var hold: Bool
        public var nes: Bool

        public init(stems: Bool, hold: Bool, nes: Bool) {
            self.stems = stems
            self.hold = hold
            self.nes = nes
        }

        public var any: Bool { stems || hold || nes }
    }

    private let rate: Int
    private let modelURL: () throws -> URL
    private let hooks: () -> any StemWorkerHooks
    /// Loads the synth splitter's model, or nil when this build has none. Called on the splitter's thread.
    private let synthModel: (@Sendable () throws -> SplitModel)?
    private let synthHooks: () -> any StemWorkerHooks

    /// - Parameters:
    ///   - rate: the capture's sample rate.
    ///   - modelURL: the StemgenRT model's file (`StemSeparator.asset` in the
    ///     bundle). Asked the first time the Stems page shows; throwing — the
    ///     resource is missing — puts that message on the page instead.
    ///   - synthModel: opens the synth splitter's model, e.g.
    ///     `{ try OnnxMaskNet.open(env: OrtEnvironment.getEnvironment(), modelFile: url) }`,
    ///     or nil when the build has none.
    public init(
        rate: Int,
        modelURL: @escaping () throws -> URL,
        hooks: @escaping () -> any StemWorkerHooks = { .none },
        synthModel: (@Sendable () throws -> SplitModel)? = nil,
        synthHooks: @escaping () -> any StemWorkerHooks = { .none }
    ) {
        self.rate = rate
        self.modelURL = modelURL
        self.hooks = hooks
        self.synthModel = synthModel
        self.synthHooks = synthHooks
        resampler = Resampler(inRate: rate, outRate: StemSeparator.sampleRate)
        holdFilter = Biquad.butterworthQs(order: 4).map { Biquad.lowPass(sampleRate: rate, freq: ScopeRunner.holdCornerHz, q: $0) }
        nesFilter = PitchPreFilter(sampleRate: rate, cornerHz: PitchPreFilter.cornerFor(NesOptions().huntMaxHz))
        holdLock = ScopeLock(
            sampleRate: rate,
            spec: ScopeLaneSpec(name: "Mix", minHz: 30, maxHz: 1000, pitchWindow: ScopeRunner.pitchWindowFor(rate, 30), trigger: .pitch)
        )
        tracker = TriangleTracker(sampleRate: rate)
    }

    // ---- stems -------------------------------------------------------------

    private var separator: StemSeparator?
    private var separatorThreads = 0
    private var loadError: String?
    private var stemsIdleMs: Float = 0
    private let resampler: Resampler
    private var rsL = [Float](repeating: 0, count: 4096)
    private var rsR = [Float](repeating: 0, count: 4096)

    private var splitter: SynthSplitter?
    private var splitError: String?

    /// One lock per stem, by `Stem.ordinal`.
    private let stemLocks = Stem.allCases.map { ScopeLock(sampleRate: StemSeparator.sampleRate, spec: ScopeRunner.specFor($0)) }
    private var laneRaw = [Float](repeating: 0, count: ScopeRunner.laneHistory)
    private var lanePitched = [Float](repeating: 0, count: ScopeRunner.laneHistory)
    private var laneFree = [Float](repeating: 0, count: ScopeRunner.freeHistory)
    /// Whether each stem's lane was held still last frame, by `Stem.ordinal`.
    private var laneHeld = [Bool](repeating: false, count: Stem.allCases.count)
    /// The ring each stem's lane read last frame: the stem model's, or the splitter's.
    private var laneSource = [HistoryRing?](repeating: nil, count: Stem.allCases.count)

    // ---- the mix: hold still + 2A03 ---------------------------------------

    private let mixRing = HistoryRing(capacity: TriangleTracker.history)
    private let holdLpRing = HistoryRing(capacity: TriangleTracker.history)
    private let nesLpRing = HistoryRing(capacity: TriangleTracker.history)
    private let holdFilter: [Biquad]
    private var nesFilter: PitchPreFilter
    private let holdLock: ScopeLock
    private let tracker: TriangleTracker
    private var mono = [Float](repeating: 0, count: 8192)
    private var lowScratch = [Float](repeating: 0, count: 8192)
    private var holdRaw = [Float](repeating: 0, count: ScopeRunner.laneHistory)
    private var holdPitched = [Float](repeating: 0, count: ScopeRunner.laneHistory)
    private var nesRaw = [Float](repeating: 0, count: TriangleTracker.history)
    private var nesPitched = [Float](repeating: 0, count: TriangleTracker.history)
    private var nesRegion = NesOptions().region

    private var sinceScopeMs: Float = 0
    private var wasHold = false
    private var wasNes = false
    private var wasStems = false

    private let latestBox = Mutex<ScopeFrame?>(nil)
    private let latestNesBox = Mutex<NesReading?>(nil)
    public var latest: ScopeFrame? { latestBox.withLock { $0 } }
    public var latestNes: NesReading? { latestNesBox.withLock { $0 } }
    private func publish(_ f: ScopeFrame?) { latestBox.withLock { $0 = f } }
    private func publishNes(_ r: NesReading?) { latestNesBox.withLock { $0 = r } }

    /// One capture block. `right` is nil for a mono source; both at the
    /// engine's own rate.
    public func process(_ left: UnsafePointer<Float>, _ right: UnsafePointer<Float>?, frames: Int,
                        wants: Wants, settings s: Settings, blockMs: Float) {
        feedStems(left, right ?? left, frames, wants.stems, s, blockMs)

        if wants.hold || wants.nes {
            let n = min(frames, mono.count)
            mono.withUnsafeMutableBufferPointer { m in
                if let right {
                    for i in 0..<n { m[i] = 0.5 * (left[i] + right[i]) }
                } else {
                    m.baseAddress!.update(from: left, count: n)
                }
            }
            // All three rings are written together or not at all. They are read
            // at one shared absolute index, and a ring that skipped the blocks
            // its mode was off for would sit permanently behind the others —
            // switching from 2A03 to "hold still" then never drew anything.
            mixRing.write(mono, offset: 0, count: n)
            mono.withUnsafeBufferPointer { m in
                lowScratch.withUnsafeMutableBufferPointer { low in
                    for i in 0..<n {
                        var v = Double(m[i])
                        for f in holdFilter { v = f.process(v) }
                        low[i] = Float(v)
                    }
                }
            }
            holdLpRing.write(lowScratch, offset: 0, count: n)
            if s.nesRegion != nesRegion {
                nesRegion = s.nesRegion
                tracker.reset()
            }
            nesFilter.process(mono, &lowScratch, count: n)
            nesLpRing.write(lowScratch, offset: 0, count: n)
        }

        if wants.hold != wasHold { holdLock.reset(); wasHold = wants.hold }
        if wants.nes != wasNes { tracker.reset(); publishNes(nil); wasNes = wants.nes }
        if wants.stems != wasStems { for l in stemLocks { l.reset() }; wasStems = wants.stems }

        if !wants.any {
            publish(nil)
            publishNes(nil)
            sinceScopeMs = 0
            return
        }

        sinceScopeMs += blockMs
        if sinceScopeMs < ScopeRunner.scopeIntervalMs { return }
        let dt = sinceScopeMs
        sinceScopeMs = 0

        let lanes = wants.stems ? stemLanes(s, dt) : []
        let info = wants.stems ? stemsInfo() : nil
        var hold: ScopeTrace? = nil
        if wants.hold && mixRing.written > 0 {
            let end = min(mixRing.written, holdLpRing.written)
            if mixRing.read(end: end, into: &holdRaw, count: holdRaw.count) &&
                holdLpRing.read(end: end, into: &holdPitched, count: holdPitched.count) {
                hold = holdLock.analyze(holdRaw, holdPitched, end: end, windowMs: s.scopeWindowMs,
                                        cleanMs: s.scopeCleanMs, points: ScopeRunner.points, dtMs: dt)
            }
        }
        publish(ScopeFrame(stems: lanes, stemsInfo: info, hold: hold))

        if wants.nes {
            let end = min(mixRing.written, nesLpRing.written)
            if mixRing.read(end: end, into: &nesRaw, count: nesRaw.count) &&
                nesLpRing.read(end: end, into: &nesPitched, count: nesPitched.count) {
                var o = NesOptions()
                o.region = s.nesRegion
                publishNes(tracker.analyze(nesRaw, nesPitched, dtMs: dt, o))
            } else {
                publishNes(nil)
            }
        } else {
            publishNes(nil)
        }
    }

    /// The same, on arrays.
    public func process(_ left: [Float], _ right: [Float]?, frames: Int, wants: Wants, settings: Settings, blockMs: Float) {
        precondition(frames <= left.count && frames <= (right?.count ?? frames), "process past the end of the block")
        left.withUnsafeBufferPointer { l in
            if let right {
                right.withUnsafeBufferPointer { r in
                    process(l.baseAddress!, r.baseAddress!, frames: frames, wants: wants, settings: settings, blockMs: blockMs)
                }
            } else {
                process(l.baseAddress!, nil, frames: frames, wants: wants, settings: settings, blockMs: blockMs)
            }
        }
    }

    private func feedStems(_ left: UnsafePointer<Float>, _ right: UnsafePointer<Float>, _ frames: Int,
                           _ wanted: Bool, _ s: Settings, _ blockMs: Float) {
        if !wanted {
            guard let sep = separator else { return }
            stemsIdleMs += blockMs
            if stemsIdleMs >= ScopeRunner.stemsLingerMs {
                stopSplitter()
                sep.stop()
                separator = nil
            }
            return
        }
        stemsIdleMs = 0

        var sep = separator
        // A different thread count is a different session. A failure, on the
        // other hand, stays on screen: retrying a model that just failed to
        // load would only fail again, every block.
        if let running = sep, separatorThreads != s.stemThreads {
            stopSplitter()
            running.stop()
            separator = nil
            sep = nil
        }
        if sep == nil && loadError == nil {
            do {
                let started = StemSeparator(modelFile: try modelURL(), threads: s.stemThreads, hooks: hooks())
                started.start()
                sep = started
            } catch {
                loadError = "The stem model is missing from this build: \(messageOf(error))"
            }
            separator = sep
            separatorThreads = s.stemThreads
            resampler.reset()
        }
        guard let sep, sep.isRunning else { return }
        updateSplitter(sep, s)

        let need = resampler.maxOutput(frames)
        if rsL.count < need {
            rsL = [Float](repeating: 0, count: need)
            rsR = [Float](repeating: 0, count: need)
        }
        let out = rsL.withUnsafeMutableBufferPointer { l in
            rsR.withUnsafeMutableBufferPointer { r in
                resampler.process(left, right, frames: frames, outL: l.baseAddress!, outR: r.baseAddress!)
            }
        }
        sep.push(left: rsL, right: rsR, frames: out)
    }

    /// Starts the synth splitter behind a running separator when the build has
    /// its model and Settings want it, and stops it when they no longer do. A
    /// model that failed to load stays failed, like the stem model's.
    private func updateSplitter(_ sep: StemSeparator, _ s: Settings) {
        guard let open = synthModel, s.stemSynth else {
            stopSplitter()
            return
        }
        if splitter != nil || splitError != nil { return }
        let split = SynthSplitter(open: open, source: { stem in sep.stems[stem.ordinal] }, hooks: synthHooks())
        splitter = split
        sep.onHop = { written in split.wake(written) }
        split.start()
    }

    private func stopSplitter() {
        guard let split = splitter else { return }
        separator?.onHop = nil
        split.stop()
        splitter = nil
    }

    private func stemLanes(_ s: Settings, _ dt: Float) -> [StemLane] {
        guard let sep = separator else { return [] }
        guard case .running = sep.status else { return [] }
        let split = splitter
        let splitStatus = split?.status
        // Five lanes from the moment a splitter exists, so the page does not
        // re-flow when its model finishes loading; four again if it fails.
        var splitting = split != nil
        if case .failed? = splitStatus { splitting = false }
        var outputs: SynthSplitter.Outputs? = nil
        if case .running? = splitStatus { outputs = split?.outputs }
        let splitClean = split?.cleanFrom ?? .max
        return ScopeRunner.lanesFor(synth: splitting).map { stem in
            // Where this lane reads from: the splitter's output once it has
            // one for this stem, else the stem model's own ring. The synth lane
            // has nothing to show until the splitter is running.
            let raw: HistoryRing?
            let low: HistoryRing?
            let clean: Int64
            if let outputs, let fromSplit = outputs.raw[stem] {
                raw = fromSplit
                low = outputs.pitched[stem]
                // A seam in the stems reaches the splitter's output up to one
                // of its frames later.
                clean = max(splitClean, sep.cleanFrom + Int64(outputs.layout.nFft))
            } else if stem.fromModel {
                raw = sep.stems[stem.ordinal]
                low = sep.pitched[stem.ordinal]
                clean = sep.cleanFrom
            } else {
                raw = nil
                low = nil
                clean = .max
            }
            let lock = stemLocks[stem.ordinal]
            let held = s.holdsStill(stem)
            // A lock switched back on must not pick up a note, a gain or a
            // hit from before it was switched off — nor one from the other
            // ring, when a lane moves between the model and the splitter.
            if held != laneHeld[stem.ordinal] || raw !== laneSource[stem.ordinal] {
                lock.reset()
                laneHeld[stem.ordinal] = held
                laneSource[stem.ordinal] = raw
            }
            // Both workers write every lane's pitched ring whatever is held, so
            // the two rings of a lane can never drift apart while it is free.
            var trace: ScopeTrace? = nil
            if let raw, let low {
                if held {
                    let end = min(raw.written, low.written)
                    if end > 0 && raw.read(end: end, into: &laneRaw, count: ScopeRunner.laneHistory) &&
                        low.read(end: end, into: &lanePitched, count: ScopeRunner.laneHistory) {
                        trace = lock.analyze(laneRaw, lanePitched, end: end, windowMs: s.scopeWindowMs,
                                             cleanMs: s.scopeCleanMs, points: ScopeRunner.points, dtMs: dt, validFrom: clean)
                    }
                } else {
                    let end = raw.written
                    if end > 0 && raw.read(end: end, into: &laneFree, count: ScopeRunner.freeHistory) {
                        trace = lock.freeRunning(laneFree, windowMs: s.scopeWindowMs, points: ScopeRunner.points, dtMs: dt)
                    }
                }
            }
            return StemLane(name: stem.label,
                            trace: trace ?? ScopeTrace.quiet(points: ScopeRunner.points, windowMs: s.scopeWindowMs, levelDb: -120),
                            slot: stem.ordinal)
        }
    }

    private func stemsInfo() -> StemsInfo {
        if let loadError { return StemsInfo(state: .failed, message: loadError, load: 0, skips: 0, msPerHop: 0) }
        var synth: SynthInfo? = nil
        if let split = splitter {
            switch split.status {
            case let .running(load, skips): synth = SynthInfo(state: .running, message: nil, load: load, skips: skips)
            case let .failed(message): synth = SynthInfo(state: .failed, message: message, load: 0, skips: 0)
            default: synth = SynthInfo(state: .loading, message: nil, load: 0, skips: 0)
            }
        }
        switch separator?.status {
        case let .running(load, skips, msPerHop)?:
            return StemsInfo(state: .running, message: nil, load: load, skips: skips, msPerHop: msPerHop, synth: synth)
        case let .failed(message)?:
            return StemsInfo(state: .failed, message: message, load: 0, skips: 0, msPerHop: 0)
        default:
            return StemsInfo(state: .loading, message: nil, load: 0, skips: 0, msPerHop: 0)
        }
    }

    public func reset() {
        for l in stemLocks { l.reset() }
        holdLock.reset()
        tracker.reset()
        publishNes(nil)
    }

    /// Stops the workers and lets their models go. The runner can be used
    /// again afterwards; the next Stems frame starts the model afresh.
    public func release() {
        stopSplitter()
        separator?.stop()
        separator = nil
        publish(nil)
        publishNes(nil)
    }

    /// Display order, top to bottom: the stem that is usually highest first,
    /// with synth between vocals and other when it is split out.
    public static func lanesFor(synth: Bool) -> [Stem] {
        synth ? [.vocals, .synth, .other, .bass, .drums] : [.vocals, .other, .bass, .drums]
    }

    /// Samples of each lane handed to its lock: 370 ms at 44.1 kHz.
    public static let laneHistory = 16_384
    /// A free lane only reads one window: 185 ms, past the longest one Settings offers.
    private static let freeHistory = 8_192
    public static let points = 512
    /// Scope refresh. Blocks arrive about every 21 ms; this is one per block.
    private static let scopeIntervalMs: Float = 15
    private static let stemsLingerMs: Float = 20_000
    private static let holdCornerHz = 1200.0

    /// A pitch window that holds two periods of `minHz`, rounded up to a power of two.
    public static func pitchWindowFor(_ rate: Int, _ minHz: Float) -> Int {
        let need = ktToInt(2.2 * Float(rate) / minHz)
        var n = 1024
        while n < need { n *= 2 }
        return n
    }

    public static func specFor(_ stem: Stem) -> ScopeLaneSpec {
        let sr = StemSeparator.sampleRate
        switch stem {
        case .vocals:
            return ScopeLaneSpec(name: stem.label, minHz: 80, maxHz: 1000, pitchWindow: pitchWindowFor(sr, 80), trigger: .pitch)
        // Leads and pads: from a low pad note up past the top of a lead's range.
        case .synth, .other:
            return ScopeLaneSpec(name: stem.label, minHz: 50, maxHz: 1000, pitchWindow: pitchWindowFor(sr, 50), trigger: .pitch)
        case .bass:
            return ScopeLaneSpec(name: stem.label, minHz: 28, maxHz: 300, pitchWindow: pitchWindowFor(sr, 28), trigger: .pitch)
        case .drums:
            return ScopeLaneSpec(name: stem.label, minHz: 40, maxHz: 400, pitchWindow: 1024, trigger: .transient)
        }
    }
}

public extension Settings {
    /// Whether `stem`'s lane on the Stems page is held still, or scrolls. Lives
    /// here rather than in Settings because Settings is also compiled into the
    /// Windows build, which has no stems (and so, here, into SpectraCore).
    func holdsStill(_ stem: Stem) -> Bool {
        switch stem {
        case .vocals: holdVocals
        case .synth: holdSynth
        case .other: holdOther
        case .bass: holdBass
        case .drums: holdDrums
        }
    }

    /// These settings with `stem`'s lane held still, or not.
    func holdingStill(_ stem: Stem, _ hold: Bool) -> Settings {
        var s = self
        switch stem {
        case .vocals: s.holdVocals = hold
        case .synth: s.holdSynth = hold
        case .other: s.holdOther = hold
        case .bass: s.holdBass = hold
        case .drums: s.holdDrums = hold
        }
        return s
    }
}
