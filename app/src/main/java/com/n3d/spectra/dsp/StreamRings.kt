package com.n3d.spectra.dsp

/**
 * A mono history that one thread writes and another reads, indexed by the
 * absolute number of samples ever written.
 *
 * The absolute index is what lets a display hold something *still*: a trigger
 * that fired at sample 1 234 567 still names the same sample a second later,
 * whereas an offset from "now" moves every time a block arrives.
 *
 * Single producer, single consumer, no locks. The writer copies first and
 * publishes [written] after, so a reader that snapshots [written] and copies
 * the samples behind it never sees a half-written block. The only way to read
 * garbage is for the writer to lap the reader during one copy, which needs the
 * capacity minus the read length to arrive in the time it takes to copy the
 * read length — a second and a half of audio in a few microseconds.
 */
class HistoryRing(capacity: Int) {

    val capacity: Int = Integer.highestOneBit(capacity - 1) shl 1
    private val mask = this.capacity - 1
    private val buf = FloatArray(this.capacity)

    /** Total samples ever written. */
    @Volatile var written: Long = 0L
        private set

    fun write(src: FloatArray, offset: Int, count: Int) {
        var w = written
        for (i in 0 until count) {
            buf[(w and mask.toLong()).toInt()] = src[offset + i]
            w++
        }
        written = w
    }

    /**
     * Copies the [count] samples that end at absolute index [end] (exclusive)
     * into [dst], oldest first. Samples before the start of the stream read as
     * zero. Returns false if [end] is ahead of the writer or already overwritten.
     */
    fun read(end: Long, dst: FloatArray, count: Int): Boolean {
        val w = written
        if (end > w || count > capacity || end - count < w - capacity) return false
        val start = end - count
        for (i in 0 until count) {
            val abs = start + i
            dst[i] = if (abs < 0) 0f else buf[(abs and mask.toLong()).toInt()]
        }
        return true
    }

    fun clear() {
        java.util.Arrays.fill(buf, 0f)
        written = 0L
    }
}

/**
 * Interleaved stereo frames from the analysis thread to the stem worker.
 *
 * Unlike [HistoryRing] the reader here *consumes*: it keeps its own position and
 * asks how far behind the writer it is, which is what lets the worker notice it
 * has fallen behind and skip ahead rather than drift ever further from "now".
 */
class StereoFeed(capacityFrames: Int) {

    val capacity: Int = Integer.highestOneBit(capacityFrames - 1) shl 1
    private val mask = capacity - 1
    private val buf = FloatArray(capacity * 2)

    /** Total frames ever written. */
    @Volatile var written: Long = 0L
        private set

    fun write(left: FloatArray, right: FloatArray, count: Int) {
        var w = written
        for (i in 0 until count) {
            val j = (w and mask.toLong()).toInt() * 2
            buf[j] = left[i]
            buf[j + 1] = right[i]
            w++
        }
        written = w
    }

    /**
     * Copies [count] frames starting at absolute frame [from] into [dst] as
     * planar channels: [count] left samples, then [count] right samples — the
     * `[1, 2, count]` layout the model takes. False if they are not all there.
     */
    fun readPlanar(from: Long, count: Int, dst: FloatArray): Boolean {
        val w = written
        if (from + count > w || from < w - capacity) return false
        for (i in 0 until count) {
            val j = ((from + i) and mask.toLong()).toInt() * 2
            dst[i] = buf[j]
            dst[count + i] = buf[j + 1]
        }
        return true
    }
}
