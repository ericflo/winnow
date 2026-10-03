package com.ericflo.winnow.data

import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.classifier.message.Category

data class ConversationSummary(
    val threadId: Long,
    val address: String,
    val displayName: String,
    val snippet: String,
    val timestamp: Long,
    val unreadCount: Int,
    /** Verdict on the newest incoming message, which decides where the conversation is shown. */
    val verdict: StoredVerdict?,
) {
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
) {
    enum class Status { RECEIVED, SENDING, SENT, FAILED }
}

data class StoredVerdict(
    val category: Category?,
    val confidence: Double,
    val action: Action,
    /** Human-readable origin, e.g. "Jev (TypeSafe)" or "Sender is in your contacts". */
    val source: String,
    /** The user's correction, which wins over [action]. */
    val userAction: Action? = null,
) {
    val effectiveAction: Action get() = userAction ?: action
}

/** Canonical form of a sender address, for sender rules. */
fun normalizeAddress(address: String): String {
    val trimmed = address.trim()
    if (trimmed.any(Char::isLetter)) return trimmed.uppercase()
    val digits = trimmed.filter(Char::isDigit)
    // Treat +1 NANP numbers and their 10-digit form as the same sender.
    return if (digits.length == 11 && digits.startsWith("1")) digits.drop(1) else digits
}
