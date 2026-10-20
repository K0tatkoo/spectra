/// Where the stem model is, in the words the painter needs.
public enum StemsState: Sendable { case loading, running, failed }

/// The separator's health as of one frame.
///
/// `load` is compute time over audio time: at 0.6 the phone separates a second
/// of music in 0.6 s and has room to spare; above 1 it cannot keep up and
/// `skips` counts the times it gave up and jumped to the present.
public struct StemsInfo: Sendable {
    public let state: StemsState
    public let message: String?
    public let load: Float
    public let skips: Int
    public let msPerHop: Float
    /// The synth splitter behind the stem model, when there is one.
    public let synth: SynthInfo?

    public init(state: StemsState, message: String?, load: Float, skips: Int, msPerHop: Float, synth: SynthInfo? = nil) {
        self.state = state
        self.message = message
        self.load = load
        self.skips = skips
        self.msPerHop = msPerHop
        self.synth = synth
    }
}

/// The synth splitter's health: `load` like the stem model's.
public struct SynthInfo: Sendable {
    public let state: StemsState
    public let message: String?
    public let load: Float
    public let skips: Int

    public init(state: StemsState, message: String?, load: Float, skips: Int) {
        self.state = state
        self.message = message
        self.load = load
        self.skips = skips
    }
}

/// One stem lane as published: its label, its held-still trace, and which
/// stem it is (`slot`), so a lane keeps its colour whatever else is shown.
public struct StemLane: Sendable {
    public let name: String
    public let trace: ScopeTrace
    public let slot: Int

    public init(name: String, trace: ScopeTrace, slot: Int) {
        self.name = name
        self.trace = trace
        self.slot = slot
    }
}

/// The held-still scopes for one published frame. Only the pages that are
/// actually on screen are computed, so everything here may be absent.
public struct ScopeFrame: Sendable {
    /// Top to bottom as drawn. Empty unless the Stems page is showing.
    public let stems: [StemLane]
    public let stemsInfo: StemsInfo?
    /// The Waveform page's "hold still" trace, on the mix.
    public let hold: ScopeTrace?

    public init(stems: [StemLane], stemsInfo: StemsInfo?, hold: ScopeTrace?) {
        self.stems = stems
        self.stemsInfo = stemsInfo
        self.hold = hold
    }
}
