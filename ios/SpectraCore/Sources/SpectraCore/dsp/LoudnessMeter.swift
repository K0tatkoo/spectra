import Foundation

/// ITU-R BS.1770-4 loudness: momentary (400 ms), short-term (3 s), gated
/// integrated, loudness range, and inter-sample true peak.
///
/// Structure follows the standard exactly:
///  - each channel is K-weighted (a +4 dB shelf at 1.68 kHz followed by a 38 Hz
///    high-pass), coefficients re-derived for the actual sample rate;
///  - energy is accumulated into 100 ms grains, because every window the standard
///    defines (400 ms momentary, 3 s short-term, both with 75 % overlap) is a
///    whole number of them;
///  - integrated loudness is gated twice, first at an absolute −70 LUFS and then
///    at 10 LU below the mean of what survived.
///
/// Integrated loudness and LRA are accumulated into histograms rather than a
/// growing list of blocks. That is what libebur128 does, and it is why this can
/// run for hours without its memory creeping.
///
/// Not thread safe; the analysis thread owns it.
public final class LoudnessMeter {

    private var rate: Int
    private var shelfL: Biquad
    private var hpL: Biquad
    private var shelfR: Biquad
    private var hpR: Biquad

    private var grainSamples: Int
    private var grainAcc = 0.0
    private var grainCount = 0

    /// Ring of the last 30 grains (3 s) of Σ_ch G·meanSquare.
    private var grains = [Double](repeating: 0, count: LoudnessMeter.grainsShort)
    private var grainHead = 0
    private var grainsFilled = 0

    private var integratedHist = [Int](repeating: 0, count: LoudnessMeter.histBins)
    private var rangeHist = [Int](repeating: 0, count: LoudnessMeter.histBins)
    private var integratedSeconds: Float = 0

    private var truePeak: TruePeak?
    private var truePeakR: TruePeak?
    private var maxTruePeak: Float = 0

    public private(set) var enabled = true

    public init(sampleRate: Int, settings: Settings) {
        rate = sampleRate
        shelfL = .kWeightingShelf(sampleRate: sampleRate)
        hpL = .kWeightingHighPass(sampleRate: sampleRate)
        shelfR = .kWeightingShelf(sampleRate: sampleRate)
        hpR = .kWeightingHighPass(sampleRate: sampleRate)
        grainSamples = sampleRate / 10
        configure(settings, sampleRate: sampleRate)
    }

    public func configure(_ s: Settings, sampleRate: Int) {
        enabled = s.loudnessEnabled
        if sampleRate != rate {
            rate = sampleRate
            shelfL = .kWeightingShelf(sampleRate: sampleRate)
            hpL = .kWeightingHighPass(sampleRate: sampleRate)
            shelfR = .kWeightingShelf(sampleRate: sampleRate)
            hpR = .kWeightingHighPass(sampleRate: sampleRate)
            grainSamples = sampleRate / 10
            reset()
        }
        if s.truePeakEnabled {
            let factor = coerceIn(s.truePeakOversample, 2, 8)
            if truePeak?.factor != factor {
                truePeak = TruePeak(factor: factor)
                truePeakR = TruePeak(factor: factor)
            }
        } else {
            truePeak = nil
            truePeakR = nil
        }
    }

    /// `right` is nil for a mono source.
    public func process(_ left: UnsafePointer<Float>, _ right: UnsafePointer<Float>?, frames n: Int) {
        if !enabled { return }
        let tp = truePeak
        let tpr = truePeakR
        let shelfL = self.shelfL, hpL = self.hpL, shelfR = self.shelfR, hpR = self.hpR
        let grainSamples = self.grainSamples
        var grainAcc = self.grainAcc
        var grainCount = self.grainCount
        var maxTruePeak = self.maxTruePeak

        for i in 0..<n {
            let l = left[i]
            let kl = hpL.process(shelfL.process(Double(l)))
            var energy = kl * kl
            if let right {
                let r = right[i]
                let kr = hpR.process(shelfR.process(Double(r)))
                energy += kr * kr
                if let tpr {
                    let p = tpr.process(r)
                    if p > maxTruePeak { maxTruePeak = p }
                }
            }
            if let tp {
                let p = tp.process(l)
                if p > maxTruePeak { maxTruePeak = p }
            }

            grainAcc += energy
            grainCount += 1
            if grainCount >= grainSamples {
                pushGrain(grainAcc / Double(grainSamples))
                grainAcc = 0
                grainCount = 0
            }
        }

        self.grainAcc = grainAcc
        self.grainCount = grainCount
        self.maxTruePeak = maxTruePeak
    }

    /// The same for arrays, mainly for tests.
    public func process(_ left: [Float], _ right: [Float]?, frames: Int) {
        precondition(left.count >= frames && (right?.count ?? frames) >= frames)
        if frames <= 0 { return }
        left.withUnsafeBufferPointer { l in
            if let right {
                right.withUnsafeBufferPointer { r in process(l.baseAddress!, r.baseAddress!, frames: frames) }
            } else {
                process(l.baseAddress!, nil, frames: frames)
            }
        }
    }

    private func pushGrain(_ meanSquare: Double) {
        grains[grainHead] = meanSquare
        grainHead = (grainHead + 1) % LoudnessMeter.grainsShort
        if grainsFilled < LoudnessMeter.grainsShort { grainsFilled += 1 }
        integratedSeconds += 0.1

        // A 400 ms block becomes available every 100 ms — that is the 75 %
        // overlap the standard asks for, expressed as "one block per grain".
        if grainsFilled >= LoudnessMeter.grainsMomentary {
            let z = meanOfLastGrains(LoudnessMeter.grainsMomentary)
            LoudnessMeter.addToHistogram(&integratedHist, LoudnessMeter.loudnessOf(z))
        }
        if grainsFilled >= LoudnessMeter.grainsShort {
            let z = meanOfLastGrains(LoudnessMeter.grainsShort)
            LoudnessMeter.addToHistogram(&rangeHist, LoudnessMeter.loudnessOf(z))
        }
    }

    private func meanOfLastGrains(_ count: Int) -> Double {
        var sum = 0.0
        var idx = grainHead - 1
        for _ in 0..<count {
            if idx < 0 { idx += LoudnessMeter.grainsShort }
            sum += grains[idx]
            idx -= 1
        }
        return sum / Double(count)
    }

    public func read() -> LoudnessReading {
        if !enabled { return .empty }
        let momentary: Float = grainsFilled >= LoudnessMeter.grainsMomentary
            ? Float(LoudnessMeter.loudnessOf(meanOfLastGrains(LoudnessMeter.grainsMomentary))) : LoudnessReading.silent
        let shortTerm: Float = grainsFilled >= LoudnessMeter.grainsShort
            ? Float(LoudnessMeter.loudnessOf(meanOfLastGrains(LoudnessMeter.grainsShort))) : LoudnessReading.silent
        let tpDb: Float = maxTruePeak <= 0 ? -144 : Float(20.0 * log10(Double(maxTruePeak)))
        return LoudnessReading(
            momentary: momentary,
            shortTerm: shortTerm,
            integrated: gatedIntegrated(),
            range: loudnessRange(),
            truePeakDb: tpDb,
            integratedSeconds: integratedSeconds
        )
    }

    public func reset() {
        grains.withUnsafeMutableBufferPointer { $0.update(repeating: 0) }
        grainHead = 0
        grainsFilled = 0
        grainAcc = 0
        grainCount = 0
        integratedHist.withUnsafeMutableBufferPointer { $0.update(repeating: 0) }
        rangeHist.withUnsafeMutableBufferPointer { $0.update(repeating: 0) }
        integratedSeconds = 0
        maxTruePeak = 0
        shelfL.reset(); hpL.reset(); shelfR.reset(); hpR.reset()
        truePeak?.reset(); truePeakR?.reset()
    }

    // ---- gating ------------------------------------------------------------

    /// Two-pass gate. The relative threshold is derived from the mean energy of
    /// everything above the absolute gate, then applied to the same histogram —
    /// which is why the histogram has to hold energy, not loudness, when it is
    /// averaged.
    private func gatedIntegrated() -> Float {
        guard let firstPass = LoudnessMeter.histogramMeanEnergy(integratedHist, 0) else { return LoudnessReading.silent }
        let relativeLufs = Float(LoudnessMeter.loudnessOf(firstPass) - LoudnessMeter.relativeGateLu)
        let fromBin = LoudnessMeter.binOf(relativeLufs) + 1
        guard let second = LoudnessMeter.histogramMeanEnergy(integratedHist, fromBin) else { return LoudnessReading.silent }
        return Float(LoudnessMeter.loudnessOf(second))
    }

    /// LRA: the 10th-to-95th percentile spread of short-term blocks, gated at −20 LU.
    private func loudnessRange() -> Float {
        guard let mean = LoudnessMeter.histogramMeanEnergy(rangeHist, 0) else { return 0 }
        let relative = Float(LoudnessMeter.loudnessOf(mean) - LoudnessMeter.lraGateLu)
        let from = LoudnessMeter.binOf(relative) + 1
        var total = 0
        for i in from..<LoudnessMeter.histBins { total += rangeHist[i] }
        if total < 10 { return 0 }

        // Kotlin's Double.toLong() truncates toward zero, as Int(Double) does.
        let lowTarget = Int(Double(total) * 0.10)
        let highTarget = Int(Double(total) * 0.95)
        var running = 0
        var low = Float.nan
        var high = Float.nan
        for i in from..<LoudnessMeter.histBins {
            running += rangeHist[i]
            if low.isNaN && running >= lowTarget { low = LoudnessMeter.binLoudness(i) }
            if running >= highTarget { high = LoudnessMeter.binLoudness(i); break }
        }
        if low.isNaN || high.isNaN { return 0 }
        return max(high - low, 0)
    }

    private static func histogramMeanEnergy(_ hist: [Int], _ fromBin: Int) -> Double? {
        var count = 0
        var energy = 0.0
        for i in max(fromBin, 0)..<histBins {
            let c = hist[i]
            if c == 0 { continue }
            count += c
            energy += Double(c) * binEnergy[i]
        }
        return count == 0 ? nil : energy / Double(count)
    }

    private static func addToHistogram(_ hist: inout [Int], _ loudness: Double) {
        if loudness <= absoluteGateLufs { return }
        let idx = binOf(Float(loudness))
        if idx >= 0 && idx < histBins { hist[idx] += 1 }
    }

    private static func binOf(_ loudness: Float) -> Int {
        coerceIn(ktToInt((loudness - histMinLufs) / histStepLu), -1, histBins - 1)
    }

    private static func binLoudness(_ i: Int) -> Float { histMinLufs + (Float(i) + 0.5) * histStepLu }

    private static func energyOfBin(_ i: Int) -> Double { pow(10.0, Double(binLoudness(i) + 0.691) / 10.0) }

    /// `energyOfBin` for every bin, computed once. The Kotlin calls `Math.pow`
    /// for each non-empty bin on every read; the numbers are the same.
    private static let binEnergy: [Double] = (0..<histBins).map(energyOfBin)

    private static func loudnessOf(_ meanSquare: Double) -> Double {
        meanSquare <= 1e-15 ? -200.0 : -0.691 + 10.0 * log10(meanSquare)
    }

    private static let grainsMomentary = 4   // 400 ms
    private static let grainsShort = 30      // 3 s
    private static let absoluteGateLufs = -70.0
    private static let relativeGateLu = 10.0
    private static let lraGateLu = 20.0
    private static let histMinLufs: Float = -70
    private static let histStepLu: Float = 0.075  // 1000 bins covering −70..+5 LUFS
    private static let histBins = 1000
}

/// Polyphase inter-sample peak detector.
///
/// The filter is generated from a windowed sinc at construction rather than
/// copied from a published coefficient table: a table is a hundred numbers that
/// cannot be verified by reading them, and a single transposed digit shows up as
/// a true-peak reading that is wrong by a fraction of a dB — small enough to
/// never be noticed and large enough to matter when you are checking a master
/// against −1 dBTP.
///
/// Not thread safe; the loudness meter owns one per channel. Its filter and
/// history are raw memory because `process` runs twice per stereo frame.
public final class TruePeak {
    public let factor: Int
    private let tapsPerPhase: Int

    /// `factor` phases of `tapsPerPhase` taps, phase after phase.
    private let phases: UnsafeMutableBufferPointer<Float>
    private let history: UnsafeMutableBufferPointer<Float>
    private var head = 0

    public init(factor: Int, tapsPerPhase: Int = 12) {
        self.factor = factor
        self.tapsPerPhase = tapsPerPhase
        let length = factor * tapsPerPhase
        var h = [Double](repeating: 0, count: length)
        let centre = Double(length - 1) / 2.0
        for n in 0..<length {
            let x = (Double(n) - centre) / Double(factor)
            let sinc = abs(x) < 1e-9 ? 1.0 : sin(Double.pi * x) / (Double.pi * x)
            // Blackman window: −58 dB sidelobes, which is well below the
            // quantisation of anything this measurement is compared against.
            let w = 0.42 - 0.5 * cos(2.0 * Double.pi * Double(n) / Double(length - 1))
                + 0.08 * cos(4.0 * Double.pi * Double(n) / Double(length - 1))
            h[n] = sinc * w
        }
        phases = .allocate(capacity: length)
        phases.initialize(repeating: 0)
        for p in 0..<factor {
            let taps = (0..<tapsPerPhase).map { m in h[m * factor + p] }
            var sum = 0.0
            for t in taps { sum += t }
            // Normalising each phase to unity DC gain is what keeps a constant
            // input from reading as a peak above itself.
            for m in 0..<tapsPerPhase {
                phases[p * tapsPerPhase + m] = Float(sum != 0.0 ? taps[m] / sum : taps[m])
            }
        }
        history = .allocate(capacity: tapsPerPhase)
        history.initialize(repeating: 0)
    }

    deinit {
        phases.deallocate()
        history.deallocate()
    }

    /// Feeds one sample and returns the largest absolute value in its neighbourhood.
    ///
    /// The loops are `while` rather than `for … in 0..<n` only because a debug
    /// build steps a range through an unspecialised iterator call per tap, which
    /// made this 96-multiply loop the slowest thing in `swift test`. Same taps,
    /// same order, same sums as the Kotlin.
    @inline(__always)
    public func process(_ x: Float) -> Float {
        let taps = tapsPerPhase
        history[head] = x
        head = (head + 1) % taps
        var maxAbs = abs(x)
        var phase = UnsafePointer(phases.baseAddress!)
        let hist = history.baseAddress!
        var p = 0
        while p < factor {
            var acc: Float = 0
            var idx = head - 1
            var m = 0
            while m < taps {
                if idx < 0 { idx += taps }
                acc += phase[m] * hist[idx]
                idx -= 1
                m += 1
            }
            let a = abs(acc)
            if a > maxAbs { maxAbs = a }
            phase += taps
            p += 1
        }
        return maxAbs
    }

    public func reset() {
        history.update(repeating: 0)
        head = 0
    }
}
