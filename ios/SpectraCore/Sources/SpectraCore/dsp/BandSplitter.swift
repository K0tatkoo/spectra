import Foundation

/// Independent per-band level meters built from real filters in the time domain,
/// not from summing FFT bins.
///
/// Summing bins is cheaper and is what most phone "spectrum" apps do, but it
/// inherits the transform's time resolution: with a 4096-point window at 48 kHz
/// every band reading is smeared over 85 ms, which is longer than the whole
/// attack of an 808. A cascaded Butterworth band-pass per band gives sample-rate
/// envelope resolution, so the sub meter actually snaps.
///
/// Cost is ~4 biquads per band per channel per sample: 7 bands × 2 ch × 4 at
/// 48 kHz is about 2.7 M biquad evaluations per second, which is nothing on this
/// class of device and buys a meter you can trust on transients.
///
/// Not thread safe; the analysis thread owns it. The band histories it hands out
/// in `readings()` are the shared, lock-free `ScrollBuffer`s.
public final class BandSplitter {

    private final class Chain {
        let highPass: [Biquad]
        let lowPass: [Biquad]

        init(highPass: [Biquad], lowPass: [Biquad]) {
            self.highPass = highPass
            self.lowPass = lowPass
        }

        @inline(__always)
        func process(_ x: Double) -> Double {
            var v = x
            for b in highPass { v = b.process(v) }
            for b in lowPass { v = b.process(v) }
            return v
        }

        func reset() {
            highPass.forEach { $0.reset() }
            lowPass.forEach { $0.reset() }
        }
    }

    private final class Band {
        let def: BandDef
        let left: Chain
        let right: Chain
        let history: ScrollBuffer

        var sumSq = 0.0
        var count = 0
        var blockPeak = 0.0

        var rmsDb = PeakHold.floorDb
        var peakDb = PeakHold.floorDb
        var holdDb = PeakHold.floorDb
        var heldMs: Float = 0

        init(def: BandDef, left: Chain, right: Chain, history: ScrollBuffer) {
            self.def = def
            self.left = left
            self.right = right
            self.history = history
        }

        func resetAccumulator() {
            sumSq = 0
            count = 0
            blockPeak = 0
        }
    }

    private var bands: [Band] = []
    private let ballistics = Ballistics(frameIntervalSec: 0.016)
    private var holdMs: Float = 900
    private var fallDbPerSec: Float = 24
    private var historyLen: Int
    private var rate: Int

    public init(sampleRate: Int, settings: Settings, historyLength: Int) {
        historyLen = historyLength
        rate = sampleRate
        configure(settings, sampleRate: sampleRate)
    }

    /// Rebuilds the filter bank. Histories survive a rebuild when the band count
    /// is unchanged, so tweaking a crossover does not blank the graphs.
    public func configure(_ s: Settings, sampleRate: Int) {
        rate = sampleRate
        let nyquist = Double(sampleRate) / 2.0
        let qs = Biquad.butterworthQs(order: s.bandSlope.order)
        let previous = bands
        let historyLen = self.historyLen

        bands = s.bands.enumerated().map { index, def in
            let lowNeeded = def.lowHz > 1
            let highNeeded = Double(def.highHz) < nyquist * 0.98
            func chain() -> Chain {
                Chain(
                    highPass: lowNeeded
                        ? qs.map { Biquad.highPass(sampleRate: sampleRate, freq: Double(def.lowHz), q: $0) }
                        : [],
                    lowPass: highNeeded
                        ? qs.map { Biquad.lowPass(sampleRate: sampleRate, freq: min(Double(def.highHz), nyquist * 0.98), q: $0) }
                        : []
                )
            }
            let kept = index < previous.count && previous[index].history.capacity == historyLen
                ? previous[index].history : nil
            return Band(def: def, left: chain(), right: chain(), history: kept ?? ScrollBuffer(capacity: historyLen))
        }

        ballistics.attackMs = s.bandAttackMs
        ballistics.releaseMs = s.bandReleaseMs
        ballistics.setFrameInterval(Float(s.hopSize) / Float(sampleRate))
        holdMs = s.peakHoldMs
        fallDbPerSec = s.peakFallDbPerSec
    }

    /// Streams `frames` new samples through every band. Must be called once per
    /// sample-run with no gaps and no repeats — the filters are stateful, and
    /// feeding them overlapping blocks would double-count energy. `right` is nil
    /// for a mono source.
    public func process(_ left: UnsafePointer<Float>, _ right: UnsafePointer<Float>?, frames n: Int) {
        for band in bands {
            var sumSq = band.sumSq
            var peak = band.blockPeak
            let hp = band.left
            for i in 0..<n {
                let y = hp.process(Double(left[i]))
                sumSq += y * y
                let a = abs(y)
                if a > peak { peak = a }
            }
            if let right {
                let rp = band.right
                for i in 0..<n {
                    let y = rp.process(Double(right[i]))
                    sumSq += y * y
                    let a = abs(y)
                    if a > peak { peak = a }
                }
                band.count += n * 2
            } else {
                band.count += n
            }
            band.sumSq = sumSq
            band.blockPeak = peak
        }
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

    /// Folds the samples accumulated since the last call into the meters.
    public func tick(_ dtMs: Float) {
        for band in bands {
            if band.count == 0 { continue }
            let rms = (band.sumSq / Double(band.count)).squareRoot()
            let targetDb = BandSplitter.toDb(rms)
            band.rmsDb = ballistics.stepDt(band.rmsDb, targetDb, dtSec: dtMs / 1000)
            band.peakDb = BandSplitter.toDb(band.blockPeak)

            if band.peakDb >= band.holdDb {
                band.holdDb = band.peakDb
                band.heldMs = 0
            } else {
                band.heldMs += dtMs
                if band.heldMs > holdMs { band.holdDb -= fallDbPerSec * dtMs / 1000 }
                if band.holdDb < PeakHold.floorDb { band.holdDb = PeakHold.floorDb }
            }

            band.history.push(band.rmsDb)
            band.resetAccumulator()
        }
    }

    public func readings() -> [BandReading] {
        bands.map {
            BandReading(name: $0.def.name, lowHz: $0.def.lowHz, highHz: $0.def.highHz,
                        rmsDb: $0.rmsDb, peakDb: $0.peakDb, holdDb: $0.holdDb, history: $0.history)
        }
    }

    public func reset() {
        for b in bands {
            b.left.reset()
            b.right.reset()
            b.resetAccumulator()
            b.rmsDb = PeakHold.floorDb
            b.peakDb = PeakHold.floorDb
            b.holdDb = PeakHold.floorDb
            b.history.clear()
        }
    }

    private static func toDb(_ linear: Double) -> Float {
        if linear <= 1e-9 { return PeakHold.floorDb }
        let db = 20.0 * log10(linear)
        return db < Double(PeakHold.floorDb) ? PeakHold.floorDb : Float(db)
    }
}
