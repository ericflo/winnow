package com.ericflo.winnow.data

import android.content.Context
import android.provider.Telephony
import com.ericflo.winnow.classifier.message.MessageContext

/**
 * What came before a text in its conversation (see MessageContext), read from the store: the
 * texts just before it, whose they were and when. For models that learn from a text's context;
 * the same reading when they learn and when they answer, so they see texts alike either way.
 */
class MessageContexts(private val context: Context) {
    /**
     * Before [key], sent at [sentAt], in [threadId]: the latest texts of the conversation up to
     * then, it left out. Null when the store can't say.
     */
    fun before(threadId: Long, sentAt: Long, key: String?): MessageContext? = runCatching {
        if (threadId < 0) return null
        val resolver = context.contentResolver
        val smsId = key?.let { ChatMessage.idIn(ChatMessage.Kind.SMS, it) } ?: -1L
        val mmsId = key?.let { ChatMessage.idIn(ChatMessage.Kind.MMS, it) } ?: -1L
        // When each came, and whether the user wrote it: drafts aren't texts.
        val earlier = ArrayList<Pair<Long, Boolean>>()
        resolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.DATE, Telephony.Sms.TYPE),
            "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.DATE} <= ? AND ${Telephony.Sms._ID} != ? AND ${Telephony.Sms.TYPE} != ${Telephony.Sms.MESSAGE_TYPE_DRAFT}",
            arrayOf(threadId.toString(), sentAt.toString(), smsId.toString()), "${Telephony.Sms.DATE} DESC LIMIT $LOOK_BACK",
        )?.use { c -> while (c.moveToNext()) earlier += c.getLong(0) to (c.getInt(1) != Telephony.Sms.MESSAGE_TYPE_INBOX) }
        resolver.query(
            Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX),
            "${Telephony.Mms.THREAD_ID} = ? AND ${Telephony.Mms.DATE} <= ? AND ${Telephony.Mms._ID} != ? AND ${Telephony.Mms.MESSAGE_BOX} != ${Telephony.Mms.MESSAGE_BOX_DRAFTS}",
            arrayOf(threadId.toString(), (sentAt / 1000).toString(), mmsId.toString()), "${Telephony.Mms.DATE} DESC LIMIT $LOOK_BACK",
        )?.use { c -> while (c.moveToNext()) earlier += c.getLong(0) * 1000 to (c.getInt(1) != Telephony.Mms.MESSAGE_BOX_INBOX) }
        val latest = earlier.sortedByDescending { it.first }.take(LOOK_BACK)
        val last = latest.firstOrNull()
        MessageContext(
            sentAt = sentAt,
            earlierFromThem = latest.count { !it.second }.coerceAtMost(MessageContext.CAP),
            earlierFromYou = latest.count { it.second }.coerceAtMost(MessageContext.CAP),
            answersYou = last?.second,
            sinceLastMillis = last?.let { (sentAt - it.first).coerceAtLeast(0) },
        )
    }.getOrNull()

    /**
     * The texts from them before [key] (sent at [sentAt]) in [threadId], newest first, at most
     * [limit]: what a model reads with it (see ConversationReading). Text messages (a picture
     * message's words take a query each); empty when the store can't say.
     */
    fun earlierBodies(threadId: Long, sentAt: Long, key: String?, limit: Int = 8): List<String> = runCatching {
        if (threadId < 0) return emptyList()
        val smsId = key?.let { ChatMessage.idIn(ChatMessage.Kind.SMS, it) } ?: -1L
        val out = ArrayList<String>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.BODY),
            "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.DATE} <= ? AND ${Telephony.Sms._ID} != ? AND ${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_INBOX}",
            arrayOf(threadId.toString(), sentAt.toString(), smsId.toString()), "${Telephony.Sms.DATE} DESC LIMIT $limit",
        )?.use { c -> while (c.moveToNext()) c.getString(0)?.takeIf { it.isNotBlank() }?.let(out::add) }
        out
    }.getOrDefault(emptyList())

    /**
     * A conversation's latest [limit] texts from them (text messages; a picture message's words
     * take a query each), newest first, each with its context read from the same texts: what came
     * before it among the latest, so counts near the oldest are short of the whole conversation's.
     */
    fun latestFromThem(threadId: Long, limit: Int): List<Pair<MessageTexts.Text, MessageContext>> = runCatching {
        class Row(val id: Long, val address: String?, val body: String, val date: Long, val fromYou: Boolean)
        val rows = ArrayList<Row>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
            "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.TYPE} != ${Telephony.Sms.MESSAGE_TYPE_DRAFT}",
            arrayOf(threadId.toString()), "${Telephony.Sms.DATE} DESC LIMIT ${limit * 3}",
        )?.use { c -> while (c.moveToNext()) rows += Row(c.getLong(0), c.getString(1), c.getString(2).orEmpty(), c.getLong(3), c.getInt(4) != Telephony.Sms.MESSAGE_TYPE_INBOX) }
        rows.withIndex().filter { (_, r) -> !r.fromYou && r.body.isNotBlank() }.take(limit).map { (i, r) ->
            val earlier = rows.drop(i + 1).take(LOOK_BACK)
            val last = earlier.firstOrNull()
            MessageTexts.Text(ChatMessage.messageKey(ChatMessage.Kind.SMS, r.id), threadId, r.address, r.body, r.date) to MessageContext(
                sentAt = r.date,
                earlierFromThem = earlier.count { !it.fromYou }.coerceAtMost(MessageContext.CAP),
                earlierFromYou = earlier.count { it.fromYou }.coerceAtMost(MessageContext.CAP),
                answersYou = last?.fromYou,
                sinceLastMillis = last?.let { (r.date - it.date).coerceAtLeast(0) },
            )
        }
    }.getOrDefault(emptyList())

    private companion object {
        /** Enough of the latest texts to count up to MessageContext.CAP each way. */
        const val LOOK_BACK = 2 * MessageContext.CAP
    }
}
