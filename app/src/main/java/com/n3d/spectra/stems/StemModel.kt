package com.n3d.spectra.stems

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * Where the stem model lives on disk.
 *
 * It ships inside the APK as an uncompressed asset, but ONNX Runtime wants a
 * file path: handed a byte array instead, the whole 37 MB would sit on the Java
 * heap next to the runtime's own copy. So it is copied out once, into
 * no-backup storage (it is part of the app, not the user's data), and every
 * later start opens the copy directly.
 *
 * The copy is named after the model's hash, so an app update that ships a new
 * model can never pick up the old one, and a copy is only ever renamed into
 * place once it is complete and the right size.
 */
object StemModel {

    fun file(context: Context): File {
        val dir = File(context.noBackupFilesDir, "stems")
        val target = File(dir, "${StemSeparator.SHA256.take(16)}.onnx")
        if (target.length() == StemSeparator.BYTES) return target

        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not create ${dir.path}")
        // Anything else in the folder is an older model.
        dir.listFiles()?.forEach { if (it != target) it.delete() }

        val partial = File(dir, target.name + ".part")
        context.assets.open(StemSeparator.ASSET).use { input ->
            partial.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
        }
        if (partial.length() != StemSeparator.BYTES) {
            partial.delete()
            throw IOException("The bundled stem model is incomplete.")
        }
        if (!partial.renameTo(target)) {
            partial.delete()
            throw IOException("Could not move the stem model into place.")
        }
        return target
    }
}
