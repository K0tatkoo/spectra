package com.n3d.spectra

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

/** Synthetic sources the tests build mixes from. All mono, −1…1. */
object TestSignals {

    fun saw(hz: Double, t: Double): Double = 2.0 * (t * hz - floor(0.5 + t * hz))

    /** The 2A03's 32-step triangle — the staircase, not a straight ramp. */
    fun nesTriangle(hz: Double, t: Double): Double {
        var u = (t * hz) % 1.0
        if (u < 0) u += 1.0
        val step = (u * 32).toInt().coerceIn(0, 31)
        val level = if (step < 16) 15 - step else step - 16
        return (level - 7.5) / 7.5
    }

    /** A sung-ish tone: a sine with a 5 Hz vibrato of ±6 Hz. */
    fun vibrato(hz: Double, t: Double): Double = sin(2 * PI * hz * t - 1.2 * cos(2 * PI * 5 * t))

    /** An 808-ish kick every [every] seconds: a falling sine with a fast decay. */
    fun kick(t: Double, every: Double = 0.5): Double {
        val tt = t % every
        if (tt > 0.25) return 0.0
        return sin(2 * PI * (50 * tt + 80 * (1 - exp(-tt * 30)) / 30)) * exp(-tt * 12)
    }

    fun render(seconds: Double, rate: Int, f: (Double) -> Double): FloatArray =
        FloatArray((seconds * rate).toInt()) { i -> f(i.toDouble() / rate).toFloat() }
}
