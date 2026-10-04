package com.ericflo.winnow.data

import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.classifier.message.FilteredPhrases

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
    /** The newest message is one of the user's that didn't go out. */
    val notSent: Boolean = false,
    /** For a group, the first two people in it, for its avatar; empty otherwise. */
    val members: List<Member> = emptyList(),
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
    /** The SIM it was sent or received on, where the store records one. */
    val subscriptionId: Int? = null,
    val starred: Boolean = false,
    /** For an MMS not downloaded yet, its size as the carrier announced it, in bytes. */
    val downloadSize: Long = 0,
) {
    enum class Status {
        RECEIVED, SENDING, SENT, DELIVERED, FAILED, DOWNLOADING, DOWNLOAD_FAILED,
        /** Announced but left for the user to download (auto-download is off, or roaming). */
        NOT_DOWNLOADED,
    }

    /** An MMS still on the carrier's server: no content yet, only a way to fetch it. */
    val isPlaceholder: Boolean get() = status == Status.DOWNLOADING || status == Status.DOWNLOAD_FAILED || status == Status.NOT_DOWNLOADED

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
    val isAudio: Boolean get() = contentType.startsWith("audio/")
    val isVideo: Boolean get() = contentType.startsWith("video/")
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

    /** What decided it, in a word or two: its category, or the kind of rule. */
    val label: String
        get() = category?.label ?: if (source.startsWith(FilteredPhrases.REASON_PREFIX)) "Filtered word" else "Sender rule"

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

/** A starred message with enough about its conversation to show it in the Starred list. */
data class StarredMessage(val message: ChatMessage, val recipients: List<String>, val conversationName: String)

/** A message match from Search. */
/** A photo or video from some conversation, for browsing them all at once. */
data class MediaHit(val attachment: Attachment, val threadId: Long, val recipients: List<String>, val displayName: String, val timestamp: Long, val key: String)

data class SearchHit(
    val threadId: Long,
    val recipients: List<String>,
    val displayName: String,
    val body: String,
    val timestamp: Long,
    /** The message's [ChatMessage.key], so opening the hit can show that message. */
    val key: String? = null,
    /** The person's photo, for a 1:1 conversation. */
    val photoUri: String? = null,
    /** A group's two faces (see groupFaces). */
    val members: List<Member> = emptyList(),
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

/** A preview for a message with attachments and no text: "Photo", "Contact", "2 videos", "3 attachments". */
fun attachmentSummary(contentTypes: List<String>): String {
    fun kind(type: String) = when {
        type.startsWith("image/") -> "Photo" to "photos"
        type.startsWith("video/") -> "Video" to "videos"
        type.startsWith("audio/") -> "Voice message" to "voice messages"
        VCard.isVCard(type) -> "Contact" to "contacts"
        else -> "Attachment" to "attachments"
    }
    val kinds = contentTypes.map(::kind).distinct()
    return when {
        contentTypes.isEmpty() -> "Attachment"
        contentTypes.size == 1 -> kinds.single().first
        kinds.size == 1 -> "${contentTypes.size} ${kinds.single().second}"
        else -> "${contentTypes.size} attachments"
    }
}

/**
 * An MMS subject worth showing: null for none, and for the placeholders some phones fill in
 * ("NoSubject", "<no subject>").
 */
fun meaningfulSubject(subject: String?): String? {
    val trimmed = subject?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val bare = trimmed.removeSurrounding("<", ">").replace(" ", "").lowercase()
    return trimmed.takeIf { bare != "nosubject" && bare != "subject" }
}

/**
 * What an MMS says in words: its subject (if it has a real one) over its text. What's classified
 * and previewed, so a message carried in the subject line is seen like any other.
 */
fun subjectAndText(subject: String?, text: String): String =
    listOfNotNull(meaningfulSubject(subject), text.takeIf { it.isNotBlank() }).joinToString("\n")

/**
 * [text] for a search result: on one line, and starting a little before [query]'s first match
 * (at a word, after "…") when the match is too far in for a two-line preview to show it.
 */
fun searchSnippet(text: String, query: String, lead: Int = 24): String {
    val flat = text.replace(Regex("\\s*\n\\s*"), " ")
    val wanted = query.trim()
    val at = if (wanted.isEmpty()) -1 else flat.indexOf(wanted, ignoreCase = true)
    if (at <= lead + 16) return flat
    val from = at - lead
    // From the start of the next word, so it never opens on half of one.
    val space = flat.indexOf(' ', from)
    val start = if (space in from until at) space + 1 else from
    return "…" + flat.substring(start)
}

/** One person in a group, as its avatar shows them. */
data class Member(val address: String, val name: String, val photoUri: String?)

/** Whether an avatar for [name] shows its initial; a bare number gets a plain person glyph. */
fun showsInitial(name: String): Boolean = name.firstOrNull()?.isLetter() == true

/**
 * The two faces a group's avatar shows, front one first: people with a photo, then people with
 * a name (an initial), so it isn't two blank glyphs while a friend is in the group. Ties go by
 * number, not by the order they came in (a notification lists the sender first), so the list,
 * the conversation, its details and its notification icon all agree.
 */
fun groupFaces(members: List<Member>): List<Member> =
    members.sortedWith(
        compareByDescending<Member> { if (it.photoUri != null) 2 else if (showsInitial(it.name)) 1 else 0 }
            .thenBy { normalizeAddress(it.address) },
    ).take(2)

/** At most this many of a group's people are looked at for its avatar, so a huge group doesn't cost a lookup each. */
const val GROUP_FACE_CANDIDATES = 12
