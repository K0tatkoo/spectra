package com.n3d.spectra.stems

import android.util.Log
import java.io.File

/**
 * Keeps the stem worker on the phone's fastest core.
 *
 * Measured on the S24 Ultra (Snapdragon 8 Gen 3: one Cortex-X4, five A720, two
 * A520), the model takes 1.9–2.3 ms per 2.9 ms hop on the X4 and 3.4–3.5 ms on
 * an A720, which can never keep up. Left to itself the scheduler moves the
 * worker between the two and the load reads 0.96–0.99, the same as the app's
 * own footer on that phone. Pinned to the X4 it reads 0.66–0.82 while the phone
 * is cool, and is still about 5 % ahead once the X4 is thermally capped at
 * half its clock — even throttled, it keeps pace with an A720 at full speed.
 *
 * Android has no Java API for this, so it is the app's one native call of its
 * own. The kernel resets a thread's affinity whenever the app moves between
 * cpusets (foreground, background), which is why the worker re-applies it
 * rather than setting it once.
 */
object CpuAffinity {

    private const val TAG = "SpectraCpu"

    private val loaded: Boolean = runCatching { System.loadLibrary("spectra_cpu") }
        .onFailure { Log.i(TAG, "no affinity library: ${it.message}") }
        .isSuccess

    /**
     * The fastest tier of cores as a mask (bit n = cpu n), or 0 when the cores
     * are all alike — then there is nothing to gain, and pinning would only stop
     * the scheduler from spreading the load.
     */
    val fastestCores: Long by lazy { findFastest() }

    private var reported = false

    /** Pins the calling thread to [fastestCores]. True if the kernel took it. */
    fun pinToFastest(): Boolean {
        val mask = fastestCores
        if (!loaded || mask == 0L) return false
        val result = pinCallingThread(mask)
        if (!reported) {
            reported = true
            if (result == 0) {
                Log.i(TAG, "stem worker pinned to cpus 0x${callingThreadMask().toString(16)}")
            } else {
                Log.i(TAG, "could not pin the stem worker to 0x${mask.toString(16)}: errno ${-result}")
            }
        }
        return result == 0
    }

    private fun findFastest(): Long {
        val maxFreq = HashMap<Int, Long>()
        var cpu = 0
        while (cpu < 64 && File("/sys/devices/system/cpu/cpu$cpu").exists()) {
            runCatching {
                File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
            }.getOrNull()?.let { maxFreq[cpu] = it }
            cpu++
        }
        if (maxFreq.size < 2) return 0L
        val top = maxFreq.values.max()
        val fastest = maxFreq.filterValues { it == top }.keys
        if (fastest.size == maxFreq.size) return 0L
        return fastest.fold(0L) { mask, c -> mask or (1L shl c) }
    }

    @JvmStatic private external fun pinCallingThread(mask: Long): Int

    @JvmStatic private external fun callingThreadMask(): Long
}
