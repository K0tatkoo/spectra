import Foundation

/// Kotlin `Double.toLong()`: truncates toward zero, NaN → 0, saturating at the
/// Int64 range. Beam.kt and Crt.kt are the only places that need it; it belongs
/// beside the other helpers in support/KotlinParity.swift.
@inline(__always)
func beamToLong(_ x: Double) -> Int64 {
    if x.isNaN { return 0 }
    if x >= 9.223372036854775807e18 { return .max }
    if x <= -9.223372036854775808e18 { return .min }
    return Int64(x)
}

/// Paces the Oscilloscope page's beam through the capture.
///
/// Audio arrives in bursts — 1024 frames every 21 ms on the phone, 2048 on
/// Windows — while a surface draws on its own clock, sixty or more times a
/// second. Drawing "whatever arrived since the last frame" would hand one frame
/// a whole burst and the next nothing, and the picture would pulse between the
/// two. So the beam runs a little behind the newest sample and moves through the
/// audio exactly as fast as time passes, like a jitter buffer: each frame draws
/// an even slice, the slices meet end to end, and every sample is drawn once.
///
/// The lag is measured rather than assumed — a little more than the largest
/// burst seen lately — so one clock suits both captures. Only a surface that
/// draws faster than 20 fps measures it: the widget, at 2 fps, sees whole
/// bursts pile up between its frames and would learn nothing true from them.
///
/// Owned by the surface's draw thread.
public final class BeamClock {

    private var head = Double.nan
    private var rate = 0
    private var lastNanos: Int64 = 0
    private var lastWritten: Int64 = 0
    private var burst = 0.0

    /// The capture to draw this frame: absolute frames [from, to).
    public private(set) var from: Int64 = 0
    public private(set) var to: Int64 = 0

    /// True when this slice does not continue the last one — the first frame,
    /// a stall long enough to fall far behind, a new sample rate. Whatever is on
    /// the tube belongs to another moment and should go.
    public private(set) var jumped = true

    public init() {}

    /// How far behind the newest sample the beam aims to run, in frames.
    public var targetLag: Double {
        coerceIn(burst * 1.25 + Double(rate) * BeamClock.slackS,
                 Double(rate) * BeamClock.minLagS, Double(rate) * BeamClock.maxTargetS)
    }

    public func advance(written: Int64, sampleRate: Int, nowNanos: Int64) {
        // The newest frame the interpolator can finish: it needs a few after it.
        let limit = Double(written - Int64(BeamInterpolator.context))
        if head.isNaN || sampleRate != rate || written < lastWritten {
            rate = sampleRate
            burst = Double(sampleRate) * BeamClock.firstBurstS
            head = max(0.0, min(Double(written) - targetLag, limit))
            lastNanos = nowNanos
            lastWritten = written
            from = beamToLong(floor(head))
            to = from
            jumped = true
            return
        }

        let elapsed = coerceIn(Double(nowNanos &- lastNanos) * 1e-9, 0.0, BeamClock.maxStepS)
        lastNanos = nowNanos
        let arrived = written - lastWritten
        lastWritten = written
        if arrived > 0 && elapsed < BeamClock.fastSurfaceS { burst = max(Double(arrived), burst * BeamClock.burstDecay) }

        var next = head + elapsed * Double(rate)
        let lag = Double(written) - next
        jumped = lag > Double(rate) * BeamClock.maxLagS
        if jumped {
            next = Double(written) - targetLag
        } else {
            // Ease towards the target instead of snapping to it: a correction
            // spread over a second is invisible, one made in a frame is a hiccup.
            next += (lag - targetLag) * BeamClock.catchUp
        }
        if next > limit { next = limit }
        if next < head && !jumped { next = head }
        from = jumped ? beamToLong(floor(next)) : beamToLong(floor(head))
        head = next
        to = max(from, beamToLong(floor(head)))
    }

    public func reset() {
        head = .nan
    }

    /// What the phone's capture delivers per read; replaced by what is measured.
    private static let firstBurstS = 0.022
    private static let slackS = 0.006
    private static let minLagS = 0.010
    private static let maxTargetS = 0.150
    /// Further behind than this and the beam jumps to the present rather than catching up.
    private static let maxLagS = 0.300
    /// A frame longer than this is a surface that was away, not a slow frame.
    private static let maxStepS = 1.0
    private static let fastSurfaceS = 0.050
    /// Forgets a big burst over about ten seconds at 60 fps.
    private static let burstDecay = 0.998
    private static let catchUp = 0.02
}

/// Band-limited 4× interpolation of the capture: the path a real beam takes
/// behind a DAC, rather than straight lines from sample to sample.
///
/// At 48 kHz a figure drawn at 200 Hz has 240 samples to its whole outline, and
/// joined with straight lines its curves come out as polygons. A converter's
/// reconstruction filter is what makes a real tube draw them smooth — and what
/// makes a sharp corner ring a little — so the beam is interpolated the same
/// way: a Kaiser-windowed sinc, four points to every sample, which also puts it
/// at the 192 kHz that oscilloscope music is mastered at.
///
/// Stateless: every call is given the samples either side of what it draws, so
/// a surface can start and stop anywhere in the capture.
public final class BeamInterpolator {

    private let taps = BeamInterpolator.halfTaps * 2
    private let kernel: UnsafeMutableBufferPointer<Float>

    public init() {
        let taps = BeamInterpolator.halfTaps * 2
        kernel = .allocate(capacity: BeamInterpolator.factor * taps)
        kernel.initialize(repeating: 0)
        let i0Beta = BeamInterpolator.besselI0(BeamInterpolator.kaiserBeta)
        for p in 0..<BeamInterpolator.factor {
            let frac = Double(p) / Double(BeamInterpolator.factor)
            var sum = 0.0
            for k in 0..<taps {
                // Offset of input sample k from the output instant, in samples.
                let t = Double(k - BeamInterpolator.halfTaps + 1) - frac
                let x = BeamInterpolator.cutoff * t
                let sinc = x == 0.0 ? 1.0 : sin(Double.pi * x) / (Double.pi * x)
                let r = t / Double(BeamInterpolator.halfTaps)
                let w = r * r >= 1.0 ? 0.0
                    : BeamInterpolator.besselI0(BeamInterpolator.kaiserBeta * (1.0 - r * r).squareRoot()) / i0Beta
                let v = BeamInterpolator.cutoff * sinc * w
                kernel[p * taps + k] = Float(v)
                sum += v
            }
            // Unity DC gain in every phase, or a still beam would buzz between
            // four slightly different places.
            for k in 0..<taps { kernel[p * taps + k] = Float(Double(kernel[p * taps + k]) / sum) }
        }
    }

    deinit { kernel.deallocate() }

    /// Interpolates `count` samples of `src` from `start`: writes `factor` points
    /// per sample into `dst`, sample i's own instant first and then the three
    /// after it. `src` must hold `context` samples before `start` and `context`
    /// after the last one.
    public func interpolate(_ src: UnsafePointer<Float>, start: Int, count: Int, dst: UnsafeMutablePointer<Float>) {
        let taps = self.taps
        let kernel = self.kernel
        var o = 0
        for i in 0..<count {
            let base = start + i - BeamInterpolator.halfTaps + 1
            for p in 0..<BeamInterpolator.factor {
                let k0 = p * taps
                var acc: Float = 0
                for k in 0..<taps { acc += src[base + k] * kernel[k0 + k] }
                dst[o] = acc
                o += 1
            }
        }
    }

    /// The same, on arrays.
    public func interpolate(_ src: [Float], start: Int, count: Int, dst: inout [Float]) {
        precondition(start + 1 >= BeamInterpolator.context && start + count + BeamInterpolator.context <= src.count)
        precondition(dst.count >= count * BeamInterpolator.factor)
        src.withUnsafeBufferPointer { s in
            dst.withUnsafeMutableBufferPointer { d in
                interpolate(s.baseAddress!, start: start, count: count, dst: d.baseAddress!)
            }
        }
    }

    public static let factor = 4
    private static let halfTaps = 8
    /// Samples needed on each side of what is interpolated.
    public static let context = halfTaps
    /// Just under the capture's Nyquist, where a converter's own filter sits.
    private static let cutoff = 0.94
    private static let kaiserBeta = 8.0

    /// Zeroth-order modified Bessel function, by its power series. For Kaiser windows.
    ///
    /// Resampler.kt's `besselI0`, which Beam.kt calls; kept as a member here so
    /// it cannot collide with the Resampler port's own copy.
    private static func besselI0(_ x: Double) -> Double {
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
}

/// The Y-T time base, triggered the way a bench scope's AUTO mode is: a sweep
/// starts where the signal next crosses zero going up, and if nothing crosses
/// for a while it sweeps anyway, so that silence still draws its flat line.
///
/// Two concessions to music. The trigger listens to a low-passed copy, cornered
/// by the time base: a mix crosses zero many times a period, once for every
/// loud harmonic, and each of those is a different place to start — low-passed,
/// it crosses once, on whatever is lowest and loudest on screen. And the
/// crossing is found between samples: at 0.1 ms per division a sample is a fifth
/// of one, and a sweep snapped to whole samples would make the trace shiver.
public final class BeamSweep {

    private var lp: Biquad? = nil
    private var lpRate = 0
    private var lpCorner = 0.0
    private var release = 0.0
    private var prev = 0.0
    private var envelope = 0.0
    private var armed = false
    private var waited = 0.0
    private var start = 0.0

    /// A sweep is crossing the screen; the beam is lit.
    public private(set) var sweeping = false

    /// The sweep on screen was started by the signal, not by the AUTO timer.
    public private(set) var triggered = false

    /// Where the latest sweep started, in absolute frames.
    public private(set) var lastStart = Double.nan

    public init() {}

    /// Sets the trigger's low-pass for `sweepFrames` of sweep at `rate`. Cheap to call every frame.
    public func configure(rate: Int, sweepFrames: Double) {
        let corner = coerceIn(BeamSweep.triggerCycles * Double(rate) / sweepFrames, BeamSweep.minCornerHz, BeamSweep.maxCornerHz)
        if rate != lpRate || corner != lpCorner {
            lp = Biquad.lowPass(sampleRate: rate, freq: corner)
            lpRate = rate
            lpCorner = corner
            release = exp(-1.0 / (Double(rate) * BeamSweep.envelopeReleaseS))
            prev = 0.0
        }
    }

    /// Frame `n` of the mix, `v`. Starts a sweep when it crosses, or when
    /// `autoFrames` have gone by without one.
    @inline(__always)
    public func step(_ n: Int64, _ v: Float, autoFrames: Double) {
        let y = lp?.process(Double(v)) ?? Double(v)
        let a = abs(y)
        envelope = a > envelope ? a : envelope * release
        if !sweeping {
            // Re-armed only once the signal has been properly below zero, so
            // noise around the crossing cannot start a second sweep.
            if y < -envelope * BeamSweep.hysteresis { armed = true }
            waited += 1.0
            if armed && prev < 0.0 && y >= 0.0 && envelope > BeamSweep.minLevel {
                begin(Double(n - 1) + prev / (prev - y), true)
            } else if waited >= autoFrames {
                begin(Double(n), false)
            }
        }
        prev = y
    }

    /// Position across the screen, 0 to 1, of the instant `t` (absolute frames).
    @inline(__always)
    public func x(_ t: Double, sweepFrames: Double) -> Double { (t - start) / sweepFrames }

    /// The sweep reached the right-hand edge: retrace, and wait for the next one.
    public func end() {
        sweeping = false
        waited = 0.0
    }

    public func reset() {
        lp?.reset()
        prev = 0.0
        envelope = 0.0
        armed = false
        waited = 0.0
        sweeping = false
        triggered = false
    }

    private func begin(_ at: Double, _ byTrigger: Bool) {
        sweeping = true
        triggered = byTrigger
        start = at
        lastStart = at
        armed = false
        waited = 0.0
    }

    /// The trigger's corner sits this many cycles per sweep up.
    private static let triggerCycles = 3.0
    private static let minCornerHz = 40.0
    private static let maxCornerHz = 2000.0
    private static let envelopeReleaseS = 0.3
    private static let hysteresis = 0.1
    /// −80 dBFS: below this there is nothing to trigger on.
    private static let minLevel = 1e-4
}
