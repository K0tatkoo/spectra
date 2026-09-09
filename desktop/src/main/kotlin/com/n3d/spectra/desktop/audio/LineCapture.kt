package com.n3d.spectra.desktop.audio

import com.n3d.spectra.audio.AudioCapture
import com.n3d.spectra.audio.CaptureException
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.Mixer
import javax.sound.sampled.TargetDataLine

/** One thing the machine can record from. */
class InputDevice(
    val name: String,
    val description: String,
    /** Null for the built-in demo generator, which is not a mixer at all. */
    val mixer: Mixer.Info?,
    /** Looks like a "record what is playing" device rather than a physical input. */
    val loopback: Boolean,
)

/**
 * What the machine can record from, and the honest story about system audio.
 *
 * On Android, Spectra can capture another app's playback directly — that is what
 * `AudioPlaybackCapture` is for. **The JDK has no equivalent on Windows.**
 * `javax.sound.sampled` can only open the recording endpoints the driver exposes,
 * and WASAPI loopback is not one of them. So there is no code here that can
 * "capture the desktop"; there is only code that opens whatever the sound card
 * offers, which on most machines includes a loopback endpoint under one of the
 * names in [LOOPBACK_HINTS] — usually disabled until the user turns it on.
 *
 * The alternative would be shipping a small JNI DLL around `IAudioClient` in
 * loopback mode. That is the right long-term answer and it is not what this build
 * does; saying so plainly in the UI is better than a mystery silent input.
 */
object Devices {

    /**
     * Endpoint names that mean "whatever is coming out of the speakers".
     *
     * Windows localises these, and the list is short because a false positive
     * only mislabels a hint, never changes what is opened.
     */
    private val LOOPBACK_HINTS = listOf(
        "stereo mix", "stereomix", "what u hear", "what you hear", "wave out",
        "loopback", "cable output", "voicemeeter out", "vb-audio", "soundflower",
        "blackhole", "monitor of", "mixage stéréo", "stereomix", "stereo-mix",
        "summe", "mixagem estéreo", "mezcla estéreo",
    )

    /** The demo generator, listed as if it were a device so it can be chosen the same way. */
    val DEMO = InputDevice(
        name = SyntheticCapture.NAME,
        description = "A 2A03 bass line generated in software",
        mixer = null,
        loopback = false,
    )

    fun list(): List<InputDevice> {
        val out = ArrayList<InputDevice>()
        out += DEMO
        for (info in AudioSystem.getMixerInfo()) {
            val mixer = runCatching { AudioSystem.getMixer(info) }.getOrNull() ?: continue
            val supportsCapture = mixer.targetLineInfo.any { it is DataLine.Info && TargetDataLine::class.java.isAssignableFrom(it.lineClass) }
            if (!supportsCapture) continue
            val label = info.name.trim().ifEmpty { info.description }
            val lower = "${info.name} ${info.description}".lowercase()
            out += InputDevice(
                name = label,
                description = info.description.trim(),
                mixer = info,
                loopback = LOOPBACK_HINTS.any { it in lower },
            )
        }
        // Distinct by visible name: the same endpoint often appears under more
        // than one provider and the duplicates are not useful to choose between.
        return out.distinctBy { it.name }
    }

    fun find(name: String?): InputDevice? =
        if (name == null) null else list().firstOrNull { it.name == name }

    /** True when nothing on this machine looks like a system-audio endpoint. */
    fun hasLoopback(): Boolean = list().any { it.loopback }
}

/**
 * A running capture on one `TargetDataLine`.
 *
 * Format negotiation is deliberate rather than "ask for float and hope":
 * `javax.sound.sampled` on Windows serves 16- and 24-bit signed PCM from the
 * DirectSound provider and often refuses `PCM_FLOAT` outright, so the encodings
 * are tried in order and whatever opens is converted here. The engine upstream
 * only ever sees interleaved floats in −1…1, exactly as `AudioRecord` gives it on
 * the phone.
 */
class LineCapture(
    private val device: InputDevice?,
    private val requestedRate: Int,
    private val requestedStereo: Boolean,
) : AudioCapture {

    private var line: TargetDataLine? = null
    private var bytes = ByteArray(0)
    private var bytesPerSample = 2
    private var bigEndian = false

    override var sampleRate: Int = requestedRate
        private set
    override var channelCount: Int = if (requestedStereo) 2 else 1
        private set
    override var describe: String = ""
        private set

    override fun start() {
        val candidates = buildList {
            val channels = intArrayOf(if (requestedStereo) 2 else 1, if (requestedStereo) 1 else 2)
            val rates = intArrayOf(requestedRate, if (requestedRate == 48000) 44100 else 48000)
            for (ch in channels) for (rate in rates) for (bits in intArrayOf(16, 24, 32)) {
                add(AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate.toFloat(), bits, ch, bits / 8 * ch, rate.toFloat(), false))
            }
        }

        var lastError: Exception? = null
        for (format in candidates) {
            val info = DataLine.Info(TargetDataLine::class.java, format)
            val opened = try {
                val l = if (device?.mixer != null) {
                    val mixer = AudioSystem.getMixer(device.mixer)
                    if (!mixer.isLineSupported(info)) continue
                    mixer.getLine(info) as TargetDataLine
                } else {
                    if (!AudioSystem.isLineSupported(info)) continue
                    AudioSystem.getLine(info) as TargetDataLine
                }
                // A buffer of roughly a fifth of a second. Smaller and Windows
                // drops frames under load; larger and the meters lag visibly.
                l.open(format, (format.frameSize * format.sampleRate / 5).toInt())
                l.start()
                l
            } catch (e: LineUnavailableException) {
                lastError = e
                continue
            } catch (e: IllegalArgumentException) {
                lastError = e
                continue
            } catch (e: SecurityException) {
                throw CaptureException(
                    CaptureException.Kind.PERMISSION,
                    "Windows blocked microphone access for this app. " +
                        "Settings → Privacy & security → Microphone, then allow desktop apps.",
                    e,
                )
            }
            line = opened
            sampleRate = format.sampleRate.toInt()
            channelCount = format.channels
            bytesPerSample = format.sampleSizeInBits / 8
            bigEndian = format.isBigEndian
            describe = buildString {
                append(device?.name ?: "System default input")
                append(" · ")
                append(sampleRate)
                append(" Hz ")
                append(if (channelCount == 2) "stereo" else "mono")
                append(" · ")
                append(format.sampleSizeInBits)
                append("-bit")
            }
            return
        }

        throw CaptureException(
            CaptureException.Kind.UNAVAILABLE,
            buildString {
                append(if (device != null) "\"${device.name}\" would not open" else "No usable audio input was found")
                append(". Another app may hold it exclusively, or it may not support ")
                append("$requestedRate Hz ${if (requestedStereo) "stereo" else "mono"}.")
                lastError?.message?.let { append(" (").append(it).append(")") }
            },
            lastError,
        )
    }

    override fun read(out: FloatArray): Int {
        val l = line ?: return -1
        val frameBytes = bytesPerSample * channelCount
        val wanted = (out.size / channelCount) * frameBytes
        if (bytes.size < wanted) bytes = ByteArray(wanted)
        // Read whole frames only. A partial frame would swap the channels for
        // the rest of the session.
        val available = (l.available() / frameBytes) * frameBytes
        val toRead = if (available >= frameBytes) minOf(available, wanted) else frameBytes
        val n = try {
            l.read(bytes, 0, toRead)
        } catch (t: Throwable) {
            return -1
        }
        if (n <= 0) return 0
        return decode(bytes, n, out)
    }

    private fun decode(src: ByteArray, byteCount: Int, dst: FloatArray): Int {
        var w = 0
        var i = 0
        when (bytesPerSample) {
            2 -> while (i + 1 < byteCount && w < dst.size) {
                val v = if (bigEndian) {
                    (src[i].toInt() shl 8) or (src[i + 1].toInt() and 0xFF)
                } else {
                    (src[i + 1].toInt() shl 8) or (src[i].toInt() and 0xFF)
                }
                dst[w++] = v / 32768f
                i += 2
            }
            3 -> while (i + 2 < byteCount && w < dst.size) {
                val v = if (bigEndian) {
                    (src[i].toInt() shl 16) or ((src[i + 1].toInt() and 0xFF) shl 8) or (src[i + 2].toInt() and 0xFF)
                } else {
                    (src[i + 2].toInt() shl 16) or ((src[i + 1].toInt() and 0xFF) shl 8) or (src[i].toInt() and 0xFF)
                }
                dst[w++] = v / 8388608f
                i += 3
            }
            4 -> while (i + 3 < byteCount && w < dst.size) {
                val v = if (bigEndian) {
                    (src[i].toInt() shl 24) or ((src[i + 1].toInt() and 0xFF) shl 16) or
                        ((src[i + 2].toInt() and 0xFF) shl 8) or (src[i + 3].toInt() and 0xFF)
                } else {
                    (src[i + 3].toInt() shl 24) or ((src[i + 2].toInt() and 0xFF) shl 16) or
                        ((src[i + 1].toInt() and 0xFF) shl 8) or (src[i].toInt() and 0xFF)
                }
                dst[w++] = (v / 2147483648.0).toFloat()
                i += 4
            }
            else -> return 0
        }
        return w
    }

    override fun stop() {
        runCatching { line?.stop() }
        runCatching { line?.flush() }
    }

    override fun release() {
        runCatching { line?.close() }
        line = null
    }
}
