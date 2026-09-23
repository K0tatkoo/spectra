package com.n3d.spectra.stems

/**
 * What the platform may do for the stem worker thread. Kept as an interface so
 * [StemSeparator] stays free of Android types and its tests run on a desktop JVM.
 */
interface StemWorkerHooks {
    /** Called once, on the worker thread, before the model loads. */
    fun onStart() {}

    /**
     * Called after every call into the model with the time it took, and the
     * length of audio it covered — 2.9 ms of audio per 128-sample hop.
     */
    fun onWork(workNanos: Long, audioNanos: Long) {}

    /** Called once, on the worker thread, as it exits. */
    fun onStop() {}

    companion object {
        val NONE = object : StemWorkerHooks {}
    }
}
