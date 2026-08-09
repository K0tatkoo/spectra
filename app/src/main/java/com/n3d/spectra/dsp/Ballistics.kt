package com.n3d.spectra.dsp

import kotlin.math.exp

/**
 * Asymmetric attack/release smoothing in the dB domain, plus the peak-hold
 * decay that sits on top of it.
 *
 * Smoothing in dB rather than in linear magnitude is deliberate: a 20 ms release
 * applied to linear amplitude spends almost all of its time in the top 20 dB and
 * looks frozen near the noise floor. In dB the fall rate is what the user set.
 *
 * Coefficients follow the usual one-pole form, α = 1 − e^(−T/τ), so "300 ms
 * release" means the reading has covered 63 % of the distance after 300 ms —
 * the same definition a hardware VU meter uses.
 */
class Ballistics(private var frameIntervalSec: Float) {

    var attackMs: Float = 20f
        set(v) { field = v; recompute() }
    var releaseMs: Float = 300f
        set(v) { field = v; recompute() }

    private var attackCoef = 0f
    private var releaseCoef = 0f

    init { recompute() }

    fun setFrameInterval(sec: Float) {
        frameIntervalSec = sec
        recompute()
    }

    private fun recompute() {
        attackCoef = coef(attackMs)
        releaseCoef = coef(releaseMs)
    }

    private fun coef(ms: Float): Float {
        if (ms <= 0f) return 1f
        return (1.0 - exp(-frameIntervalSec.toDouble() / (ms / 1000.0))).toFloat().coerceIn(0f, 1f)
    }

    /**
     * One step with an explicit time delta, for callers whose tick interval is
     * set by the capture chunk size rather than by the hop — the band meters run
     * off whatever AudioRecord happened to hand over, which is not constant.
     */
    fun stepDt(current: Float, target: Float, dtSec: Float): Float {
        val ms = if (target > current) attackMs else releaseMs
        val c = if (ms <= 0f) 1f
        else (1.0 - exp(-dtSec.toDouble() / (ms / 1000.0))).toFloat().coerceIn(0f, 1f)
        return current + (target - current) * c
    }

    /** One smoothed step from [current] towards [target]; both in dB. */
    fun step(current: Float, target: Float): Float {
        val coef = if (target > current) attackCoef else releaseCoef
        return current + (target - current) * coef
    }

    /** Vectorised form, used for the ~2000 spectrum bins. */
    fun stepAll(current: FloatArray, target: FloatArray) {
        for (i in current.indices) {
            val t = target[i]
            val c = current[i]
            current[i] = c + (t - c) * (if (t > c) attackCoef else releaseCoef)
        }
    }
}

/**
 * Peak hold with a flat hold time followed by a constant dB/s fall. Kept
 * separate from [Ballistics] because peak hold is not a filter — it is a
 * max() with a timer, and mixing the two produces the "sagging peaks" that
 * make an analyser useless for spotting transients.
 */
class PeakHold(size: Int) {

    var holdMs: Float = 900f
    var fallDbPerSec: Float = 24f
    var enabled: Boolean = true

    val values = FloatArray(size) { FLOOR_DB }
    private val heldForMs = FloatArray(size)

    fun resize(size: Int): PeakHold =
        if (values.size == size) this else PeakHold(size).also {
            it.holdMs = holdMs
            it.fallDbPerSec = fallDbPerSec
            it.enabled = enabled
        }

    fun update(current: FloatArray, dtMs: Float) {
        if (!enabled) {
            java.util.Arrays.fill(values, FLOOR_DB)
            return
        }
        val fall = fallDbPerSec * dtMs / 1000f
        for (i in values.indices) {
            val v = current[i]
            if (v >= values[i]) {
                values[i] = v
                heldForMs[i] = 0f
            } else {
                heldForMs[i] += dtMs
                if (heldForMs[i] > holdMs) values[i] -= fall
                if (values[i] < FLOOR_DB) values[i] = FLOOR_DB
            }
        }
    }

    fun reset() {
        java.util.Arrays.fill(values, FLOOR_DB)
        java.util.Arrays.fill(heldForMs, 0f)
    }

    companion object {
        /** Everything below this is "silence" as far as the display is concerned. */
        const val FLOOR_DB = -140f
    }
}
