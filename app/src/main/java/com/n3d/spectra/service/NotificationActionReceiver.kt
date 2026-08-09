package com.n3d.spectra.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The notification's buttons post here rather than straight to the service.
 *
 * A PendingIntent aimed at a service would be subject to the background
 * foreground-service start rules the moment the service happened to be down;
 * a broadcast always lands, and this then forwards to a service that — by
 * construction, since the notification is its own — is already running.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val serviceAction = when (intent.action) {
            ACTION_TOGGLE -> AnalyzerService.ACTION_TOGGLE_PAUSE
            ACTION_CYCLE -> AnalyzerService.ACTION_CYCLE_PAGE
            ACTION_STOP -> AnalyzerService.ACTION_STOP
            else -> return
        }
        runCatching {
            context.startService(AnalyzerService.intent(context, serviceAction))
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.n3d.spectra.notif.TOGGLE"
        const val ACTION_CYCLE = "com.n3d.spectra.notif.CYCLE"
        const val ACTION_STOP = "com.n3d.spectra.notif.STOP"
    }
}
