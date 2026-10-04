package com.ericflo.winnow.data

import android.app.ActivityOptions
import android.app.PendingIntent
import android.app.RemoteAction
import android.content.Context
import android.os.Build
import android.util.Log
import android.util.LruCache
import android.view.textclassifier.ConversationAction
import android.view.textclassifier.ConversationActions
import android.view.textclassifier.TextClassification
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier
import android.view.textclassifier.TextLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

private const val TAG = "WinnowSmartLinks"

/** A place, date or flight in a message: [start] to [end] in its text. */
data class SmartLink(val start: Int, val end: Int, val type: String) {
    val isPlace: Boolean get() = type == TextClassifier.TYPE_ADDRESS
    val isDate: Boolean get() = type == TextClassifier.TYPE_DATE || type == TextClassifier.TYPE_DATE_TIME
}

/** Something to do with a [SmartLink], such as "Open in Maps" or "Add to calendar". */
class SmartAction(val title: String, private val intent: PendingIntent) {
    /** Runs it. The app is in front, and lets the classifier's intent open its activity. */
    fun run(context: Context) {
        val options = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        }
        runCatching { intent.send(context, 0, null, null, null, null, options.toBundle()) }
            .onFailure { Log.w(TAG, "Couldn't run a smart link's action", it) }
    }
}

/**
 * Dates, times and flight numbers in messages (and addresses, where its model finds them), from
 * Android's own on-device text classifier, the one Messages uses. Web links, phone numbers,
 * emails, tracking numbers and US street addresses are Winnow's own (see MessageText.linkify).
 */
class SmartLinks(
    context: Context,
    /** Debug builds only: replies to offer instead of the classifier's (an emulator has no Smart Reply model). */
    private val debugReplies: () -> List<String>? = { null },
) {
    private val manager = context.getSystemService(TextClassificationManager::class.java)
    private val cache = LruCache<String, List<SmartLink>>(256)
    /** A few at a time: flinging through a long conversation mustn't queue a call per bubble. */
    private val gate = Semaphore(2)

    /** The smart links in [text]; none if the classifier can't say. Off the main thread. */
    suspend fun find(text: String): List<SmartLink> {
        if (text.length < MIN_LENGTH) return emptyList()
        cache.get(text)?.let { return it }
        return withContext(Dispatchers.Default) {
            gate.withPermit {
            // A bubble scrolled away while waiting no longer wants an answer.
            ensureActive()
            cache.get(text)?.let { return@withPermit it }
            val found = runCatching {
                val classifier = manager?.textClassifier ?: return@runCatching emptyList<SmartLink>()
                if (text.length > classifier.maxGenerateLinksTextLength) return@runCatching emptyList<SmartLink>()
                val config = TextClassifier.EntityConfig.Builder().setIncludedTypes(TYPES).includeTypesFromTextClassifier(false).build()
                classifier.generateLinks(TextLinks.Request.Builder(text).setEntityConfig(config).build())
                    .links
                    .mapNotNull { link ->
                        val type = (0 until link.entityCount).map(link::getEntity).firstOrNull { it in TYPES } ?: return@mapNotNull null
                        SmartLink(link.start, link.end, type).takeIf { link.getConfidenceScore(type) >= MIN_CONFIDENCE }
                    }
            }.onFailure { Log.w(TAG, "The text classifier couldn't look at a message", it) }
            // A failure (the service still starting, say) is tried again next time, not remembered.
            found.getOrNull()?.also { cache.put(text, it) } ?: emptyList()
            }
        }
    }

    /**
     * What can be done with [link] in [text], sent at [sentAt]: the classifier's own actions, with
     * "tomorrow" read from when the message came, not from today. Off the main thread.
     */
    suspend fun actions(text: String, link: SmartLink, sentAt: Long): List<SmartAction> = withContext(Dispatchers.Default) {
        runCatching {
            val classifier = manager?.textClassifier ?: return@runCatching emptyList()
            val reference = ZonedDateTime.ofInstant(Instant.ofEpochMilli(sentAt), ZoneId.systemDefault())
            classifier.classifyText(TextClassification.Request.Builder(text, link.start, link.end).setReferenceTime(reference).build())
                .actions
                .filter(RemoteAction::isEnabled)
                .map { SmartAction(it.title.toString(), it.actionIntent) }
        }.onFailure { Log.w(TAG, "The text classifier had nothing to offer", it) }.getOrDefault(emptyList())
    }

    /** One message of the conversation, for [suggestReplies]: [fromMe] for the user's own. */
    data class Turn(val text: String, val fromMe: Boolean, val sender: String?, val at: Long)

    /**
     * Replies to the newest of [turns] (oldest first) from Android's on-device classifier, the
     * Smart Reply Messages shows; none if it has nothing, or no model. Off the main thread.
     */
    suspend fun suggestReplies(turns: List<Turn>): List<String> = withContext(Dispatchers.Default) {
        if (turns.isEmpty() || turns.last().fromMe) return@withContext emptyList()
        debugReplies()?.let { return@withContext it }
        runCatching {
            val classifier = manager?.textClassifier ?: return@runCatching emptyList()
            val people = HashMap<String, android.app.Person>()
            val messages = turns.takeLast(MAX_TURNS).map { t ->
                val author = if (t.fromMe) {
                    ConversationActions.Message.PERSON_USER_SELF
                } else {
                    // One Person per sender, so a group's voices are told apart.
                    people.getOrPut(t.sender.orEmpty()) { android.app.Person.Builder().setKey(t.sender.orEmpty().ifEmpty { "other" }).build() }
                }
                ConversationActions.Message.Builder(author)
                    .setText(t.text)
                    .setReferenceTime(ZonedDateTime.ofInstant(Instant.ofEpochMilli(t.at), ZoneId.systemDefault()))
                    .build()
            }
            val config = TextClassifier.EntityConfig.Builder()
                .includeTypesFromTextClassifier(false)
                .setIncludedTypes(listOf(ConversationAction.TYPE_TEXT_REPLY))
                .build()
            classifier.suggestConversationActions(
                ConversationActions.Request.Builder(messages).setTypeConfig(config).setMaxSuggestions(MAX_REPLIES).build(),
            ).conversationActions
                .filter { it.type == ConversationAction.TYPE_TEXT_REPLY }
                .mapNotNull { it.textReply?.toString()?.trim()?.takeIf(String::isNotEmpty) }
                .distinct()
                .take(MAX_REPLIES)
        }.onFailure { Log.w(TAG, "The text classifier had no replies to offer", it) }.getOrDefault(emptyList())
    }

    private companion object {
        /** How much of the conversation the classifier sees: the latest few turns are what a reply answers. */
        const val MAX_TURNS = 10
        const val MAX_REPLIES = 3
        val TYPES = listOf(TextClassifier.TYPE_ADDRESS, TextClassifier.TYPE_DATE, TextClassifier.TYPE_DATE_TIME, TextClassifier.TYPE_FLIGHT_NUMBER)
        /** Nothing shorter holds an address or a date worth a link. */
        const val MIN_LENGTH = 6
        const val MIN_CONFIDENCE = 0.5f
    }
}
