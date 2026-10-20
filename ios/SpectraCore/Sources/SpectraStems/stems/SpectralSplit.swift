import Accelerate
import SpectraCore

/// The network half of the synth splitter: band powers in, masks out, one frame
/// at a time, carrying whatever memory it has between calls. Kept apart from the
/// signal path so the tests can check that path against a stand-in whose answer
/// is known.
public protocol MaskNet: AnyObject {
    /// `power` holds each input's band powers — inputs × bands, in
    /// `SplitLayout.inputs` order — and `mask` receives, in the same layout, the
    /// share of each band that is synth, 0 to 1.
    func step(_ power: UnsafeBufferPointer<Float>, _ mask: UnsafeMutableBufferPointer<Float>) throws
}

/// A loaded splitter: what its frames look like, and the network that masks them.
/// Dropping the last reference releases the network (Kotlin's `close()`).
public final class SplitModel {
    public let layout: SplitLayout
    public let net: MaskNet

    public init(layout: SplitLayout, net: MaskNet) {
        self.layout = layout
        self.net = net
    }
}

/// The synth splitter's signal path, one frame at a time: each input stem is
/// windowed and transformed, the network is shown its band powers and answers
/// with a mask per band, and the masked spectra are transformed back and
/// overlap-added into the synth stem. What each input gives up to the synth is
/// subtracted from it, so the outputs add back up to the inputs exactly — the
/// same "nothing lost, nothing invented" rule the stem model follows.
///
/// A mask never lets more of a band through than was there. The worst a wrong
/// answer can do is put the right sound in the wrong lane.
///
/// No app types: the unit tests run this on the Mac, and it is the exact
/// arithmetic the training code (train/synthsplit/dsp.py) reproduces in batch.
/// The one difference from SpectralSplit.kt is the FFT, which `Fft` hands to
/// vDSP (see there): the same transform, rounded differently.
/// Not thread safe: the splitter worker owns it.
public final class SpectralSplit {
    public let layout: SplitLayout
    private let net: MaskNet

    private let n: Int
    private let hop: Int
    private let count: Int
    private let bins: Int
    private let bands: Int

    // Raw pointers rather than buffer pointers, and vDSP for the element-wise
    // steps: in a debug build every subscript and range step is a call, and
    // the splitter ran at a fortieth of its release speed. vDSP's multiply,
    // add and subtract round exactly like the Kotlin's scalar ones (no fused
    // multiply-add is used anywhere here), so the numbers are the same.
    private let fft: Fft
    private let analysis: UnsafeMutablePointer<Float>
    private let synthesis: UnsafeMutablePointer<Float>
    private let re: UnsafeMutablePointer<Float>
    private let im: UnsafeMutablePointer<Float>
    /// Each input's spectrum, `bins` per input.
    private let specRe: UnsafeMutablePointer<Float>
    private let specIm: UnsafeMutablePointer<Float>
    private let power: UnsafeMutablePointer<Float>
    private let mask: UnsafeMutablePointer<Float>
    private let bandOf: UnsafeMutablePointer<Int>
    private let bandWidth: UnsafeMutablePointer<Float>

    /// The second half of the last frame's synthesis, per input (`hop` each), waiting for the next frame's first half.
    private let pending: UnsafeMutablePointer<Float>
    /// What each input gives to the synth, final, for the hop this frame completes (`hop` each).
    private let part: UnsafeMutablePointer<Float>

    private static func zeros(_ count: Int) -> UnsafeMutablePointer<Float> {
        let p = UnsafeMutablePointer<Float>.allocate(capacity: count)
        p.initialize(repeating: 0, count: count)
        return p
    }

    public init(layout: SplitLayout, net: MaskNet) {
        self.layout = layout
        self.net = net
        n = layout.nFft
        hop = layout.hop
        count = layout.inputs.count
        bins = layout.bins
        bands = layout.bands

        fft = Fft(size: n)
        analysis = Self.zeros(n)
        analysis.update(from: layout.analysisWindow(), count: n)
        synthesis = Self.zeros(n)
        synthesis.update(from: layout.synthesisWindow(), count: n)
        re = Self.zeros(n)
        im = Self.zeros(n)
        specRe = Self.zeros(count * bins)
        specIm = Self.zeros(count * bins)
        power = Self.zeros(count * bands)
        mask = Self.zeros(count * bands)
        bandOf = .allocate(capacity: bins)
        bandOf.initialize(repeating: 0, count: bins)
        for band in 0..<bands {
            for bin in layout.bandEdges[band]..<layout.bandEdges[band + 1] { bandOf[bin] = band }
        }
        bandWidth = Self.zeros(bands)
        for b in 0..<bands { bandWidth[b] = Float(layout.bandEdges[b + 1] - layout.bandEdges[b]) }
        pending = Self.zeros(count * hop)
        part = Self.zeros(count * hop)
    }

    deinit {
        for p in [analysis, synthesis, re, im, specRe, specIm, power, mask, bandWidth, pending, part] { p.deallocate() }
        bandOf.deallocate()
    }

    /// Takes one frame — the `SplitLayout.nFft` newest samples of each input,
    /// oldest first, ending at a multiple of the hop — and completes the hop
    /// that lies two hops before its end: `synth` gets the synth there, and
    /// `rest` each input with its synth taken out, all `SplitLayout.hop` long.
    public func frame(_ frames: [UnsafeMutableBufferPointer<Float>], synth: UnsafeMutableBufferPointer<Float>,
                      rest: [UnsafeMutableBufferPointer<Float>]) throws {
        precondition(frames.count == count && rest.count == count && synth.count >= hop, "frame: wrong buffers")
        let n = self.n, hop = self.hop, count = self.count, bins = self.bins, bands = self.bands
        let re = self.re, im = self.im, analysis = self.analysis
        // Two real inputs share one complex transform, one as the real part and
        // one as the imaginary, and are told apart again by symmetry.
        var a = 0
        while a < count {
            let b = a + 1
            precondition(frames[a].count >= n)
            let xa = frames[a].baseAddress!
            vDSP_vmul(xa, 1, analysis, 1, re, 1, vDSP_Length(n))
            if b < count {
                precondition(frames[b].count >= n)
                let xb = frames[b].baseAddress!
                vDSP_vmul(xb, 1, analysis, 1, im, 1, vDSP_Length(n))
            } else {
                vDSP_vclr(im, 1, vDSP_Length(n))
            }
            fft.transform(re, im)
            unpack(a, b < count ? b : -1)
            a += 2
        }

        let power = self.power, specRe = self.specRe, specIm = self.specIm, bandOf = self.bandOf, bandWidth = self.bandWidth
        power.update(repeating: 0, count: count * bands)
        for input in 0..<count {
            let off = input * bands
            let sr = specRe + input * bins
            let si = specIm + input * bins
            for bin in 0..<bins { power[off + bandOf[bin]] += sr[bin] * sr[bin] + si[bin] * si[bin] }
            for band in 0..<bands { power[off + band] /= bandWidth[band] }
        }
        try net.step(UnsafeBufferPointer(start: power, count: count * bands),
                     UnsafeMutableBufferPointer(start: mask, count: count * bands))

        a = 0
        while a < count {
            let b = a + 1
            inverse(a, b < count ? b : -1)
            overlapAdd(a, re)
            if b < count { overlapAdd(b, im) }
            a += 2
        }

        let part = self.part
        let start = n - 2 * hop
        let out = synth.baseAddress!
        for i in 0..<hop {
            var s: Float = 0
            for input in 0..<count { s += part[input * hop + i] }
            out[i] = s
        }
        for input in 0..<count {
            let x = frames[input].baseAddress!
            precondition(rest[input].count >= hop)
            // r = x[start…] − part (vDSP_vsub subtracts its first operand from its second).
            vDSP_vsub(part + input * hop, 1, x + start, 1, rest[input].baseAddress!, 1, vDSP_Length(hop))
        }
    }

    /// Forgets the half-finished overlap from before a jump in the input. The
    /// network keeps its memory — what follows is nearly always the same song.
    public func resetOverlap() {
        pending.update(repeating: 0, count: count * hop)
    }

    /// Splits the packed transform in `re`/`im` into the spectra of inputs `a` and `b` (b < 0: none).
    private func unpack(_ a: Int, _ b: Int) {
        let n = self.n, re = self.re, im = self.im
        let ar = specRe + a * bins
        let ai = specIm + a * bins
        let br = b >= 0 ? specRe + b * bins : specRe
        let bi = b >= 0 ? specIm + b * bins : specIm
        for k in 0..<bins {
            let j = (n - k) & (n - 1)
            let zr = re[k]
            let zi = im[k]
            let cr = re[j]
            let ci = im[j]
            ar[k] = 0.5 * (zr + cr)
            ai[k] = 0.5 * (zi - ci)
            if b >= 0 {
                br[k] = 0.5 * (zi + ci)
                bi[k] = 0.5 * (cr - zr)
            }
        }
    }

    /// Masks inputs `a` and `b` and transforms both back at once: the real part
    /// of the result is `a`'s synth, the imaginary part `b`'s. Scaled by 1 / n.
    private func inverse(_ a: Int, _ b: Int) {
        let n = self.n, re = self.re, im = self.im, mask = self.mask, bandOf = self.bandOf
        let ar = specRe + a * bins
        let ai = specIm + a * bins
        let br = b >= 0 ? specRe + b * bins : specRe
        let bi = b >= 0 ? specIm + b * bins : specIm
        let maskA = mask + a * bands
        let maskB = b >= 0 ? mask + b * bands : mask
        for k in 0..<bins {
            let ma = maskA[bandOf[k]]
            let yar = ma * ar[k]
            let yai = ma * ai[k]
            var ybr: Float = 0
            var ybi: Float = 0
            if b >= 0 {
                let mb = maskB[bandOf[k]]
                ybr = mb * br[k]
                ybi = mb * bi[k]
            }
            // W = Ya + i·Yb, then its conjugate for the inverse by forward transform.
            re[k] = yar - ybi
            im[k] = -(yai + ybr)
            if k >= 1 && k < n / 2 {
                re[n - k] = yar + ybi
                im[n - k] = -(ybr - yai)
            }
        }
        fft.transform(re, im)
        var scale: Float = 1 / Float(n)
        var negScale = -scale
        vDSP_vsmul(re, 1, &scale, re, 1, vDSP_Length(n))
        vDSP_vsmul(im, 1, &negScale, im, 1, vDSP_Length(n))
    }

    /// Adds one input's windowed synthesis into its overlap, completing one hop of `part`.
    private func overlapAdd(_ input: Int, _ y: UnsafeMutablePointer<Float>) {
        let start = n - 2 * hop
        let pend = pending + input * hop
        let out = part + input * hop
        let synthesis = self.synthesis
        let length = vDSP_Length(hop)
        // out = pend + y·w over the first hop, then pend = y·w over the second:
        // a multiply, then an add, as the Kotlin does (not vDSP_vma, which may fuse).
        vDSP_vmul(y + start, 1, synthesis + start, 1, out, 1, length)
        vDSP_vadd(pend, 1, out, 1, out, 1, length)
        vDSP_vmul(y + start + hop, 1, synthesis + start + hop, 1, pend, 1, length)
    }
}
