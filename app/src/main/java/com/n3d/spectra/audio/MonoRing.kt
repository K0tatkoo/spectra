package com.n3d.spectra.audio

/**
 * Fixed-size circular history of the most recent [size] samples.
 *
 * The FFT needs a window that overlaps the previous one; the capture callback
 * delivers arbitrary chunk sizes that have nothing to do with the window size.
 * This is the piece that decouples them, and [pending] is what tells the engine
 * how many new samples have arrived since the last transform so it can fire one
 * hop at a time instead of once per chunk.
 */
class MonoRing(val size: Int) {

    private val buf = FloatArray(size)
    private var writeIndex = 0

    /** New samples written since the last [consume]. */
    var pending = 0
        private set

    fun write(src: FloatArray, offset: Int, count: Int) {
        var remaining = count
        var from = offset
        while (remaining > 0) {
            val room = size - writeIndex
            val n = minOf(room, remaining)
            System.arraycopy(src, from, buf, writeIndex, n)
            writeIndex = (writeIndex + n) % size
            from += n
            remaining -= n
        }
        pending += count
        if (pending > size) pending = size
    }

    fun consume(n: Int) {
        pending -= n
        if (pending < 0) pending = 0
    }

    /** Copies the whole history into [out] oldest sample first. */
    fun copyLatest(out: FloatArray) {
        val tail = size - writeIndex
        System.arraycopy(buf, writeIndex, out, 0, tail)
        System.arraycopy(buf, 0, out, tail, writeIndex)
    }

    /**
     * Copies the newest [count] samples into [out], oldest first, decimating by
     * [stride]. Used for the scope trace, where drawing 4096 points into 400 px
     * would just be four thousand ways to draw the same line.
     */
    fun copyNewestDecimated(out: FloatArray, count: Int, stride: Int) {
        val start = ((writeIndex - count) % size + size) % size
        var idx = start
        var w = 0
        while (w < out.size) {
            out[w] = buf[idx]
            idx += stride
            if (idx >= size) idx -= size
            w++
        }
    }

    fun clear() {
        java.util.Arrays.fill(buf, 0f)
        writeIndex = 0
        pending = 0
    }
}
