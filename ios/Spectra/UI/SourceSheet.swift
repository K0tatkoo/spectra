import ReplayKit
import SwiftUI
import SpectraCore

/// Where the sound comes from: the microphone, a file Spectra plays, or other
/// apps through a screen broadcast.
struct SourceSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        @Bindable var model = model
        NavigationStack {
            List {
                Section {
                    row(.microphone, "mic.fill", "Microphone", "What the \(UIDevice.current.model) hears, live.")
                    row(.file, "music.note", "Audio file",
                        model.fileName.map { "Playing “\($0)”" } ?? "Play a file through Spectra — a clean digital signal.")
                    row(.otherApps, "app.connected.to.app.below.fill", "Other apps",
                        "Spotify, YouTube and others, through a screen broadcast.")
                }

                switch model.settings.source {
                case .microphone:
                    Section {
                        Toggle("Stereo", isOn: $model.settings.stereo)
                        Picker("Mode", selection: $model.settings.micMode) {
                            ForEach(MicMode.allCases, id: \.self) { Text($0.label).tag($0) }
                        }
                        .disabled(model.settings.stereo)
                    } footer: {
                        if model.settings.stereo {
                            Text("iOS records stereo only in its video-recording mode, from the mics on the back, with some processing. Turn Stereo off to choose the mode, Measurement for a flat mic.")
                        } else {
                            Text(model.settings.micMode.blurb)
                        }
                    }
                case .file:
                    FileSection()
                case .otherApps:
                    Section {
                        HStack(spacing: 14) {
                            BroadcastButton()
                                .frame(width: 44, height: 44)
                            Text("Start or stop the broadcast").font(.subheadline)
                        }
                    } footer: {
                        Text("iOS lets an app hear other apps only through a screen broadcast. Tap the button, "
                            + "choose Spectra, then Start Broadcast and come back. iOS shows the red recording "
                            + "indicator meanwhile; nothing is recorded or sent anywhere — only the sound is "
                            + "passed to Spectra. Apple Music and other DRM-protected audio arrive as silence.")
                    }
                }
            }
            .navigationTitle("Input")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done", systemImage: "checkmark") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func row(_ source: SourceKind, _ symbol: String, _ title: String, _ detail: String) -> some View {
        Button {
            if source == .file && model.fileName == nil {
                model.showSource = false
                model.showFileImporter = true
            } else {
                model.choose(source)
            }
        } label: {
            HStack(spacing: 14) {
                Image(systemName: symbol).font(.title3).foregroundStyle(.tint).frame(width: 30)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).foregroundStyle(.primary)
                    Text(detail).font(.footnote).foregroundStyle(.secondary)
                }
                Spacer()
                if model.settings.source == source {
                    Image(systemName: "checkmark").font(.body.weight(.semibold)).foregroundStyle(.tint)
                }
            }
            .contentShape(.rect)
        }
        // A plain row: a list button would tint its whole label.
        .buttonStyle(.plain)
    }
}

/// The file's transport: position, loop, and choosing another file.
private struct FileSection: View {
    @Environment(AppModel.self) private var model
    @State private var scrub: Double?

    var body: some View {
        Section {
            if let file = model.fileCapture {
                TimelineView(.periodic(from: .now, by: 0.25)) { _ in
                    VStack(spacing: 4) {
                        Slider(value: Binding(get: { scrub ?? file.currentTime }, set: { scrub = $0 }),
                               in: 0...max(file.duration, 0.1)) { editing in
                            if !editing, let to = scrub {
                                file.seek(to: to)
                                scrub = nil
                            }
                        }
                        HStack {
                            Text(clock(scrub ?? file.currentTime))
                            Spacer()
                            Text(clock(file.duration))
                        }
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                    }
                }
                Toggle("Loop", isOn: Binding(get: { file.loops }, set: { file.setLoop($0) }))
            }
            Button("Choose another file…", systemImage: "folder") {
                model.showSource = false
                model.showFileImporter = true
            }
        } footer: {
            Text("A copy of the file is kept in Spectra so it plays again next time. Oscilloscope music needs "
                + "a file: both channels arrive exactly as they were mastered.")
        }
    }

    private func clock(_ seconds: Double) -> String {
        let s = Int(seconds.isFinite ? seconds : 0)
        return String(format: "%d:%02d", s / 60, s % 60)
    }
}

/// iOS's own broadcast picker, pointed at Spectra's extension. It is the only
/// control that may start a screen broadcast.
private struct BroadcastButton: UIViewRepresentable {
    func makeUIView(context: Context) -> RPSystemBroadcastPickerView {
        let picker = RPSystemBroadcastPickerView(frame: CGRect(x: 0, y: 0, width: 44, height: 44))
        picker.preferredExtension = "com.n3d.spectra.broadcast"
        picker.showsMicrophoneButton = false
        return picker
    }

    func updateUIView(_ view: RPSystemBroadcastPickerView, context: Context) {}
}
