package com.ericflo.winnow.data

import android.content.Context
import android.provider.Telephony

/**
 * The words of particular messages, by key, read from the phone's message store a chunk at a
 * time: what Winnow shows beside a run's answers and an evaluation's items, which keep only keys.
 * A message deleted since is simply missing.
 */
class MessageTexts(private val context: Context) {
    /** One message: its conversation, who sent it (null for the user's own), what it said, and when. */
    data class Text(val key: String, val threadId: Long, val address: String?, val body: String, val date: Long)

    fun of(keys: Collection<String>): Map<String, Text> {
        val out = HashMap<String, Text>(keys.size * 2)
        val sms = keys.mapNotNull { ChatMessage.idIn(ChatMessage.Kind.SMS, it) }
        val mms = keys.mapNotNull { ChatMessage.idIn(ChatMessage.Kind.MMS, it) }
        sms.distinct().chunked(CHUNK).forEach { ids ->
            runCatching {
                context.contentResolver.query(
                    Telephony.Sms.CONTENT_URI,
                    arrayOf(Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
                    "${Telephony.Sms._ID} IN (${ids.joinToString(",")})", null, null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val key = ChatMessage.messageKey(ChatMessage.Kind.SMS, c.getLong(0))
                        val incoming = c.getInt(5) == Telephony.Sms.MESSAGE_TYPE_INBOX
                        out[key] = Text(key, c.getLong(1), c.getString(2).takeIf { incoming }, c.getString(3).orEmpty(), c.getLong(4))
                    }
                }
            }
        }
        // Picture messages: a subject and text parts. Few are labeled, so their senders are looked up one by one.
        mms.distinct().take(MAX_MMS).chunked(CHUNK).forEach { ids ->
            runCatching {
                val heads = HashMap<Long, Triple<Long, String?, Long>>()
                context.contentResolver.query(
                    Telephony.Mms.CONTENT_URI,
                    arrayOf(Telephony.Mms._ID, Telephony.Mms.THREAD_ID, Telephony.Mms.SUBJECT, Telephony.Mms.DATE),
                    "${Telephony.Mms._ID} IN (${ids.joinToString(",")})", null, null,
                )?.use { c -> while (c.moveToNext()) heads[c.getLong(0)] = Triple(c.getLong(1), c.getString(2), c.getLong(3) * 1000) }
                val words = HashMap<Long, StringBuilder>()
                context.contentResolver.query(
                    Telephony.Mms.Part.CONTENT_URI,
                    arrayOf(Telephony.Mms.Part.MSG_ID, Telephony.Mms.Part.TEXT),
                    "${Telephony.Mms.Part.MSG_ID} IN (${ids.joinToString(",")}) AND ${Telephony.Mms.Part.CONTENT_TYPE} = 'text/plain'", null, null,
                )?.use { c -> while (c.moveToNext()) words.getOrPut(c.getLong(0)) { StringBuilder() }.apply { if (isNotEmpty()) append('\n') }.append(c.getString(1).orEmpty()) }
                for ((id, head) in heads) {
                    val key = ChatMessage.messageKey(ChatMessage.Kind.MMS, id)
                    val from = context.contentResolver.query(
                        Telephony.Mms.Addr.getAddrUriForMessage(id.toString()), arrayOf(Telephony.Mms.Addr.ADDRESS),
                        "${Telephony.Mms.Addr.TYPE} = $ADDR_TYPE_FROM", null, null,
                    )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    val body = subjectAndText(head.second, words[id]?.toString().orEmpty()).ifBlank { "[photo]" }
                    out[key] = Text(key, head.first, from?.takeIf { it != "insert-address-token" }, body, head.third)
                }
            }
        }
        return out
    }

    private companion object {
        const val CHUNK = 500
        /** Picture messages looked up at most: each takes a lookup of its own for its sender. */
        const val MAX_MMS = 2_000
        /** PduHeaders.FROM, as stored in the MMS addr table. */
        const val ADDR_TYPE_FROM = 137
    }
}
