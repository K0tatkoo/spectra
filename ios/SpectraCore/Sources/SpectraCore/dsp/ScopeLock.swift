import Foundation

// ScopeLock.kt, part 1: the value types a held-still lane is described and
// published in. The pages and AnalysisFrame need them before the ScopeLock
// class itself (part 2, below) exists.

/// How a lane decides where its picture starts.
public enum LaneTrigger: Sendable {
    /// Find the note's period and start every frame at the same point of it.
    case pitch
    /// No note to find (drums): freeze on each hit until the next one.
    case transient
}

/// What one lane of a held-still scope listens for.
public struct ScopeLaneSpec: Sendable {
    public let name: String
    public let minHz: Float
    public let maxHz: Float
    /// Samples the pitch detector looks at. Must hold two periods of `minHz`.
    public let pitchWindow: Int
    public let trigger: LaneTrigger

    public init(name: String, minHz: Float, maxHz: Float, pitchWindow: Int, trigger: LaneTrigger) {
        self.name = name
        self.minHz = minHz
        self.maxHz = maxHz
        self.pitchWindow = pitchWindow
        self.trigger = trigger
    }
}

/// What a trace on screen is doing, for the painter's labels.
public enum TraceMode: Sendable {
    /// Locked to a detected note — the picture stands still while the note is held.
    case pitch
    /// Frozen on the most recent hit.
    case hit
    /// Nothing to lock to; showing the newest audio as it arrives.
    case free
    /// Below the level where a picture would be amplified noise.
    case quiet
}

/// One frame of one held-still lane, ready to draw.
///
/// `points` span the whole time window left to right and are already scaled to
/// about ±1 by an automatic gain, because a stem's level says nothing about its
/// shape and a quiet vocal should be as legible as a loud kick. `levelDb` is the
/// real level, for the readout.
public struct ScopeTrace: Sendable {
    public let points: [Float]
    public let windowMs: Float
    public let mode: TraceMode
    public let hz: Float
    public let note: String
    public let clarity: Float
    public let levelDb: Float
    /// Periods averaged into the picture (1 = raw audio).
    public let folded: Int
    /// True while this is a held copy of an older lock.
    public let stale: Bool

    public init(points: [Float], windowMs: Float, mode: TraceMode, hz: Float, note: String,
                clarity: Float, levelDb: Float, folded: Int, stale: Bool) {
        self.points = points
        self.windowMs = windowMs
        self.mode = mode
        self.hz = hz
        self.note = note
        self.clarity = clarity
        self.levelDb = levelDb
        self.folded = folded
        self.stale = stale
    }

    public static func quiet(points: Int, windowMs: Float, levelDb: Float) -> ScopeTrace {
        ScopeTrace(points: [Float](repeating: 0, count: points), windowMs: windowMs, mode: .quiet,
                   hz: 0, note: "", clarity: 0, levelDb: levelDb, folded: 1, stale: false)
    }
}

// ScopeLock.kt, part 2: the ScopeLock class — ported in the scopes pass.
