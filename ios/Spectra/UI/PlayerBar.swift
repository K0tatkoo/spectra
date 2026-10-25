import SwiftUI
import SpectraCore

/// The input, as a now-playing bar above the tab bar: what Spectra is listening
/// to, how it is going, and the one button that matters for it. Tapping the bar
/// opens the input sheet.
struct PlayerBar: View {
    @Environment(AppModel.self) private var model
    @Environment(\.tabViewBottomAccessoryPlacement) private var placement

    var body: some View {
        // A file's position and whether it is playing are not observable, and a
        // file can stop or loop by itself: the whole bar refreshes on a timeline.
        TimelineView(.periodic(from: .now, by: 0.5)) { _ in bar }
    }

    private var bar: some View {
        HStack(spacing: 12) {
            Image(systemName: symbol)
                .font(.body.weight(.semibold))
                .foregroundStyle(.tint)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 1) {
                Text(title).font(.subheadline.weight(.semibold)).lineLimit(1)
                if placement != .inline {
                    Text(subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1).monospacedDigit()
                }
            }
            Spacer(minLength: 8)
            // Freezes the picture while the sound carries on — Android's pause.
            Button(model.frozen ? "Unfreeze picture" : "Freeze picture",
                   systemImage: model.frozen ? "snowflake.circle.fill" : "snowflake") { model.toggleFreeze() }
                .labelStyle(.iconOnly)
                .font(.title3)
                .foregroundStyle(model.frozen ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
            Button(primaryLabel, systemImage: primarySymbol) { model.primaryAction() }
                .labelStyle(.iconOnly)
                .font(.title3)
                // With a keyboard (an iPad's), the space bar plays and stops.
                .keyboardShortcut(.space, modifiers: [])
        }
        .padding(.horizontal, 16)
        .contentShape(.rect)
        .onTapGesture { model.showSource = true }
        .accessibilityElement(children: .contain)
        .accessibilityAction(named: "Choose input") { model.showSource = true }
    }

    private var symbol: String {
        switch model.settings.source {
        case .microphone: "mic.fill"
        case .file: "music.note"
        case .otherApps: "app.connected.to.app.below.fill"
        }
    }

    private var title: String {
        switch model.settings.source {
        case .microphone: "Microphone"
        case .file: model.fileName ?? "Choose a file"
        case .otherApps: "Other apps"
        }
    }

    private var subtitle: String {
        switch model.engineState {
        case .idle: return model.settings.source == .otherApps ? "Start a broadcast to listen" : "Stopped"
        case .starting: return "Starting…"
        case .failed: return "Stopped · error"
        case .running(_, let describe):
            let frozen = model.frozen ? "frozen · " : ""
            if model.settings.source == .file, let file = model.fileCapture {
                return frozen + "\(clock(file.currentTime)) / \(clock(file.duration))" + (file.isPlaying ? "" : " · paused")
            }
            let lufs = model.engine.frame.map { $0.loudness.shortTerm > -70 ? String(format: " · %.1f LUFS", $0.loudness.shortTerm) : "" } ?? ""
            return frozen + describe + lufs
        }
    }

    private var playing: Bool {
        if model.settings.source == .file { return model.isRunning && (model.fileCapture?.isPlaying ?? false) }
        return model.isRunning
    }

    private var primaryLabel: String {
        if model.settings.source == .file { return playing ? "Pause" : "Play" }
        return playing ? "Stop" : "Listen"
    }

    private var primarySymbol: String {
        if model.settings.source == .file { return playing ? "pause.fill" : "play.fill" }
        return playing ? "stop.fill" : "record.circle"
    }

    private func clock(_ seconds: Double) -> String {
        let s = Int(seconds.isFinite ? seconds : 0)
        return String(format: "%d:%02d", s / 60, s % 60)
    }
}
