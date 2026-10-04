package com.ericflo.winnow.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.ericflo.winnow.R
import com.ericflo.winnow.WinnowApp
import com.ericflo.winnow.data.ConversationSummary
import com.ericflo.winnow.data.withState
import com.ericflo.winnow.ui.MainActivity
import com.ericflo.winnow.ui.components.shortTimestamp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import androidx.room.InvalidationTracker
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.collectAsState

/**
 * A home-screen widget of the inbox's newest conversations: who, what they said last, when,
 * and which are unread. Never anything filtered or archived; with app lock on, only that
 * Winnow is locked, since a widget is on the home screen for anyone holding the phone.
 */
class ConversationsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) = coroutineScope {
        val first = load(context)
        val changes = (context.applicationContext as WinnowApp).container.widgetUpdates.changes
        // Read again on every change: a session outlives one update (Glance keeps it a while and
        // only recomposes), so what was loaded first can't simply stand. Once per change here,
        // not in the composition, which Glance runs once per size the launcher asks for.
        val content = changes.map { load(context) }.stateIn(this, SharingStarted.Eagerly, first)
        provideContent {
            val shown by content.collectAsState()
            GlanceTheme {
                Body(shown)
            }
        }
    }

    private sealed interface Content {
        data object Locked : Content
        data object NotSetUp : Content
        data class Rows(val conversations: List<ConversationSummary>) : Content
    }

    private suspend fun load(context: Context): Content {
        val container = (context.applicationContext as WinnowApp).container
        val settings = container.settings.current()
        if (settings.appLock) return Content.Locked
        if (!container.isLive.value) return Content.NotSetUp
        val classifying = container.incoming.classifying.value
        val conversations = container.messages.conversations().first()
            .withState(container.conversationStates.all().associateBy { it.threadId })
            .filter { !it.isFiltered && !it.archived }
            // A text being classified right now (its notification waits too): a scam mustn't sit
            // on the home screen until it's filtered. Shown once it's decided.
            .filterNot { it.threadId in classifying }
            .take(MAX_ROWS)
        return Content.Rows(conversations)
    }

    @Composable
    private fun Body(content: Content) {
        Column(
            modifier = GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(24.dp).padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp)) {
                Text(
                    "Winnow",
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Medium),
                    modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity(openApp())),
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = GlanceModifier.size(36.dp).cornerRadius(18.dp).background(GlanceTheme.colors.primaryContainer)
                        .clickable(actionStartActivity(newChat())),
                ) {
                    Image(
                        ImageProvider(R.drawable.ic_chat),
                        contentDescription = "Start chat",
                        colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimaryContainer),
                        modifier = GlanceModifier.size(20.dp),
                    )
                }
            }
            when (content) {
                Content.Locked -> Note("Winnow is locked. Open it to see your conversations.")
                Content.NotSetUp -> Note("Open Winnow to make it your SMS app.")
                is Content.Rows -> if (content.conversations.isEmpty()) {
                    Note("No conversations yet.")
                } else {
                    LazyColumn {
                        items(content.conversations, itemId = { it.threadId }) { Row(it) }
                    }
                }
            }
        }
    }

    @Composable
    private fun Note(text: String) {
        Text(
            text,
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp),
            modifier = GlanceModifier.padding(8.dp).clickable(actionStartActivity(openApp())),
        )
    }

    @Composable
    private fun Row(c: ConversationSummary) {
        val unread = c.unread && !c.muted
        Column(
            modifier = GlanceModifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp).clickable(actionStartActivity(openThread(c))),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxWidth()) {
                Text(
                    c.displayName,
                    maxLines = 1,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 15.sp,
                        fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                    ),
                    modifier = GlanceModifier.defaultWeight(),
                )
                Spacer(GlanceModifier.width(8.dp))
                Text(
                    // A clock time, not "5 min": a widget isn't redrawn every minute.
                    shortTimestamp(c.timestamp, relative = false),
                    style = TextStyle(color = if (unread) GlanceTheme.colors.primary else GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                )
            }
            Text(
                c.draft?.let { "Draft: $it" } ?: if (c.notSent) "Not sent: ${c.snippet}" else c.snippet,
                maxLines = 1,
                style = TextStyle(
                    color = if (unread) GlanceTheme.colors.onSurface else GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 13.sp,
                    fontWeight = if (unread) FontWeight.Medium else FontWeight.Normal,
                ),
            )
        }
    }

    companion object {
        /** A widget shows a handful; the app is a tap away for the rest. */
        private const val MAX_ROWS = 12

        private fun openApp() = Intent(Intent.ACTION_MAIN).setClassName("com.ericflo.winnow", MainActivity::class.java.name)
            .addCategory(Intent.CATEGORY_LAUNCHER)

        private fun newChat() = Intent(MainActivity.ACTION_NEW_CHAT).setClassName("com.ericflo.winnow", MainActivity::class.java.name)

        private fun openThread(c: ConversationSummary) = Intent(MainActivity.ACTION_OPEN_THREAD)
            .setClassName("com.ericflo.winnow", MainActivity::class.java.name)
            .putExtra(MainActivity.EXTRA_THREAD_ID, c.threadId)
            .putExtra(MainActivity.EXTRA_ADDRESS, com.ericflo.winnow.data.joinAddresses(c.recipients))
            // Each row its own intent, not one whose extras the last row overwrote.
            .setData(Uri.parse("winnow://thread/${c.threadId}"))
    }
}

/** The widget's receiver, as Android sees it; it also starts [WidgetUpdates]. */
class ConversationsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ConversationsWidget()

    override fun onUpdate(context: Context, appWidgetManager: android.appwidget.AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        (context.applicationContext as WinnowApp).container.widgetUpdates.start()
    }
}

/**
 * Keeps the widget current while Winnow runs: messages arriving, sent or read, verdicts and
 * corrections, archive, pin, mute, contacts and settings changing. It listens for changes only
 * (cheap), and redraws when there's a widget to redraw, a second after the last one, so a burst
 * is one update.
 */
class WidgetUpdates(private val context: Context, private val scope: CoroutineScope) {
    private val started = AtomicBoolean(false)
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Something the widget shows may have changed (debounced): a showing widget reads again. */
    val changes: SharedFlow<Unit> = _changes

    @OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun start() {
        if (!started.compareAndSet(false, true)) return
        val container = (context.applicationContext as WinnowApp).container
        scope.launch {
            merge(
                // Only once Winnow may read texts (an observer before that is refused, quietly);
                // and the widget's own "make it your SMS app" note goes when it can.
                container.isLive.flatMapLatest { live -> if (live) messageStoreChanges().onStart { emit(Unit) } else flowOf(Unit) },
                // A text's classification finishing brings it in.
                container.incoming.classifying.map { },
                roomChanges(container),
                container.contacts.changes(),
                container.settings.settings.map { it.appLock }.distinctUntilChanged(),
            )
                .debounce(1_000)
                .collect {
                    val ids = runCatching { GlanceAppWidgetManager(context).getGlanceIds(ConversationsWidget::class.java) }.getOrDefault(emptyList())
                    if (ids.isEmpty()) return@collect
                    // A widget with a session running reads again on its own; one without needs one started.
                    _changes.tryEmit(Unit)
                    runCatching { ConversationsWidget().updateAll(context) }
                }
        }
    }

    /** The SMS and MMS store changing: anything arriving, sent, read or deleted. */
    private fun messageStoreChanges() = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        listOf(Telephony.MmsSms.CONTENT_URI, Telephony.Sms.CONTENT_URI, Telephony.Mms.CONTENT_URI).forEach {
            runCatching { context.contentResolver.registerContentObserver(it, true, observer) }
        }
        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }

    /** Verdicts (a text filtered a moment after it lands) and conversation state (archive, pin, mute, drafts). */
    private fun roomChanges(container: com.ericflo.winnow.AppContainer) = callbackFlow {
        val tracker = container.database.invalidationTracker
        val observer = object : InvalidationTracker.Observer(arrayOf("verdicts", "conversation_state")) {
            override fun onInvalidated(tables: Set<String>) {
                trySend(Unit)
            }
        }
        tracker.addObserver(observer)
        awaitClose { tracker.removeObserver(observer) }
    }
}
