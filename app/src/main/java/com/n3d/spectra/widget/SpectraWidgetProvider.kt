package com.n3d.spectra.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import com.n3d.spectra.audio.AudioEngine
import com.n3d.spectra.settings.SettingsStore

/**
 * The widget itself does almost nothing: while the analyser runs, the service
 * pushes frames; the callbacks here only cover the moments the service is not
 * in a position to, such as the instant the widget is first dropped onto the
 * home screen or resized.
 */
class SpectraWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        renderOnce(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        newOptions: Bundle,
    ) {
        renderOnce(context)
    }

    private fun renderOnce(context: Context) {
        val settings = SettingsStore.get(context).state.value
        WidgetRenderer.push(context, AudioEngine.frame.value, settings, isDark(context))
    }

    companion object {
        fun isDark(context: Context): Boolean =
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
    }
}
