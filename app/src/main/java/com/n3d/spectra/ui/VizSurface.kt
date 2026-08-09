package com.n3d.spectra.ui

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import com.n3d.spectra.dsp.AnalysisFrame
import com.n3d.spectra.paint.VizPainter
import com.n3d.spectra.settings.Settings
import com.n3d.spectra.settings.VizPage
import com.n3d.spectra.ui.theme.LocalPalette

/**
 * The in-app view of a visualisation.
 *
 * It is a thin shell: the drawing is the same [VizPainter] the notification, the
 * overlay and the widget use, handed Compose's native canvas. Reimplementing the
 * graphs in Compose's own draw API would have been more idiomatic and would have
 * guaranteed the four surfaces drifted apart.
 */
@Composable
fun VizSurface(
    page: VizPage,
    frame: AnalysisFrame?,
    settings: Settings,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val palette = LocalPalette.current
    val density = LocalDensity.current.density
    val painter = remember { VizPainter(palette) }

    DisposableEffect(painter) {
        onDispose { painter.release() }
    }

    painter.palette = palette
    painter.density = density

    Canvas(modifier) {
        drawIntoCanvas { canvas ->
            painter.draw(
                canvas.nativeCanvas,
                RectF(0f, 0f, size.width, size.height),
                page,
                frame,
                settings,
                compact,
            )
        }
    }
}
