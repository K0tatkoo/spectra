import Foundation

/// McLeod's normalised square difference function, computed through the FFT.
///
/// The same method the 2A03 tracker uses (see `TriangleTracker.detectPeriod`),
/// lifted out so every held-still scope can share it. The autocorrelation is the
/// inverse transform of the power spectrum and the normalising term is two
/// prefix sums, so the cost is two transforms rather than a multiply-add per lag
/// per sample. The peak is chosen the MPM way — the *first* local maximum within
/// 90 % of the global one — because every multiple of the period is also a
/// near-perfect match and taking the tallest drops an octave at random.
///
/// Not thread safe; one per analysis lane.
public final class PitchDetector {
    private let sampleRate: Int
    public let window: Int

    private let fftN: Int
    private let fft: Fft
    private let re: UnsafeMutableBufferPointer<Float>
    private let im: UnsafeMutableBufferPointer<Float>
    private let prefix: UnsafeMutableBufferPointer<Double>
    private let nsdf: UnsafeMutableBufferPointer<Float>

    /// Peak height of the last detection, 0…1 — how periodic the input was.
    public private(set) var clarity: Float = 0

    public init(sampleRate: Int, window: Int) {
        self.sampleRate = sampleRate
        self.window = window
        fftN = window * 2
        fft = Fft(size: fftN)
        re = .allocate(capacity: fftN)
        re.initialize(repeating: 0)
        im = .allocate(capacity: fftN)
        im.initialize(repeating: 0)
        prefix = .allocate(capacity: window + 1)
        prefix.initialize(repeating: 0)
        nsdf = .allocate(capacity: window)
        nsdf.initialize(repeating: 0)
    }

    deinit {
        re.deallocate()
        im.deallocate()
        prefix.deallocate()
        nsdf.deallocate()
    }

    /// The period, in samples (fractional), of the newest `window` samples of `x`,
    /// searching only between `minHz` and `maxHz`. Returns −1 when there is no
    /// usable peak; `clarity` says how strong it was either way.
    public func detect(_ x: UnsafeBufferPointer<Float>, minHz: Float, maxHz: Float) -> Double {
        clarity = 0
        let n = window
        if x.count < n { return -1.0 }
        let from = x.count - n

        var mean = 0.0
        for i in 0..<n { mean += Double(x[from + i]) }
        mean /= Double(n)

        re.update(repeating: 0)
        im.update(repeating: 0)
        prefix[0] = 0.0
        for i in 0..<n {
            let v = Float(Double(x[from + i]) - mean)
            re[i] = v
            prefix[i + 1] = prefix[i] + Double(v) * Double(v)
        }
        if prefix[n] < 1e-10 { return -1.0 }

        fft.transform(re.baseAddress!, im.baseAddress!)
        for k in 0..<fftN {
            re[k] = re[k] * re[k] + im[k] * im[k]
            im[k] = 0
        }
        fft.transform(re.baseAddress!, im.baseAddress!)
        let scale = 1 / Float(fftN)

        let minLag = max(2, ktToInt(Float(sampleRate) / max(maxHz, 1)))
        // Two whole periods have to fit, or the "match" is against nothing.
        let maxLag = min(n / 2, ktToInt(Float(sampleRate) / max(minHz, 1)))
        if maxLag <= minLag + 2 { return -1.0 }

        let total = prefix[n]
        for tau in (minLag - 1)...(maxLag + 1) {
            let m = prefix[n - tau] + total - prefix[tau]
            nsdf[tau] = m <= 1e-12 ? 0 : (2 * re[tau] * scale / Float(m))
        }

        var globalMax: Float = 0
        for tau in minLag..<maxLag where nsdf[tau] > globalMax { globalMax = nsdf[tau] }
        if globalMax <= 0 { return -1.0 }
        let threshold = globalMax * PitchDetector.keyMaxRatio

        for tau in minLag..<maxLag {
            let v = nsdf[tau]
            if v < threshold { continue }
            if v < nsdf[tau - 1] || v < nsdf[tau + 1] { continue }
            // Parabolic interpolation: without it the period is quantised to
            // whole samples, which at 55 Hz is enough to slide the wave visibly.
            let a = nsdf[tau - 1]
            let c = nsdf[tau + 1]
            let denom = a - 2 * v + c
            let delta: Float = abs(denom) < 1e-9 ? 0 : 0.5 * (a - c) / denom
            clarity = coerceIn(v - 0.25 * (a - c) * delta, 0, 1)
            return Double(tau) + Double(coerceIn(delta, -1, 1))
        }
        return -1.0
    }

    /// The same, on an array.
    public func detect(_ x: [Float], minHz: Float, maxHz: Float) -> Double {
        x.withUnsafeBufferPointer { detect($0, minHz: minHz, maxHz: maxHz) }
    }

    private static let keyMaxRatio: Float = 0.9
}
