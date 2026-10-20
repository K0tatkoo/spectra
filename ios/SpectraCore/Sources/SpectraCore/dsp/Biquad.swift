import Foundation

/// Transposed direct form II biquad. TDF-II is chosen over DF-I because it needs
/// two state variables instead of four and is better behaved in floating point at
/// the low corner frequencies this app uses (a 20 Hz high-pass at 48 kHz is a
/// very short distance from the unit circle).
///
/// State is per instance, so a stereo band needs two. A class, like Biquad.kt,
/// so the filter banks that hold arrays of them can update them in place.
public final class Biquad {
    private let b0: Double
    private let b1: Double
    private let b2: Double
    private let a1: Double
    private let a2: Double
    private var z1 = 0.0
    private var z2 = 0.0

    public init(b0: Double, b1: Double, b2: Double, a1: Double, a2: Double) {
        self.b0 = b0
        self.b1 = b1
        self.b2 = b2
        self.a1 = a1
        self.a2 = a2
    }

    @inline(__always)
    public func process(_ x: Double) -> Double {
        let y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    public func reset() {
        z1 = 0
        z2 = 0
    }

    /// A structural copy with fresh state — used to build the second channel.
    public func copyShape() -> Biquad { Biquad(b0: b0, b1: b1, b2: b2, a1: a1, a2: a2) }

    /// RBJ cookbook low-pass. `q` = 1/√2 gives Butterworth; cascade two of
    /// those for a 24 dB/oct Linkwitz–Riley.
    public static func lowPass(sampleRate: Int, freq: Double, q: Double = sqrt1_2) -> Biquad {
        let w0 = 2.0 * Double.pi * freq / Double(sampleRate)
        let cosW = cos(w0)
        let alpha = sin(w0) / (2.0 * q)
        let a0 = 1.0 + alpha
        return Biquad(
            b0: ((1.0 - cosW) / 2.0) / a0,
            b1: (1.0 - cosW) / a0,
            b2: ((1.0 - cosW) / 2.0) / a0,
            a1: (-2.0 * cosW) / a0,
            a2: (1.0 - alpha) / a0
        )
    }

    public static func highPass(sampleRate: Int, freq: Double, q: Double = sqrt1_2) -> Biquad {
        let w0 = 2.0 * Double.pi * freq / Double(sampleRate)
        let cosW = cos(w0)
        let alpha = sin(w0) / (2.0 * q)
        let a0 = 1.0 + alpha
        return Biquad(
            b0: ((1.0 + cosW) / 2.0) / a0,
            b1: (-(1.0 + cosW)) / a0,
            b2: ((1.0 + cosW) / 2.0) / a0,
            a1: (-2.0 * cosW) / a0,
            a2: (1.0 - alpha) / a0
        )
    }

    /// Stage 1 of ITU-R BS.1770 K-weighting: the "head shelf", a +4 dB high
    /// shelf at ~1681 Hz. The published coefficients are for 48 kHz only, so
    /// they are re-derived here from the analogue prototype with a bilinear
    /// transform — the same derivation libebur128 uses, which is what makes
    /// LUFS readings correct at 44.1 kHz as well.
    public static func kWeightingShelf(sampleRate: Int) -> Biquad {
        let f0 = 1681.974450955533
        let g = 3.999843853973347
        let q = 0.7071752369554196
        let k = tan(Double.pi * f0 / Double(sampleRate))
        let vh = pow(10.0, g / 20.0)
        let vb = pow(vh, 0.4996667741545416)
        let a0 = 1.0 + k / q + k * k
        return Biquad(
            b0: (vh + vb * k / q + k * k) / a0,
            b1: 2.0 * (k * k - vh) / a0,
            b2: (vh - vb * k / q + k * k) / a0,
            a1: 2.0 * (k * k - 1.0) / a0,
            a2: (1.0 - k / q + k * k) / a0
        )
    }

    /// Stage 2 of K-weighting: a 2nd-order high-pass at ~38 Hz (unity numerator).
    public static func kWeightingHighPass(sampleRate: Int) -> Biquad {
        let f0 = 38.13547087602444
        let q = 0.5003270373238773
        let k = tan(Double.pi * f0 / Double(sampleRate))
        let denom = 1.0 + k / q + k * k
        return Biquad(
            b0: 1.0,
            b1: -2.0,
            b2: 1.0,
            a1: 2.0 * (k * k - 1.0) / denom,
            a2: (1.0 - k / q + k * k) / denom
        )
    }

    public static let sqrt1_2 = 1.0 / 2.0.squareRoot()

    /// Butterworth Q values for a cascade of `order`/2 biquads. A 4th-order
    /// Butterworth is NOT two Q=0.707 sections (that is Linkwitz–Riley, which
    /// is −6 dB at the corner); it is Q = 0.5412 and 1.3066.
    public static func butterworthQs(order: Int) -> [Double] {
        let sections = order / 2
        return (0..<sections).map { i in
            1.0 / (2.0 * cos(Double.pi * (2.0 * Double(i) + 1.0) / (2.0 * Double(order))))
        }
    }
}
