package com.ericflo.winnow.backup

/**
 * Which of a conversation's backed-up [messages] are on the phone already. Pure, so it's
 * unit-tested.
 *
 * A message kept by Recently deleted ([MessageBackup.was]) is here only if that very row is
 * ([identity] gives its key, else null). Any other is here if a message in the thread has its
 * [MessageBackup.fingerprint] ([existing]: keys by fingerprint), or (a text) one in any
 * conversation does ([elsewhere]: its key and thread).
 *
 * Returns the ones present, each with the key and thread of the message that is it, and the missing.
 */
internal fun matchExisting(
    messages: List<MessageBackup>,
    threadId: Long,
    existing: Map<String, List<String>>,
    identity: (MessageBackup) -> String?,
    elsewhere: (MessageBackup) -> Pair<String, Long>?,
): Pair<List<Pair<MessageBackup, Pair<String, Long>>>, List<MessageBackup>> {
    val here = mutableListOf<Pair<MessageBackup, Pair<String, Long>>>()
    val missing = mutableListOf<MessageBackup>()
    for (m in messages) {
        val at = if (m.was != null) {
            identity(m)?.let { it to threadId }
        } else {
            existing[m.fingerprint]?.firstOrNull()?.let { it to threadId } ?: elsewhere(m)
        }
        if (at != null) here += m to at else missing += m
    }
    return here to missing
}
