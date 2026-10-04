package com.ericflo.winnow.sms

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony.Mms
import android.telephony.SubscriptionManager
import com.ericflo.winnow.mms.ContentTypes
import com.ericflo.winnow.mms.MmsCharsets
import com.ericflo.winnow.mms.MmsPart
import com.ericflo.winnow.mms.NotificationInd
import com.ericflo.winnow.mms.RetrieveConf

/**
 * Writes MMS into the system store the way the platform's own persister does: a row in
 * `mms`, its parts in `part` (text inline, binary data streamed to the part's own URI), and
 * its sender and recipients in `addr`.
 */
class MmsStore(private val context: Context) {
    private val resolver = context.contentResolver

    /** A placeholder for an announced, not-yet-downloaded message ("Downloading MMS…"). */
    fun insertNotification(ind: NotificationInd, threadId: Long, subscriptionId: Int): Uri? {
        val values = baseValues(threadId, Mms.MESSAGE_BOX_INBOX, MESSAGE_TYPE_NOTIFICATION_IND, System.currentTimeMillis() / 1000, read = false, subscriptionId).apply {
            put(Mms.CONTENT_LOCATION, ind.contentLocation)
            put(Mms.TRANSACTION_ID, ind.transactionId)
            put(Mms.MESSAGE_SIZE, ind.messageSize)
            put(Mms.MESSAGE_CLASS, ind.messageClass)
            ind.expiry?.let { put(Mms.EXPIRY, it.seconds) }
            ind.subject?.let { put(Mms.SUBJECT, it); put(Mms.SUBJECT_CHARSET, MmsCharsets.UTF_8) }
        }
        val uri = resolver.insert(Mms.Inbox.CONTENT_URI, values) ?: return null
        ind.from?.let { insertAddress(uri, it, ADDR_FROM) }
        return uri
    }

    /** A downloaded incoming message. */
    fun insertIncoming(conf: RetrieveConf, threadId: Long, subscriptionId: Int): Uri? {
        val date = conf.dateSeconds ?: (System.currentTimeMillis() / 1000)
        val values = baseValues(threadId, Mms.MESSAGE_BOX_INBOX, MESSAGE_TYPE_RETRIEVE_CONF, date, read = false, subscriptionId).apply {
            put(Mms.DATE_SENT, date)
            put(Mms.CONTENT_TYPE, conf.contentType)
            conf.messageId?.let { put(Mms.MESSAGE_ID, it) }
            conf.transactionId?.let { put(Mms.TRANSACTION_ID, it) }
            conf.subject?.let { put(Mms.SUBJECT, it); put(Mms.SUBJECT_CHARSET, MmsCharsets.UTF_8) }
            put(Mms.TEXT_ONLY, if (conf.parts.all { it.isText() }) 1 else 0)
        }
        val uri = resolver.insert(Mms.Inbox.CONTENT_URI, values) ?: return null
        conf.from?.let { insertAddress(uri, it, ADDR_FROM) }
        conf.to.forEach { insertAddress(uri, it, ADDR_TO) }
        conf.cc.forEach { insertAddress(uri, it, ADDR_CC) }
        insertParts(uri, conf.parts)
        return uri
    }

    /** An outgoing message, in the outbox until [setBox] moves it to sent or failed. */
    fun insertOutgoing(threadId: Long, recipients: List<String>, parts: List<MmsPart>, subscriptionId: Int): Uri? {
        val values = baseValues(threadId, Mms.MESSAGE_BOX_OUTBOX, MESSAGE_TYPE_SEND_REQ, System.currentTimeMillis() / 1000, read = true, subscriptionId).apply {
            put(Mms.CONTENT_TYPE, ContentTypes.MULTIPART_RELATED)
            put(Mms.MESSAGE_CLASS, "personal")
            put(Mms.TEXT_ONLY, if (parts.all { it.isText() }) 1 else 0)
        }
        val uri = resolver.insert(Mms.Outbox.CONTENT_URI, values) ?: return null
        insertAddress(uri, INSERT_ADDRESS_TOKEN, ADDR_FROM)
        recipients.forEach { insertAddress(uri, it, ADDR_TO) }
        insertParts(uri, parts)
        return uri
    }

    /**
     * A message from a backup: received ([from] is the sender) or sent ([from] null), in the
     * box it was in. Restored messages count as seen, so they don't light up as new.
     */
    fun insertRestored(threadId: Long, box: Int, dateSeconds: Long, read: Boolean, subject: String?, from: String?, to: List<String>, parts: List<MmsPart>): Uri? {
        val type = if (box == Mms.MESSAGE_BOX_INBOX) MESSAGE_TYPE_RETRIEVE_CONF else MESSAGE_TYPE_SEND_REQ
        val values = baseValues(threadId, box, type, dateSeconds, read, SubscriptionManager.INVALID_SUBSCRIPTION_ID).apply {
            put(Mms.SEEN, 1)
            put(Mms.DATE_SENT, dateSeconds)
            put(Mms.CONTENT_TYPE, ContentTypes.MULTIPART_RELATED)
            put(Mms.MESSAGE_CLASS, "personal")
            put(Mms.TEXT_ONLY, if (parts.all { it.isText() }) 1 else 0)
            subject?.let { put(Mms.SUBJECT, it); put(Mms.SUBJECT_CHARSET, MmsCharsets.UTF_8) }
        }
        val uri = resolver.insert(Mms.CONTENT_URI, values) ?: return null
        insertAddress(uri, from ?: INSERT_ADDRESS_TOKEN, ADDR_FROM)
        to.forEach { insertAddress(uri, it, ADDR_TO) }
        insertParts(uri, parts)
        return uri
    }

    fun setBox(uri: Uri, box: Int, messageId: String? = null) {
        val values = ContentValues().apply {
            put(Mms.MESSAGE_BOX, box)
            messageId?.let { put(Mms.MESSAGE_ID, it) }
        }
        resolver.update(uri, values, null, null)
    }

    /**
     * Records a delivery report on the sent message the carrier named by its Message-ID: its
     * status column takes the report's X-Mms-Status (retrieved means delivered). In a group, the
     * first report to arrive. Returns whether a sent message matched.
     */
    fun markDelivered(messageId: String, status: Int): Boolean = resolver.update(
        Mms.CONTENT_URI,
        ContentValues().apply { put(Mms.STATUS, status) },
        "${Mms.MESSAGE_ID} = ? AND ${Mms.MESSAGE_BOX} = ${Mms.MESSAGE_BOX_SENT} AND (${Mms.STATUS} IS NULL OR ${Mms.STATUS} != $DELIVERED)",
        arrayOf(messageId),
    ) > 0

    /**
     * A placeholder already stored for this announcement, if the carrier is repeating itself.
     * Matched on the content location, which names one message, else the transaction ID.
     */
    fun findNotification(contentLocation: String, transactionId: String): Uri? {
        val (selection, args) = when {
            contentLocation.isNotBlank() -> "${Mms.CONTENT_LOCATION} = ?" to arrayOf(contentLocation)
            transactionId.isNotBlank() -> "${Mms.TRANSACTION_ID} = ?" to arrayOf(transactionId)
            else -> return null
        }
        return resolver.query(Mms.CONTENT_URI, arrayOf(Mms._ID), "${Mms.MESSAGE_TYPE} = $MESSAGE_TYPE_NOTIFICATION_IND AND $selection", args, null)
            ?.use { c -> if (c.moveToFirst()) android.content.ContentUris.withAppendedId(Mms.CONTENT_URI, c.getLong(0)) else null }
    }

    /** The placeholder's status column: [STATUS_DEFERRED], [STATUS_DOWNLOAD_FAILED], or null while it downloads. */
    fun status(uri: Uri): Int? =
        resolver.query(uri, arrayOf(Mms.STATUS), null, null, null)?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else null }

    /** Marks a placeholder as waiting for the user to download it. */
    fun markDeferred(uri: Uri) {
        resolver.update(uri, ContentValues().apply { put(Mms.STATUS, STATUS_DEFERRED) }, null, null)
    }

    /** Marks a placeholder as failed to download, so the UI can offer a retry. */
    fun markDownloadFailed(uri: Uri) {
        resolver.update(uri, ContentValues().apply { put(Mms.STATUS, STATUS_DOWNLOAD_FAILED) }, null, null)
    }

    fun delete(uri: Uri) {
        resolver.delete(uri, null, null)
    }

    /** What's needed to download a placeholder again. */
    fun contentLocation(mmsId: Long): String? =
        resolver.query(ContentUris.withAppendedId(Mms.CONTENT_URI, mmsId), arrayOf(Mms.CONTENT_LOCATION), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun subscriptionId(mmsId: Long): Int? =
        resolver.query(ContentUris.withAppendedId(Mms.CONTENT_URI, mmsId), arrayOf(Mms.SUBSCRIPTION_ID), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else null }

    fun threadId(mmsId: Long): Long? =
        resolver.query(ContentUris.withAppendedId(Mms.CONTENT_URI, mmsId), arrayOf(Mms.THREAD_ID), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    /** Reads a stored message's parts back, for resending. */
    fun parts(mmsId: Long): List<MmsPart> {
        val parts = mutableListOf<MmsPart>()
        resolver.query(
            Mms.Part.CONTENT_URI,
            arrayOf(Mms.Part._ID, Mms.Part.CONTENT_TYPE, Mms.Part.TEXT, Mms.Part.NAME, Mms.Part.CONTENT_ID, Mms.Part.CONTENT_LOCATION, Mms.Part.CHARSET),
            "${Mms.Part.MSG_ID} = ?", arrayOf(mmsId.toString()), "${Mms.Part.SEQ} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val type = c.getString(1).orEmpty()
                val data = if (type == ContentTypes.TEXT_PLAIN || type == ContentTypes.SMIL) {
                    c.getString(2).orEmpty().encodeToByteArray()
                } else {
                    resolver.openInputStream(ContentUris.withAppendedId(Mms.Part.CONTENT_URI, c.getLong(0)))?.use { it.readBytes() } ?: continue
                }
                parts += MmsPart(
                    contentType = type,
                    data = data,
                    name = c.getString(3),
                    contentId = c.getString(4)?.removeSurrounding("<", ">"),
                    contentLocation = c.getString(5),
                    charset = if (type.startsWith("text/")) MmsCharsets.UTF_8 else null,
                )
            }
        }
        return parts
    }

    /** A message's text parts joined, without reading any media. */
    fun text(mmsId: Long): String =
        resolver.query(
            Mms.Part.CONTENT_URI, arrayOf(Mms.Part.TEXT),
            "${Mms.Part.MSG_ID} = ? AND ${Mms.Part.CONTENT_TYPE} = 'text/plain'", arrayOf(mmsId.toString()), "${Mms.Part.SEQ} ASC",
        )?.use { c -> buildList { while (c.moveToNext()) c.getString(0)?.let(::add) } }.orEmpty().joinToString("\n")

    fun sender(mmsId: Long): String? =
        resolver.query(
            Mms.Addr.getAddrUriForMessage(mmsId.toString()), arrayOf(Mms.Addr.ADDRESS),
            "${Mms.Addr.TYPE} = $ADDR_FROM", null, null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun recipients(mmsId: Long): List<String> =
        resolver.query(
            Mms.Addr.getAddrUriForMessage(mmsId.toString()), arrayOf(Mms.Addr.ADDRESS),
            "${Mms.Addr.TYPE} = $ADDR_TO", null, null,
        )?.use { c -> buildList { while (c.moveToNext()) c.getString(0)?.let(::add) } }.orEmpty()

    private fun baseValues(threadId: Long, box: Int, type: Int, dateSeconds: Long, read: Boolean, subscriptionId: Int) = ContentValues().apply {
        put(Mms.THREAD_ID, threadId)
        put(Mms.MESSAGE_BOX, box)
        put(Mms.MESSAGE_TYPE, type)
        put(Mms.DATE, dateSeconds)
        put(Mms.READ, if (read) 1 else 0)
        put(Mms.SEEN, if (read) 1 else 0)
        put(Mms.MMS_VERSION, MMS_VERSION_1_2)
        put(Mms.LOCKED, 0)
        put(Mms.SUBSCRIPTION_ID, subscriptionId)
    }

    private fun insertAddress(message: Uri, address: String, type: Int) {
        val values = ContentValues().apply {
            put(Mms.Addr.ADDRESS, address)
            put(Mms.Addr.TYPE, type)
            put(Mms.Addr.CHARSET, MmsCharsets.UTF_8)
        }
        resolver.insert(Uri.withAppendedPath(message, "addr"), values)
    }

    private fun insertParts(message: Uri, parts: List<MmsPart>) {
        parts.forEachIndexed { index, part ->
            val values = ContentValues().apply {
                put(Mms.Part.CONTENT_TYPE, part.contentType)
                // The platform gives the SMIL root sequence -1 and media parts 0..n.
                put(Mms.Part.SEQ, if (part.contentType == ContentTypes.SMIL) -1 else index)
                part.name?.let { put(Mms.Part.NAME, it) }
                part.filename?.let { put(Mms.Part.FILENAME, it) }
                part.contentId?.let { put(Mms.Part.CONTENT_ID, "<$it>") }
                part.contentLocation?.let { put(Mms.Part.CONTENT_LOCATION, it) }
                if (part.isText()) {
                    put(Mms.Part.CHARSET, MmsCharsets.UTF_8)
                    put(Mms.Part.TEXT, part.text ?: part.data.decodeToString())
                }
            }
            val partUri = resolver.insert(Uri.withAppendedPath(message, "part"), values) ?: return@forEachIndexed
            if (!part.isText()) resolver.openOutputStream(partUri)?.use { it.write(part.data) }
        }
    }

    /** What the platform stores inline in the `text` column; everything else lives in the part's file. */
    private fun MmsPart.isText() = contentType == ContentTypes.TEXT_PLAIN || contentType == ContentTypes.SMIL

    companion object {
        const val MESSAGE_TYPE_SEND_REQ = 0x80
        const val MESSAGE_TYPE_NOTIFICATION_IND = 0x82
        const val MESSAGE_TYPE_RETRIEVE_CONF = 0x84

        const val ADDR_FROM = 0x89
        const val ADDR_TO = 0x97
        const val ADDR_CC = 0x82

        /** What the platform stores as the sender of outgoing MMS; the MMSC fills in our number. */
        const val INSERT_ADDRESS_TOKEN = "insert-address-token"

        /** X-Mms-MMS-Version 1.2 as a short integer (0x92 without the high bit). */
        private const val MMS_VERSION_1_2 = 0x12

        /** Winnow's marker in `st` for a placeholder whose download failed. */
        const val STATUS_DOWNLOAD_FAILED = 0x87
        /** PduHeaders.STATUS_DEFERRED: the recipient will fetch it later. */
        const val STATUS_DEFERRED = 0x83
        /** X-Mms-Status retrieved, on a sent message: a delivery report said it arrived. */
        const val DELIVERED = 0x81

    }
}
