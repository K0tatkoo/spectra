package com.n3d.spectra.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.util.Log
import androidx.annotation.RequiresPermission

/**
 * Captures what other apps are playing, through MediaProjection.
 *
 * The important limitation, and the reason the UI is explicit about it: an app
 * decides for itself whether it can be captured. Anything that sets
 * `allowAudioPlaybackCapture="false"` — Spotify, YouTube, and every DRM-backed
 * player — is silently excluded from the mix handed to us. There is no error and
 * no callback; the stream simply contains digital silence. The engine's silence
 * watchdog is the only way to tell that apart from "nothing is playing", and it
 * is why the app says so in words rather than showing an empty graph.
 *
 * Only MEDIA, GAME and UNKNOWN usages can be captured at all — notification and
 * call audio are excluded by the platform regardless of app policy.
 */
class PlaybackCapture(
    private val projection: MediaProjection,
    requestedRate: Int,
    requestedStereo: Boolean,
) : RecordCapture(requestedRate, requestedStereo) {

    override val describe: String
        get() = "Device audio · ${sampleRate / 1000f} kHz · ${if (channelCount == 2) "stereo" else "mono"}"

    /* RECORD_AUDIO is what gates the capture API here, not the microphone: the
       permission is requested and confirmed before the service that owns this
       ever starts, and a revocation between then and now arrives as the
       SecurityException the catch below already turns into "unavailable for
       this format". Annotated rather than re-checked so lint can see that. */
    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    override fun build(rate: Int, channelMask: Int, encoding: Int, bufferBytes: Int): AudioRecord? {
        val config = try {
            AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
        } catch (t: Throwable) {
            throw CaptureException(
                CaptureException.Kind.PROJECTION,
                "The screen-capture permission is no longer valid. Grant it again to read device audio.",
                t,
            )
        }

        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(rate)
            .setChannelMask(channelMask)
            .build()

        return try {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferBytes)
                .setAudioPlaybackCaptureConfig(config)
                .build()
        } catch (t: Throwable) {
            Log.d(TAG, "playback capture unavailable for this format: ${t.message}")
            null
        }
    }
}
