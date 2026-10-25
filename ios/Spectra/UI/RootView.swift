import SwiftUI
import SpectraCore
import UniformTypeIdentifiers

/// Four tabs in Liquid Glass, with the input as a Music-app-style player bar
/// above them. Each tab switches its own pages from the navigation bar; the
/// graph runs edge to edge underneath the glass.
struct RootView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.scenePhase) private var phase

    var body: some View {
        @Bindable var model = model
        TabView(selection: Binding(get: { model.tab }, set: { model.select(tab: $0) })) {
            ForEach(AppTab.allCases) { tab in
                Tab(tab.title, systemImage: tab.symbol, value: tab) {
                    PageStack(tab: tab)
                }
            }
        }
        .tabViewBottomAccessory { PlayerBar() }
        .preferredColorScheme(model.colorScheme)
        .sheet(isPresented: $model.showSettings) { SettingsView() }
        .sheet(isPresented: $model.showSource) { SourceSheet() }
        .fileImporter(isPresented: $model.showFileImporter, allowedContentTypes: [.audio]) { result in
            if case .success(let url) = result { model.importFile(url) }
        }
        .onChange(of: phase, initial: true) { _, now in model.setActive(now == .active) }
        #if DEBUG
        // SIMCTL_CHILD_SPECTRA_PLAY=1: press play by itself, for screenshots.
        .task {
            guard ProcessInfo.processInfo.environment["SPECTRA_PLAY"] != nil else { return }
            try? await Task.sleep(for: .seconds(1))
            model.primaryAction()
        }
        #endif
        // "Open in Spectra" from Files or a share sheet.
        .onOpenURL { url in if url.isFileURL { model.importFile(url) } }
        // The first-launch tour sits over everything from the very first frame
        // (a cover would let the tabs show first and then slide it up).
        .accessibilityHidden(model.showWelcome)
        .overlay {
            if model.showWelcome {
                WelcomeView { withAnimation(.smooth) { model.finishWelcome() } }
                    .transition(.move(edge: .bottom))
            }
        }
        // Outermost, so the sheets inherit it too: every "on" switch and filled
        // control is the one flat violet, as on the sites and the Android apps.
        .tint(Color.accentColor)
    }
}

/// One tab: its navigation bar (settings, the page switcher, freeze and reset)
/// over the page itself.
private struct PageStack: View {
    let tab: AppTab
    @Environment(AppModel.self) private var model

    var body: some View {
        NavigationStack {
            PageView(page: model.page(for: tab))
                .navigationTitle(tab.title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) {
                        Button("Settings", systemImage: "gearshape") { model.showSettings = true }
                            .keyboardShortcut(",")
                    }
                    if tab.pages.count > 1 {
                        ToolbarItem(placement: .principal) {
                            Picker("Page", selection: Binding(get: { model.page(for: tab) }, set: { model.show($0) })) {
                                ForEach(tab.pages, id: \.self) { Text($0.label).tag($0) }
                            }
                            .pickerStyle(.segmented)
                            .fixedSize()
                        }
                    }
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("Reset meters", systemImage: "arrow.counterclockwise") { model.resetMeters() }
                            .keyboardShortcut("r")
                    }
                }
        }
    }
}

/// A page: the graph, drawn every display frame from the engine's newest
/// analysis, plus the page's own controls floating over it in glass.
struct PageView: View {
    let page: VizPage
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var scheme
    @State private var painter = VizPainter()

    var body: some View {
        let palette = Palette.of(dark: scheme == .dark)
        // The reader keeps to the safe area, so its insets are the bars' — the
        // navigation bar, the player bar and the tab bar. Only the canvas runs
        // under the glass; the graph's own plot stays inside the insets.
        GeometryReader { geo in
            let insets = geo.safeAreaInsets
            TimelineView(.animation(minimumInterval: 1.0 / Double(max(model.settings.uiFps, 1)))) { timeline in
                Canvas { ctx, size in
                    // Reading the timeline's date is what makes SwiftUI redraw the
                    // canvas on every tick; without it the first picture stays.
                    _ = timeline.date
                    painter.palette = palette
                    let reserved = PageControls.height(for: page, settings: model.settings) + 36
                    let area = CGRect(
                        x: insets.leading + 16, y: insets.top + 10,
                        width: size.width - insets.leading - insets.trailing - 32,
                        height: size.height - insets.top - insets.bottom - 20 - reserved
                    )
                    let frame = model.engine.frame
                    painter.draw(ctx, area: area, page: page, frame: frame, settings: model.settings)
                    painter.drawReadouts(ctx, CGRect(x: area.minX, y: area.maxY + reserved - 30, width: area.width, height: 28), frame)
                }
            }
            .ignoresSafeArea()
        }
        .overlay(alignment: .bottom) {
            PageControls(page: page)
                .padding(.bottom, 44)
                .padding(.horizontal, 16)
        }
        .overlay(alignment: .top) {
            Banner()
                .padding(.top, 8)
                .padding(.horizontal, 16)
        }
        .background(Color(argb: palette.bg).ignoresSafeArea())
    }
}

/// The page's own front panel, floating in glass at the foot of the graph:
/// the Waveform page's mode, the tube's X-Y/Y-T and time base, and which
/// stems stand still. Everything else is in Settings.
private struct PageControls: View {
    let page: VizPage
    @Environment(AppModel.self) private var model

    static func height(for page: VizPage, settings: Settings) -> CGFloat {
        switch page {
        case .waveform, .oscilloscope, .stems: 56
        default: 0
        }
    }

    var body: some View {
        @Bindable var model = model
        switch page {
        case .waveform:
            Picker("Mode", selection: $model.settings.waveformMode) {
                ForEach(WaveformMode.allCases, id: \.self) { Text($0.label).tag($0) }
            }
            .pickerStyle(.segmented)
            .padding(6)
            .glassEffect(.regular, in: .capsule)
        case .oscilloscope:
            GlassEffectContainer(spacing: 10) {
                HStack(spacing: 10) {
                    Picker("Beam", selection: $model.settings.oscMode) {
                        ForEach(OscMode.allCases, id: \.self) { Text($0.label).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .frame(maxWidth: 160)
                    .padding(6)
                    .glassEffect(.regular, in: .capsule)
                    if model.settings.oscMode == .yt { TimeBaseStepper() }
                }
            }
        case .stems:
            StemHoldToggles()
        default:
            EmptyView()
        }
    }
}

/// Y-T time base, stepped through a bench scope's 1-2-5 sequence.
private struct TimeBaseStepper: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let steps = Settings.oscTimeDivs
        let at = steps.indices.min { abs(steps[$0] - model.settings.oscTimeDivMs) < abs(steps[$1] - model.settings.oscTimeDivMs) } ?? 3
        HStack(spacing: 4) {
            Button("Less time per division", systemImage: "minus") {
                model.settings.oscTimeDivMs = steps[max(at - 1, 0)]
            }
            .labelStyle(.iconOnly)
            .disabled(at == 0)
            Text(oscTimeLabel(steps[at]))
                .font(.system(.footnote, design: .monospaced).bold())
                .frame(minWidth: 56)
            Button("More time per division", systemImage: "plus") {
                model.settings.oscTimeDivMs = steps[min(at + 1, steps.count - 1)]
            }
            .labelStyle(.iconOnly)
            .disabled(at == steps.count - 1)
        }
        .padding(.horizontal, 12)
        .frame(height: 44)
        .glassEffect(.regular.interactive(), in: .capsule)
    }
}

/// Which Stems lanes stand still: a lit button is held still, a plain one scrolls.
private struct StemHoldToggles: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        ScrollView(.horizontal, showsIndicators: false) {
            GlassEffectContainer(spacing: 8) {
                HStack(spacing: 8) {
                    Text("Hold still").font(.caption).foregroundStyle(.secondary)
                    hold("Vocals", $model.settings.holdVocals)
                    if model.settings.stemSynth && StemModels.hasSynth { hold("Synth", $model.settings.holdSynth) }
                    hold("Other", $model.settings.holdOther)
                    hold("Bass", $model.settings.holdBass)
                    hold("Drums", $model.settings.holdDrums)
                }
                .padding(.horizontal, 4)
            }
        }
        .controlSize(.small)
        .scrollClipDisabled()
    }

    @ViewBuilder
    private func hold(_ name: String, _ on: Binding<Bool>) -> some View {
        if on.wrappedValue {
            Button(name) { on.wrappedValue.toggle() }.buttonStyle(.glassProminent)
        } else {
            Button(name) { on.wrappedValue.toggle() }.buttonStyle(.glass)
        }
    }
}

/// A failure or a warning, on tinted glass under the navigation bar.
private struct Banner: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        if case .failed(let kind, let message) = model.engineState {
            content(message, tint: .red) {
                if kind == .permission {
                    Button("Open Settings") {
                        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                    }
                } else {
                    Button("Try again") { model.restart() }
                }
            }
        } else if let warning = model.warning {
            content(warning, tint: .orange) {
                Button("Dismiss", systemImage: "xmark") { model.dismissWarning() }.labelStyle(.iconOnly)
            }
        }
    }

    private func content(_ text: String, tint: Color, @ViewBuilder action: () -> some View) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Text(text).font(.footnote).frame(maxWidth: .infinity, alignment: .leading)
            action().font(.footnote.bold())
        }
        .padding(12)
        .glassEffect(.regular.tint(tint.opacity(0.25)), in: .rect(cornerRadius: 18))
    }
}
