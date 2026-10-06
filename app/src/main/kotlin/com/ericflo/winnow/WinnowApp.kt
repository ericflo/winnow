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
import android.content.pm.ApplicationInfo
import android.os.StrictMode
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
import com.ericflo.winnow.data.NoAccessMessageRepository
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
import com.ericflo.winnow.sms.MmsRetries
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
        // First, so a crash anywhere after this is on record (Settings → About).
        container.problems.install()
        container.appScope.launch(Dispatchers.IO) { runCatching { container.problems.load() } }
        // Debug builds log main-thread disk and network work, and leaked resources: on a real
        // phone, slower than any emulator, those are where freezes and "not responding" come from.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build())
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectLeakedRegistrationObjects().penaltyLog().build())
        }
        // Kept draft attachments nothing refers to any more. Here, as the process starts, before
        // any screen (the main one or a chat bubble) can be holding one in memory.
        container.appScope.launch(Dispatchers.IO) {
            container.draftAttachments.sweep(container.conversationStates.all().map { it.draftAttachments })
        }
        container.appScope.launch { container.trash.purgeExpired() }
        container.appScope.launch { runCatching { container.dailySummary.rearm() } }
        // A force stop cancels the app's jobs; waiting MMS retries are set again.
        container.appScope.launch(Dispatchers.IO) { runCatching { container.mmsRetries.rearm() } }
        // Phone-number formatting reads its country's rules from disk the first time; done here, off
        // the main thread, rather than by the first screen to show a number (a conversation opened
        // from a notification, say). The contact list is read again on every resume (refreshAccess).
        container.appScope.launch(Dispatchers.IO) { runCatching { ContactLookup.formatAddress("+12025550100") } }
        // A widget on the home screen follows the inbox while the app runs.
        container.widgetUpdates.start()
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

/** What a hardware keyboard shortcut asks the screen on show to do. */
enum class KeyShortcut { FIND }

/** Debug-only shared preference: pretend the phone has a second SIM. */
const val SIMULATE_SECOND_SIM = "simulate_second_sim"

/** Debug-only shared preference: suggested replies to offer, "|"-separated (emulators have no Smart Reply model). */
const val SIMULATE_REPLIES = "simulate_replies"

/** Hand-rolled dependency graph. Small enough that a DI framework would cost more than it saves. */
class AppContainer(private val context: Context) {
    /** The application context, for starting services from view models. */
    val appContext: Context get() = context

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

    /** Winnow's own tables; outside here only to watch them (see WidgetUpdates). */
    internal val database by lazy {
        Room.databaseBuilder(context, WinnowDatabase::class.java, "winnow.db").build()
    }
    val settings by lazy { SettingsRepository(context, SecretBox()) }
    val correctionDao by lazy { database.corrections() }
    val learner: Learner by lazy {
        // Kept for this install: an update (a new model, a new way of fitting) fits afresh.
        val install = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        Learner(correctionDao, settings, com.ericflo.winnow.classify.PersonalModelStore(java.io.File(context.filesDir, "personal-model.bin"), install), database.fits(), appScope) {
            modelLab.inUse()
        }
    }
    /** Backlog runs and every answer they got (see RunEntity). */
    val runDao by lazy { database.runs() }
    /** Every fit of the on-device model (see ModelFitEntity). */
    val fitDao by lazy { database.fits() }
    /** Evaluations the user ran (see EvalEntity). */
    val evalDao by lazy { database.evals() }
    /** Fits of the on-device model kept to score and compare later (see ModelKeeper). */
    val modelKeeper by lazy {
        com.ericflo.winnow.classify.ModelKeeper(learner, fitDao, com.ericflo.winnow.classify.ModelSnapshots(java.io.File(context.filesDir, "model-fits")))
    }
    /** Asking the service about the user's labeled texts with and without their examples (see ExamplesExperiment). */
    val examplesExperiment by lazy {
        com.ericflo.winnow.classify.ExamplesExperiment(context, appScope, verdictDao, correctionDao, contacts, settings, classifiers, bootstrap, evalDao)
    }
    /** Models the user designs, trains on the phone, scores and puts in use (see ModelLab). */
    val modelLab: com.ericflo.winnow.classify.ModelLab by lazy {
        com.ericflo.winnow.classify.ModelLab(
            appScope, correctionDao, verdictDao, contacts, settings, com.ericflo.winnow.data.MessageTexts(context), evalDao,
            repliedThreads = { bootstrap.threadsWithOutgoing() },
            dir = java.io.File(context.filesDir, "lab"),
        ) { learner.reload() }
    }

    /** After a Train round or a backlog run: a Lab model in use learns what it taught, if the user wants it to. */
    fun retrainLabModelIfWanted() {
        appScope.launch {
            val s = settings.current()
            if (s.labModel != null && s.labAutoRetrain) modelLab.retrainInUse()
        }
    }

    /** When Winnow stopped being the SMS app, and what arrived until it was again (see RoleWatch). */
    val roleWatch by lazy { com.ericflo.winnow.data.RoleWatch(context) }

    /** Why a text went where it did (see Provenance). */
    val provenance by lazy {
        com.ericflo.winnow.classify.ProvenanceSource(verdictDao, correctionDao, runDao, fitDao, settings, com.ericflo.winnow.data.MessageTexts(context))
    }
    val classifiers by lazy {
        // Without contacts, a contact's text looks like a stranger's: none may go to a classifier service.
        ClassifierFactory(OkHttpTransport(), { learner.classifier() }) { if (contacts.canRead()) null else ClassifierFactory.CONTACTS_HIDDEN }
    }
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
    val smsSender by lazy { SmsSender(context, { settings.current().deliveryReports }, sims::forSending) { settings.current().simpleCharacters } }

    val sharedFiles by lazy { SharedFiles(context) }
    val mediaExport by lazy { MediaExport(context) }
    fun newVoiceRecorder() = com.ericflo.winnow.data.VoiceRecorder(context)
    val currentLocation by lazy { com.ericflo.winnow.data.CurrentLocation(context) }
    val videoShrinker by lazy { com.ericflo.winnow.data.VideoShrinker(context) }
    val draftAttachments by lazy { com.ericflo.winnow.data.DraftAttachments(context) }
    val linkPreviews by lazy { LinkPreviewFetcher(context, okhttp3.OkHttpClient()) }
    val codeCleaner by lazy {
        CodeCleaner(context, verdictDao, starredDao, reminded = { reminders.all().keys }) { isDefaultSmsApp() && settings.current().deleteOldCodes }
    }

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
        MmsReceiver(
            context, mmsStore, mmsFiles, OwnNumbers(context), incoming,
            autoDownload = { subscriptionId ->
                val s = settings.current()
                val roaming = runCatching {
                    context.getSystemService(TelephonyManager::class.java).createForSubscriptionId(subscriptionId).isNetworkRoaming
                }.getOrDefault(false)
                if (roaming) s.autoDownloadMms && s.autoDownloadMmsRoaming else s.autoDownloadMms
            },
            retries = mmsRetries,
        )
    }

    /** Failed MMS downloads, fetched again by themselves (see MmsRetries). */
    val mmsRetries by lazy { MmsRetries(context, sendReadiness) }
    val conversationStates by lazy { ConversationStateStore(database.conversationStates()) }
    val verdictDao by lazy { database.verdicts() }
    val starredDao by lazy { database.starred() }

    private val access = MutableStateFlow(hasSmsAccess())

    /** True once Winnow can read the real SMS store; until then there's nothing to show. */
    val isLive: StateFlow<Boolean> = access

    val messages: MessageRepository by lazy {
        SwitchingMessageRepository(
            live = TelephonyMessageRepository(
                context, verdictDao, database.starred(), contacts, smsSender, mmsSender,
                retryDownload = { mmsReceiver.retryDownload(it) },
                onCorrected = { threadId, message, action -> learner.learn(threadId, message, action) },
                onUncorrected = { threadId -> learner.unlearn(threadId) },
                onLabelsDropped = { threadId, keys, action -> learner.dropForCorrection(threadId, keys, action) },
                onLabelsRestored = { labels -> learner.unlabel(emptyList(), labels) },
            ),
            demo = NoAccessMessageRepository(),
            isLive = access,
            scope = appScope,
        )
    }

    val contactsSource by lazy { ContactsSource(context) }
    val blockedNumbers by lazy { BlockedNumbers(context) }
    val historyReviewer by lazy { HistoryReviewer(context, appScope, verdictDao, contacts, settings, classifiers) }

    /** Teaching the on-device model the backlog with the chosen classifier service (Train Winnow). */
    val bootstrap by lazy {
        com.ericflo.winnow.classify.Bootstrap(
            context, appScope, messages, verdictDao, correctionDao, learner, contacts, settings, classifiers, runDao,
            onFinished = {
                historyReviewer.refresh()
                // The model a backlog run leaves is kept, to compare with what comes after.
                appScope.launch { runCatching { modelKeeper.keepCurrent() } }
                retrainLabModelIfWanted()
            },
        )
    }
    // Scheduled texts only ever go out through the real store, once Winnow is the SMS app.
    val scheduler by lazy { MessageScheduler(context, database.scheduled()) { messages.takeIf { isDefaultSmsApp() } } }

    /** Settings → Clear out old filtered texts. */
    val filteredCleaner by lazy {
        com.ericflo.winnow.backup.FilteredCleaner(
            context, messages, conversationStates, starredDao, verdictDao, trash,
            busyThreads = { reminders.all().values.mapTo(HashSet()) { it.threadId } + database.scheduled().all().map { it.threadId } },
        ) {
            isDefaultSmsApp() && settings.current().clearOldFiltered
        }
    }

    /** Hardware keyboard shortcuts the screen on show takes up (see MainActivity.onKeyShortcut). */
    val keyShortcuts = kotlinx.coroutines.flow.MutableSharedFlow<KeyShortcut>(extraBufferCapacity = 1)

    /** Train Winnow: rounds of guesses for the user to confirm or fix (see Training). */
    /** Crashes and freezes on this phone, for a report the user can share. */
    val problems by lazy { com.ericflo.winnow.diagnostics.ProblemLog(context) }

    val training by lazy { com.ericflo.winnow.classify.Training(context, messages, verdictDao, correctionDao, learner, contacts) }

    /** The user's labels: filing messages by category and teaching the on-device model. */
    val labeler by lazy { com.ericflo.winnow.classify.Labeler(messages, verdictDao, learner, contacts, settings) }

    /** Reply reminders the user said "not now" to. */
    val dismissedNudges by lazy { com.ericflo.winnow.data.DismissedNudges(context, appScope) }

    /** Airplane mode and mobile data, said before a send fails (see SendReadiness). */
    val sendReadiness by lazy { com.ericflo.winnow.sms.SendReadiness(context) }

    /** Contacts' birthdays, for birthday reminders. */
    val birthdays by lazy { com.ericflo.winnow.data.Birthdays(context) }

    /** Keeps the home-screen widget current while Winnow runs. */
    val widgetUpdates by lazy { com.ericflo.winnow.widget.WidgetUpdates(context, appScope) }

    /** "Remind me" on messages. */
    val reminders by lazy {
        com.ericflo.winnow.notify.Reminders(
            context, database.reminders(), messages::displayName,
            hideOnLockScreen = { settings.current().hideOnLockScreen },
            messageExists = { key, at ->
                // Just the one row's date: SMS in milliseconds, MMS in seconds.
                val (kind, id) = key.split(':').let { it.getOrNull(0) to it.getOrNull(1)?.toLongOrNull() }
                val uri = when (kind) {
                    "sms" -> android.provider.Telephony.Sms.CONTENT_URI
                    "mms" -> android.provider.Telephony.Mms.CONTENT_URI
                    else -> null
                }
                val date = if (uri == null || id == null) null else kotlinx.coroutines.withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.query(android.content.ContentUris.withAppendedId(uri, id), arrayOf("date"), null, null, null)
                            ?.use { c -> if (c.moveToFirst()) c.getLong(0) * (if (kind == "mms") 1000 else 1) else null }
                    }.getOrNull()
                }
                date != null && (at == 0L || date == at)
            },
        )
    }

    val autoBackup by lazy { AutoBackup(context, settings, backups) }

    /** Recently deleted: conversations kept 30 days after the user deletes them. */
    val trash by lazy {
        com.ericflo.winnow.backup.Trash(
            context, backups, messages, conversationStates, notifier, canWrite = ::isDefaultSmsApp,
            onGone = { reminders.cancelForThreads(it) },
            onMessagesGone = { keys -> keys.forEach { reminders.cancel(it) } },
        )
    }

    /** Settings → Backup password. */
    val backupPassword by lazy { com.ericflo.winnow.backup.BackupPassword(context) }

    val backups by lazy {
        BackupManager(
            context, appScope, verdictDao, database.conversationStates(), database.scheduled(), settings, mmsStore, scheduler,
            database.corrections(), starredDao, learner,
            canWriteMessages = { isDefaultSmsApp() },
            ownNumbers = { OwnNumbers(context).all() },
            drafts = draftAttachments,
            reminders = reminders,
            password = backupPassword,
            contacts = contacts,
            lab = modelLab,
        )
    }

    val incoming by lazy {
        IncomingMessageHandler(
            context, verdictDao, contacts, settings, classifiers, notifier, conversationStates, visibleThread, saveToPhone = mediaExport::save,
            learnFromAnswer = { threadId, key, message, category -> learner.learnFromAnswer(threadId, key, message, category) },
        )
    }

    /** The SIM a new message to [threadId] should go out on; null for Android's default. */
    suspend fun simFor(threadId: Long): Int? {
        val available = sims.available().map { it.subscriptionId }
        if (available.size < 2) return null
        return SimChoice.pick(available, conversationStates.get(threadId).subscriptionId, messages.lastIncomingSubscription(threadId), sims.systemDefault())
    }

    fun deviceIsSecure(): Boolean = context.getSystemService(KeyguardManager::class.java).isDeviceSecure

    fun isDefaultSmsApp(): Boolean = context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS)

    /**
     * Asked to be the default SMS app, and still isn't: the user said no, or (Android 15+, an app
     * installed from a browser) Android refused it as a restricted setting. See RestrictedSettingHelp.
     */
    val defaultRefused = MutableStateFlow(false)

    /** Call after permission or role changes. */
    fun refreshAccess() {
        contacts.permissionsChanged()
        access.value = hasSmsAccess()
        // In case Android's word of the SMS app changing was missed (see RoleWatch.Receiver).
        val isDefault = isDefaultSmsApp()
        appScope.launch(Dispatchers.IO) { runCatching { roleWatch.check(isDefault, messages::displayName) } }
        // Anything whose alarm Android held back goes out now that Winnow's in front (see sendDue).
        if (isDefault) appScope.launch(Dispatchers.IO) {
            runCatching { scheduler.sendDue() }
            runCatching { reminders.fireDue() }
            // A picture message's download retry is a job, which Android holds back the same way.
            runCatching { mmsRetries.runDue(mmsReceiver::retryDownload) }
        }
        restricted.value = backgroundRestricted()
        if (isDefaultSmsApp()) defaultRefused.value = false
    }

    /**
     * Android is holding Winnow back in the background: the "Restricted" battery setting (Samsung's
     * too), or a standby bucket so low that its alarms and jobs wait. Texts still arrive; scheduled
     * texts, reminders and the evening summary don't go out on time.
     */
    val restricted = MutableStateFlow(false)

    private fun backgroundRestricted(): Boolean {
        val am = context.getSystemService(android.app.ActivityManager::class.java)
        val usage = context.getSystemService(android.app.usage.UsageStatsManager::class.java)
        return runCatching { am.isBackgroundRestricted }.getOrDefault(false) ||
            runCatching { usage.appStandbyBucket >= android.app.usage.UsageStatsManager.STANDBY_BUCKET_RESTRICTED }.getOrDefault(false)
    }

    private fun hasSmsAccess(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
}
