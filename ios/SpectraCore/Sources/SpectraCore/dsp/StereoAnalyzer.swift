import Foundation

/// Phase correlation, stereo width and the goniometer cloud.
///
/// Correlation is computed from exponentially-decaying sums rather than from a
/// hard sliding window, so the reading settles rather than jumping when a
/// boundary sample falls out of the window — that flicker is what makes most
/// phone correlation meters unreadable.
///
/// The goniometer axes are the usual 45° rotation: a mono signal (L = R) lands on
/// the vertical, an out-of-phase signal on the horizontal.
///
/// Not thread safe; the analysis thread owns it.
public final class StereoAnalyzer {

    private let cloudPoints: Int
    private var rate: Int
    private var decay = 0.0

    private var sumLL = 0.0
    private var sumRR = 0.0
    private var sumLR = 0.0
    private var sumMM = 0.0
    private var sumSS = 0.0

    /// Interleaved x,y pairs; overwritten in place every block.
    public private(set) var cloud: [Float]
    private var cloudCount = 0

    public private(set) var correlation: Float = 0
    public private(set) var width: Float = 0
    public private(set) var rmsL: Float = 0
    public private(set) var rmsR: Float = 0
    public private(set) var peakL: Float = 0
    public private(set) var peakR: Float = 0

    public init(sampleRate: Int, settings: Settings, cloudPoints: Int = 720) {
        self.cloudPoints = cloudPoints
        rate = sampleRate
        cloud = [Float](repeating: 0, count: cloudPoints * 2)
        configure(settings, sampleRate: sampleRate)
    }

    public func configure(_ s: Settings, sampleRate: Int) {
        rate = sampleRate
        let tau = max(Double(s.correlationWindowMs) / 1000.0, 0.005)
        // Per-sample decay for a first-order average with time constant tau.
        decay = exp(-1.0 / (tau * Double(sampleRate)))
    }

    /// `right` is nil for a mono source, which then reads as perfectly correlated.
    public func process(_ left: UnsafePointer<Float>, _ right: UnsafePointer<Float>?, frames n: Int) {
        if n <= 0 { return }

        var pl: Float = 0
        var pr: Float = 0
        var ll = sumLL
        var rr = sumRR
        var lr = sumLR
        var mm = sumMM
        var ss = sumSS
        let d = decay
        // Kotlin reads `right?.get(i) ?: left[i]`: the left channel stands in for a missing right.
        let rp = right ?? left

        for i in 0..<n {
            let l = Double(left[i])
            let r = Double(rp[i])
            ll = ll * d + l * l
            rr = rr * d + r * r
            lr = lr * d + l * r
            let mid = (l + r) * 0.5
            let side = (l - r) * 0.5
            mm = mm * d + mid * mid
            ss = ss * d + side * side

            let al = abs(left[i]); if al > pl { pl = al }
            let ar = abs(rp[i]); if ar > pr { pr = ar }
        }

        sumLL = ll; sumRR = rr; sumLR = lr; sumMM = mm; sumSS = ss

        let denom = (ll * rr).squareRoot()
        correlation = denom < 1e-12 ? 1 : coerceIn(Float(lr / denom), -1, 1)

        let midRms = mm.squareRoot()
        let sideRms = ss.squareRoot()
        width = midRms + sideRms < 1e-12 ? 0 : coerceIn(Float(sideRms / (midRms + 1e-12)), 0, 4)

        // Scale factor: these sums are decaying, so their steady-state value for
        // a signal of mean square m is m/(1−d). Undo that to get a real RMS.
        let norm = max(1.0 - d, 1e-9)
        rmsL = Float((ll * norm).squareRoot())
        rmsR = Float((rr * norm).squareRoot())
        peakL = pl
        peakR = pr

        fillCloud(left, right, n)
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

    /// Decimates the block down to `cloudPoints` dots. Older dots are kept and
    /// faded by the painter rather than being erased, which is what gives a
    /// goniometer its characteristic smear on transients.
    private func fillCloud(_ left: UnsafePointer<Float>, _ right: UnsafePointer<Float>?, _ n: Int) {
        guard let right else {
            cloudCount = 0
            return
        }
        let take = min(cloudPoints, n)
        let stride = max(Float(n) / Float(take), 1)
        cloud.withUnsafeMutableBufferPointer { cloud in
            var w = 0
            var pos: Float = 0
            while w < take {
                let i = coerceIn(ktToInt(pos), 0, n - 1)
                let l = left[i]
                let r = right[i]
                cloud[w * 2] = (r - l) * StereoAnalyzer.sqrt1_2
                cloud[w * 2 + 1] = (r + l) * StereoAnalyzer.sqrt1_2
                pos += stride
                w += 1
            }
        }
        cloudCount = take
    }

    public func cloudSize() -> Int { cloudCount }

    public func snapshotCloud() -> [Float] {
        cloudCount == 0 ? [] : Array(cloud[0 ..< cloudCount * 2])
    }

    public func reset() {
        sumLL = 0; sumRR = 0; sumLR = 0; sumMM = 0; sumSS = 0
        correlation = 0; width = 0
        cloudCount = 0
    }

    private static let sqrt1_2: Float = 0.70710677

    public static func toDb(_ linear: Float) -> Float {
        if linear <= 1e-7 { return PeakHold.floorDb }
        // Kotlin's log10(Float) is Math.log10 in Double, rounded to Float.
        let db = 20 * Float(log10(Double(linear)))
        return db < PeakHold.floorDb ? PeakHold.floorDb : db
    }
}
