import Accelerate

/// Forward complex FFT, in place, X[k] = Σ x[n]·e^(-j2πkn/N) — the same
/// transform and the same (unscaled) scaling as Fft.kt.
///
/// Fft.kt is a hand-written radix-2 Cooley–Tukey; this one hands the same
/// transform to vDSP, which computes it with the same sign convention and no
/// scaling, and does it several times faster. The sign is worth spelling out
/// because getting it backwards is invisible in a magnitude plot and only shows
/// up later, in the phase and in anything that inverse-transforms — the
/// fixture test compares real and imaginary parts, not just magnitudes.
///
/// One instance is bound to one size and is NOT thread safe: the analysis
/// thread owns it exclusively.
public final class Fft {
    public let size: Int
    private let log2n: vDSP_Length
    private let setup: FFTSetup

    public init(size: Int) {
        precondition(size >= 2 && size & (size - 1) == 0, "FFT size must be a power of two, was \(size)")
        self.size = size
        log2n = vDSP_Length(size.trailingZeroBitCount)
        guard let s = vDSP_create_fftsetup(log2n, FFTRadix(kFFTRadix2)) else {
            fatalError("vDSP could not set up a \(size)-point FFT")
        }
        setup = s
    }

    deinit { vDSP_destroy_fftsetup(setup) }

    /// Transforms `re`/`im` in place. Both arrays must be `size` long.
    public func transform(_ re: inout [Float], _ im: inout [Float]) {
        precondition(re.count == size && im.count == size)
        re.withUnsafeMutableBufferPointer { r in
            im.withUnsafeMutableBufferPointer { i in
                transform(r.baseAddress!, i.baseAddress!)
            }
        }
    }

    /// The same, on raw buffers of `size` floats each.
    public func transform(_ re: UnsafeMutablePointer<Float>, _ im: UnsafeMutablePointer<Float>) {
        var split = DSPSplitComplex(realp: re, imagp: im)
        vDSP_fft_zip(setup, &split, 1, log2n, FFTDirection(kFFTDirection_Forward))
    }
}
