package com.ericflo.winnow.classify

import com.ericflo.winnow.classifier.local.Adjustments
import com.ericflo.winnow.data.db.CorrectionEntity
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * What the user's labels taught the on-device model, kept between runs of the app. Fitting them
 * takes a while per few hundred labels, and a text arriving while Winnow isn't running starts it
 * afresh, its notification waiting on the fit; so the fit is kept, and fitted again only when
 * something that goes into it has changed. It's stamped with every label as it stands and with
 * this install of the app (whose model and way of fitting an update can change), so a stale one
 * is never used.
 */
class PersonalModelStore(private val file: File, private val install: Long) {
    /** The adjustments last saved under [stamp], or null if there are none (or they're unreadable). */
    fun load(stamp: Long): Adjustments? = runCatching {
        if (!file.exists()) return null
        DataInputStream(file.inputStream().buffered()).use { input ->
            if (input.readInt() != MAGIC || input.readLong() != stamp) return null
            Adjustments.readFrom(input)
        }
    }.getOrNull()

    /** Keeps [adjustments] under [stamp]: whole or not at all (written aside, then moved in). */
    fun save(stamp: Long, adjustments: Adjustments) {
        val partial = File(file.parentFile, "${file.name}.part")
        runCatching {
            DataOutputStream(partial.outputStream().buffered()).use { out ->
                out.writeInt(MAGIC)
                out.writeLong(stamp)
                adjustments.writeTo(out)
            }
            if (!partial.renameTo(file)) partial.delete()
        }.onFailure { partial.delete() }
    }

    /** The stamp for a fit of [rows] by this install (see [stampOf]). */
    fun stamp(rows: List<CorrectionEntity>): Long = stampOf(rows, install)

    companion object {
        private const val MAGIC = 0x574e504d // "WNPM"

        /**
         * One number for [rows] (every field a fit reads) and [install]: the same only when
         * nothing that goes into a fit has changed, in whatever order the rows come. FNV-1a.
         * Pure, so it's unit-tested.
         */
        fun stampOf(rows: List<CorrectionEntity>, install: Long): Long {
            var h = -0x340d631b7bdddcdbL
            fun mix(s: String) {
                for (ch in s) {
                    h = (h xor ch.code.toLong()) * 0x100000001b3L
                }
                h = (h xor 0x1f) * 0x100000001b3L
            }
            for (r in rows.sortedBy { it.id }) {
                mix(r.id.toString())
                mix(r.label)
                mix(r.featurizerVersion.toString())
                mix(r.source)
                mix(r.buckets)
            }
            mix(install.toString())
            return h
        }
    }
}
