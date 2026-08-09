package com.n3d.spectra

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.n3d.spectra.service.AnalyzerService
import com.n3d.spectra.settings.SourceKind
import com.n3d.spectra.ui.MainViewModel
import com.n3d.spectra.ui.screens.HomeScreen
import com.n3d.spectra.ui.screens.SettingsScreen
import com.n3d.spectra.ui.theme.SpectraTheme

/**
 * The Activity exists for three things the service cannot do: ask for
 * RECORD_AUDIO, ask for the MediaProjection consent token, and draw the full UI.
 * Everything else lives in [AnalyzerService], which is why closing the app does
 * not stop the analyser.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private lateinit var micPermission: ActivityResultLauncher<String>
    private lateinit var notificationPermission: ActivityResultLauncher<String>
    private lateinit var projectionRequest: ActivityResultLauncher<Intent>

    /** Set when a start was interrupted by a permission prompt. */
    private var pendingSource: SourceKind? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val source = pendingSource
            pendingSource = null
            if (granted && source != null) {
                beginCapture(source)
            } else if (!granted) {
                toast("Spectra cannot analyse anything without the microphone permission.")
            }
        }

        notificationPermission =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* advisory only */ }

        projectionRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                ContextCompat.startForegroundService(
                    this,
                    AnalyzerService.intent(this, AnalyzerService.ACTION_START_PLAYBACK).apply {
                        putExtra(AnalyzerService.EXTRA_RESULT_CODE, result.resultCode)
                        putExtra(AnalyzerService.EXTRA_RESULT_DATA, data)
                    },
                )
            } else {
                toast("Device audio needs the screen-capture consent. Nothing is recorded to disk.")
            }
        }

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            SpectraTheme(settings.theme) {
                var showSettings by remember { mutableStateOf(false) }
                if (showSettings) {
                    SettingsScreen(
                        viewModel = viewModel,
                        onBack = { showSettings = false },
                        onRequestOverlayPermission = ::requestOverlayPermission,
                        canDrawOverlays = ::canDrawOverlays,
                    )
                } else {
                    HomeScreen(
                        viewModel = viewModel,
                        onOpenSettings = { showSettings = true },
                        onStart = ::start,
                        onStop = ::stop,
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // Without it the foreground service still runs, but its notification
            // — the whole lock-screen feature — is invisible.
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun start() {
        val source = viewModel.settings.value.source
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // Playback capture needs RECORD_AUDIO too — the permission gates the
            // capture API, not the microphone hardware.
            pendingSource = source
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        beginCapture(source)
    }

    private fun beginCapture(source: SourceKind) {
        when (source) {
            SourceKind.MICROPHONE -> ContextCompat.startForegroundService(
                this,
                AnalyzerService.intent(this, AnalyzerService.ACTION_START_MIC),
            )
            SourceKind.DEVICE_AUDIO -> {
                val manager = getSystemService(MediaProjectionManager::class.java)
                projectionRequest.launch(manager.createScreenCaptureIntent())
            }
        }
    }

    /**
     * Stopping goes through the service rather than touching [AudioEngine]
     * directly: the service also owns the overlay, the widget pushes and the
     * MediaProjection token, and half-stopping would leave those running.
     */
    private fun stop() {
        ContextCompat.startForegroundService(this, AnalyzerService.intent(this, AnalyzerService.ACTION_STOP))
    }

    private fun canDrawOverlays(): Boolean = AndroidSettings.canDrawOverlays(this)

    private fun requestOverlayPermission() {
        startActivity(
            Intent(
                AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
