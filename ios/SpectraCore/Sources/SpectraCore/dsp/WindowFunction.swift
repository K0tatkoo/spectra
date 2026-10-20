import Foundation

/// Analysis windows, with the two normalisation constants that actually matter
/// computed from the generated samples rather than from a table of published
/// values — a table is one more thing that can silently disagree with the code
/// that generates the window.
///
///  - `coherentGain` (mean of the window) corrects the *amplitude* of a sinusoid
///    sitting exactly on a bin. Divide magnitudes by it and a full-scale sine
///    reads 0 dBFS whatever window is selected.
///  - `enbw` (equivalent noise bandwidth, in bins) corrects *power* summed across
///    bins, which is what a band or noise-floor measurement needs. Using coherent
///    gain there instead is the classic way to get band levels a few dB wrong.
public enum WindowFunction: String, CaseIterable, Codable, Sendable {
    /// No window. Only correct when the block is exactly periodic — otherwise leaks badly.
    case rectangular = "RECTANGULAR"
    /// The default: −31 dB sidelobes, 1.5-bin main lobe. What most analysers use.
    case hann = "HANN"
    case hamming = "HAMMING"
    /// −92 dB sidelobes. Use when looking for a quiet tone next to a loud one.
    case blackmanHarris = "BLACKMAN_HARRIS"
    /// Amplitude-accurate to ~0.01 dB regardless of where the tone falls between bins.
    case flatTop = "FLAT_TOP"

    public var label: String {
        switch self {
        case .rectangular: "Rectangular"
        case .hann: "Hann"
        case .hamming: "Hamming"
        case .blackmanHarris: "Blackman–Harris 4"
        case .flatTop: "Flat top"
        }
    }

    public var blurb: String {
        switch self {
        case .rectangular: "No leakage suppression. Highest resolution, worst sidelobes."
        case .hann: "The default. Good balance of resolution and leakage."
        case .hamming: "Lower first sidelobe than Hann, slower far-field rolloff."
        case .blackmanHarris: "−92 dB sidelobes. Best dynamic range, widest main lobe."
        case .flatTop: "Amplitude-accurate for level calibration. Poor frequency resolution."
        }
    }

    /// Builds the window and its normalisation constants for a block of `n` samples.
    public func build(_ n: Int) -> Windowed {
        var w = [Float](repeating: 0, count: n)
        // Periodic (÷n) rather than symmetric (÷(n−1)): the periodic form is the
        // correct one for spectral analysis of a continuous stream.
        for i in 0..<n {
            let x = 2.0 * Double.pi * Double(i) / Double(n)
            let v: Double = switch self {
            case .rectangular: 1.0
            case .hann: 0.5 - 0.5 * cos(x)
            case .hamming: 0.54 - 0.46 * cos(x)
            case .blackmanHarris:
                0.35875 - 0.48829 * cos(x) + 0.14128 * cos(2 * x) - 0.01168 * cos(3 * x)
            case .flatTop:
                0.21557895 - 0.41663158 * cos(x) + 0.277263158 * cos(2 * x) -
                    0.083578947 * cos(3 * x) + 0.006947368 * cos(4 * x)
            }
            w[i] = Float(v)
        }

        var sum = 0.0
        var sumSq = 0.0
        for v in w {
            sum += Double(v)
            sumSq += Double(v) * Double(v)
        }
        let coherentGain = Float(sum / Double(n))
        let enbw = Float(Double(n) * sumSq / (sum * sum))
        return Windowed(samples: w, coherentGain: coherentGain, enbw: enbw, sumSquares: Float(sumSq))
    }

    public struct Windowed: Sendable {
        public let samples: [Float]
        public let coherentGain: Float
        public let enbw: Float
        /// Σw², the denominator of a power-spectral-density normalisation.
        public let sumSquares: Float
    }
}
