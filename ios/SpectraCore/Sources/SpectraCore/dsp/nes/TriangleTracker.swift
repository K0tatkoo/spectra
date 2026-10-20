import Foundation

// TriangleTracker.kt, part 1: the options and the reading. The pages and
// AnalysisFrame need these before the tracker itself (part 2, below) exists.

/// How the 2A03 waveform view behaves. All of it is display, none of it is capture.
public struct NesOptions: Sendable, Equatable {
    public var region: Nes2A03.Region = .ntsc
    /// Snap the display period to the nearest 2A03 timer value. This is what makes it stand still.
    public var lockToTimer: Bool = true
    /// Periods drawn across the plot.
    public var cycles: Int = 2
    public var huntMinHz: Float = 25
    public var huntMaxHz: Float = 200
    /// NSDF peak below which the detection is not trusted.
    public var clarityThreshold: Float = 0.55
    /// Fold every period in the window onto one. Rejects the other four channels.
    public var averaging: Bool = true
    /// How many periods to fold together. Rejection of anything not harmonically
    /// locked to the note goes as √n, so 8 is about 9 dB — enough to lift the
    /// triangle out from under a melody, while still following a bass line that
    /// changes note every eighth.
    public var foldPeriods: Int = 8
    /// Cross-frame smoothing of the folded period, 0…0.95.
    public var persistence: Float = 0.55
    public var showIdeal: Bool = true
    public var showLevels: Bool = true
    public var showSteps: Bool = true
    /// Snap the trace to the sixteen DAC levels the chip can actually output.
    public var quantizeToDac: Bool = false
    /// Mark the individual captured samples, so the time grid is visible too.
    public var showSamples: Bool = true
    /// Keep the last good lock on screen this long after the signal goes.
    public var holdMs: Float = 900

    public init() {}
}

/// One frame of 2A03 triangle analysis. Everything the painter needs, already in
/// chip units — phase 0…1, amplitude normalised to the DAC's own ±1.
public struct NesReading: Sendable {
    public let locked: Bool
    /// True while this is a held copy of an older lock rather than a live one.
    public let stale: Bool
    public let region: Nes2A03.Region
    /// Measured fundamental, Hz.
    public let hz: Double
    /// The 11-bit timer that would produce it.
    public let timer: Int
    /// What that timer actually plays.
    public let nesHz: Double
    public let note: String
    /// Cents from `nesHz` to equal temperament.
    public let cents: Double
    /// Cents between the measurement and the chip's grid — large means this is not a 2A03.
    public let gridCents: Double
    public let samplesPerStep: Double
    public let periodSamples: Double
    /// Cents between this timer and the next one — how much the chip's grid is worth here.
    public let gridStepCents: Double
    /// Periods actually folded. Below the setting while a note is still new.
    public let foldedPeriods: Int
    /// NSDF peak, 0…1. How periodic the input is.
    public let clarity: Float
    /// Correlation with the ideal 32-step wave, −1…1. Dominated by the fundamental.
    public let match: Float
    /// Correlation with the ideal wave *after the fundamental is removed*, −1…1.
    ///
    /// This is the number that says "2A03" and `match` is not. A plain sine
    /// correlates 0.991 with a triangle — the triangle's own fundamental carries
    /// 99 % of its energy — so a high `match` proves only that something periodic
    /// is there. What is left after the fundamental is subtracted is the staircase
    /// and the odd-harmonic series, and that is the chip's signature.
    public let stepMatch: Float
    /// Fitted amplitude of the triangle component, dBFS.
    public let levelDb: Float
    /// One period folded and averaged, `grid` points, normalised to ±1. Empty when not averaging.
    public let cycle: [Float]
    /// Raw samples across the drawn span, normalised to ±1.
    public let live: [Float]
    /// Phase of `live[0]`, in periods. Usually a small negative number.
    public let livePhase0: Float
    public let cycles: Int
    public let audible: Bool

    public init(
        locked: Bool, stale: Bool, region: Nes2A03.Region, hz: Double, timer: Int, nesHz: Double,
        note: String, cents: Double, gridCents: Double, samplesPerStep: Double, periodSamples: Double,
        gridStepCents: Double, foldedPeriods: Int, clarity: Float, match: Float, stepMatch: Float,
        levelDb: Float, cycle: [Float], live: [Float], livePhase0: Float, cycles: Int, audible: Bool
    ) {
        self.locked = locked
        self.stale = stale
        self.region = region
        self.hz = hz
        self.timer = timer
        self.nesHz = nesHz
        self.note = note
        self.cents = cents
        self.gridCents = gridCents
        self.samplesPerStep = samplesPerStep
        self.periodSamples = periodSamples
        self.gridStepCents = gridStepCents
        self.foldedPeriods = foldedPeriods
        self.clarity = clarity
        self.match = match
        self.stepMatch = stepMatch
        self.levelDb = levelDb
        self.cycle = cycle
        self.live = live
        self.livePhase0 = livePhase0
        self.cycles = cycles
        self.audible = audible
    }

    /// Points in `cycle`.
    public static let grid = 2048
}

// TriangleTracker.kt, part 2: the TriangleTracker class — ported in the scopes pass.
