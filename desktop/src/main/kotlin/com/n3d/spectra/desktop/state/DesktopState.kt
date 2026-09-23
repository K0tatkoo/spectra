package com.n3d.spectra.desktop.state

import com.n3d.spectra.dsp.nes.NesOptions
import com.n3d.spectra.dsp.WindowFunction
import com.n3d.spectra.settings.BandDef
import com.n3d.spectra.settings.BandSlope
import com.n3d.spectra.settings.ColorMap
import com.n3d.spectra.settings.FreqScale
import com.n3d.spectra.settings.Settings
import com.n3d.spectra.settings.SpectrumStyle
import com.n3d.spectra.settings.ThemeMode
import com.n3d.spectra.settings.VizPage
import com.n3d.spectra.settings.Weighting
import java.io.File
import java.util.Properties

/** The pages the desktop build cycles through. Same six as Android. */
enum class DesktopPage(val label: String, val page: VizPage) {
    SPECTRUM("Spectrum", VizPage.SPECTRUM),
    BANDS("Bands", VizPage.BANDS),
    SPECTROGRAM("Spectrogram", VizPage.SPECTROGRAM),
    LOUDNESS("Loudness", VizPage.LOUDNESS),
    STEREO("Stereo", VizPage.STEREO),
    WAVEFORM("Waveform", VizPage.WAVEFORM),
}

/**
 * How the waveform page draws.
 *
 * [FREE] is the scope the Android build has: the newest samples, scrolling. [NES]
 * is the 2A03 mode — period-locked, folded and compared against the chip's own
 * 32-step sequence. It is a mode of the waveform rather than a seventh page
 * because it answers the same question the scope does, just with the answer
 * held still.
 */
enum class WaveMode(val label: String) {
    FREE("Free-running"),
    NES("NES 2A03 triangle"),
}

/**
 * Everything the desktop build remembers.
 *
 * The shared [Settings] is carried whole so the analysis code — which is the
 * Android app's, unchanged — sees exactly the object it expects. The fields that
 * exist only here sit alongside it rather than being bolted onto it, which is
 * what keeps the shared model free of a Windows-only display mode.
 */
data class DesktopState(
    val settings: Settings = Settings(),
    val nes: NesOptions = NesOptions(),
    val waveMode: WaveMode = WaveMode.FREE,
    val page: DesktopPage = DesktopPage.SPECTRUM,
    /** Mixer name of the chosen input, or null for "ask the system". */
    val deviceName: String? = null,
    /** Multiplies every dp in the painter. The window can be very large or very small. */
    val uiScale: Float = 1f,
    val windowWidth: Int = 1280,
    val windowHeight: Int = 800,
    val maximized: Boolean = false,
    val sidebarVisible: Boolean = true,
)

/**
 * Persistence, as a plain properties file.
 *
 * Written out field by field on purpose. Reflection would be shorter and would
 * also mean a renamed field silently loses a user's setting, and pulling in
 * kotlin-reflect would roughly triple the size of the shipped jar for the sake of
 * sixty lines.
 */
object StateStore {

    private val file: File by lazy {
        val base = when {
            System.getProperty("os.name").orEmpty().startsWith("Windows") ->
                System.getenv("APPDATA")?.let { File(it) } ?: File(System.getProperty("user.home"), "AppData/Roaming")
            System.getProperty("os.name").orEmpty().contains("Mac") ->
                File(System.getProperty("user.home"), "Library/Application Support")
            else -> System.getenv("XDG_CONFIG_HOME")?.let { File(it) }
                ?: File(System.getProperty("user.home"), ".config")
        }
        File(File(base, "Spectra").apply { mkdirs() }, "spectra.properties")
    }

    fun load(): DesktopState {
        val p = Properties()
        if (file.exists()) {
            runCatching { file.inputStream().use { p.load(it) } }
        }
        val d = DesktopState()
        val s = d.settings
        val settings = s.copy(
            sampleRate = p.int("sampleRate", s.sampleRate),
            stereo = p.bool("stereo", s.stereo),
            inputGainDb = p.float("inputGainDb", s.inputGainDb),
            splOffsetDb = p.float("splOffsetDb", s.splOffsetDb),
            showSpl = p.bool("showSpl", s.showSpl),
            fftSize = p.int("fftSize", s.fftSize),
            window = p.enum("window", s.window),
            overlap = p.int("overlap", s.overlap),
            freqScale = p.enum("freqScale", s.freqScale),
            minHz = p.float("minHz", s.minHz),
            maxHz = p.float("maxHz", s.maxHz),
            floorDb = p.float("floorDb", s.floorDb),
            ceilingDb = p.float("ceilingDb", s.ceilingDb),
            tiltDbPerOct = p.float("tiltDbPerOct", s.tiltDbPerOct),
            weighting = p.enum("weighting", s.weighting),
            spectrumStyle = p.enum("spectrumStyle", s.spectrumStyle),
            attackMs = p.float("attackMs", s.attackMs),
            releaseMs = p.float("releaseMs", s.releaseMs),
            peakHold = p.bool("peakHold", s.peakHold),
            peakHoldMs = p.float("peakHoldMs", s.peakHoldMs),
            peakFallDbPerSec = p.float("peakFallDbPerSec", s.peakFallDbPerSec),
            showGrid = p.bool("showGrid", s.showGrid),
            showLabels = p.bool("showLabels", s.showLabels),
            bands = p.bands("bands", s.bands),
            bandSlope = p.enum("bandSlope", s.bandSlope),
            bandAttackMs = p.float("bandAttackMs", s.bandAttackMs),
            bandReleaseMs = p.float("bandReleaseMs", s.bandReleaseMs),
            colorMap = p.enum("colorMap", s.colorMap),
            spectrogramFloorDb = p.float("spectrogramFloorDb", s.spectrogramFloorDb),
            spectrogramCeilingDb = p.float("spectrogramCeilingDb", s.spectrogramCeilingDb),
            loudnessEnabled = p.bool("loudnessEnabled", s.loudnessEnabled),
            truePeakEnabled = p.bool("truePeakEnabled", s.truePeakEnabled),
            truePeakOversample = p.int("truePeakOversample", s.truePeakOversample),
            loudnessTargetLufs = p.float("loudnessTargetLufs", s.loudnessTargetLufs),
            goniometerPersistence = p.float("goniometerPersistence", s.goniometerPersistence),
            correlationWindowMs = p.float("correlationWindowMs", s.correlationWindowMs),
            theme = p.enum("theme", s.theme),
            uiFps = p.int("uiFps", s.uiFps),
        )
        val n = d.nes
        val nes = n.copy(
            region = p.enum("nes.region", n.region),
            lockToTimer = p.bool("nes.lockToTimer", n.lockToTimer),
            cycles = p.int("nes.cycles", n.cycles),
            huntMinHz = p.float("nes.huntMinHz", n.huntMinHz),
            huntMaxHz = p.float("nes.huntMaxHz", n.huntMaxHz),
            clarityThreshold = p.float("nes.clarityThreshold", n.clarityThreshold),
            averaging = p.bool("nes.averaging", n.averaging),
            foldPeriods = p.int("nes.foldPeriods", n.foldPeriods),
            persistence = p.float("nes.persistence", n.persistence),
            showIdeal = p.bool("nes.showIdeal", n.showIdeal),
            showLevels = p.bool("nes.showLevels", n.showLevels),
            showSteps = p.bool("nes.showSteps", n.showSteps),
            quantizeToDac = p.bool("nes.quantizeToDac", n.quantizeToDac),
            showSamples = p.bool("nes.showSamples", n.showSamples),
            holdMs = p.float("nes.holdMs", n.holdMs),
        )
        return DesktopState(
            settings = settings,
            nes = nes,
            waveMode = p.enum("waveMode", d.waveMode),
            page = p.enum("page", d.page),
            deviceName = p.getProperty("deviceName")?.takeIf { it.isNotBlank() },
            uiScale = p.float("uiScale", d.uiScale),
            windowWidth = p.int("windowWidth", d.windowWidth),
            windowHeight = p.int("windowHeight", d.windowHeight),
            maximized = p.bool("maximized", d.maximized),
            sidebarVisible = p.bool("sidebarVisible", d.sidebarVisible),
        )
    }

    fun save(d: DesktopState) {
        val p = Properties()
        val s = d.settings
        p["sampleRate"] = s.sampleRate.toString()
        p["stereo"] = s.stereo.toString()
        p["inputGainDb"] = s.inputGainDb.toString()
        p["splOffsetDb"] = s.splOffsetDb.toString()
        p["showSpl"] = s.showSpl.toString()
        p["fftSize"] = s.fftSize.toString()
        p["window"] = s.window.name
        p["overlap"] = s.overlap.toString()
        p["freqScale"] = s.freqScale.name
        p["minHz"] = s.minHz.toString()
        p["maxHz"] = s.maxHz.toString()
        p["floorDb"] = s.floorDb.toString()
        p["ceilingDb"] = s.ceilingDb.toString()
        p["tiltDbPerOct"] = s.tiltDbPerOct.toString()
        p["weighting"] = s.weighting.name
        p["spectrumStyle"] = s.spectrumStyle.name
        p["attackMs"] = s.attackMs.toString()
        p["releaseMs"] = s.releaseMs.toString()
        p["peakHold"] = s.peakHold.toString()
        p["peakHoldMs"] = s.peakHoldMs.toString()
        p["peakFallDbPerSec"] = s.peakFallDbPerSec.toString()
        p["showGrid"] = s.showGrid.toString()
        p["showLabels"] = s.showLabels.toString()
        p["bands"] = s.bands.joinToString("|") { "${it.name};${it.lowHz};${it.highHz};${it.enabled}" }
        p["bandSlope"] = s.bandSlope.name
        p["bandAttackMs"] = s.bandAttackMs.toString()
        p["bandReleaseMs"] = s.bandReleaseMs.toString()
        p["colorMap"] = s.colorMap.name
        p["spectrogramFloorDb"] = s.spectrogramFloorDb.toString()
        p["spectrogramCeilingDb"] = s.spectrogramCeilingDb.toString()
        p["loudnessEnabled"] = s.loudnessEnabled.toString()
        p["truePeakEnabled"] = s.truePeakEnabled.toString()
        p["truePeakOversample"] = s.truePeakOversample.toString()
        p["loudnessTargetLufs"] = s.loudnessTargetLufs.toString()
        p["goniometerPersistence"] = s.goniometerPersistence.toString()
        p["correlationWindowMs"] = s.correlationWindowMs.toString()
        p["theme"] = s.theme.name
        p["uiFps"] = s.uiFps.toString()

        val n = d.nes
        p["nes.region"] = n.region.name
        p["nes.lockToTimer"] = n.lockToTimer.toString()
        p["nes.cycles"] = n.cycles.toString()
        p["nes.huntMinHz"] = n.huntMinHz.toString()
        p["nes.huntMaxHz"] = n.huntMaxHz.toString()
        p["nes.clarityThreshold"] = n.clarityThreshold.toString()
        p["nes.averaging"] = n.averaging.toString()
        p["nes.foldPeriods"] = n.foldPeriods.toString()
        p["nes.persistence"] = n.persistence.toString()
        p["nes.showIdeal"] = n.showIdeal.toString()
        p["nes.showLevels"] = n.showLevels.toString()
        p["nes.showSteps"] = n.showSteps.toString()
        p["nes.quantizeToDac"] = n.quantizeToDac.toString()
        p["nes.showSamples"] = n.showSamples.toString()
        p["nes.holdMs"] = n.holdMs.toString()

        p["waveMode"] = d.waveMode.name
        p["page"] = d.page.name
        p["deviceName"] = d.deviceName.orEmpty()
        p["uiScale"] = d.uiScale.toString()
        p["windowWidth"] = d.windowWidth.toString()
        p["windowHeight"] = d.windowHeight.toString()
        p["maximized"] = d.maximized.toString()
        p["sidebarVisible"] = d.sidebarVisible.toString()

        runCatching {
            file.parentFile?.mkdirs()
            file.outputStream().use { p.store(it, "Spectra for Windows") }
        }
    }

    fun path(): String = file.absolutePath

    // ---- typed reads, all of which fall back rather than throw ----------

    private fun Properties.int(k: String, d: Int) = getProperty(k)?.toIntOrNull() ?: d
    private fun Properties.float(k: String, d: Float) = getProperty(k)?.toFloatOrNull() ?: d
    private fun Properties.bool(k: String, d: Boolean) = getProperty(k)?.toBooleanStrictOrNull() ?: d

    private inline fun <reified T : Enum<T>> Properties.enum(k: String, d: T): T {
        val v = getProperty(k) ?: return d
        return runCatching { enumValueOf<T>(v) }.getOrDefault(d)
    }

    private fun Properties.bands(k: String, d: List<BandDef>): List<BandDef> {
        val raw = getProperty(k) ?: return d
        val parsed = raw.split("|").mapNotNull { entry ->
            val f = entry.split(";")
            if (f.size != 4) return@mapNotNull null
            val lo = f[1].toFloatOrNull() ?: return@mapNotNull null
            val hi = f[2].toFloatOrNull() ?: return@mapNotNull null
            BandDef(f[0], lo, hi, f[3].equals("true", true))
        }
        return parsed.ifEmpty { d }
    }
}

/** Defaults tuned for looking at a 2A03 rather than at a mix. */
fun Settings.forNesTriangle(): Settings = copy(
    fftSize = 8192,
    overlap = 4,
    minHz = 20f,
    maxHz = 8000f,
    freqScale = FreqScale.LOG,
    floorDb = -110f,
    ceilingDb = -10f,
    spectrumStyle = SpectrumStyle.LINE,
    weighting = Weighting.Z,
    colorMap = ColorMap.NEBULA,
    bandSlope = BandSlope.DB24,
    theme = ThemeMode.DARK,
    // A band that spans exactly what the triangle channel can play, so its level
    // is readable next to the pulse channels rather than lumped in with them.
    bands = listOf(
        BandDef("Triangle", 25f, 200f),
        BandDef("Bass", 60f, 150f),
        BandDef("Low mid", 150f, 400f),
        BandDef("Mid", 400f, 1500f),
        BandDef("High mid", 1500f, 4000f),
        BandDef("Presence", 4000f, 10000f),
        BandDef("Air", 10000f, 20000f),
    ),
    window = WindowFunction.HANN,
)
