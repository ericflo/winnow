package com.ericflo.winnow.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reply reminders (Settings → Messages), as Messages calls nudges: a contact's question you
 * haven't answered in a couple of days, or yours they haven't, comes back to the top of the inbox
 * with "Reply?" or "Follow up?" until it's answered or dismissed.
 */
object Nudge {
    enum class Kind { REPLY, FOLLOW_UP, BIRTHDAY }

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
    fun of(conversation: ConversationSummary, now: Long, isContact: Boolean, birthday: Boolean = false, startOfToday: Long = 0): Kind? {
        if (!isContact || conversation.isGroup || conversation.archived || conversation.isFiltered || conversation.muted) return null
        // Their birthday, unless the user has texted them today already.
        if (birthday && !(conversation.lastFromMe && conversation.timestamp >= startOfToday)) return Kind.BIRTHDAY
        if (conversation.draft != null || conversation.notSent || !conversation.lastAsks) return null
        val age = now - conversation.timestamp
        if (age < AFTER_MILLIS || age > UNTIL_MILLIS) return null
        return if (conversation.lastFromMe) Kind.FOLLOW_UP else Kind.REPLY
    }

    /**
     * What dismissing [conversation]'s [kind] of nudge is remembered by: the conversation and its
     * newest message's time, or for a birthday, the day.
     */
    fun key(conversation: ConversationSummary, kind: Kind, startOfToday: Long): String =
        if (kind == Kind.BIRTHDAY) "${conversation.threadId}:$startOfToday:birthday" else "${conversation.threadId}:${conversation.timestamp}"
}

/**
 * Nudges the user dismissed ("Not now"), each for the message it was about; a newer one nudges
 * again. Read from disk off the main thread: the inbox opens without waiting on it, and
 * [keys] first emits once they're loaded, so a dismissed nudge never flashes back.
 */
class DismissedNudges(context: Context, private val scope: CoroutineScope) {
    private val prefs = scope.async(Dispatchers.IO) { context.getSharedPreferences("nudges", Context.MODE_PRIVATE) }
    private val _keys = MutableStateFlow<Set<String>?>(null)
    val keys: Flow<Set<String>> = _keys.filterNotNull()
    private val lock = Mutex()

    init {
        scope.launch(Dispatchers.IO) {
            val loaded = prefs.await().getStringSet(KEY, emptySet()).orEmpty().toSet()
            lock.withLock { if (_keys.value == null) _keys.value = loaded }
        }
    }

    fun dismiss(key: String) {
        scope.launch(Dispatchers.IO) {
            val store = prefs.await()
            lock.withLock {
                // Ones about messages too old to nudge any more go, so this stays small.
                val cutoff = System.currentTimeMillis() - Nudge.UNTIL_MILLIS
                val current = _keys.value ?: store.getStringSet(KEY, emptySet()).orEmpty().toSet()
                val kept = current.filter { (it.substringAfter(':').substringBefore(':').toLongOrNull() ?: 0) >= cutoff }.toSet() + key
                store.edit().putStringSet(KEY, kept).apply()
                _keys.value = kept
            }
        }
    }

    private companion object {
        const val KEY = "dismissed"
    }
}
