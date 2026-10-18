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
import com.ericflo.winnow.data.threadRecipients
import com.ericflo.winnow.mms.ContentTypes
import com.ericflo.winnow.mms.MmsPduException
import com.ericflo.winnow.mms.DeliveryInd
import com.ericflo.winnow.mms.NotificationInd
import com.ericflo.winnow.mms.NotifyRespInd
import com.ericflo.winnow.mms.PduComposer
import com.ericflo.winnow.mms.PduParser
import com.ericflo.winnow.mms.RetrieveConf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
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
    /** Why each failed (see MmsFailures); null keeps no record. */
    private val failures: MmsFailures? = null,
    /** Told of a failure worth reporting (the problem log): its details, never the message's. */
    private val report: (String) -> Unit = {},
) {
    private val downloadResults = Mutex()
    private val diagnostics = MmsDownloadDiagnostics(context)
    /**
     * [placeholder]'s download failed, at [stage], because [why]: say so in the conversation (with
     * why), keep it for the problem report, and try again later.
     */
    private fun failed(placeholder: Uri, stage: String, why: String, subscriptionId: Int = -1, requestDetails: String? = null, attemptedLocation: String? = null) {
        store.markDownloadFailed(placeholder)
        val id = placeholder.lastPathSegment?.toLongOrNull()
        Log.w(TAG, "MMS download failed ($stage): $why")
        // A failed request in a new version or at a new URL needs a fresh report even if the
        // carrier's error is unchanged. Otherwise an upgrade silently keeps the old evidence.
        val reportKey = "${runCatching { diagnostics.version }.getOrDefault("?")}:$why:${MmsDownloadReport.fingerprint(attemptedLocation.orEmpty())}"
        if (id != null && failures?.record(id, why, reportKey) == true) {
            val (size, server) = context.contentResolver.query(placeholder, arrayOf(Telephony.Mms.MESSAGE_SIZE, Telephony.Mms.CONTENT_LOCATION), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getLong(0) to Uri.parse(c.getString(1).orEmpty()).host.orEmpty() else null } ?: (0L to "")
            runCatching {
                report(
                    "Stage: $stage\nWhy: $why\nDefault network (not necessarily the MMS network): ${failures?.network(subscriptionId)}\nSIM subscription: $subscriptionId\n" +
                        "Announced size: ${size / 1000} KB\nStored MMS server: ${server.ifBlank { "?" }}\n" +
                        (requestDetails ?: "Request snapshot unavailable (failed before the request or callback from an older version)."),
                )
            }
        }
        id?.let { retries?.failed(it) }
    }

    /** A WAP push carrying an m-notification-ind. */
    suspend fun onPush(pdu: ByteArray, subscriptionId: Int) = downloadResults.withLock {
        handlePush(pdu, subscriptionId)
    }

    private suspend fun handlePush(pdu: ByteArray, subscriptionId: Int) {
        if (!isDefaultSmsApp()) return
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
        val notification = parsed as? NotificationInd ?: return
        // SmsManager downloads exactly the URL we supply. On carriers such as Verizon the
        // notification's URL can be incomplete until the transaction ID has been appended.
        val ind = notification.copy(contentLocation = downloadLocation(notification.contentLocation, notification.transactionId, subscriptionId))
        // Carriers announce again when they think the first went unanswered.
        store.findNotification(ind.contentLocation, ind.transactionId, subscriptionId)?.let { existing ->
            val status = store.status(existing)
            val changed = store.refreshNotification(existing, ind)
            Log.i(TAG, "Repeated MMS notification; location changed=$changed")
            if (status == MmsStore.STATUS_DEFERRED) {
                acknowledge(NotifyRespInd(ind.transactionId, status = MmsStatus.DEFERRED), subscriptionId, ind.contentLocation)
            } else if ((changed || status == MmsStore.STATUS_DOWNLOAD_FAILED) && autoDownload(subscriptionId)) {
                // A renewed location must replace an old in-flight request as well. Its late
                // failure cannot undo the newer attempt (onDownloaded checks attempt identity).
                val id = ContentUris.parseId(existing)
                if (changed) retries?.done(id)
                retryDownload(id, "carrier re-announcement", notification.contentLocation)
            }
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
            acknowledge(NotifyRespInd(ind.transactionId, status = MmsStatus.DEFERRED), subscriptionId, ind.contentLocation)
            incoming.onMmsDeferred(placeholder, threadId, ind.from ?: UNKNOWN_SENDER, ind.messageSize)
            return
        }
        try {
            download(placeholder, ind.contentLocation, ind.transactionId, subscriptionId, source = "new notification", announcedLocation = notification.contentLocation)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't start the MMS download", e)
            failed(placeholder, "starting the download", "Winnow couldn't start it: ${e.message ?: e::class.simpleName}", subscriptionId)
        }
    }

    /** Downloads a placeholder again after a failure. */
    fun retryDownload(mmsId: Long, source: String = "manual retry", announcedLocation: String? = null) {
        if (!isDefaultSmsApp()) return
        if (retries?.isDownloading(mmsId) == true) return
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
            val corrected = downloadLocation(location, transactionId, subscriptionId)
            context.contentResolver.update(placeholder, ContentValues().apply { put(Telephony.Mms.CONTENT_LOCATION, corrected) }, null, null)
            download(placeholder, corrected, transactionId, subscriptionId, deferred, source, announcedLocation, location)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't restart the MMS download", e)
            failed(placeholder, "starting the download again", "Winnow couldn't start it: ${e.message ?: e::class.simpleName}", subscriptionId)
        }
    }

    /** Run after an upgrade/role change: fix placeholders shared with other SMS apps and retry
     * repaired failures, including ones the old retry queue had already given up on. */
    suspend fun recoverPending() {
        if (!isDefaultSmsApp()) return
        val configs = mutableMapOf<Int, Boolean>()
        val repaired = store.repairNotifications { location, transactionId, subId ->
            val append = configs.getOrPut(subId) { carrierConfig(subId)?.getBoolean(SmsManager.MMS_CONFIG_APPEND_TRANSACTION_ID, false) == true }
            MmsProtocol.downloadLocation(location, transactionId, append)
        }
        for (id in repaired) {
            val uri = ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, id)
            if (store.status(uri) == MmsStore.STATUS_DOWNLOAD_FAILED && autoDownload(store.subscriptionId(id) ?: -1)) {
                retryDownload(id, "metadata repair of existing notification")
            }
        }
    }

    private fun download(placeholder: Uri, location: String, transactionId: String, subscriptionId: Int, deferred: Boolean = false, source: String, announcedLocation: String? = null, inputLocation: String = announcedLocation ?: location) {
        val id = placeholder.lastPathSegment?.toLongOrNull()
        val file = files.newFile("retrieve")
        if (id != null && retries?.started(id, file.name) == false) {
            file.delete()
            return
        }
        val manager = smsManager(subscriptionId)
        val config = carrierConfig(subscriptionId)
        val overrides = MmsHttpConfig.overrides(context, manager, config)
        val details = runCatching {
            diagnostics.describe(placeholder, location, transactionId, subscriptionId, config, overrides, source, announcedLocation, inputLocation)
        }.getOrDefault("Request snapshot unavailable")
        val done = PendingIntent.getBroadcast(
            context, placeholder.lastPathSegment?.toIntOrNull() ?: 0,
            Intent(context, MmsDownloadedReceiver::class.java)
                .setData(placeholder)
                // Each attempt owns its callback extras, even if an older attempt answers late.
                .setIdentifier(file.name)
                .putExtra(EXTRA_FILE, file.path)
                .putExtra(EXTRA_TRANSACTION_ID, transactionId)
                .putExtra(EXTRA_SUBSCRIPTION, subscriptionId)
                .putExtra(EXTRA_LOCATION, location)
                .putExtra(EXTRA_REQUEST_DETAILS, details)
                .putExtra(EXTRA_DEFERRED, deferred),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val uri = files.uriFor(file)
        // Android's MMS service writes the message into this file as the phone. It's let in as
        // that already; let every package of the phone's user write it too, whatever the phone's
        // make calls its MMS service.
        phoneHosts().forEach { host ->
            runCatching { context.grantUriPermission(host, uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        try {
            manager.downloadMultimediaMessage(context, location, uri, overrides, done)
        } catch (e: Exception) {
            file.delete()
            failed(placeholder, "starting the download", "Winnow couldn't start it: ${e.message ?: e::class.simpleName}", subscriptionId, details, location)
        }
    }

    private fun phoneHosts(): List<String> =
        (runCatching { context.packageManager.getPackagesForUid(android.os.Process.PHONE_UID)?.toList() }.getOrNull().orEmpty() + PHONE_HOSTS).distinct()

    /**
     * A downloaded m-retrieve-conf. [placeholder] is replaced by the real message; with
     * [acknowledge], the carrier is told the message arrived so it stops re-announcing it.
     * Returns what's left once it's stored, classifying and notifying, for after the download's
     * broadcast has ended (see MmsDownloadedReceiver); null if there's nothing left to do.
     */
    suspend fun onDownloaded(
        placeholder: Uri?,
        pdu: ByteArray?,
        transactionId: String?,
        subscriptionId: Int,
        acknowledge: Boolean = true,
        /** Fetched after a deferred notification, which the spec confirms with m-acknowledge-ind instead. */
        deferred: Boolean = false,
        /** Why there's no [pdu]: Android's MMS service's failure, in words. */
        whyNone: String? = null,
        contentLocation: String? = null,
        attempt: String? = null,
        requestDetails: String? = null,
    ): (suspend () -> Unit)? = downloadResults.withLock {
        // A late callback must not recreate a deleted message or duplicate a completed retry.
        if (placeholder != null) {
            val pending = context.contentResolver.query(placeholder, arrayOf(Telephony.Mms.MESSAGE_TYPE), null, null, null)
                ?.use { c -> c.moveToFirst() && c.getInt(0) == MmsStore.MESSAGE_TYPE_NOTIFICATION_IND } == true
            if (!pending) return@withLock null
        }
        val id = placeholder?.lastPathSegment?.toLongOrNull()
        val staleAttempt = id != null && attempt != null && retries?.isCurrentAttempt(id, attempt) == false
        if (pdu == null) {
            if (staleAttempt) return@withLock null
            placeholder?.let { failed(it, "the download (Android's MMS service and the carrier)", whyNone ?: "nothing came back", subscriptionId, requestDetails, contentLocation) }
            return@withLock null
        }
        var unreadable: String? = null
        val parsed = try {
            PduParser.parse(pdu)
        } catch (e: MmsPduException) {
            Log.w(TAG, "Unreadable downloaded MMS", e)
            unreadable = e.message
            null
        }
        val conf = parsed as? RetrieveConf
        if (conf == null) {
            if (staleAttempt) return@withLock null
            val why = when {
                pdu.isEmpty() -> "the download came back empty"
                parsed != null -> "the carrier sent something other than the message (a ${parsed::class.simpleName})"
                else -> "what came back couldn't be read (${unreadable ?: "malformed"}, ${pdu.size} bytes)"
            }
            placeholder?.let { failed(it, "reading what came back", why, subscriptionId) }
            return@withLock null
        }
        if (!MmsProtocol.retrieved(conf)) {
            if (staleAttempt) return@withLock null
            placeholder?.let { failed(it, "the carrier's retrieval response", "the carrier couldn't retrieve it (MMS status ${conf.retrieveStatus})", subscriptionId) }
            return@withLock null
        }
        val recipients = participants(conf)
        val threadId = Telephony.Threads.getOrCreateThreadId(context, recipients.toSet())
        val message = store.insertIncoming(conf, threadId, subscriptionId) ?: run {
            placeholder?.let { failed(it, "saving it", "the phone's message store wouldn't take it", subscriptionId) }
            return@withLock null
        }
        placeholder?.lastPathSegment?.toLongOrNull()?.let { failures?.clear(it) }
        // Stored: until it's classified and said, it's on the list a later start finishes (see IncomingMessageHandler).
        incoming.markUnfinished(message)
        // Housekeeping that can fail (a full cache, say): never a reason for the stored message to go unsaid.
        runCatching { placeholder?.let(store::delete) }.onFailure { Log.w(TAG, "Couldn't remove a placeholder", it) }
        runCatching { placeholder?.lastPathSegment?.toLongOrNull()?.let { retries?.done(it) } }
        runCatching {
            if (acknowledge) MmsProtocol.response(conf, transactionId, deferred)?.let {
                acknowledge(it, subscriptionId, contentLocation)
            }
        }.onFailure { Log.w(TAG, "Couldn't acknowledge a picture message", it) }
        val text = conf.parts.filter { it.contentType == ContentTypes.TEXT_PLAIN }.mapNotNull { it.text }.joinToString("\n")
        // Contacts are text/x-vcard, so "everything but the text and the layout", not "not text/".
        val media = conf.parts.map { it.contentType }.filter { it != ContentTypes.TEXT_PLAIN && it != ContentTypes.SMIL }
        return@withLock suspend { incoming.onMmsStored(message, threadId, conf.from ?: UNKNOWN_SENDER, recipients, text, media, subject = conf.subject) }
    }

    /**
     * Everyone in the conversation except this phone. Without our own number, a 1:1 MMS
     * (one To, no Cc) is still recognized; for groups we may include ourselves.
     */
    private fun participants(conf: RetrieveConf): List<String> {
        // Sent to this phone alone: the one it was sent to is this phone (see OwnNumbers.learn).
        val from = conf.from
        if (conf.to.size == 1 && conf.cc.isEmpty() && from != null && normalizeAddress(from) != normalizeAddress(conf.to.single())) {
            ownNumbers.learn(conf.to.single())
        }
        val me = ownNumbers.all()
        val decided = participantsOf(conf.from, conf.to, conf.cc, me) {
            runCatching { context.contentResolver.threadRecipients() }.getOrNull()?.values
                ?.mapTo(HashSet()) { people -> people.mapTo(HashSet(), ::normalizeAddress) }.orEmpty()
        }
        if (decided.unaddressed) ownNumbers.noteUnaddressed()
        return decided.people
    }

    private fun acknowledge(pdu: MmsPdu, subscriptionId: Int, contentLocation: String?) {
        val file = files.write("ack", PduComposer.compose(pdu))
        val manager = smsManager(subscriptionId)
        val config = carrierConfig(subscriptionId)
        val location = MmsProtocol.responseLocation(contentLocation, config?.getBoolean(SmsManager.MMS_CONFIG_NOTIFY_WAP_MMSC_ENABLED, false) == true)
        runCatching { manager.sendMultimediaMessage(context, files.uriFor(file), location, MmsHttpConfig.overrides(context, manager, config), null) }
            .onFailure { Log.w(TAG, "Couldn't acknowledge MMS", it) }
    }

    private fun downloadLocation(location: String, transactionId: String, subscriptionId: Int): String =
        MmsProtocol.downloadLocation(location, transactionId, carrierConfig(subscriptionId)?.getBoolean(SmsManager.MMS_CONFIG_APPEND_TRANSACTION_ID, false) == true)

    private fun carrierConfig(subscriptionId: Int) = runCatching { smsManager(subscriptionId).carrierConfigValues }
        .onFailure { Log.w(TAG, "Couldn't read MMS carrier configuration for SIM $subscriptionId", it) }.getOrNull()

    private fun isDefaultSmsApp() = context.getSystemService(android.app.role.RoleManager::class.java).isRoleHeld(android.app.role.RoleManager.ROLE_SMS)

    private fun smsManager(subscriptionId: Int): SmsManager =
        context.getSystemService(SmsManager::class.java).let { if (subscriptionId >= 0) it.createForSubscriptionId(subscriptionId) else it }

    companion object {
        /** Who a received MMS is between (see [participantsOf]), and whether this phone's known number wasn't among its recipients. */
        data class Participants(val people: List<String>, val unaddressed: Boolean)

        /**
         * Everyone in the conversation except this phone ([me], its known numbers, normalized). A
         * message reaches this phone addressed to it, so if none of its recipients is in [me], that
         * number is wrong or out of date ([Participants.unaddressed]) and is no help. Then a 1:1
         * message is with its sender; a group, if leaving out exactly one of its recipients gives a
         * conversation the phone already has ([conversations], each a set of normalized numbers,
         * asked only when needed), is that conversation, the one left out being this phone; failing
         * that, everyone, this phone perhaps among them. Pure, so it's unit-tested.
         */
        fun participantsOf(from: String?, to: List<String>, cc: List<String>, me: Set<String>, conversations: () -> Set<Set<String>>): Participants {
            val everyone = (listOfNotNull(from) + to + cc).filter { it.isNotBlank() }.distinctBy(::normalizeAddress)
            val recipients = (to + cc).filter { it.isNotBlank() }
            val addressedToMe = recipients.any { normalizeAddress(it) in me }
            val group = recipients.size > 1
            val others = when {
                addressedToMe -> everyone.filterNot { normalizeAddress(it) in me }
                to.size == 1 && cc.isEmpty() -> listOfNotNull(from)
                else -> {
                    val sender = from?.let(::normalizeAddress)
                    val known = conversations()
                    val fits = recipients.map(::normalizeAddress).distinct().filter { it != sender }.filter { candidate ->
                        everyone.map(::normalizeAddress).filter { it != candidate }.toHashSet() in known
                    }
                    fits.singleOrNull()?.let { mine -> everyone.filterNot { normalizeAddress(it) == mine } } ?: everyone
                }
            }
            return Participants(
                people = others.ifEmpty { listOfNotNull(from).ifEmpty { listOf(UNKNOWN_SENDER) } },
                unaddressed = group && me.isNotEmpty() && !addressedToMe,
            )
        }

        const val EXTRA_FILE = "pdu_file"
        const val EXTRA_TRANSACTION_ID = "transaction_id"
        const val EXTRA_SUBSCRIPTION = "subscription"
        const val EXTRA_DEFERRED = "deferred"
        const val EXTRA_LOCATION = "content_location"
        const val EXTRA_REQUEST_DETAILS = "request_details"
        /** Where Android's MMS service may live, besides whatever the phone's user ID says. */
        private val PHONE_HOSTS = listOf("com.android.phone", "com.android.mms.service")
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
        val code = resultCode
        // The carrier's HTTP answer, when the service had one (SmsManager.EXTRA_MMS_HTTP_STATUS).
        val http = intent.getIntExtra("android.telephony.extra.MMS_HTTP_STATUS", 0)
        val pending = goAsync()
        container.appScope.launch {
            // Stored, the broadcast ends (the next download's can come); classifying and notifying follow.
            var then: (suspend () -> Unit)? = null
            try {
                val bytes = if (ok) file?.takeIf { it.exists() }?.readBytes() else null
                val subscriptionId = intent.getIntExtra(MmsReceiver.EXTRA_SUBSCRIPTION, -1)
                val why = when {
                    !ok && code == 4 && http == 0 && container.mmsFailures.mobileDataOff(subscriptionId) -> MmsFailures.describe(11, 0)
                    !ok -> MmsFailures.describe(code, http)
                    file == null || !file.exists() -> "Android said it downloaded, but nothing was written for Winnow to read"
                    else -> null
                }
                then = container.mmsReceiver.onDownloaded(
                    placeholder = intent.data,
                    pdu = bytes,
                    transactionId = intent.getStringExtra(MmsReceiver.EXTRA_TRANSACTION_ID),
                    subscriptionId = subscriptionId,
                    deferred = intent.getBooleanExtra(MmsReceiver.EXTRA_DEFERRED, false),
                    whyNone = why,
                    contentLocation = intent.getStringExtra(MmsReceiver.EXTRA_LOCATION),
                    attempt = file?.name,
                    requestDetails = intent.getStringExtra(MmsReceiver.EXTRA_REQUEST_DETAILS),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("WinnowMms", "Handling downloaded MMS failed", e)
            } finally {
                file?.delete()
                pending.finish()
            }
            then?.let { rest ->
                try {
                    rest()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("WinnowMms", "Classifying a downloaded MMS failed", e)
                }
            }
        }
    }
}
