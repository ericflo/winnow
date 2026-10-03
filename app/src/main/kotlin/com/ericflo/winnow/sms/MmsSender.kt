package com.ericflo.winnow.sms

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import com.ericflo.winnow.data.OutgoingAttachment
import com.ericflo.winnow.mms.MmsPart
import com.ericflo.winnow.mms.PduComposer
import com.ericflo.winnow.mms.PduParser
import com.ericflo.winnow.mms.ResponseStatus
import com.ericflo.winnow.mms.SendConf
import com.ericflo.winnow.mms.SendReq
import com.ericflo.winnow.mms.Smil
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Sends MMS (group texts and attachments) through the system MMS service. */
class MmsSender(private val context: Context, private val store: MmsStore, private val files: MmsFiles) {

    fun send(recipients: List<String>, body: String, attachments: List<OutgoingAttachment>) {
        val media = attachments.mapIndexedNotNull { i, attachment -> readAttachment(attachment, i + 1, attachments.size) }
        val content = media + listOfNotNull(body.takeIf { it.isNotBlank() }?.let { MmsPart.plainText(it) })
        require(content.isNotEmpty()) { "nothing to send" }
        val parts = listOf(Smil.forParts(content)) + content
        val threadId = Telephony.Threads.getOrCreateThreadId(context, recipients.toSet())
        val uri = store.insertOutgoing(threadId, recipients, parts, SubscriptionManager.getDefaultSmsSubscriptionId())
            ?: error("couldn't store the outgoing MMS; is Winnow the default SMS app?")
        transmit(uri, recipients, parts)
    }

    fun retry(mmsId: Long) {
        val uri = ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, mmsId)
        store.setBox(uri, Telephony.Mms.MESSAGE_BOX_OUTBOX)
        transmit(uri, store.recipients(mmsId), store.parts(mmsId))
    }

    private fun transmit(message: Uri, recipients: List<String>, parts: List<MmsPart>) {
        val pdu = PduComposer.compose(
            SendReq(
                transactionId = "T" + UUID.randomUUID().toString().replace("-", "").take(12),
                to = recipients,
                dateSeconds = System.currentTimeMillis() / 1000,
                parts = parts,
            ),
        )
        val file = files.write("send", pdu)
        val sent = PendingIntent.getBroadcast(
            context, message.lastPathSegment?.toIntOrNull() ?: 0,
            Intent(context, MmsSentReceiver::class.java).setData(message).putExtra(MmsSentReceiver.EXTRA_FILE, file.path),
            // The MMS service attaches the carrier's m-send-conf, so this must be mutable.
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        context.getSystemService(SmsManager::class.java).sendMultimediaMessage(context, files.uriFor(file), null, null, sent)
    }

    /** Reads an attachment, shrinking photos so the whole message fits carrier limits (~1 MB). */
    private fun readAttachment(attachment: OutgoingAttachment, index: Int, count: Int): MmsPart? {
        val raw = context.contentResolver.openInputStream(Uri.parse(attachment.uri))?.use { it.readBytes() } ?: return null
        val budget = MESSAGE_BUDGET_BYTES / count
        val isPhoto = attachment.contentType.startsWith("image/") && attachment.contentType != "image/gif"
        val (type, data) = if (isPhoto && raw.size > budget) "image/jpeg" to shrink(raw, budget) else attachment.contentType to raw
        val name = "attachment$index.${type.substringAfter('/').substringBefore(';').ifBlank { "bin" }}"
        return MmsPart(contentType = type, data = data, name = name, contentId = "attachment$index", contentLocation = name)
    }

    private fun shrink(image: ByteArray, budget: Int): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE_PX) sample *= 2
        var bitmap = BitmapFactory.decodeByteArray(image, 0, image.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return image
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

    private companion object {
        const val MESSAGE_BUDGET_BYTES = 900_000
        const val MAX_EDGE_PX = 1600
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
        MmsStore(context).setBox(message, if (ok) Telephony.Mms.MESSAGE_BOX_SENT else Telephony.Mms.MESSAGE_BOX_FAILED, conf?.messageId)
    }

    companion object {
        const val EXTRA_FILE = "pdu_file"
    }
}
