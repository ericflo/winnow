package com.ericflo.winnow.data

import android.app.ActivityOptions
import android.app.PendingIntent
import android.app.RemoteAction
import android.content.Context
import android.os.Build
import android.util.Log
import android.util.LruCache
import android.view.textclassifier.TextClassification
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier
import android.view.textclassifier.TextLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
class SmartLinks(context: Context) {
    private val manager = context.getSystemService(TextClassificationManager::class.java)
    private val cache = LruCache<String, List<SmartLink>>(256)

    /** The smart links in [text]; none if the classifier can't say. Off the main thread. */
    suspend fun find(text: String): List<SmartLink> {
        if (text.length < MIN_LENGTH) return emptyList()
        cache.get(text)?.let { return it }
        return withContext(Dispatchers.Default) {
            val found = runCatching {
                val classifier = manager?.textClassifier ?: return@runCatching emptyList()
                if (text.length > classifier.maxGenerateLinksTextLength) return@runCatching emptyList()
                val config = TextClassifier.EntityConfig.Builder().setIncludedTypes(TYPES).includeTypesFromTextClassifier(false).build()
                classifier.generateLinks(TextLinks.Request.Builder(text).setEntityConfig(config).build())
                    .links
                    .mapNotNull { link ->
                        val type = (0 until link.entityCount).map(link::getEntity).firstOrNull { it in TYPES } ?: return@mapNotNull null
                        SmartLink(link.start, link.end, type).takeIf { link.getConfidenceScore(type) >= MIN_CONFIDENCE }
                    }
            }.onFailure { Log.w(TAG, "The text classifier couldn't look at a message", it) }.getOrDefault(emptyList())
            found.also { cache.put(text, it) }
        }
    }

    /** What can be done with [link] in [text]: the classifier's own actions. Off the main thread. */
    suspend fun actions(text: String, link: SmartLink): List<SmartAction> = withContext(Dispatchers.Default) {
        runCatching {
            val classifier = manager?.textClassifier ?: return@runCatching emptyList()
            classifier.classifyText(TextClassification.Request.Builder(text, link.start, link.end).build())
                .actions
                .filter(RemoteAction::isEnabled)
                .map { SmartAction(it.title.toString(), it.actionIntent) }
        }.onFailure { Log.w(TAG, "The text classifier had nothing to offer", it) }.getOrDefault(emptyList())
    }

    private companion object {
        val TYPES = listOf(TextClassifier.TYPE_ADDRESS, TextClassifier.TYPE_DATE, TextClassifier.TYPE_DATE_TIME, TextClassifier.TYPE_FLIGHT_NUMBER)
        /** Nothing shorter holds an address or a date worth a link. */
        const val MIN_LENGTH = 6
        const val MIN_CONFIDENCE = 0.5f
    }
}
