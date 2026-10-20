import Foundation

/// Where the Oscilloscope page puts its tube, in a surface's own pixels.
///
/// Shared by both painters, so a division is the same share of the glass on
/// the phone and on Windows. X-Y gets a square graticule — a circle has to come
/// out round — while Y-T spreads its ten divisions over whatever room there is.
public final class CrtLayout {
    public private(set) var faceL: Float = 0
    public private(set) var faceT: Float = 0
    public private(set) var faceR: Float = 0
    public private(set) var faceB: Float = 0
    public private(set) var gratL: Float = 0
    public private(set) var gratT: Float = 0
    public private(set) var gratR: Float = 0
    public private(set) var gratB: Float = 0
    public private(set) var divisionsX = 8
    public private(set) var divisionsY = 8
    /// Corner radius of the glass.
    public private(set) var radius: Float = 0
    /// Baseline of the readout under the graticule; NaN on a compact surface.
    public private(set) var readoutY = Float.nan
    /// Pixels per dp (points, on iPhone) on the surface: what the tube's own resolution is chosen from.
    public private(set) var density: Float = 1

    public init() {}

    public func compute(left: Float, top: Float, right: Float, bottom: Float, density: Float, mode: OscMode, compact: Bool) {
        self.density = density
        faceL = left
        faceT = top
        faceR = right
        faceB = bottom
        radius = density * (compact ? 6 : 12)
        let pad = density * (compact ? 3 : 9)
        let band: Float = compact ? 0 : density * 18
        readoutY = compact ? .nan : bottom - density * 8
        divisionsX = mode == .xy ? 8 : Crt.timeDivisions
        divisionsY = 8
        let l = left + pad
        let t = top + pad
        let r = right - pad
        let b = bottom - pad - band
        if mode == .xy {
            let side = max(0, min(r - l, b - t))
            gratL = (l + r - side) / 2
            gratT = (t + b - side) / 2
            gratR = gratL + side
            gratB = gratT + side
        } else {
            gratL = l
            gratT = t
            gratR = max(l, r)
            gratB = max(t, b)
        }
    }
}

/// The Oscilloscope page's tube: a beam, a phosphor, and the glass in front of it.
///
/// What makes a real scope look like one is that brightness is *time*: the
/// phosphor glows in proportion to how long the beam spends on it. A slow stroke
/// is bright, a fast jump across the screen is a faint thread, and a beam at
/// rest burns a white-hot dot — which is exactly why oscilloscope music can hide
/// the moves between its shapes. So the beam here deposits energy along its
/// path, the same amount for every instant, spread over however much glass that
/// instant covers. The picture is that energy fading with the phosphor's
/// persistence, developed through a curve that saturates the phosphor's own
/// colour first and white after, so the core of a bright stroke goes pale while
/// its edges stay green. The glow is a blur of the same energy: the light a
/// bright stroke throws on the glass around it.
///
/// It runs on the surface's own thread and keeps its own state — the phosphor
/// remembers — so each surface has one. Pure arithmetic into a pixel buffer, so
/// the phone, Windows and the iPhone draw the same tube.
///
/// Measured cost of the Kotlin original (commit 0c9474d): 0.95 ms a frame for a
/// phone-sized tube on an M1 core, 2–3 ms of CPU on the Android emulator at 1.5×
/// a phone's pixels. Stretching the picture in Java2D's software path cost
/// 7.5 ms, which is why the Windows build draws it one to one. `TimingTests`
/// measures this port the same way.
///
/// ## The pixels, for a CGImage
///
/// `pixels` is `width × height` `UInt32`s, row by row with no padding, each
/// 0xAARRGGBB — the same numbers as the Kotlin `IntArray`. Every pixel is
/// opaque (alpha 0xFF; the tone table is built with `Palette.argb(255, …)`), so
/// premultiplied and straight alpha are the same thing and nothing is
/// premultiplied here. On a little-endian CPU the bytes in memory are B, G, R, A,
/// which is exactly what `CGImage` reads with
/// `CGImageAlphaInfo.premultipliedFirst` (or `.noneSkipFirst`) +
/// `CGBitmapInfo.byteOrder32Little`, 8 bits a component, 32 a pixel,
/// `bytesPerRow = width * 4`, in sRGB (the tone curve encodes like sRGB).
///
/// The buffer is the tube's own storage, not a copy. It stays valid until the
/// next `render` that changes the tube's size (when `width`/`height` change)
/// or `release()`, and every `render` that returns true rewrites it in place —
/// so wrap it per frame, and don't let anything read it while `render` runs.
public final class Crt {

    public private(set) var width = 0
    public private(set) var height = 0

    /// The developed picture: opaque 0xAARRGGBB, row by row, `width` × `height`.
    /// See the type's notes for how to hand it to a CGImage without a copy.
    public private(set) var pixels = UnsafeMutableBufferPointer<UInt32>(start: nil, count: 0)

    /// True when the last `render` changed `pixels`.
    public private(set) var changed = false

    /// Whether the Y-T sweep on screen was started by the signal (false: AUTO).
    public var triggered: Bool { sweep.triggered }

    private var energy = UnsafeMutableBufferPointer<Float>(start: nil, count: 0)
    /// The glow, at a quarter of the tube's resolution — it is a blur several
    /// pixels wide, and a quarter of the pixels is a quarter of the blurring.
    /// `glow` is what this frame shows, blurred from what the last one
    /// gathered; `glowNext` is what this frame gathers.
    private var glow = UnsafeMutableBufferPointer<Float>(start: nil, count: 0)
    private var glowNext = UnsafeMutableBufferPointer<Float>(start: nil, count: 0)
    private var glowTmp = UnsafeMutableBufferPointer<Float>(start: nil, count: 0)
    private var glowW = 0
    private var glowH = 0
    /// For each column of the tube, the glow column left of it and how far towards the next.
    private var glowCol = UnsafeMutableBufferPointer<Int>(start: nil, count: 0)
    private var glowFrac = UnsafeMutableBufferPointer<Float>(start: nil, count: 0)
    /// One row of glow, interpolated to the tube row being developed.
    private var glowRow = UnsafeMutableBufferPointer<Float>(start: nil, count: 0)

    private let clock = BeamClock()
    private let interpolator = BeamInterpolator()
    private let sweep = BeamSweep()
    private var planar = UnsafeMutableBufferPointer<Float>(start: nil, count: 0)
    private var mix = UnsafeMutableBufferPointer<Float>(start: nil, count: 0)
    private var pointsX = UnsafeMutableBufferPointer<Float>(start: nil, count: 0)
    private var pointsY = UnsafeMutableBufferPointer<Float>(start: nil, count: 0)

    private var glowRadius = 1
    /// The tone table, `lutSize` entries. Rebuilt in place when the phosphor changes.
    private let lut: UnsafeMutableBufferPointer<UInt32>
    private var lutPhosphor: Phosphor? = nil
    private var face: UInt32 = 0
    private var mode: OscMode? = nil
    private var lastGlowGain: Float = -1
    private var dirty = true

    // The graticule in the tube's own pixels.
    private var gridL: Float = 0
    private var gridR: Float = 0
    private var centreX: Float = 0
    private var centreY: Float = 0
    private var halfW: Float = 1
    private var halfH: Float = 1

    // Where the beam was at the end of the last frame, so strokes join up.
    private var beamX: Float = 0
    private var beamY: Float = 0
    private var beamOn = false

    private var columnAcc = UnsafeMutableBufferPointer<Float>(start: nil, count: 0)

    public init() {
        lut = .allocate(capacity: Crt.lutSize)
        lut.initialize(repeating: 0)
    }

    deinit {
        lut.deallocate()
        freeBuffers()
        columnAcc.deallocate()
    }

    /// Moves the beam on to `nowNanos` and develops the picture into `pixels`.
    ///
    /// Returns false when nothing arrived to draw — the capture is paused, or
    /// this frame fell between two of its bursts — and `pixels` still holds the
    /// previous picture, unfaded: the phosphor fades with audio time, not wall
    /// time, so a paused scope freezes the way every other page does.
    @discardableResult
    public func render(
        feed: StereoFeed,
        rate: Int,
        settings s: Settings,
        layout: CrtLayout,
        nowNanos: Int64,
        glowAllowed: Bool = true
    ) -> Bool {
        changed = false
        let fw = layout.faceR - layout.faceL
        let fh = layout.faceB - layout.faceT
        if fw < 2 || fh < 2 || rate <= 0 { return false }
        let scale = Crt.internalScale(faceW: fw, faceH: fh, density: layout.density)
        let w = max(2, ktToInt(ceil(fw * scale)))
        let h = max(2, ktToInt(ceil(fh * scale)))
        if w != width || h != height { resize(w, h) }

        // The halo is a few dp wide on every surface: the same soft edge on a
        // phone and on a monitor, where the glass is twice the size in dp.
        glowRadius = max(1, ktRoundToInt(Crt.glowSigmaDp * layout.density * scale / Float(Crt.glowCell) * 0.8))
        let sx = Float(w) / fw
        let sy = Float(h) / fh
        let gl = (layout.gratL - layout.faceL) * sx
        let gr = (layout.gratR - layout.faceL) * sx
        let gt = (layout.gratT - layout.faceT) * sy
        let gb = (layout.gratB - layout.faceT) * sy
        if gl != gridL || gr != gridR || (gt + gb) / 2 != centreY || (gb - gt) / 2 != halfH {
            gridL = gl
            gridR = gr
            centreX = (gl + gr) / 2
            centreY = (gt + gb) / 2
            halfW = max(1, (gr - gl) / 2)
            halfH = max(1, (gb - gt) / 2)
            // The old picture is in the old coordinates.
            clearEnergy()
            beamOn = false
        }
        if s.oscMode != mode {
            mode = s.oscMode
            clearEnergy()
            sweep.reset()
            beamOn = false
        }
        if s.oscPhosphor != lutPhosphor { buildLut(s.oscPhosphor) }
        let glowGain: Float = glowAllowed ? coerceIn(s.oscGlow, 0, 1) * Crt.glowGainMax : 0
        if glowGain != lastGlowGain {
            // Switched back on, the glow would show whatever it last gathered.
            if lastGlowGain <= 0 { glow.update(repeating: 0) }
            lastGlowGain = glowGain
            dirty = true
        }

        clock.advance(written: feed.written, sampleRate: rate, nowNanos: nowNanos)
        // Kotlin: rate * oscPersistenceMs is Int × Float, so Float, then widened.
        let tauFrames = max(1.0, Double(Float(rate) * s.oscPersistenceMs) / 1000.0)
        // Further back than this the picture has faded below what shows.
        let reach = min(min(beamToLong(tauFrames * Crt.fadeTaus) + Int64(rate / 30), beamToLong(Double(rate) * Crt.maxDrawS)),
                        Int64(feed.capacity) - 4 * Int64(BeamInterpolator.context))
        let to = clock.to
        var from = clock.from
        var decay = 1.0
        if clock.jumped {
            clearEnergy()
            sweep.reset()
            beamOn = false
            from = max(0, to - reach)
        } else {
            decay = exp(Double(-(to - from)) / tauFrames)
            if to - from > reach {
                // A slow surface: its frame starts too long ago to show.
                from = to - reach
                sweep.reset()
                beamOn = false
            }
        }
        let count = Int(to - from)
        if count <= 0 && !dirty { return false }
        if decay < 1e-6 {
            clearEnergy()
            decay = 1.0
        }
        if count > 0 { trace(feed, rate, s, from, count, tauFrames, decay) }
        develop(Float(decay), glowGain)
        dirty = false
        changed = true
        return true
    }

    public func release() {
        width = 0
        height = 0
        freeBuffers()
        clock.reset()
        sweep.reset()
        beamOn = false
        dirty = true
    }

    private func freeBuffers() {
        for b in [energy, glow, glowNext, glowTmp, glowFrac, glowRow, planar, mix, pointsX, pointsY] { b.deallocate() }
        pixels.deallocate()
        glowCol.deallocate()
        energy = .init(start: nil, count: 0)
        glow = .init(start: nil, count: 0)
        glowNext = .init(start: nil, count: 0)
        glowTmp = .init(start: nil, count: 0)
        glowFrac = .init(start: nil, count: 0)
        glowRow = .init(start: nil, count: 0)
        planar = .init(start: nil, count: 0)
        mix = .init(start: nil, count: 0)
        pointsX = .init(start: nil, count: 0)
        pointsY = .init(start: nil, count: 0)
        pixels = .init(start: nil, count: 0)
        glowCol = .init(start: nil, count: 0)
    }

    // ---- the beam ----------------------------------------------------------

    private func trace(_ feed: StereoFeed, _ rate: Int, _ s: Settings, _ from: Int64, _ count: Int,
                       _ tauFrames: Double, _ decay: Double) {
        let context = BeamInterpolator.context
        let n = count + 2 * context
        if planar.count < 2 * n { Crt.regrow(&planar, 2 * n + 1024) }
        if !feed.readPlanar(from: from - Int64(context), count: n, into: planar.baseAddress!) {
            // Overwritten or not there yet: start again from the present.
            clock.reset()
            return
        }
        let points = count * BeamInterpolator.factor
        if pointsY.count < points {
            Crt.regrow(&pointsX, points + 4096)
            Crt.regrow(&pointsY, points + 4096)
        }
        // Every instant of beam gets the same energy; scaled with the graticule
        // so a figure is as bright on a small surface as on a big one, and
        // pre-divided by the fade develop() is about to apply to everything.
        let e0 = Double(coerceIn(s.oscIntensity, 0.01, 100)) * Crt.energyPerSecond * Double(halfH / Crt.refHalf) /
            (Double(rate) * Double(BeamInterpolator.factor)) / decay
        if s.oscMode == .yt {
            if mix.count < n { Crt.regrow(&mix, n + 1024) }
            for i in 0..<n { mix[i] = 0.5 * (planar[i] + planar[n + i]) }
            interpolator.interpolate(mix.baseAddress!, start: context, count: count, dst: pointsY.baseAddress!)
            traceYT(rate, s, from, count, tauFrames, e0)
        } else {
            interpolator.interpolate(planar.baseAddress!, start: context, count: count, dst: pointsX.baseAddress!)
            interpolator.interpolate(planar.baseAddress!, start: n + context, count: count, dst: pointsY.baseAddress!)
            traceXY(s, count, tauFrames, e0)
        }
    }

    private func traceXY(_ s: Settings, _ count: Int, _ tauFrames: Double, _ e0: Double) {
        let tube = Tube(energy: energy.baseAddress!, width: width, height: height)
        let px = pointsX
        let py = pointsY
        let cx = centreX
        let cy = centreY
        let zx = s.oscZoom * halfW
        let zy = s.oscZoom * halfH
        var x0 = beamX
        var y0 = beamY
        var on = beamOn
        var k = 0
        for i in 0..<count {
            // Older instants have been fading for longer.
            let e = Float(e0 * exp(Double(-(count - 1 - i)) / tauFrames))
            for _ in 0..<BeamInterpolator.factor {
                let x1 = cx + px[k] * zx
                let y1 = cy - py[k] * zy
                if on { tube.segment(x0, y0, x1, y1, e) } else { tube.splat(x1 - 0.5, y1 - 0.5, e) }
                x0 = x1
                y0 = y1
                on = true
                k += 1
            }
        }
        beamX = x0
        beamY = y0
        beamOn = on
    }

    private func traceYT(_ rate: Int, _ s: Settings, _ from: Int64, _ count: Int, _ tauFrames: Double, _ e0: Double) {
        let context = BeamInterpolator.context
        let factor = BeamInterpolator.factor
        // Kotlin: TIME_DIVISIONS * oscTimeDivMs * rate is Int × Float × Int, so Float, then widened.
        let sweepFrames = Double(Float(Crt.timeDivisions) * s.oscTimeDivMs * Float(rate)) / 1000.0
        sweep.configure(rate: rate, sweepFrames: sweepFrames)
        let autoFrames = max(Double(rate) * Crt.autoS, sweepFrames * 1.5)
        let tube = Tube(energy: energy.baseAddress!, width: width, height: height)
        let mix = self.mix
        let py = pointsY
        let left = gridL
        let cy = centreY
        let zy = s.oscZoom * halfH
        let span = gridR - gridL
        var x0 = beamX
        var y0 = beamY
        var on = beamOn
        for i in 0..<count {
            let frame = from + Int64(i)
            sweep.step(frame, mix[context + i], autoFrames: autoFrames)
            if !sweep.sweeping {
                on = false
                continue
            }
            let e = Float(e0 * exp(Double(-(count - 1 - i)) / tauFrames))
            for p in 0..<factor {
                let u = sweep.x(Double(frame) + Double(p) / Double(factor), sweepFrames: sweepFrames)
                if u < 0.0 { continue }
                if u > 1.0 {
                    // Retrace, blanked, as on the real thing.
                    sweep.end()
                    on = false
                    break
                }
                let x1 = left + Float(u * Double(span))
                let y1 = cy - py[i * factor + p] * zy
                if on { tube.segment(x0, y0, x1, y1, e) } else { tube.splat(x1 - 0.5, y1 - 0.5, e) }
                x0 = x1
                y0 = y1
                on = true
            }
        }
        beamX = x0
        beamY = y0
        beamOn = on
    }

    /// The energy plane, handed to the beam by value so its hot loops touch no
    /// class storage (and so no exclusivity checks).
    private struct Tube {
        let energy: UnsafeMutablePointer<Float>
        let width: Int
        let height: Int

        /// One instant of beam, from (x0, y0) to (x1, y1) in the tube's pixels:
        /// `e` spread evenly along it, so energy per pixel falls as the beam speeds up.
        @inline(__always)
        func segment(_ x0: Float, _ y0: Float, _ x1: Float, _ y1: Float, _ e: Float) {
            let w = Float(width)
            let h = Float(height)
            if (x0 < -2 && x1 < -2) || (x0 > w + 2 && x1 > w + 2) ||
                (y0 < -2 && y1 < -2) || (y0 > h + 2 && y1 > h + 2)
            {
                return
            }
            let dx = x1 - x0
            let dy = y1 - y0
            let len = (dx * dx + dy * dy).squareRoot()
            var steps = ktToInt(len * Crt.stepsPerPx) + 1
            if steps > Crt.maxSteps { steps = Crt.maxSteps }
            let es = e / Float(steps)
            let stepX = dx / Float(steps)
            let stepY = dy / Float(steps)
            // Pixel i's centre is at i + 0.5; splat works in centre coordinates.
            var x = x0 + stepX * 0.5 - 0.5
            var y = y0 + stepY * 0.5 - 0.5
            for _ in 0..<steps {
                splat(x, y, es)
                x += stepX
                y += stepY
            }
        }

        /// Deposits `e` at (x, y), in pixel-centre coordinates, shared bilinearly between four pixels.
        @inline(__always)
        func splat(_ x: Float, _ y: Float, _ e: Float) {
            let fx0 = floor(x)
            let fy0 = floor(y)
            let ix = ktToInt(fx0)
            let iy = ktToInt(fy0)
            let fx = x - fx0
            let fy = y - fy0
            let w = width
            let h = height
            if ix >= 0 && iy >= 0 && ix < w - 1 && iy < h - 1 {
                let i = iy * w + ix
                let gx = 1 - fx
                let gy = 1 - fy
                energy[i] += e * gx * gy
                energy[i + 1] += e * fx * gy
                energy[i + w] += e * gx * fy
                energy[i + w + 1] += e * fx * fy
            } else if ix >= -1 && iy >= -1 && ix < w && iy < h {
                add(ix, iy, e * (1 - fx) * (1 - fy))
                add(ix + 1, iy, e * fx * (1 - fy))
                add(ix, iy + 1, e * (1 - fx) * fy)
                add(ix + 1, iy + 1, e * fx * fy)
            }
        }

        @inline(__always)
        private func add(_ x: Int, _ y: Int, _ e: Float) {
            if x < 0 || y < 0 || x >= width || y >= height { return }
            energy[y * width + x] += e
        }
    }

    // ---- the phosphor ------------------------------------------------------

    /// Fades everything by `decay` and develops it, with the glass's glow, into
    /// `pixels` — in one pass over the tube, the work that has to be done for
    /// every pixel of every frame.
    ///
    /// Two things keep it cheap. The glow shown is the one gathered on the last
    /// frame, blurred: a sixtieth of a second behind the stroke, which nobody
    /// can see, and it saves reading the whole tube a second time. And the tone
    /// curve is looked up by the energy's own float bits — exponent and top of
    /// the mantissa — which is a logarithmic table with no division in it: fine
    /// steps where faint trails need them, coarse ones where it is all white.
    private func develop(_ decay: Float, _ glowGain: Float) {
        let w = width
        let h = height
        let e = energy
        let out = pixels
        let table = lut
        let dark = face
        if glowGain > 0 {
            let shown = glow
            let next = glowNext
            next.update(repeating: 0)
            let gw = glowW
            let gh = glowH
            let col = glowCol
            let frac = glowFrac
            let line = glowRow
            // The glow was gathered before this frame's fade.
            let gain = glowGain * decay
            for y in 0..<h {
                let row = y * w
                // Bilinear, so the halo is smooth rather than made of blocks:
                // this row's glow, interpolated once, then across it per pixel.
                let fy = (Float(y) + 0.5) / Float(Crt.glowCell) - 0.5
                var y0 = ktToInt(floor(fy))
                var ty = fy - Float(y0)
                if y0 < 0 { y0 = 0; ty = 0 }
                if y0 > gh - 2 { y0 = gh - 2; ty = 1 }
                let g0 = y0 * gw
                for c in 0..<gw { line[c] = shown[g0 + c] + (shown[g0 + gw + c] - shown[g0 + c]) * ty }
                // Gathered a cell at a time: adding each pixel straight into
                // the array makes every add wait for the one before it.
                var cell = (y >> Crt.glowShift) * gw
                var x = 0
                while x < w {
                    let end = x + Crt.glowCell < w ? x + Crt.glowCell : w
                    var gathered: Float = 0
                    while x < end {
                        let i = row + x
                        var v = e[i] * decay
                        if v < Crt.floorEnergy { v = 0 }
                        e[i] = v
                        // Capped, because a phosphor cannot give out more light than it
                        // has: a resting beam piles thousands of strokes into one pixel.
                        gathered += v < Crt.glowCap ? v : Crt.glowCap
                        let c = col[x]
                        let g = line[c] + (line[c + 1] - line[c]) * frac[x]
                        out[i] = table[Crt.toneIndex(v + g * gain)]
                        x += 1
                    }
                    next[cell] += gathered
                    cell += 1
                }
            }
            // Swap: what was gathered is blurred into next frame's glow.
            glowNext = shown
            glow = next
            let norm = 1 / Float(Crt.glowCell * Crt.glowCell)
            for k in 0..<next.count { next[k] *= norm }
            blur(next, glowTmp, gw, gh, glowRadius)
        } else {
            for i in 0..<(w * h) {
                var v = e[i] * decay
                if v < Crt.floorEnergy { v = 0 }
                e[i] = v
                out[i] = table[Crt.toneIndex(v)]
            }
        }
        if dark != table[0] { face = table[0] }
    }

    /// Three box passes each way — close enough to a Gaussian that a single
    /// bright dot throws a round halo, at a fixed cost per pixel whatever the
    /// radius. Both directions walk the rows in order; a column-by-column
    /// vertical pass spends its time waiting on memory.
    private func blur(_ a: UnsafeMutableBufferPointer<Float>, _ tmp: UnsafeMutableBufferPointer<Float>, _ w: Int, _ h: Int, _ r: Int) {
        for _ in 0..<3 {
            boxRows(a, tmp, w, h, r)
            boxColumns(tmp, a, w, h, r)
        }
    }

    private func boxRows(_ src: UnsafeMutableBufferPointer<Float>, _ dst: UnsafeMutableBufferPointer<Float>, _ w: Int, _ h: Int, _ r: Int) {
        let norm = 1 / Float(2 * r + 1)
        for y in 0..<h {
            let row = y * w
            var acc: Float = 0
            for k in -r...r { acc += src[row + coerceIn(k, 0, w - 1)] }
            for x in 0..<w {
                dst[row + x] = acc * norm
                acc += src[row + min(x + r + 1, w - 1)] - src[row + max(x - r, 0)]
            }
        }
    }

    private func boxColumns(_ src: UnsafeMutableBufferPointer<Float>, _ dst: UnsafeMutableBufferPointer<Float>, _ w: Int, _ h: Int, _ r: Int) {
        let norm = 1 / Float(2 * r + 1)
        if columnAcc.count < w { Crt.regrow(&columnAcc, w) }
        let acc = columnAcc
        for x in 0..<w { acc[x] = 0 }
        for k in -r...r {
            let row = coerceIn(k, 0, h - 1) * w
            for x in 0..<w { acc[x] += src[row + x] }
        }
        for y in 0..<h {
            let row = y * w
            let add = min(y + r + 1, h - 1) * w
            let sub = max(y - r, 0) * w
            for x in 0..<w {
                dst[row + x] = acc[x] * norm
                acc[x] += src[add + x] - src[sub + x]
            }
        }
    }

    private func resize(_ w: Int, _ h: Int) {
        width = w
        height = h
        Crt.regrow(&energy, w * h)
        pixels.deallocate()
        pixels = .allocate(capacity: w * h)
        pixels.initialize(repeating: 0)
        // One spare cell each way, so the interpolation at the far edges has a neighbour.
        glowW = (w + Crt.glowCell - 1) / Crt.glowCell + 1
        glowH = (h + Crt.glowCell - 1) / Crt.glowCell + 1
        Crt.regrow(&glow, glowW * glowH)
        Crt.regrow(&glowNext, glowW * glowH)
        Crt.regrow(&glowTmp, glowW * glowH)
        Crt.regrow(&glowRow, glowW)
        glowCol.deallocate()
        glowCol = .allocate(capacity: w)
        Crt.regrow(&glowFrac, w)
        for x in 0..<w {
            let f = (Float(x) + 0.5) / Float(Crt.glowCell) - 0.5
            let c = coerceIn(ktToInt(floor(f)), 0, glowW - 2)
            glowCol.initializeElement(at: x, to: c)
            glowFrac[x] = coerceIn(f - Float(c), 0, 1)
        }
        beamOn = false
        dirty = true
    }

    /// Replaces `b` with `count` zeroed floats — the Kotlin's `FloatArray(count)`.
    private static func regrow(_ b: inout UnsafeMutableBufferPointer<Float>, _ count: Int) {
        b.deallocate()
        b = .allocate(capacity: count)
        b.initialize(repeating: 0)
    }

    private func clearEnergy() {
        energy.update(repeating: 0)
        glow.update(repeating: 0)
        dirty = true
    }

    private func buildLut(_ p: Phosphor) {
        // Entry 0 is the dark glass; entry i the energy at the middle of its
        // bucket of float bits.
        for i in 0..<Crt.lutSize { lut[i] = i == 0 ? Crt.colorOf(p, 0) : Crt.colorOf(p, Crt.energyOfIndex(i)) }
        face = lut[0]
        lutPhosphor = p
        dirty = true
    }

    /// The tube's resolution relative to the surface: about 1.4 tube pixels
    /// a dp. That is half resolution on a phone — a trace still about a dp
    /// wide, a soft edge the glow wants anyway, a quarter of the work — and
    /// one to one on a desktop monitor, where it saves stretching the
    /// picture in software, which costs far more than drawing it. Small
    /// surfaces keep enough pixels to draw with; big ones are capped.
    public static func internalScale(faceW: Float, faceH: Float, density: Float) -> Float {
        let maxDim = max(faceW, faceH)
        if maxDim <= 0 { return 1 }
        var scale = min(1, tubePxPerDp / max(density, 0.5))
        scale = max(scale, min(1, minTubePx / maxDim))
        if maxDim * scale > maxTubePx { scale = maxTubePx / maxDim }
        return scale
    }

    /// The phosphor lit to `x`: 0 is the dark glass, about 1 a steady stroke, 20 and up white.
    public static func colorOf(_ p: Phosphor, _ x: Float) -> UInt32 {
        let spectrum = spectrumOf(p)
        let glass = glassOf(p)
        return Palette.argb(
            255,
            channel(x, spectrum.0, Palette.red(glass)),
            channel(x, spectrum.1, Palette.green(glass)),
            channel(x, spectrum.2, Palette.blue(glass))
        )
    }

    /// How strongly each of red, green and blue answers the beam. The main
    /// one saturates first and the faint ones only at high energy, which is
    /// what turns the core of a bright stroke white while its edges keep
    /// the phosphor's colour.
    private static func spectrumOf(_ p: Phosphor) -> (Float, Float, Float) {
        switch p {
        case .green: (0.030, 1, 0.075)
        case .amber: (1, 0.40, 0.020)
        case .blue: (0.050, 0.26, 1)
        case .spectra: (0.035, 0.80, 1)
        }
    }

    /// The unlit glass: nearly black, tinted by the phosphor coating behind it.
    private static func glassOf(_ p: Phosphor) -> UInt32 {
        switch p {
        case .green: 0xFF090E0B
        case .amber: 0xFF0E0B08
        case .blue: 0xFF080A10
        case .spectra: 0xFF080C10
        }
    }

    private static func channel(_ x: Float, _ weight: Float, _ glass: Int) -> Int {
        // Light is linear in the energy until the phosphor saturates, and
        // is encoded for the display the way sRGB is. Without the encoding
        // every faint stroke would vanish into the glass; with a plain
        // power curve instead, its steep foot would draw a visible ring
        // where the outermost glow starts.
        let light = 1.0 - exp(-Double(x * weight))
        let lit = light <= 0.0031308 ? 12.92 * light : 1.055 * pow(light, 1.0 / 2.4) - 0.055
        let base = Double(glass) / 255.0
        let v = 1.0 - (1.0 - base) * (1.0 - lit)
        return coerceIn(ktToInt(v * 255.0 + 0.5), 0, 255)
    }

    /// The tone table's index for energy `v`: its float bits shifted down to
    /// the exponent and the top 8 bits of the mantissa, counted from
    /// `lutFloor`. Floats are ordered like their bits, so this is a log
    /// scale: 256 steps to every doubling, from 1/2048 of a steady stroke
    /// to 256 times one, clamped at both ends.
    @inline(__always)
    private static func toneIndex(_ v: Float) -> Int {
        // Negative (a blur's rounding) and anything under the floor are the dark glass.
        let i = Int(Int32(bitPattern: v.bitPattern) >> Int32(lutShift)) - lutBase
        return i < 1 ? 0 : (i >= lutSize ? lutSize - 1 : i)
    }

    private static func energyOfIndex(_ i: Int) -> Float {
        Float(bitPattern: UInt32(truncatingIfNeeded: ((i + lutBase) << lutShift) | (1 << (lutShift - 1))))
    }

    private static let tubePxPerDp: Float = 1.4
    private static let minTubePx: Float = 220
    private static let maxTubePx: Float = 1000

    private static let lutShift = 15
    private static let lutFloor: Float = 1.0 / 2048.0
    private static let lutBase = Int(Int32(bitPattern: lutFloor.bitPattern) >> Int32(lutShift))
    /// Nineteen doublings, 1/2048 to 256.
    private static let lutSize = 19 * 256

    /// Energy the beam lays down per second of audio, at unit intensity.
    private static let energyPerSecond = 1.1e5
    /// The graticule half-height, in tube pixels, that `energyPerSecond` was tuned at.
    private static let refHalf: Float = 225
    /// Beam steps per pixel of path: close enough that the bilinear footprints overlap.
    private static let stepsPerPx: Float = 1.4
    /// A jump across the whole face is about 900 steps; nothing needs more.
    private static let maxSteps = 1200
    private static let fadeTaus = 6.0
    private static let maxDrawS = 0.5
    /// GLOW_GAIN in the Kotlin.
    private static let glowGainMax: Float = 0.8
    /// How soft the halo is, in dp.
    private static let glowSigmaDp: Float = 4
    /// Tube pixels to a glow cell, each way.
    private static let glowCell = 4
    private static let glowShift = 2
    /// The brightest a pixel counts for in the glow: a few times a steady stroke.
    private static let glowCap: Float = 6
    /// FLOOR in the Kotlin.
    private static let floorEnergy: Float = 1e-7
    public static let timeDivisions = 10
    /// A bench scope's AUTO waits about this long for a trigger before sweeping anyway.
    private static let autoS = 0.06
}
