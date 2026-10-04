package com.ericflo.winnow.backup

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A Winnow backup: one zip with `winnow-backup.json` and the MMS media it refers to under
 * `media/`. Conversations are keyed by their participants, not thread ids, so a backup
 * restores onto another phone. API keys are never included.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class WinnowBackup(
    // Always written, even though it's the default, so a later version knows what it's reading.
    @EncodeDefault val format: Int = FORMAT,
    val createdAt: Long,
    val settings: SettingsBackup? = null,
    val senderRules: List<SenderRuleBackup> = emptyList(),
    val conversations: List<ConversationBackup> = emptyList(),
    val scheduled: List<ScheduledBackup> = emptyList(),
    val corrections: List<CorrectionBackup> = emptyList(),
) {
    val messageCount: Int get() = conversations.sumOf { it.messages.size }

    companion object {
        const val FORMAT = 1
    }
}

@Serializable
data class SettingsBackup(
    val provider: String,
    val providers: Map<String, ProviderBackup> = emptyMap(),
    val classifyContacts: Boolean,
    val classifyKnownConversations: Boolean,
    val classifyVerificationCodes: Boolean,
    val shareSenderAddress: Boolean,
    val maskDigitRuns: Boolean,
    val maskEmails: Boolean,
    val stripUrlPaths: Boolean,
    val zdrOnly: Boolean,
    val decideOnPhoneWhenSure: Boolean = false,
    val hideOnLockScreen: Boolean = false,
    val undoSendSeconds: Int = 0,
    val deleteOldCodes: Boolean = false,
    val clearOldFiltered: Boolean = false,
    val deliveryReports: Boolean,
    val categoryActions: Map<String, String> = emptyMap(),
    val theme: String = "SYSTEM",
    val textScale: Float = 1f,
    val swipeRight: String = "ARCHIVE",
    val swipeLeft: String = "ARCHIVE",
    val autoDownloadMms: Boolean = true,
    val autoDownloadMmsRoaming: Boolean = false,
    val linkPreviews: Boolean = false,
    /** Null in backups made before suggested replies existed: the phone keeps its own. */
    val suggestedReplies: Boolean? = null,
    val enterToSend: Boolean = false,
    val autoSaveMedia: Boolean = false,
    /** Null in backups made before quick replies existed: the phone keeps its own. */
    val quickReplies: List<String>? = null,
    /** Null in backups made before filtered phrases existed. */
    val filteredPhrases: List<String>? = null,
    val dailySummary: Boolean = false,
    val simpleCharacters: Boolean = false,
)

/** A provider's non-secret settings. The API key stays on the phone it was entered on. */
@Serializable
data class ProviderBackup(val model: String = "", val baseUrl: String = "", val zeroRetention: Boolean = false)

@Serializable
data class SenderRuleBackup(val address: String, val rule: String, val createdAt: Long)

@Serializable
data class ConversationBackup(
    val recipients: List<String>,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val muted: Boolean = false,
    val draft: String? = null,
    val title: String? = null,
    val messages: List<MessageBackup> = emptyList(),
    /** When a timed mute ends; null with [muted] means until turned off. */
    val mutedUntil: Long? = null,
    /** The draft's MMS subject: null for none, "" for an empty subject field. */
    val draftSubject: String? = null,
    /** What the draft has attached, stored with the media like a message's parts. */
    val draftAttachments: List<PartBackup> = emptyList(),
)

/** [MessageBackup.kind] values. */
internal const val KIND_SMS = "sms"
internal const val KIND_MMS = "mms"

/** [MessageBackup.status] for a message that didn't go out. */
internal const val STATUS_FAILED = "failed"

@Serializable
data class MessageBackup(
    /** "sms" or "mms". */
    val kind: String,
    val date: Long,
    val outgoing: Boolean,
    /** Who sent it, for incoming messages. */
    val sender: String? = null,
    val body: String = "",
    val subject: String? = null,
    /** "delivered" or "failed"; null for incoming and ordinary sent messages. */
    val status: String? = null,
    val read: Boolean = true,
    /** An outgoing SMS's recipient, when the conversation has several people. */
    val to: String? = null,
    val parts: List<PartBackup> = emptyList(),
    val verdict: VerdictBackup? = null,
    val starred: Boolean = false,
    /** When a "Remind me" on it is due; null for none. */
    val remindAt: Long? = null,
    /**
     * Recently deleted keeping some messages: how many just like this one (same [fingerprint])
     * stayed in the conversation. That many on the phone aren't it, so it's still put back.
     */
    val alongside: Int = 0,
) {
    /** Identifies the message across backup, phone and re-import, to skip duplicates. */
    val fingerprint: String get() = fingerprint(kind, date, outgoing, body, parts.size)

    companion object {
        /** [mediaCount] counts parts other than text and SMIL, as [parts] does. */
        fun fingerprint(kind: String, date: Long, outgoing: Boolean, body: String, mediaCount: Int) =
            "$kind|$date|$outgoing|${body.hashCode()}|$mediaCount"
    }
}

@Serializable
data class PartBackup(val contentType: String, val name: String? = null, val file: String)

@Serializable
data class VerdictBackup(
    val category: String? = null,
    val confidence: Double,
    val action: String,
    val sourceKind: String,
    val sourceDetail: String,
    val model: String? = null,
    val userAction: String? = null,
    val costUsd: Double = 0.0,
    val decidedAt: Long = 0,
)

/** A correction the on-device model learned from: feature buckets and a category, no text. */
@Serializable
data class CorrectionBackup(val buckets: List<Int>, val label: String, val featurizerVersion: Int, val createdAt: Long)

@Serializable
data class ScheduledBackup(val recipients: List<String>, val body: String, val sendAt: Long)

/** Reads and writes the zip container. Pure JVM, so it's unit-tested without Android. */
object BackupArchive {
    const val MANIFEST = "winnow-backup.json"
    private const val MEDIA = "media/"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = false }

    /**
     * Writes [backup] and its media; [media] streams each part's bytes by its `file` name.
     * [output] is left open, for the caller to close. The zip's index, without which it doesn't
     * open, is written only once everything else is: a write that fails partway leaves no file
     * that looks whole.
     */
    fun write(output: OutputStream, backup: WinnowBackup, media: (PartBackup) -> InputStream?) {
        val shield = Shield(output.buffered())
        val zip = ZipOutputStream(shield)
        try {
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(json.encodeToString(WinnowBackup.serializer(), backup).encodeToByteArray())
            zip.closeEntry()
            backup.conversations.flatMap { c -> c.messages.flatMap { it.parts } + c.draftAttachments }.forEach { part ->
                val stream = media(part) ?: return@forEach
                zip.putNextEntry(ZipEntry(MEDIA + part.file))
                stream.use { it.copyTo(zip) }
                zip.closeEntry()
            }
        } catch (e: Throwable) {
            // Closed all the same, to free its compressor, but with the index going nowhere.
            shield.discarding = true
            runCatching { zip.close() }
            throw e
        }
        zip.close()
    }

    /** Between the zip and [write]'s output: closing only flushes, and once [discarding], nothing gets through. */
    private class Shield(out: OutputStream) : java.io.FilterOutputStream(out) {
        var discarding = false

        override fun write(b: Int) {
            if (!discarding) out.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (!discarding) out.write(b, off, len)
        }

        override fun flush() {
            if (!discarding) out.flush()
        }

        override fun close() = flush()
    }

    /**
     * Reads a backup. Media can be far larger than memory, so each file is handed to [media]
     * as a stream, by its `file` name, to be spooled wherever the caller likes.
     */
    fun read(input: InputStream, media: (name: String, InputStream) -> Unit = { _, _ -> }): WinnowBackup {
        var backup: WinnowBackup? = null
        val buffered = input.buffered()
        ZipInputStream(buffered).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when {
                    entry.name == MANIFEST -> backup = parse(zip.readBytes())
                    entry.name.startsWith(MEDIA) -> safeName(entry.name.removePrefix(MEDIA))?.let { media(it, zip) }
                }
            }
            // On to the end, past the zip's index: a protected file is checked whole only there.
            val sink = ByteArray(8192)
            while (buffered.read(sink) >= 0) Unit
        }
        return backup ?: throw IllegalArgumentException("Not a Winnow backup: $MANIFEST is missing")
    }

    /** Reads just the manifest, which [write] puts first, without touching the media. */
    fun peek(input: InputStream): WinnowBackup {
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == MANIFEST) return parse(zip.readBytes())
            }
        }
        throw IllegalArgumentException("Not a Winnow backup: $MANIFEST is missing")
    }

    private fun parse(bytes: ByteArray): WinnowBackup {
        val backup = try {
            json.decodeFromString(WinnowBackup.serializer(), bytes.decodeToString())
        } catch (e: SerializationException) {
            throw IllegalArgumentException("This backup is damaged", e)
        }
        require(backup.format <= WinnowBackup.FORMAT) { "This backup is from a newer version of Winnow" }
        // Each part's file is looked up in the folder media is spooled into, so its name must be
        // as flat as the media entries': a "../" one in a crafted backup would reach the app's
        // own files (and put them in a message, or a draft one tap from being sent).
        fun safe(parts: List<PartBackup>) = parts.filter { safeName(it.file) != null }
        return backup.copy(
            conversations = backup.conversations.map { c ->
                c.copy(messages = c.messages.map { m -> m.copy(parts = safe(m.parts)) }, draftAttachments = safe(c.draftAttachments))
            },
        )
    }

    /** Only flat file names: nothing that could climb out of the folder media is spooled into. */
    internal fun safeName(name: String): String? =
        name.takeIf { it.isNotEmpty() && it != "." && it != ".." && '/' !in it && '\\' !in it }
}
