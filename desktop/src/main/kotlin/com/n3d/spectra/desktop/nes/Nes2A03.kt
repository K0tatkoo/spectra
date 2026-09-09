package com.n3d.spectra.desktop.nes

import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The Ricoh 2A03's triangle channel, as arithmetic.
 *
 * Everything the analyser needs to know about the chip lives here, and it is all
 * exact rather than approximate — the triangle is not "a triangle wave", it is a
 * 32-entry sequence clocked by a divider off the CPU clock, and the whole point
 * of the display it feeds is to show that difference.
 *
 * Three facts drive the rest of the code:
 *
 *  1. **It is 4 bits.** The sequencer walks a fixed 32-step table of DAC levels
 *     15…0, 0…15. There are only sixteen output values, and both extremes are
 *     held for two steps, so the waveform is a staircase with flats at the top
 *     and bottom — never a straight ramp.
 *  2. **It is clocked by the CPU, not the APU.** The pulse channels' timers are
 *     clocked every *other* CPU cycle; the triangle's is clocked every cycle.
 *     That is why its period is 32·(t+1) CPU cycles rather than 16·(t+1)·2, and
 *     why the same timer value plays an octave lower on the pulse channels.
 *  3. **It has no volume.** The linear counter gates it on or off and that is
 *     all, so amplitude carries no information — which is what makes it safe for
 *     the display to normalise the captured level away entirely.
 */
object Nes2A03 {

    /** The 32 DAC levels the sequencer walks, in order. */
    val SEQUENCE = intArrayOf(
        15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0,
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
    )

    const val STEPS = 32
    const val LEVELS = 16

    /**
     * [SEQUENCE] centred and scaled to −1…+1.
     *
     * Level 0 maps to −1 and level 15 to +1, so the mid-point sits at 7.5 — a
     * value the DAC can never actually output. That asymmetry is real: the chip
     * has no zero, only two levels either side of it.
     */
    val WAVE = FloatArray(STEPS) { (SEQUENCE[it] - 7.5f) / 7.5f }

    /** RMS of [WAVE], used to fit a captured level to the chip's own scale. */
    val WAVE_RMS: Float = kotlin.math.sqrt(WAVE.sumOf { (it * it).toDouble() } / STEPS).toFloat()

    /**
     * CPU clocks. The APU divides these, so a region change retunes every
     * channel — the same $400A/$400B pair is about 7.6 % flat on PAL.
     */
    enum class Region(val label: String, val cpuHz: Double) {
        NTSC("NTSC", 21477272.0 / 12.0),
        PAL("PAL", 26601712.0 / 16.0),
        DENDY("Dendy", 26601712.0 / 15.0),
    }

    /** The 11-bit timer's range. */
    const val TIMER_MAX = 2047

    /**
     * Emulators mute the channel below this timer value.
     *
     * At t < 2 the sequencer advances faster than the DAC can be heard to move
     * and real hardware emits a mostly-inaudible click, so nearly every emulator
     * silences it instead. A "note" detected down there is not a note.
     */
    const val TIMER_MIN_AUDIBLE = 2

    /** f = CPU / (32·(t+1)). */
    fun frequency(region: Region, timer: Int): Double = region.cpuHz / (STEPS * (timer + 1.0))

    /** The timer the chip would need to play [hz], clamped to the 11-bit register. */
    fun timerFor(region: Region, hz: Double): Int {
        if (hz <= 0.0) return TIMER_MAX
        val t = (region.cpuHz / (STEPS * hz)).roundToLong() - 1L
        return t.coerceIn(0L, TIMER_MAX.toLong()).toInt()
    }

    /** Lowest and highest notes the channel can play in this region. */
    fun lowestHz(region: Region): Double = frequency(region, TIMER_MAX)
    fun highestHz(region: Region): Double = frequency(region, TIMER_MIN_AUDIBLE)

    /**
     * The chip's output at a phase in 0…1, where 0 is the first step of the
     * sequence — the top of the wave, level 15.
     */
    fun levelAt(phase: Float): Float {
        var u = phase % 1f
        if (u < 0f) u += 1f
        val k = (u * STEPS).toInt().coerceIn(0, STEPS - 1)
        return WAVE[k]
    }

    /** The raw 0…15 DAC level at a phase in 0…1. */
    fun dacAt(phase: Float): Int {
        var u = phase % 1f
        if (u < 0f) u += 1f
        return SEQUENCE[(u * STEPS).toInt().coerceIn(0, STEPS - 1)]
    }

    /** The −1…+1 value of DAC level [level] (0…15). */
    fun valueOfLevel(level: Int): Float = (level - 7.5f) / 7.5f

    /**
     * How many captured samples fall inside one DAC step.
     *
     * This is the number that decides whether the staircase is visible at all.
     * Each step lasts (t+1) CPU cycles, so at 48 kHz an A1 (t = 1016) holds each
     * level for about 27 samples and every corner survives resampling, while an
     * A4 holds it for 3 and the whole thing arrives as a smooth triangle. It is
     * the reason this display is useful for bass and misleading for melody.
     */
    fun samplesPerStep(region: Region, timer: Int, sampleRate: Int): Double =
        sampleRate * (timer + 1.0) / region.cpuHz

    // ---- note naming -------------------------------------------------------

    private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    /** Equal-tempered note number for [hz], fractional, A4 = 440 = 69. */
    fun midiOf(hz: Double): Double = 69.0 + 12.0 * ln(hz / 440.0) / ln(2.0)

    /** e.g. "A1" for 55 Hz. */
    fun noteName(hz: Double): String {
        if (hz <= 0.0) return "—"
        val midi = midiOf(hz).roundToInt()
        val name = NOTE_NAMES[((midi % 12) + 12) % 12]
        return "$name${midi / 12 - 1}"
    }

    /** Signed cents from [hz] to the nearest equal-tempered note. */
    fun centsOff(hz: Double): Double {
        if (hz <= 0.0) return 0.0
        val midi = midiOf(hz)
        return (midi - midi.roundToInt()) * 100.0
    }
}
