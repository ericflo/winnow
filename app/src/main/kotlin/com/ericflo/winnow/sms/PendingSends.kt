package com.ericflo.winnow.sms

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.ericflo.winnow.WinnowApp
import com.ericflo.winnow.data.ReturnedMessages
import com.ericflo.winnow.data.displayNameFor
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

/**
 * Messages waiting out Undo send, kept where they outlive the app. The conversation's countdown
 * sends one when it ends; if Winnow is closed before then (swiped away, or ended by Android), an
 * alarm set for just after sends it instead, and one a reboot cut off goes at start-up. Whichever
 * comes first [take]s it, so it goes once; Undo takes it back the same way.
 */
class PendingSends(private val context: Context) {
    @Serializable
    data class Held(
        val id: Long,
        val threadId: Long,
        val recipients: List<String>,
        val text: String,
        val sendsAt: Long,
        val subject: String? = null,
        val separately: Boolean = false,
        val subscriptionId: Int? = null,
        /** Its attachments, as kept copies (DraftAttachments.encode). */
        val attachments: String? = null,
    )

    private val prefs by lazy { context.getSharedPreferences("pending_sends", Context.MODE_PRIVATE) }
    private val alarms by lazy { context.getSystemService(AlarmManager::class.java) }
    /** Ids taken in this process, which [hold] mustn't bring back. */
    private val taken = HashSet<Long>()

    /** Keeps [held] until it's taken, with an alarm to send it should the app be gone by then. */
    @Synchronized
    fun hold(held: Held) {
        // Undone (taken) before it was even kept: kept now, its alarm would send it anyway.
        if (held.id in taken) return
        prefs.edit().putString(held.id.toString(), json.encodeToString(Held.serializer(), held)).commit()
        arm(held)
    }

    /** Adds [attachments] to [id], if it's still held: never brings back one already taken. */
    @Synchronized
    fun attach(id: Long, attachments: String?) {
        val held = read(id) ?: return
        prefs.edit().putString(id.toString(), json.encodeToString(Held.serializer(), held.copy(attachments = attachments))).commit()
    }

    /** Takes [id] to send or to undo; null if something else took it first. */
    @Synchronized
    fun take(id: Long): Held? {
        taken += id
        val held = read(id)
        prefs.edit().remove(id.toString()).commit()
        alarms.cancel(alarmIntent(id))
        return held
    }

    fun all(): List<Held> = prefs.all.keys.mapNotNull { it.toLongOrNull()?.let(::read) }

    private fun read(id: Long): Held? =
        prefs.getString(id.toString(), null)?.let { runCatching { json.decodeFromString(Held.serializer(), it) }.getOrNull() }

    private fun arm(held: Held) {
        val at = held.sendsAt + GRACE_MILLIS
        if (alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(held.id))
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(held.id))
        }
    }

    private fun alarmIntent(id: Long): PendingIntent = PendingIntent.getBroadcast(
        context, id.toInt(),
        // Its own data, so no two held messages' alarms are ever the same intent.
        Intent(context, PendingSendReceiver::class.java).setAction(ACTION_SEND).setData(Uri.parse("winnow-held:$id")).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * As the app starts: what a closed app left held. Any whose time has passed goes now (a
     * reboot took its alarm); the rest wait for theirs, set again in case.
     */
    suspend fun sendLeftovers(now: Long = System.currentTimeMillis()) {
        all().forEach { held ->
            if (held.sendsAt + GRACE_MILLIS <= now) sendLeftover(held.id) else arm(held)
        }
    }

    /**
     * Sends [id] if it's still held: its conversation's countdown didn't. One that can't go out
     * comes back to its conversation's composer, with a notice, as the countdown's would.
     */
    suspend fun sendLeftover(id: Long) {
        val held = take(id) ?: return
        val container = (context.applicationContext as WinnowApp).container
        val files = container.draftAttachments.decode(held.attachments)
        val people = if (held.separately && held.recipients.size > 1) held.recipients.map(::listOf) else listOf(held.recipients)
        var stored: StoredAsFailed? = null
        var failed = 0
        people.forEach { to ->
            try {
                container.messages.send(to, held.text, files, held.subscriptionId, held.subject)
            } catch (e: CancellationException) {
                throw e
            } catch (e: StoredAsFailed) {
                // In its conversation, marked not sent: tried again from there.
                Log.w(TAG, "A held message couldn't be sent", e)
                stored = e
                failed++
            } catch (e: Exception) {
                Log.w(TAG, "A held message couldn't be sent", e)
                failed++
            }
        }
        if (failed == 0) return
        val title = displayNameFor(held.recipients, container.messages::displayName)
        val notSent = stored
        // Every copy failed before it was stored: back to the composer, as Undo would put it.
        if (notSent == null && failed == people.size && held.threadId >= 0) {
            val saved = container.conversationStates.get(held.threadId)
            container.conversationStates.saveDraft(held.threadId, ReturnedMessages.appendTo(saved.draft.orEmpty(), held.text))
            val already = container.draftAttachments.decode(saved.draftAttachments)
            container.conversationStates.saveDraftAttachments(
                held.threadId, container.draftAttachments.encode(already + files.filter { f -> already.none { it.uri == f.uri } }),
            )
            if (!held.subject.isNullOrBlank()) {
                container.conversationStates.saveDraftSubject(held.threadId, ReturnedMessages.mergeSubjects(held.subject, saved.draftSubject))
            }
            container.returnedMessages.put(held.threadId, ReturnedMessages.Returned(held.text, files, held.separately, subject = held.subject))
        }
        container.notifier.showNotSent(held.threadId, held.recipients, title, held.text.ifBlank { "a picture message" }, retryKey = notSent?.key)
    }

    companion object {
        private const val TAG = "WinnowPendingSends"
        const val ACTION_SEND = "com.ericflo.winnow.SEND_HELD"
        const val EXTRA_ID = "held_id"

        /** The alarm's lead for the countdown itself: it only sends what the countdown didn't. */
        const val GRACE_MILLIS = 3_000L
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/** A held message's alarm: the countdown that should have sent it is gone with the app. */
class PendingSendReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(PendingSends.EXTRA_ID, -1)
        if (id < 0) return
        val container = (context.applicationContext as WinnowApp).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                container.pendingSends.sendLeftover(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("WinnowPendingSends", "Sending a held message failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
