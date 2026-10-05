package com.ericflo.winnow.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * When Winnow stopped being the SMS app, and what arrived until it was again: while another app
 * is, texts arrive without Winnow seeing them (nothing filtered, nothing notified by Winnow), and
 * RCS chats arrive only then. Told by Android when the SMS app changes (see [Receiver]), and
 * checked again whenever Winnow comes to the front, in case that was missed.
 */
class RoleWatch(private val context: Context) {
    /** What arrived while another app was the SMS app. */
    @Serializable
    data class Away(
        val from: Long,
        val until: Long,
        val texts: Int,
        val conversations: Int,
        /** Of those, conversations that were RCS chats, and up to three of their names. */
        val rcsConversations: Int,
        val rcsNames: List<String> = emptyList(),
    )

    private val prefs by lazy { context.getSharedPreferences("sms_role", Context.MODE_PRIVATE) }
    private val json = Json { ignoreUnknownKeys = true }
    private val _away = MutableStateFlow(load())
    /** The last time away, until dismissed; null when there's nothing to say. */
    val away: StateFlow<Away?> = _away.asStateFlow()

    private fun load(): Away? = prefs.getString(KEY_AWAY, null)?.let { runCatching { json.decodeFromString(Away.serializer(), it) }.getOrNull() }

    /** Winnow is (or isn't) the SMS app now: records when it stopped, and when it's back, what came meanwhile. */
    fun check(isDefault: Boolean, names: (String) -> String, now: Long = System.currentTimeMillis()) {
        val since = prefs.getLong(KEY_SINCE, 0)
        if (!isDefault) {
            if (since == 0L) prefs.edit().putLong(KEY_SINCE, now).apply()
            return
        }
        if (since == 0L) return
        prefs.edit().remove(KEY_SINCE).apply()
        val away = runCatching { arrivedSince(since, now, names) }.getOrNull() ?: return
        if (away.texts == 0) return
        prefs.edit().putString(KEY_AWAY, json.encodeToString(Away.serializer(), away)).apply()
        _away.value = away
    }

    fun dismiss() {
        prefs.edit().remove(KEY_AWAY).apply()
        _away.value = null
    }

    private fun arrivedSince(since: Long, until: Long, names: (String) -> String): Away {
        val resolver = context.contentResolver
        val threads = HashMap<Long, Int>()
        resolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.THREAD_ID),
            "${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_INBOX} AND ${Telephony.Sms.DATE} > ?", arrayOf(since.toString()), null,
        )?.use { c -> while (c.moveToNext()) threads.merge(c.getLong(0), 1, Int::plus) }
        // Picture messages are dated in seconds.
        resolver.query(
            Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms.THREAD_ID),
            "${Telephony.Mms.MESSAGE_BOX} = ${Telephony.Mms.MESSAGE_BOX_INBOX} AND ${Telephony.Mms.DATE} > ?", arrayOf((since / 1000).toString()), null,
        )?.use { c -> while (c.moveToNext()) threads.merge(c.getLong(0), 1, Int::plus) }
        val recipients = resolver.threadRecipients()
        val rcs = threads.keys.filter { id -> recipients[id].orEmpty().any(::isRcsAddress) }
        return Away(
            from = since,
            until = until,
            texts = threads.values.sum(),
            conversations = threads.size,
            rcsConversations = rcs.size,
            rcsNames = rcs.take(3).map { id -> displayNameFor(recipients[id].orEmpty(), names) },
        )
    }

    /** Android's word that the SMS app changed: to Winnow, or from it. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Telephony.Sms.Intents.ACTION_DEFAULT_SMS_PACKAGE_CHANGED) return
            val container = (context.applicationContext as? com.ericflo.winnow.WinnowApp)?.container ?: return
            // Asked of Android, not taken from the broadcast: any app can send one, so its word isn't proof.
            val isDefault = container.isDefaultSmsApp()
            val pending = goAsync()
            kotlinx.coroutines.CoroutineScope(container.appScope.coroutineContext).launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    container.roleWatch.check(isDefault, container.messages::displayName)
                } finally {
                    pending.finish()
                }
            }
        }
    }

    private companion object {
        const val KEY_SINCE = "not_default_since"
        const val KEY_AWAY = "away"
    }
}

