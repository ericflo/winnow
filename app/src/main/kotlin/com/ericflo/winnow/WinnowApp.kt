package com.ericflo.winnow

import android.Manifest
import android.app.Application
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.room.Room
import com.ericflo.winnow.classifier.http.OkHttpTransport
import com.ericflo.winnow.classify.ClassifierFactory
import com.ericflo.winnow.classify.IncomingMessageHandler
import com.ericflo.winnow.data.ContactLookup
import com.ericflo.winnow.data.DemoMessageRepository
import com.ericflo.winnow.data.MessageRepository
import com.ericflo.winnow.data.SecretBox
import com.ericflo.winnow.data.SettingsRepository
import com.ericflo.winnow.data.SwitchingMessageRepository
import com.ericflo.winnow.data.TelephonyMessageRepository
import com.ericflo.winnow.data.db.WinnowDatabase
import com.ericflo.winnow.notify.Notifier
import com.ericflo.winnow.sms.SmsSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class WinnowApp : Application() {
    val container by lazy { AppContainer(this) }
}

/** Hand-rolled dependency graph. Small enough that a DI framework would cost more than it saves. */
class AppContainer(private val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val database by lazy {
        Room.databaseBuilder(context, WinnowDatabase::class.java, "winnow.db").build()
    }
    val settings by lazy { SettingsRepository(context, SecretBox()) }
    val classifiers by lazy { ClassifierFactory(OkHttpTransport()) }
    val contacts by lazy { ContactLookup(context) }
    val notifier by lazy { Notifier(context) }
    val smsSender by lazy { SmsSender(context) }

    private val access = MutableStateFlow(hasSmsAccess())

    /** True once Winnow can read the real SMS store; until then the UI shows sample conversations. */
    val isLive: StateFlow<Boolean> = access

    val messages: MessageRepository by lazy {
        SwitchingMessageRepository(
            live = TelephonyMessageRepository(context, database.verdicts(), contacts, smsSender),
            demo = DemoMessageRepository(),
            isLive = access,
        )
    }

    val incoming by lazy {
        IncomingMessageHandler(context, database.verdicts(), contacts, settings, classifiers, notifier)
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
