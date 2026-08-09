package com.n3d.spectra.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.util.Log

/**
 * Shared plumbing for both sources: negotiate a format, then hand back floats.
 *
 * Float capture (ENCODING_PCM_FLOAT) is tried first and 16-bit is the fallback.
 * That is not cosmetic — a 16-bit path puts the quantisation floor at −96 dBFS,
 * and an analyser with a −100 dB display floor would then spend its bottom
 * division showing its own dither rather than the signal.
 */
abstract class RecordCapture(
    private val requestedRate: Int,
    private val requestedStereo: Boolean,
) : AudioCapture {

    private var record: AudioRecord? = null
    private var shortScratch: ShortArray? = null
    private var isFloat = true

    final override var sampleRate: Int = requestedRate
        private set
    final override var channelCount: Int = if (requestedStereo) 2 else 1
        private set

    /**
     * Builds a configured-but-not-started AudioRecord, or returns null if this
     * particular combination is not available. Implementations must not throw
     * for an unsupported format — only for a genuine failure such as a missing
     * permission.
     */
    protected abstract fun build(
        rate: Int,
        channelMask: Int,
        encoding: Int,
        bufferBytes: Int,
    ): AudioRecord?

    /** Called once, after the record is open and running. */
    protected open fun onOpened(record: AudioRecord) {}

    override fun start() {
        val encodings = intArrayOf(AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT)
        val masks = if (requestedStereo) {
            intArrayOf(AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_IN_MONO)
        } else {
            intArrayOf(AudioFormat.CHANNEL_IN_MONO)
        }

        var lastError: Throwable? = null
        for (encoding in encodings) {
            for (mask in masks) {
                val min = AudioRecord.getMinBufferSize(requestedRate, mask, encoding)
                if (min <= 0) continue
                // Four periods of headroom, and never less than ~120 ms: the
                // analysis thread also does the FFT, and a short buffer turns a
                // scheduling hiccup into an overrun and a visible glitch.
                val frameBytes = (if (mask == AudioFormat.CHANNEL_IN_STEREO) 2 else 1) *
                    (if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2)
                val bufferBytes = maxOf(min * 4, requestedRate * frameBytes / 8)
                try {
                    val r = build(requestedRate, mask, encoding, bufferBytes) ?: continue
                    if (r.state != AudioRecord.STATE_INITIALIZED) {
                        r.release()
                        continue
                    }
                    r.startRecording()
                    if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        r.release()
                        continue
                    }
                    record = r
                    isFloat = encoding == AudioFormat.ENCODING_PCM_FLOAT
                    sampleRate = r.sampleRate
                    channelCount = if (mask == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
                    if (!isFloat) shortScratch = ShortArray(bufferBytes / 2)
                    Log.i(TAG, "capture open: ${sampleRate}Hz ${channelCount}ch ${if (isFloat) "float" else "16-bit"}")
                    onOpened(r)
                    return
                } catch (se: SecurityException) {
                    throw CaptureException(CaptureException.Kind.PERMISSION, "Microphone permission is not granted.", se)
                } catch (t: Throwable) {
                    lastError = t
                }
            }
        }
        throw CaptureException(
            CaptureException.Kind.UNAVAILABLE,
            "Could not open the audio input at ${requestedRate} Hz. " +
                "Another app may be holding the microphone.",
            lastError,
        )
    }

    override fun read(out: FloatArray): Int {
        val r = record ?: return -1
        return if (isFloat) {
            val n = r.read(out, 0, out.size, AudioRecord.READ_BLOCKING)
            if (n < 0) mapReadError(n) else n
        } else {
            val scratch = shortScratch ?: return -1
            val want = minOf(out.size, scratch.size)
            val n = r.read(scratch, 0, want)
            if (n < 0) return mapReadError(n)
            val inv = 1f / 32768f
            for (i in 0 until n) out[i] = scratch[i] * inv
            n
        }
    }

    private fun mapReadError(code: Int): Int {
        Log.w(TAG, "AudioRecord.read returned $code")
        return -1
    }

    override fun stop() {
        try {
            record?.takeIf { it.recordingState == AudioRecord.RECORDSTATE_RECORDING }?.stop()
        } catch (t: IllegalStateException) {
            Log.w(TAG, "stop failed", t)
        }
    }

    @Synchronized
    override fun release() {
        stop()
        record?.release()
        record = null
        shortScratch = null
    }

    protected val audioSessionId: Int get() = record?.audioSessionId ?: 0

    companion object {
        const val TAG = "SpectraCapture"
    }
}
