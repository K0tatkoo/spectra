package com.n3d.spectra.paint

import android.graphics.Color
import com.n3d.spectra.settings.ColorMap

/**
 * The Nebula 3D design tokens, as plain ints.
 *
 * These are the same values as `public/css/style.css` on n3d-store.com, kept
 * here rather than read from resources because the same numbers have to be
 * available to Compose, to a software Canvas rendering a notification bitmap,
 * and to an overlay View — three places with three different ways of resolving
 * a theme. One list of numbers, three consumers.
 *
 * The rule the whole look depends on: a control is the same colour as the
 * surface behind it, and only two shadows separate them — a dark one away from
 * the light and a light one facing it, with the light fixed at the top-left.
 */
class Palette(
    val isDark: Boolean,
    val bg: Int,
    val bgDeep: Int,
    val dark: Int,
    val light: Int,
    val line: Int,
    val text: Int,
    val textDim: Int,
    val textFaint: Int,
    val accent: Int,
    val accent2: Int,
    val gradA: Int,
    val gradB: Int,
    val good: Int,
    val warn: Int,
    val bad: Int,
) {
    /** Top-left end of the raised-surface gradient: 88 % bg, 12 % light. */
    val surfaceHigh: Int = mix(bg, light, 0.12f)
    /** Bottom-right end. Without this a large card reads as a flat slab. */
    val surfaceLow: Int = mix(bg, dark, 0.12f)

    fun colorMap(map: ColorMap): IntArray = when (map) {
        ColorMap.NEBULA -> nebulaLut
        ColorMap.MAGMA -> magmaLut
        ColorMap.VIRIDIS -> viridisLut
        ColorMap.ICE -> iceLut
        ColorMap.MONO -> monoLut
    }

    private val nebulaLut by lazy {
        // Deliberately starts at the well colour, so quiet passages read as an
        // empty well rather than as a black rectangle pasted into the UI.
        ramp(bgDeep, 0x2A1B4D, 0x6A45FF, 0x9B7CFF, 0x34E3FF, 0xEAF6FF)
    }
    private val magmaLut by lazy { ramp(bgDeep, 0x1C1044, 0x711F81, 0xB63679, 0xF1605D, 0xFEC287, 0xFCFDBF) }
    private val viridisLut by lazy { ramp(bgDeep, 0x440154, 0x3B528B, 0x21918C, 0x5EC962, 0xFDE725) }
    private val iceLut by lazy { ramp(bgDeep, 0x06172B, 0x0B4F8A, 0x1BA9D8, 0x8BE9FD, 0xFFFFFF) }
    private val monoLut by lazy { ramp(bgDeep, if (isDark) 0xFFFFFF else 0x000000) }

    companion object {
        val DARK = Palette(
            isDark = true,
            bg = 0xFF22252C.toInt(),
            bgDeep = 0xFF1B1E24.toInt(),
            dark = 0xFF171920.toInt(),
            light = 0xFF2D313A.toInt(),
            line = 0xFF33373F.toInt(),
            text = 0xFFEEF1F7.toInt(),
            textDim = 0xFFA3ABBD.toInt(),
            textFaint = 0xFF737B8C.toInt(),
            accent = 0xFF9B7CFF.toInt(),
            accent2 = 0xFF34E3FF.toInt(),
            gradA = 0xFF6A45FF.toInt(),
            gradB = 0xFF9A6BFF.toInt(),
            good = 0xFF4ADE80.toInt(),
            warn = 0xFFF5C451.toInt(),
            bad = 0xFFFF6B6B.toInt(),
        )

        val LIGHT = Palette(
            isDark = false,
            bg = 0xFFE7EAF1.toInt(),
            bgDeep = 0xFFDDE1EA.toInt(),
            dark = 0xFFC1C6D1.toInt(),
            light = 0xFFFFFFFF.toInt(),
            line = 0xFFD2D7E1.toInt(),
            text = 0xFF1C2029.toInt(),
            textDim = 0xFF545C6C.toInt(),
            textFaint = 0xFF7B8394.toInt(),
            accent = 0xFF5F43E8.toInt(),
            accent2 = 0xFF0A9FBD.toInt(),
            gradA = 0xFF5734DB.toInt(),
            gradB = 0xFF7D54EF.toInt(),
            good = 0xFF17914D.toInt(),
            warn = 0xFFA06F04.toInt(),
            bad = 0xFFCF3535.toInt(),
        )

        fun of(dark: Boolean) = if (dark) DARK else LIGHT

        fun mix(a: Int, b: Int, t: Float): Int {
            val inv = 1f - t
            return Color.argb(
                (Color.alpha(a) * inv + Color.alpha(b) * t).toInt(),
                (Color.red(a) * inv + Color.red(b) * t).toInt(),
                (Color.green(a) * inv + Color.green(b) * t).toInt(),
                (Color.blue(a) * inv + Color.blue(b) * t).toInt(),
            )
        }

        fun withAlpha(color: Int, alpha: Float): Int =
            Color.argb((alpha.coerceIn(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

        /**
         * Builds a 256-entry lookup by walking evenly through the given anchor
         * colours. A LUT rather than a per-pixel interpolation because the
         * spectrogram blits 256 rows every frame on up to four surfaces.
         */
        private fun ramp(vararg anchorsRgb: Int): IntArray {
            val anchors = anchorsRgb.map { if (it ushr 24 == 0) it or 0xFF000000.toInt() else it }
            val out = IntArray(256)
            val segments = anchors.size - 1
            for (i in 0 until 256) {
                val pos = i / 255f * segments
                val seg = pos.toInt().coerceAtMost(segments - 1)
                out[i] = mix(anchors[seg], anchors[seg + 1], pos - seg)
            }
            return out
        }
    }
}
