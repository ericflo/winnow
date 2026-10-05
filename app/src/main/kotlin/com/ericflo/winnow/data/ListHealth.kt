package com.ericflo.winnow.data

/**
 * How the last listing of conversations went, step by step, so an empty inbox can say why it's
 * empty instead of looking like there's nothing on the phone.
 */
data class ListHealth(
    val at: Long,
    /** Conversations the phone's store has, and how many of those it named anyone in. */
    val threads: Int,
    val threadsWithPeople: Int,
    /** Conversations with a newest message found. */
    val withMessages: Int,
    /** Of those, ones the store named no one in, found from their own messages instead, and ones still not. */
    val recovered: Int,
    val withoutPeople: Int,
    /** Conversations listed in the end. */
    val listed: Int,
    /** Steps that failed, in words ("conversation list: SecurityException: …"). */
    val failures: List<String> = emptyList(),
)

/** What the phone's message store holds, counted directly: -1 where it couldn't be counted. */
data class StoreCounts(val sms: Int, val mms: Int, val threads: Int) {
    val texts: Int get() = sms.coerceAtLeast(0) + mms.coerceAtLeast(0)
    val unreadable: Boolean get() = sms < 0 && mms < 0
}
