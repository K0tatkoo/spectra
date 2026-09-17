package com.n3d.spectra.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.n3d.spectra.dsp.WindowFunction
import com.n3d.spectra.settings.BandSlope
import com.n3d.spectra.settings.ColorMap
import com.n3d.spectra.settings.FreqScale
import com.n3d.spectra.settings.MicPreset
import com.n3d.spectra.settings.Settings
import com.n3d.spectra.settings.SourceKind
import com.n3d.spectra.settings.SpectrumStyle
import com.n3d.spectra.settings.ThemeMode
import com.n3d.spectra.settings.VizPage
import com.n3d.spectra.settings.Weighting
import com.n3d.spectra.ui.MainViewModel
import com.n3d.spectra.ui.neu.NeuButton
import com.n3d.spectra.ui.neu.NeuCard
import com.n3d.spectra.ui.neu.NeuIconButton
import com.n3d.spectra.ui.neu.NeuPicker
import com.n3d.spectra.ui.neu.NeuSegmented
import com.n3d.spectra.ui.neu.NeuSlider
import com.n3d.spectra.ui.neu.NeuSwitch
import com.n3d.spectra.ui.neu.SectionTitle
import com.n3d.spectra.ui.neu.SettingRow
import com.n3d.spectra.ui.theme.LocalPalette
import com.n3d.spectra.ui.theme.toComposeColor
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The full control surface.
 *
 * Nothing here is hidden behind an "advanced" flag. The whole point of the app
 * is that someone who knows what a flat-top window or a −20 LU gate is can set
 * it; burying those two levels deep to protect a hypothetical beginner would
 * make it a toy with a settings screen.
 */
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    canDrawOverlays: () -> Boolean,
) {
    val palette = LocalPalette.current
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    fun edit(block: (Settings) -> Settings) = viewModel.update(block)

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.bg.toComposeColor())
            .systemBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeuIconButton(onClick = onBack, glyph = "‹", size = 42.dp)
            Text(
                "  Settings",
                color = palette.text.toComposeColor(),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp),
        ) {
            // ---- source ----------------------------------------------------
            SectionTitle("Input")
            NeuCard {
                NeuSegmented(
                    options = SourceKind.entries.toList(),
                    selected = s.source,
                    onSelect = { v -> edit { it.copy(source = v) } },
                    modifier = Modifier.fillMaxWidth(),
                    labelOf = { it.label },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (s.source == SourceKind.DEVICE_AUDIO) {
                        "Device audio needs the screen-capture consent each time it starts. " +
                            "Apps that opt out of playback capture — Spotify, YouTube, anything " +
                            "DRM-protected — send silence instead of audio, and Android gives no " +
                            "error for it. Use the microphone to measure those."
                    } else {
                        "Analyses whatever the microphone hears, including the phone's own speaker."
                    },
                    color = palette.textFaint.toComposeColor(),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )

                if (s.source == SourceKind.MICROPHONE) {
                    Spacer(Modifier.height(14.dp))
                    NeuPicker(
                        options = MicPreset.entries.toList(),
                        selected = s.micPreset,
                        onSelect = { v -> edit { it.copy(micPreset = v) } },
                        label = "Audio source",
                        labelOf = { it.label },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        s.micPreset.blurb,
                        color = palette.textFaint.toComposeColor(),
                        fontSize = 11.sp,
                    )
                    SettingRow("Automatic gain control", "Off for measurement. On flatters quiet sources.") {
                        NeuSwitch(s.micAgc) { v -> edit { it.copy(micAgc = v) } }
                    }
                    SettingRow("Noise suppression", "Removes steady noise — and steady tones with it.") {
                        NeuSwitch(s.micNoiseSuppression) { v -> edit { it.copy(micNoiseSuppression = v) } }
                    }
                }

                Spacer(Modifier.height(12.dp))
                NeuSegmented(
                    options = Settings.SAMPLE_RATES,
                    selected = s.sampleRate,
                    onSelect = { v -> edit { it.copy(sampleRate = v) } },
                    modifier = Modifier.fillMaxWidth(),
                    labelOf = { "${it / 1000f} kHz" },
                )
                SettingRow("Stereo", "Needed for the goniometer and correlation.") {
                    NeuSwitch(s.stereo) { v -> edit { it.copy(stereo = v) } }
                }
                NeuSlider(
                    value = s.inputGainDb,
                    onValueChange = { v -> edit { it.copy(inputGainDb = v.round(1)) } },
                    valueRange = -24f..24f,
                    label = "Input trim",
                    valueText = "${s.inputGainDb.fmt(1)} dB",
                    modifier = Modifier.fillMaxWidth(),
                )
                SettingRow("Show dB SPL", "Adds the offset below to every dBFS reading.") {
                    NeuSwitch(s.showSpl) { v -> edit { it.copy(showSpl = v) } }
                }
                if (s.showSpl) {
                    NeuSlider(
                        value = s.splOffsetDb,
                        onValueChange = { v -> edit { it.copy(splOffsetDb = v.round(0)) } },
                        valueRange = 0f..140f,
                        label = "dBFS → dB SPL offset",
                        valueText = "${s.splOffsetDb.fmt(0)} dB",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // ---- transform --------------------------------------------------
            SectionTitle("Transform")
            NeuCard {
                NeuPicker(
                    options = Settings.FFT_SIZES,
                    selected = s.fftSize,
                    onSelect = { v -> edit { it.copy(fftSize = v) } },
                    label = "FFT size",
                    labelOf = { "$it points" },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                NeuPicker(
                    options = WindowFunction.entries.toList(),
                    selected = s.window,
                    onSelect = { v -> edit { it.copy(window = v) } },
                    label = "Window",
                    labelOf = { it.label },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(s.window.blurb, color = palette.textFaint.toComposeColor(), fontSize = 11.sp)

                Spacer(Modifier.height(12.dp))
                NeuSegmented(
                    options = Settings.OVERLAPS,
                    selected = s.overlap,
                    onSelect = { v -> edit { it.copy(overlap = v) } },
                    modifier = Modifier.fillMaxWidth(),
                    labelOf = { "${(100f - 100f / it).roundToInt()}%" },
                )
                Spacer(Modifier.height(8.dp))
                // The two numbers that actually decide what you can see. Every
                // other choice on this screen is cosmetics by comparison.
                Text(
                    "Bin spacing ${(s.sampleRate.toFloat() / s.fftSize).fmt(2)} Hz · " +
                        "window ${(s.fftSize * 1000f / s.sampleRate).fmt(1)} ms · " +
                        "refresh ${(s.sampleRate.toFloat() / s.hopSize).fmt(0)} Hz",
                    color = palette.textDim.toComposeColor(),
                    fontSize = 11.sp,
                )
            }

            // ---- spectrum ----------------------------------------------------
            SectionTitle("Spectrum")
            NeuCard {
                NeuSegmented(
                    options = FreqScale.entries.toList(),
                    selected = s.freqScale,
                    onSelect = { v -> edit { it.copy(freqScale = v) } },
                    modifier = Modifier.fillMaxWidth(),
                    labelOf = { it.label },
                )
                Spacer(Modifier.height(10.dp))
                NeuSlider(
                    value = freqToSlider(s.minHz),
                    onValueChange = { v ->
                        val hz = sliderToFreq(v)
                        edit { it.copy(minHz = hz.coerceAtMost(it.maxHz - 50f)) }
                    },
                    valueRange = 0f..1f,
                    label = "Lowest frequency",
                    valueText = "${s.minHz.roundToInt()} Hz",
                    modifier = Modifier.fillMaxWidth(),
                )
                NeuSlider(
                    value = freqToSlider(s.maxHz),
                    onValueChange = { v ->
                        val hz = sliderToFreq(v)
                        edit { it.copy(maxHz = hz.coerceAtLeast(it.minHz + 50f)) }
                    },
                    valueRange = 0f..1f,
                    label = "Highest frequency",
                    valueText = "${s.maxHz.roundToInt()} Hz",
                    modifier = Modifier.fillMaxWidth(),
                )
                NeuSlider(
                    value = s.floorDb,
                    onValueChange = { v -> edit { it.copy(floorDb = v.round(0).coerceAtMost(it.ceilingDb - 20f)) } },
                    valueRange = -140f..-40f,
                    label = "Display floor",
                    valueText = "${s.floorDb.fmt(0)} dB",
                    modifier = Modifier.fillMaxWidth(),
                )
                NeuSlider(
                    value = s.ceilingDb,
                    onValueChange = { v -> edit { it.copy(ceilingDb = v.round(0).coerceAtLeast(it.floorDb + 20f)) } },
                    valueRange = -40f..20f,
                    label = "Display ceiling",
                    valueText = "${s.ceilingDb.fmt(0)} dB",
                    modifier = Modifier.fillMaxWidth(),
                )
                NeuSlider(
                    value = s.tiltDbPerOct,
                    onValueChange = { v -> edit { it.copy(tiltDbPerOct = v.round(1)) } },
                    valueRange = -6f..9f,
                    label = "Slope tilt",
                    valueText = "${s.tiltDbPerOct.fmt(1)} dB/oct",
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "3 dB/oct makes pink noise read flat; 4.5 matches the usual mastering target curve.",
                    color = palette.textFaint.toComposeColor(),
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(12.dp))
                NeuSegmented(
                    options = Weighting.entries.toList(),
                    selected = s.weighting,
                    onSelect = { v -> edit { it.copy(weighting = v) } },
                    modifier = Modifier.fillMaxWidth(),
                    labelOf = { it.label },
                )
                Spacer(Modifier.height(10.dp))
                NeuSegmented(
                    options = SpectrumStyle.entries.toList(),
                    selected = s.spectrumStyle,
                    onSelect = { v -> edit { it.copy(spectrumStyle = v) } },
                    modifier = Modifier.fillMaxWidth(),
                    labelOf = { it.label },
                )
                Spacer(Modifier.height(12.dp))
                NeuSlider(
                    value = s.attackMs,
                    onValueChange = { v -> edit { it.copy(attackMs = v.round(0)) } },
                    valueRange = 0f..200f,
                    label = "Attack",
                    valueText = "${s.attackMs.fmt(0)} ms",
                    modifier = Modifier.fillMaxWidth(),
                )
                NeuSlider(
                    value = s.releaseMs,
                    onValueChange = { v -> edit { it.copy(releaseMs = v.round(0)) } },
                    valueRange = 20f..2000f,
                    label = "Release",
                    valueText = "${s.releaseMs.fmt(0)} ms",
                    modifier = Modifier.fillMaxWidth(),
                )
                SettingRow("Peak hold") {
                    NeuSwitch(s.peakHold) { v -> edit { it.copy(peakHold = v) } }
                }
                if (s.peakHold) {
                    NeuSlider(
                        value = s.peakHoldMs,
                        onValueChange = { v -> edit { it.copy(peakHoldMs = v.round(0)) } },
                        valueRange = 100f..5000f,
                        label = "Hold time",
                        valueText = "${s.peakHoldMs.fmt(0)} ms",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    NeuSlider(
                        value = s.peakFallDbPerSec,
                        onValueChange = { v -> edit { it.copy(peakFallDbPerSec = v.round(0)) } },
                        valueRange = 2f..120f,
                        label = "Fall rate",
                        valueText = "${s.peakFallDbPerSec.fmt(0)} dB/s",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                SettingRow("Grid") { NeuSwitch(s.showGrid) { v -> edit { it.copy(showGrid = v) } } }
                SettingRow("Axis labels") { NeuSwitch(s.showLabels) { v -> edit { it.copy(showLabels = v) } } }
            }

            // ---- bands --------------------------------------------------------
            SectionTitle("Bands")
            NeuCard {
                NeuSegmented(
                    options = BandSlope.entries.toList(),
                    selected = s.bandSlope,
                    onSelect = { v -> edit { it.copy(bandSlope = v) } },
                    modifier = Modifier.fillMaxWidth(),
                    labelOf = { it.label },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Crossover steepness of the band-pass filters. Steeper isolates better and " +
                        "rings more on transients.",
                    color = palette.textFaint.toComposeColor(),
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(12.dp))
                NeuSlider(
                    value = s.bandAttackMs,
                    onValueChange = { v -> edit { it.copy(bandAttackMs = v.round(0)) } },
                    valueRange = 0f..100f,
                    label = "Band attack",
                    valueText = "${s.bandAttackMs.fmt(0)} ms",
                    modifier = Modifier.fillMaxWidth(),
                )
                NeuSlider(
                    value = s.bandReleaseMs,
                    onValueChange = { v -> edit { it.copy(bandReleaseMs = v.round(0)) } },
                    valueRange = 20f..1500f,
                    label = "Band release",
                    valueText = "${s.bandReleaseMs.fmt(0)} ms",
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            s.bands.forEachIndexed { index, band ->
                Spacer(Modifier.height(10.dp))
                NeuCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            band.name,
                            color = palette.text.toComposeColor(),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        NeuSwitch(band.enabled) { v ->
                            edit { st -> st.copy(bands = st.bands.replaceAt(index) { it.copy(enabled = v) }) }
                        }
                    }
                    NeuSlider(
                        value = freqToSlider(band.lowHz),
                        onValueChange = { v ->
                            val hz = sliderToFreq(v)
                            edit { st ->
                                st.copy(
                                    bands = st.bands.replaceAt(index) {
                                        it.copy(lowHz = hz.coerceAtMost(it.highHz - 10f))
                                    },
                                )
                            }
                        },
                        valueRange = 0f..1f,
                        label = "Low corner",
                        valueText = "${band.lowHz.roundToInt()} Hz",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    NeuSlider(
                        value = freqToSlider(band.highHz),
                        onValueChange = { v ->
                            val hz = sliderToFreq(v)
                            edit { st ->
                                st.copy(
                                    bands = st.bands.replaceAt(index) {
                                        it.copy(highHz = hz.coerceAtLeast(it.lowHz + 10f))
                                    },
                                )
                            }
                        },
                        valueRange = 0f..1f,
                        label = "High corner",
                        valueText = "${band.highHz.roundToInt()} Hz",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            NeuButton(
                onClick = { edit { it.copy(bands = Settings.DEFAULT_BANDS) } },
                label = "Restore default bands",
                modifier = Modifier.fillMaxWidth(),
            )

            // ---- spectrogram --------------------------------------------------
            SectionTitle("Spectrogram")
            NeuCard {
                NeuPicker(
                    options = ColorMap.entries.toList(),
                    selected = s.colorMap,
                    onSelect = { v -> edit { it.copy(colorMap = v) } },
                    label = "Colour map",
                    labelOf = { it.label },
                    modifier = Modifier.fillMaxWidth(),
                )
                NeuSlider(
                    value = s.spectrogramFloorDb,
                    onValueChange = { v ->
                        edit { it.copy(spectrogramFloorDb = v.round(0).coerceAtMost(it.spectrogramCeilingDb - 10f)) }
                    },
                    valueRange = -140f..-30f,
                    label = "Floor",
                    valueText = "${s.spectrogramFloorDb.fmt(0)} dB",
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                NeuSlider(
                    value = s.spectrogramCeilingDb,
                    onValueChange = { v ->
                        edit { it.copy(spectrogramCeilingDb = v.round(0).coerceAtLeast(it.spectrogramFloorDb + 10f)) }
                    },
                    valueRange = -60f..6f,
                    label = "Ceiling",
                    valueText = "${s.spectrogramCeilingDb.fmt(0)} dB",
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Changing either clears the history — the columns are stored already mapped " +
                        "into this range.",
                    color = palette.textFaint.toComposeColor(),
                    fontSize = 11.sp,
                )
            }

            // ---- loudness ------------------------------------------------------
            SectionTitle("Loudness (BS.1770-4)")
            NeuCard {
                SettingRow("Enabled", "K-weighted momentary, short-term, integrated and LRA.") {
                    NeuSwitch(s.loudnessEnabled) { v -> edit { it.copy(loudnessEnabled = v) } }
                }
                SettingRow("True peak", "Inter-sample peaks by oversampling. Costs a little CPU.") {
                    NeuSwitch(s.truePeakEnabled) { v -> edit { it.copy(truePeakEnabled = v) } }
                }
                if (s.truePeakEnabled) {
                    NeuSegmented(
                        options = listOf(2, 4, 8),
                        selected = s.truePeakOversample,
                        onSelect = { v -> edit { it.copy(truePeakOversample = v) } },
                        modifier = Modifier.fillMaxWidth(),
                        labelOf = { "${it}×" },
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "4× is the BS.1770 minimum. 8× is closer above 10 kHz.",
                        color = palette.textFaint.toComposeColor(),
                        fontSize = 11.sp,
                    )
                }
                NeuSlider(
                    value = s.loudnessTargetLufs,
                    onValueChange = { v -> edit { it.copy(loudnessTargetLufs = v.round(0)) } },
                    valueRange = -30f..-5f,
                    label = "Target",
                    valueText = "${s.loudnessTargetLufs.fmt(0)} LUFS",
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                Text(
                    "−14 streaming, −16 podcast, −23 EBU R128 broadcast.",
                    color = palette.textFaint.toComposeColor(),
                    fontSize = 11.sp,
                )
            }

            // ---- stereo ---------------------------------------------------------
            SectionTitle("Stereo")
            NeuCard {
                NeuSlider(
                    value = s.goniometerPersistence,
                    onValueChange = { v -> edit { it.copy(goniometerPersistence = v) } },
                    valueRange = 0f..0.95f,
                    label = "Goniometer persistence",
                    valueText = "${(s.goniometerPersistence * 100).roundToInt()}%",
                    modifier = Modifier.fillMaxWidth(),
                )
                NeuSlider(
                    value = s.correlationWindowMs,
                    onValueChange = { v -> edit { it.copy(correlationWindowMs = v.round(0)) } },
                    valueRange = 20f..2000f,
                    label = "Correlation window",
                    valueText = "${s.correlationWindowMs.fmt(0)} ms",
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ---- appearance -------------------------------------------------------
            SectionTitle("Appearance")
            NeuCard {
                NeuSegmented(
                    options = ThemeMode.entries.toList(),
                    selected = s.theme,
                    onSelect = { v -> edit { it.copy(theme = v) } },
                    modifier = Modifier.fillMaxWidth(),
                    labelOf = { it.label },
                )
                Spacer(Modifier.height(10.dp))
                NeuSegmented(
                    options = listOf(30, 60, 90, 120),
                    selected = s.uiFps,
                    onSelect = { v -> edit { it.copy(uiFps = v) } },
                    modifier = Modifier.fillMaxWidth(),
                    labelOf = { "$it fps" },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "In-app redraw cap. The analysis itself always runs at the transform's own rate.",
                    color = palette.textFaint.toComposeColor(),
                    fontSize = 11.sp,
                )
            }

            // ---- background -------------------------------------------------------
            SectionTitle("Lock screen & background")
            NeuCard {
                SettingRow(
                    "Lock-screen notification",
                    "Ongoing notification carrying the live graph. Visible on the lock screen.",
                ) {
                    NeuSwitch(s.notificationEnabled) { v -> edit { it.copy(notificationEnabled = v) } }
                }
                if (s.notificationEnabled) {
                    NeuPicker(
                        options = VizPage.entries.toList(),
                        selected = s.notificationPage,
                        onSelect = { v -> edit { it.copy(notificationPage = v) } },
                        label = "Shows",
                        labelOf = { it.label },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    NeuSlider(
                        value = s.notificationFps.toFloat(),
                        onValueChange = { v -> edit { it.copy(notificationFps = v.roundToInt()) } },
                        valueRange = 1f..15f,
                        steps = 14,
                        label = "Update rate",
                        valueText = "${s.notificationFps} fps",
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    )
                    Text(
                        "Android throttles notification updates. Above about 12 per second they are " +
                            "dropped rather than queued, and every frame is a bitmap sent to another " +
                            "process — this is the setting that costs battery.",
                        color = palette.textFaint.toComposeColor(),
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            NeuCard {
                SettingRow(
                    "Floating overlay",
                    "Full-rate window on top of other apps. Android hides overlays on the lock " +
                        "screen, so this does not replace the notification.",
                ) {
                    NeuSwitch(s.overlayEnabled) { v -> edit { it.copy(overlayEnabled = v) } }
                }
                if (s.overlayEnabled && !canDrawOverlays()) {
                    NeuButton(
                        onClick = onRequestOverlayPermission,
                        label = "Grant \"display over other apps\"",
                        primary = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (s.overlayEnabled) {
                    NeuPicker(
                        options = VizPage.entries.toList(),
                        selected = s.overlayPage,
                        onSelect = { v -> edit { it.copy(overlayPage = v) } },
                        label = "Shows",
                        labelOf = { it.label },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    )
                    NeuSlider(
                        value = s.overlayFps.toFloat(),
                        onValueChange = { v -> edit { it.copy(overlayFps = v.roundToInt()) } },
                        valueRange = 10f..120f,
                        label = "Update rate",
                        valueText = "${s.overlayFps} fps",
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    )
                    NeuSlider(
                        value = s.overlayOpacity,
                        onValueChange = { v -> edit { it.copy(overlayOpacity = v) } },
                        valueRange = 0.25f..1f,
                        label = "Opacity",
                        valueText = "${(s.overlayOpacity * 100).roundToInt()}%",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Drag to move, drag the bottom-right corner to resize, double-tap to collapse.",
                        color = palette.textFaint.toComposeColor(),
                        fontSize = 11.sp,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            NeuCard {
                SettingRow(
                    "Home screen widget",
                    "Pushed frame by frame from the service. A level indicator, not a smooth graph.",
                ) {
                    NeuSwitch(s.widgetEnabled) { v -> edit { it.copy(widgetEnabled = v) } }
                }
                if (s.widgetEnabled) {
                    NeuPicker(
                        options = VizPage.entries.toList(),
                        selected = s.widgetPage,
                        onSelect = { v -> edit { it.copy(widgetPage = v) } },
                        label = "Shows",
                        labelOf = { it.label },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    NeuSlider(
                        value = s.widgetFps.toFloat(),
                        onValueChange = { v -> edit { it.copy(widgetFps = v.roundToInt()) } },
                        valueRange = 1f..4f,
                        steps = 3,
                        label = "Update rate",
                        valueText = "${s.widgetFps} fps",
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    )
                }
                SettingRow(
                    "Pause drawing when the screen is off",
                    "Capture keeps running; only the surfaces stop. Leave this on.",
                ) {
                    NeuSwitch(s.pauseRenderWhenScreenOff) { v ->
                        edit { it.copy(pauseRenderWhenScreenOff = v) }
                    }
                }
            }

            SectionTitle("Updates")
            UpdateCard(viewModel.updates)

            Spacer(Modifier.height(24.dp))
            NeuButton(
                onClick = viewModel::resetSettings,
                label = "Reset everything to defaults",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

// ---- helpers ---------------------------------------------------------------

/**
 * Frequency sliders are logarithmic: a linear 20 Hz–20 kHz slider spends 95 % of
 * its travel above 1 kHz, which makes the two decades that matter for a bass
 * crossover unusable.
 */
private fun freqToSlider(hz: Float): Float =
    (ln((hz / 20f).coerceAtLeast(1e-3f)) / ln(1000f)).coerceIn(0f, 1f)

private fun sliderToFreq(t: Float): Float = 20f * 1000f.pow(t.coerceIn(0f, 1f))

private fun Float.round(decimals: Int): Float {
    val factor = 10f.pow(decimals)
    return (this * factor).roundToInt() / factor
}

private fun Float.fmt(decimals: Int): String = String.format(Locale.US, "%.${decimals}f", this)

private fun <T> List<T>.replaceAt(index: Int, transform: (T) -> T): List<T> =
    mapIndexed { i, value -> if (i == index) transform(value) else value }
