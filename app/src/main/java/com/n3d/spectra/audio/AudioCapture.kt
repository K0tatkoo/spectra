package com.n3d.spectra.audio

/**
 * A running PCM source. Both implementations hand back interleaved floats in
 * −1..1 so the engine never has to care whether the underlying AudioRecord
 * negotiated float or 16-bit, or whether the samples came from a microphone or
 * from another app's playback.
 */
interface AudioCapture {
    val sampleRate: Int
    val channelCount: Int
    /** The concrete input the platform gave us, for display when it differs from the request. */
    val describe: String

    /** Throws [CaptureException] if the source cannot be opened. */
    fun start()

    /** Blocks until samples are available. Returns the number of floats written, or −1 on error. */
    fun read(out: FloatArray): Int

    fun stop()
    fun release()
}

class CaptureException(val kind: Kind, message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    enum class Kind {
        /** RECORD_AUDIO not granted, or revoked while running. */
        PERMISSION,
        /** Another app holds the input, or the platform refused the configuration. */
        UNAVAILABLE,
        /** The MediaProjection token is missing, expired, or was revoked by the user. */
        PROJECTION,
        UNKNOWN,
    }
}
