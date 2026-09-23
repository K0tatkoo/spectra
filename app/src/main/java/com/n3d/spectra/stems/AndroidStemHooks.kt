package com.n3d.spectra.stems

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Tells Android what the stem worker is for.
 *
 * The model has to finish 128 samples of audio in less than the 2.9 ms those
 * samples last, forever, or the picture falls behind the music. That is a
 * deadline, and a phone's scheduler cannot guess it from the outside: a thread
 * that is busy 80 % of the time looks, to the governor, like one that is
 * managing fine at the current clock on whatever core it happens to be on —
 * and on this phone the difference between a little core and the big one is
 * more than 2×.
 *
 * Android 12 added a way to say it out loud: a performance hint session. The
 * worker reports how long each batch of hops took against a target of three
 * quarters of the audio they cover, and the system raises the clock or moves
 * the thread to a bigger core when it misses. Older versions just get the
 * thread priority.
 */
class AndroidStemHooks(private val context: Context) : StemWorkerHooks {

    private var session: Any? = null
    private var batchWork = 0L
    private var batchAudio = 0L
    private var reportEvery = 0L

    override fun onStart() {
        // Above normal: the worker feeds a display, and at normal priority a
        // busy moment elsewhere in the app starves it into falling behind. Not
        // an audio priority — it is heavy compute, and must not beat the
        // capture thread to a core.
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) startSession()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun startSession() {
        val manager = context.getSystemService(PerformanceHintManager::class.java) ?: return
        val batchAudioNanos = BATCH_HOPS * HOP_NANOS
        session = runCatching {
            manager.createHintSession(intArrayOf(Process.myTid()), (batchAudioNanos * TARGET_SHARE).toLong())
        }.onFailure { Log.i(TAG, "no performance hint session: ${it.message}") }.getOrNull()
        reportEvery = batchAudioNanos
    }

    override fun onWork(workNanos: Long, audioNanos: Long) {
        if (session == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        batchWork += workNanos
        batchAudio += audioNanos
        // One report per batch rather than per hop: the hint is a binder call,
        // and 345 of them a second would cost more than they could win.
        if (batchAudio >= reportEvery) {
            report(batchWork)
            batchWork = 0L
            batchAudio = 0L
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun report(nanos: Long) {
        runCatching { (session as PerformanceHintManager.Session).reportActualWorkDuration(nanos) }
    }

    override fun onStop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { (session as? PerformanceHintManager.Session)?.close() }
        }
        session = null
    }

    private companion object {
        const val TAG = "SpectraStems"
        const val BATCH_HOPS = 8
        const val HOP_NANOS = 128L * 1_000_000_000L / StemSeparator.SAMPLE_RATE
        /** Aim to finish in three quarters of real time, leaving room for a hiccup. */
        const val TARGET_SHARE = 0.75
    }
}
