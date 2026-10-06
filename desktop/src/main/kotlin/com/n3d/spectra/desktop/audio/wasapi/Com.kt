package com.n3d.spectra.desktop.audio.wasapi

import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * Just enough COM to talk to WASAPI from the JVM.
 *
 * WASAPI is a set of COM interfaces, and a COM interface is nothing more than a
 * pointer to a table of function pointers. JNA can call a function pointer, so
 * every call here is "read slot N of the table, call it with the object as the
 * first argument" — no generated bindings, no native code of our own to build,
 * and nothing that stops the Windows build being cross-built from a Mac.
 *
 * The slot numbers are the declaration order in the Windows SDK headers
 * (mmdeviceapi.h, audioclient.h, propsys.h), counting IUnknown's three first.
 */
internal interface Ole32 : Library {
    fun CoInitializeEx(reserved: Pointer?, coInit: Int): Int
    fun CoUninitialize()
    fun CoCreateInstance(clsid: Pointer, outer: Pointer?, context: Int, iid: Pointer, out: Pointer): Int
    fun CoTaskMemFree(p: Pointer?)
    fun PropVariantClear(pv: Pointer): Int

    companion object {
        val INSTANCE: Ole32 by lazy { Native.load("ole32", Ole32::class.java) }
    }
}

/** A COM interface pointer that we own one reference to. */
internal class ComPtr(val raw: Pointer) : AutoCloseable {
    private var released = false

    /** Slot [index] of the vtable. Worth caching for anything called per packet. */
    fun fn(index: Int): Function =
        Function.getFunction(raw.getPointer(0).getPointer(index.toLong() * Native.POINTER_SIZE))

    fun call(index: Int, vararg args: Any?): Int = fn(index).invokeInt(arrayOf<Any?>(raw, *args))

    override fun close() {
        if (released) return
        released = true
        call(2) // IUnknown::Release
    }
}

/** A GUID laid out the way Windows expects it in memory. */
internal class Guid(text: String) {
    val memory = Memory(16)

    init {
        val hex = text.filter { it.isLetterOrDigit() }
        require(hex.length == 32) { "not a GUID: $text" }
        memory.setInt(0, hex.substring(0, 8).toLong(16).toInt())
        memory.setShort(4, hex.substring(8, 12).toInt(16).toShort())
        memory.setShort(6, hex.substring(12, 16).toInt(16).toShort())
        for (i in 0 until 8) memory.setByte(8L + i, hex.substring(16 + i * 2, 18 + i * 2).toInt(16).toByte())
    }
}

/** A PROPERTYKEY: a GUID and a property id. */
internal class PropertyKey(fmtid: String, pid: Int) {
    val memory = Memory(20).also {
        it.write(0, Guid(fmtid).memory.getByteArray(0, 16), 0, 16)
        it.setInt(16, pid)
    }
}

internal class HResultException(val hr: Int, what: String) : Exception("$what failed (${Hr.describe(hr)})")

internal fun Int.orThrow(what: String): Int {
    if (this < 0) throw HResultException(this, what)
    return this
}

internal object Hr {
    const val S_OK = 0
    const val S_FALSE = 1
    const val AUDCLNT_S_BUFFER_EMPTY = 0x08890001
    const val AUDCLNT_E_DEVICE_INVALIDATED = 0x88890004.toInt()
    const val AUDCLNT_E_UNSUPPORTED_FORMAT = 0x88890008.toInt()
    const val AUDCLNT_E_DEVICE_IN_USE = 0x8889000A.toInt()
    const val AUDCLNT_E_SERVICE_NOT_RUNNING = 0x88890010.toInt()
    const val E_INVALIDARG = 0x80070057.toInt()
    const val E_NOTFOUND = 0x80070490.toInt()
    const val RPC_E_CHANGED_MODE = 0x80010106.toInt()

    private val names = mapOf(
        AUDCLNT_E_DEVICE_INVALIDATED to "AUDCLNT_E_DEVICE_INVALIDATED",
        AUDCLNT_E_UNSUPPORTED_FORMAT to "AUDCLNT_E_UNSUPPORTED_FORMAT",
        AUDCLNT_E_DEVICE_IN_USE to "AUDCLNT_E_DEVICE_IN_USE",
        AUDCLNT_E_SERVICE_NOT_RUNNING to "AUDCLNT_E_SERVICE_NOT_RUNNING",
        E_INVALIDARG to "E_INVALIDARG",
        E_NOTFOUND to "E_NOTFOUND",
        0x80004001.toInt() to "E_NOTIMPL",
        0x80004002.toInt() to "E_NOINTERFACE",
        0x80004005.toInt() to "E_FAIL",
        0x80070005.toInt() to "E_ACCESSDENIED",
        0x8007000E.toInt() to "E_OUTOFMEMORY",
        0x800401F0.toInt() to "CO_E_NOTINITIALIZED",
    )

    fun describe(hr: Int): String {
        val hex = "0x%08X".format(hr)
        return names[hr]?.let { "$it, $hex" } ?: hex
    }
}
