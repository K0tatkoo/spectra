package com.n3d.spectra.desktop.dev

import com.n3d.spectra.audio.MonoRing
import com.n3d.spectra.dsp.Biquad
import com.n3d.spectra.dsp.Fft
import com.n3d.spectra.dsp.WindowFunction
import com.n3d.spectra.dsp.nes.Nes2A03
import com.n3d.spectra.paint.Palette
import com.n3d.spectra.settings.ColorMap

/**
 * Fixtures for the pieces everything else stands on: the FFT, the windows,
 * the biquads, the mono ring, the 2A03's arithmetic and the colour maps.
 * Checked by ios/SpectraCore/Tests/SpectraCoreTests/BaseTests.swift.
 */
object IosBaseFixtures {

    fun export(sink: (IosFixture) -> Unit) {
        for (size in intArrayOf(1024, 4096)) {
            val re = IosSignals.mix(
                IosSignals.sine(size, size, 5.3, 0.8),
                IosSignals.sine(size, size, 211.0, 0.1, 0.4),
                IosSignals.noise(size, 0.01),
            )
            val im = IosSignals.noise(size, 0.05, seed = 99L)
            val outRe = re.copyOf()
            val outIm = im.copyOf()
            Fft(size).transform(outRe, outIm)
            sink(IosFixture("fft_$size").meta("size", size).f32("re", re).f32("im", im).f32("outRe", outRe).f32("outIm", outIm))
        }

        val windows = IosFixture("windows").meta("n", 1024)
            .meta("names", WindowFunction.entries.map { it.name })
        for (w in WindowFunction.entries) {
            val b = w.build(1024)
            windows.f32("${w.name}.samples", b.samples)
                .f32("${w.name}.constants", floatArrayOf(b.coherentGain, b.enbw, b.sumSquares))
        }
        sink(windows)

        val input = IosSignals.noise(2048, 0.5)
        val biquads = IosFixture("biquads").f32("input", input)
        val filters = listOf(
            "lowPass48k1k" to Biquad.lowPass(48000, 1000.0),
            "lowPass44k80q2" to Biquad.lowPass(44100, 80.0, 2.0),
            "highPass48k20" to Biquad.highPass(48000, 20.0),
            "kShelf48k" to Biquad.kWeightingShelf(48000),
            "kShelf44k" to Biquad.kWeightingShelf(44100),
            "kHighPass48k" to Biquad.kWeightingHighPass(48000),
        )
        biquads.meta("names", filters.map { it.first })
        for ((name, f) in filters) biquads.f64(name, DoubleArray(input.size) { f.process(input[it].toDouble()) })
        biquads.f64("butterworthQs8", Biquad.butterworthQs(8))
        sink(biquads)

        // The ring, fed in awkward chunk sizes that wrap at odd places.
        val ring = MonoRing(1000)
        val feed = IosSignals.noise(3333, 1.0, seed = 7L)
        val chunks = intArrayOf(1, 999, 17, 400, 1024, 892)
        val latest = mutableListOf<FloatArray>()
        val decimated = mutableListOf<FloatArray>()
        val pending = mutableListOf<Int>()
        var at = 0
        for (c in chunks) {
            ring.write(feed, at, c)
            at += c
            pending += ring.pending
            ring.consume(300)
            latest += FloatArray(1000).also { ring.copyLatest(it) }
            decimated += FloatArray(64).also { ring.copyNewestDecimated(it, 512, 8) }
        }
        sink(
            IosFixture("monoRing").meta("chunks", chunks.toList()).f32("feed", feed)
                .i32("pending", pending.toIntArray())
                .f32("latest", latest.flatMap { it.asIterable() }.toFloatArray())
                .f32("decimated", decimated.flatMap { it.asIterable() }.toFloatArray()),
        )

        val hz = doubleArrayOf(27.5, 41.2, 55.0, 61.7, 110.0, 220.0, 440.0, 1000.0, 4186.0, 8.0)
        val nes = IosFixture("nes2a03").f64("hz", hz)
        for (r in Nes2A03.Region.entries) {
            nes.i32("${r.name}.timerFor", IntArray(hz.size) { Nes2A03.timerFor(r, hz[it]) })
            nes.f64("${r.name}.frequency", DoubleArray(hz.size) { Nes2A03.frequency(r, Nes2A03.timerFor(r, hz[it])) })
            nes.f64("${r.name}.samplesPerStep", DoubleArray(hz.size) { Nes2A03.samplesPerStep(r, Nes2A03.timerFor(r, hz[it]), 48000) })
        }
        nes.meta("noteNames", hz.map { Nes2A03.noteName(it) })
            .f64("centsOff", DoubleArray(hz.size) { Nes2A03.centsOff(hz[it]) })
            .f32("phases", FloatArray(67) { -1.1f + it * 0.0331f })
        nes.f32("levelAt", FloatArray(67) { Nes2A03.levelAt(-1.1f + it * 0.0331f) })
            .i32("dacAt", IntArray(67) { Nes2A03.dacAt(-1.1f + it * 0.0331f) })
            .meta("waveRms", Nes2A03.WAVE_RMS)
        sink(nes)

        val palette = IosFixture("palette")
        for ((label, p) in listOf("dark" to Palette.DARK, "light" to Palette.LIGHT)) {
            for (map in ColorMap.entries) palette.i32("$label.${map.name}", p.colorMap(map))
            palette.i32("$label.surfaces", intArrayOf(p.surfaceHigh, p.surfaceLow, Palette.withAlpha(p.accent, 0.37f)))
        }
        sink(palette)
    }
}
