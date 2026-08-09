package com.n3d.spectra.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.n3d.spectra.paint.Palette
import com.n3d.spectra.settings.ThemeMode

val LocalPalette = staticCompositionLocalOf { Palette.DARK }

/** The n3d motion tokens, one for one with the CSS custom properties. */
object Motion {
    val Spring = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)
    val Out = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
    /** Overshoots ~4 % past the target before settling — the wobble in "jelly". */
    val Jelly = CubicBezierEasing(0.2f, 1.72f, 0.36f, 1f)

    const val FAST = 140
    const val MID = 260
    const val SLOW = 420
    const val JELLY_MS = 340
}

/** Radii and shadow depths, matching `--r-*` and `--d-*`. */
object Neumorph {
    val RadiusSm = 10.dp
    val RadiusMd = 16.dp
    val RadiusLg = 22.dp
    val RadiusXl = 30.dp
    val RadiusPill = 999.dp

    val DepthSm = 4.dp
    val DepthMd = 7.dp
    val DepthLg = 12.dp
}

fun Int.toComposeColor() = Color(this)

@Composable
fun SpectraTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val palette = Palette.of(dark)

    // Material3's scheme is only here so that Text, ripples and menus pick up
    // sensible defaults. Every surface the user actually looks at is drawn by
    // the neumorphic modifiers, which read [LocalPalette] directly.
    val scheme = if (dark) {
        darkColorScheme(
            primary = palette.accent.toComposeColor(),
            onPrimary = Color.White,
            background = palette.bg.toComposeColor(),
            onBackground = palette.text.toComposeColor(),
            surface = palette.bg.toComposeColor(),
            onSurface = palette.text.toComposeColor(),
            surfaceVariant = palette.bgDeep.toComposeColor(),
            onSurfaceVariant = palette.textDim.toComposeColor(),
            error = palette.bad.toComposeColor(),
        )
    } else {
        lightColorScheme(
            primary = palette.accent.toComposeColor(),
            onPrimary = Color.White,
            background = palette.bg.toComposeColor(),
            onBackground = palette.text.toComposeColor(),
            surface = palette.bg.toComposeColor(),
            onSurface = palette.text.toComposeColor(),
            surfaceVariant = palette.bgDeep.toComposeColor(),
            onSurfaceVariant = palette.textDim.toComposeColor(),
            error = palette.bad.toComposeColor(),
        )
    }

    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
