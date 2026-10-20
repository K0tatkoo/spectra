import SwiftUI
import SpectraCore

/// Every visualisation — VizPainter.kt, drawn with SwiftUI's GraphicsContext.
///
/// The geometry, colours, labels and wording follow the Android painter, so a
/// graph reads the same on both phones; what changed is only how it is drawn.
/// The chrome around it is iOS's own (Liquid Glass); the graphs keep Spectra's
/// palette, so a spectrum is the same colours on Android, Windows and iPhone.
///
/// One instance per visible page: it caches the bin map, the spectrogram's
/// pixels, the goniometer's persistence and the tube, sized for that page.
@MainActor
final class VizPainter {
    var palette: Palette = .darkPalette

    // Cached per-pixel bin mapping for the spectrum.
    private var mapWidth = 0
    private var mapKey = 0
    private var binLo: [Int] = []
    private var binHi: [Int] = []

    private var spectrogramPixels: [UInt32] = []
    private var spectrogramSize = (cols: 0, rows: 0)
    private var spectrogramLastHead = -1
    private var spectrogramLutKey: (ColorMap, Bool)? = nil

    private var gonioPixels: [UInt32] = []
    private var gonioDim = 0

    private var historyScratch = [Float](repeating: 0, count: AudioEngine.bandHistory)
    private var loudnessScratch = [Float](repeating: 0, count: AudioEngine.bandHistory)

    /// The Oscilloscope page's tube (see TubePainter).
    let tube = TubePainter()

    func draw(_ ctx: GraphicsContext, area: CGRect, page: VizPage, frame: AnalysisFrame?, settings s: Settings) {
        guard area.width >= 4, area.height >= 4 else { return }
        var ctx = ctx
        ctx.clip(to: Path(area))
        guard let f = frame else {
            drawIdleText(ctx, area, "no signal", size: 14)
            return
        }
        switch page {
        case .spectrum: drawSpectrum(ctx, area, f, s)
        case .bands: drawBands(ctx, area, f, s)
        case .spectrogram: drawSpectrogram(ctx, area, f, s)
        case .loudness: drawLoudness(ctx, area, f, s)
        case .stereo: drawStereo(ctx, area, f, s)
        case .waveform:
            switch s.waveformMode {
            case .free: drawWaveform(ctx, area, f)
            case .hold: drawHold(ctx, area, f)
            case .nes:
                var o = NesOptions()
                o.region = s.nesRegion
                drawNesTriangle(ctx, area, f.nes, o)
            }
        case .oscilloscope: tube.draw(ctx, area: area, frame: f, settings: s, palette: palette)
        case .stems: drawStems(ctx, area, f, s)
        }
        if f.clipped { drawClipFlag(ctx, area, centred: page == .stems) }
    }

    /// Top right, except on the Stems page, where the top lane's readout sits
    /// there: top centre, between the lane's name and its note.
    private func drawClipFlag(_ ctx: GraphicsContext, _ area: CGRect, centred: Bool) {
        let h: CGFloat = 16, w: CGFloat = 40
        let x = centred ? area.midX - w / 2 : area.maxX - w - 4
        let r = CGRect(x: x, y: area.minY + (centred ? 0 : 4), width: w, height: h)
        ctx.fill(Path(roundedRect: r, cornerRadius: h / 2), with: .color(c(palette.bad)))
        ctx.draw(Text("CLIP").font(.system(size: h * 0.62, weight: .semibold)).foregroundStyle(.white),
                 at: CGPoint(x: r.midX, y: r.midY))
    }

    // ---- spectrum ----------------------------------------------------------

    private func drawSpectrum(_ ctx: GraphicsContext, _ area: CGRect, _ f: AnalysisFrame, _ s: Settings) {
        let labelPad: CGFloat = s.showLabels ? 16 : 0
        let plot = CGRect(x: area.minX, y: area.minY, width: area.width, height: area.height - labelPad)
        if s.showGrid { drawSpectrumGrid(ctx, plot, s) }

        let plotWidth = Int(plot.width.rounded())
        guard plotWidth > 1 else { return }
        ensureBinMap(plotWidth, f, s)
        let w = binLo.count
        guard w > 1 else { return }

        let bottom = plot.maxY
        switch s.spectrumStyle {
        case .bars:
            let barW = max(2, plot.width / 96)
            let gap = barW * 0.35
            var bars = Path()
            var x = plot.minX
            while x < plot.maxX {
                let i0 = coerceIn(Int((x - plot.minX).rounded()), 0, w - 1)
                let i1 = coerceIn(Int((x + barW - plot.minX).rounded()), 0, w - 1)
                var db = PeakHold.floorDb
                for i in i0...max(i0, i1) { db = max(db, magnitudeAt(f, i)) }
                let y = yForDb(db, plot, s)
                bars.addRoundedRect(in: CGRect(x: x, y: y, width: barW - gap, height: bottom - y),
                                    cornerSize: CGSize(width: barW * 0.25, height: barW * 0.25))
                x += barW
            }
            ctx.fill(bars, with: spectrumShading(plot))
        case .line, .filled:
            var line = Path()
            for i in 0..<w {
                let p = CGPoint(x: plot.minX + CGFloat(i), y: yForDb(magnitudeAt(f, i), plot, s))
                if i == 0 { line.move(to: p) } else { line.addLine(to: p) }
            }
            if s.spectrumStyle == .filled {
                var fill = line
                fill.addLine(to: CGPoint(x: plot.minX + CGFloat(w - 1), y: bottom))
                fill.addLine(to: CGPoint(x: plot.minX, y: bottom))
                fill.closeSubpath()
                ctx.fill(fill, with: spectrumShading(plot))
            }
            // Re-stroke the top edge: a filled area alone loses the fine
            // detail that makes a resonance visible.
            ctx.stroke(line, with: .color(c(palette.accent2)), lineWidth: 1.6)
        }

        if s.peakHold && !f.peakDb.isEmpty {
            var peak = Path()
            for i in 0..<w {
                var db = PeakHold.floorDb
                for b in binLo[i]...binHi[i] where b < f.peakDb.count { db = max(db, f.peakDb[b]) }
                let p = CGPoint(x: plot.minX + CGFloat(i), y: yForDb(db, plot, s))
                if i == 0 { peak.move(to: p) } else { peak.addLine(to: p) }
            }
            ctx.stroke(peak, with: .color(c(palette.text, 0.45)), lineWidth: 1)
        }

        if s.showLabels { drawFrequencyLabels(ctx, plot, area, s) }
    }

    private func spectrumShading(_ plot: CGRect) -> GraphicsContext.Shading {
        .linearGradient(
            Gradient(stops: [
                .init(color: c(palette.accent2, 0.95), location: 0),
                .init(color: c(palette.accent, 0.75), location: 0.55),
                .init(color: c(palette.accentFill, 0.25), location: 1),
            ]),
            startPoint: CGPoint(x: 0, y: plot.minY), endPoint: CGPoint(x: 0, y: plot.maxY)
        )
    }

    private func magnitudeAt(_ f: AnalysisFrame, _ i: Int) -> Float {
        var db = PeakHold.floorDb
        let hi = min(binHi[i], f.magnitudesDb.count - 1)
        if binLo[i] <= hi { for b in binLo[i]...hi { db = max(db, f.magnitudesDb[b]) } }
        return db
    }

    /// Maps every pixel column to the FFT bins behind it, once per resize.
    ///
    /// Taking the maximum of the bins under a pixel rather than the mean is
    /// deliberate: at 20 kHz on a log axis a single pixel can span forty bins,
    /// and a mean turns every narrow peak into a shrug.
    private func ensureBinMap(_ width: Int, _ f: AnalysisFrame, _ s: Settings) {
        let key = ((f.fftSize &* 31 &+ Int(s.minHz)) &* 31 &+ Int(s.maxHz)) &+ (s.freqScale == .log ? 0 : 7) &+ f.sampleRate
        if width == mapWidth && key == mapKey { return }
        mapWidth = width
        mapKey = key
        binLo = [Int](repeating: 0, count: width)
        binHi = [Int](repeating: 0, count: width)
        let maxBin = f.magnitudesDb.count - 1
        for x in 0..<width {
            let f0 = freqForFraction(Float(x) / Float(width), s)
            let f1 = freqForFraction(Float(x + 1) / Float(width), s)
            var b0 = ktToInt(f0 / f.binHz)
            var b1 = ktToInt(f1 / f.binHz)
            if b1 < b0 { b1 = b0 }
            b0 = coerceIn(b0, 0, maxBin)
            b1 = coerceIn(b1, b0, maxBin)
            binLo[x] = b0
            binHi[x] = b1
        }
    }

    private func freqForFraction(_ t: Float, _ s: Settings) -> Float {
        let lo = max(s.minHz, 1)
        let hi = max(s.maxHz, lo + 1)
        return s.freqScale == .log ? lo * exp(t * log(hi / lo)) : lo + (hi - lo) * t
    }

    private func fractionForFreq(_ freq: Float, _ s: Settings) -> Float {
        let lo = max(s.minHz, 1)
        let hi = max(s.maxHz, lo + 1)
        return s.freqScale == .log ? log(freq / lo) / log(hi / lo) : (freq - lo) / (hi - lo)
    }

    private func yForDb(_ db: Float, _ plot: CGRect, _ s: Settings) -> CGFloat {
        let span = max(s.ceilingDb - s.floorDb, 1)
        let t = coerceIn((db - s.floorDb) / span, 0, 1)
        return plot.maxY - CGFloat(t) * plot.height
    }

    private func drawSpectrumGrid(_ ctx: GraphicsContext, _ plot: CGRect, _ s: Settings) {
        var grid = Path()
        for freq in Self.gridFreqs where freq >= s.minHz && freq <= s.maxHz {
            let x = plot.minX + CGFloat(fractionForFreq(freq, s)) * plot.width
            grid.move(to: CGPoint(x: x, y: plot.minY))
            grid.addLine(to: CGPoint(x: x, y: plot.maxY))
        }
        var db = s.ceilingDb
        while db >= s.floorDb {
            let y = yForDb(db, plot, s)
            grid.move(to: CGPoint(x: plot.minX, y: y))
            grid.addLine(to: CGPoint(x: plot.maxX, y: y))
            db -= 20
        }
        ctx.stroke(grid, with: .color(c(palette.line, palette.isDark ? 0.9 : 0.8)), lineWidth: 0.8)

        guard s.showLabels else { return }
        var d = s.ceilingDb
        while d >= s.floorDb {
            let y = yForDb(d, plot, s)
            if y > plot.minY + 9 {
                ctx.draw(label("\(ktToInt(d))", 9, palette.textFaint), at: CGPoint(x: plot.minX + 3, y: y - 2), anchor: .bottomLeading)
            }
            d -= 20
        }
    }

    private func drawFrequencyLabels(_ ctx: GraphicsContext, _ plot: CGRect, _ area: CGRect, _ s: Settings) {
        for freq in Self.gridFreqs where freq >= s.minHz && freq <= s.maxHz {
            let x = plot.minX + CGFloat(fractionForFreq(freq, s)) * plot.width
            if x < plot.minX + 10 || x > plot.maxX - 10 { continue }
            ctx.draw(label(freqLabel(freq), 9, palette.textFaint), at: CGPoint(x: x, y: area.maxY - 2), anchor: .bottom)
        }
    }

    private func freqLabel(_ f: Float) -> String {
        if f >= 1000 {
            let k = f / 1000
            return k == Float(Int(k)) ? "\(Int(k))k" : "\(Float(ktRoundToInt(k * 10)) / 10)k"
        }
        return "\(Int(f))"
    }

    // ---- bands -------------------------------------------------------------

    /// The independent per-band graphs: one row per band — history on the left,
    /// live bar and readout on the right — because that is where a band's
    /// *shape over time* is legible. A short area drops to vertical bars.
    private func drawBands(_ ctx: GraphicsContext, _ area: CGRect, _ f: AnalysisFrame, _ s: Settings) {
        let bands = f.bands.enumerated().filter { i, _ in i < s.bands.count ? s.bands[i].enabled : true }.map(\.element)
        guard !bands.isEmpty else { return }

        if area.height < 150 || bands.count > 9 {
            let gap: CGFloat = 3
            let slot = (area.width - gap * CGFloat(bands.count - 1)) / CGFloat(bands.count)
            var x = area.minX
            for band in bands {
                let r = CGRect(x: x, y: area.minY, width: slot, height: area.height - 12)
                drawVerticalBandBar(ctx, r, band.rmsDb, band.holdDb, s)
                ctx.draw(label(shortName(band.name), 9, palette.textFaint), at: CGPoint(x: r.midX, y: area.maxY - 1), anchor: .bottom)
                x += slot + gap
            }
            return
        }

        let rowGap: CGFloat = 8
        let rowH = (area.height - rowGap * CGFloat(bands.count - 1)) / CGFloat(bands.count)
        var y = area.minY
        let labelW: CGFloat = 64
        let readoutW: CGFloat = 50
        for band in bands {
            let row = CGRect(x: area.minX, y: y, width: area.width, height: rowH)
            ctx.draw(label(band.name, 11, palette.textDim), at: CGPoint(x: row.minX, y: row.midY - 1), anchor: .bottomLeading)
            ctx.draw(label("\(Int(band.lowHz))–\(freqLabel(band.highHz))", 8.5, palette.textFaint),
                     at: CGPoint(x: row.minX, y: row.midY + 1), anchor: .topLeading)
            let graph = CGRect(x: row.minX + labelW, y: row.minY, width: row.width - labelW - readoutW, height: row.height)
            drawBandHistory(ctx, graph, band.history, s)
            drawBandLevelBar(ctx, CGRect(x: graph.maxX + 4, y: row.minY, width: readoutW - 4, height: row.height),
                             band.rmsDb, band.holdDb, s)
            y += rowH + rowGap
        }
    }

    private func shortName(_ name: String) -> String {
        name.count <= 5 ? name : name.split(separator: " ").map { String($0.prefix(1)).uppercased() }.joined()
    }

    private func drawBandHistory(_ ctx: GraphicsContext, _ r: CGRect, _ history: ScrollBuffer, _ s: Settings) {
        guard r.width >= 4 else { return }
        let n = min(historyScratch.count, history.capacity)
        if historyScratch.count != history.capacity { historyScratch = [Float](repeating: 0, count: history.capacity) }
        history.snapshot(&historyScratch)
        var p = Path()
        p.move(to: CGPoint(x: r.minX, y: r.maxY))
        for i in 0..<n {
            p.addLine(to: CGPoint(x: r.minX + r.width * CGFloat(i) / CGFloat(n - 1), y: yForDb(historyScratch[i], r, s)))
        }
        p.addLine(to: CGPoint(x: r.maxX, y: r.maxY))
        p.closeSubpath()
        ctx.fill(p, with: .linearGradient(
            Gradient(colors: [c(palette.accent, 0.85), c(palette.accentFill, 0.1)]),
            startPoint: CGPoint(x: 0, y: r.minY), endPoint: CGPoint(x: 0, y: r.maxY)))
    }

    private var levelGradient: Gradient {
        Gradient(stops: [
            .init(color: c(palette.bad), location: 0),
            .init(color: c(palette.warn), location: 0.18),
            .init(color: c(palette.accent2), location: 0.55),
            .init(color: c(palette.accent), location: 1),
        ])
    }

    private func drawBandLevelBar(_ ctx: GraphicsContext, _ r: CGRect, _ rmsDb: Float, _ holdDb: Float, _ s: Settings) {
        let trackW = min(r.width * 0.42, 10)
        let track = CGRect(x: r.minX, y: r.minY, width: trackW, height: r.height)
        ctx.fill(Path(roundedRect: track, cornerRadius: trackW / 2), with: .color(c(palette.bgDeep)))
        let y = yForDb(rmsDb, track, s)
        ctx.fill(Path(roundedRect: CGRect(x: track.minX, y: y, width: trackW, height: track.maxY - y), cornerRadius: trackW / 2),
                 with: .linearGradient(levelGradient, startPoint: CGPoint(x: 0, y: track.minY), endPoint: CGPoint(x: 0, y: track.maxY)))
        let hy = yForDb(holdDb, track, s)
        ctx.fill(Path(CGRect(x: track.minX, y: hy - 1, width: trackW, height: 2)), with: .color(c(palette.text)))
        ctx.draw(mono(dbText(rmsDb), 10, palette.textDim), at: CGPoint(x: r.maxX, y: r.midY), anchor: .trailing)
    }

    private func drawVerticalBandBar(_ ctx: GraphicsContext, _ r: CGRect, _ rmsDb: Float, _ holdDb: Float, _ s: Settings) {
        let radius = min(r.width, 6) / 2
        ctx.fill(Path(roundedRect: r, cornerRadius: radius), with: .color(c(palette.bgDeep)))
        let y = yForDb(rmsDb, r, s)
        ctx.fill(Path(roundedRect: CGRect(x: r.minX, y: y, width: r.width, height: r.maxY - y), cornerRadius: radius),
                 with: .linearGradient(levelGradient, startPoint: CGPoint(x: 0, y: r.minY), endPoint: CGPoint(x: 0, y: r.maxY)))
        let hy = yForDb(holdDb, r, s)
        ctx.fill(Path(CGRect(x: r.minX, y: hy - 1, width: r.width, height: 2)), with: .color(c(palette.text)))
    }

    // ---- spectrogram -------------------------------------------------------

    private func drawSpectrogram(_ ctx: GraphicsContext, _ area: CGRect, _ f: AnalysisFrame, _ s: Settings) {
        let buffer = f.spectrogram
        let rows = buffer.rows, cols = buffer.columns
        if spectrogramSize.cols != cols || spectrogramSize.rows != rows {
            spectrogramPixels = [UInt32](repeating: palette.bgDeep, count: rows * cols)
            spectrogramSize = (cols, rows)
            spectrogramLastHead = -1
        }
        if spectrogramLutKey == nil || spectrogramLutKey! != (s.colorMap, palette.isDark) {
            spectrogramLutKey = (s.colorMap, palette.isDark)
            spectrogramLastHead = -1
        }
        let lut = palette.colorMap(s.colorMap)

        // Only convert the columns that arrived since the last draw. A full
        // rebuild is 128 k pixels; a frame usually adds one or two columns.
        let head = buffer.head
        let from = spectrogramLastHead
        if from < 0 {
            convertColumns(buffer, 0, cols, lut)
        } else if from != head {
            var col = from
            while col != head {
                convertColumns(buffer, col, 1, lut)
                col = (col + 1) % cols
            }
        }
        spectrogramLastHead = head

        guard let image = makeImage(spectrogramPixels, width: cols, height: rows) else { return }
        // Unwrap the ring into two draws, oldest on the left.
        let sx = area.width / CGFloat(cols)
        let firstWidth = cols - head
        if firstWidth > 0, let part = image.cropping(to: CGRect(x: head, y: 0, width: firstWidth, height: rows)) {
            ctx.draw(Image(decorative: part, scale: 1).interpolation(.medium).resizable(),
                     in: CGRect(x: area.minX, y: area.minY, width: CGFloat(firstWidth) * sx, height: area.height))
        }
        if head > 0, let part = image.cropping(to: CGRect(x: 0, y: 0, width: head, height: rows)) {
            ctx.draw(Image(decorative: part, scale: 1).interpolation(.medium).resizable(),
                     in: CGRect(x: area.minX + CGFloat(firstWidth) * sx, y: area.minY, width: CGFloat(head) * sx, height: area.height))
        }

        guard s.showLabels else { return }
        for freq in Self.gridFreqs where freq >= s.minHz && freq <= s.maxHz {
            let t = CGFloat(fractionForFreq(freq, s))
            let y = area.maxY - t * area.height
            if y < area.minY + 9 || y > area.maxY - 2 { continue }
            ctx.draw(label(freqLabel(freq), 8.5, palette.text, 0.75), at: CGPoint(x: area.minX + 3, y: y - 1.5), anchor: .bottomLeading)
        }
    }

    private func convertColumns(_ buffer: SpectrogramBuffer, _ startColumn: Int, _ count: Int, _ lut: [UInt32]) {
        let rows = buffer.rows, cols = buffer.columns
        spectrogramPixels.withUnsafeMutableBufferPointer { px in
            lut.withUnsafeBufferPointer { l in
                for n in 0..<count {
                    let col = (startColumn + n) % cols
                    let base = col * rows
                    for r in 0..<rows {
                        // Row 0 of the buffer is the lowest frequency; row 0 of the
                        // image is the top of the picture, so the axis flips here.
                        px[(rows - 1 - r) * cols + col] = l[Int(buffer.data[base + r])]
                    }
                }
            }
        }
    }

    // ---- loudness ----------------------------------------------------------

    private func drawLoudness(_ ctx: GraphicsContext, _ area: CGRect, _ f: AnalysisFrame, _ s: Settings) {
        let l = f.loudness
        // Capped, or on a tall phone the labels drift far from their numbers.
        let topH = min(area.height * 0.38, 118)
        let top = CGRect(x: area.minX, y: area.minY, width: area.width, height: topH)
        let cellW = top.width / 4
        func cell(_ i: CGFloat) -> CGRect { CGRect(x: top.minX + cellW * i, y: top.minY, width: cellW, height: top.height) }
        drawStat(ctx, cell(0), "M", lufsText(l.momentary), "LUFS")
        drawStat(ctx, cell(1), "S", lufsText(l.shortTerm), "LUFS")
        drawStat(ctx, cell(2), "I", lufsText(l.integrated), "LUFS")
        drawStat(ctx, cell(3), "TP", l.truePeakDb <= -140 ? "—" : String(format: "%.1f", l.truePeakDb), "dBTP",
                 valueColor: l.truePeakDb > -1 ? palette.bad : palette.text)

        let mid = CGRect(x: area.minX, y: top.maxY + 4, width: area.width, height: area.height - topH - 4 - 44)
        drawLoudnessHistory(ctx, mid, s)

        let bottom = CGRect(x: area.minX, y: area.maxY - 22, width: area.width, height: 22)
        drawLoudnessBar(ctx, bottom, l.momentary, l.shortTerm, s)

        let delta = l.integrated <= -70 ? "—" : String(format: "%+.1f LU", l.integrated - s.loudnessTargetLufs)
        ctx.draw(label("target \(Int(s.loudnessTargetLufs)) LUFS · Δ \(delta) · LRA \(String(format: "%.1f", l.range)) LU", 9.5, palette.textFaint),
                 at: CGPoint(x: area.minX, y: area.maxY - 26), anchor: .bottomLeading)
    }

    private func drawStat(_ ctx: GraphicsContext, _ r: CGRect, _ name: String, _ value: String, _ unit: String, valueColor: UInt32? = nil) {
        ctx.draw(label(name, 10, palette.textFaint), at: CGPoint(x: r.midX, y: r.minY + 2), anchor: .top)
        ctx.draw(mono(value, min(26, r.width * 0.3), valueColor ?? palette.text), at: CGPoint(x: r.midX, y: r.midY))
        ctx.draw(label(unit, 8.5, palette.textFaint), at: CGPoint(x: r.midX, y: r.maxY - 2), anchor: .bottom)
    }

    private func drawLoudnessHistory(_ ctx: GraphicsContext, _ r: CGRect, _ s: Settings) {
        guard r.height >= 12 else { return }
        AudioEngine.shared.loudnessHistory.snapshot(&loudnessScratch)
        let n = loudnessScratch.count
        let lo: Float = -40, hi: Float = 0
        func yFor(_ v: Float) -> CGFloat { r.maxY - CGFloat((coerceIn(v, lo, hi) - lo) / (hi - lo)) * r.height }

        var target = Path()
        let ty = yFor(s.loudnessTargetLufs)
        target.move(to: CGPoint(x: r.minX, y: ty))
        target.addLine(to: CGPoint(x: r.maxX, y: ty))
        ctx.stroke(target, with: .color(c(palette.accent, 0.55)), lineWidth: 0.8)

        var p = Path()
        p.move(to: CGPoint(x: r.minX, y: r.maxY))
        for i in 0..<n { p.addLine(to: CGPoint(x: r.minX + r.width * CGFloat(i) / CGFloat(n - 1), y: yFor(loudnessScratch[i]))) }
        p.addLine(to: CGPoint(x: r.maxX, y: r.maxY))
        p.closeSubpath()
        ctx.fill(p, with: .linearGradient(Gradient(colors: [c(palette.accent2, 0.7), c(palette.accent2, 0.05)]),
                                          startPoint: CGPoint(x: 0, y: r.minY), endPoint: CGPoint(x: 0, y: r.maxY)))
    }

    private func drawLoudnessBar(_ ctx: GraphicsContext, _ r: CGRect, _ momentary: Float, _ shortTerm: Float, _ s: Settings) {
        let lo: Float = -40, hi: Float = 0
        func xFor(_ v: Float) -> CGFloat { r.minX + CGFloat((coerceIn(v, lo, hi) - lo) / (hi - lo)) * r.width }
        let h = min(r.height, 14)
        let track = CGRect(x: r.minX, y: r.midY - h / 2, width: r.width, height: h)
        ctx.fill(Path(roundedRect: track, cornerRadius: h / 2), with: .color(c(palette.bgDeep)))
        ctx.fill(Path(roundedRect: CGRect(x: track.minX, y: track.minY, width: xFor(momentary) - track.minX, height: h), cornerRadius: h / 2),
                 with: .linearGradient(Gradient(stops: [
                    .init(color: c(palette.accent), location: 0),
                    .init(color: c(palette.accent2), location: 0.55),
                    .init(color: c(palette.warn), location: 0.82),
                    .init(color: c(palette.bad), location: 1),
                 ]), startPoint: CGPoint(x: track.minX, y: 0), endPoint: CGPoint(x: track.maxX, y: 0)))
        let sx = xFor(shortTerm)
        ctx.fill(Path(CGRect(x: sx - 1, y: track.minY - 2, width: 2, height: h + 4)), with: .color(c(palette.text)))
        let tx = xFor(s.loudnessTargetLufs)
        ctx.fill(Path(CGRect(x: tx - 1, y: track.minY - 3, width: 2, height: h + 6)), with: .color(c(palette.good, 0.9)))
    }

    // ---- stereo ------------------------------------------------------------

    private func drawStereo(_ ctx: GraphicsContext, _ area: CGRect, _ f: AnalysisFrame, _ s: Settings) {
        if f.gonio.isEmpty {
            drawIdleText(ctx, area, "mono source — no stereo image", size: 12)
            return
        }
        let size = min(area.width, area.height - 34)
        let cx = area.midX
        let cy = area.minY + size / 2
        let radius = size / 2 - 4

        // Persistence needs somewhere to persist, so the cloud is composited on
        // its own pixels that are faded rather than cleared.
        let dim = max(8, Int((size * 2).rounded()))
        if dim != gonioDim {
            gonioDim = dim
            gonioPixels = [UInt32](repeating: palette.bgDeep, count: dim * dim)
        }
        // Fading towards the well colour instead of clearing is what leaves the
        // familiar smear behind a transient.
        let fade = coerceIn(1 - s.goniometerPersistence, 0.02, 1)
        let well = palette.bgDeep
        let dot = Palette.withAlpha(palette.accent2, 0.9)
        gonioPixels.withUnsafeMutableBufferPointer { px in
            for i in px.indices { px[i] = Palette.mix(px[i], well, fade) }
            let r2 = Float(dim) / 2
            var i = 0
            while i < f.gonio.count - 1 {
                let gx = ktToInt(r2 + f.gonio[i] * r2 * 0.92)
                let gy = ktToInt(r2 - f.gonio[i + 1] * r2 * 0.92)
                // A 2×2 dot at twice the page's resolution, about 1 pt.
                for dy in 0...1 { for dx in 0...1 {
                    let x = gx + dx, y = gy + dy
                    if x >= 0, y >= 0, x < dim, y < dim { px[y * dim + x] = Palette.mix(px[y * dim + x], dot | 0xFF000000, 0.9) }
                } }
                i += 2
            }
        }

        let circle = Path(ellipseIn: CGRect(x: cx - radius, y: cy - radius, width: radius * 2, height: radius * 2))
        if let image = makeImage(gonioPixels, width: dim, height: dim) {
            var clipped = ctx
            clipped.clip(to: circle)
            clipped.draw(Image(decorative: image, scale: 1).interpolation(.medium).resizable(),
                         in: CGRect(x: cx - radius, y: cy - radius, width: radius * 2, height: radius * 2))
        }
        var guides = circle
        guides.move(to: CGPoint(x: cx - radius * 0.7, y: cy + radius * 0.7))
        guides.addLine(to: CGPoint(x: cx + radius * 0.7, y: cy - radius * 0.7))
        guides.move(to: CGPoint(x: cx - radius * 0.7, y: cy - radius * 0.7))
        guides.addLine(to: CGPoint(x: cx + radius * 0.7, y: cy + radius * 0.7))
        ctx.stroke(guides, with: .color(c(palette.line)), lineWidth: 0.8)

        let bar = CGRect(x: area.minX, y: area.maxY - 24, width: area.width, height: 12)
        drawCorrelationMeter(ctx, bar, f.correlation)
        ctx.draw(label("corr \(String(format: "%+.2f", f.correlation)) · S/M \(String(format: "%.2f", f.stereoWidth))", 9.5, palette.textFaint),
                 at: CGPoint(x: area.minX, y: area.maxY), anchor: .bottomLeading)
    }

    private func drawCorrelationMeter(_ ctx: GraphicsContext, _ r: CGRect, _ correlation: Float) {
        ctx.fill(Path(roundedRect: r, cornerRadius: r.height / 2), with: .color(c(palette.bgDeep)))
        let centre = r.midX
        let x = centre + CGFloat(coerceIn(correlation, -1, 1)) * r.width / 2
        let tone = correlation < -0.2 ? palette.bad : (correlation < 0.4 ? palette.warn : palette.good)
        ctx.fill(Path(roundedRect: CGRect(x: min(centre, x), y: r.minY, width: abs(x - centre), height: r.height), cornerRadius: r.height / 2),
                 with: .color(c(tone)))
        ctx.fill(Path(CGRect(x: centre - 0.7, y: r.minY, width: 1.4, height: r.height)), with: .color(c(palette.text, 0.6)))
    }

    // ---- waveform ----------------------------------------------------------

    private func drawWaveform(_ ctx: GraphicsContext, _ area: CGRect, _ f: AnalysisFrame) {
        if !f.waveR.isEmpty {
            let gap: CGFloat = 6
            let h = (area.height - gap) / 2
            drawTrace(ctx, CGRect(x: area.minX, y: area.minY, width: area.width, height: h), f.waveL, palette.accent)
            drawTrace(ctx, CGRect(x: area.minX, y: area.minY + h + gap, width: area.width, height: h), f.waveR, palette.accent2)
        } else {
            drawTrace(ctx, area, f.waveL, palette.accent)
        }
    }

    private func drawTrace(_ ctx: GraphicsContext, _ r: CGRect, _ samples: [Float], _ color: UInt32) {
        guard !samples.isEmpty else { return }
        var axis = Path()
        axis.move(to: CGPoint(x: r.minX, y: r.midY))
        axis.addLine(to: CGPoint(x: r.maxX, y: r.midY))
        ctx.stroke(axis, with: .color(c(palette.line, 0.9)), lineWidth: 0.7)
        var p = Path()
        let half = r.height / 2
        for (i, v) in samples.enumerated() {
            let pt = CGPoint(x: r.minX + r.width * CGFloat(i) / CGFloat(samples.count - 1),
                             y: r.midY - CGFloat(coerceIn(v, -1, 1)) * half * 0.94)
            if i == 0 { p.move(to: pt) } else { p.addLine(to: pt) }
        }
        ctx.stroke(p, with: .color(c(color)), style: StrokeStyle(lineWidth: 1.5, lineJoin: .round))
    }

    // ---- held-still scopes ------------------------------------------------

    /// The Stems page: one held-still lane per separated stem, stacked, each in
    /// its own colour — the chiptune channel-scope look, applied to a real mix.
    private func drawStems(_ ctx: GraphicsContext, _ area: CGRect, _ f: AnalysisFrame, _ s: Settings) {
        let lanes = f.scopes?.stems ?? []
        let info = f.scopes?.stemsInfo
        if lanes.isEmpty {
            let message: String = switch info?.state {
            case nil: "starting the stem model…"
            case .failed?: info?.message ?? "stem separation failed"
            case .loading?: "loading the stem model…"
            case .running?: "separating…"
            }
            drawWrappedText(ctx, area, message, bad: info?.state == .failed)
            return
        }
        let footerH: CGFloat = info == nil ? 0 : 16
        let body = CGRect(x: area.minX, y: area.minY, width: area.width, height: area.height - footerH)
        let gap: CGFloat = 8
        let laneH = (body.height - gap * CGFloat(lanes.count - 1)) / CGFloat(lanes.count)
        var y = body.minY
        for (i, lane) in lanes.enumerated() {
            let r = CGRect(x: body.minX, y: y, width: body.width, height: laneH)
            drawScopeLane(ctx, r, lane.name, lane.trace, laneColor(lane.slot))
            if i < lanes.count - 1 {
                var sep = Path()
                sep.move(to: CGPoint(x: r.minX, y: r.maxY + gap / 2))
                sep.addLine(to: CGPoint(x: r.maxX, y: r.maxY + gap / 2))
                ctx.stroke(sep, with: .color(c(palette.line, 0.9)), lineWidth: 0.8)
            }
            y += laneH + gap
        }
        if let info { drawStemsFooter(ctx, CGRect(x: area.minX, y: body.maxY, width: area.width, height: footerH), info, s) }
    }

    /// Each stem its own colour, whichever lanes are showing. Synth has no
    /// token of its own; its pink clears 4.5:1 against the page in both themes.
    /// `slot` is the Stem's position in Stem.kt: drums, bass, vocals, other, synth.
    private func laneColor(_ slot: Int) -> UInt32 {
        switch slot {
        case 2: palette.accent2
        case 3: palette.accent
        case 1: palette.warn
        case 4: palette.isDark ? Self.synthDark : Self.synthLight
        default: palette.good
        }
    }

    private func drawStemsFooter(_ ctx: GraphicsContext, _ r: CGRect, _ info: StemsInfo, _ s: Settings) {
        let load = String(format: "%.2f", info.load)
        let synth = info.synth
        // Above 1 the phone separates a second of music in more than a second.
        // It still works — the separator skips to the present — but say so,
        // rather than let a stutter look like a bug.
        let slow = info.load > 1 || (synth.map { $0.load > 1 } ?? false)
        let left: String
        if synth?.state == .failed { left = synth?.message ?? "synth splitting failed" }
        else if slow { left = "slower than the music — skipping" }
        else if synth != nil { left = "on this iPhone · \(String(format: "%.1f", info.msPerHop)) ms per 2.9 ms" }
        else { left = "separated on this iPhone · \(String(format: "%.1f", info.msPerHop)) ms per 2.9 ms" }
        let right: String
        if synth?.state == .running, let synth { right = "load \(load) · synth \(String(format: "%.2f", synth.load))" }
        else if slow { right = "load \(load)" }
        else { right = "load \(load) · \(ktRoundToInt(s.scopeWindowMs)) ms" }
        let rightText = ctx.resolve(mono(right, 9, palette.textFaint))
        let leftText = ctx.resolve(mono(left, 9, slow || synth?.state == .failed ? palette.warn : palette.textFaint))
        ctx.draw(rightText, at: CGPoint(x: r.maxX, y: r.maxY - 2), anchor: .bottomTrailing)
        // Whatever does not fit beside the load readout is dropped, not drawn over it.
        let room = r.width - rightText.measure(in: r.size).width - 10
        if leftText.measure(in: r.size).width <= room {
            ctx.draw(leftText, at: CGPoint(x: r.minX, y: r.maxY - 2), anchor: .bottomLeading)
        }
    }

    /// The Waveform page in "hold still" mode: one lane, the mix, locked to its loudest note.
    private func drawHold(_ ctx: GraphicsContext, _ area: CGRect, _ f: AnalysisFrame) {
        guard let trace = f.scopes?.hold else {
            drawIdleText(ctx, area, "listening for a note to hold", size: 12)
            return
        }
        drawScopeLane(ctx, area, "Mix", trace, palette.accent2, large: true)
    }

    /// One held-still lane: centre line, the wave, and a label saying what it is
    /// locked to — the note while there is one, the hit it froze on otherwise.
    private func drawScopeLane(_ ctx: GraphicsContext, _ r: CGRect, _ name: String, _ trace: ScopeTrace, _ color: UInt32, large: Bool = false) {
        guard r.height >= 4 else { return }
        let labelH: CGFloat = large ? 18 : 14
        let plot = CGRect(x: r.minX, y: r.minY + labelH, width: r.width, height: r.height - labelH)
        let half = plot.height / 2 * 0.92

        var axis = Path()
        axis.move(to: CGPoint(x: plot.minX, y: plot.midY))
        axis.addLine(to: CGPoint(x: plot.maxX, y: plot.midY))
        ctx.stroke(axis, with: .color(c(palette.line, 0.9)), lineWidth: 0.7)

        let pts = trace.points
        if trace.mode != .quiet && pts.count > 1 {
            var p = Path()
            for (i, v) in pts.enumerated() {
                let pt = CGPoint(x: plot.minX + plot.width * CGFloat(i) / CGFloat(pts.count - 1),
                                 y: plot.midY - CGFloat(coerceIn(v, -1.1, 1.1)) * half)
                if i == 0 { p.move(to: pt) } else { p.addLine(to: pt) }
            }
            let alpha: Float = trace.stale ? 0.45 : (trace.mode == .free ? 0.7 : 1)
            ctx.stroke(p, with: .color(c(color, alpha)), style: StrokeStyle(lineWidth: large ? 1.8 : 1.5, lineCap: .round, lineJoin: .round))
        }

        ctx.draw(label(name, large ? 14 : 11, trace.mode == .quiet ? palette.textFaint : color), at: CGPoint(x: r.minX, y: r.minY), anchor: .topLeading)
        let right: String = switch trace.mode {
        case .pitch:
            "\(trace.note) · " + (trace.hz >= 100 ? "\(ktRoundToInt(trace.hz))" : String(format: "%.1f", trace.hz)) + " Hz"
                + (trace.folded > 1 ? " · ×\(trace.folded)" : "")
        case .hit: "hit"
        case .free: "free"
        case .quiet: "quiet"
        }
        ctx.draw(mono(right, large ? 12 : 10, trace.stale || trace.mode == .quiet ? palette.textFaint : palette.textDim),
                 at: CGPoint(x: r.maxX, y: r.minY + 1), anchor: .topTrailing)
    }

    /// A message that may be a whole sentence, wrapped to the area and centred.
    private func drawWrappedText(_ ctx: GraphicsContext, _ area: CGRect, _ message: String, bad: Bool) {
        let text = Text(message).font(.system(size: 13)).foregroundStyle(c(bad ? palette.bad : palette.textFaint))
        let resolved = ctx.resolve(text)
        let box = CGSize(width: area.width - 32, height: area.height)
        let size = resolved.measure(in: box)
        ctx.draw(resolved, in: CGRect(x: area.midX - size.width / 2, y: area.midY - size.height / 2, width: size.width, height: size.height))
    }

    // ---- 2A03 triangle -----------------------------------------------------

    /// The NES triangle view: the captured wave, held still, against the chip's
    /// own 32-step staircase. By the time a NesReading arrives, the horizontal
    /// axis is *phase*, not time — 0 is the first DAC step of a period and the
    /// reading is normalised to the chip's own ±1. That is what lets the ideal
    /// wave be drawn as a fixed shape rather than fitted to the trace.
    private func drawNesTriangle(_ ctx: GraphicsContext, _ area: CGRect, _ reading: NesReading?, _ o: NesOptions) {
        let headerH: CGFloat = 28
        let footerH: CGFloat = 34
        let axisW: CGFloat = 22
        let plot = CGRect(x: area.minX + axisW, y: area.minY + headerH,
                          width: area.width - axisW, height: area.height - headerH - footerH)
        guard plot.width >= 8, plot.height >= 8 else { return }

        let cycles = coerceIn(reading?.cycles ?? o.cycles, 1, 8)
        drawNesGrid(ctx, plot, cycles, o)

        guard let reading else {
            drawIdleText(ctx, plot, "listening for a triangle between \(Int(o.huntMinHz)) and \(Int(o.huntMaxHz)) Hz", size: 12)
            drawNesHeader(ctx, CGRect(x: area.minX, y: area.minY, width: area.width, height: headerH), nil, o)
            return
        }

        let half = plot.height / 2 * 0.92
        let yFor: (Float) -> CGFloat = { v in plot.midY - CGFloat(coerceIn(v, -1.15, 1.15)) * half }
        let xFor: (Float) -> CGFloat = { phase in plot.minX + plot.width * CGFloat(phase) / CGFloat(cycles) }

        // Clipped to the well: the scale leaves 15 % of headroom so an overshoot
        // still shows its shape, and without a clip it is drawn over the readouts.
        var clipped = ctx
        clipped.clip(to: Path(plot))
        if o.showIdeal { drawNesIdeal(clipped, cycles, xFor, yFor, dim: reading.stale) }
        let traceColor = c(palette.accent2, reading.stale ? 0.35 : 1)
        if o.averaging && !reading.cycle.isEmpty {
            drawNesCycle(clipped, plot, reading.cycle, cycles, o, traceColor, xFor, yFor)
        } else {
            drawNesLive(clipped, plot, reading, cycles, o, traceColor, xFor, yFor)
        }

        drawNesHeader(ctx, CGRect(x: area.minX, y: area.minY, width: area.width, height: headerH), reading, o)
        drawNesFooter(ctx, CGRect(x: area.minX, y: area.maxY - footerH, width: area.width, height: footerH), reading, o)
        for level in [0, 4, 8, 11, 15] {
            ctx.draw(mono("\(level)", 8, palette.textFaint),
                     at: CGPoint(x: area.minX + axisW - 4, y: yFor(Nes2A03.valueOfLevel(level))), anchor: .trailing)
        }
    }

    /// The 4-bit ladder and the 32-step time grid: the chip's two quantisations, drawn.
    private func drawNesGrid(_ ctx: GraphicsContext, _ plot: CGRect, _ cycles: Int, _ o: NesOptions) {
        ctx.fill(Path(roundedRect: plot, cornerRadius: 10), with: .color(c(palette.bgDeep)))
        let half = plot.height / 2 * 0.92
        if o.showLevels {
            for level in 0..<Nes2A03.levels {
                let y = plot.midY - CGFloat(Nes2A03.valueOfLevel(level)) * half
                var p = Path()
                p.move(to: CGPoint(x: plot.minX, y: y))
                p.addLine(to: CGPoint(x: plot.maxX, y: y))
                // The chip has no zero: the two levels either side of the middle
                // are the ones it can never straddle, so they get the stronger line.
                ctx.stroke(p, with: .color(c(palette.line, level == 7 || level == 8 ? 0.55 : 0.24)), lineWidth: 0.7)
            }
        }
        guard o.showSteps else { return }
        let total = Nes2A03.steps * cycles
        let stepW = plot.width / CGFloat(total)
        // Below about three points a step the grid stops being information
        // and becomes texture.
        if stepW >= 2.5 {
            for i in 0...total {
                let x = plot.minX + CGFloat(i) * stepW
                let boundary = i % Nes2A03.steps == 0
                let mid = i % (Nes2A03.steps / 2) == 0
                var p = Path()
                p.move(to: CGPoint(x: x, y: plot.minY))
                p.addLine(to: CGPoint(x: x, y: plot.maxY))
                ctx.stroke(p, with: .color(c(palette.line, boundary ? 0.6 : (mid ? 0.35 : 0.16))), lineWidth: boundary ? 1 : 0.7)
            }
        } else {
            for i in 0...cycles {
                let x = plot.minX + CGFloat(i) * plot.width / CGFloat(cycles)
                var p = Path()
                p.move(to: CGPoint(x: x, y: plot.minY))
                p.addLine(to: CGPoint(x: x, y: plot.maxY))
                ctx.stroke(p, with: .color(c(palette.line, 0.6)), lineWidth: 1)
            }
        }
    }

    private func drawNesIdeal(_ ctx: GraphicsContext, _ cycles: Int, _ xFor: (Float) -> CGFloat, _ yFor: (Float) -> CGFloat, dim: Bool) {
        var p = Path()
        let total = Nes2A03.steps * cycles
        for i in 0..<total {
            let v = yFor(Nes2A03.wave[i % Nes2A03.steps])
            let x0 = xFor(Float(i) / Float(Nes2A03.steps))
            let x1 = xFor(Float(i + 1) / Float(Nes2A03.steps))
            if i == 0 { p.move(to: CGPoint(x: x0, y: v)) } else { p.addLine(to: CGPoint(x: x0, y: v)) }
            p.addLine(to: CGPoint(x: x1, y: v))
        }
        ctx.stroke(p, with: .color(c(palette.accent, dim ? 0.22 : 0.5)), lineWidth: 1.4)
    }

    private func drawNesCycle(_ ctx: GraphicsContext, _ plot: CGRect, _ cycle: [Float], _ cycles: Int, _ o: NesOptions,
                              _ color: Color, _ xFor: (Float) -> CGFloat, _ yFor: (Float) -> CGFloat) {
        // One point per pixel column is all a stroke can show.
        let columns = max(2, Int(plot.width.rounded()))
        let perCycle = max(2, columns / cycles)
        var p = Path()
        var first = true
        for k in 0..<cycles {
            for i in 0...perCycle {
                let u = Float(i) / Float(perCycle)
                var v = cycle[coerceIn(ktToInt(u * Float(cycle.count)), 0, cycle.count - 1)]
                if o.quantizeToDac { v = quantize(v) }
                let pt = CGPoint(x: xFor(Float(k) + u), y: yFor(v))
                if first { p.move(to: pt); first = false } else { p.addLine(to: pt) }
            }
        }
        ctx.stroke(p, with: .color(color), style: StrokeStyle(lineWidth: 1.7, lineCap: .round, lineJoin: .round))
    }

    private func drawNesLive(_ ctx: GraphicsContext, _ plot: CGRect, _ r: NesReading, _ cycles: Int, _ o: NesOptions,
                             _ color: Color, _ xFor: (Float) -> CGFloat, _ yFor: (Float) -> CGFloat) {
        let live = r.live
        guard !live.isEmpty else { return }
        let period = Float(r.periodSamples)
        var p = Path()
        var first = true
        for (i, raw) in live.enumerated() {
            let phase = r.livePhase0 + Float(i) / period
            if phase < -0.02 { continue }
            if phase > Float(cycles) + 0.02 { break }
            let v = o.quantizeToDac ? quantize(raw) : raw
            let pt = CGPoint(x: xFor(phase), y: yFor(v))
            if first { p.move(to: pt); first = false } else { p.addLine(to: pt) }
        }
        ctx.stroke(p, with: .color(color), style: StrokeStyle(lineWidth: 1.5, lineCap: .round, lineJoin: .round))

        // The capture's own time grid, when the samples are far enough apart to
        // read as samples rather than as a thicker line.
        guard o.showSamples, plot.width / CGFloat(period * Float(cycles)) >= 4 else { return }
        var dots = Path()
        for (i, raw) in live.enumerated() {
            let phase = r.livePhase0 + Float(i) / period
            if phase < 0 || phase > Float(cycles) { continue }
            let v = o.quantizeToDac ? quantize(raw) : raw
            dots.addEllipse(in: CGRect(x: xFor(phase) - 1.5, y: yFor(v) - 1.5, width: 3, height: 3))
        }
        ctx.fill(dots, with: .color(c(palette.text, 0.75)))
    }

    /// Snaps a normalised value to the nearest of the sixteen levels the DAC has.
    private func quantize(_ v: Float) -> Float {
        Nes2A03.valueOfLevel(coerceIn(ktRoundToInt(v * 7.5 + 7.5), 0, Nes2A03.levels - 1))
    }

    private func drawNesHeader(_ ctx: GraphicsContext, _ r: CGRect, _ reading: NesReading?, _ o: NesOptions) {
        let baseline = r.midY
        guard let reading else {
            ctx.draw(label("2A03 triangle", 12, palette.textFaint), at: CGPoint(x: r.minX, y: baseline), anchor: .leading)
            ctx.draw(mono(o.region.label, 10, palette.textFaint), at: CGPoint(x: r.maxX, y: baseline), anchor: .trailing)
            return
        }
        // Rounded first, then signed: %+.0f of −0.2 prints "-0 ct", which reads
        // as a tuning error that is not there.
        let roundedCents = ktRoundToInt(reading.cents)
        let cents = roundedCents == 0 ? "in tune" : String(format: "%+d ct", roundedCents)
        // The register value is the point of the whole view: the number a tracker
        // would have written to $400A/$400B to make this sound.
        let hex = String(reading.timer, radix: 16).uppercased()
        let timer = "$" + String(repeating: "0", count: max(0, 3 - hex.count)) + hex
        let note = Text(reading.note).font(.system(size: 16, weight: .semibold))
            .foregroundStyle(c(reading.stale ? palette.textFaint : palette.text))
        let tuning = Text("  \(cents)   ").font(.system(size: 10, design: .monospaced)).foregroundStyle(c(palette.textFaint))
        let register = Text(timer).font(.system(size: 13, weight: .bold, design: .monospaced)).foregroundStyle(c(palette.accent))
        let detail = Text(" (\(reading.timer)) · \(String(format: "%.2f", reading.nesHz)) Hz")
            .font(.system(size: 10, design: .monospaced)).foregroundStyle(c(palette.textDim))
        ctx.draw(Text("\(note)\(tuning)\(register)\(detail)"), at: CGPoint(x: r.minX, y: baseline), anchor: .leading)
        ctx.draw(mono(reading.stale ? "\(o.region.label) · holding" : o.region.label, 10, reading.stale ? palette.warn : palette.textFaint),
                 at: CGPoint(x: r.maxX, y: baseline), anchor: .trailing)
    }

    private func drawNesFooter(_ ctx: GraphicsContext, _ r: CGRect, _ reading: NesReading, _ o: NesOptions) {
        let sps = reading.samplesPerStep
        var parts = String(format: "%.1f smp/step", sps) + " · fold \(reading.foldedPeriods)"
        if reading.foldedPeriods < o.foldPeriods { parts += "/\(o.foldPeriods)" }
        parts += " · fit " + String(format: "%.2f", reading.stepMatch)
        parts += " · grid " + String(format: "%+.2f", reading.gridCents) + "/" + String(format: "%.1f ct", reading.gridStepCents)
        ctx.draw(mono(parts, 9, palette.textFaint), at: CGPoint(x: r.minX, y: r.maxY - 2), anchor: .bottomLeading)

        // One short verdict, because the numbers only mean something to someone
        // who already knows what they mean.
        let (verdict, color): (String, UInt32) =
            if !reading.audible { ("below audible", palette.warn) }
            else if sps < 4 { ("too high to show steps", palette.warn) }
            else if reading.stepMatch < 0.35 { ("buried", palette.warn) }
            else if reading.stepMatch < 0.7 { ("partly buried", palette.textDim) }
            else { ("clean staircase", palette.good) }
        ctx.draw(mono(verdict, 9, color), at: CGPoint(x: r.minX, y: r.minY + 2), anchor: .topLeading)
    }

    // ---- readouts -----------------------------------------------------------

    /// The numbers under every page — HomeScreen.kt's Readouts row, drawn with
    /// the graph so they change on the same frame as it does.
    func drawReadouts(_ ctx: GraphicsContext, _ r: CGRect, _ f: AnalysisFrame?) {
        let items: [(String, String)] = [
            ("RMS L", readoutDb(f?.rmsDbL)),
            ("RMS R", readoutDb(f?.rmsDbR)),
            ("PEAK", readoutDb(f.map { max($0.peakDbL, $0.peakDbR) })),
            ("LUFS M", f.map { lufsText($0.loudness.momentary) } ?? "—"),
            ("CORR", f.map { String(format: "%+.2f", $0.correlation) } ?? "—"),
        ]
        let cell = r.width / CGFloat(items.count)
        for (i, item) in items.enumerated() {
            let x = r.minX + cell * (CGFloat(i) + 0.5)
            ctx.draw(label(item.0, 8.5, palette.textFaint), at: CGPoint(x: x, y: r.minY), anchor: .top)
            ctx.draw(mono(item.1, 12, palette.text), at: CGPoint(x: x, y: r.maxY), anchor: .bottom)
        }
    }

    private func readoutDb(_ v: Float?) -> String {
        guard let v else { return "—" }
        return v <= PeakHold.floorDb + 1 ? "-inf" : String(format: "%.1f", v)
    }

    // ---- helpers -----------------------------------------------------------

    private func drawIdleText(_ ctx: GraphicsContext, _ area: CGRect, _ message: String, size: CGFloat) {
        ctx.draw(label(message, size, palette.textFaint), at: CGPoint(x: area.midX, y: area.midY))
    }

    func c(_ argb: UInt32, _ alpha: Float = 1) -> Color { Color(argb: argb, alpha: alpha) }

    private func label(_ s: String, _ size: CGFloat, _ color: UInt32, _ alpha: Float = 1) -> Text {
        Text(s).font(.system(size: size, weight: .medium)).foregroundStyle(c(color, alpha))
    }

    private func mono(_ s: String, _ size: CGFloat, _ color: UInt32) -> Text {
        Text(s).font(.system(size: size, weight: .bold, design: .monospaced)).foregroundStyle(c(color))
    }

    private func dbText(_ db: Float) -> String { db <= PeakHold.floorDb + 1 ? "-inf" : String(format: "%.0f", db) }

    private func lufsText(_ v: Float) -> String { v <= -70 ? "—" : String(format: "%.1f", v) }

    /// The synth lane: 6.5:1 on the dark page, 5.0:1 on the light one.
    static let synthDark: UInt32 = 0xFFFF7AC8
    static let synthLight: UInt32 = 0xFFB32A73

    static let gridFreqs: [Float] = [20, 30, 50, 100, 200, 300, 500, 1000, 2000, 3000, 5000, 10000, 20000]
}

/// A CGImage of 0xAARRGGBB pixels — on a little-endian phone that is exactly
/// premultiplied-first, 32-bit-little — copied, so the next frame can write the
/// buffer while this one is still being composited.
func makeImage(_ pixels: [UInt32], width: Int, height: Int) -> CGImage? {
    let data = pixels.withUnsafeBufferPointer { Data(buffer: $0) }
    guard let provider = CGDataProvider(data: data as CFData) else { return nil }
    return CGImage(
        width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: width * 4,
        space: CGColorSpace(name: CGColorSpace.sRGB)!,
        bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue),
        provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent
    )
}

extension Color {
    /// A Palette colour (0xAARRGGBB), with an extra alpha on top.
    init(argb: UInt32, alpha: Float = 1) {
        self.init(.sRGB,
                  red: Double(argb >> 16 & 0xFF) / 255,
                  green: Double(argb >> 8 & 0xFF) / 255,
                  blue: Double(argb & 0xFF) / 255,
                  opacity: Double(argb >> 24 & 0xFF) / 255 * Double(alpha))
    }
}
