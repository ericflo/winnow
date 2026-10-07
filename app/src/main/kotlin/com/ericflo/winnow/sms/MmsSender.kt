package com.ericflo.winnow.sms

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.util.Log
import com.ericflo.winnow.WinnowApp
import com.ericflo.winnow.data.displayNameFor
import kotlinx.coroutines.launch
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.Telephony
import android.os.Bundle
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import com.ericflo.winnow.data.OutgoingAttachment
import com.ericflo.winnow.mms.MmsPart
import com.ericflo.winnow.mms.PduComposer
import com.ericflo.winnow.mms.PduParser
import com.ericflo.winnow.mms.ResponseStatus
import com.ericflo.winnow.mms.SendConf
import com.ericflo.winnow.mms.SendReq
import com.ericflo.winnow.mms.Smil
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Sends MMS (group texts and attachments) through the system MMS service. */
class MmsSender(
    private val context: Context,
    private val store: MmsStore,
    private val files: MmsFiles,
    /** Maps a chosen SIM to the subscription to send on (null: Android's default). */
    private val forSending: (Int?) -> Int? = { it },
    /** Whether to ask the carrier for delivery reports (Settings → SMS delivery reports). */
    private val deliveryReports: suspend () -> Boolean = { false },
) {

    suspend fun send(recipients: List<String>, body: String, attachments: List<OutgoingAttachment>, subscriptionId: Int? = null, subject: String? = null) {
        // Settled before the message is stored: nothing after that may be cancelled halfway.
        val reports = deliveryReports()
        val sub = forSending(subscriptionId)
        val media = readAttachments(attachments, messageBudget(subscriptionId))
        val text = body.takeIf { it.isNotBlank() }?.let { MmsPart.plainText(it) }
            // A subject alone still needs a part to carry: an empty text, as other phones send.
            ?: MmsPart.plainText("").takeIf { media.isEmpty() && !subject.isNullOrBlank() }
        val content = media + listOfNotNull(text)
        require(content.isNotEmpty()) { "nothing to send" }
        val parts = listOf(Smil.forParts(content)) + content
        val threadId = Telephony.Threads.getOrCreateThreadId(context, recipients.toSet())
        val uri = store.insertOutgoing(threadId, recipients, parts, sub ?: SubscriptionManager.getDefaultSmsSubscriptionId(), subject)
            ?: error("couldn't store the outgoing MMS; is Winnow the default SMS app?")
        try {
            transmit(uri, recipients, parts, sub, reports, subject)
        } catch (e: Exception) {
            // In the conversation now: marked not sent (Tap to retry), not left "Sending…" for good.
            store.setBox(uri, Telephony.Mms.MESSAGE_BOX_FAILED)
            throw StoredAsFailed("mms:${ContentUris.parseId(uri)}", e)
        }
    }

    suspend fun retry(mmsId: Long) {
        val reports = deliveryReports()
        val uri = ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, mmsId)
        // Claimed by moving it out of failed: of two tries at once, only the one that moved it sends.
        if (!store.claimFailed(uri)) return
        // Retry on the SIM it was first sent from; back to failed if it can't even be handed off.
        try {
            transmit(uri, store.recipients(mmsId), store.parts(mmsId), forSending(store.subscriptionId(mmsId)), reports, store.subject(mmsId))
        } catch (e: Exception) {
            store.setBox(uri, Telephony.Mms.MESSAGE_BOX_FAILED)
            throw e
        }
    }

    private fun transmit(message: Uri, recipients: List<String>, parts: List<MmsPart>, subscriptionId: Int?, deliveryReport: Boolean, subject: String?) {
        val pdu = PduComposer.compose(
            SendReq(
                transactionId = "T" + UUID.randomUUID().toString().replace("-", "").take(12),
                to = recipients,
                dateSeconds = System.currentTimeMillis() / 1000,
                parts = parts,
                subject = subject?.takeIf { it.isNotBlank() },
                // The carrier answers with an m-delivery-ind; see MmsReceiver.onPush.
                deliveryReport = deliveryReport.takeIf { it },
            ),
        )
        val file = files.write("send", pdu)
        val sent = PendingIntent.getBroadcast(
            context, message.lastPathSegment?.toIntOrNull() ?: 0,
            Intent(context, MmsSentReceiver::class.java).setData(message).putExtra(MmsSentReceiver.EXTRA_FILE, file.path),
            // The MMS service attaches the carrier's m-send-conf, so this must be mutable.
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        context.getSystemService(SmsManager::class.java)
            .let { if (subscriptionId != null) it.createForSubscriptionId(subscriptionId) else it }
            .sendMultimediaMessage(context, files.uriFor(file), null, null, sent)
    }

    /**
     * How many bytes of attachments one MMS can carry on the SIM [subscriptionId] would send on:
     * the carrier's own limit (from its MMS config) less room for headers and the text, or
     * [DEFAULT_BUDGET_BYTES] if it won't say. Carriers range from 300 KB to well over 1 MB.
     */
    fun messageBudget(subscriptionId: Int?): Int =
        budgetFor(carrierConfig(subscriptionId)?.getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE, 0) ?: 0)

    /**
     * Whether the carrier wants [body] (to one person, nothing attached) sent as an MMS: some
     * say past so many parts, or so many characters, a text should be one MMS, and some can't
     * send a text in parts at all.
     */
    fun textNeedsMms(body: String, subscriptionId: Int?): Boolean {
        if (body.length <= SINGLE_SMS_CHARS) return false
        val config = carrierConfig(subscriptionId) ?: return false
        return textNeedsMms(
            segments = SmsMessage.calculateLength(body, false)[0],
            length = body.length,
            segmentThreshold = config.getInt(SmsManager.MMS_CONFIG_SMS_TO_MMS_TEXT_THRESHOLD, -1),
            lengthThreshold = config.getInt(SmsManager.MMS_CONFIG_SMS_TO_MMS_TEXT_LENGTH_THRESHOLD, -1),
            multipart = config.getBoolean(SmsManager.MMS_CONFIG_MULTIPART_SMS_ENABLED, true),
        )
    }

    private class Config(val readAt: Long, val values: Bundle)
    private val configs = java.util.concurrent.ConcurrentHashMap<Int, Config>()

    /** The carrier's MMS config for the SIM a message would go out on, read at most once a minute: the composer asks per keystroke. */
    private fun carrierConfig(subscriptionId: Int?): Bundle? {
        val sub = forSending(subscriptionId)
        val now = System.currentTimeMillis()
        configs[sub ?: -1]?.takeIf { now - it.readAt < CONFIG_MAX_AGE_MILLIS }?.let { return it.values.takeUnless { values -> values.isEmpty } }
        // A phone that can't say (no telephony, say) is asked again in a minute, not per keystroke.
        val values = runCatching {
            context.getSystemService(SmsManager::class.java)
                .let { if (sub != null) it.createForSubscriptionId(sub) else it }
                .carrierConfigValues
        }.getOrNull() ?: Bundle.EMPTY
        configs[sub ?: -1] = Config(now, values)
        return values.takeUnless { it.isEmpty }
    }

    /**
     * Reads the attachments so the whole message fits in [budget]. Videos, GIFs, audio and cards
     * can't be shrunk here, so they count at their real size, and the photos, which can, share
     * whatever is left: a voice message and a photo fit together.
     */
    private fun readAttachments(attachments: List<OutgoingAttachment>, budget: Int): List<MmsPart> {
        val parts = arrayOfNulls<MmsPart>(attachments.size)
        var left = budget
        attachments.forEachIndexed { i, attachment ->
            if (!isPhoto(attachment)) parts[i] = readAttachment(attachment, i + 1, left).also { left -= it.data.size }
        }
        val photos = attachments.count(::isPhoto)
        if (photos > 0) {
            val each = left / photos
            require(each >= MIN_PHOTO_BYTES) { "That's too much for one MMS. Try sending the photos in a separate message." }
            attachments.forEachIndexed { i, attachment -> if (isPhoto(attachment)) parts[i] = readAttachment(attachment, i + 1, each) }
        }
        return parts.filterNotNull()
    }

    private fun isPhoto(attachment: OutgoingAttachment) = canShrink(attachment.contentType)

    /**
     * Reads one attachment within [budget] bytes, shrinking a photo to fit. Anything else over the
     * budget is refused before it's read, rather than read whole into memory and sent to certain failure.
     */
    private fun readAttachment(attachment: OutgoingAttachment, index: Int, budget: Int): MmsPart {
        val isPhoto = isPhoto(attachment)
        val raw = readAtMost(Uri.parse(attachment.uri), if (isPhoto) MAX_PHOTO_BYTES else budget) ?: run {
            val what = when {
                attachment.contentType.startsWith("video/") -> "That video is"
                attachment.contentType.startsWith("audio/") -> "That recording is"
                isPhoto -> "That photo is"
                else -> "That attachment is"
            }
            throw IllegalArgumentException("$what too big to send by MMS (about ${budget / 1000} KB fits)")
        }
        val (type, data) = if (isPhoto && raw.size > budget) "image/jpeg" to shrink(raw, budget) else attachment.contentType to raw
        val name = "attachment$index.${type.substringAfter('/').substringBefore(';').ifBlank { "bin" }}"
        return MmsPart(contentType = type, data = data, name = name, contentId = "attachment$index", contentLocation = name)
    }

    /** The content's bytes, or null if there are more than [limit] (checked as it reads, never loading more). */
    private fun readAtMost(uri: Uri, limit: Int): ByteArray? {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) return out.toByteArray()
                out.write(buffer, 0, n)
                if (out.size() > limit) return null
            }
        }
        throw IllegalArgumentException("That attachment can't be read any more")
    }

    private fun shrink(image: ByteArray, budget: Int): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE_PX) sample *= 2
        var bitmap = BitmapFactory.decodeByteArray(image, 0, image.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return image
        // Re-encoding drops EXIF, so bake the camera's orientation into the pixels first.
        bitmap = upright(bitmap, image)
        var quality = 85
        while (true) {
            val out = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
            if (out.size <= budget || (quality <= 40 && bitmap.width < 320)) return out
            if (quality > 40) {
                quality -= 15
            } else {
                bitmap = Bitmap.createScaledBitmap(bitmap, bitmap.width * 3 / 4, bitmap.height * 3 / 4, true)
                quality = 70
            }
        }
    }

    private fun upright(bitmap: Bitmap, image: ByteArray): Bitmap {
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(image)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    companion object {
        /** About what carriers accept for one MMS, all parts together, when the carrier doesn't say. */
        const val DEFAULT_BUDGET_BYTES = 900_000
        /** The PDU's headers and SMIL, and room for the text. */
        private const val HEADER_BYTES = 8_000
        private const val MIN_BUDGET_BYTES = 100_000
        /** Whatever a carrier claims: some configs say far more than the network takes. */
        private const val MAX_BUDGET_BYTES = 2_000_000

        /** The carrier's rules for when a text goes as an MMS ([textNeedsMms]); a threshold of 0 or less is no rule. */
        fun textNeedsMms(segments: Int, length: Int, segmentThreshold: Int, lengthThreshold: Int, multipart: Boolean): Boolean =
            (segmentThreshold > 0 && segments > segmentThreshold) ||
                (lengthThreshold > 0 && length > lengthThreshold) ||
                (!multipart && segments > 1)

        /** No text this short is more than one part, whatever its alphabet. */
        private const val SINGLE_SMS_CHARS = 70
        private const val CONFIG_MAX_AGE_MILLIS = 60_000L

        /** Attachments' share of a carrier's [maxMessageSize] (0 or less: it didn't say). */
        fun budgetFor(maxMessageSize: Int): Int =
            if (maxMessageSize <= 0) DEFAULT_BUDGET_BYTES
            // Never more than the carrier takes, however small: the MMS service refuses anything bigger.
            else (maxMessageSize - maxMessageSize / 20 - HEADER_BYTES).coerceIn(minOf(MIN_BUDGET_BYTES, maxMessageSize / 2), MAX_BUDGET_BYTES)

        /** Photos are shrunk to fit; GIFs (which would lose their animation), video and audio go as they are. */
        fun canShrink(contentType: String) = contentType.startsWith("image/") && contentType != "image/gif"

        /** Below this a shrunk photo is a smudge; better to say it doesn't fit. */
        const val MIN_PHOTO_BYTES = 40_000
        private const val MAX_EDGE_PX = 1600
        /** Photos get shrunk, but one bigger than this isn't worth decoding on a phone. */
        private const val MAX_PHOTO_BYTES = 40_000_000
    }
}

/** Moves an outgoing MMS to sent or failed once the MMS service reports back. */
class MmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val message = intent.data ?: return
        intent.getStringExtra(EXTRA_FILE)?.let { File(it).delete() }
        val conf = intent.getByteArrayExtra(SmsManager.EXTRA_MMS_DATA)
            ?.let { runCatching { PduParser.parse(it) }.getOrNull() as? SendConf }
        val ok = resultCode == Activity.RESULT_OK && (conf == null || conf.responseStatus == ResponseStatus.OK)
        val store = MmsStore(context)
        store.setBox(message, if (ok) Telephony.Mms.MESSAGE_BOX_SENT else Telephony.Mms.MESSAGE_BOX_FAILED, conf?.messageId)
        if (!ok) notifyNotSent(context, store, message)
    }

    /** Says so, unless the conversation is on screen, where the message already shows "Not sent". */
    private fun notifyNotSent(context: Context, store: MmsStore, message: Uri) {
        val container = (context.applicationContext as WinnowApp).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                val id = ContentUris.parseId(message)
                val threadId = context.contentResolver.query(message, arrayOf(Telephony.Mms.THREAD_ID), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getLong(0) else null } ?: return@launch
                if (container.visibleThread.value == threadId) return@launch
                val recipients = store.recipients(id)
                val text = store.parts(id).firstOrNull { it.contentType == "text/plain" }?.data?.toString(Charsets.UTF_8)
                container.notifier.showNotSent(
                    threadId, recipients, displayNameFor(recipients, container.messages::displayName), text ?: "a picture message",
                    retryKey = "mms:$id",
                )
            } catch (e: Exception) {
                Log.w("WinnowMms", "Couldn't say an MMS wasn't sent", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_FILE = "pdu_file"
    }
}
