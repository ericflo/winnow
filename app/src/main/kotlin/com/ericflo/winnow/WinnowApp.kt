package com.ericflo.winnow

import android.Manifest
import android.app.Application
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.room.Room
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
import com.ericflo.winnow.data.MessageRepository
import com.ericflo.winnow.data.OwnNumbers
import com.ericflo.winnow.data.SecretBox
import com.ericflo.winnow.data.OutgoingAttachment
import com.ericflo.winnow.data.SettingsRepository
import com.ericflo.winnow.data.SharedFiles
import com.ericflo.winnow.data.SimCards
import com.ericflo.winnow.data.SimChoice
import com.ericflo.winnow.data.SwitchingMessageRepository
import com.ericflo.winnow.data.TelephonyMessageRepository
import com.ericflo.winnow.data.db.WinnowDatabase
import com.ericflo.winnow.notify.Notifier
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

class WinnowApp : Application() {
    val container by lazy { AppContainer(this) }
}

/** Debug-only shared preference: pretend the phone has a second SIM. */
const val SIMULATE_SECOND_SIM = "simulate_second_sim"

/** Hand-rolled dependency graph. Small enough that a DI framework would cost more than it saves. */
class AppContainer(private val context: Context) {
    // Background work (receivers, reviews, sends) must never take the app down with it.
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e("Winnow", "Background task failed", e) },
    )

    private val database by lazy {
        Room.databaseBuilder(context, WinnowDatabase::class.java, "winnow.db").build()
    }
    val settings by lazy { SettingsRepository(context, SecretBox()) }
    val learner by lazy { Learner(database.corrections(), settings) }
    val classifiers by lazy { ClassifierFactory(OkHttpTransport()) { learner.classifier() } }
    val contacts by lazy { ContactLookup(context) }
    val notifier by lazy { Notifier(context) }
    /** The phone's SIMs. Debug builds can pretend there's a second one (see DebugSimReceiver). */
    val sims by lazy {
        val debug = context.getSharedPreferences("debug", Context.MODE_PRIVATE)
        SimCards(context) { debug.getBoolean(SIMULATE_SECOND_SIM, false) }
    }
    // Read when sending, not cached at startup, so a cold process honors the saved setting.
    val smsSender by lazy { SmsSender(context, { settings.current().deliveryReports }, sims::forSending) }

    val sharedFiles by lazy { SharedFiles(context) }

    /** Photos shared into Winnow, waiting for the user to pick a conversation. */
    val pendingShare = MutableStateFlow<List<OutgoingAttachment>>(emptyList())

    /** The conversation on screen right now, which shouldn't raise notifications for itself. */
    val visibleThread = MutableStateFlow<Long?>(null)
    val mmsStore by lazy { MmsStore(context) }
    val mmsFiles by lazy { MmsFiles(context) }
    val mmsSender by lazy { MmsSender(context, mmsStore, mmsFiles, sims::forSending) }
    val mmsReceiver by lazy { MmsReceiver(context, mmsStore, mmsFiles, OwnNumbers(context), incoming) }
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

    val backups by lazy {
        BackupManager(
            context, appScope, verdictDao, database.conversationStates(), database.scheduled(), settings, mmsStore, scheduler,
            database.corrections(), starredDao, learner,
        ) { isDefaultSmsApp() }
    }

    val incoming by lazy {
        IncomingMessageHandler(context, verdictDao, contacts, settings, classifiers, notifier, conversationStates, visibleThread)
    }

    /** The SIM a new message to [threadId] should go out on; null for Android's default. */
    suspend fun simFor(threadId: Long): Int? {
        val available = sims.available().map { it.subscriptionId }
        if (available.size < 2) return null
        return SimChoice.pick(available, conversationStates.get(threadId).subscriptionId, messages.lastIncomingSubscription(threadId), sims.systemDefault())
    }

    fun isDefaultSmsApp(): Boolean = context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS)

    /** Call after permission or role changes. */
    fun refreshAccess() {
        contacts.clear()
        access.value = hasSmsAccess()
    }

    private fun hasSmsAccess(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
}
