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
    val deliveryReports: Boolean,
    val categoryActions: Map<String, String> = emptyMap(),
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
    val messages: List<MessageBackup> = emptyList(),
)

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

@Serializable
data class ScheduledBackup(val recipients: List<String>, val body: String, val sendAt: Long)

/** Reads and writes the zip container. Pure JVM, so it's unit-tested without Android. */
object BackupArchive {
    const val MANIFEST = "winnow-backup.json"
    private const val MEDIA = "media/"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = false }

    /** Writes [backup] and its media; [media] streams each part's bytes by its `file` name. */
    fun write(output: OutputStream, backup: WinnowBackup, media: (PartBackup) -> InputStream?) {
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(json.encodeToString(WinnowBackup.serializer(), backup).encodeToByteArray())
            zip.closeEntry()
            backup.conversations.flatMap { it.messages }.flatMap { it.parts }.forEach { part ->
                val stream = media(part) ?: return@forEach
                zip.putNextEntry(ZipEntry(MEDIA + part.file))
                stream.use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /**
     * Reads a backup. Media can be far larger than memory, so each file is handed to [media]
     * as a stream, by its `file` name, to be spooled wherever the caller likes.
     */
    fun read(input: InputStream, media: (name: String, InputStream) -> Unit = { _, _ -> }): WinnowBackup {
        var backup: WinnowBackup? = null
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when {
                    entry.name == MANIFEST -> backup = parse(zip.readBytes())
                    entry.name.startsWith(MEDIA) -> safeName(entry.name.removePrefix(MEDIA))?.let { media(it, zip) }
                }
            }
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
        return backup
    }

    /** Only flat file names: nothing that could climb out of the folder media is spooled into. */
    private fun safeName(name: String): String? =
        name.takeIf { it.isNotEmpty() && it != "." && it != ".." && '/' !in it && '\\' !in it }
}
