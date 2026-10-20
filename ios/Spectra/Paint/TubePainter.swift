import SwiftUI
import SpectraCore

/// The Oscilloscope page: the simulated tube (Crt) seen through a graticule,
/// with a bench scope's readout along the bottom of the glass — VizPainter.kt's
/// drawOscilloscope, drawGraticule, drawGlass and drawScopeReadout.
///
/// The tube lays itself out in points with a density of 1, so it renders its
/// own pixels at about 1.4 per point — the Android phone's proportion — and is
/// stretched with filtering, which suits a trace a point wide with a soft edge.
/// The graticule and readout are drawn over it at full resolution so they stay
/// crisp, the way lines etched on the glass would.
@MainActor
final class TubePainter {
    private let layout = CrtLayout()
    private var tube: Crt?
    private var image: CGImage?

    func draw(_ ctx: GraphicsContext, area: CGRect, frame f: AnalysisFrame, settings s: Settings, palette: Palette) {
        let lay = layout
        lay.compute(left: Float(area.minX), top: Float(area.minY), right: Float(area.maxX), bottom: Float(area.maxY),
                    density: 1, mode: s.oscMode, compact: false)
        let face = CGRect(x: CGFloat(lay.faceL), y: CGFloat(lay.faceT),
                          width: CGFloat(lay.faceR - lay.faceL), height: CGFloat(lay.faceB - lay.faceT))
        let radius = CGFloat(lay.radius)
        let tint = Crt.colorOf(s.oscPhosphor, Self.tintEnergy)
        let tube = self.tube ?? Crt()
        self.tube = tube

        if let feed = f.beam {
            tube.render(feed: feed, rate: f.sampleRate, settings: s, layout: lay,
                        nowNanos: Int64(DispatchTime.now().uptimeNanoseconds), glowAllowed: true)
        }
        if f.beam == nil || tube.width == 0 {
            ctx.fill(Path(roundedRect: face, cornerRadius: radius), with: .color(Color(argb: Crt.colorOf(s.oscPhosphor, 0))))
            drawGraticule(ctx, lay, tint)
            return
        }
        // Unchanged between two capture bursts, or while frozen: the image still
        // holds the picture, and building it again would be waste.
        if tube.changed || image == nil || image!.width != tube.width || image!.height != tube.height {
            image = makeImage(Array(tube.pixels), width: tube.width, height: tube.height)
        }
        if let image {
            var clipped = ctx
            clipped.clip(to: Path(roundedRect: face, cornerRadius: radius))
            clipped.draw(Image(decorative: image, scale: 1).interpolation(.medium).resizable(), in: face)
        }
        drawGraticule(ctx, lay, tint)
        drawGlass(ctx, face, radius)
        drawReadout(ctx, lay, f, s, tube, tint, palette)
    }

    /// Eight divisions a side for X-Y, ten across for Y-T, with the centre axes
    /// ticked in fifths — the graticule of a bench scope, faint enough that the
    /// trace reads over it.
    private func drawGraticule(_ ctx: GraphicsContext, _ lay: CrtLayout, _ tint: UInt32) {
        let l = CGFloat(lay.gratL), t = CGFloat(lay.gratT), r = CGFloat(lay.gratR), b = CGFloat(lay.gratB)
        guard r - l >= 4, b - t >= 4 else { return }
        let nx = lay.divisionsX, ny = lay.divisionsY
        let dx = (r - l) / CGFloat(nx), dy = (b - t) / CGFloat(ny)
        let mx = (l + r) / 2, my = (t + b) / 2
        let ink = Palette.mix(Self.graticuleGrey, tint, 0.35)

        var faint = Path()
        for i in 1..<nx where i * 2 != nx {
            faint.move(to: CGPoint(x: l + dx * CGFloat(i), y: t))
            faint.addLine(to: CGPoint(x: l + dx * CGFloat(i), y: b))
        }
        for j in 1..<ny where j * 2 != ny {
            faint.move(to: CGPoint(x: l, y: t + dy * CGFloat(j)))
            faint.addLine(to: CGPoint(x: r, y: t + dy * CGFloat(j)))
        }
        ctx.stroke(faint, with: .color(Color(argb: ink, alpha: 0.17)), lineWidth: 0.7)

        var bold = Path(CGRect(x: l, y: t, width: r - l, height: b - t))
        bold.move(to: CGPoint(x: mx, y: t))
        bold.addLine(to: CGPoint(x: mx, y: b))
        bold.move(to: CGPoint(x: l, y: my))
        bold.addLine(to: CGPoint(x: r, y: my))
        let tick = min(dx, dy) * 0.07
        for i in 1..<(nx * 5) where i % 5 != 0 {
            let x = l + dx * CGFloat(i) / 5
            bold.move(to: CGPoint(x: x, y: my - tick))
            bold.addLine(to: CGPoint(x: x, y: my + tick))
        }
        for j in 1..<(ny * 5) where j % 5 != 0 {
            let y = t + dy * CGFloat(j) / 5
            bold.move(to: CGPoint(x: mx - tick, y: y))
            bold.addLine(to: CGPoint(x: mx + tick, y: y))
        }
        ctx.stroke(bold, with: .color(Color(argb: ink, alpha: 0.3)), lineWidth: 0.7)
    }

    /// A tube's face is curved, so its corners sit further from the eye and a
    /// shade darker; and there is a dark rim where the bezel meets the glass.
    private func drawGlass(_ ctx: GraphicsContext, _ face: CGRect, _ radius: CGFloat) {
        let shape = Path(roundedRect: face, cornerRadius: radius)
        ctx.fill(shape, with: .radialGradient(
            Gradient(stops: [
                .init(color: .black.opacity(0), location: 0),
                .init(color: .black.opacity(0), location: 0.55),
                .init(color: .black.opacity(0.36), location: 1),
            ]),
            center: CGPoint(x: face.midX, y: face.midY), startRadius: 0,
            endRadius: hypot(face.width, face.height) / 2))
        ctx.stroke(shape, with: .color(.black.opacity(0.35)), lineWidth: 1)
    }

    private func drawReadout(_ ctx: GraphicsContext, _ lay: CrtLayout, _ f: AnalysisFrame, _ s: Settings,
                             _ tube: Crt, _ tint: UInt32, _ palette: Palette) {
        let y = CGFloat(lay.readoutY)
        guard !y.isNaN else { return }
        let scale = "\(oscFullScaleLabel(s.oscZoom)) FS/div"
        let left = s.oscMode == .xy ? "X-Y · \(scale)" : "\(oscTimeLabel(s.oscTimeDivMs))/div · \(scale)"
        // A mono input has no picture to draw in X-Y: L and R are the same, so
        // the beam can only run up the diagonal. Say so rather than look broken.
        let mono = f.waveR.isEmpty
        let right = mono ? "mono input" : (s.oscMode == .xy ? "L → X · R → Y" : (tube.triggered ? "trig ↑ mix" : "auto"))
        let font = Font.system(size: 10, weight: .bold, design: .monospaced)
        let leftText = ctx.resolve(Text(left).font(font).foregroundStyle(Color(argb: tint, alpha: 0.9)))
        let rightText = ctx.resolve(Text(right).font(font).foregroundStyle(mono ? Color(argb: palette.warn) : Color(argb: tint, alpha: 0.9)))
        // Under the graticule's edges when they are far enough apart, else out to
        // the glass's, and the second one goes if even that is too tight.
        let need = leftText.measure(in: .zero).width + rightText.measure(in: .zero).width + 12
        var l = CGFloat(lay.gratL), r = CGFloat(lay.gratR)
        if r - l < need {
            l = CGFloat(lay.faceL) + 10
            r = CGFloat(lay.faceR) - 10
        }
        ctx.draw(leftText, at: CGPoint(x: l, y: y), anchor: .bottomLeading)
        if r - l >= need { ctx.draw(rightText, at: CGPoint(x: r, y: y), anchor: .bottomTrailing) }
    }

    /// How bright the graticule's tint and the readout are, in the phosphor's own terms.
    private static let tintEnergy: Float = 1.4
    private static let graticuleGrey: UInt32 = 0xFF8C9690
}
