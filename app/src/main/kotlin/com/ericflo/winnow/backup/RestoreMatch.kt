package com.ericflo.winnow.backup

/**
 * Which of a conversation's backed-up [messages] are on the phone already. Pure, so it's
 * unit-tested.
 *
 * Each message in the thread ([existing], keys by [MessageBackup.fingerprint]) stands for one in
 * the backup, counted: two lookalikes in the backup and one here means one is missing. Those that
 * were beside a message all along ([MessageBackup.alongside], from Recently deleted) don't stand
 * for it at all. A text found in another conversation ([elsewhere]: its key and thread) counts too.
 *
 * Returns the ones present, each with the key and thread of the message that is it, and the missing.
 */
internal fun matchExisting(
    messages: List<MessageBackup>,
    threadId: Long,
    existing: Map<String, List<String>>,
    elsewhere: (MessageBackup) -> Pair<String, Long>?,
): Pair<List<Pair<MessageBackup, Pair<String, Long>>>, List<MessageBackup>> {
    val claimed = HashMap<String, Int>()
    val here = mutableListOf<Pair<MessageBackup, Pair<String, Long>>>()
    val missing = mutableListOf<MessageBackup>()
    for (m in messages) {
        val keys = existing[m.fingerprint].orEmpty()
        val used = claimed[m.fingerprint] ?: 0
        val other = elsewhere(m)?.takeIf { (_, inThread) -> inThread != threadId && m.alongside == 0 }
        when {
            keys.size - m.alongside - used > 0 -> {
                claimed[m.fingerprint] = used + 1
                here += m to (keys[m.alongside + used] to threadId)
            }
            other != null -> here += m to other
            else -> missing += m
        }
    }
    return here to missing
}
