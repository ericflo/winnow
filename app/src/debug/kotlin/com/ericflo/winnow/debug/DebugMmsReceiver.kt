package com.ericflo.winnow.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.telephony.SubscriptionManager
import android.util.Log
import com.ericflo.winnow.R
import com.ericflo.winnow.WinnowApp
import com.ericflo.winnow.data.splitAddresses
import com.ericflo.winnow.mms.MmsPart
import com.ericflo.winnow.mms.NotificationInd
import com.ericflo.winnow.mms.PduComposer
import com.ericflo.winnow.mms.RetrieveConf
import com.ericflo.winnow.mms.Smil
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * Debug builds only. Emulators have no MMSC, so this feeds codec-built PDUs through the real
 * receive path:
 *
 *     adb shell am broadcast -n com.ericflo.winnow/.debug.DebugMmsReceiver \
 *         --es from +14155550161 --es to +15555215554,+14155550162 --es text "hi" --ez photo true
 *
 * `--ez voice true` adds a voice memo (audio/amr) and `--ez video true` a short clip (video/mp4).
 *
 * The default mode runs an m-retrieve-conf through [com.ericflo.winnow.sms.MmsReceiver.onDownloaded];
 * `--es mode push` runs an m-notification-ind through onPush, whose download then fails for
 * want of an MMSC, which exercises the retry path.
 */
class DebugMmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as WinnowApp).container
        val from = intent.getStringExtra("from") ?: "+14155550161"
        val to = intent.getStringExtra("to")?.let(::splitAddresses) ?: listOf("+15555215554")
        val text = intent.getStringExtra("text")
        val photo = intent.getBooleanExtra("photo", false)
        val voice = intent.getBooleanExtra("voice", false)
        val video = intent.getBooleanExtra("video", false)
        val subscriptionId = SubscriptionManager.getDefaultSmsSubscriptionId()
        val id = "debug${System.currentTimeMillis()}"
        val pending = goAsync()
        container.appScope.launch {
            try {
                if (intent.getStringExtra("mode") == "push") {
                    val ind = NotificationInd(transactionId = id, contentLocation = "http://mmsc.invalid/$id", from = from, messageSize = 50_000)
                    container.mmsReceiver.onPush(PduComposer.compose(ind), subscriptionId)
                } else {
                    val content = buildList {
                        if (photo) add(MmsPart("image/jpeg", samplePhoto(), name = "photo.jpg", contentId = "photo", contentLocation = "photo.jpg"))
                        if (voice) add(MmsPart("audio/amr", raw(context, R.raw.sample_voice), name = "voice.amr", contentId = "voice", contentLocation = "voice.amr"))
                        if (video) add(MmsPart("video/mp4", raw(context, R.raw.sample_clip), name = "clip.mp4", contentId = "clip", contentLocation = "clip.mp4"))
                        text?.let { add(MmsPart.plainText(it)) }
                    }
                    val conf = RetrieveConf(
                        transactionId = id,
                        messageId = id,
                        dateSeconds = System.currentTimeMillis() / 1000,
                        from = from,
                        to = to,
                        parts = listOf(Smil.forParts(content)) + content,
                    )
                    container.mmsReceiver.onDownloaded(null, PduComposer.compose(conf), id, subscriptionId, acknowledge = false)
                }
                Log.i(TAG, "Injected MMS $id from $from")
            } catch (e: Exception) {
                Log.e(TAG, "Injecting MMS failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    /** A 4-second voice memo (AMR) or a 3-second clip (MP4), generated with ffmpeg; see res/raw. */
    private fun raw(context: Context, id: Int): ByteArray = context.resources.openRawResource(id).use { it.readBytes() }

    /** A 1200x900 dusk-over-hills JPEG, big enough to exercise real image handling. */
    private fun samplePhoto(): ByteArray {
        val bitmap = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val sky = Paint().apply { shader = LinearGradient(0f, 0f, 0f, 900f, Color.rgb(36, 52, 120), Color.rgb(242, 150, 86), Shader.TileMode.CLAMP) }
        canvas.drawRect(0f, 0f, 1200f, 900f, sky)
        canvas.drawCircle(820f, 560f, 90f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 222, 140) })
        val hills = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(34, 60, 48) }
        canvas.drawOval(-300f, 640f, 700f, 1300f, hills)
        canvas.drawOval(400f, 700f, 1600f, 1300f, hills.apply { color = Color.rgb(24, 44, 36) })
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }

    private companion object {
        const val TAG = "WinnowDebugMms"
    }
}
