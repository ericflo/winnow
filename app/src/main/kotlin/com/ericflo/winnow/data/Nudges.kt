package com.ericflo.winnow.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reply reminders (Settings → Messages), as Messages calls nudges: a contact's question you
 * haven't answered in a couple of days, or yours they haven't, comes back to the top of the inbox
 * with "Reply?" or "Follow up?" until it's answered or dismissed.
 */
object Nudge {
    enum class Kind { REPLY, FOLLOW_UP }

    /** How long a question waits before it's a nudge, and how long it stays one. */
    const val AFTER_MILLIS = 2 * 24 * 60 * 60_000L
    const val UNTIL_MILLIS = 14 * 24 * 60 * 60_000L

    private val LINK = Regex("""https?://\S+|www\.\S+""", RegexOption.IGNORE_CASE)

    /** Whether [text] asks something: a question mark anywhere but in a link. */
    fun asks(text: String): Boolean = LINK.replace(text, " ").contains('?')

    /**
     * [conversation]'s nudge at [now], if it has one: a 1:1 conversation with a contact, in the
     * inbox, whose newest message is a question (see [ConversationSummary.lastAsks]) old enough to
     * be waiting and not so old it's moot. Not while there's a draft (the reply is underway), a
     * failed send (that says so already) or a mute.
     */
    fun of(conversation: ConversationSummary, now: Long, isContact: Boolean): Kind? {
        if (!isContact || conversation.isGroup || conversation.archived || conversation.isFiltered || conversation.muted) return null
        if (conversation.draft != null || conversation.notSent || !conversation.lastAsks) return null
        val age = now - conversation.timestamp
        if (age < AFTER_MILLIS || age > UNTIL_MILLIS) return null
        return if (conversation.lastFromMe) Kind.FOLLOW_UP else Kind.REPLY
    }

    /** What dismissing [conversation]'s nudge is remembered by: the conversation and its newest message's time. */
    fun key(conversation: ConversationSummary): String = "${conversation.threadId}:${conversation.timestamp}"
}

/** Nudges the user dismissed ("Not now"), each for the message it was about; a newer one nudges again. */
class DismissedNudges(context: Context) {
    private val prefs = context.getSharedPreferences("nudges", Context.MODE_PRIVATE)
    private val _keys = MutableStateFlow(prefs.getStringSet(KEY, emptySet()).orEmpty().toSet())
    val keys: StateFlow<Set<String>> = _keys.asStateFlow()

    @Synchronized
    fun dismiss(key: String) {
        // Ones about messages too old to nudge any more go, so this stays small.
        val cutoff = System.currentTimeMillis() - Nudge.UNTIL_MILLIS
        val kept = _keys.value.filter { (it.substringAfter(':').toLongOrNull() ?: 0) >= cutoff }.toSet() + key
        prefs.edit().putStringSet(KEY, kept).apply()
        _keys.value = kept
    }

    private companion object {
        const val KEY = "dismissed"
    }
}
