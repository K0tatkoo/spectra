import Foundation

/// Windowed STFT magnitude spectrum with display corrections and ballistics.
///
/// Normalisation is chosen so a full-scale sine sitting on a bin reads exactly
/// 0 dBFS with any window: magnitude is divided by N and by the window's coherent
/// gain, and doubled for every bin except DC and Nyquist (which have no mirror
/// to fold in). Everything downstream — grid labels, band readouts, the
/// spectrogram range — assumes that reference.
///
/// Not thread safe; the analysis thread owns it. `smoothedDb` and `peaks.values`
/// are value arrays: handing one to an `AnalysisFrame` shares its storage until
/// the next `process`, which then writes into a fresh copy — the same one copy
/// per frame the Kotlin makes with `copyOf()`.
public final class SpectrumAnalyzer {

    public private(set) var sampleRate: Int
    public private(set) var fftSize: Int

    private var fft: Fft
    private var win: WindowFunction.Windowed

    private var re: [Float]
    private var im: [Float]

    /// Raw, corrected magnitudes for the newest block.
    public private(set) var rawDb: [Float]
    /// Ballistically smoothed magnitudes — what actually gets drawn.
    public private(set) var smoothedDb: [Float]

    /// Per-bin weighting + tilt, precomputed because it never changes per frame.
    private var correctionDb: [Float]

    private let ballistics = Ballistics(frameIntervalSec: 0.016)
    public private(set) var peaks: PeakHold

    /// For each spectrogram row, the half-open bin range that feeds it.
    private var rowBinStart: [Int]
    private var rowBinEnd: [Int]
    private let rows: Int

    public init(sampleRate: Int, settings: Settings, spectrogramRows: Int) {
        self.sampleRate = sampleRate
        fftSize = settings.fftSize
        fft = Fft(size: settings.fftSize)
        win = settings.window.build(settings.fftSize)
        re = [Float](repeating: 0, count: settings.fftSize)
        im = [Float](repeating: 0, count: settings.fftSize)
        let bins = settings.fftSize / 2 + 1
        rawDb = [Float](repeating: PeakHold.floorDb, count: bins)
        smoothedDb = [Float](repeating: PeakHold.floorDb, count: bins)
        correctionDb = [Float](repeating: 0, count: bins)
        peaks = PeakHold(size: bins)
        rowBinStart = [Int](repeating: 0, count: spectrogramRows)
        rowBinEnd = [Int](repeating: 0, count: spectrogramRows)
        rows = spectrogramRows
        configure(settings, rate: sampleRate)
    }

    /// Applies a settings change. Reallocates only when the transform size or the
    /// sample rate actually changed, so dragging a ballistics slider is free.
    public func configure(_ s: Settings, rate: Int) {
        let sizeChanged = s.fftSize != fftSize || rate != sampleRate
        sampleRate = rate

        if sizeChanged {
            fftSize = s.fftSize
            fft = Fft(size: fftSize)
            re = [Float](repeating: 0, count: fftSize)
            im = [Float](repeating: 0, count: fftSize)
            let bins = fftSize / 2 + 1
            rawDb = [Float](repeating: PeakHold.floorDb, count: bins)
            smoothedDb = [Float](repeating: PeakHold.floorDb, count: bins)
            correctionDb = [Float](repeating: 0, count: bins)
            peaks = peaks.resize(bins)
        }
        win = s.window.build(fftSize)

        ballistics.attackMs = s.attackMs
        ballistics.releaseMs = s.releaseMs
        ballistics.setFrameInterval(Float(s.hopSize) / Float(sampleRate))

        peaks.enabled = s.peakHold
        peaks.holdMs = s.peakHoldMs
        peaks.fallDbPerSec = s.peakFallDbPerSec

        let binHz = Float(sampleRate) / Float(fftSize)
        for k in correctionDb.indices {
            let f = Float(k) * binHz
            var c = SpectrumAnalyzer.weightingDb(s.weighting, f)
            if s.tiltDbPerOct != 0 && f > 0 {
                // Kotlin's ln(Float) is the Double log rounded to Float, then
                // divided by a Double LN2: the same steps here.
                c += s.tiltDbPerOct * Float(Double(Float(log(Double(f / 1000)))) / SpectrumAnalyzer.ln2)
            }
            correctionDb[k] = c
        }

        buildSpectrogramRows(s)
    }

    private func buildSpectrogramRows(_ s: Settings) {
        let binHz = Float(sampleRate) / Float(fftSize)
        let nyquistBin = fftSize / 2
        let lo = max(s.minHz, 1)
        let hi = min(s.maxHz, Float(sampleRate) / 2)
        for r in 0..<rows {
            // Row 0 is the bottom of the picture and the bottom of the range.
            let t0 = Float(r) / Float(rows)
            let t1 = Float(r + 1) / Float(rows)
            let f0: Float
            let f1: Float
            if s.freqScale == .log {
                // Kotlin's Float.pow is Math.pow in Double, rounded to Float.
                f0 = lo * Float(pow(Double(hi / lo), Double(t0)))
                f1 = lo * Float(pow(Double(hi / lo), Double(t1)))
            } else {
                f0 = lo + (hi - lo) * t0
                f1 = lo + (hi - lo) * t1
            }
            var b0 = coerceIn(ktToInt(f0 / binHz), 0, nyquistBin)
            var b1 = coerceIn(ktToInt(f1 / binHz), 0, nyquistBin)
            // At the bottom of a log scale many rows land inside one bin; give
            // each row at least that bin so the picture has no dead stripes.
            if b1 <= b0 { b1 = b0 + 1 }
            if b1 > nyquistBin { b1 = nyquistBin + 1; b0 = max(b1 - 1, 0) }
            rowBinStart[r] = b0
            rowBinEnd[r] = b1
        }
    }

    /// Runs one transform over `block` (already windowed-length, oldest sample
    /// first) and advances the ballistics by `dtMs`.
    public func process(_ block: [Float], dtMs: Float) {
        precondition(block.count >= fftSize)
        block.withUnsafeBufferPointer { process($0.baseAddress!, dtMs: dtMs) }
    }

    /// The same on a raw buffer of at least `fftSize` floats.
    public func process(_ block: UnsafePointer<Float>, dtMs: Float) {
        let n = fftSize
        let bins = n / 2
        let norm = 1 / (Float(n) * win.coherentGain)
        let floor = PeakHold.floorDb
        let fft = self.fft
        win.samples.withUnsafeBufferPointer { w in
            re.withUnsafeMutableBufferPointer { re in
                im.withUnsafeMutableBufferPointer { im in
                    for i in 0..<n {
                        re[i] = block[i] * w[i]
                        im[i] = 0
                    }
                    fft.transform(re.baseAddress!, im.baseAddress!)

                    correctionDb.withUnsafeBufferPointer { corr in
                        rawDb.withUnsafeMutableBufferPointer { raw in
                            for k in 0...bins {
                                let mag = (re[k] * re[k] + im[k] * im[k]).squareRoot()
                                let scaled = (k == 0 || k == bins) ? mag * norm : 2 * mag * norm
                                // Kotlin's log10(Float) is Math.log10 in Double,
                                // rounded to Float; doing the same keeps the bins
                                // on the Kotlin's numbers rather than an ulp off.
                                let db = 20 * Float(log10(Double(scaled + 1e-12))) + corr[k]
                                raw[k] = db < floor ? floor : db
                            }
                        }
                    }
                }
            }
        }

        rawDb.withUnsafeBufferPointer { raw in
            smoothedDb.withUnsafeMutableBufferPointer { sm in
                ballistics.stepAll(sm.baseAddress!, raw.baseAddress!, count: sm.count)
                peaks.update(sm.baseAddress!, dtMs: dtMs)
            }
        }
    }

    /// Quantises the current spectrum into one spectrogram column, bottom row
    /// first. Rows that span several bins take the maximum, not the mean: a
    /// narrow resonance that averages away is exactly what the user is looking
    /// for. `out` is Kotlin's ByteArray, bit for bit.
    public func fillSpectrogramColumn(_ out: inout [UInt8], floorDb: Float, ceilDb: Float) {
        precondition(out.count >= rows)
        let span = max(ceilDb - floorDb, 1)
        smoothedDb.withUnsafeBufferPointer { sm in
            out.withUnsafeMutableBufferPointer { out in
                for r in 0..<rows {
                    var peak = PeakHold.floorDb
                    var b = rowBinStart[r]
                    let end = rowBinEnd[r]
                    while b < end {
                        let v = sm[b]
                        if v > peak { peak = v }
                        b += 1
                    }
                    let t = coerceIn((peak - floorDb) / span, 0, 1)
                    out[r] = UInt8(truncatingIfNeeded: ktToInt(t * 255))
                }
            }
        }
    }

    private static let ln2 = 0.6931471805599453

    /// IEC 61672 A and C weighting from the analogue pole definitions. These
    /// are the response of the weighting network, not a table interpolation,
    /// so they stay correct at any bin spacing.
    public static func weightingDb(_ weighting: Weighting, _ f: Float) -> Float {
        if weighting == .z || f <= 0 { return 0 }
        let f2 = Double(f) * Double(f)
        let c1 = 20.598997 * 20.598997
        let c2 = 107.65265 * 107.65265
        let c3 = 737.86223 * 737.86223
        let c4 = 12194.217 * 12194.217
        switch weighting {
        case .a:
            let num = c4 * f2 * f2
            let den = (f2 + c1) * ((f2 + c2) * (f2 + c3)).squareRoot() * (f2 + c4)
            return Float(20.0 * log10(num / den) + 2.0)
        case .c:
            let num = c4 * f2
            let den = (f2 + c1) * (f2 + c4)
            return Float(20.0 * log10(num / den) + 0.06)
        case .z:
            return 0
        }
    }
}
