import Foundation

// Settings.kt, for iPhone. Every field that decides a number — the transform,
// the display ranges, the ballistics, the bands, loudness, the scopes, the tube —
// is the same field with the same default as on Android and Windows. What differs
// is what the platform differs on: where the audio comes from, and that iOS has
// no notification graphs, overlay or widget to configure.

/// Where the analysed audio comes from.
public enum SourceKind: String, CaseIterable, Codable, Sendable {
    case microphone = "MICROPHONE"
    /// An audio file played through Spectra — the clean digital signal that
    /// device-audio capture gives on Android, and what oscilloscope music needs.
    case file = "FILE"
    /// Other apps' sound, through a screen broadcast (ReplayKit) — iOS's only
    /// route to it. DRM-protected audio (Apple Music) arrives as silence.
    case otherApps = "OTHER_APPS"

    public var label: String {
        switch self {
        case .microphone: "Microphone"
        case .file: "Audio file"
        case .otherApps: "Other apps"
        }
    }
}

/// How iOS prepares the microphone. The measurement mode is iOS's counterpart
/// of Android's UNPROCESSED source: no automatic gain, no noise suppression,
/// no voice processing — the only defensible choice for measurement.
public enum MicMode: String, CaseIterable, Codable, Sendable {
    case measurement = "MEASUREMENT"
    case standard = "STANDARD"
    case voice = "VOICE"

    public var label: String {
        switch self {
        case .measurement: "Measurement"
        case .standard: "Default mic"
        case .voice: "Voice processing"
        }
    }

    public var blurb: String {
        switch self {
        case .measurement: "Raw mic, no gain control or noise suppression. Correct for measurement."
        case .standard: "Whatever iOS does for recording apps. Expect some processing."
        case .voice: "Echo cancellation and noise suppression, tuned for speech. Not for measuring."
        }
    }
}

public enum Weighting: String, CaseIterable, Codable, Sendable {
    case z = "Z", a = "A", c = "C"
    public var label: String {
        switch self {
        case .z: "Z (flat)"
        case .a: "A"
        case .c: "C"
        }
    }
}

public enum FreqScale: String, CaseIterable, Codable, Sendable {
    case log = "LOG", linear = "LINEAR"
    public var label: String { self == .log ? "Logarithmic" : "Linear" }
}

public enum SpectrumStyle: String, CaseIterable, Codable, Sendable {
    case bars = "BARS", line = "LINE", filled = "FILLED"
    public var label: String {
        switch self {
        case .bars: "Bars"
        case .line: "Line"
        case .filled: "Filled curve"
        }
    }
}

public enum ColorMap: String, CaseIterable, Codable, Sendable {
    case nebula = "NEBULA", magma = "MAGMA", viridis = "VIRIDIS", ice = "ICE", mono = "MONO"
    public var label: String {
        switch self {
        case .nebula: "Nebula"
        case .magma: "Magma"
        case .viridis: "Viridis"
        case .ice: "Ice"
        case .mono: "Mono"
        }
    }
}

public enum ThemeMode: String, CaseIterable, Codable, Sendable {
    case system = "SYSTEM", dark = "DARK", light = "LIGHT"
    public var label: String {
        switch self {
        case .system: "Follow system"
        case .dark: "Dark"
        case .light: "Light"
        }
    }
}

public enum BandSlope: String, CaseIterable, Codable, Sendable {
    case db12 = "DB12", db24 = "DB24", db48 = "DB48"
    public var label: String {
        switch self {
        case .db12: "12 dB/oct"
        case .db24: "24 dB/oct"
        case .db48: "48 dB/oct"
        }
    }
    public var order: Int {
        switch self {
        case .db12: 2
        case .db24: 4
        case .db48: 8
        }
    }
}

/// The pages the visualiser shows.
public enum VizPage: String, CaseIterable, Codable, Sendable {
    case spectrum = "SPECTRUM"
    case bands = "BANDS"
    case spectrogram = "SPECTROGRAM"
    case loudness = "LOUDNESS"
    case stereo = "STEREO"
    case waveform = "WAVEFORM"
    case oscilloscope = "OSCILLOSCOPE"
    case stems = "STEMS"

    public var label: String {
        switch self {
        case .spectrum: "Spectrum"
        case .bands: "Bands"
        case .spectrogram: "Spectrogram"
        case .loudness: "Loudness"
        case .stereo: "Stereo"
        case .waveform: "Waveform"
        case .oscilloscope: "Oscilloscope"
        case .stems: "Stems"
        }
    }
}

/// How the Oscilloscope page moves its beam.
public enum OscMode: String, CaseIterable, Codable, Sendable {
    /// The left channel moves the beam across and the right moves it up — the
    /// way oscilloscope music is written to be watched. Anything else draws a
    /// tangle around the diagonal.
    case xy = "XY"
    /// A time base sweeps it across, restarted by the mix crossing zero upwards: the bench scope.
    case yt = "YT"

    public var label: String { self == .xy ? "X-Y" : "Y-T" }
}

/// The colour the Oscilloscope page's tube glows in.
public enum Phosphor: String, CaseIterable, Codable, Sendable {
    /// P31, the green of nearly every analog scope.
    case green = "GREEN"
    case amber = "AMBER"
    case blue = "BLUE"
    /// The app's own cyan.
    case spectra = "SPECTRA"

    public var label: String {
        switch self {
        case .green: "Green"
        case .amber: "Amber"
        case .blue: "Blue"
        case .spectra: "Spectra"
        }
    }
}

/// What the Waveform page draws.
public enum WaveformMode: String, CaseIterable, Codable, Sendable {
    /// The newest audio, scrolling — the classic scope.
    case free = "FREE"
    /// Locked to the loudest note, standing still while it is held.
    case hold = "HOLD"
    /// The NES triangle channel against the chip's own 32-step staircase.
    case nes = "NES"

    public var label: String {
        switch self {
        case .free: "Free-running"
        case .hold: "Hold still"
        case .nes: "2A03 triangle"
        }
    }
}

/// A time base as a bench scope labels it: 100 µs, 2 ms.
public func oscTimeLabel(_ ms: Float) -> String {
    ms < 1 ? "\(ktRoundToInt(ms * 1000)) µs" : "\(ktRoundToInt(ms)) ms"
}

/// Full scale per division at `zoom`. Four divisions from the centre to the
/// edge, so at 1× a division is a quarter of full scale.
public func oscFullScaleLabel(_ zoom: Float) -> String {
    let perDiv = 0.25 / max(zoom, 0.01)
    return String(format: perDiv < 0.1 ? "%.3f" : "%.2f", locale: Locale(identifier: "en_US_POSIX"), Double(perDiv))
}

public struct BandDef: Codable, Hashable, Sendable {
    public var name: String
    public var lowHz: Float
    public var highHz: Float
    public var enabled: Bool = true

    public init(_ name: String, _ lowHz: Float, _ highHz: Float, enabled: Bool = true) {
        self.name = name
        self.lowHz = lowHz
        self.highHz = highHz
        self.enabled = enabled
    }
}

/// Everything the user can change. One flat value: the analysis thread takes a
/// copy once per block, so a settings change takes effect on the next block
/// without a single lock, and can never be seen half-applied.
public struct Settings: Codable, Equatable, Sendable {
    // ---- source ----------------------------------------------------------
    public var source: SourceKind = .microphone
    public var micMode: MicMode = .measurement
    public var sampleRate: Int = 48000
    public var stereo: Bool = true
    /// Trim applied before analysis, dB. For matching a known reference level.
    public var inputGainDb: Float = 0
    /// Added to dBFS to display dB SPL. 94 dB SPL at −X dBFS ⇒ offset = 94 + X.
    public var splOffsetDb: Float = 0
    public var showSpl: Bool = false

    // ---- transform -------------------------------------------------------
    public var fftSize: Int = 4096
    public var window: WindowFunction = .hann
    /// 2 = 50 % overlap, 4 = 75 %, 8 = 87.5 %. Higher is smoother and costlier.
    public var overlap: Int = 4

    // ---- spectrum display ------------------------------------------------
    public var freqScale: FreqScale = .log
    public var minHz: Float = 20
    public var maxHz: Float = 20000
    public var floorDb: Float = -100
    public var ceilingDb: Float = 0
    /// Pink-noise tilt, dB per octave above 1 kHz. 3.0 makes pink noise flat.
    public var tiltDbPerOct: Float = 0
    public var weighting: Weighting = .z
    public var spectrumStyle: SpectrumStyle = .filled
    public var attackMs: Float = 20
    public var releaseMs: Float = 300
    public var peakHold: Bool = true
    public var peakHoldMs: Float = 900
    public var peakFallDbPerSec: Float = 24
    public var showGrid: Bool = true
    public var showLabels: Bool = true

    // ---- bands -----------------------------------------------------------
    public var bands: [BandDef] = Settings.defaultBands
    public var bandSlope: BandSlope = .db24
    public var bandAttackMs: Float = 8
    public var bandReleaseMs: Float = 220

    // ---- spectrogram -----------------------------------------------------
    public var colorMap: ColorMap = .nebula
    public var spectrogramFloorDb: Float = -90
    public var spectrogramCeilingDb: Float = -6

    // ---- loudness --------------------------------------------------------
    public var loudnessEnabled: Bool = true
    public var truePeakEnabled: Bool = true
    /// 4× is the BS.1770 minimum for true peak; 8× is closer at high frequencies.
    public var truePeakOversample: Int = 4
    public var loudnessTargetLufs: Float = -14

    // ---- stereo ----------------------------------------------------------
    public var goniometerPersistence: Float = 0.55
    public var correlationWindowMs: Float = 300

    // ---- held-still scopes and stems ------------------------------------
    public var waveformMode: WaveformMode = .free
    /// Width of every held-still picture. Fixed in time rather than in cycles,
    /// so a higher note shows more, narrower cycles — the picture squeezes and
    /// stretches with the melody the way chiptune channel scopes do.
    public var scopeWindowMs: Float = 35
    /// How much of a held note's recent past is averaged into the picture, in
    /// ms. Time rather than a count of cycles, so a high note and a low one are
    /// cleaned by the same amount and follow a melody equally fast. 0 = off.
    public var scopeCleanMs: Float = 60
    /// Which Stems lanes are held still; the rest scroll like a plain scope.
    /// Only bass by default: a bass line is one note at a time and locks
    /// cleanly, while vocals, leads and drums in a real mix rarely hold one
    /// shape long enough to read held still. A free lane also skips the pitch
    /// detector, the phase and the fold, which are most of what a lane costs.
    public var holdVocals: Bool = false
    public var holdSynth: Bool = false
    public var holdOther: Bool = false
    public var holdBass: Bool = true
    public var holdDrums: Bool = false
    /// Split synths out of the vocals and other stems into a lane of their own,
    /// when the build carries the synth model. A second, much smaller network.
    public var stemSynth: Bool = true
    /// CPU threads for the stem model. One is usually fastest: the work per call is tiny.
    public var stemThreads: Int = 1
    public var nesRegion: Nes2A03.Region = .ntsc

    // ---- oscilloscope ----------------------------------------------------
    public var oscMode: OscMode = .xy
    public var oscPhosphor: Phosphor = .green
    /// Beam current, as a multiple of the default. The tube's brightness knob.
    public var oscIntensity: Float = 1
    /// How long the phosphor keeps glowing, as the time for it to fall to 37 %.
    /// About one frame's worth by default, which is what a camera filming a real
    /// tube sees: the tube itself fades in microseconds.
    public var oscPersistenceMs: Float = 25
    /// Strength of the halo that bright strokes throw on the glass, 0–1.
    public var oscGlow: Float = 0.5
    /// Deflection gain. At 1, full scale reaches the edge of the graticule.
    public var oscZoom: Float = 1
    /// Y-T time base, per division; one of `oscTimeDivs`. The graticule is ten divisions wide.
    public var oscTimeDivMs: Float = 1

    // ---- display ---------------------------------------------------------
    public var theme: ThemeMode = .system
    public var page: VizPage = .spectrum
    /// In-app frame cap. ProMotion iPhones run at 120 Hz; 60 is plenty and halves the draw cost.
    public var uiFps: Int = 60

    public init() {}

    /// Hop between analysis blocks, in samples.
    public var hopSize: Int { fftSize / overlap }

    public static let defaultBands: [BandDef] = [
        // Sub and bass are split at 60 Hz because that is roughly where a
        // phone speaker stops reproducing anything at all — keeping them
        // separate is what makes an 808 legible on this device.
        BandDef("Sub", 20, 60),
        BandDef("Bass", 60, 150),
        BandDef("Low mid", 150, 400),
        BandDef("Mid", 400, 1500),
        BandDef("High mid", 1500, 4000),
        BandDef("Presence", 4000, 10000),
        BandDef("Air", 10000, 20000),
    ]

    /// Off, light, medium, strong.
    public static let scopeCleans: [Float] = [0, 30, 60, 120]

    /// Y-T time bases, ms per division, in a bench scope's 1-2-5 steps.
    public static let oscTimeDivs: [Float] = [0.1, 0.2, 0.5, 1, 2, 5, 10]

    public static let fftSizes = [512, 1024, 2048, 4096, 8192, 16384]
    public static let sampleRates = [44100, 48000]
    public static let overlaps = [2, 4, 8]
}
