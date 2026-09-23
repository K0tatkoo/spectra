package com.n3d.spectra

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.n3d.spectra.audio.AudioEngine
import com.n3d.spectra.settings.SettingsStore
import com.n3d.spectra.stems.AndroidStemHooks
import com.n3d.spectra.stems.StemModel

class SpectraApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Warm the settings so the service does not have to block on first read.
        SettingsStore.get(this)
        createChannel()
        // The engine is a plain object with no Context; these are the two
        // Android things the stem model needs from it.
        AudioEngine.stemModel = { StemModel.file(this) }
        AudioEngine.stemHooks = { AndroidStemHooks(this) }
    }

    /**
     * IMPORTANCE_LOW, not MIN: MIN collapses the notification out of the lock
     * screen entirely, which is the one place this notification exists for.
     * VISIBILITY_PUBLIC is what allows its content — the graph — to be drawn
     * while the device is locked rather than replaced by "contents hidden".
     */
    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ANALYZER,
            getString(R.string.notif_channel_analyzer),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_analyzer_desc)
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
            setSound(null, null)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ANALYZER = "spectra.analyzer"
    }
}
