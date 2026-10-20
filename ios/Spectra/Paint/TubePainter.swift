import SwiftUI
import SpectraCore

/// The Oscilloscope page: the simulated tube (Crt) seen through a graticule.
/// Placeholder until the Crt port lands; then this drives Crt + CrtLayout
/// the way VizPainter.kt's drawOscilloscope does.
@MainActor
final class TubePainter {
    func draw(_ ctx: GraphicsContext, area: CGRect, frame: AnalysisFrame, settings s: Settings, palette: Palette) {
        let side = min(area.width, area.height - 30)
        let face = CGRect(x: area.midX - side / 2, y: area.minY, width: side, height: side)
        ctx.fill(Path(roundedRect: face, cornerRadius: 14), with: .color(.black))
        ctx.draw(Text("tube coming").font(.system(size: 12, weight: .medium)).foregroundStyle(.green),
                 at: CGPoint(x: face.midX, y: face.midY))
    }
}
