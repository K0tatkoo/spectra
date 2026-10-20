import Foundation

/// Asymmetric attack/release smoothing in the dB domain, plus the peak-hold
/// decay that sits on top of it.
///
/// Smoothing in dB rather than in linear magnitude is deliberate: a 20 ms release
/// applied to linear amplitude spends almost all of its time in the top 20 dB and
/// looks frozen near the noise floor. In dB the fall rate is what the user set.
///
/// Coefficients follow the usual one-pole form, α = 1 − e^(−T/τ), so "300 ms
/// release" means the reading has covered 63 % of the distance after 300 ms —
/// the same definition a hardware VU meter uses.
///
/// Not thread safe; the analysis thread owns it.
public final class Ballistics {
    private var frameIntervalSec: Float

    public var attackMs: Float = 20 { didSet { recompute() } }
    public var releaseMs: Float = 300 { didSet { recompute() } }

    private var attackCoef: Float = 0
    private var releaseCoef: Float = 0

    public init(frameIntervalSec: Float) {
        self.frameIntervalSec = frameIntervalSec
        recompute()
    }

    public func setFrameInterval(_ sec: Float) {
        frameIntervalSec = sec
        recompute()
    }

    private func recompute() {
        attackCoef = coef(attackMs)
        releaseCoef = coef(releaseMs)
    }

    private func coef(_ ms: Float) -> Float {
        if ms <= 0 { return 1 }
        return coerceIn(Float(1.0 - exp(-Double(frameIntervalSec) / (Double(ms) / 1000.0))), 0, 1)
    }

    /// One step with an explicit time delta, for callers whose tick interval is
    /// set by the capture chunk size rather than by the hop — the band meters run
    /// off whatever the capture happened to hand over, which is not constant.
    public func stepDt(_ current: Float, _ target: Float, dtSec: Float) -> Float {
        let ms = target > current ? attackMs : releaseMs
        let c: Float = ms <= 0 ? 1
            : coerceIn(Float(1.0 - exp(-Double(dtSec) / (Double(ms) / 1000.0))), 0, 1)
        return current + (target - current) * c
    }

    /// One smoothed step from `current` towards `target`; both in dB.
    public func step(_ current: Float, _ target: Float) -> Float {
        let coef = target > current ? attackCoef : releaseCoef
        return current + (target - current) * coef
    }

    /// Vectorised form, used for the ~2000 spectrum bins.
    public func stepAll(_ current: inout [Float], _ target: [Float]) {
        current.withUnsafeMutableBufferPointer { c in
            target.withUnsafeBufferPointer { t in stepAll(c.baseAddress!, t.baseAddress!, count: c.count) }
        }
    }

    /// The same on raw buffers of `count` floats each.
    public func stepAll(_ current: UnsafeMutablePointer<Float>, _ target: UnsafePointer<Float>, count: Int) {
        let a = attackCoef
        let r = releaseCoef
        for i in 0..<count {
            let t = target[i]
            let c = current[i]
            current[i] = c + (t - c) * (t > c ? a : r)
        }
    }
}

/// Peak hold with a flat hold time followed by a constant dB/s fall. Kept
/// separate from `Ballistics` because peak hold is not a filter — it is a
/// max() with a timer, and mixing the two produces the "sagging peaks" that
/// make an analyser useless for spotting transients.
///
/// Not thread safe; the analysis thread owns it.
public final class PeakHold {
    public var holdMs: Float = 900
    public var fallDbPerSec: Float = 24
    public var enabled: Bool = true

    public private(set) var values: [Float]
    private var heldForMs: [Float]

    public init(size: Int) {
        values = [Float](repeating: PeakHold.floorDb, count: size)
        heldForMs = [Float](repeating: 0, count: size)
    }

    public func resize(_ size: Int) -> PeakHold {
        if values.count == size { return self }
        let p = PeakHold(size: size)
        p.holdMs = holdMs
        p.fallDbPerSec = fallDbPerSec
        p.enabled = enabled
        return p
    }

    public func update(_ current: [Float], dtMs: Float) {
        current.withUnsafeBufferPointer { update($0.baseAddress!, dtMs: dtMs) }
    }

    /// `current` must hold at least `values.count` floats.
    public func update(_ current: UnsafePointer<Float>, dtMs: Float) {
        if !enabled {
            values.withUnsafeMutableBufferPointer { $0.update(repeating: PeakHold.floorDb) }
            return
        }
        let fall = fallDbPerSec * dtMs / 1000
        let hold = holdMs
        values.withUnsafeMutableBufferPointer { values in
            heldForMs.withUnsafeMutableBufferPointer { heldForMs in
                for i in 0..<values.count {
                    let v = current[i]
                    if v >= values[i] {
                        values[i] = v
                        heldForMs[i] = 0
                    } else {
                        heldForMs[i] += dtMs
                        if heldForMs[i] > hold { values[i] -= fall }
                        if values[i] < PeakHold.floorDb { values[i] = PeakHold.floorDb }
                    }
                }
            }
        }
    }

    public func reset() {
        values.withUnsafeMutableBufferPointer { $0.update(repeating: PeakHold.floorDb) }
        heldForMs.withUnsafeMutableBufferPointer { $0.update(repeating: 0) }
    }

    /// Everything below this is "silence" as far as the display is concerned.
    public static let floorDb: Float = -140
}
