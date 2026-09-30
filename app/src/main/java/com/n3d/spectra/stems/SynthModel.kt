package com.n3d.spectra.stems

import android.content.Context

/**
 * The synth splitter's model, when this build carries one.
 *
 * Small enough — a few MB against the stem model's 37 — that ONNX Runtime can
 * take it straight from memory, so unlike [StemModel] nothing is copied out
 * to disk. A build without the asset simply has no synth lane.
 */
object SynthModel {

    const val ASSET = "stems/synth-split.onnx"

    /** Reads the model's bytes, or null when the build has none. Cheap to call: it only lists the folder. */
    fun loader(context: Context): (() -> ByteArray)? {
        val present = runCatching { context.assets.list("stems")?.contains(ASSET.substringAfter('/')) == true }.getOrDefault(false)
        if (!present) return null
        val app = context.applicationContext
        return { app.assets.open(ASSET).use { it.readBytes() } }
    }
}
