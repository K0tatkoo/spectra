import SwiftUI
import SpectraCore

/// Everything the user can change — SettingsScreen.kt's sections with the same
/// ranges, as an iOS form.
struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var confirmReset = false

    var body: some View {
        @Bindable var model = model
        let s = model.settings
        NavigationStack {
            Form {
                Section {
                    Picker("Sample rate", selection: $model.settings.sampleRate) {
                        ForEach(Settings.sampleRates, id: \.self) { Text("\(Double($0) / 1000, specifier: "%g") kHz").tag($0) }
                    }
                    LabeledSlider("Input trim", value: $model.settings.inputGainDb, in: -24...24, default: 0) {
                        String(format: "%+.1f dB", $0)
                    }
                    Toggle("Show dB SPL", isOn: $model.settings.showSpl)
                    if s.showSpl {
                        LabeledSlider("dBFS → dB SPL offset", value: $model.settings.splOffsetDb, in: 0...140, default: 0) {
                            String(format: "%.1f dB", $0)
                        }
                    }
                } header: {
                    Text("Input")
                } footer: {
                    Text("Trim matches a known reference level. The microphone's mode and stereo are on the input sheet.")
                }

                Section {
                    Picker("FFT size", selection: $model.settings.fftSize) {
                        ForEach(Settings.fftSizes, id: \.self) { Text("\($0)").tag($0) }
                    }
                    Picker("Window", selection: $model.settings.window) {
                        ForEach(WindowFunction.allCases, id: \.self) { Text($0.label).tag($0) }
                    }
                    Picker("Overlap", selection: $model.settings.overlap) {
                        ForEach(Settings.overlaps, id: \.self) { Text(overlapLabel($0)).tag($0) }
                    }
                } header: {
                    Text("Transform")
                } footer: {
                    Text(s.window.blurb + " Bin spacing \(fmt(Float(s.sampleRate) / Float(s.fftSize), 2)) Hz · window "
                        + "\(fmt(Float(s.fftSize) * 1000 / Float(s.sampleRate), 1)) ms · refresh \(fmt(Float(s.sampleRate) / Float(s.hopSize), 0)) Hz")
                }

                Section("Spectrum") {
                    Picker("Frequency scale", selection: $model.settings.freqScale) {
                        ForEach(FreqScale.allCases, id: \.self) { Text($0.label).tag($0) }
                    }
                    FrequencySlider("Lowest frequency", hz: $model.settings.minHz, default: 20)
                    FrequencySlider("Highest frequency", hz: $model.settings.maxHz, default: 20000)
                    LabeledSlider("Display floor", value: $model.settings.floorDb, in: -140 ... -40, default: -100) { "\(Int($0)) dB" }
                    LabeledSlider("Display ceiling", value: $model.settings.ceilingDb, in: -40...20, default: 0) { "\(Int($0)) dB" }
                    LabeledSlider("Slope tilt", value: $model.settings.tiltDbPerOct, in: -6...9, default: 0) {
                        String(format: "%+.1f dB/oct", $0)
                    }
                    Picker("Weighting", selection: $model.settings.weighting) {
                        ForEach(Weighting.allCases, id: \.self) { Text($0.label).tag($0) }
                    }
                    Picker("Style", selection: $model.settings.spectrumStyle) {
                        ForEach(SpectrumStyle.allCases, id: \.self) { Text($0.label).tag($0) }
                    }
                    LabeledSlider("Attack", value: $model.settings.attackMs, in: 0...200, default: 20) { "\(Int($0)) ms" }
                    LabeledSlider("Release", value: $model.settings.releaseMs, in: 20...2000, default: 300) { "\(Int($0)) ms" }
                    Toggle("Peak hold", isOn: $model.settings.peakHold)
                    if s.peakHold {
                        LabeledSlider("Hold time", value: $model.settings.peakHoldMs, in: 100...5000, default: 900) { "\(Int($0)) ms" }
                        LabeledSlider("Fall rate", value: $model.settings.peakFallDbPerSec, in: 2...120, default: 24) { "\(Int($0)) dB/s" }
                    }
                    Toggle("Grid", isOn: $model.settings.showGrid)
                    Toggle("Labels", isOn: $model.settings.showLabels)
                }

                Section {
                    Picker("Slope", selection: $model.settings.bandSlope) {
                        ForEach(BandSlope.allCases, id: \.self) { Text($0.label).tag($0) }
                    }
                    LabeledSlider("Band attack", value: $model.settings.bandAttackMs, in: 0...100, default: 8) { "\(Int($0)) ms" }
                    LabeledSlider("Band release", value: $model.settings.bandReleaseMs, in: 20...1500, default: 220) { "\(Int($0)) ms" }
                    ForEach(s.bands.indices, id: \.self) { i in
                        NavigationLink(s.bands[i].name) { BandEditor(index: i) }
                    }
                    Button("Restore default bands") { model.settings.bands = Settings.defaultBands }
                } header: {
                    Text("Bands")
                } footer: {
                    Text("Real Butterworth band-passes in time, not summed FFT bins, so an 808's attack is legible.")
                }

                Section {
                    Picker("Colour map", selection: $model.settings.colorMap) {
                        ForEach(ColorMap.allCases, id: \.self) { Text($0.label).tag($0) }
                    }
                    LabeledSlider("Floor", value: $model.settings.spectrogramFloorDb, in: -140 ... -30, default: -90) { "\(Int($0)) dB" }
                    LabeledSlider("Ceiling", value: $model.settings.spectrogramCeilingDb, in: -60...6, default: -6) { "\(Int($0)) dB" }
                } header: {
                    Text("Spectrogram")
                } footer: {
                    Text("Changing either clears the history — the columns are stored already mapped into this range.")
                }

                Section {
                    Toggle("Loudness", isOn: $model.settings.loudnessEnabled)
                    Toggle("True peak", isOn: $model.settings.truePeakEnabled)
                    if s.truePeakEnabled {
                        Picker("Oversampling", selection: $model.settings.truePeakOversample) {
                            ForEach([2, 4, 8], id: \.self) { Text("\($0)×").tag($0) }
                        }
                        .pickerStyle(.segmented)
                    }
                    LabeledSlider("Target", value: $model.settings.loudnessTargetLufs, in: -30 ... -5, default: -14) { "\(Int($0)) LUFS" }
                } header: {
                    Text("Loudness (BS.1770-4)")
                } footer: {
                    Text("K-weighted momentary, short-term, integrated and LRA. 4× is the BS.1770 minimum for true peak; 8× is closer above 10 kHz.")
                }

                Section("Stereo") {
                    LabeledSlider("Goniometer persistence", value: $model.settings.goniometerPersistence, in: 0...0.95, default: 0.55) {
                        String(format: "%.2f", $0)
                    }
                    LabeledSlider("Correlation window", value: $model.settings.correlationWindowMs, in: 20...2000, default: 300) { "\(Int($0)) ms" }
                }

                Section {
                    LabeledSlider("Time window", value: $model.settings.scopeWindowMs, in: 10...100, default: 35) { "\(Int($0)) ms" }
                    Picker("Clean-up", selection: Binding(
                        get: { Settings.scopeCleans.min { abs($0 - s.scopeCleanMs) < abs($1 - s.scopeCleanMs) } ?? 60 },
                        set: { model.settings.scopeCleanMs = $0 }
                    )) {
                        ForEach(Settings.scopeCleans, id: \.self) { Text(cleanLabel($0)).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    Picker("2A03 region", selection: $model.settings.nesRegion) {
                        ForEach(Nes2A03.Region.allCases, id: \.self) { Text($0.label).tag($0) }
                    }
                } header: {
                    Text("Held-still scopes")
                } footer: {
                    Text("The picture is a fixed stretch of time, so a higher note shows more, narrower cycles. Clean-up "
                        + "stacks the last few cycles of a held note: what belongs to the note stays sharp, leftovers of "
                        + "other instruments fade. PAL runs the 2A03 slower, so the same register plays about 7.6 % flat.")
                }

                Section {
                    Toggle("Synth lane", isOn: $model.settings.stemSynth)
                    Picker("Stem model threads", selection: $model.settings.stemThreads) {
                        Text("1 core").tag(1)
                        Text("2 cores").tag(2)
                    }
                    .pickerStyle(.segmented)
                } header: {
                    Text("Stems")
                } footer: {
                    Text("The Stems page splits the music into vocals, other, bass and drums with a neural network running "
                        + "on this iPhone — nothing is uploaded. It only runs while that page is on screen. The synth lane "
                        + "takes synth leads and pads out of the vocals and other stems; it is tuned for electronic music, "
                        + "hardstyle above all.")
                }

                Section {
                    Picker("Phosphor", selection: $model.settings.oscPhosphor) {
                        ForEach(Phosphor.allCases, id: \.self) { Text($0.label).tag($0) }
                    }
                    LogSlider("Intensity", value: $model.settings.oscIntensity, exponent: -3...3, default: 1) {
                        "×" + String(format: "%.2f", $0)
                    }
                    PersistenceSlider(ms: $model.settings.oscPersistenceMs)
                    LabeledSlider("Glow", value: $model.settings.oscGlow, in: 0...1, default: 0.5) { String(format: "%.2f", $0) }
                    LogSlider("Zoom", value: $model.settings.oscZoom, exponent: -1...2, default: 1) {
                        "×" + String(format: "%.2f", $0)
                    }
                } header: {
                    Text("Oscilloscope")
                } footer: {
                    Text("A simulated tube: brightness is where the beam lingers, so slow strokes are bright, fast jumps "
                        + "are faint threads and a resting beam burns a dot in the middle. The audio is drawn at four times "
                        + "its sample rate, band-limited — the smooth path a real beam takes behind a converter.")
                }

                Section {
                    Picker("Appearance", selection: $model.settings.theme) {
                        ForEach(ThemeMode.allCases, id: \.self) { Text($0.label).tag($0) }
                    }
                    Picker("Frame rate", selection: $model.settings.uiFps) {
                        ForEach([30, 60, 120], id: \.self) { Text("\($0) fps").tag($0) }
                    }
                } header: {
                    Text("Display")
                } footer: {
                    Text("A redraw cap. The analysis itself always runs at the transform's own rate.")
                }

                Section {
                    LabeledContent("Version", value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "—")
                    NavigationLink("Credits and licences") { CreditsView() }
                    Button("Reset everything to defaults", role: .destructive) { confirmReset = true }
                } header: {
                    Text("About")
                } footer: {
                    Text("Spectra never sends audio or measurements anywhere. Everything is analysed on this iPhone.")
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done", systemImage: "checkmark") { dismiss() }
                }
            }
            .confirmationDialog("Reset every setting to its default?", isPresented: $confirmReset, titleVisibility: .visible) {
                Button("Reset", role: .destructive) {
                    var fresh = Settings()
                    fresh.source = model.settings.source
                    fresh.page = model.settings.page
                    model.settings = fresh
                }
            }
        }
    }

    private func overlapLabel(_ n: Int) -> String {
        switch n {
        case 2: "50 %"
        case 4: "75 %"
        default: "87.5 %"
        }
    }

    private func cleanLabel(_ ms: Float) -> String {
        switch ms {
        case 0: "Off"
        case 30: "Light"
        case 60: "Medium"
        default: "Strong"
        }
    }

    private func fmt(_ v: Float, _ decimals: Int) -> String { String(format: "%.\(decimals)f", v) }
}

/// One band's name, corners and whether it shows.
private struct BandEditor: View {
    let index: Int
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        let fallback = index < Settings.defaultBands.count ? Settings.defaultBands[index] : nil
        Form {
            Toggle("Show", isOn: $model.settings.bands[index].enabled)
            TextField("Name", text: $model.settings.bands[index].name)
            FrequencySlider("Low corner", hz: $model.settings.bands[index].lowHz, default: fallback?.lowHz ?? 20)
            FrequencySlider("High corner", hz: $model.settings.bands[index].highHz, default: fallback?.highHz ?? 20000)
        }
        .navigationTitle(model.settings.bands[index].name)
    }
}

/// A slider with its label and value above it. A double tap on the label puts
/// it back to its default — the same reset the Android slider has.
struct LabeledSlider: View {
    let title: String
    @Binding var value: Float
    let range: ClosedRange<Float>
    let defaultValue: Float
    let text: (Float) -> String

    init(_ title: String, value: Binding<Float>, in range: ClosedRange<Float>, default defaultValue: Float, text: @escaping (Float) -> String) {
        self.title = title
        _value = value
        self.range = range
        self.defaultValue = defaultValue
        self.text = text
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(title)
                Spacer()
                Text(text(value)).foregroundStyle(.secondary).monospacedDigit()
            }
            .contentShape(.rect)
            .onTapGesture(count: 2) { value = defaultValue }
            Slider(value: $value, in: range)
        }
        .accessibilityElement(children: .combine)
        .accessibilityValue(text(value))
    }
}

/// Frequency sliders are logarithmic: a linear 20 Hz–20 kHz slider spends 95 %
/// of its travel above 1 kHz, which makes the two decades that matter for a
/// bass crossover unusable.
private struct FrequencySlider: View {
    let title: String
    @Binding var hz: Float
    let defaultHz: Float

    init(_ title: String, hz: Binding<Float>, default defaultHz: Float) {
        self.title = title
        _hz = hz
        self.defaultHz = defaultHz
    }

    var body: some View {
        LabeledSlider(title, value: Binding(
            get: { coerceIn(log(max(hz / 20, 1e-3)) / log(1000), 0, 1) },
            set: { hz = Float(ktRoundToInt(20 * pow(1000, coerceIn($0, 0, 1)))) }
        ), in: 0...1, default: coerceIn(log(max(defaultHz / 20, 1e-3)) / log(1000), 0, 1)) { _ in
            hz >= 1000 ? String(format: "%.1f kHz", hz / 1000) : "\(Int(hz)) Hz"
        }
    }
}

/// A slider over a power of two: intensity and zoom double and halve.
private struct LogSlider: View {
    let title: String
    @Binding var value: Float
    let exponent: ClosedRange<Float>
    let defaultValue: Float
    let text: (Float) -> String

    init(_ title: String, value: Binding<Float>, exponent: ClosedRange<Float>, default defaultValue: Float, text: @escaping (Float) -> String) {
        self.title = title
        _value = value
        self.exponent = exponent
        self.defaultValue = defaultValue
        self.text = text
    }

    var body: some View {
        LabeledSlider(title, value: Binding(
            get: { log2(max(value, 1e-3)) },
            set: { value = pow(2, (($0 * 100).rounded()) / 100) }
        ), in: exponent, default: log2(defaultValue)) { _ in text(value) }
    }
}

/// Persistence is logarithmic too: 5 ms to half a second, with room at the
/// short end where the choices differ.
private struct PersistenceSlider: View {
    @Binding var ms: Float

    var body: some View {
        LabeledSlider("Persistence", value: Binding(
            get: { coerceIn(log(max(ms / 5, 1)) / log(100), 0, 1) },
            set: { ms = Float(ktRoundToInt(5 * pow(100, coerceIn($0, 0, 1)))) }
        ), in: 0...1, default: log(25 / 5) / log(100)) { _ in "\(Int(ms)) ms" }
    }
}

/// What Spectra is built on, and the terms it ships them under.
private struct CreditsView: View {
    var body: some View {
        List {
            Section("Stem separation") {
                Text("StemgenRT (HS-TasNet streaming model) — MIT licence.")
                Text("ONNX Runtime — MIT licence, © Microsoft Corporation.")
            }
            Section("Synth lane") {
                Text("Spectra's own synth splitter, trained on MoisesDB, Slakh2100 and MUSDB18-HQ vocals. Its weights are CC BY-NC-SA 4.0.")
            }
            Section("Spectra") {
                Text("Spectra is open source (MIT), by Danny. Source: github.com/K0tatkoo/spectra")
            }
        }
        .navigationTitle("Credits")
    }
}
