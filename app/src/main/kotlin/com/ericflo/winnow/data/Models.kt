package com.ericflo.winnow.data

import com.ericflo.winnow.classifier.message.Action

data class ConversationSummary(
    val threadId: Long,
    /** Every other participant. One for a 1:1 conversation, several for a group. */
    val recipients: List<String>,
    val displayName: String,
    val snippet: String,
    val timestamp: Long,
    val unreadCount: Int,
    /** Verdict on the newest incoming message, which decides where the conversation is shown. */
    val verdict: StoredVerdict?,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val muted: Boolean = false,
    val draft: String? = null,
    /** The contact's photo, for 1:1 conversations with a contact who has one. */
    val photoUri: String? = null,
) {
    /** The single address of a 1:1 conversation, or the first participant of a group. */
    val address: String get() = recipients.firstOrNull().orEmpty()
    val isGroup: Boolean get() = recipients.size > 1
    val isFiltered: Boolean get() = verdict?.effectiveAction == Action.FILTER
    val unread: Boolean get() = unreadCount > 0
}

data class ChatMessage(
    val id: Long,
    val threadId: Long,
    val body: String,
    val timestamp: Long,
    val outgoing: Boolean,
    val status: Status,
    val verdict: StoredVerdict?,
    val kind: Kind = Kind.SMS,
    /** Who sent an incoming message; matters in groups. */
    val sender: String? = null,
    val attachments: List<Attachment> = emptyList(),
    val subject: String? = null,
) {
    enum class Status { RECEIVED, SENDING, SENT, DELIVERED, FAILED, DOWNLOADING, DOWNLOAD_FAILED }

    enum class Kind { SMS, MMS }

    /** Unique across the SMS and MMS tables, whose ids overlap. Also the verdict key. */
    val key: String get() = messageKey(kind, id)

    companion object {
        fun messageKey(kind: Kind, id: Long) = "${kind.name.lowercase()}:$id"
    }
}

data class Attachment(
    /** A content:// or android.resource:// URI. */
    val uri: String,
    val contentType: String,
    val name: String? = null,
) {
    val isImage: Boolean get() = contentType.startsWith("image/")
}

data class StoredVerdict(
    val category: com.ericflo.winnow.classifier.message.Category?,
    val confidence: Double,
    val action: Action,
    /** Human-readable origin, e.g. "Classified by Jev (TypeSafe)" or "Sender is in your contacts". */
    val source: String,
    /** The user's correction, which wins over [action]. */
    val userAction: Action? = null,
) {
    val effectiveAction: Action get() = userAction ?: action

    /** Links in fraud are never clickable, whatever the user later decides about the sender. */
    val isFraud: Boolean
        get() = userAction != Action.ALLOW && category in setOf(
            com.ericflo.winnow.classifier.message.Category.PHISHING,
            com.ericflo.winnow.classifier.message.Category.SCAM,
        )
}

/** One decision Winnow made, for the Activity screen. */
data class VerdictRecord(
    val category: com.ericflo.winnow.classifier.message.Category?,
    /** What actually happened, after any correction by the user. */
    val action: Action,
    /** True when a classifier service decided; false for on-phone rules, the on-device model and keywords. */
    val byProvider: Boolean,
    val decidedAt: Long,
    val costUsd: Double,
    val sender: String,
)

/** A message match from Search. */
data class SearchHit(
    val threadId: Long,
    val recipients: List<String>,
    val displayName: String,
    val body: String,
    val timestamp: Long,
)

/** Canonical form of a sender address, for sender rules. */
fun normalizeAddress(address: String): String {
    val trimmed = address.trim()
    if (trimmed.any(Char::isLetter)) return trimmed.uppercase()
    val digits = trimmed.filter(Char::isDigit)
    // Treat +1 NANP numbers and their 10-digit form as the same sender.
    return if (digits.length == 11 && digits.startsWith("1")) digits.drop(1) else digits
}

/** Route-safe encoding of a recipient list. */
fun joinAddresses(addresses: List<String>): String = addresses.joinToString(",")

fun splitAddresses(joined: String): List<String> = joined.split(',').map { it.trim() }.filter { it.isNotEmpty() }
