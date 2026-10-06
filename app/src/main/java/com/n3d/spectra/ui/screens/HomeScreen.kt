package com.n3d.spectra.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.n3d.spectra.audio.AudioEngine
import com.n3d.spectra.dsp.AnalysisFrame
import com.n3d.spectra.dsp.PeakHold
import com.n3d.spectra.settings.OscMode
import com.n3d.spectra.settings.Settings
import com.n3d.spectra.settings.SourceKind
import com.n3d.spectra.settings.VizPage
import com.n3d.spectra.settings.WaveformMode
import com.n3d.spectra.settings.oscTimeLabel
import com.n3d.spectra.stems.ScopeRunner
import com.n3d.spectra.stems.Stem
import com.n3d.spectra.stems.holdingStill
import com.n3d.spectra.stems.holdsStill
import com.n3d.spectra.ui.MainViewModel
import com.n3d.spectra.ui.VizSurface
import com.n3d.spectra.ui.neu.NeuButton
import com.n3d.spectra.ui.neu.NeuChip
import com.n3d.spectra.ui.neu.NeuIconAction
import com.n3d.spectra.ui.neu.NeuIconButton
import com.n3d.spectra.ui.neu.NeuIcons
import com.n3d.spectra.ui.neu.NeuSegmented
import com.n3d.spectra.ui.neu.NeuWell
import com.n3d.spectra.ui.theme.LocalPalette
import com.n3d.spectra.ui.theme.Neumorph
import com.n3d.spectra.ui.theme.toComposeColor
import java.util.Locale

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onOpenSettings: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val palette = LocalPalette.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val frame by viewModel.frame.collectAsStateWithLifecycle()
    val state by viewModel.engineState.collectAsStateWithLifecycle()
    val warning by viewModel.warning.collectAsStateWithLifecycle()
    val paused by viewModel.paused.collectAsStateWithLifecycle()

    val running = state is AudioEngine.State.Running || state is AudioEngine.State.Starting

    // Tell the engine which page is actually on screen. The stem model and the
    // held-still scopes cost real battery, and only run for a page someone can
    // see — so this has to go back to null the moment the app is not visible.
    LifecycleResumeEffect(settings.page) {
        AudioEngine.inAppPage = settings.page
        onPauseOrDispose { AudioEngine.inAppPage = null }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.bg.toComposeColor())
            .systemBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Spectra",
                    color = palette.text.toComposeColor(),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    statusText(state, paused),
                    color = palette.textFaint.toComposeColor(),
                    fontSize = 11.sp,
                )
            }
            NeuIconButton(onClick = onOpenSettings, icon = NeuIcons.Tune, contentDescription = "Settings")
        }

        Spacer(Modifier.height(12.dp))

        val failure = state as? AudioEngine.State.Failed
        if (failure != null) {
            Banner(text = failure.message, tone = palette.bad, onDismiss = null)
            Spacer(Modifier.height(10.dp))
        } else if (warning != null) {
            Banner(text = warning!!, tone = palette.warn, onDismiss = viewModel::dismissWarning)
            Spacer(Modifier.height(10.dp))
        }

        NeuWell(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            radius = Neumorph.RadiusLg,
        ) {
            VizSurface(
                page = settings.page,
                frame = frame,
                settings = settings,
                modifier = Modifier.fillMaxSize().padding(10.dp),
            )
        }

        Spacer(Modifier.height(12.dp))
        PageChips(
            selected = settings.page,
            onSelect = { page -> viewModel.update { it.copy(page = page) } },
        )

        if (settings.page == VizPage.WAVEFORM) {
            Spacer(Modifier.height(12.dp))
            NeuSegmented(
                options = WaveformMode.entries.toList(),
                selected = settings.waveformMode,
                onSelect = { mode -> viewModel.update { it.copy(waveformMode = mode) } },
                modifier = Modifier.fillMaxWidth(),
                labelOf = { it.label },
            )
        }

        if (settings.page == VizPage.OSCILLOSCOPE) {
            Spacer(Modifier.height(12.dp))
            ScopeControls(
                mode = settings.oscMode,
                timeDivMs = settings.oscTimeDivMs,
                onMode = { mode -> viewModel.update { it.copy(oscMode = mode) } },
                onTimeDiv = { ms -> viewModel.update { it.copy(oscTimeDivMs = ms) } },
            )
        }

        if (settings.page == VizPage.STEMS) {
            Spacer(Modifier.height(12.dp))
            StemHoldChips(
                isHeld = { stem -> settings.holdsStill(stem) },
                onToggle = { stem -> viewModel.update { it.holdingStill(stem, !it.holdsStill(stem)) } },
            )
        }

        Spacer(Modifier.height(12.dp))
        Readouts(frame)

        Spacer(Modifier.height(12.dp))
        NeuSegmented(
            options = SourceKind.entries.toList(),
            selected = settings.source,
            onSelect = { source ->
                viewModel.update { it.copy(source = source) }
                // Changing source means a different capture object entirely, so
                // an already-running analyser has to be restarted by hand.
                if (running) {
                    onStop()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            labelOf = { it.label },
        )

        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeuButton(
                onClick = { if (running) onStop() else onStart() },
                label = if (running) "Stop" else "Start",
                primary = !running,
                modifier = Modifier.weight(1f),
            )
            NeuIconButton(
                onClick = { AudioEngine.setPaused(!paused) },
                icon = if (paused) NeuIcons.Play else NeuIcons.Pause,
                contentDescription = if (paused) "Resume" else "Pause",
                active = paused,
            )
            NeuIconButton(onClick = viewModel::resetMeters, icon = NeuIcons.Reset, contentDescription = "Reset meters")
        }
    }
}

@Composable
private fun Banner(text: String, tone: Int, onDismiss: (() -> Unit)?) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                androidx.compose.ui.graphics.Color(tone).copy(alpha = 0.12f),
                androidx.compose.foundation.shape.RoundedCornerShape(Neumorph.RadiusMd),
            )
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text,
            color = palette.text.toComposeColor(),
            fontSize = 12.sp,
            lineHeight = 17.sp,
            modifier = Modifier.weight(1f),
        )
        if (onDismiss != null) {
            Spacer(Modifier.width(8.dp))
            NeuIconAction(NeuIcons.Close, "Dismiss", onDismiss)
        }
    }
}

@Composable
private fun PageChips(selected: VizPage, onSelect: (VizPage) -> Unit) {
    val scroll = rememberScrollState()
    Row(
        Modifier.fillMaxWidth().horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        VizPage.entries.forEach { page ->
            NeuChip(
                label = page.label,
                selected = page == selected,
                onClick = { onSelect(page) },
            )
        }
    }
}

/**
 * Which Stems lanes stand still. One chip per lane, top to bottom as drawn;
 * a pressed-in chip is held still, a raised one scrolls.
 */
@Composable
private fun StemHoldChips(isHeld: (Stem) -> Boolean, onToggle: (Stem) -> Unit) {
    val palette = LocalPalette.current
    val scroll = rememberScrollState()
    Row(
        Modifier.fillMaxWidth().horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Hold still", color = palette.textFaint.toComposeColor(), fontSize = 11.sp)
        ScopeRunner.LANES.forEach { stem ->
            NeuChip(
                label = stem.label,
                selected = isHeld(stem),
                onClick = { onToggle(stem) },
            )
        }
    }
}

/**
 * The Oscilloscope page's front panel: X-Y or Y-T, and in Y-T the time base,
 * stepped through a bench scope's 1-2-5 sequence. Everything else about the
 * tube is in Settings.
 */
@Composable
private fun ScopeControls(
    mode: OscMode,
    timeDivMs: Float,
    onMode: (OscMode) -> Unit,
    onTimeDiv: (Float) -> Unit,
) {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        NeuSegmented(
            options = OscMode.entries.toList(),
            selected = mode,
            onSelect = onMode,
            modifier = Modifier.weight(1f),
            labelOf = { it.label },
        )
        if (mode == OscMode.YT) {
            val steps = Settings.OSC_TIME_DIVS
            val at = steps.indices.minBy { kotlin.math.abs(steps[it] - timeDivMs) }
            Spacer(Modifier.width(10.dp))
            NeuIconButton(
                onClick = { onTimeDiv(steps[(at - 1).coerceAtLeast(0)]) },
                icon = NeuIcons.Minus,
                contentDescription = "Less time per division",
                size = 44.dp,
            )
            Text(
                oscTimeLabel(steps[at]),
                color = palette.text.toComposeColor(),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(62.dp),
            )
            NeuIconButton(
                onClick = { onTimeDiv(steps[(at + 1).coerceAtMost(steps.lastIndex)]) },
                icon = NeuIcons.Plus,
                contentDescription = "More time per division",
                size = 44.dp,
            )
        }
    }
}

@Composable
private fun Readouts(frame: AnalysisFrame?) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Readout("RMS L", db(frame?.rmsDbL))
        Readout("RMS R", db(frame?.rmsDbR))
        Readout("PEAK", db(maxOf(frame?.peakDbL ?: -144f, frame?.peakDbR ?: -144f)))
        Readout("LUFS M", lufs(frame?.loudness?.momentary))
        Readout("CORR", frame?.let { String.format(Locale.US, "%+.2f", it.correlation) } ?: "—")
    }
}

@Composable
private fun Readout(label: String, value: String) {
    val palette = LocalPalette.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = palette.textFaint.toComposeColor(), fontSize = 9.sp, letterSpacing = 0.8.sp)
        Text(
            value,
            color = palette.text.toComposeColor(),
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun db(value: Float?): String = when {
    value == null -> "—"
    value <= PeakHold.FLOOR_DB + 1f -> "-inf"
    else -> String.format(Locale.US, "%.1f", value)
}

private fun lufs(value: Float?): String =
    if (value == null || value <= -70f) "—" else String.format(Locale.US, "%.1f", value)

private fun statusText(state: AudioEngine.State, paused: Boolean): String = when (state) {
    is AudioEngine.State.Running -> (if (paused) "paused · " else "") + state.describe
    is AudioEngine.State.Failed -> "stopped · error"
    AudioEngine.State.Starting -> "starting…"
    AudioEngine.State.Idle -> "idle"
}
