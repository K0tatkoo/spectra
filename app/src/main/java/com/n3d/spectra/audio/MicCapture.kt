package com.n3d.spectra.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.n3d.spectra.settings.MicPreset
import com.n3d.spectra.settings.Settings

/**
 * Microphone capture with the platform's "helpful" processing explicitly turned
 * off.
 *
 * Two things fight a measurement microphone on a phone. The first is the audio
 * source: MIC runs through the voice pipeline, which on Samsung hardware means
 * beamforming and gain riding. UNPROCESSED is the only source specified to be
 * raw, so it is preferred and probed for rather than assumed — it is optional in
 * the platform and a device that lacks it reports so through AudioManager.
 *
 * The second is the effect chain, which the framework may attach on its own.
 * Creating AGC / NS / AEC and explicitly disabling them is the documented way to
 * be sure they are off; leaving them alone is not the same thing as them being
 * absent.
 */
class MicCapture(
    private val context: Context,
    private val settings: Settings,
) : RecordCapture(settings.sampleRate, settings.stereo) {

    private var agc: AutomaticGainControl? = null
    private var ns: NoiseSuppressor? = null
    private var aec: AcousticEchoCanceler? = null
    private var chosenSource: Int = MediaRecorder.AudioSource.UNPROCESSED

    override val describe: String
        get() = "${sourceName(chosenSource)} · ${sampleRate / 1000f} kHz · ${if (channelCount == 2) "stereo" else "mono"}"

    override fun build(rate: Int, channelMask: Int, encoding: Int, bufferBytes: Int): AudioRecord? {
        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(rate)
            .setChannelMask(channelMask)
            .build()

        for (source in candidateSources()) {
            try {
                val record = AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferBytes)
                    .build()
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    chosenSource = source
                    return record
                }
                record.release()
            } catch (se: SecurityException) {
                throw se
            } catch (t: Throwable) {
                Log.d(TAG, "source ${sourceName(source)} unavailable: ${t.message}")
            }
        }
        return null
    }

    /** The requested source first, then progressively more processed fallbacks. */
    private fun candidateSources(): List<Int> {
        val preferred = when (settings.micPreset) {
            MicPreset.UNPROCESSED -> MediaRecorder.AudioSource.UNPROCESSED
            MicPreset.VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            MicPreset.MIC -> MediaRecorder.AudioSource.MIC
            MicPreset.CAMCORDER -> MediaRecorder.AudioSource.CAMCORDER
        }
        val ordered = mutableListOf<Int>()
        if (preferred != MediaRecorder.AudioSource.UNPROCESSED || unprocessedSupported()) {
            ordered += preferred
        }
        ordered += listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.MIC,
        )
        return ordered.distinct()
    }

    private fun unprocessedSupported(): Boolean = try {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
    } catch (t: Throwable) {
        false
    }

    override fun onOpened(record: AudioRecord) {
        val session = record.audioSessionId
        if (AutomaticGainControl.isAvailable()) {
            agc = runCatching { AutomaticGainControl.create(session) }.getOrNull()
            agc?.enabled = settings.micAgc
        }
        if (NoiseSuppressor.isAvailable()) {
            ns = runCatching { NoiseSuppressor.create(session) }.getOrNull()
            ns?.enabled = settings.micNoiseSuppression
        }
        if (AcousticEchoCanceler.isAvailable()) {
            // Never wanted here: AEC subtracts the device's own output, which is
            // precisely the thing being measured when the phone is in front of a
            // speaker.
            aec = runCatching { AcousticEchoCanceler.create(session) }.getOrNull()
            aec?.enabled = false
        }
        Log.i(
            TAG,
            "effects — agc=${agc?.enabled} ns=${ns?.enabled} aec=${aec?.enabled} source=${sourceName(chosenSource)}",
        )
    }

    override fun release() {
        agc?.release(); agc = null
        ns?.release(); ns = null
        aec?.release(); aec = null
        super.release()
    }

    private fun sourceName(source: Int) = when (source) {
        MediaRecorder.AudioSource.UNPROCESSED -> "Unprocessed"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "Voice recognition"
        MediaRecorder.AudioSource.CAMCORDER -> "Camcorder"
        MediaRecorder.AudioSource.MIC -> "Default mic"
        else -> "Source $source"
    }
}
