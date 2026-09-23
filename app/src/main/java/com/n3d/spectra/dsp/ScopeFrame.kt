package com.n3d.spectra.dsp

/** Where the stem model is, in the words the painter needs. */
enum class StemsState { LOADING, RUNNING, FAILED }

/**
 * The separator's health as of one frame.
 *
 * [load] is compute time over audio time: at 0.6 the phone separates a second
 * of music in 0.6 s and has room to spare; above 1 it cannot keep up and
 * [skips] counts the times it gave up and jumped to the present.
 */
class StemsInfo(
    val state: StemsState,
    val message: String?,
    val load: Float,
    val skips: Int,
    val msPerHop: Float,
)

/** One stem lane as published: its label and its held-still trace. */
class StemLane(val name: String, val trace: ScopeTrace)

/**
 * The held-still scopes for one published frame. Only the pages that are
 * actually on screen are computed, so everything here may be absent.
 */
class ScopeFrame(
    /** Top to bottom as drawn. Empty unless the Stems page is showing. */
    val stems: List<StemLane>,
    val stemsInfo: StemsInfo?,
    /** The Waveform page's "hold still" trace, on the mix. */
    val hold: ScopeTrace?,
)
