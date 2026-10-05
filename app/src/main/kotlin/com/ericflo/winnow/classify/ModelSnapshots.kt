package com.ericflo.winnow.classify

import com.ericflo.winnow.classifier.local.Adjustments
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * Fits of the on-device model kept by name (see ModelFitEntity.kept): what each learned, so an
 * older model can be scored on the labels made since it, and compared with the model now. Each
 * is its learned layer only, compressed: a few hundred KB at most, however many labels it had.
 */
class ModelSnapshots(private val dir: File) {
    private fun file(fit: String) = File(dir, "$fit.bin")

    fun save(fit: String, adjustments: Adjustments): Boolean = runCatching {
        dir.mkdirs()
        val partial = File(dir, "$fit.bin.part")
        DataOutputStream(DeflaterOutputStream(partial.outputStream().buffered())).use { out ->
            out.writeInt(MAGIC)
            adjustments.writeTo(out)
        }
        partial.renameTo(file(fit)) || run { partial.delete(); false }
    }.getOrDefault(false)

    fun load(fit: String): Adjustments? = runCatching {
        val f = file(fit)
        if (!f.exists()) return null
        DataInputStream(InflaterInputStream(f.inputStream().buffered())).use { input ->
            if (input.readInt() != MAGIC) return null
            Adjustments.readFrom(input)
        }
    }.getOrNull()

    fun has(fit: String): Boolean = file(fit).exists()

    fun delete(fit: String) {
        file(fit).delete()
    }

    /** What the kept fits take on the phone, in bytes. */
    fun bytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0

    private companion object {
        const val MAGIC = 0x574e4653 // "WNFS"
    }
}
