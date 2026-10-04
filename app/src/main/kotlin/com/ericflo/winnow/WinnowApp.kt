package com.ericflo.winnow

import android.Manifest
import android.app.Application
import android.provider.Settings
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.serviceLoaderEnabled
import coil3.gif.AnimatedImageDecoder
import android.app.KeyguardManager
import android.app.UiModeManager
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.room.Room
import com.ericflo.winnow.backup.AutoBackup
import com.ericflo.winnow.backup.BackupManager
import com.ericflo.winnow.classifier.http.OkHttpTransport
import com.ericflo.winnow.classify.ClassifierFactory
import com.ericflo.winnow.classify.HistoryReviewer
import com.ericflo.winnow.classify.IncomingMessageHandler
import com.ericflo.winnow.classify.Learner
import com.ericflo.winnow.data.BlockedNumbers
import com.ericflo.winnow.data.ContactLookup
import com.ericflo.winnow.data.ContactsSource
import com.ericflo.winnow.data.ConversationStateStore
import com.ericflo.winnow.data.DemoMessageRepository
import com.ericflo.winnow.data.MediaExport
import com.ericflo.winnow.data.MessageRepository
import com.ericflo.winnow.data.OwnNumbers
import com.ericflo.winnow.data.SecretBox
import com.ericflo.winnow.data.OutgoingAttachment
import com.ericflo.winnow.data.SettingsRepository
import com.ericflo.winnow.data.SharedFiles
import com.ericflo.winnow.ui.lock.AppLock
import com.ericflo.winnow.data.SimCards
import com.ericflo.winnow.data.SimChoice
import com.ericflo.winnow.data.SwitchingMessageRepository
import com.ericflo.winnow.data.TelephonyMessageRepository
import com.ericflo.winnow.data.ThemeMode
import com.ericflo.winnow.data.db.WinnowDatabase
import com.ericflo.winnow.notify.Notifier
import com.ericflo.winnow.sms.CodeCleaner
import com.ericflo.winnow.sms.MessageScheduler
import com.ericflo.winnow.sms.MmsFiles
import com.ericflo.winnow.sms.MmsReceiver
import com.ericflo.winnow.sms.MmsSender
import com.ericflo.winnow.sms.MmsStore
import com.ericflo.winnow.sms.SmsSender
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import android.telephony.TelephonyManager
import com.ericflo.winnow.data.LinkPreviewFetcher

class WinnowApp : Application(), SingletonImageLoader.Factory {
    val container by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // Kept draft attachments nothing refers to any more. Here, as the process starts, before
        // any screen (the main one or a chat bubble) can be holding one in memory.
        container.appScope.launch(Dispatchers.IO) {
            container.draftAttachments.sweep(container.conversationStates.all().map { it.draftAttachments })
        }
        container.appScope.launch { container.trash.purgeExpired() }
        container.appScope.launch { runCatching { container.dailySummary.rearm() } }
        // Yesterday's notification photos: their notifications are gone.
        container.appScope.launch(Dispatchers.IO) { runCatching { container.notifier.purgeImages() } }
    }

    /**
     * GIFs and animated stickers play (Android's own decoder), unless animations are turned off
     * in the phone's accessibility settings; then they hold still on their first frame. There's
     * no network fetcher: every picture Winnow shows is already on the phone.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val animate = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        return ImageLoader.Builder(context)
            // coil-gif would otherwise register its decoder on its own, animations off or not.
            .serviceLoaderEnabled(false)
            .components { if (animate) add(AnimatedImageDecoder.Factory()) }
            .build()
    }
}

/** Debug-only shared preference: pretend the phone has a second SIM. */
const val SIMULATE_SECOND_SIM = "simulate_second_sim"

/** Debug-only shared preference: suggested replies to offer, "|"-separated (emulators have no Smart Reply model). */
const val SIMULATE_REPLIES = "simulate_replies"

/** Hand-rolled dependency graph. Small enough that a DI framework would cost more than it saves. */
class AppContainer(private val context: Context) {
    // Background work (receivers, reviews, sends) must never take the app down with it.
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e("Winnow", "Background task failed", e) },
    )

    val returnedMessages = com.ericflo.winnow.data.ReturnedMessages()

    /**
     * Forwards from a chat bubble on their way to New chat, by a one-time token: copied files
     * never ride in an intent another app could forge to make Winnow attach its own files.
     */
    val forwards = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()

    /** A short message over whatever is on screen, for news with no screen of its own to show it. */
    fun toast(message: String) {
        appScope.launch(Dispatchers.Main) { android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show() }
    }

    private val database by lazy {
        Room.databaseBuilder(context, WinnowDatabase::class.java, "winnow.db").build()
    }
    val settings by lazy { SettingsRepository(context, SecretBox()) }
    val learner by lazy { Learner(database.corrections(), settings) }
    val classifiers by lazy { ClassifierFactory(OkHttpTransport()) { learner.classifier() } }
    val contacts by lazy { ContactLookup(context, appScope) }
    val smartLinks by lazy {
        val debug = context.getSharedPreferences("debug", Context.MODE_PRIVATE)
        com.ericflo.winnow.data.SmartLinks(context) { debug.getString(SIMULATE_REPLIES, null)?.split('|')?.filter(String::isNotBlank) }
    }
    val dailySummary by lazy { com.ericflo.winnow.notify.DailySummary(context, verdictDao, settings, notifier, ::isDefaultSmsApp) }
    val notifier by lazy {
        Notifier(
            context,
            groupFaces = { people ->
                com.ericflo.winnow.data.groupFaces(
                    people.take(com.ericflo.winnow.data.GROUP_FACE_CANDIDATES).map { com.ericflo.winnow.data.Member(it, messages.displayName(it), messages.photoUri(it)) },
                )
            },
            // Off the main thread (icons are drawn in the background); settings are in memory after the first read.
            themeOverridden = { kotlinx.coroutines.runBlocking { settings.current() }.theme != ThemeMode.SYSTEM },
        ).also { notifier -> appScope.launch { settings.settings.collect { notifier.quickReplies = it.quickReplies } } }
    }
    /** The phone's SIMs. Debug builds can pretend there's a second one (see DebugSimReceiver). */
    val sims by lazy {
        val debug = context.getSharedPreferences("debug", Context.MODE_PRIVATE)
        SimCards(context) { debug.getBoolean(SIMULATE_SECOND_SIM, false) }
    }
    // Read when sending, not cached at startup, so a cold process honors the saved setting.
    val smsSender by lazy { SmsSender(context, { settings.current().deliveryReports }, sims::forSending) }

    val sharedFiles by lazy { SharedFiles(context) }
    val mediaExport by lazy { MediaExport(context) }
    fun newVoiceRecorder() = com.ericflo.winnow.data.VoiceRecorder(context)
    val currentLocation by lazy { com.ericflo.winnow.data.CurrentLocation(context) }
    val videoShrinker by lazy { com.ericflo.winnow.data.VideoShrinker(context) }
    val draftAttachments by lazy { com.ericflo.winnow.data.DraftAttachments(context) }
    val linkPreviews by lazy { LinkPreviewFetcher(context, okhttp3.OkHttpClient()) }
    val codeCleaner by lazy { CodeCleaner(context, verdictDao, starredDao) { isDefaultSmsApp() && settings.current().deleteOldCodes } }

    /** Process-wide, so rotating the screen or reopening the activity doesn't re-lock. */
    val appLock = AppLock().also { lock ->
        // Watched here, not in an activity, so a bubble or notification in a fresh process sees it too.
        appScope.launch { settings.settings.collect { lock.update(it.appLock && deviceIsSecure()) } }
    }

    init {
        // Android keeps a per-app night mode (12+), which recolors system bars and dialogs too.
        appScope.launch {
            settings.settings.map { it.theme }.distinctUntilChanged().collect { theme ->
                context.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(
                    when (theme) {
                        ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
                        ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
                        ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
                    },
                )
            }
        }
    }

    /**
     * A screen an intent asked for (a [com.ericflo.winnow.ui.ThreadRoute] or
     * [com.ericflo.winnow.ui.NewChatRoute]). Process-wide, so a share still being copied when the
     * screen rotates reaches the recreated activity.
     */
    val pendingRoute = MutableStateFlow<Any?>(null)

    /** The conversation on screen right now, which shouldn't raise notifications for itself. */
    val visibleThread = MutableStateFlow<Long?>(null)
    val mmsStore by lazy { MmsStore(context) }
    val mmsFiles by lazy { MmsFiles(context) }
    val mmsSender by lazy { MmsSender(context, mmsStore, mmsFiles, sims::forSending) { settings.current().deliveryReports } }
    val mmsReceiver by lazy {
        MmsReceiver(context, mmsStore, mmsFiles, OwnNumbers(context), incoming) { subscriptionId ->
            val s = settings.current()
            val roaming = runCatching {
                context.getSystemService(TelephonyManager::class.java).createForSubscriptionId(subscriptionId).isNetworkRoaming
            }.getOrDefault(false)
            if (roaming) s.autoDownloadMms && s.autoDownloadMmsRoaming else s.autoDownloadMms
        }
    }
    val conversationStates by lazy { ConversationStateStore(database.conversationStates()) }
    val verdictDao by lazy { database.verdicts() }
    val starredDao by lazy { database.starred() }

    private val access = MutableStateFlow(hasSmsAccess())

    /** True once Winnow can read the real SMS store; until then the UI shows sample conversations. */
    val isLive: StateFlow<Boolean> = access

    val messages: MessageRepository by lazy {
        SwitchingMessageRepository(
            live = TelephonyMessageRepository(
                context, verdictDao, database.starred(), contacts, smsSender, mmsSender,
                retryDownload = { mmsReceiver.retryDownload(it) },
                onCorrected = { threadId, message, action -> learner.learn(threadId, message, action) },
                onUncorrected = { threadId -> learner.unlearn(threadId) },
            ),
            demo = DemoMessageRepository(context.packageName),
            isLive = access,
        )
    }

    val contactsSource by lazy { ContactsSource(context, access) }
    val blockedNumbers by lazy { BlockedNumbers(context) }
    val historyReviewer by lazy { HistoryReviewer(context, appScope, verdictDao, contacts, settings, classifiers) }
    // Scheduled texts only ever go out through the real store, never the sample conversations.
    val scheduler by lazy { MessageScheduler(context, database.scheduled()) { messages.takeIf { isDefaultSmsApp() } } }

    /** "Remind me" on messages. */
    val reminders by lazy { com.ericflo.winnow.notify.Reminders(context, database.reminders(), messages::displayName) }

    val autoBackup by lazy { AutoBackup(context, settings, backups) }

    /** Recently deleted: conversations kept 30 days after the user deletes them. */
    val trash by lazy {
        com.ericflo.winnow.backup.Trash(
            context, backups, messages, conversationStates, notifier, canWrite = ::isDefaultSmsApp,
            onGone = { reminders.cancelForThreads(it) },
        )
    }

    val backups by lazy {
        BackupManager(
            context, appScope, verdictDao, database.conversationStates(), database.scheduled(), settings, mmsStore, scheduler,
            database.corrections(), starredDao, learner,
            canWriteMessages = { isDefaultSmsApp() },
            ownNumbers = { OwnNumbers(context).all() },
            drafts = draftAttachments,
        )
    }

    val incoming by lazy {
        IncomingMessageHandler(context, verdictDao, contacts, settings, classifiers, notifier, conversationStates, visibleThread, saveToPhone = mediaExport::save)
    }

    /** The SIM a new message to [threadId] should go out on; null for Android's default. */
    suspend fun simFor(threadId: Long): Int? {
        val available = sims.available().map { it.subscriptionId }
        if (available.size < 2) return null
        return SimChoice.pick(available, conversationStates.get(threadId).subscriptionId, messages.lastIncomingSubscription(threadId), sims.systemDefault())
    }

    fun deviceIsSecure(): Boolean = context.getSystemService(KeyguardManager::class.java).isDeviceSecure

    fun isDefaultSmsApp(): Boolean = context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS)

    /** Call after permission or role changes. */
    fun refreshAccess() {
        contacts.permissionsChanged()
        access.value = hasSmsAccess()
    }

    private fun hasSmsAccess(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
}
