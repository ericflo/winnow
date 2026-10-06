package com.ericflo.winnow.sms

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Scratch files for PDUs exchanged with the system MMS service, shared through a FileProvider. */
class MmsFiles(private val context: Context) {
    // Made again each time: clearing Winnow's cache (App info, or Android short of space) takes the
    // folder from under a running Winnow, and every picture message after would fail.
    private val dir: File get() = File(context.cacheDir, "mms").apply { mkdirs() }

    fun newFile(prefix: String): File = File.createTempFile(prefix, ".pdu", dir)

    fun write(prefix: String, bytes: ByteArray): File = newFile(prefix).apply { writeBytes(bytes) }

    fun uriFor(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.mms", file)

    /** Removes leftovers from sends and acknowledgements whose callbacks never came. */
    fun cleanUp(olderThanMillis: Long = 24 * 60 * 60_000L) {
        val cutoff = System.currentTimeMillis() - olderThanMillis
        dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }
}
