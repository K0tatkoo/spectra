package com.n3d.spectra.desktop.audio.wasapi

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.WString

/**
 * Windows' audio endpoints, reached through COM.
 *
 * Do not touch this object off Windows: initialising it loads JNA's native
 * library. [SystemAudioSupport.available] is the guard.
 */
internal object Wasapi {
    private const val CLSCTX_ALL = 0x17
    const val COINIT_MULTITHREADED = 0
    private const val E_RENDER = 0
    private const val E_CONSOLE = 0
    private const val DEVICE_STATE_ACTIVE = 1
    private const val STGM_READ = 0
    private const val VT_LPWSTR = 31

    private val CLSID_MMDeviceEnumerator = Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")
    private val IID_IMMDeviceEnumerator = Guid("A95664D2-9614-4F35-A746-DE8DB63617E6")
    val IID_IAudioClient = Guid("1CB9AD4C-DBFA-4C32-B178-C2F568A703B2")
    val IID_IAudioCaptureClient = Guid("C8ADBD64-E71E-48A0-A4DE-185C395CD317")
    private val PKEY_Device_FriendlyName = PropertyKey("A45C254E-DF1C-4EFD-8020-67D146A850E0", 14)

    val ole32: Ole32 get() = Ole32.INSTANCE

    class Endpoint(val id: String, val name: String)

    /**
     * Runs [block] with the calling thread in the multithreaded apartment.
     *
     * If the thread already belongs to a single-threaded one (Swing's or AWT's
     * own COM use), CoInitializeEx says so and the block runs there instead —
     * the device enumerator works in either, so that is not an error.
     */
    fun <T> inMta(block: () -> T): T {
        val hr = ole32.CoInitializeEx(null, COINIT_MULTITHREADED)
        try {
            return block()
        } finally {
            if (hr == Hr.S_OK || hr == Hr.S_FALSE) ole32.CoUninitialize()
        }
    }

    fun enumerator(): ComPtr {
        val out = Memory(8)
        ole32.CoCreateInstance(CLSID_MMDeviceEnumerator.memory, null, CLSCTX_ALL, IID_IMMDeviceEnumerator.memory, out)
            .orThrow("CoCreateInstance(MMDeviceEnumerator)")
        return ComPtr(out.getPointer(0))
    }

    /** Every playback endpoint that is switched on and connected, in Windows' order. */
    fun outputs(): List<Endpoint> = inMta {
        enumerator().use { en ->
            val out = Memory(8)
            en.call(3, E_RENDER, DEVICE_STATE_ACTIVE, out).orThrow("EnumAudioEndpoints")
            ComPtr(out.getPointer(0)).use { list ->
                val count = Memory(4)
                list.call(3, count).orThrow("IMMDeviceCollection::GetCount")
                (0 until count.getInt(0)).mapNotNull { i ->
                    if (list.call(4, i, out) < 0) return@mapNotNull null
                    ComPtr(out.getPointer(0)).use { dev -> Endpoint(idOf(dev), nameOf(dev) ?: "Output ${i + 1}") }
                }
            }
        }
    }

    /** The endpoint id of the default playback device, or null when there is none. */
    fun defaultOutputId(en: ComPtr): String? {
        val out = Memory(8)
        val hr = en.call(4, E_RENDER, E_CONSOLE, out)
        if (hr == Hr.E_NOTFOUND) return null
        hr.orThrow("GetDefaultAudioEndpoint")
        return ComPtr(out.getPointer(0)).use { idOf(it) }
    }

    /** The device with [id], or null if Windows no longer knows it. */
    fun device(en: ComPtr, id: String): ComPtr? {
        val out = Memory(8)
        val hr = en.call(5, WString(id), out)
        if (hr == Hr.E_NOTFOUND) return null
        hr.orThrow("IMMDeviceEnumerator::GetDevice")
        return ComPtr(out.getPointer(0))
    }

    /** Known but unplugged or disabled devices still answer GetDevice; only active ones can stream. */
    fun isActive(dev: ComPtr): Boolean {
        val state = Memory(4)
        dev.call(6, state).orThrow("IMMDevice::GetState")
        return state.getInt(0) == DEVICE_STATE_ACTIVE
    }

    fun idOf(dev: ComPtr): String {
        val out = Memory(8)
        dev.call(5, out).orThrow("IMMDevice::GetId")
        val p = out.getPointer(0)
        try {
            return p.getWideString(0)
        } finally {
            ole32.CoTaskMemFree(p)
        }
    }

    /** "Speakers (Realtek(R) Audio)" — the name Windows shows in its own sound settings. */
    fun nameOf(dev: ComPtr): String? {
        val out = Memory(8)
        if (dev.call(4, STGM_READ, out) < 0) return null
        return ComPtr(out.getPointer(0)).use { store ->
            // PROPVARIANT is 16 bytes on 32-bit Windows and 24 on 64-bit.
            val pv = Memory(24)
            pv.clear()
            if (store.call(5, PKEY_Device_FriendlyName.memory, pv) < 0) return@use null
            try {
                if (pv.getShort(0).toInt() == VT_LPWSTR) pv.getPointer(8)?.getWideString(0)?.trim()?.ifEmpty { null } else null
            } finally {
                ole32.PropVariantClear(pv)
            }
        }
    }

    fun activateAudioClient(dev: ComPtr): ComPtr {
        val out = Memory(8)
        dev.call(3, IID_IAudioClient.memory, CLSCTX_ALL, null, out).orThrow("IMMDevice::Activate(IAudioClient)")
        return ComPtr(out.getPointer(0))
    }
}

/**
 * One running loopback stream: everything an endpoint is playing, as it is mixed.
 *
 * Loopback is a flag on an ordinary shared-mode capture stream opened on a
 * *playback* device. Two consequences shape the class:
 *
 *  * Windows only delivers packets while something is actually being rendered.
 *    When nothing plays, the stream goes quiet rather than sending zeros —
 *    [com.n3d.spectra.desktop.audio.LoopbackCapture] fills that gap.
 *  * The stream has to use the endpoint's mix format unless Windows agrees to
 *    convert. It is asked to (float stereo at the user's rate); if a driver
 *    refuses, the mix format is taken as it is and converted here.
 */
internal class LoopbackStream private constructor(
    private val client: ComPtr,
    private val capture: ComPtr,
    val format: PcmFormat,
    /** True when Windows is converting to the format we asked for. */
    val converted: Boolean,
) : AutoCloseable {

    // Called every few milliseconds: look the slots up once.
    private val getBuffer = capture.fn(3)
    private val releaseBuffer = capture.fn(4)
    private val getNextPacketSize = capture.fn(5)
    private val dataOut = Memory(8)
    private val framesOut = Memory(4)
    private val flagsOut = Memory(4)
    private val sizeOut = Memory(4)

    fun start() {
        client.call(10).orThrow("IAudioClient::Start")
    }

    /**
     * Hands every packet waiting in the buffer to [sink] as (data, frames, silent),
     * and returns how many frames that was. Throws [HResultException] — notably
     * AUDCLNT_E_DEVICE_INVALIDATED when the device went away or changed format.
     */
    fun drain(sink: (Pointer, Int, Boolean) -> Unit): Int {
        var total = 0
        while (true) {
            getNextPacketSize.invokeInt(arrayOf<Any?>(capture.raw, sizeOut)).orThrow("GetNextPacketSize")
            if (sizeOut.getInt(0) == 0) return total
            val hr = getBuffer.invokeInt(arrayOf<Any?>(capture.raw, dataOut, framesOut, flagsOut, null, null))
            if (hr == Hr.AUDCLNT_S_BUFFER_EMPTY) return total
            hr.orThrow("IAudioCaptureClient::GetBuffer")
            val frames = framesOut.getInt(0)
            try {
                if (frames > 0) sink(dataOut.getPointer(0), frames, flagsOut.getInt(0) and BUFFERFLAGS_SILENT != 0)
            } finally {
                releaseBuffer.invokeInt(arrayOf<Any?>(capture.raw, frames))
            }
            total += frames
        }
    }

    override fun close() {
        runCatching { client.call(11) } // IAudioClient::Stop
        runCatching { capture.close() }
        runCatching { client.close() }
    }

    companion object {
        private const val SHAREMODE_SHARED = 0
        private const val STREAMFLAGS_LOOPBACK = 0x00020000
        private const val STREAMFLAGS_AUTOCONVERTPCM = 0x80000000.toInt()
        private const val STREAMFLAGS_SRC_DEFAULT_QUALITY = 0x08000000
        private const val BUFFERFLAGS_SILENT = 0x2

        /** A fifth of a second, in 100 ns units — the same margin the microphone path keeps. */
        private const val BUFFER_HNS = 2_000_000L

        /** [allowConversion] false skips straight to the mix format; only the checks do that. */
        fun open(device: ComPtr, rate: Int, allowConversion: Boolean = true): LoopbackStream {
            // First choice: Windows resamples and down-mixes to float stereo at the
            // rate the user picked, so the stream matches every other source.
            if (allowConversion) {
                val wanted = PcmFormat.float32(rate, 2)
                val wantedBytes = wanted.toWaveFormatEx()
                val wantedMem = Memory(wantedBytes.size.toLong()).apply { write(0, wantedBytes, 0, wantedBytes.size) }
                tryInit(device, wantedMem, wanted, STREAMFLAGS_AUTOCONVERTPCM or STREAMFLAGS_SRC_DEFAULT_QUALITY)
                    ?.let { return it }
            }

            // Fallback: the endpoint's own mix format, converted on our side.
            val client = Wasapi.activateAudioClient(device)
            try {
                val out = Memory(8)
                client.call(8, out).orThrow("IAudioClient::GetMixFormat")
                val mix = out.getPointer(0)
                try {
                    val size = 18 + (mix.getShort(16).toInt() and 0xFFFF)
                    val format = PcmFormat.parse(mix.getByteArray(0, size))
                    client.call(3, SHAREMODE_SHARED, STREAMFLAGS_LOOPBACK, BUFFER_HNS, 0L, mix, null)
                        .orThrow("IAudioClient::Initialize(loopback, mix format)")
                    return LoopbackStream(client, service(client), format, converted = false)
                } finally {
                    Wasapi.ole32.CoTaskMemFree(mix)
                }
            } catch (t: Throwable) {
                client.close()
                throw t
            }
        }

        /** Null when Windows will not convert to [format] — a fresh client is then needed. */
        private fun tryInit(device: ComPtr, formatMem: Memory, format: PcmFormat, extraFlags: Int): LoopbackStream? {
            val client = Wasapi.activateAudioClient(device)
            val hr = client.call(3, SHAREMODE_SHARED, STREAMFLAGS_LOOPBACK or extraFlags, BUFFER_HNS, 0L, formatMem, null)
            if (hr < 0) {
                client.close()
                // These say nothing about the format; retrying with another would only hide them.
                if (hr == Hr.AUDCLNT_E_DEVICE_IN_USE || hr == Hr.AUDCLNT_E_DEVICE_INVALIDATED ||
                    hr == Hr.AUDCLNT_E_SERVICE_NOT_RUNNING
                ) {
                    throw HResultException(hr, "IAudioClient::Initialize(loopback)")
                }
                return null
            }
            return try {
                LoopbackStream(client, service(client), format, converted = true)
            } catch (t: Throwable) {
                client.close()
                throw t
            }
        }

        private fun service(client: ComPtr): ComPtr {
            val out = Memory(8)
            client.call(14, Wasapi.IID_IAudioCaptureClient.memory, out).orThrow("IAudioClient::GetService(IAudioCaptureClient)")
            return ComPtr(out.getPointer(0))
        }
    }
}

/** The one place that decides whether WASAPI may be touched at all. Safe to call anywhere. */
object SystemAudioSupport {
    /** On Windows, with JNA's native half loadable. Never initialises [Wasapi] elsewhere. */
    val available: Boolean by lazy {
        System.getProperty("os.name").orEmpty().startsWith("Windows") &&
            runCatching { Wasapi.ole32; true }.getOrElse { false }
    }
}
