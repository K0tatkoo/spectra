package com.n3d.spectra.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.n3d.spectra.dsp.WindowFunction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("spectra_settings")

/**
 * Persisted settings, exposed as a [StateFlow] that updates *before* the write
 * completes.
 *
 * That ordering matters for a UI made of sliders: waiting for a disk write to
 * come back through DataStore's flow before the knob moves makes every control
 * feel like it is lagging behind the finger. The in-memory value is the truth
 * for the session and the disk catches up.
 */
class SettingsStore private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(Settings())
    val state: StateFlow<Settings> = _state.asStateFlow()

    init {
        scope.launch {
            val loaded = runCatching { decode(appContext.dataStore.data.first()) }.getOrNull()
            if (loaded != null) _state.value = loaded
        }
    }

    /** Blocks until the first read completes. Only for the service's cold start. */
    fun awaitLoaded(): Settings = runBlocking {
        runCatching { decode(appContext.dataStore.data.first()) }.getOrDefault(_state.value)
            .also { _state.value = it }
    }

    private var writeJob: Job? = null

    /**
     * Updates immediately in memory and persists lazily.
     *
     * Dragging a slider produces sixty of these a second, and every DataStore
     * write rewrites the whole preferences file. Coalescing them behind a single
     * in-flight job means at most one write per 250 ms, and the last update of a
     * gesture still schedules the write that captures its final value.
     */
    fun update(transform: (Settings) -> Settings) {
        val next = transform(_state.value)
        if (next == _state.value) return
        _state.value = next
        if (writeJob?.isActive == true) return
        writeJob = scope.launch {
            delay(WRITE_DEBOUNCE_MS)
            val snapshot = _state.value
            runCatching { appContext.dataStore.edit { encode(snapshot, it) } }
        }
    }

    fun resetToDefaults() = update { Settings() }

    // ---- serialisation -----------------------------------------------------

    private fun encode(s: Settings, p: androidx.datastore.preferences.core.MutablePreferences) {
        p[K_SOURCE] = s.source.name
        p[K_MIC_PRESET] = s.micPreset.name
        p[K_MIC_AGC] = s.micAgc
        p[K_MIC_NS] = s.micNoiseSuppression
        p[K_SAMPLE_RATE] = s.sampleRate
        p[K_STEREO] = s.stereo
        p[K_INPUT_GAIN] = s.inputGainDb
        p[K_SPL_OFFSET] = s.splOffsetDb
        p[K_SHOW_SPL] = s.showSpl

        p[K_FFT] = s.fftSize
        p[K_WINDOW] = s.window.name
        p[K_OVERLAP] = s.overlap

        p[K_FREQ_SCALE] = s.freqScale.name
        p[K_MIN_HZ] = s.minHz
        p[K_MAX_HZ] = s.maxHz
        p[K_FLOOR] = s.floorDb
        p[K_CEIL] = s.ceilingDb
        p[K_TILT] = s.tiltDbPerOct
        p[K_WEIGHTING] = s.weighting.name
        p[K_STYLE] = s.spectrumStyle.name
        p[K_ATTACK] = s.attackMs
        p[K_RELEASE] = s.releaseMs
        p[K_PEAK_HOLD] = s.peakHold
        p[K_PEAK_HOLD_MS] = s.peakHoldMs
        p[K_PEAK_FALL] = s.peakFallDbPerSec
        p[K_GRID] = s.showGrid
        p[K_LABELS] = s.showLabels

        p[K_BANDS] = s.bands.joinToString("|") { "${it.name}:${it.lowHz}:${it.highHz}:${it.enabled}" }
        p[K_BAND_SLOPE] = s.bandSlope.name
        p[K_BAND_ATTACK] = s.bandAttackMs
        p[K_BAND_RELEASE] = s.bandReleaseMs

        p[K_COLORMAP] = s.colorMap.name
        p[K_SG_FLOOR] = s.spectrogramFloorDb
        p[K_SG_CEIL] = s.spectrogramCeilingDb

        p[K_LOUD_ON] = s.loudnessEnabled
        p[K_TP_ON] = s.truePeakEnabled
        p[K_TP_OS] = s.truePeakOversample
        p[K_TARGET] = s.loudnessTargetLufs

        p[K_GONIO_PERSIST] = s.goniometerPersistence
        p[K_CORR_WINDOW] = s.correlationWindowMs

        p[K_WAVE_MODE] = s.waveformMode.name
        p[K_SCOPE_WINDOW] = s.scopeWindowMs
        p[K_SCOPE_CLEAN] = s.scopeCleanMs
        p[K_STEM_THREADS] = s.stemThreads
        p[K_NES_REGION] = s.nesRegion.name

        p[K_THEME] = s.theme.name
        p[K_PAGE] = s.page.name
        p[K_UI_FPS] = s.uiFps

        p[K_NOTIF_ON] = s.notificationEnabled
        p[K_NOTIF_FPS] = s.notificationFps
        p[K_NOTIF_PAGE] = s.notificationPage.name
        p[K_OVERLAY_ON] = s.overlayEnabled
        p[K_OVERLAY_FPS] = s.overlayFps
        p[K_OVERLAY_PAGE] = s.overlayPage.name
        p[K_OVERLAY_ALPHA] = s.overlayOpacity
        p[K_WIDGET_ON] = s.widgetEnabled
        p[K_WIDGET_FPS] = s.widgetFps
        p[K_WIDGET_PAGE] = s.widgetPage.name
        p[K_PAUSE_SCREEN_OFF] = s.pauseRenderWhenScreenOff
    }

    private fun decode(p: Preferences): Settings {
        val d = Settings()
        return Settings(
            source = p.enum(K_SOURCE, d.source),
            micPreset = p.enum(K_MIC_PRESET, d.micPreset),
            micAgc = p[K_MIC_AGC] ?: d.micAgc,
            micNoiseSuppression = p[K_MIC_NS] ?: d.micNoiseSuppression,
            sampleRate = p[K_SAMPLE_RATE] ?: d.sampleRate,
            stereo = p[K_STEREO] ?: d.stereo,
            inputGainDb = p[K_INPUT_GAIN] ?: d.inputGainDb,
            splOffsetDb = p[K_SPL_OFFSET] ?: d.splOffsetDb,
            showSpl = p[K_SHOW_SPL] ?: d.showSpl,

            fftSize = p[K_FFT] ?: d.fftSize,
            window = p.enum(K_WINDOW, d.window),
            overlap = p[K_OVERLAP] ?: d.overlap,

            freqScale = p.enum(K_FREQ_SCALE, d.freqScale),
            minHz = p[K_MIN_HZ] ?: d.minHz,
            maxHz = p[K_MAX_HZ] ?: d.maxHz,
            floorDb = p[K_FLOOR] ?: d.floorDb,
            ceilingDb = p[K_CEIL] ?: d.ceilingDb,
            tiltDbPerOct = p[K_TILT] ?: d.tiltDbPerOct,
            weighting = p.enum(K_WEIGHTING, d.weighting),
            spectrumStyle = p.enum(K_STYLE, d.spectrumStyle),
            attackMs = p[K_ATTACK] ?: d.attackMs,
            releaseMs = p[K_RELEASE] ?: d.releaseMs,
            peakHold = p[K_PEAK_HOLD] ?: d.peakHold,
            peakHoldMs = p[K_PEAK_HOLD_MS] ?: d.peakHoldMs,
            peakFallDbPerSec = p[K_PEAK_FALL] ?: d.peakFallDbPerSec,
            showGrid = p[K_GRID] ?: d.showGrid,
            showLabels = p[K_LABELS] ?: d.showLabels,

            bands = p[K_BANDS]?.let(::decodeBands) ?: d.bands,
            bandSlope = p.enum(K_BAND_SLOPE, d.bandSlope),
            bandAttackMs = p[K_BAND_ATTACK] ?: d.bandAttackMs,
            bandReleaseMs = p[K_BAND_RELEASE] ?: d.bandReleaseMs,

            colorMap = p.enum(K_COLORMAP, d.colorMap),
            spectrogramFloorDb = p[K_SG_FLOOR] ?: d.spectrogramFloorDb,
            spectrogramCeilingDb = p[K_SG_CEIL] ?: d.spectrogramCeilingDb,

            loudnessEnabled = p[K_LOUD_ON] ?: d.loudnessEnabled,
            truePeakEnabled = p[K_TP_ON] ?: d.truePeakEnabled,
            truePeakOversample = p[K_TP_OS] ?: d.truePeakOversample,
            loudnessTargetLufs = p[K_TARGET] ?: d.loudnessTargetLufs,

            goniometerPersistence = p[K_GONIO_PERSIST] ?: d.goniometerPersistence,
            correlationWindowMs = p[K_CORR_WINDOW] ?: d.correlationWindowMs,

            waveformMode = p.enum(K_WAVE_MODE, d.waveformMode),
            scopeWindowMs = p[K_SCOPE_WINDOW] ?: d.scopeWindowMs,
            scopeCleanMs = p[K_SCOPE_CLEAN] ?: d.scopeCleanMs,
            stemThreads = p[K_STEM_THREADS] ?: d.stemThreads,
            nesRegion = p.enum(K_NES_REGION, d.nesRegion),

            theme = p.enum(K_THEME, d.theme),
            page = p.enum(K_PAGE, d.page),
            uiFps = p[K_UI_FPS] ?: d.uiFps,

            notificationEnabled = p[K_NOTIF_ON] ?: d.notificationEnabled,
            notificationFps = p[K_NOTIF_FPS] ?: d.notificationFps,
            notificationPage = p.enum(K_NOTIF_PAGE, d.notificationPage),
            overlayEnabled = p[K_OVERLAY_ON] ?: d.overlayEnabled,
            overlayFps = p[K_OVERLAY_FPS] ?: d.overlayFps,
            overlayPage = p.enum(K_OVERLAY_PAGE, d.overlayPage),
            overlayOpacity = p[K_OVERLAY_ALPHA] ?: d.overlayOpacity,
            widgetEnabled = p[K_WIDGET_ON] ?: d.widgetEnabled,
            widgetFps = p[K_WIDGET_FPS] ?: d.widgetFps,
            widgetPage = p.enum(K_WIDGET_PAGE, d.widgetPage),
            pauseRenderWhenScreenOff = p[K_PAUSE_SCREEN_OFF] ?: d.pauseRenderWhenScreenOff,
        )
    }

    private fun decodeBands(raw: String): List<BandDef> = raw.split("|").mapNotNull { entry ->
        val parts = entry.split(":")
        if (parts.size != 4) return@mapNotNull null
        val low = parts[1].toFloatOrNull() ?: return@mapNotNull null
        val high = parts[2].toFloatOrNull() ?: return@mapNotNull null
        BandDef(parts[0], low, high, parts[3].toBooleanStrictOrNull() ?: true)
    }.ifEmpty { Settings.DEFAULT_BANDS }

    companion object {
        private const val WRITE_DEBOUNCE_MS = 250L

        @Volatile private var instance: SettingsStore? = null

        fun get(context: Context): SettingsStore =
            instance ?: synchronized(this) {
                instance ?: SettingsStore(context).also { instance = it }
            }

        private val K_SOURCE = stringPreferencesKey("source")
        private val K_MIC_PRESET = stringPreferencesKey("mic_preset")
        private val K_MIC_AGC = booleanPreferencesKey("mic_agc")
        private val K_MIC_NS = booleanPreferencesKey("mic_ns")
        private val K_SAMPLE_RATE = intPreferencesKey("sample_rate")
        private val K_STEREO = booleanPreferencesKey("stereo")
        private val K_INPUT_GAIN = floatPreferencesKey("input_gain")
        private val K_SPL_OFFSET = floatPreferencesKey("spl_offset")
        private val K_SHOW_SPL = booleanPreferencesKey("show_spl")

        private val K_FFT = intPreferencesKey("fft")
        private val K_WINDOW = stringPreferencesKey("window")
        private val K_OVERLAP = intPreferencesKey("overlap")

        private val K_FREQ_SCALE = stringPreferencesKey("freq_scale")
        private val K_MIN_HZ = floatPreferencesKey("min_hz")
        private val K_MAX_HZ = floatPreferencesKey("max_hz")
        private val K_FLOOR = floatPreferencesKey("floor_db")
        private val K_CEIL = floatPreferencesKey("ceil_db")
        private val K_TILT = floatPreferencesKey("tilt")
        private val K_WEIGHTING = stringPreferencesKey("weighting")
        private val K_STYLE = stringPreferencesKey("spectrum_style")
        private val K_ATTACK = floatPreferencesKey("attack_ms")
        private val K_RELEASE = floatPreferencesKey("release_ms")
        private val K_PEAK_HOLD = booleanPreferencesKey("peak_hold")
        private val K_PEAK_HOLD_MS = floatPreferencesKey("peak_hold_ms")
        private val K_PEAK_FALL = floatPreferencesKey("peak_fall")
        private val K_GRID = booleanPreferencesKey("grid")
        private val K_LABELS = booleanPreferencesKey("labels")

        private val K_BANDS = stringPreferencesKey("bands")
        private val K_BAND_SLOPE = stringPreferencesKey("band_slope")
        private val K_BAND_ATTACK = floatPreferencesKey("band_attack")
        private val K_BAND_RELEASE = floatPreferencesKey("band_release")

        private val K_COLORMAP = stringPreferencesKey("colormap")
        private val K_SG_FLOOR = floatPreferencesKey("sg_floor")
        private val K_SG_CEIL = floatPreferencesKey("sg_ceil")

        private val K_LOUD_ON = booleanPreferencesKey("loudness_on")
        private val K_TP_ON = booleanPreferencesKey("true_peak_on")
        private val K_TP_OS = intPreferencesKey("true_peak_os")
        private val K_TARGET = floatPreferencesKey("target_lufs")

        private val K_GONIO_PERSIST = floatPreferencesKey("gonio_persist")
        private val K_CORR_WINDOW = floatPreferencesKey("corr_window")

        private val K_WAVE_MODE = stringPreferencesKey("waveform_mode")
        private val K_SCOPE_WINDOW = floatPreferencesKey("scope_window_ms")
        private val K_SCOPE_CLEAN = floatPreferencesKey("scope_clean_ms")
        private val K_STEM_THREADS = intPreferencesKey("stem_threads")
        private val K_NES_REGION = stringPreferencesKey("nes_region")

        private val K_THEME = stringPreferencesKey("theme")
        private val K_PAGE = stringPreferencesKey("page")
        private val K_UI_FPS = intPreferencesKey("ui_fps")

        private val K_NOTIF_ON = booleanPreferencesKey("notif_on")
        private val K_NOTIF_FPS = intPreferencesKey("notif_fps")
        private val K_NOTIF_PAGE = stringPreferencesKey("notif_page")
        private val K_OVERLAY_ON = booleanPreferencesKey("overlay_on")
        private val K_OVERLAY_FPS = intPreferencesKey("overlay_fps")
        private val K_OVERLAY_PAGE = stringPreferencesKey("overlay_page")
        private val K_OVERLAY_ALPHA = floatPreferencesKey("overlay_alpha")
        private val K_WIDGET_ON = booleanPreferencesKey("widget_on")
        private val K_WIDGET_FPS = intPreferencesKey("widget_fps")
        private val K_WIDGET_PAGE = stringPreferencesKey("widget_page")
        private val K_PAUSE_SCREEN_OFF = booleanPreferencesKey("pause_screen_off")
    }
}

/**
 * Enum round-tripping that survives a renamed constant: an unknown name falls
 * back to the default rather than throwing, so an old preferences file from a
 * previous build can never brick the app on launch.
 */
private inline fun <reified E : Enum<E>> Preferences.enum(
    key: Preferences.Key<String>,
    fallback: E,
): E = this[key]?.let { name -> runCatching { enumValueOf<E>(name) }.getOrNull() } ?: fallback

/** Kept out of [Settings] so the data class stays a plain value with no Android types. */
val WindowFunctionValues: List<WindowFunction> = WindowFunction.entries
