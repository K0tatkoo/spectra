import Foundation
import SpectraCore
import Synchronization

/// Splits synths out of the stems the model already made, live.
///
/// The stem model has no synth stem: trained on four, it puts a synth wherever
/// it sounds most at home. Measured on synthetic ones, a supersaw lead lands 99 %
/// in vocals and a pad 86 % in other, so the splitter reads both of those (the
/// model file says which, `SplitLayout.inputs`) and hands back three lanes:
/// synth, and each input with its synth taken out.
///
/// It trails the stem worker on its own thread, a frame of `SplitLayout.hop`
/// samples at a time, and keeps its output rings on the stems' absolute index,
/// so sample i of the synth ring is the synth of sample i of the mix. If it
/// falls behind it jumps to the present, keeping the network's memory, and
/// writes silence over what it skipped so the index stays true.
///
/// Its own thread rather than the stem worker's: the stem model already fills
/// the phone's fastest core, and the splitter must never be the reason the
/// other stems start skipping.
///
/// `start` and `stop` belong to one thread; `wake` is the stem worker's; the
/// status, `outputs` and `cleanFrom` may be read from any. The worker holds the
/// splitter until it exits, so call `stop()` before letting go.
///
/// No app types: the tests run it on the Mac with a stand-in network.
public final class SynthSplitter: @unchecked Sendable {

    public enum Status: Sendable, Equatable {
        case idle
        case loading
        /// `load`: compute time ÷ audio time, smoothed. `skips`: jumps to the present.
        case running(load: Float, skips: Int)
        case failed(message: String)
    }

    /// What the splitter writes, once its model is loaded: the synth and each
    /// input with the synth taken out, as mono rings on the stems' index, and the
    /// same low-passed for the pitch detectors — written together, like the
    /// stem worker's.
    public final class Outputs: Sendable {
        public let layout: SplitLayout
        public let raw: [Stem: HistoryRing]
        public let pitched: [Stem: HistoryRing]

        public init(layout: SplitLayout, raw: [Stem: HistoryRing], pitched: [Stem: HistoryRing]) {
            self.layout = layout
            self.raw = raw
            self.pitched = pitched
        }

        /// The lanes this splitter feeds: synth, then its inputs.
        public var stems: [Stem] { [.synth] + layout.inputs }
    }

    /// Loads the model. Called once, on the splitter's own thread; throws if it cannot.
    private let open: @Sendable () throws -> SplitModel
    /// The stem model's mono output ring for each stem.
    private let source: @Sendable (Stem) -> HistoryRing
    private let hooks: any StemWorkerHooks

    public init(open: @escaping @Sendable () throws -> SplitModel, source: @escaping @Sendable (Stem) -> HistoryRing,
                hooks: any StemWorkerHooks = .none) {
        self.open = open
        self.source = source
        self.hooks = hooks
    }

    private let statusBox = Mutex<Status>(.idle)
    public var status: Status { statusBox.withLock { $0 } }
    private func setStatus(_ s: Status) { statusBox.withLock { $0 = s } }

    private let outputsBox = Mutex<Outputs?>(nil)
    public var outputs: Outputs? { outputsBox.withLock { $0 } }

    private let _cleanFrom = Atomic<Int64>(.max)
    /// Absolute index from which the output can be trusted: past the network's
    /// warm-up after it starts, then past the seam of its latest jump. The
    /// stems' own seams come on top; `ScopeRunner` adds those.
    public var cleanFrom: Int64 { _cleanFrom.load(ordering: .acquiring) }

    private let running = Atomic<Bool>(false)
    private let waitingFor = Atomic<Int64>(.max)
    private let parker = Parker()
    private var finished: DispatchSemaphore?

    public var isRunning: Bool { running.load(ordering: .acquiring) }

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
        thread.name = "spectra-synth"
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

    /// Called by the stem worker after each hop it writes, with its rings'
    /// new length. Wakes the splitter only once a whole frame is there.
    public func wake(_ written: Int64) {
        if written >= waitingFor.load(ordering: .acquiring) { parker.unpark() }
    }

    // ------------------------------------------------------------------------

    private func runWorker() {
        hooks.onStart()
        defer { hooks.onStop() }
        let model: SplitModel
        do {
            model = try open()
        } catch {
            fail("The synth model could not be loaded: \(messageOf(error))")
            return
        }
        do {
            try loop(model)
        } catch {
            fail("Synth splitting stopped: \(messageOf(error))")
        }
        running.store(false, ordering: .releasing)
    }

    private func loop(_ model: SplitModel) throws {
        let layout = model.layout
        try require(layout.sampleRate == StemSeparator.sampleRate,
                    "the synth model is for \(layout.sampleRate) Hz audio, the stems are \(StemSeparator.sampleRate) Hz")
        let split = SpectralSplit(layout: layout, net: model.net)
        let hop = layout.hop
        let hop64 = Int64(hop)
        let inputs = layout.inputs
        let rings = inputs.map(source)
        let frames = inputs.map { _ in floats(layout.nFft) }
        let synth = floats(hop)
        let rest = inputs.map { _ in floats(hop) }
        let low = floats(hop)
        defer {
            for b in frames { b.deallocate() }
            for b in rest { b.deallocate() }
            synth.deallocate()
            low.deallocate()
        }

        // Lane i is lanes[i]: synth, then the inputs.
        let lanes = [Stem.synth] + inputs
        let raw = lanes.map { _ in HistoryRing(capacity: StemSeparator.history) }
        let pitched = lanes.map { _ in HistoryRing(capacity: StemSeparator.history) }
        let filters = lanes.map { StemSeparator.pitchFilter($0) }
        let l = low.baseAddress!
        func emit(_ lane: Int, _ samples: UnsafeMutableBufferPointer<Float>) {
            let x = samples.baseAddress!
            raw[lane].write(x, offset: 0, count: hop)
            let chain = filters[lane]
            for i in 0..<hop {
                var v = Double(x[i])
                for f in chain { v = f.process(v) }
                l[i] = Float(v)
            }
            pitched[lane].write(l, offset: 0, count: hop)
        }
        func available() -> Int64 {
            var m = Int64.max
            for r in rings { m = min(m, r.written) }
            return m
        }

        // The frame that ends at `next` completes the hop two hops before it,
        // so the rings always stand 2 × hop behind `next` when a frame starts.
        var next: Int64 = 0
        func jumpTo(_ available: Int64, _ guardFrames: Int64) {
            next = max(2 * hop64, available / hop64 * hop64)
            split.resetOverlap()
            for r in raw { r.advanceTo(next - 2 * hop64) }
            for r in pitched { r.advanceTo(next - 2 * hop64) }
            _cleanFrom.store(next + guardFrames, ordering: .releasing)
        }

        jumpTo(available(), Int64(SynthSplitter.warmupFrames))
        outputsBox.withLock {
            $0 = Outputs(layout: layout, raw: Dictionary(uniqueKeysWithValues: zip(lanes, raw)),
                         pitched: Dictionary(uniqueKeysWithValues: zip(lanes, pitched)))
        }
        var skips = 0
        setStatus(.running(load: 0, skips: 0))

        let frameNanos = hop64 * 1_000_000_000 / Int64(StemSeparator.sampleRate)
        var busyNs = 0.0
        var done = 0
        var load: Float = 0
        var lastReport = nanoTime()

        while running.load(ordering: .relaxed) {
            let avail = available()
            if avail - next > Int64(SynthSplitter.maxBacklogFrames) {
                // Behind by more than is worth showing late: jump, keep the
                // network's memory — what follows is the song it was hearing.
                jumpTo(avail, hop64)
                skips += 1
                setStatus(.running(load: load, skips: skips))
                continue
            }
            if avail < next {
                waitingFor.store(next, ordering: .releasing)
                // Re-check after publishing what we wait for: the stem worker
                // may have written the frame in between, and then no wake comes.
                if available() < next { parker.park(nanos: SynthSplitter.parkNs) }
                waitingFor.store(.max, ordering: .releasing)
                continue
            }
            var ok = true
            for i in inputs.indices { ok = ok && rings[i].read(end: next, into: frames[i].baseAddress!, count: layout.nFft) }
            if !ok {
                jumpTo(available(), hop64)
                skips += 1
                continue
            }

            let t0 = nanoTime()
            try split.frame(frames, synth: synth, rest: rest)
            let work = nanoTime() - t0
            emit(0, synth)
            for i in inputs.indices { emit(i + 1, rest[i]) }
            next += hop64
            busyNs += Double(work)
            done += 1
            hooks.onWork(workNanos: work, audioNanos: frameNanos)

            let now = nanoTime()
            if now - lastReport >= SynthSplitter.reportNs && done > 0 {
                let instant = Float(busyNs / (Double(done) * Double(frameNanos)))
                load = load == 0 ? instant : load * 0.6 + instant * 0.4
                setStatus(.running(load: load, skips: skips))
                busyNs = 0
                done = 0
                lastReport = now
            }
        }
    }

    private func fail(_ message: String) {
        setStatus(.failed(message: message))
        running.store(false, ordering: .releasing)
    }

    /// How late the splitter may fall before it jumps to the present: 150 ms, like the stem worker.
    public static let maxBacklogFrames = StemSeparator.maxBacklogFrames
    /// Output after the network starts from silence not yet worth measuring: 250 ms.
    public static let warmupFrames = 11_025
    /// A fallback only: the stem worker wakes the splitter when a frame is ready.
    private static let parkNs: Int64 = 20_000_000
    private static let reportNs: Int64 = 500_000_000
}
