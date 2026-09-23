package com.n3d.spectra.desktop.audio

import com.n3d.spectra.audio.AudioCapture
import com.n3d.spectra.dsp.nes.Nes2A03
import kotlin.math.max
import kotlin.random.Random

/**
 * A 2A03 rendered in software, as a capture source.
 *
 * Two things make this worth shipping rather than keeping in a test:
 *
 *  * The Windows loopback problem is real (see [Devices]) and a user whose
 *    machine has no "Stereo Mix" has, until they fix that, no way to tell a
 *    broken install from a missing endpoint. This gives them a signal that is
 *    definitely there.
 *  * The 2A03 view is unusual enough that seeing it work on known-good audio is
 *    the fastest way to understand what it is showing — the staircase, the timer
 *    readout and the fold depth all mean more when you already know the answer.
 *
 * The triangle is generated the way the chip generates it: a zero-order hold on
 * the 32-step sequence, clocked at CPU rate and sampled at the output rate. It is
 * deliberately *not* band-limited, because that is what a real emulator's output
 * looks like once it has been through a sound card.
 */
class SyntheticCapture(
    override val sampleRate: Int,
    stereo: Boolean,
    private val region: Nes2A03.Region = Nes2A03.Region.NTSC,
) : AudioCapture {

    override val channelCount: Int = if (stereo) 2 else 1
    override val describe: String = "Built-in 2A03 demo · $sampleRate Hz · ${region.label}"

    private var cycle = 0.0
    private var pulsePhase1 = 0.0
    private var pulsePhase2 = 0.0
    private var sampleIndex = 0L
    private var startedAt = 0L
    private val random = Random(0x2A03)

    /** A bass line in timer values, one per eighth note, and a melody over it. */
    private val bass = intArrayOf(
        1016, 1016, 1355, 1016, 905, 905, 1016, 1207,
        1016, 1016, 1355, 1523, 1355, 1207, 1016, 1016,
    )
    private val melody = doubleArrayOf(
        440.0, 523.25, 659.25, 523.25, 493.88, 587.33, 493.88, 440.0,
        440.0, 523.25, 659.25, 783.99, 659.25, 587.33, 523.25, 493.88,
    )

    /**
     * Quarter notes at 120 bpm — half a second each.
     *
     * Not a musical choice: the tracker's pitch window is 8192 samples, 170 ms at
     * 48 kHz, and a note shorter than that is straddled by every analysis window,
     * so the detected pitch lands between two notes and the timer readout wanders.
     * A demo whose whole job is to show the lock working should not be fighting it.
     */
    private val samplesPerStep get() = sampleRate * 60 / 120

    override fun start() {
        startedAt = System.nanoTime()
        sampleIndex = 0
    }

    override fun read(out: FloatArray): Int {
        val frames = out.size / channelCount
        // Pace it to real time. Without this the engine would consume a whole
        // history in microseconds and every meter would be meaningless.
        val dueAt = startedAt + (sampleIndex + frames) * 1_000_000_000L / sampleRate
        val waitNs = dueAt - System.nanoTime()
        if (waitNs > 0) {
            try {
                Thread.sleep(waitNs / 1_000_000L, (waitNs % 1_000_000L).toInt())
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return -1
            }
        }

        val cyclesPerSample = region.cpuHz / sampleRate
        var w = 0
        for (i in 0 until frames) {
            val step = ((sampleIndex + i) / samplesPerStep % bass.size).toInt()
            val timer = bass[step]

            val seqIndex = ((cycle / (timer + 1.0)).toLong() % 32L + 32L) % 32L
            val triangle = Nes2A03.WAVE[seqIndex.toInt()] * 0.55f
            cycle += cyclesPerSample

            val m = melody[step]
            pulsePhase1 += m / sampleRate
            if (pulsePhase1 >= 1.0) pulsePhase1 -= 1.0
            pulsePhase2 += m * 1.5 / sampleRate
            if (pulsePhase2 >= 1.0) pulsePhase2 -= 1.0
            val pulse1 = if (pulsePhase1 < 0.5) 0.10f else -0.10f
            val pulse2 = if (pulsePhase2 < 0.25) 0.06f else -0.06f
            // A little noise channel on the off-beats, so the fold has something
            // non-periodic to reject.
            val noise = if (((sampleIndex + i) / (samplesPerStep / 4)) % 2L == 1L) {
                (random.nextFloat() - 0.5f) * 0.03f
            } else {
                0f
            }

            val v = (triangle + pulse1 + pulse2 + noise).coerceIn(-1f, 1f)
            out[w++] = v
            // Slightly different levels per side, so the goniometer and the
            // correlation meter have something honest to show.
            if (channelCount == 2) out[w++] = (triangle + pulse1 * 0.7f + pulse2 * 1.2f + noise).coerceIn(-1f, 1f)
        }
        sampleIndex += frames
        return max(0, w)
    }

    override fun stop() = Unit
    override fun release() = Unit

    companion object {
        /** The name this appears under in the input list. */
        const val NAME = "Built-in 2A03 demo signal"
    }
}
