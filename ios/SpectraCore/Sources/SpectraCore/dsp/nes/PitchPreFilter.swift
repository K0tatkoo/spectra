import Foundation

/// The low-pass in front of the period detector.
///
/// Without it the tracker locks to whatever is loudest, and in NES music that is
/// usually the pulse melody rather than the triangle bass — worse, two pulses a
/// fourth apart share a common subharmonic, so the detector finds a confident,
/// completely wrong period an octave or two below the tune. The bass is the only
/// thing under the hunt range, so removing everything above it is what makes the
/// question well posed.
///
/// It is 8th order because 4th is not enough: a melody a fourth above the hunt
/// ceiling is only 17 dB down through a 4th-order skirt, which loses to a bass
/// line mixed 5 dB quieter. At 8th order the same note is 57 dB down and the
/// question is no longer close.
///
/// This filters a *stream*, not a window. Re-filtering the same history every
/// frame would put a settling transient inside the analysis window and move the
/// detected period around as the transient slid through it.
///
/// Not thread safe; the analysis thread owns one.
public final class PitchPreFilter {

    private let stages: ContiguousArray<Biquad>

    public init(sampleRate: Int, cornerHz: Double) {
        stages = ContiguousArray(Biquad.butterworthQs(order: PitchPreFilter.order).map {
            Biquad.lowPass(sampleRate: sampleRate, freq: cornerHz, q: $0)
        })
    }

    @inline(__always)
    public func process(_ x: Float) -> Float {
        var v = Double(x)
        for s in stages { v = s.process(v) }
        return Float(v)
    }

    public func process(_ src: UnsafePointer<Float>, _ dst: UnsafeMutablePointer<Float>, count: Int) {
        for i in 0..<count { dst[i] = process(src[i]) }
    }

    public func process(_ src: [Float], _ dst: inout [Float], count: Int) {
        src.withUnsafeBufferPointer { s in
            dst.withUnsafeMutableBufferPointer { d in
                process(s.baseAddress!, d.baseAddress!, count: count)
            }
        }
    }

    public func reset() {
        for s in stages { s.reset() }
    }

    private static let order = 8

    /// Corner for a given hunt ceiling. Just above it, so a bass note at the
    /// very top of the range is still passed flat.
    public static func cornerFor(_ huntMaxHz: Float) -> Double {
        coerceIn(Double(huntMaxHz * 1.2), 40.0, 4000.0)
    }
}
