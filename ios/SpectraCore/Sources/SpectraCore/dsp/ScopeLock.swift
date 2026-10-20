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

// ScopeLock.kt, part 2: the ScopeLock class.

/// Holds a waveform still, the way the channel scopes in chiptune videos do.
///
/// The time window is fixed, so a higher note shows more, narrower cycles and a
/// lower one fewer, wider ones — the picture squeezes and stretches with the
/// melody instead of scrolling. What keeps it still is the anchor: the centre of
/// the window always sits on the same point of the note's cycle.
///
///  1. **Period.** McLeod's NSDF on a low-passed copy (`PitchDetector`).
///  2. **Phase.** The argument of a single DFT bin at the fundamental, taken over
///     several whole periods. It is continuous — a threshold trigger would add a
///     sample of jitter every frame — and averaging over periods keeps whatever
///     else is still in the lane from dragging the picture sideways.
///  3. **Clean-up.** Optionally, every point on screen is the average of the
///     same point in each period of the last `cleanMs`. That is a comb filter
///     with its teeth on the note's harmonics: the note survives intact, corners
///     and all, and anything not locked to it (the leftovers of the other stems)
///     falls by √periods. Measured in time, not in cycles, so a high note and a
///     low one are cleaned alike and follow a melody equally fast; the depth
///     ramps back up after each note change, so a moving bass line is never
///     smeared across two notes.
///
/// Drums have no period, so their lane uses a trigger instead: an energy jump
/// marks a hit, and the window stays on that hit — frozen — until the next one.
///
/// Not thread safe; the analysis thread owns one per lane. The only allocation
/// per call is the trace's own `points`, which crosses to the painter's thread.
public final class ScopeLock {

    private let sampleRate: Int
    public let spec: ScopeLaneSpec

    private let detector: PitchDetector

    private var gain: Float = 1
    /// The last pitch-locked picture before its gain, for the cross-frame blend.
    /// Kotlin keeps a nullable copy; here the storage is kept and `previousValid` says whether it holds one.
    private var previous: [Float] = []
    private var previousValid = false
    private var previousTau = -1.0
    private var lastTau = -1.0
    private var sinceChangeMs: Float = 0
    private var held: ScopeTrace? = nil
    private var heldMs: Float = 0

    // Transient trigger state, all in absolute sample indices.
    private var scannedTo = Int64.min
    private var slowEnergy = 0.0
    private var onset = Int64.min
    private var previousOnset = Int64.min

    /// One frame's picture before its gain, `count` points.
    private var scratch: [Float] = []

    public init(sampleRate: Int, spec: ScopeLaneSpec) {
        self.sampleRate = sampleRate
        self.spec = spec
        detector = PitchDetector(sampleRate: sampleRate, window: spec.pitchWindow)
    }

    public func reset() {
        gain = 1
        previousValid = false
        previousTau = -1.0
        lastTau = -1.0
        sinceChangeMs = 0
        held = nil
        heldMs = 0
        scannedTo = .min
        slowEnergy = 0.0
        onset = .min
        previousOnset = .min
    }

    /// - Parameters:
    ///   - raw: the lane's newest audio, oldest first.
    ///   - pitched: the same audio low-passed for the detector, sample aligned.
    ///   - end: absolute index one past `raw.last`.
    ///   - windowMs: width of the picture.
    ///   - cleanMs: how much of the note's recent past to average in, 0 = off.
    ///   - points: resolution of the trace handed to the painter.
    ///   - dtMs: time since the previous call.
    ///   - validFrom: absolute index of the first sample worth measuring. The
    ///     stem separator moves it forward whenever it skips, so a period is
    ///     never measured across the seam.
    public func analyze(
        _ raw: UnsafeBufferPointer<Float>,
        _ pitched: UnsafeBufferPointer<Float>,
        end: Int64,
        windowMs: Float,
        cleanMs: Float,
        points: Int,
        dtMs: Float,
        validFrom: Int64 = .min
    ) -> ScopeTrace {
        let n = raw.count
        let firstValid = validFrom == .min ? 0 : Int(coerceIn(validFrom - (end - Int64(n)), 0, Int64(n)))
        let w = coerceIn(Double(windowMs * Float(sampleRate) / 1000), 16.0, Double(n) / 2.0)
        let count = coerceIn(points, 16, max(16, ktToInt(w)))
        let newest = n - 1 - ScopeLock.edge
        let levelDb = levelDb(raw, ktToInt(Double(newest) - w), newest)

        // Every lane tracks hits, not just the drums: a pitched lane that loses
        // its note falls back to the last hit rather than to a scrolling wave.
        scanOnsets(raw, end)

        if levelDb < ScopeLock.quietDb {
            previousValid = false
            held = nil
            lastTau = -1.0
            return ScopeTrace.quiet(points: count, windowMs: windowMs, levelDb: levelDb)
        }

        if spec.trigger == .pitch {
            // Too little clean audio since a skip to measure a period on: keep
            // the last good picture a while longer instead of guessing.
            if n - firstValid < spec.pitchWindow + ScopeLock.edge {
                heldMs += dtMs
                if let h = held, heldMs <= ScopeLock.settleHoldMs { return h.asStale() }
                return triggered(raw, end, w, count, windowMs, levelDb)
            }
            let tau = detector.detect(pitched, minHz: spec.minHz, maxHz: spec.maxHz)
            if tau > 0.0 && detector.clarity >= ScopeLock.clarity {
                heldMs = 0
                let trace = pitchLocked(raw, tau, w, count, cleanMs, windowMs, levelDb, dtMs, firstValid)
                held = trace
                return trace
            }
            // A breath, a consonant, the gap between two notes: keep the last
            // lock on screen briefly rather than let the picture jump to noise.
            heldMs += dtMs
            if let h = held, heldMs <= ScopeLock.holdMs { return h.asStale() }
            held = nil
        }

        return triggered(raw, end, w, count, windowMs, levelDb)
    }

    /// The same, on arrays.
    public func analyze(
        _ raw: [Float], _ pitched: [Float], end: Int64, windowMs: Float, cleanMs: Float,
        points: Int, dtMs: Float, validFrom: Int64 = .min
    ) -> ScopeTrace {
        raw.withUnsafeBufferPointer { r in
            pitched.withUnsafeBufferPointer { p in
                analyze(r, p, end: end, windowMs: windowMs, cleanMs: cleanMs, points: points,
                        dtMs: dtMs, validFrom: validFrom)
            }
        }
    }

    /// The same lane, not held: the newest `windowMs` of `raw` as it arrives,
    /// the classic free-running scope.
    ///
    /// Nothing here looks for a note, so it costs a small fraction of
    /// `analyze`: no pitch detector, no phase, no fold, no hit scan. It keeps
    /// the lane's automatic gain, smoothed so the picture does not pump from
    /// frame to frame. `raw` only has to reach back one window. Call `reset`
    /// when a lane switches between this and `analyze`.
    public func freeRunning(_ raw: UnsafeBufferPointer<Float>, windowMs: Float, points: Int, dtMs: Float) -> ScopeTrace {
        let newest = raw.count - 1 - ScopeLock.edge
        let w = coerceIn(Double(windowMs * Float(sampleRate) / 1000), 16.0, Double(newest))
        let count = coerceIn(points, 16, max(16, ktToInt(w)))
        let start = Double(newest) - w
        let levelDb = levelDb(raw, ktToInt(start), newest)
        if levelDb < ScopeLock.quietDb { return ScopeTrace.quiet(points: count, windowMs: windowMs, levelDb: levelDb) }

        let step = w / Double(count - 1)
        if scratch.count != count { scratch = [Float](repeating: 0, count: count) }
        var peak: Float = 0
        scratch.withUnsafeMutableBufferPointer { out in
            for i in 0..<count {
                let v = ScopeLock.sampleAt(raw, start + Double(i) * step)
                out[i] = v
                peak = max(peak, abs(v))
            }
        }
        return ScopeTrace(points: normalise(scratch, peak, dtMs), windowMs: windowMs, mode: .free, hz: 0, note: "",
                          clarity: 0, levelDb: levelDb, folded: 1, stale: false)
    }

    /// The same, on an array.
    public func freeRunning(_ raw: [Float], windowMs: Float, points: Int, dtMs: Float) -> ScopeTrace {
        raw.withUnsafeBufferPointer { freeRunning($0, windowMs: windowMs, points: points, dtMs: dtMs) }
    }

    // ---- pitch lock --------------------------------------------------------

    private func pitchLocked(
        _ raw: UnsafeBufferPointer<Float>,
        _ tau: Double,
        _ w: Double,
        _ count: Int,
        _ cleanMs: Float,
        _ windowMs: Float,
        _ levelDb: Float,
        _ dtMs: Float,
        _ firstValid: Int
    ) -> ScopeTrace {
        let n = raw.count
        let newest = Double(n - 1 - ScopeLock.edge)

        // Note-change bookkeeping for the fold ramp.
        if lastTau <= 0.0 || abs(tau / lastTau - 1.0) > ScopeLock.noteChange {
            sinceChangeMs = 0
        } else {
            sinceChangeMs += dtMs
        }
        lastTau = tau
        let periodMs = Float(tau * 1000.0 / Double(sampleRate))
        let elapsedPeriods = ktToInt(floor(sinceChangeMs / periodMs)) + 1
        let foldSetting = coerceIn(ktToInt(floor(cleanMs / periodMs)), 1, ScopeLock.maxFold)

        // Phase of the fundamental over several whole periods ending at the
        // newest sample. Whole periods, so the bin's own image cancels.
        let phasePeriods = max(2, min(foldSetting, 4))
        let len = min(ktToInt(Double(phasePeriods) * tau), ktToInt(newest - Double(firstValid)))
        let i0 = max(ktToInt(newest - Double(len)), 0)
        // The bin's rotation is stepped by complex multiplication rather than
        // a cos and a sin per sample: thousands of samples, four lanes, every
        // frame. Double precision keeps the drift far below anything visible.
        let omega = 2.0 * Double.pi / tau
        let stepRe = cos(omega)
        let stepIm = sin(omega)
        var rotRe = 1.0
        var rotIm = 0.0
        var c = 0.0
        var s = 0.0
        if len > 0 {
            for i in 0..<len {
                let v = Double(raw[i0 + i])
                c += v * rotRe
                s -= v * rotIm
                let nr = rotRe * stepRe - rotIm * stepIm
                rotIm = rotRe * stepIm + rotIm * stepRe
                rotRe = nr
            }
        }
        // A cosine peak of the fundamental; every other one is a whole period away.
        let peak0 = Double(i0) - tau * atan2(s, c) / (2.0 * Double.pi)
        let half = w / 2.0
        let k = floor((newest - half - peak0) / tau)
        let anchor = peak0 + k * tau
        let start = anchor - half

        // How many periods can be averaged: the setting, the ramp after a note
        // change, and the history that is actually there.
        let available = ktToInt(floor((start - Double(firstValid)) / tau)) + 1
        let folds = min(min(max(foldSetting, 1), elapsedPeriods), max(1, available))

        if scratch.count != count { scratch = [Float](repeating: 0, count: count) }
        let step = w / Double(count - 1)
        var peak: Float = 0
        let inv = 1 / Float(folds)
        scratch.withUnsafeMutableBufferPointer { out in
            for i in 0..<count {
                let t = start + Double(i) * step
                var v: Float = 0
                for j in 0..<folds { v += ScopeLock.sampleAt(raw, t - Double(j) * tau) }
                v *= inv
                out[i] = v
                peak = max(peak, abs(v))
            }

            // Blend with the previous frame when the note has not moved. Both are
            // anchored to the same phase, so this only softens what is *not* the
            // note — the wobble of leftover bleed — and never smears the shape.
            if previousValid && previous.count == count && previousTau > 0.0 &&
                abs(tau / previousTau - 1.0) < ScopeLock.sameNote
            {
                previous.withUnsafeBufferPointer { prev in
                    for i in 0..<count {
                        out[i] = prev[i] * ScopeLock.persistence + out[i] * (1 - ScopeLock.persistence)
                    }
                }
            }
        }
        if previous.count != count { previous = [Float](repeating: 0, count: count) }
        previous.withUnsafeMutableBufferPointer { _ = $0.update(fromContentsOf: scratch) }
        previousValid = true
        previousTau = tau

        let hz = Float(Double(sampleRate) / tau)
        let scaled = normalise(scratch, peak, dtMs)
        return ScopeTrace(points: scaled, windowMs: windowMs, mode: .pitch, hz: hz, note: Nes2A03.noteName(Double(hz)),
                          clarity: detector.clarity, levelDb: levelDb, folded: folds, stale: false)
    }

    // ---- transient trigger -------------------------------------------------

    /// Walks the audio that arrived since the last call in short blocks, marking
    /// a hit wherever the block energy jumps well above its own recent average.
    private func scanOnsets(_ raw: UnsafeBufferPointer<Float>, _ end: Int64) {
        let n = raw.count
        let first = end - Int64(n)
        let block = max(8, sampleRate * ScopeLock.blockMs / 1000)
        var from = scannedTo == .min ? end - Int64(block) * 8 : max(scannedTo, first)
        let alpha = 1.0 - exp(Double(-ScopeLock.blockMs) / ScopeLock.slowMs)
        let holdoff = Int64(sampleRate * ScopeLock.holdoffMs) / 1000
        while from + Int64(block) <= end {
            let base = Int(from - first)
            var e = 0.0
            for i in 0..<block {
                let v = Double(raw[base + i])
                e += v * v
            }
            e /= Double(block)
            let clear = onset == .min || from - onset > holdoff
            if clear && e > slowEnergy * ScopeLock.onsetRatio && e > ScopeLock.onsetFloor {
                previousOnset = onset
                onset = from
            }
            slowEnergy += (e - slowEnergy) * alpha
            from += Int64(block)
        }
        scannedTo = from
    }

    private func triggered(
        _ raw: UnsafeBufferPointer<Float>,
        _ end: Int64,
        _ w: Double,
        _ count: Int,
        _ windowMs: Float,
        _ levelDb: Float
    ) -> ScopeTrace {
        previousValid = false
        let n = raw.count
        let first = end - Int64(n)
        let newest = n - 1 - ScopeLock.edge
        let pre = w * ScopeLock.preTrigger

        // The newest hit whose whole window has arrived; failing that the one
        // before it, which is what stays on screen while a new hit fills in.
        var start = -1.0
        for which in 0..<2 {
            let o = which == 0 ? onset : previousOnset
            if o == .min { continue }
            let s = Double(o - first) - pre
            if s >= 0.0 && s + w <= Double(newest) && end - o <= Int64(ScopeLock.maxHitAge * sampleRate / 1000) {
                start = s
                break
            }
        }
        let mode: TraceMode = start >= 0.0 ? .hit : .free
        if start < 0.0 { start = max(Double(newest) - w, 0.0) }

        let step = w / Double(count - 1)
        if scratch.count != count { scratch = [Float](repeating: 0, count: count) }
        var peak: Float = 0
        scratch.withUnsafeMutableBufferPointer { out in
            for i in 0..<count {
                let v = ScopeLock.sampleAt(raw, start + Double(i) * step)
                out[i] = v
                peak = max(peak, abs(v))
            }
        }
        return ScopeTrace(points: normalise(scratch, peak, 0), windowMs: windowMs, mode: mode, hz: 0, note: "",
                          clarity: 0, levelDb: levelDb, folded: 1, stale: false)
    }

    // ---- shared ------------------------------------------------------------

    /// RMS level of `raw[from ..< to]`, dBFS, for the readout and the quiet gate.
    private func levelDb(_ raw: UnsafeBufferPointer<Float>, _ from: Int, _ to: Int) -> Float {
        let a = max(from, 0)
        var sum = 0.0
        if a < to {
            for i in a..<to { sum += Double(raw[i]) * Double(raw[i]) }
        }
        let rms = (sum / Double(max(1, to - a))).squareRoot()
        return rms <= 1e-9 ? -120 : Float(20.0 * log10(rms))
    }

    /// Automatic gain: instant when the picture would clip, slow when it would
    /// grow, so a crescendo never runs off the lane and a quiet bar does not
    /// pump. Capped, so a lane that is nearly silent is not blown up into fuzz.
    ///
    /// Kotlin scales the frame's array in place and hands it on; here the frame
    /// is built in a reused scratch array and this returns the scaled copy that
    /// becomes the trace's `points` — the one allocation per call.
    private func normalise(_ v: [Float], _ peak: Float, _ dtMs: Float) -> [Float] {
        let target = peak <= 1e-6 ? ScopeLock.maxGain : min(ScopeLock.fill / peak, ScopeLock.maxGain)
        if target < gain || dtMs <= 0 {
            gain = target
        } else {
            // Kotlin's exp(Float) is Math.exp on the widened value, rounded back.
            gain = gain + (target - gain) * (1 - Float(exp(Double(-dtMs / ScopeLock.gainReleaseMs))))
        }
        let g = gain
        return [Float](unsafeUninitializedCapacity: v.count) { buf, initialized in
            v.withUnsafeBufferPointer { src in
                for i in 0..<src.count { buf[i] = coerceIn(src[i] * g, -1.2, 1.2) }
            }
            initialized = v.count
        }
    }

    /// Linear interpolation, clamped at both ends — the same thing a scope does.
    @inline(__always)
    private static func sampleAt(_ x: UnsafeBufferPointer<Float>, _ pos: Double) -> Float {
        if pos <= 0.0 { return x[0] }
        let i = ktToInt(pos)
        if i >= x.count - 1 { return x[x.count - 1] }
        let t = Float(pos - Double(i))
        return x[i] * (1 - t) + x[i + 1] * t
    }

    /// Samples kept clear of the newest one, for interpolation.
    private static let edge = 2
    private static let clarity: Float = 0.6
    private static let quietDb: Float = -62
    private static let holdMs: Float = 220
    /// How long to keep a picture while the separator's output settles after a skip.
    private static let settleHoldMs: Float = 600
    /// A period change bigger than this is a new note: restart the fold ramp.
    private static let noteChange = 0.03
    /// …and smaller than this is the same note, safe to blend across frames.
    private static let sameNote = 0.01
    /// Cross-frame smoothing while a note is held. Half and half: frames are
    /// ~20 ms apart, so this is another ~40 ms of averaging for the bleed
    /// and none at all across a note change, where it is switched off.
    private static let persistence: Float = 0.5
    private static let maxFold = 32
    private static let fill: Float = 0.9
    /// 36 dB. Anything quieter than about −60 dBFS stays small.
    private static let maxGain: Float = 63
    private static let gainReleaseMs: Float = 280

    private static let blockMs = 3
    private static let slowMs = 140.0
    private static let onsetRatio = 3.2
    private static let onsetFloor = 1e-6
    private static let holdoffMs = 45
    private static let preTrigger = 0.08
    private static let maxHitAge = 1500
}

private extension ScopeTrace {
    func asStale() -> ScopeTrace {
        ScopeTrace(points: points, windowMs: windowMs, mode: mode, hz: hz, note: note, clarity: clarity,
                   levelDb: levelDb, folded: folded, stale: true)
    }
}
