package com.n3d.spectra.desktop.ui

import javax.swing.JComponent
import javax.swing.Timer

/**
 * The sites' one button motion: `.22s cubic-bezier(.34, 1.4, .64, 1)`, the
 * `--t` every site's `.btn` moves on. Hovering lifts a button 2 px and grows
 * its shadow from the small pair to the medium one, overshooting a hair before
 * it settles; pressing drops it back and moves the shadows inside. Owner's call
 * for the sites on 2026-09-18 and for Spectra on 2026-10-06, so a button moves
 * the same in a browser, on the phone and here.
 */
internal object SiteEase {
    const val MS = 220

    private const val X1 = 0.34
    private const val Y1 = 1.4
    private const val X2 = 0.64
    private const val Y2 = 1.0

    /** How far along the curve the motion is at [t], the share of [MS] gone by. */
    fun at(t: Float): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        // x(s) only ever rises for control points inside 0..1, so halving the
        // interval finds the s that lands on t: 24 halvings is 6e-8, far below
        // anything a pixel can show, and cheap enough to run on every paint.
        var lo = 0.0
        var hi = 1.0
        var s = t.toDouble()
        repeat(24) {
            s = (lo + hi) * 0.5
            if (bezier(s, X1, X2) < t) lo = s else hi = s
        }
        return bezier(s, Y1, Y2).toFloat()
    }

    private fun bezier(s: Double, p1: Double, p2: Double): Double {
        val u = 1.0 - s
        return 3.0 * u * u * s * p1 + 3.0 * u * s * s * p2 + s * s * s
    }
}

/**
 * One value easing towards a target on [SiteEase]. Swing has no transitions,
 * so this repaints [owner] from a timer while the value is moving and stops the
 * timer once it has arrived. Read [value] while painting.
 */
internal class Tween(private val owner: JComponent) {
    private var from = 0f
    private var to = 0f
    private var startNs = 0L

    private val timer = Timer(1000 / 60) { e ->
        owner.repaint()
        if (progress() >= 1f) (e.source as Timer).stop()
    }

    val value: Float get() = from + (to - from) * SiteEase.at(progress())

    /** Starts a move to [target] from wherever the value is now, mid-move included. */
    fun moveTo(target: Float) {
        if (target == to) return
        from = value
        to = target
        startNs = System.nanoTime()
        timer.restart()
    }

    private fun progress(): Float =
        ((System.nanoTime() - startNs) / (SiteEase.MS * 1e6)).toFloat().coerceIn(0f, 1f)
}
