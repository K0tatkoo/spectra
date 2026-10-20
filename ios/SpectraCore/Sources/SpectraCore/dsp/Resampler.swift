import Foundation

/// Streaming stereo sample-rate converter, polyphase windowed sinc.
///
/// It exists for one reason: the stem model is trained at exactly 44.1 kHz and
/// the capture usually runs at 48. Feeding it 48 kHz audio would not fail — it
/// would quietly shift every pitch the model knows up by 1.5 semitones and every
/// spectral boundary it learned with it, which is the kind of wrong that looks
/// like "the model is just not very good".
///
/// The ratio is reduced to L/M (48000 → 44100 is 147/160) and one short sinc
/// kernel per output phase is precomputed, so each output sample is a single
/// 32-tap dot product per channel. The cut-off sits a little under the lower
/// Nyquist, Kaiser-windowed: separation does not need mastering-grade
/// conversion, it needs nothing folded back into the band it listens to.
///
/// Not thread safe; the analysis thread owns one.
public final class Resampler {

    public let inRate: Int
    public let outRate: Int
    private let halfTaps: Int

    public let passthrough: Bool

    private let up: Int
    private let down: Int
    private let taps: Int
    private let kernel: [Float]

    private var bufL = [Float](repeating: 0, count: 8192)
    private var bufR = [Float](repeating: 0, count: 8192)
    private var bufLen: Int
    private var pos: Int
    private var phase = 0

    public init(inRate: Int, outRate: Int, halfTaps: Int = 16) {
        self.inRate = inRate
        self.outRate = outRate
        self.halfTaps = halfTaps
        passthrough = inRate == outRate
        taps = halfTaps * 2
        bufLen = halfTaps - 1
        pos = halfTaps - 1

        let g = Resampler.gcd(inRate, outRate)
        up = outRate / g
        down = inRate / g
        var kernel = [Float](repeating: 0, count: passthrough ? 0 : up * taps)
        if !passthrough {
            // Cut-off as a fraction of the *input* rate.
            let fc = 0.5 * min(1.0, Double(outRate) / Double(inRate)) * Resampler.cutoff
            let beta = Resampler.kaiserBeta
            let i0Beta = besselI0(beta)
            for p in 0..<up {
                let frac = Double(p) / Double(up)
                var sum = 0.0
                for k in 0..<taps {
                    // Offset of input sample k from the output instant, in input samples.
                    let t = Double(k - halfTaps + 1) - frac
                    let x = 2.0 * fc * t
                    let sinc = x == 0.0 ? 1.0 : sin(Double.pi * x) / (Double.pi * x)
                    let r = t / Double(halfTaps)
                    let w = r * r >= 1.0 ? 0.0 : besselI0(beta * (1.0 - r * r).squareRoot()) / i0Beta
                    let v = 2.0 * fc * sinc * w
                    kernel[p * taps + k] = Float(v)
                    sum += v
                }
                // Normalise each phase to unity DC gain. Without it the phases
                // disagree by a fraction of a dB and the output grows a faint
                // tone at the ratio's beat frequency. (The Kotlin divides the
                // already-Float tap by the Double sum; so does this.)
                for k in 0..<taps { kernel[p * taps + k] = Float(Double(kernel[p * taps + k]) / sum) }
            }
        }
        self.kernel = kernel
    }

    /// Upper bound on the output of one `process` call with `frames` of input.
    public func maxOutput(_ frames: Int) -> Int {
        passthrough ? frames : Int(Int64(frames) * Int64(up) / Int64(down)) + 2
    }

    /// Converts `frames` of input. `inR` may be the same buffer as `inL` for mono.
    /// Returns the number of frames written to `outL`/`outR`, which must have room
    /// for `maxOutput(frames)`.
    public func process(
        _ inL: UnsafePointer<Float>, _ inR: UnsafePointer<Float>, frames: Int,
        outL: UnsafeMutablePointer<Float>, outR: UnsafeMutablePointer<Float>
    ) -> Int {
        if passthrough {
            outL.update(from: inL, count: frames)
            outR.update(from: inR, count: frames)
            return frames
        }
        ensure(bufLen + frames)
        let halfTaps = self.halfTaps
        let taps = self.taps
        let up = self.up
        let down = self.down
        var bufLen = self.bufLen
        var pos = self.pos
        var phase = self.phase
        var out = 0
        // The Kotlin's array writes would throw past the end; raw pointers would
        // not, so a debug build checks the promise `maxOutput` makes.
        let limit = maxOutput(frames)

        kernel.withUnsafeBufferPointer { kernel in
            bufL.withUnsafeMutableBufferPointer { bufL in
                bufR.withUnsafeMutableBufferPointer { bufR in
                    (bufL.baseAddress! + bufLen).update(from: inL, count: frames)
                    (bufR.baseAddress! + bufLen).update(from: inR, count: frames)
                    bufLen += frames

                    while pos + halfTaps < bufLen {
                        let k0 = phase * taps
                        let base = pos - halfTaps + 1
                        var l: Float = 0
                        var r: Float = 0
                        for k in 0..<taps {
                            let c = kernel[k0 + k]
                            l += bufL[base + k] * c
                            r += bufR[base + k] * c
                        }
                        assert(out < limit, "Resampler wrote past maxOutput(\(frames))")
                        outL[out] = l
                        outR[out] = r
                        out += 1
                        phase += down
                        while phase >= up {
                            phase -= up
                            pos += 1
                        }
                    }

                    // Keep only what the next output still needs.
                    let drop = pos - (halfTaps - 1)
                    if drop > 0 {
                        // Overlapping, moving down: update(from:) copies front to back.
                        bufL.baseAddress!.update(from: bufL.baseAddress! + drop, count: bufLen - drop)
                        bufR.baseAddress!.update(from: bufR.baseAddress! + drop, count: bufLen - drop)
                        bufLen -= drop
                        pos -= drop
                    }
                }
            }
        }

        self.bufLen = bufLen
        self.pos = pos
        self.phase = phase
        return out
    }

    /// The same for arrays. `outL`/`outR` must hold `maxOutput(frames)`.
    public func process(_ inL: [Float], _ inR: [Float], frames: Int, outL: inout [Float], outR: inout [Float]) -> Int {
        precondition(inL.count >= frames && inR.count >= frames)
        precondition(outL.count >= maxOutput(frames) && outR.count >= maxOutput(frames))
        if frames <= 0 { return 0 }
        return inL.withUnsafeBufferPointer { l in
            inR.withUnsafeBufferPointer { r in
                outL.withUnsafeMutableBufferPointer { ol in
                    outR.withUnsafeMutableBufferPointer { or in
                        process(l.baseAddress!, r.baseAddress!, frames: frames, outL: ol.baseAddress!, outR: or.baseAddress!)
                    }
                }
            }
        }
    }

    public func reset() {
        bufL.withUnsafeMutableBufferPointer { $0.update(repeating: 0) }
        bufR.withUnsafeMutableBufferPointer { $0.update(repeating: 0) }
        bufLen = halfTaps - 1
        pos = halfTaps - 1
        phase = 0
    }

    private func ensure(_ size: Int) {
        if size <= bufL.count { return }
        var n = bufL.count
        while n < size { n *= 2 }
        bufL.append(contentsOf: repeatElement(0, count: n - bufL.count))
        bufR.append(contentsOf: repeatElement(0, count: n - bufR.count))
    }

    private static let cutoff = 0.94
    private static let kaiserBeta = 8.0

    private static func gcd(_ a: Int, _ b: Int) -> Int { b == 0 ? a : gcd(b, a % b) }
}

/// Zeroth-order modified Bessel function, by its power series. For Kaiser windows.
func besselI0(_ x: Double) -> Double {
    var sum = 1.0
    var term = 1.0
    let half = x / 2.0
    var k = 1
    while k < 64 {
        term *= (half / Double(k)) * (half / Double(k))
        sum += term
        if term < sum * 1e-12 { break }
        k += 1
    }
    return sum
}
