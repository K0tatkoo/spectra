import SwiftUI
import SpectraCore

/// The tour a fresh install opens on: what Spectra is, what is in each tab,
/// where the sound comes from, the handful of controls worth knowing, and what
/// happens to the audio. Five pages, swiped or stepped through with Continue;
/// Settings › About › Welcome tour shows it again.
///
/// It asks for nothing itself. The microphone prompt is iOS's own, and comes
/// when Spectra first listens — after the tour, never under it.
struct WelcomeView: View {
    var onFinish: () -> Void

    @State private var step = 0
    @Environment(\.colorScheme) private var scheme

    private let pages = WelcomePage.allCases
    private var isLast: Bool { step == pages.count - 1 }

    var body: some View {
        TabView(selection: $step) {
            ForEach(pages) { page in
                WelcomePageView(page: page, current: step == page.rawValue)
                    .tag(page.rawValue)
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
        .background(Color(argb: Palette.of(dark: scheme == .dark).bg).ignoresSafeArea())
        .safeAreaInset(edge: .top, spacing: 0) { topBar }
        .safeAreaInset(edge: .bottom, spacing: 0) { footer }
    }

    private var topBar: some View {
        HStack {
            if step > 0 {
                Button("Back", systemImage: "chevron.left") { go(to: step - 1) }
                    .labelStyle(.iconOnly)
                    .buttonBorderShape(.circle)
                    .transition(.opacity)
            }
            Spacer()
            if !isLast {
                Button("Skip", action: onFinish)
                    .transition(.opacity)
            }
        }
        .buttonStyle(.glass)
        .controlSize(.large)
        .frame(height: 48)
        .padding(.horizontal, 20)
        .padding(.top, 4)
        .animation(.smooth, value: step)
    }

    private var footer: some View {
        let palette = Palette.of(dark: scheme == .dark)
        return VStack(spacing: 18) {
            // Where Apple's own setup screens keep it: the privacy note sits just
            // above the button that leads on to the permission prompt.
            if isLast {
                VStack(spacing: 4) {
                    Text("iOS asks for the microphone when Spectra first listens. You can change that any time in Settings.")
                    Link("Privacy policy", destination: Legal.privacyPolicy)
                        .fontWeight(.semibold)
                        .foregroundStyle(.tint)
                }
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .transition(.opacity)
            }
            PageDots(count: pages.count, current: step)
            Button {
                if isLast { onFinish() } else { go(to: step + 1) }
            } label: {
                Text(isLast ? "Get Started" : "Continue")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 6)
                    .contentTransition(.opacity)
            }
            .buttonStyle(.glassProminent)
            .controlSize(.large)
            // Filled controls are the one flat violet, never the lighter text accent.
            .tint(Color(argb: palette.accentFill))
        }
        .frame(maxWidth: 520)
        .padding(.horizontal, 28)
        .padding(.top, 8)
        .padding(.bottom, 12)
        .animation(.smooth, value: step)
    }

    private func go(to page: Int) {
        withAnimation(.smooth) { step = min(max(page, 0), pages.count - 1) }
    }
}

// MARK: - the pages

private enum WelcomePage: Int, CaseIterable, Identifiable {
    case hello, tabs, input, tips, privacy

    var id: Int { rawValue }
}

private struct WelcomePageView: View {
    let page: WelcomePage
    /// Only the page on screen animates; its neighbours are already built.
    let current: Bool

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                hero
                    .padding(.bottom, 24)
                Text(title)
                    .font(.largeTitle.bold())
                    .multilineTextAlignment(.center)
                    .accessibilityAddTraits(.isHeader)
                Text(subtitle)
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.top, 10)
                if !rows.isEmpty {
                    VStack(alignment: .leading, spacing: 20) {
                        ForEach(rows, id: \.title) { FeatureRow(row: $0) }
                    }
                    .padding(.top, 28)
                }
            }
            .frame(maxWidth: 520)
            .padding(.horizontal, 28)
            .padding(.top, 20)
            .padding(.bottom, 24)
            .frame(maxWidth: .infinity)
        }
        .scrollBounceBehavior(.basedOnSize)
        .scrollIndicators(.hidden)
        // A page that runs long (large text sizes) fades into the bars rather
        // than being cut mid-line: the paging view clips at its own edge.
        .mask {
            VStack(spacing: 0) {
                LinearGradient(colors: [.clear, .black], startPoint: .top, endPoint: .bottom).frame(height: 16)
                Rectangle()
                LinearGradient(colors: [.black, .clear], startPoint: .top, endPoint: .bottom).frame(height: 24)
            }
        }
        // A page that fits sits in the middle; a long one (big text) starts at its top.
        .defaultScrollAnchor(.center, for: .alignment)
    }

    @ViewBuilder private var hero: some View {
        switch page {
        case .hello: HelloHero(animating: current)
        case .tabs: TabBarReplica(animating: current)
        case .input: PlayerBarReplica(animating: current)
        case .tips: SymbolTile(symbol: "slider.horizontal.3", bounce: current)
        case .privacy: SymbolTile(symbol: "lock.shield.fill", bounce: current)
        }
    }

    private var title: String {
        switch page {
        case .hello: "Welcome to Spectra"
        case .tabs: "Four tabs, eight views"
        case .input: "Choose what to listen to"
        case .tips: "Make it yours"
        case .privacy: "Private by design"
        }
    }

    private var subtitle: String {
        switch page {
        case .hello:
            "See what you hear. Spectra draws sound as it happens — its frequencies, loudness, stereo "
                + "image and waveform — measured properly and live, right on your \(UIDevice.current.model)."
        case .tabs:
            "Each tab holds a few related views — switch between them with the control at the top."
        case .input:
            "The bar above the tabs is the input: tap it to switch. The snowflake freezes the picture."
        case .tips:
            "Spectra starts out with sensible settings, and every one of them can be changed."
        case .privacy:
            "Spectra analyses sound the moment it arrives, keeps none of it, and never sends anything anywhere."
        }
    }

    private var rows: [Feature] {
        switch page {
        case .hello:
            []
        case .tabs:
            [
                Feature(symbol: AppTab.spectrum.symbol, title: "Spectrum",
                        text: "The live spectrum with peak hold, and a scrolling spectrogram."),
                Feature(symbol: AppTab.levels.symbol, title: "Levels",
                        text: "Band meters, loudness in LUFS with true peak, and the stereo image."),
                Feature(symbol: AppTab.scope.symbol, title: "Scope",
                        text: "The waveform held still on a note, and a simulated tube oscilloscope."),
                Feature(symbol: AppTab.stems.symbol, title: "Stems",
                        text: "Vocals, bass, drums and the rest, split live by a neural network."),
            ]
        case .input:
            [
                Feature(symbol: "mic.fill", title: "Microphone",
                        text: "Whatever the \(UIDevice.current.model) hears, live — in stereo too."),
                Feature(symbol: "music.note", title: "Audio file",
                        text: "A clean digital signal. Pick one, or use Open in Spectra in Files."),
                Feature(symbol: "app.connected.to.app.below.fill", title: "Other apps",
                        text: "Spotify, YouTube and more, through a screen broadcast."),
            ]
        case .tips:
            [
                Feature(symbol: "gearshape", title: "Settings",
                        text: "FFT size, window, range, colours and bands, behind the gear at the top left."),
                Feature(symbol: "hand.tap", title: "Double-tap to reset",
                        text: "Double-tap a slider's name to put it back to its default."),
                Feature(symbol: "arrow.counterclockwise", title: "Start fresh",
                        text: "The arrow at the top right clears peaks, loudness and history."),
                Feature(symbol: "iphone.landscape", title: "Go wide",
                        text: "Turn your \(UIDevice.current.model) sideways for a bigger graph."),
            ]
        case .privacy:
            [
                Feature(symbol: "mic.fill", title: "Nothing is recorded",
                        text: "The microphone is listened to, never saved, and let go when you leave."),
                Feature(symbol: "wifi.slash", title: "No internet needed",
                        text: "Spectra never goes online. No accounts, no ads, no tracking."),
                Feature(symbol: "cpu", title: "Stems on the device",
                        text: "The neural network runs on your \(UIDevice.current.model)'s own chip."),
            ]
        }
    }
}

private struct Feature {
    let symbol: String
    let title: String
    let text: String
}

private struct FeatureRow: View {
    let row: Feature

    var body: some View {
        HStack(alignment: .top, spacing: 16) {
            Image(systemName: row.symbol)
                .font(.title2)
                .symbolRenderingMode(.hierarchical)
                .foregroundStyle(.tint)
                .frame(width: 36)
            VStack(alignment: .leading, spacing: 3) {
                Text(row.title).font(.headline)
                Text(row.text)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .combine)
    }
}

private struct PageDots: View {
    let count: Int
    let current: Int

    var body: some View {
        HStack(spacing: 8) {
            ForEach(0..<count, id: \.self) { i in
                Capsule()
                    .fill(i == current ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary.opacity(0.35)))
                    .frame(width: i == current ? 22 : 8, height: 8)
            }
        }
        .accessibilityElement()
        .accessibilityLabel("Page \(current + 1) of \(count)")
    }
}

// MARK: - heroes

/// The app's own mark on its flat violet tile, over a spectrum that moves the
/// way the real one does: a falling slope with a few peaks breathing on it.
private struct HelloHero: View {
    let animating: Bool
    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let palette = Palette.of(dark: scheme == .dark)
        ZStack {
            TimelineView(.animation(minimumInterval: 1.0 / 60, paused: !animating || reduceMotion)) { timeline in
                Canvas { ctx, size in
                    // Reading the date is what makes the canvas redraw each tick.
                    let t = timeline.date.timeIntervalSinceReferenceDate
                    Self.drawSpectrum(ctx, size: size, t: reduceMotion ? 0 : t, palette: palette)
                }
            }
            .frame(height: 150)
            .mask(LinearGradient(colors: [.clear, .black, .black, .clear], startPoint: .leading, endPoint: .trailing))
            .offset(y: 26)

            SpectraMarkView()
                .frame(width: 116, height: 116)
                .background(Color(argb: palette.accentFill), in: .rect(cornerRadius: 27, style: .continuous))
                .shadow(color: Color(argb: palette.accentFill, alpha: 0.35), radius: 18, y: 8)
        }
        .frame(height: 190)
        .accessibilityHidden(true)
    }

    private static func drawSpectrum(_ ctx: GraphicsContext, size: CGSize, t: Double, palette: Palette) {
        let n = 72
        func level(_ x: Double) -> Double {
            func peak(_ at: Double, _ width: Double) -> Double { exp(-pow((x - at) / width, 2)) }
            var v = 0.62 - 0.42 * x
            v += 0.26 * peak(0.14, 0.045) * (0.65 + 0.35 * sin(t * 2.3))
            v += 0.18 * peak(0.36, 0.035) * (0.5 + 0.5 * sin(t * 3.1 + 1.2))
            v += 0.14 * peak(0.58, 0.05) * (0.5 + 0.5 * sin(t * 1.7 + 2.4))
            v += 0.08 * peak(0.79, 0.03) * (0.5 + 0.5 * sin(t * 4.3 + 0.7))
            v += 0.025 * sin(x * 47 + t * 5) + 0.015 * sin(x * 91 - t * 7)
            return min(max(v, 0.04), 1)
        }
        let points = (0...n).map { i -> CGPoint in
            let x = Double(i) / Double(n)
            return CGPoint(x: x * size.width, y: size.height * (1 - level(x)))
        }
        var line = Path()
        line.move(to: points[0])
        for i in 1..<points.count {
            let mid = CGPoint(x: (points[i - 1].x + points[i].x) / 2, y: (points[i - 1].y + points[i].y) / 2)
            line.addQuadCurve(to: mid, control: points[i - 1])
        }
        line.addLine(to: points[points.count - 1])

        var fill = line
        fill.addLine(to: CGPoint(x: size.width, y: size.height))
        fill.addLine(to: CGPoint(x: 0, y: size.height))
        fill.closeSubpath()
        ctx.fill(fill, with: .linearGradient(
            Gradient(colors: [Color(argb: palette.accent, alpha: 0.42), Color(argb: palette.accent, alpha: 0)]),
            startPoint: CGPoint(x: 0, y: 0), endPoint: CGPoint(x: 0, y: size.height)))
        ctx.stroke(line, with: .color(Color(argb: palette.accent)), style: StrokeStyle(lineWidth: 2, lineJoin: .round))
    }
}

/// The app icon's mark, drawn: half a sine wave running into the stepped
/// triangle wave of the NES sound chip. Coordinates are the icon's own, in its
/// 72-unit box (18…90), so it sits on a tile exactly as it sits on the icon.
struct SpectraMarkView: View {
    var body: some View {
        Canvas { ctx, size in
            let s = min(size.width, size.height) / 72
            let ox = (size.width - 72 * s) / 2 - 18 * s
            let oy = (size.height - 72 * s) / 2 - 18 * s
            func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: ox + x * s, y: oy + y * s) }

            var sine = Path()
            sine.move(to: p(34.909, 54))
            sine.addCurve(to: p(39.682, 44.55), control1: p(36.5, 50.501), control2: p(38.091, 47.024))
            sine.addCurve(to: p(44.455, 40.636), control1: p(41.273, 42.077), control2: p(42.864, 40.636))
            sine.addCurve(to: p(49.227, 44.55), control1: p(46.045, 40.636), control2: p(47.636, 42.077))
            sine.addCurve(to: p(54, 54), control1: p(50.818, 47.024), control2: p(52.409, 50.501))
            ctx.stroke(sine, with: .color(.white), style: StrokeStyle(lineWidth: 3.818 * s, lineCap: .round, lineJoin: .round))

            let steps: [(CGFloat, CGFloat)] = [
                (54, 54), (54, 57.341), (56.727, 57.341), (56.727, 60.682), (59.455, 60.682), (59.455, 64.023),
                (62.182, 64.023), (62.182, 67.364), (64.909, 67.364), (64.909, 64.023), (67.636, 64.023),
                (67.636, 60.682), (70.364, 60.682), (70.364, 57.341), (73.091, 57.341), (73.091, 54),
            ]
            var stair = Path()
            stair.move(to: p(steps[0].0, steps[0].1))
            for q in steps.dropFirst() { stair.addLine(to: p(q.0, q.1)) }
            ctx.stroke(stair, with: .color(.white), style: StrokeStyle(lineWidth: 3.818 * s, lineCap: .square, lineJoin: .miter))
        }
        .accessibilityHidden(true)
    }
}

/// The real tab bar in miniature, its selection walking from tab to tab.
private struct TabBarReplica: View {
    let animating: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Namespace private var pill

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1.4)) { timeline in
            let tabs = AppTab.allCases
            let lit = animating && !reduceMotion
                ? tabs[Int(timeline.date.timeIntervalSinceReferenceDate / 1.4) % tabs.count]
                : .spectrum
            HStack(spacing: 2) {
                ForEach(tabs) { tab in
                    VStack(spacing: 4) {
                        Image(systemName: tab.symbol).font(.system(size: 19, weight: .medium))
                            .frame(height: 24)
                        Text(tab.title).font(.caption2.weight(.semibold))
                    }
                    .foregroundStyle(tab == lit ? AnyShapeStyle(.tint) : AnyShapeStyle(.primary))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
                    .background {
                        if tab == lit {
                            Capsule().fill(.secondary.opacity(0.16)).matchedGeometryEffect(id: "pill", in: pill)
                        }
                    }
                }
            }
            .padding(5)
            .animation(.smooth(duration: 0.45), value: lit)
        }
        .glassEffect(.regular, in: .capsule)
        .frame(maxWidth: 340)
        .padding(.vertical, 12)
        .accessibilityHidden(true)
    }
}

/// The player bar in miniature, going round the three inputs it can show.
private struct PlayerBarReplica: View {
    let animating: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private static var sources: [(symbol: String, title: String, detail: String, action: String)] { [
        ("mic.fill", "Microphone", "\(UIDevice.current.model) Microphone · 48 kHz mono", "stop.fill"),
        ("music.note", "Audio file", "1:24 / 3:51", "pause.fill"),
        ("app.connected.to.app.below.fill", "Other apps", "Screen broadcast · −14.2 LUFS", "stop.fill"),
    ] }

    var body: some View {
        TimelineView(.periodic(from: .now, by: 2.2)) { timeline in
            let i = animating && !reduceMotion ? Int(timeline.date.timeIntervalSinceReferenceDate / 2.2) % 3 : 0
            let source = Self.sources[i]
            HStack(spacing: 12) {
                Image(systemName: source.symbol)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(.tint)
                    .frame(width: 28)
                    .contentTransition(.symbolEffect(.replace))
                VStack(alignment: .leading, spacing: 1) {
                    Text(source.title).font(.subheadline.weight(.semibold))
                    Text(source.detail).font(.caption).foregroundStyle(.secondary).monospacedDigit()
                }
                .lineLimit(1)
                .contentTransition(.opacity)
                Spacer(minLength: 8)
                Image(systemName: "snowflake").font(.title3).foregroundStyle(.secondary)
                Image(systemName: source.action).font(.title3)
                    .contentTransition(.symbolEffect(.replace))
            }
            .padding(.horizontal, 18)
            .frame(height: 58)
            .animation(.smooth, value: i)
        }
        .glassEffect(.regular, in: .capsule)
        .frame(maxWidth: 360)
        .padding(.vertical, 12)
        .accessibilityHidden(true)
    }
}

/// A page's symbol on the same flat violet tile the app icon uses.
private struct SymbolTile: View {
    let symbol: String
    let bounce: Bool
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let palette = Palette.of(dark: scheme == .dark)
        Image(systemName: symbol)
            .font(.system(size: 42, weight: .semibold))
            .foregroundStyle(.white)
            .symbolEffect(.bounce, value: bounce)
            .frame(width: 96, height: 96)
            .background(Color(argb: palette.accentFill), in: .rect(cornerRadius: 23, style: .continuous))
            .shadow(color: Color(argb: palette.accentFill, alpha: 0.3), radius: 16, y: 7)
            .accessibilityHidden(true)
    }
}
