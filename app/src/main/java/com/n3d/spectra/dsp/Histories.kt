package com.n3d.spectra.dsp

/**
 * Circular column store for the spectrogram.
 *
 * Columns are quantised to a byte (0 = at or below the floor, 255 = at the
 * ceiling) rather than kept as floats. At 256 rows × 512 columns that is 128 KB
 * instead of 512 KB, and it is what the painter wants anyway — a colour-map
 * lookup index. The dB range is baked in at write time, so changing the range
 * in settings clears the history; the alternative (keeping dB and re-mapping on
 * every draw) costs a multiply per pixel per frame on four surfaces.
 *
 * Written by the analysis thread, read by up to four painters on other threads.
 * There is no lock: the worst possible outcome is one torn column in one frame,
 * which is invisible, and a lock here would put the audio thread at the mercy of
 * a painter's scheduling.
 */
class SpectrogramBuffer(val rows: Int, val columns: Int) {

    val data = ByteArray(rows * columns)

    /** Index of the column that will be written next — i.e. the oldest column. */
    @Volatile var head: Int = 0
        private set

    @Volatile var filled: Int = 0
        private set

    fun push(column: ByteArray) {
        val h = head
        val base = h * rows
        val n = minOf(rows, column.size)
        System.arraycopy(column, 0, data, base, n)
        head = (h + 1) % columns
        if (filled < columns) filled++
    }

    fun clear() {
        java.util.Arrays.fill(data, 0)
        head = 0
        filled = 0
    }
}

/**
 * A fixed-length scrolling history of one scalar — used for the per-band level
 * graphs and for the loudness trace. Same reasoning as above on locking.
 */
class ScrollBuffer(val capacity: Int, private val initial: Float = -140f) {

    val data = FloatArray(capacity) { initial }

    @Volatile var head: Int = 0
        private set

    fun push(value: Float) {
        data[head] = value
        head = (head + 1) % capacity
    }

    /** Copies the history into [out] oldest-first, so painters can walk it linearly. */
    fun snapshot(out: FloatArray) {
        val h = head
        val tail = capacity - h
        System.arraycopy(data, h, out, 0, tail)
        System.arraycopy(data, 0, out, tail, h)
    }

    fun clear() {
        java.util.Arrays.fill(data, initial)
        head = 0
    }
}
