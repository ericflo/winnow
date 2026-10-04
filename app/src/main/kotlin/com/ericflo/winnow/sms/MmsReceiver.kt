package com.ericflo.winnow.sms

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import com.ericflo.winnow.WinnowApp
import com.ericflo.winnow.classify.IncomingMessageHandler
import com.ericflo.winnow.data.OwnNumbers
import com.ericflo.winnow.data.normalizeAddress
import com.ericflo.winnow.mms.ContentTypes
import com.ericflo.winnow.mms.MmsPduException
import com.ericflo.winnow.mms.DeliveryInd
import com.ericflo.winnow.mms.NotificationInd
import com.ericflo.winnow.mms.NotifyRespInd
import com.ericflo.winnow.mms.PduComposer
import com.ericflo.winnow.mms.PduParser
import com.ericflo.winnow.mms.RetrieveConf
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import com.ericflo.winnow.mms.AcknowledgeInd
import com.ericflo.winnow.mms.MmsPdu
import com.ericflo.winnow.mms.MmsStatus

/**
 * Incoming MMS: a WAP push announces a message, the system MMS service downloads it, and the
 * result is stored, acknowledged, and handed to [IncomingMessageHandler] like any SMS.
 */
class MmsReceiver(
    private val context: Context,
    private val store: MmsStore,
    private val files: MmsFiles,
    private val ownNumbers: OwnNumbers,
    private val incoming: IncomingMessageHandler,
    /** Whether to fetch an announced MMS right away on this SIM: the auto-download settings, and roaming. */
    private val autoDownload: suspend (subscriptionId: Int) -> Boolean = { true },
    /** Fetches failed downloads again by themselves; null for none. */
    private val retries: MmsRetries? = null,
) {
    /** [placeholder]'s download failed: say so in the conversation, and try again later. */
    private fun failed(placeholder: Uri) {
        store.markDownloadFailed(placeholder)
        placeholder.lastPathSegment?.toLongOrNull()?.let { retries?.failed(it) }
    }

    /** A WAP push carrying an m-notification-ind. */
    suspend fun onPush(pdu: ByteArray, subscriptionId: Int) {
        val parsed = try {
            PduParser.parse(pdu)
        } catch (e: MmsPduException) {
            Log.w(TAG, "Unreadable MMS notification", e)
            null
        }
        // A delivery report for an MMS Winnow sent (asked for when delivery reports are on).
        if (parsed is DeliveryInd) {
            if (!store.markDelivered(parsed.messageId, parsed.status, subscriptionId)) Log.i(TAG, "Delivery report for a message that isn't here")
            return
        }
        val ind = parsed as? NotificationInd ?: return
        // Carriers announce again when they think the first went unanswered.
        store.findNotification(ind.contentLocation, ind.transactionId)?.let { existing ->
            Log.i(TAG, "Repeated MMS notification ${ind.transactionId}; already have it")
            if (store.status(existing) == MmsStore.STATUS_DEFERRED) acknowledge(NotifyRespInd(ind.transactionId, status = MmsStatus.DEFERRED), subscriptionId)
            return
        }
        val threadId = Telephony.Threads.getOrCreateThreadId(context, setOf(ind.from ?: UNKNOWN_SENDER))
        val placeholder = store.insertNotification(ind, threadId, subscriptionId) ?: run {
            Log.e(TAG, "Couldn't store MMS notification; is Winnow the default SMS app?")
            return
        }
        if (!autoDownload(subscriptionId)) {
            store.markDeferred(placeholder)
            // Tells the carrier it'll be fetched later, so it isn't announced again.
            acknowledge(NotifyRespInd(ind.transactionId, status = MmsStatus.DEFERRED), subscriptionId)
            incoming.onMmsDeferred(placeholder, threadId, ind.from ?: UNKNOWN_SENDER, ind.messageSize)
            return
        }
        try {
            download(placeholder, ind.contentLocation, ind.transactionId, subscriptionId)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't start the MMS download", e)
            failed(placeholder)
        }
    }

    /** Downloads a placeholder again after a failure. */
    fun retryDownload(mmsId: Long) {
        val placeholder = ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, mmsId)
        val location = store.contentLocation(mmsId) ?: return
        // Retry on the SIM the message arrived on, not whichever is the default now.
        val (transactionId, subscriptionId) = context.contentResolver.query(
            placeholder, arrayOf(Telephony.Mms.TRANSACTION_ID, Telephony.Mms.SUBSCRIPTION_ID), null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0).orEmpty() to c.getInt(1) else null }
            ?: ("" to SubscriptionManager.getDefaultSmsSubscriptionId())
        val deferred = store.status(placeholder) == MmsStore.STATUS_DEFERRED
        context.contentResolver.update(placeholder, ContentValues().apply { putNull(Telephony.Mms.STATUS) }, null, null)
        try {
            download(placeholder, location, transactionId, subscriptionId, deferred)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't restart the MMS download", e)
            failed(placeholder)
        }
    }

    private fun download(placeholder: Uri, location: String, transactionId: String, subscriptionId: Int, deferred: Boolean = false) {
        val file = files.newFile("retrieve")
        val done = PendingIntent.getBroadcast(
            context, placeholder.lastPathSegment?.toIntOrNull() ?: 0,
            Intent(context, MmsDownloadedReceiver::class.java)
                .setData(placeholder)
                .putExtra(EXTRA_FILE, file.path)
                .putExtra(EXTRA_TRANSACTION_ID, transactionId)
                .putExtra(EXTRA_SUBSCRIPTION, subscriptionId)
                .putExtra(EXTRA_DEFERRED, deferred),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        smsManager(subscriptionId).downloadMultimediaMessage(context, location, files.uriFor(file), null, done)
    }

    /**
     * A downloaded m-retrieve-conf. [placeholder] is replaced by the real message; with
     * [acknowledge], the carrier is told the message arrived so it stops re-announcing it.
     */
    suspend fun onDownloaded(
        placeholder: Uri?,
        pdu: ByteArray?,
        transactionId: String?,
        subscriptionId: Int,
        acknowledge: Boolean = true,
        /** Fetched after a deferred notification, which the spec confirms with m-acknowledge-ind instead. */
        deferred: Boolean = false,
    ) {
        val conf = pdu?.let {
            try {
                PduParser.parse(it) as? RetrieveConf
            } catch (e: MmsPduException) {
                Log.w(TAG, "Unreadable downloaded MMS", e)
                null
            }
        }
        if (conf == null) {
            placeholder?.let(::failed)
            return
        }
        val recipients = participants(conf)
        val threadId = Telephony.Threads.getOrCreateThreadId(context, recipients.toSet())
        val message = store.insertIncoming(conf, threadId, subscriptionId) ?: run {
            placeholder?.let(::failed)
            return
        }
        placeholder?.let(store::delete)
        placeholder?.lastPathSegment?.toLongOrNull()?.let { retries?.done(it) }
        if (acknowledge) transactionId?.takeIf { it.isNotBlank() }?.let {
            acknowledge(if (deferred) AcknowledgeInd(it) else NotifyRespInd(it), subscriptionId)
        }
        val text = conf.parts.filter { it.contentType == ContentTypes.TEXT_PLAIN }.mapNotNull { it.text }.joinToString("\n")
        // Contacts are text/x-vcard, so "everything but the text and the layout", not "not text/".
        val media = conf.parts.map { it.contentType }.filter { it != ContentTypes.TEXT_PLAIN && it != ContentTypes.SMIL }
        incoming.onMmsStored(message, threadId, conf.from ?: UNKNOWN_SENDER, recipients, text, media, subject = conf.subject)
    }

    /**
     * Everyone in the conversation except this phone. Without our own number, a 1:1 MMS
     * (one To, no Cc) is still recognized; for groups we may include ourselves.
     */
    private fun participants(conf: RetrieveConf): List<String> {
        val me = ownNumbers.all()
        val everyone = (listOfNotNull(conf.from) + conf.to + conf.cc).filter { it.isNotBlank() }.distinctBy(::normalizeAddress)
        val others = when {
            me.isNotEmpty() -> everyone.filterNot { normalizeAddress(it) in me }
            conf.to.size == 1 && conf.cc.isEmpty() -> listOfNotNull(conf.from)
            else -> everyone
        }
        return others.ifEmpty { listOfNotNull(conf.from).ifEmpty { listOf(UNKNOWN_SENDER) } }
    }

    private fun acknowledge(pdu: MmsPdu, subscriptionId: Int) {
        val file = files.write("ack", PduComposer.compose(pdu))
        runCatching { smsManager(subscriptionId).sendMultimediaMessage(context, files.uriFor(file), null, null, null) }
            .onFailure { Log.w(TAG, "Couldn't acknowledge MMS ${pdu.transactionId}", it) }
    }

    private fun smsManager(subscriptionId: Int): SmsManager =
        context.getSystemService(SmsManager::class.java).let { if (subscriptionId >= 0) it.createForSubscriptionId(subscriptionId) else it }

    companion object {
        const val EXTRA_FILE = "pdu_file"
        const val EXTRA_TRANSACTION_ID = "transaction_id"
        const val EXTRA_SUBSCRIPTION = "subscription"
        const val EXTRA_DEFERRED = "deferred"
        private const val UNKNOWN_SENDER = "Unknown"
        private const val TAG = "WinnowMms"
    }
}

/** The system MMS service finished (or failed) a download. */
class MmsDownloadedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as WinnowApp).container
        val file = intent.getStringExtra(MmsReceiver.EXTRA_FILE)?.let(::File)
        val ok = resultCode == Activity.RESULT_OK
        val pending = goAsync()
        container.appScope.launch {
            try {
                val bytes = if (ok) file?.takeIf { it.exists() }?.readBytes() else null
                container.mmsReceiver.onDownloaded(
                    placeholder = intent.data,
                    pdu = bytes,
                    transactionId = intent.getStringExtra(MmsReceiver.EXTRA_TRANSACTION_ID),
                    subscriptionId = intent.getIntExtra(MmsReceiver.EXTRA_SUBSCRIPTION, -1),
                    deferred = intent.getBooleanExtra(MmsReceiver.EXTRA_DEFERRED, false),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("WinnowMms", "Handling downloaded MMS failed", e)
            } finally {
                file?.delete()
                pending.finish()
            }
        }
    }
}
