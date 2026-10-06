package com.n3d.spectra.desktop.audio

import com.n3d.spectra.audio.AudioCapture
import com.n3d.spectra.audio.CaptureException
import com.n3d.spectra.desktop.audio.wasapi.ComPtr
import com.n3d.spectra.desktop.audio.wasapi.HResultException
import com.n3d.spectra.desktop.audio.wasapi.Hr
import com.n3d.spectra.desktop.audio.wasapi.LoopbackStream
import com.n3d.spectra.desktop.audio.wasapi.PcmConverter
import com.n3d.spectra.desktop.audio.wasapi.SystemAudioSupport
import com.n3d.spectra.desktop.audio.wasapi.Wasapi
import com.sun.jna.Pointer

/**
 * What Windows is playing, captured with WASAPI loopback — the desktop twin of
 * the phone's "Device audio".
 *
 * Three things a plain capture loop would get wrong:
 *
 *  * **Silence is not delivered.** When nothing is playing, Windows stops
 *    rendering and the loopback stream simply produces no packets. Left alone,
 *    every meter would freeze at its last value. Instead, once no packet has
 *    arrived for [SILENCE_AFTER_NS], zeros are generated at the stream's own
 *    rate, so the graphs fall away as they do on a quiet microphone.
 *  * **The default output moves.** Plugging in headphones or picking another
 *    output in the volume flyout changes where audio goes. "System audio ·
 *    default output" checks once a second and follows it; a named output stays
 *    put, and says so plainly when it is unplugged.
 *  * **Devices get invalidated.** Unplugging, or changing an output's format in
 *    Sound settings, kills the stream with AUDCLNT_E_DEVICE_INVALIDATED. That is
 *    answered by reopening, not by failing.
 *
 * All COM work happens on the thread that called [start] (the engine's DSP
 * thread), which joins the multithreaded apartment for the stream's lifetime.
 */
class LoopbackCapture(
    private val device: InputDevice,
    private val requestedRate: Int,
    requestedStereo: Boolean,
) : AudioCapture {

    private val followDefault = device.outputId == Devices.DEFAULT_OUTPUT_ID

    override var sampleRate: Int = requestedRate
        private set
    override val channelCount: Int = if (requestedStereo) 2 else 1
    override var describe: String = ""
        private set

    /** The output currently being listened to, for messages. */
    @Volatile var outputName: String = device.name.removePrefix(Devices.SYSTEM_AUDIO_PREFIX)
        private set

    private val lock = Any()
    private var enumerator: ComPtr? = null
    private var stream: LoopbackStream? = null
    private var converter: PcmConverter? = null
    private var currentId: String? = null
    private var comThread: Thread? = null
    private var comJoined = false
    @Volatile private var stopped = false

    private var fifo = FloatArray(0)
    private var fifoHead = 0
    private var fifoCount = 0
    private var packet = FloatArray(0)
    private var lastDataNs = 0L
    private var lastDefaultCheckNs = 0L

    override fun start() {
        if (device.outputId.isNullOrEmpty()) throw notConnected(outputName)
        if (!SystemAudioSupport.available) {
            throw CaptureException(
                CaptureException.Kind.UNAVAILABLE,
                "System audio is captured through Windows' own audio service (WASAPI), which is not available here.",
            )
        }
        synchronized(lock) {
            val hr = Wasapi.ole32.CoInitializeEx(null, Wasapi.COINIT_MULTITHREADED)
            comJoined = hr == Hr.S_OK || hr == Hr.S_FALSE
            comThread = Thread.currentThread()
            try {
                enumerator = Wasapi.enumerator()
                open(initial = true)
            } catch (e: CaptureException) {
                release()
                throw e
            } catch (e: Exception) {
                release()
                throw CaptureException(CaptureException.Kind.UNKNOWN, "Could not start capturing system audio: ${e.message}", e)
            }
        }
        // Half a second of headroom: the engine reads every few milliseconds, so
        // this only fills up if the DSP thread stalls, and then the oldest goes.
        fifo = FloatArray(sampleRate / 2 * channelCount)
        lastDataNs = System.nanoTime()
        lastDefaultCheckNs = lastDataNs
    }

    override fun read(out: FloatArray): Int {
        while (!stopped) {
            if (fifoCount > 0) return take(out)
            pump()
            if (fifoCount > 0) continue
            padSilence()
            if (fifoCount > 0) continue
            try {
                // Packets arrive every ~10 ms (the engine period); polling at
                // half that keeps latency low without spinning a core.
                Thread.sleep(POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return 0
            }
        }
        return 0
    }

    override fun stop() {
        stopped = true
    }

    override fun release() {
        synchronized(lock) {
            closeAll()
            if (comJoined && Thread.currentThread() === comThread) {
                Wasapi.ole32.CoUninitialize()
                comJoined = false
            }
        }
    }

    // ------------------------------------------------------------------------

    private fun closeAll() {
        runCatching { stream?.close() }
        stream = null
        runCatching { enumerator?.close() }
        enumerator = null
    }

    /** Opens the stream on the device this source points at. Caller holds [lock]. */
    private fun open(initial: Boolean) {
        val en = enumerator ?: return
        val id = if (followDefault) {
            Wasapi.defaultOutputId(en) ?: throw CaptureException(
                CaptureException.Kind.UNAVAILABLE,
                "Windows has no playback device switched on, so there is nothing playing to capture.",
            )
        } else {
            device.outputId!!
        }
        val dev = Wasapi.device(en, id) ?: throw notConnected(outputName)
        dev.use {
            if (!Wasapi.isActive(dev)) throw notConnected(outputName)
            val name = Wasapi.nameOf(dev) ?: outputName
            val next = try {
                LoopbackStream.open(dev, requestedRate)
            } catch (e: HResultException) {
                throw explain(e, name)
            }
            // The engine sized every filter for the first stream's rate. Only the
            // fallback path can change it, and only on an unusual device switch.
            if (!initial && next.format.sampleRate != sampleRate && !next.converted) {
                next.close()
                throw CaptureException(
                    CaptureException.Kind.UNAVAILABLE,
                    "\"$name\" runs at ${next.format.sampleRate} Hz, not $sampleRate Hz. Press Start to pick it up.",
                )
            }
            try {
                next.start()
            } catch (e: HResultException) {
                next.close()
                throw explain(e, name)
            }
            stream = next
            converter = PcmConverter(next.format, channelCount)
            currentId = id
            outputName = name
            if (initial) sampleRate = if (next.converted) requestedRate else next.format.sampleRate
            describe = buildString {
                append(if (followDefault) "System audio (default) · " else "System audio · ")
                append(name)
                append(" · ").append(sampleRate).append(" Hz ")
                append(if (channelCount == 2) "stereo" else "mono")
                if (!next.converted) append(" · ").append(next.format.label)
            }
        }
    }

    /** Moves every waiting packet into the FIFO, reopening the stream if Windows asks for it. */
    private fun pump() {
        synchronized(lock) {
            val s = stream ?: return
            val now = System.nanoTime()
            if (followDefault && now - lastDefaultCheckNs > DEFAULT_CHECK_NS) {
                lastDefaultCheckNs = now
                val id = enumerator?.let { runCatching { Wasapi.defaultOutputId(it) }.getOrNull() }
                if (id != null && id != currentId) {
                    reopen()
                    return
                }
            }
            try {
                if (s.drain(::push) > 0) lastDataNs = now
            } catch (e: HResultException) {
                if (e.hr == Hr.AUDCLNT_E_DEVICE_INVALIDATED) {
                    reopen()
                } else {
                    throw CaptureException(
                        CaptureException.Kind.UNAVAILABLE,
                        "Windows stopped delivering what \"$outputName\" is playing (${e.message}).",
                        e,
                    )
                }
            }
        }
    }

    private fun reopen() {
        runCatching { stream?.close() }
        stream = null
        open(initial = false)
    }

    private fun push(data: Pointer, frames: Int, silent: Boolean) {
        val conv = converter ?: return
        val floats = frames * channelCount
        if (packet.size < floats) packet = FloatArray(floats)
        if (silent) {
            packet.fill(0f, 0, floats)
        } else {
            val bytes = conv.bytesFor(frames)
            val raw = conv.scratch(bytes)
            data.read(0, raw, 0, bytes)
            conv.convert(raw, frames, packet, 0)
        }
        write(packet, floats)
    }

    /**
     * Feeds zeros for the time nothing has arrived, at the stream's rate.
     *
     * The gap is measured from the last real packet, so when sound resumes the
     * two overlap by at most one poll — a few milliseconds that no meter shows.
     * A gap longer than [MAX_GAP_NS] (the machine slept, the thread stalled) is
     * not replayed in full; the graphs only need to know it went quiet.
     */
    private fun padSilence() {
        val now = System.nanoTime()
        var idle = now - lastDataNs
        if (idle < SILENCE_AFTER_NS) return
        if (idle > MAX_GAP_NS) {
            lastDataNs = now - SILENCE_AFTER_NS
            idle = SILENCE_AFTER_NS
        }
        val frames = (idle * sampleRate / 1_000_000_000L).toInt()
        if (frames <= 0) return
        val floats = frames * channelCount
        if (packet.size < floats) packet = FloatArray(floats)
        packet.fill(0f, 0, floats)
        write(packet, floats)
        lastDataNs += frames * 1_000_000_000L / sampleRate
    }

    private fun write(src: FloatArray, count: Int) {
        val cap = fifo.size
        if (cap == 0) return
        var n = count
        var from = 0
        if (n > cap) {
            from = n - cap
            n = cap
        }
        // Overflow drops the oldest whole frames, never half of one.
        val overflow = fifoCount + n - cap
        if (overflow > 0) {
            fifoHead = (fifoHead + overflow) % cap
            fifoCount -= overflow
        }
        var tail = (fifoHead + fifoCount) % cap
        for (i in 0 until n) {
            fifo[tail] = src[from + i]
            tail++
            if (tail == cap) tail = 0
        }
        fifoCount += n
    }

    private fun take(out: FloatArray): Int {
        val cap = fifo.size
        val n = minOf(fifoCount, out.size - out.size % channelCount)
        for (i in 0 until n) {
            out[i] = fifo[fifoHead]
            fifoHead++
            if (fifoHead == cap) fifoHead = 0
        }
        fifoCount -= n
        return n
    }

    private fun explain(e: HResultException, name: String): CaptureException = when (e.hr) {
        Hr.AUDCLNT_E_DEVICE_IN_USE -> CaptureException(
            CaptureException.Kind.UNAVAILABLE,
            "Another app is using \"$name\" in exclusive mode (an ASIO or WASAPI-exclusive player), " +
                "and Windows cannot record an output while that lasts.",
            e,
        )
        Hr.AUDCLNT_E_SERVICE_NOT_RUNNING -> CaptureException(
            CaptureException.Kind.UNAVAILABLE,
            "The Windows Audio service is not running.",
            e,
        )
        Hr.AUDCLNT_E_DEVICE_INVALIDATED -> notConnected(name)
        else -> CaptureException(
            CaptureException.Kind.UNAVAILABLE,
            "Windows would not record what \"$name\" is playing (${e.message}).",
            e,
        )
    }

    private fun notConnected(name: String) = CaptureException(
        CaptureException.Kind.UNAVAILABLE,
        "\"$name\" is unplugged or switched off. Pick another output under Source.",
    )

    private companion object {
        const val POLL_MS = 5L
        const val SILENCE_AFTER_NS = 30_000_000L
        const val MAX_GAP_NS = 500_000_000L
        const val DEFAULT_CHECK_NS = 1_000_000_000L
    }
}
