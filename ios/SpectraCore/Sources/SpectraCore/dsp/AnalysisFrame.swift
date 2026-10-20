/// One immutable snapshot of everything the pages might want to draw.
///
/// Arrays that are cheap and change every frame (the spectrum, the scope trace,
/// the goniometer cloud) are copied into the frame. Arrays that are large and
/// mostly unchanged (the spectrogram, the band histories) are *referenced* — the
/// frame carries a pointer to the engine's own ring buffers and the painter
/// snapshots them itself. Copying 128 KB of spectrogram sixty times a second to
/// redraw the two columns that changed would be the single most expensive thing
/// in the app.
public final class AnalysisFrame: Sendable {
    public let seq: Int64
    public let sampleRate: Int
    public let fftSize: Int
    /// Hz per FFT bin.
    public let binHz: Float

    /// Display-corrected magnitudes, dBFS, index 0...fftSize/2.
    public let magnitudesDb: [Float]
    /// Peak-hold trace over `magnitudesDb`, or empty when peak hold is off.
    public let peakDb: [Float]

    public let bands: [BandReading]

    /// Decimated scope traces, −1...1. `waveR` is empty for a mono source.
    public let waveL: [Float]
    public let waveR: [Float]

    /// Goniometer cloud as interleaved x,y in −1...1. Empty for mono.
    public let gonio: [Float]
    public let correlation: Float
    public let stereoWidth: Float

    public let loudness: LoudnessReading

    public let rmsDbL: Float
    public let rmsDbR: Float
    public let peakDbL: Float
    public let peakDbR: Float

    public let clipped: Bool
    /// True once the input has been digital silence for long enough to be suspicious.
    public let silent: Bool

    public let spectrogram: SpectrogramBuffer

    /// Held-still scopes and stems, when a page that shows them is on screen.
    public let scopes: ScopeFrame?
    /// The 2A03 triangle reading, when that waveform mode is on screen.
    public let nes: NesReading?
    /// Every captured frame, both channels, for the Oscilloscope page's beam.
    /// Referenced like `spectrogram`: each surface reads the stretch it needs.
    public let beam: StereoFeed?

    public init(
        seq: Int64, sampleRate: Int, fftSize: Int, binHz: Float,
        magnitudesDb: [Float], peakDb: [Float], bands: [BandReading],
        waveL: [Float], waveR: [Float], gonio: [Float], correlation: Float, stereoWidth: Float,
        loudness: LoudnessReading,
        rmsDbL: Float, rmsDbR: Float, peakDbL: Float, peakDbR: Float,
        clipped: Bool, silent: Bool, spectrogram: SpectrogramBuffer,
        scopes: ScopeFrame? = nil, nes: NesReading? = nil, beam: StereoFeed? = nil
    ) {
        self.seq = seq
        self.sampleRate = sampleRate
        self.fftSize = fftSize
        self.binHz = binHz
        self.magnitudesDb = magnitudesDb
        self.peakDb = peakDb
        self.bands = bands
        self.waveL = waveL
        self.waveR = waveR
        self.gonio = gonio
        self.correlation = correlation
        self.stereoWidth = stereoWidth
        self.loudness = loudness
        self.rmsDbL = rmsDbL
        self.rmsDbR = rmsDbR
        self.peakDbL = peakDbL
        self.peakDbR = peakDbR
        self.clipped = clipped
        self.silent = silent
        self.spectrogram = spectrogram
        self.scopes = scopes
        self.nes = nes
        self.beam = beam
    }

    public var binCount: Int { magnitudesDb.count }

    /// Centre frequency of bin `i`.
    public func freqOf(_ i: Int) -> Float { Float(i) * binHz }
}

public struct BandReading: Sendable {
    public let name: String
    public let lowHz: Float
    public let highHz: Float
    /// Smoothed band RMS in dBFS (full-scale sine in band ≈ −3 dBFS).
    public let rmsDb: Float
    /// Sample-accurate band peak in dBFS, unsmoothed.
    public let peakDb: Float
    /// Peak-hold marker in dBFS.
    public let holdDb: Float
    /// Shared scrolling history of `rmsDb`; snapshot it, do not mutate it.
    public let history: ScrollBuffer

    public init(name: String, lowHz: Float, highHz: Float, rmsDb: Float, peakDb: Float, holdDb: Float, history: ScrollBuffer) {
        self.name = name
        self.lowHz = lowHz
        self.highHz = highHz
        self.rmsDb = rmsDb
        self.peakDb = peakDb
        self.holdDb = holdDb
        self.history = history
    }
}

public struct LoudnessReading: Sendable, Equatable {
    /// 400 ms window, LUFS.
    public let momentary: Float
    /// 3 s window, LUFS.
    public let shortTerm: Float
    /// Gated integrated loudness since the last reset, LUFS.
    public let integrated: Float
    /// Loudness range, LU.
    public let range: Float
    /// Inter-sample true peak since reset, dBTP.
    public let truePeakDb: Float
    /// Seconds of audio folded into `integrated`.
    public let integratedSeconds: Float

    public init(momentary: Float, shortTerm: Float, integrated: Float, range: Float, truePeakDb: Float, integratedSeconds: Float) {
        self.momentary = momentary
        self.shortTerm = shortTerm
        self.integrated = integrated
        self.range = range
        self.truePeakDb = truePeakDb
        self.integratedSeconds = integratedSeconds
    }

    /// BS.1770 uses −∞ for "not enough audio yet"; −70 is the absolute gate.
    public static let silent: Float = -70
    public static let empty = LoudnessReading(
        momentary: silent, shortTerm: silent, integrated: silent, range: 0, truePeakDb: -144, integratedSeconds: 0
    )
}
