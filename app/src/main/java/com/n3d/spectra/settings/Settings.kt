package com.n3d.spectra.settings

import com.n3d.spectra.dsp.WindowFunction
import com.n3d.spectra.dsp.nes.Nes2A03

enum class SourceKind(val label: String) {
    MICROPHONE("Microphone"),
    DEVICE_AUDIO("Device audio"),
}

/**
 * Which [android.media.MediaRecorder.AudioSource] to open.
 *
 * UNPROCESSED is the only one specified to be free of AGC, noise suppression and
 * the mic-array beamformer, which is what makes it the only defensible choice
 * for measurement. It is optional on the platform, so the capture layer probes
 * for it and falls back to VOICE_RECOGNITION (no AGC by convention, though not
 * guaranteed) and only then to MIC.
 */
enum class MicPreset(val label: String, val blurb: String) {
    UNPROCESSED("Unprocessed", "Raw mic, no AGC/NS/beamforming. Correct for measurement."),
    VOICE_RECOGNITION("Voice recognition", "Usually unprocessed. Fallback when raw is unavailable."),
    MIC("Default mic", "Whatever the OS does for recording apps. Expect AGC."),
    CAMCORDER("Camcorder", "Tuned for video. Wider dynamic range, some processing."),
}

enum class Weighting(val label: String) { Z("Z (flat)"), A("A"), C("C") }

enum class FreqScale(val label: String) { LOG("Logarithmic"), LINEAR("Linear") }

enum class SpectrumStyle(val label: String) { BARS("Bars"), LINE("Line"), FILLED("Filled curve") }

enum class ColorMap(val label: String) {
    NEBULA("Nebula"), MAGMA("Magma"), VIRIDIS("Viridis"), ICE("Ice"), MONO("Mono"),
}

enum class ThemeMode(val label: String) { SYSTEM("Follow system"), DARK("Dark"), LIGHT("Light") }

enum class BandSlope(val label: String, val order: Int) {
    DB12("12 dB/oct", 2), DB24("24 dB/oct", 4), DB48("48 dB/oct", 8),
}

/** The pages the visualiser cycles through, in every surface. */
enum class VizPage(val label: String) {
    SPECTRUM("Spectrum"),
    BANDS("Bands"),
    SPECTROGRAM("Spectrogram"),
    LOUDNESS("Loudness"),
    STEREO("Stereo"),
    WAVEFORM("Waveform"),
    STEMS("Stems"),
}

/** What the Waveform page draws. */
enum class WaveformMode(val label: String) {
    /** The newest audio, scrolling — the classic scope. */
    FREE("Free-running"),
    /** Locked to the loudest note, standing still while it is held. */
    HOLD("Hold still"),
    /** The NES triangle channel against the chip's own 32-step staircase. */
    NES("2A03 triangle"),
}

data class BandDef(
    val name: String,
    val lowHz: Float,
    val highHz: Float,
    val enabled: Boolean = true,
)

/**
 * Everything the user can change. One flat immutable object: the analysis thread
 * reads a volatile reference to it once per block, so a settings change takes
 * effect on the next block without a single lock, and can never be seen
 * half-applied.
 */
data class Settings(
    // ---- source ----------------------------------------------------------
    val source: SourceKind = SourceKind.MICROPHONE,
    val micPreset: MicPreset = MicPreset.UNPROCESSED,
    val micAgc: Boolean = false,
    val micNoiseSuppression: Boolean = false,
    val sampleRate: Int = 48000,
    val stereo: Boolean = true,
    /** Trim applied before analysis, dB. For matching a known reference level. */
    val inputGainDb: Float = 0f,
    /** Added to dBFS to display dB SPL. 94 dB SPL at −X dBFS ⇒ offset = 94 + X. */
    val splOffsetDb: Float = 0f,
    val showSpl: Boolean = false,

    // ---- transform -------------------------------------------------------
    val fftSize: Int = 4096,
    val window: WindowFunction = WindowFunction.HANN,
    /** 2 = 50 % overlap, 4 = 75 %, 8 = 87.5 %. Higher is smoother and costlier. */
    val overlap: Int = 4,

    // ---- spectrum display ------------------------------------------------
    val freqScale: FreqScale = FreqScale.LOG,
    val minHz: Float = 20f,
    val maxHz: Float = 20000f,
    val floorDb: Float = -100f,
    val ceilingDb: Float = 0f,
    /** Pink-noise tilt, dB per octave above 1 kHz. 3.0 makes pink noise flat. */
    val tiltDbPerOct: Float = 0f,
    val weighting: Weighting = Weighting.Z,
    val spectrumStyle: SpectrumStyle = SpectrumStyle.FILLED,
    val attackMs: Float = 20f,
    val releaseMs: Float = 300f,
    val peakHold: Boolean = true,
    val peakHoldMs: Float = 900f,
    val peakFallDbPerSec: Float = 24f,
    val showGrid: Boolean = true,
    val showLabels: Boolean = true,

    // ---- bands -----------------------------------------------------------
    val bands: List<BandDef> = DEFAULT_BANDS,
    val bandSlope: BandSlope = BandSlope.DB24,
    val bandAttackMs: Float = 8f,
    val bandReleaseMs: Float = 220f,

    // ---- spectrogram -----------------------------------------------------
    val colorMap: ColorMap = ColorMap.NEBULA,
    val spectrogramFloorDb: Float = -90f,
    val spectrogramCeilingDb: Float = -6f,

    // ---- loudness --------------------------------------------------------
    val loudnessEnabled: Boolean = true,
    val truePeakEnabled: Boolean = true,
    /** 4× is the BS.1770 minimum for true peak; 8× is closer at high frequencies. */
    val truePeakOversample: Int = 4,
    val loudnessTargetLufs: Float = -14f,

    // ---- stereo ----------------------------------------------------------
    val goniometerPersistence: Float = 0.55f,
    val correlationWindowMs: Float = 300f,

    // ---- held-still scopes and stems ------------------------------------
    val waveformMode: WaveformMode = WaveformMode.FREE,
    /**
     * Width of every held-still picture. Fixed in time rather than in cycles,
     * so a higher note shows more, narrower cycles — the picture squeezes and
     * stretches with the melody the way chiptune channel scopes do.
     */
    val scopeWindowMs: Float = 35f,
    /**
     * How much of a held note's recent past is averaged into the picture, in
     * ms. Time rather than a count of cycles, so a high note and a low one are
     * cleaned by the same amount and follow a melody equally fast. 0 = off.
     */
    val scopeCleanMs: Float = 60f,
    /** CPU threads for the stem model. One is usually fastest: the work per call is tiny. */
    val stemThreads: Int = 1,
    val nesRegion: Nes2A03.Region = Nes2A03.Region.NTSC,

    // ---- display ---------------------------------------------------------
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val page: VizPage = VizPage.SPECTRUM,
    /** In-app frame cap. The S24 Ultra runs at 120 Hz; 60 is plenty and halves the draw cost. */
    val uiFps: Int = 60,

    // ---- background surfaces --------------------------------------------
    val notificationEnabled: Boolean = true,
    /** The platform throttles notification updates; above ~12 they are dropped, not queued. */
    val notificationFps: Int = 10,
    val notificationPage: VizPage = VizPage.SPECTRUM,
    val overlayEnabled: Boolean = false,
    val overlayFps: Int = 60,
    val overlayPage: VizPage = VizPage.SPECTRUM,
    val overlayOpacity: Float = 1f,
    val widgetEnabled: Boolean = false,
    val widgetFps: Int = 2,
    val widgetPage: VizPage = VizPage.BANDS,
    /** Stop rendering (not capturing) while the screen is off. Saves most of the battery. */
    val pauseRenderWhenScreenOff: Boolean = true,
) {
    /** Hop between analysis blocks, in samples. */
    val hopSize: Int get() = fftSize / overlap

    companion object {
        val DEFAULT_BANDS = listOf(
            // Sub and bass are split at 60 Hz because that is roughly where a
            // phone speaker stops reproducing anything at all — keeping them
            // separate is what makes an 808 legible on this device.
            BandDef("Sub", 20f, 60f),
            BandDef("Bass", 60f, 150f),
            BandDef("Low mid", 150f, 400f),
            BandDef("Mid", 400f, 1500f),
            BandDef("High mid", 1500f, 4000f),
            BandDef("Presence", 4000f, 10000f),
            BandDef("Air", 10000f, 20000f),
        )

        /** Off, light, medium, strong. */
        val SCOPE_CLEANS = listOf(0f, 30f, 60f, 120f)

        val FFT_SIZES = listOf(512, 1024, 2048, 4096, 8192, 16384)
        val SAMPLE_RATES = listOf(44100, 48000)
        val OVERLAPS = listOf(2, 4, 8)
    }
}
