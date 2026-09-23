package com.n3d.spectra.desktop.ui

import com.n3d.spectra.desktop.audio.DesktopEngine
import com.n3d.spectra.desktop.audio.Devices
import com.n3d.spectra.desktop.audio.InputDevice
import com.n3d.spectra.desktop.audio.LineCapture
import com.n3d.spectra.desktop.audio.SyntheticCapture
import com.n3d.spectra.dsp.nes.Nes2A03
import com.n3d.spectra.desktop.paint.Align
import com.n3d.spectra.desktop.paint.Box
import com.n3d.spectra.desktop.paint.Colors
import com.n3d.spectra.desktop.paint.Fonts
import com.n3d.spectra.desktop.paint.Neu2D
import com.n3d.spectra.desktop.paint.VizPainter2D
import com.n3d.spectra.desktop.paint.ellipsize
import com.n3d.spectra.desktop.paint.quality
import com.n3d.spectra.desktop.paint.circle
import com.n3d.spectra.desktop.paint.text
import com.n3d.spectra.desktop.paint.textWidth
import com.n3d.spectra.desktop.state.DesktopPage
import com.n3d.spectra.desktop.state.DesktopState
import com.n3d.spectra.desktop.state.StateStore
import com.n3d.spectra.desktop.state.WaveMode
import com.n3d.spectra.desktop.state.forNesTriangle
import com.n3d.spectra.dsp.WindowFunction
import com.n3d.spectra.paint.Palette
import com.n3d.spectra.settings.BandSlope
import com.n3d.spectra.settings.ColorMap
import com.n3d.spectra.settings.FreqScale
import com.n3d.spectra.settings.Settings
import com.n3d.spectra.settings.SpectrumStyle
import com.n3d.spectra.settings.ThemeMode
import com.n3d.spectra.settings.Weighting
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.Locale
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Spectra's window.
 *
 * Three regions: the graph, the page bar under it, and a sidebar whose contents
 * are rebuilt for whichever page is showing. The sidebar is rebuilt rather than
 * shown/hidden because every page wants a different half-dozen controls and
 * keeping forty of them alive to toggle visibility is how a settings panel turns
 * into a maze.
 */
class MainWindow(initial: DesktopState) : JFrame("Spectra") {

    private var state: DesktopState = initial
    private var palette: Palette = resolvePalette(initial.settings.theme)

    private val painter = VizPainter2D(palette)
    private val viz = VizPanel(painter, palette, state)
    private val meters = MeterStrip(palette)
    private val pageBar = PageBar(DesktopPage.entries.toList(), state.page, palette, { it.label }) { setPage(it) }
    private val sidebar = JPanel()
    private val topBar = TopBar()
    private val scrollBarUi = ThinScrollBarUI(palette)
    private var devices: List<InputDevice> = emptyList()
    private var timer: Timer? = null

    init {
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        minimumSize = Dimension(900, 560)
        size = Dimension(state.windowWidth, state.windowHeight)
        setLocationRelativeTo(null)
        if (state.maximized) extendedState = MAXIMIZED_BOTH

        val root = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                (g as Graphics2D).quality()
                g.paint = Colors.of(palette.bg)
                g.fillRect(0, 0, width, height)
            }
        }
        root.isOpaque = true

        val centre = JPanel(BorderLayout())
        centre.isOpaque = false
        centre.add(viz, BorderLayout.CENTER)

        val under = JPanel()
        under.isOpaque = false
        under.layout = BoxLayout(under, BoxLayout.Y_AXIS)
        under.border = BorderFactory.createEmptyBorder(0, 8, 8, 8)
        under.add(pageBar)
        under.add(meters)
        centre.add(under, BorderLayout.SOUTH)

        sidebar.isOpaque = false
        sidebar.layout = BoxLayout(sidebar, BoxLayout.Y_AXIS)
        sidebar.border = BorderFactory.createEmptyBorder(10, 12, 14, 14)

        val scroll = JScrollPane(sidebar).apply {
            isOpaque = false
            viewport.isOpaque = false
            border = BorderFactory.createEmptyBorder()
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = 18
            verticalScrollBar.setUI(scrollBarUi)
            verticalScrollBar.isOpaque = false
            verticalScrollBar.preferredSize = Dimension(10, 10)
            preferredSize = Dimension((330 * state.uiScale).toInt(), 100)
        }

        root.add(topBar, BorderLayout.NORTH)
        root.add(centre, BorderLayout.CENTER)
        root.add(scroll, BorderLayout.EAST)
        contentPane = root

        pageBar.scale = state.uiScale
        meters.scale = state.uiScale

        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) = shutdown()
        })

        // The .exe carries the icon for Explorer; this is the same mark for the
        // window, the taskbar and Alt-Tab, which the executable's resource does
        // not supply.
        iconImages = AppIcon.images()

        refreshDevices()
        rebuildSidebar()
        startTicker()
        startCapture()
    }

    // ---- lifecycle ---------------------------------------------------------

    private fun startTicker() {
        timer?.stop()
        val fps = state.settings.uiFps.coerceIn(10, 120)
        timer = Timer(1000 / fps) {
            val f = DesktopEngine.frame?.analysis
            if (f != null) {
                meters.update(f.peakDbL, f.peakDbR, f.rmsDbL, f.rmsDbR, f.waveR.isNotEmpty())
            }
            topBar.repaint()
            viz.repaint()
        }.apply { start() }
    }

    private fun startCapture() {
        DesktopEngine.updateSettings(state.settings)
        DesktopEngine.updateNes(state.nes)
        DesktopEngine.nesActive = state.page == DesktopPage.WAVEFORM && state.waveMode == WaveMode.NES
        val device = Devices.find(state.deviceName)
        val source = if (device != null && device.mixer == null) {
            SyntheticCapture(state.settings.sampleRate, state.settings.stereo, state.nes.region)
        } else {
            LineCapture(device, state.settings.sampleRate, state.settings.stereo)
        }
        DesktopEngine.start(source)
    }

    private fun restartCapture() {
        DesktopEngine.stop()
        startCapture()
    }

    private fun shutdown() {
        timer?.stop()
        DesktopEngine.stop()
        state = state.copy(
            windowWidth = if (extendedState == MAXIMIZED_BOTH) state.windowWidth else width,
            windowHeight = if (extendedState == MAXIMIZED_BOTH) state.windowHeight else height,
            maximized = extendedState == MAXIMIZED_BOTH,
        )
        StateStore.save(state)
        dispose()
        System.exit(0)
    }

    // ---- state -------------------------------------------------------------

    private fun update(transform: (DesktopState) -> DesktopState) {
        val previous = state
        state = transform(state)
        if (previous.settings !== state.settings) {
            if (DesktopEngine.updateSettings(state.settings)) restartCapture()
        }
        if (previous.nes !== state.nes) DesktopEngine.updateNes(state.nes)
        if (previous.settings.theme != state.settings.theme) {
            palette = resolvePalette(state.settings.theme)
            Neu2D.clearCache()
            applyPalette()
        }
        if (previous.settings.uiFps != state.settings.uiFps) startTicker()
        DesktopEngine.nesActive = state.page == DesktopPage.WAVEFORM && state.waveMode == WaveMode.NES
        viz.update(state, palette)
        repaint()
    }

    private fun setPage(page: DesktopPage) {
        update { it.copy(page = page) }
        rebuildSidebar()
    }

    private fun applyPalette() {
        painter.palette = palette
        pageBar.applyPalette(palette)
        scrollBarUi.applyPalette(palette)
        meters.applyPalette(palette)
        viz.update(state, palette)
        rebuildSidebar()
    }

    private fun resolvePalette(mode: ThemeMode): Palette = Palette.of(
        when (mode) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            ThemeMode.SYSTEM -> SystemTheme.prefersDark
        },
    )

    private fun refreshDevices() {
        devices = Devices.list()
    }

    // ---- sidebar -----------------------------------------------------------

    private fun add(c: javax.swing.JComponent) {
        if (c is NeuComponent) c.scale = state.uiScale
        c.alignmentX = LEFT_ALIGNMENT
        sidebar.add(c)
    }

    private fun gap(h: Int = 6) {
        sidebar.add(javax.swing.Box.createVerticalStrut((h * state.uiScale).toInt()))
    }

    private fun rebuildSidebar() {
        sidebar.removeAll()
        buildSourceSection()
        gap(10)
        when (state.page) {
            DesktopPage.SPECTRUM -> buildSpectrumSection()
            DesktopPage.BANDS -> buildBandsSection()
            DesktopPage.SPECTROGRAM -> buildSpectrogramSection()
            DesktopPage.LOUDNESS -> buildLoudnessSection()
            DesktopPage.STEREO -> buildStereoSection()
            DesktopPage.WAVEFORM -> buildWaveformSection()
        }
        gap(10)
        buildDisplaySection()
        sidebar.revalidate()
        sidebar.repaint()
    }

    private fun s() = state.settings

    private fun setSettings(transform: (Settings) -> Settings) = update { it.copy(settings = transform(it.settings)) }

    private fun buildSourceSection() {
        add(SectionLabel("Source", palette))
        add(
            NeuDropdown(
                "Input", devices, Devices.find(state.deviceName) ?: devices.firstOrNull(), palette,
                {
                when {
                    it.mixer == null -> it.name
                    it.loopback -> "${it.name}  ·  system audio"
                    else -> it.name
                }
            },
            ) { device ->
                update { it.copy(deviceName = device.name) }
                restartCapture()
            },
        )
        if (devices.none { it.loopback }) {
            add(
                NoteLabel(
                    "No loopback input found. Windows only lets an app record what is playing " +
                        "through a loopback endpoint — enable \"Stereo Mix\" in Sound settings → " +
                        "Recording, or install a virtual cable, then pick it above.",
                    palette, warn = true,
                ),
            )
        }
        add(
            NeuSegmented("Sample rate", Settings.SAMPLE_RATES, s().sampleRate, palette, { "$it Hz" }) { rate ->
                setSettings { it.copy(sampleRate = rate) }
            },
        )
        add(NeuToggle("Stereo", s().stereo, palette) { v -> setSettings { it.copy(stereo = v) } })
        add(
            NeuSlider("Input gain", -24f, 24f, s().inputGainDb, palette, 0.5f, { String.format(Locale.US, "%+.1f dB", it) }) { v ->
                setSettings { it.copy(inputGainDb = v) }
            },
        )
        val row = JPanel()
        row.isOpaque = false
        row.layout = BoxLayout(row, BoxLayout.X_AXIS)
        val startStop = NeuButton(if (DesktopEngine.isRunning()) "Stop" else "Start", palette, accent = true) {
            if (DesktopEngine.isRunning()) DesktopEngine.stop() else startCapture()
            rebuildSidebar()
        }
        startStop.scale = state.uiScale
        val rescan = NeuButton("Rescan", palette) {
            refreshDevices()
            rebuildSidebar()
        }
        rescan.scale = state.uiScale
        val reset = NeuButton("Reset meters", palette) { DesktopEngine.resetMeters() }
        reset.scale = state.uiScale
        row.add(startStop)
        row.add(javax.swing.Box.createHorizontalStrut(6))
        row.add(rescan)
        row.add(javax.swing.Box.createHorizontalStrut(6))
        row.add(reset)
        row.fixHeight((36 * state.uiScale).toInt())
        add(row)
    }

    private fun buildSpectrumSection() {
        add(SectionLabel("Spectrum", palette))
        add(NeuSegmented("Style", SpectrumStyle.entries.toList(), s().spectrumStyle, palette, { it.label }) { v -> setSettings { it.copy(spectrumStyle = v) } })
        add(NeuSegmented("Frequency scale", FreqScale.entries.toList(), s().freqScale, palette, { it.label }) { v -> setSettings { it.copy(freqScale = v) } })
        add(NeuSegmented("Weighting", Weighting.entries.toList(), s().weighting, palette, { it.label }) { v -> setSettings { it.copy(weighting = v) } })
        add(NeuSegmented("FFT size", Settings.FFT_SIZES, s().fftSize, palette, { "$it" }) { v -> setSettings { it.copy(fftSize = v) } })
        add(NeuSegmented("Overlap", Settings.OVERLAPS, s().overlap, palette, { "${100 - 100 / it} %" }) { v -> setSettings { it.copy(overlap = v) } })
        add(NeuSegmented("Window", WindowFunction.entries.toList(), s().window, palette, { it.label }) { v -> setSettings { it.copy(window = v) } })
        add(NeuSlider("Floor", -140f, -40f, s().floorDb, palette, 5f, { "${it.toInt()} dB" }) { v -> setSettings { it.copy(floorDb = v) } })
        add(NeuSlider("Ceiling", -40f, 12f, s().ceilingDb, palette, 2f, { "${it.toInt()} dB" }) { v -> setSettings { it.copy(ceilingDb = v) } })
        add(NeuSlider("Lowest frequency", 10f, 500f, s().minHz, palette, 5f, { "${it.toInt()} Hz" }) { v -> setSettings { it.copy(minHz = v) } })
        add(NeuSlider("Highest frequency", 1000f, 24000f, s().maxHz, palette, 500f, { "${(it / 1000).toInt()} kHz" }) { v -> setSettings { it.copy(maxHz = v) } })
        add(NeuSlider("Tilt", 0f, 6f, s().tiltDbPerOct, palette, 0.5f, { String.format(Locale.US, "%.1f dB/oct", it) }) { v -> setSettings { it.copy(tiltDbPerOct = v) } })
        add(NeuSlider("Attack", 1f, 200f, s().attackMs, palette, 1f, { "${it.toInt()} ms" }) { v -> setSettings { it.copy(attackMs = v) } })
        add(NeuSlider("Release", 20f, 2000f, s().releaseMs, palette, 10f, { "${it.toInt()} ms" }) { v -> setSettings { it.copy(releaseMs = v) } })
        add(NeuToggle("Peak hold", s().peakHold, palette) { v -> setSettings { it.copy(peakHold = v) } })
        add(NeuToggle("Grid", s().showGrid, palette) { v -> setSettings { it.copy(showGrid = v) } })
        add(NeuToggle("Labels", s().showLabels, palette) { v -> setSettings { it.copy(showLabels = v) } })
    }

    private fun buildBandsSection() {
        add(SectionLabel("Bands", palette))
        add(NeuSegmented("Filter slope", BandSlope.entries.toList(), s().bandSlope, palette, { it.label }) { v -> setSettings { it.copy(bandSlope = v) } })
        add(NeuSlider("Attack", 1f, 100f, s().bandAttackMs, palette, 1f, { "${it.toInt()} ms" }) { v -> setSettings { it.copy(bandAttackMs = v) } })
        add(NeuSlider("Release", 20f, 1000f, s().bandReleaseMs, palette, 10f, { "${it.toInt()} ms" }) { v -> setSettings { it.copy(bandReleaseMs = v) } })
        add(SectionLabel("Enabled", palette))
        for ((i, band) in s().bands.withIndex()) {
            add(
                NeuToggle("${band.name}  ${band.lowHz.toInt()}–${band.highHz.toInt()} Hz", band.enabled, palette) { v ->
                    setSettings { st ->
                        st.copy(bands = st.bands.mapIndexed { j, b -> if (i == j) b.copy(enabled = v) else b })
                    }
                },
            )
        }
    }

    private fun buildSpectrogramSection() {
        add(SectionLabel("Spectrogram", palette))
        add(NeuSegmented("Colours", ColorMap.entries.toList(), s().colorMap, palette, { it.label }) { v -> setSettings { it.copy(colorMap = v) } })
        add(NeuSlider("Floor", -140f, -40f, s().spectrogramFloorDb, palette, 5f, { "${it.toInt()} dB" }) { v -> setSettings { it.copy(spectrogramFloorDb = v) } })
        add(NeuSlider("Ceiling", -60f, 0f, s().spectrogramCeilingDb, palette, 2f, { "${it.toInt()} dB" }) { v -> setSettings { it.copy(spectrogramCeilingDb = v) } })
        add(NeuSegmented("Frequency scale", FreqScale.entries.toList(), s().freqScale, palette, { it.label }) { v -> setSettings { it.copy(freqScale = v) } })
    }

    private fun buildLoudnessSection() {
        add(SectionLabel("Loudness", palette))
        add(NeuSlider("Target", -36f, -6f, s().loudnessTargetLufs, palette, 1f, { "${it.toInt()} LUFS" }) { v -> setSettings { it.copy(loudnessTargetLufs = v) } })
        add(NeuToggle("True peak", s().truePeakEnabled, palette) { v -> setSettings { it.copy(truePeakEnabled = v) } })
        add(NeuSegmented("Oversample", listOf(4, 8), s().truePeakOversample, palette, { "${it}×" }) { v -> setSettings { it.copy(truePeakOversample = v) } })
        add(NoteLabel("Integrated loudness and LRA accumulate until you reset the meters.", palette))
    }

    private fun buildStereoSection() {
        add(SectionLabel("Stereo", palette))
        add(NeuSlider("Persistence", 0f, 0.95f, s().goniometerPersistence, palette, 0.05f, { String.format(Locale.US, "%.2f", it) }) { v -> setSettings { it.copy(goniometerPersistence = v) } })
        add(NeuSlider("Correlation window", 50f, 1000f, s().correlationWindowMs, palette, 25f, { "${it.toInt()} ms" }) { v -> setSettings { it.copy(correlationWindowMs = v) } })
        if (!s().stereo) add(NoteLabel("The input is set to mono, so there is no stereo image to show.", palette, warn = true))
    }

    // ---- the 2A03 controls -------------------------------------------------

    private fun buildWaveformSection() {
        add(SectionLabel("Waveform", palette))
        add(
            NeuSegmented("Mode", WaveMode.entries.toList(), state.waveMode, palette, { it.label }) { mode ->
                update { it.copy(waveMode = mode) }
                rebuildSidebar()
            },
        )
        if (state.waveMode == WaveMode.FREE) {
            add(NoteLabel("A free-running scope over the newest samples, exactly as the Android build draws it.", palette))
            return
        }

        val n = state.nes
        add(
            NoteLabel(
                "Finds the NES triangle channel, rounds its period to the 11-bit timer the chip " +
                    "would have used, and holds the wave still against that. The staircase is only " +
                    "visible in the bass: at 55 Hz each DAC step lasts 27 samples, at 440 Hz it lasts 3.",
                palette,
            ),
        )
        add(SectionLabel("Chip", palette))
        add(
            NeuSegmented("Region", Nes2A03.Region.entries.toList(), n.region, palette, { it.label }) { v ->
                update { it.copy(nes = it.nes.copy(region = v)) }
            },
        )
        add(
            NeuToggle("Lock to the timer grid", n.lockToTimer, palette) { v ->
                update { it.copy(nes = it.nes.copy(lockToTimer = v)) }
            },
        )
        add(
            NoteLabel(
                if (n.lockToTimer) {
                    "Off, the display follows the measured period and drifts by a fraction of a sample per frame."
                } else {
                    "The wave will crawl sideways: a measured period is never exactly the chip's."
                },
                palette, warn = !n.lockToTimer,
            ),
        )

        add(SectionLabel("Lock", palette))
        add(
            NeuSlider("Hunt from", 15f, 100f, n.huntMinHz, palette, 5f, { "${it.toInt()} Hz" }) { v ->
                update { it.copy(nes = it.nes.copy(huntMinHz = v)) }
            },
        )
        add(
            NeuSlider("Hunt to", 100f, 600f, n.huntMaxHz, palette, 10f, { "${it.toInt()} Hz" }) { v ->
                update { it.copy(nes = it.nes.copy(huntMaxHz = v)) }
            },
        )
        add(
            NeuSlider("Confidence needed", 0.3f, 0.95f, n.clarityThreshold, palette, 0.05f, { String.format(Locale.US, "%.2f", it) }) { v ->
                update { it.copy(nes = it.nes.copy(clarityThreshold = v)) }
            },
        )
        add(
            NeuSlider("Hold after signal", 0f, 3000f, n.holdMs, palette, 100f, { "${it.toInt()} ms" }) { v ->
                update { it.copy(nes = it.nes.copy(holdMs = v)) }
            },
        )

        add(SectionLabel("Trace", palette))
        add(
            NeuSegmented("Periods on screen", listOf(1, 2, 3, 4, 6, 8), n.cycles, palette, { "$it" }) { v ->
                update { it.copy(nes = it.nes.copy(cycles = v)) }
            },
        )
        add(
            NeuToggle("Fold periods together", n.averaging, palette) { v ->
                update { it.copy(nes = it.nes.copy(averaging = v)) }
                rebuildSidebar()
            },
        )
        if (n.averaging) {
            add(
                NeuSlider("Fold depth", 1f, 48f, n.foldPeriods.toFloat(), palette, 1f, { "${it.toInt()} periods" }) { v ->
                    update { it.copy(nes = it.nes.copy(foldPeriods = v.toInt())) }
                },
            )
            add(
                NeuSlider("Persistence", 0f, 0.95f, n.persistence, palette, 0.05f, { String.format(Locale.US, "%.2f", it) }) { v ->
                    update { it.copy(nes = it.nes.copy(persistence = v)) }
                },
            )
            add(
                NoteLabel(
                    "Folding averages whole periods, which cancels anything not locked to the note — " +
                        "the pulse channels, noise, DMC. Rejection improves as the square root of the " +
                        "depth, so raise it when the staircase is buried and lower it when the bass " +
                        "line moves faster than the display keeps up with.",
                    palette,
                ),
            )
        } else {
            add(NoteLabel("Raw samples, unaveraged. Honest, and noisy under anything but a soloed channel.", palette))
        }
        add(
            NeuToggle("Quantise to the 16 DAC levels", n.quantizeToDac, palette) { v ->
                update { it.copy(nes = it.nes.copy(quantizeToDac = v)) }
            },
        )
        add(
            NeuToggle("Mark captured samples", n.showSamples, palette) { v ->
                update { it.copy(nes = it.nes.copy(showSamples = v)) }
            },
        )

        add(SectionLabel("Overlay", palette))
        add(NeuToggle("Ideal 2A03 wave", n.showIdeal, palette) { v -> update { it.copy(nes = it.nes.copy(showIdeal = v)) } })
        add(NeuToggle("4-bit level ladder", n.showLevels, palette) { v -> update { it.copy(nes = it.nes.copy(showLevels = v)) } })
        add(NeuToggle("32-step time grid", n.showSteps, palette) { v -> update { it.copy(nes = it.nes.copy(showSteps = v)) } })

        add(SectionLabel("Presets", palette))
        add(
            NeuButton("Tune everything for chiptune", palette) {
                update { it.copy(settings = it.settings.forNesTriangle()) }
                rebuildSidebar()
            },
        )
    }

    private fun buildDisplaySection() {
        add(SectionLabel("Display", palette))
        add(NeuSegmented("Theme", ThemeMode.entries.toList(), s().theme, palette, { it.label }) { v -> setSettings { it.copy(theme = v) } })
        add(
            NeuSlider("Interface scale", 0.8f, 1.8f, state.uiScale, palette, 0.1f, { String.format(Locale.US, "%.1f×", it) }) { v ->
                update { it.copy(uiScale = v) }
                pageBar.scale = v
                meters.scale = v
                rebuildSidebar()
            },
        )
        add(NeuSegmented("Frame rate", listOf(30, 60, 90, 120), s().uiFps, palette, { "$it fps" }) { v -> setSettings { it.copy(uiFps = v) } })
        add(NoteLabel("Settings are saved to ${StateStore.path()}", palette))
    }

    /** Test hooks. Only [com.n3d.spectra.desktop.dev] uses these. */
    internal fun showPageForShot(page: DesktopPage) {
        pageBar.value = page
        setPage(page)
    }

    internal fun setThemeForShot(mode: ThemeMode) = setSettings { it.copy(theme = mode) }

    // ---- top bar -----------------------------------------------------------

    private inner class TopBar : JPanel() {
        init {
            isOpaque = false
            preferredSize = Dimension(10, (58 * state.uiScale).toInt())
        }

        override fun paintComponent(gr: Graphics) {
            val g = (gr as Graphics2D).quality()
            val sc = state.uiScale
            g.text("SPECTRA", 18f * sc, 34f * sc, Fonts.sans(19f * sc, bold = true), palette.text)
            val wordmarkW: Float = g.textWidth("SPECTRA", Fonts.sans(19f * sc, bold = true))
            g.text("for Windows", 18f * sc + wordmarkW + 8f * sc, 34f * sc, Fonts.sans(10f * sc), palette.textFaint)

            val warning = DesktopEngine.warning
            val engineState = DesktopEngine.state
            val (message, color) = when {
                warning != null -> warning to palette.warn
                engineState is DesktopEngine.State.Failed -> engineState.message to palette.bad
                engineState is DesktopEngine.State.Running -> engineState.describe to palette.textDim
                engineState is DesktopEngine.State.Starting -> "starting…" to palette.textFaint
                else -> "stopped" to palette.textFaint
            }
            val dotX = 18f * sc
            val available = width - dotX - 28f * sc
            val font = Fonts.sans(10f * sc)
            g.text(g.ellipsize(message, font, available), dotX, 50f * sc, font, color)

            val y = height - 1f
            g.paint = Colors.of(Palette.withAlpha(palette.line, 0.8f))
            g.fillRect(0, y.toInt(), width, 1)

            // A dot that says at a glance whether audio is arriving.
            val live = DesktopEngine.isRunning() && DesktopEngine.frame != null
            val dotColor = when {
                engineState is DesktopEngine.State.Failed -> palette.bad
                warning != null -> palette.warn
                live -> palette.good
                else -> palette.textFaint
            }
            g.circle(width - 20f * sc, 30f * sc, 5f * sc, dotColor)
        }
    }

}

/** The window icon, loaded from the PNG set the Android launcher icon is rendered into. */
object AppIcon {
    private val sizes = listOf(16, 24, 32, 48, 64, 128, 256)

    fun images(): List<java.awt.Image> = sizes.mapNotNull { size ->
        AppIcon::class.java.getResourceAsStream("/icon/spectra-$size.png")?.use {
            runCatching { javax.imageio.ImageIO.read(it) }.getOrNull()
        }
    }
}

/** Reads the desktop's light/dark preference once, and falls back to dark. */
object SystemTheme {
    val prefersDark: Boolean by lazy {
        val os = System.getProperty("os.name").orEmpty()
        runCatching {
            when {
                os.startsWith("Windows") -> {
                    val out = ProcessBuilder(
                        "reg", "query",
                        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                        "/v", "AppsUseLightTheme",
                    ).redirectErrorStream(true).start().inputStream.bufferedReader().readText()
                    // The value is "light theme on", so dark is the absence of 1.
                    !out.contains("0x1")
                }
                os.contains("Mac") -> {
                    val out = ProcessBuilder("defaults", "read", "-g", "AppleInterfaceStyle")
                        .redirectErrorStream(true).start().inputStream.bufferedReader().readText()
                    out.contains("Dark")
                }
                else -> true
            }
        }.getOrDefault(true)
    }
}

/** Convenience so the window can be created and shown from anywhere. */
fun showMainWindow(state: DesktopState) {
    SwingUtilities.invokeLater {
        MainWindow(state).isVisible = true
    }
}
