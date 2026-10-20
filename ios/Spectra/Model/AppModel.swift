import AVFoundation
import Observation
import SpectraCore
import SwiftUI

/// The tabs, and the pages each one holds — eight pages in four places, because
/// an iPhone tab bar has room for about five.
enum AppTab: String, CaseIterable, Identifiable {
    case spectrum, levels, scope, stems

    var id: Self { self }

    var title: String {
        switch self {
        case .spectrum: "Spectrum"
        case .levels: "Levels"
        case .scope: "Scope"
        case .stems: "Stems"
        }
    }

    var symbol: String {
        switch self {
        case .spectrum: "chart.xyaxis.line"
        case .levels: "chart.bar.fill"
        case .scope: "waveform.path.ecg"
        case .stems: "square.3.layers.3d"
        }
    }

    var pages: [VizPage] {
        switch self {
        case .spectrum: [.spectrum, .spectrogram]
        case .levels: [.bands, .loudness, .stereo]
        case .scope: [.waveform, .oscilloscope]
        case .stems: [.stems]
        }
    }

    static func of(_ page: VizPage) -> AppTab { allCases.first { $0.pages.contains(page) } ?? .spectrum }
}

/// Everything the UI shows and changes, on the main actor. The analysis itself
/// runs on its own thread in AudioEngine; this is the side that talks to it.
@MainActor @Observable
final class AppModel {
    var settings: Settings {
        didSet {
            guard settings != oldValue else { return }
            store.save(settings)
            let reopen = engine.updateSettings(settings)
            engine.inAppPage = active ? settings.page : nil
            if reopen && engine.isRunning { restart() }
        }
    }

    var engineState: AudioEngine.State = .idle
    var warning: String?
    /// The picture is frozen while capture carries on.
    var frozen = false
    var tab: AppTab
    /// The page each tab last showed.
    var pageForTab: [AppTab: VizPage]

    var showSettings = false
    var showSource = false
    var showFileImporter = false

    /// The file Spectra plays when the source is "Audio file".
    private(set) var fileURL: URL?
    private(set) var fileCapture: FileCapture?

    @ObservationIgnored let engine = AudioEngine.shared
    @ObservationIgnored private let store = SettingsStore()
    @ObservationIgnored private var active = true

    init() {
        let s = SettingsStore().load()
        settings = s
        tab = AppTab.of(s.page)
        var pages: [AppTab: VizPage] = [:]
        for t in AppTab.allCases { pages[t] = t.pages[0] }
        pages[AppTab.of(s.page)] = s.page
        pageForTab = pages
        fileURL = Self.importedFiles().first
        engine.updateSettings(s)
        engine.inAppPage = s.page
        engine.makeScopes = { StemModels.makeRunner(rate: $0) }
        engine.onChange = { [weak self] state, warning in
            self?.engineState = state
            self?.warning = warning
        }
    }

    // ---- pages ------------------------------------------------------------------

    func page(for tab: AppTab) -> VizPage { pageForTab[tab] ?? tab.pages[0] }

    func show(_ page: VizPage) {
        pageForTab[AppTab.of(page)] = page
        if settings.page != page { settings.page = page }
    }

    func select(tab: AppTab) {
        self.tab = tab
        show(page(for: tab))
    }

    // ---- capture ------------------------------------------------------------------

    var isRunning: Bool {
        switch engineState {
        case .running, .starting: true
        default: false
        }
    }

    /// Starts the chosen source. The microphone asks for permission first.
    func start() {
        switch settings.source {
        case .microphone:
            Task {
                if AVAudioApplication.shared.recordPermission == .undetermined {
                    _ = await AVAudioApplication.requestRecordPermission()
                }
                engine.start(MicCapture(settings: settings))
            }
        case .file:
            guard let url = fileURL else {
                showFileImporter = true
                return
            }
            do {
                let capture = try FileCapture(url: url)
                fileCapture = capture
                engine.start(capture)
            } catch let e as CaptureError {
                engineState = .failed(kind: e.kind, message: e.message)
            } catch {}
        case .otherApps:
            engine.start(BroadcastCapture())
        }
    }

    func stop() {
        engine.stop()
        fileCapture = nil
    }

    func restart() {
        stop()
        start()
    }

    func choose(_ source: SourceKind) {
        guard source != settings.source || !isRunning else { return }
        stop()
        settings.source = source
        start()
    }

    /// The player bar's main button: listen / stop for the mic and other apps,
    /// play / pause for a file.
    func primaryAction() {
        if settings.source == .file, let file = fileCapture, isRunning {
            if file.isPlaying { file.pause() } else { file.play() }
            return
        }
        if isRunning { stop() } else { start() }
    }

    func toggleFreeze() {
        frozen.toggle()
        engine.setPaused(frozen)
    }

    func resetMeters() { engine.resetMeters() }

    func dismissWarning() { engine.clearWarning() }

    /// The app came to the front or left it. In the background nothing is drawn,
    /// so nothing is analysed; the microphone is let go entirely (no orange dot
    /// for an app nobody is looking at), while a file keeps playing.
    func setActive(_ value: Bool) {
        active = value
        engine.setVisible(value)
        engine.inAppPage = value ? settings.page : nil
        if value {
            if !isRunning, settings.source != .file { start() }
        } else if settings.source == .microphone {
            stop()
        }
    }

    // ---- files ---------------------------------------------------------------------

    /// Copies a picked file into the app's own storage, so it can be played again
    /// after a relaunch without keeping a permission to the original.
    func importFile(_ picked: URL) {
        let scoped = picked.startAccessingSecurityScopedResource()
        defer { if scoped { picked.stopAccessingSecurityScopedResource() } }
        let dir = Self.filesDirectory
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let target = dir.appendingPathComponent(picked.lastPathComponent)
        do {
            // One file at a time is all the player has; keep the folder that size.
            for old in Self.importedFiles() where old != target { try? FileManager.default.removeItem(at: old) }
            if FileManager.default.fileExists(atPath: target.path) { try FileManager.default.removeItem(at: target) }
            try FileManager.default.copyItem(at: picked, to: target)
        } catch {
            engineState = .failed(kind: .unavailable, message: "Couldn't open that file: \(error.localizedDescription)")
            return
        }
        fileURL = target
        stop()
        settings.source = .file
        start()
    }

    var fileName: String? { fileURL?.deletingPathExtension().lastPathComponent }

    private static var filesDirectory: URL {
        URL.applicationSupportDirectory.appendingPathComponent("Playing", isDirectory: true)
    }

    private static func importedFiles() -> [URL] {
        (try? FileManager.default.contentsOfDirectory(at: filesDirectory, includingPropertiesForKeys: nil)) ?? []
    }

    // ---- look ---------------------------------------------------------------------

    var colorScheme: ColorScheme? {
        switch settings.theme {
        case .system: nil
        case .dark: .dark
        case .light: .light
        }
    }
}
