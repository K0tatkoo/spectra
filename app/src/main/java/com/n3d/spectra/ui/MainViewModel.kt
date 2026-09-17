package com.n3d.spectra.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.n3d.spectra.audio.AudioEngine
import com.n3d.spectra.dsp.AnalysisFrame
import com.n3d.spectra.settings.Settings
import com.n3d.spectra.settings.SettingsStore
import com.n3d.spectra.update.UpdateManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SettingsStore.get(app)

    /**
     * The app looking after its own version. See update/Updater.kt — and note
     * that this is the only thing in Spectra that opens a connection at all.
     */
    val updates = UpdateManager(app).also { it.checkOnLaunch() }

    val settings: StateFlow<Settings> = store.state

    val engineState = AudioEngine.state
    val warning = AudioEngine.warning
    val paused = AudioEngine.paused

    /**
     * Frames throttled to the configured UI rate.
     *
     * The engine publishes roughly every hop — about 47 Hz at 4096/75 %, and
     * nearly 190 Hz at 512/87.5 % — which has nothing to do with how fast the
     * screen can draw. Sampling here means a small FFT does not turn into a
     * recomposition storm.
     */
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val frame: StateFlow<AnalysisFrame?> = store.state
        .map { it.uiFps }
        .distinctUntilChanged()
        .flatMapLatest { fps -> AudioEngine.frame.sample(1000L / fps.coerceIn(10, 120)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(2_000), null)

    fun update(transform: (Settings) -> Settings) = store.update(transform)

    fun resetSettings() = store.resetToDefaults()

    fun resetMeters() = AudioEngine.resetMeters()

    fun dismissWarning() = AudioEngine.clearWarning()
}
