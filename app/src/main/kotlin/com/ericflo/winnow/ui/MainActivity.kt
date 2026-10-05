package com.ericflo.winnow.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import com.ericflo.winnow.data.ReturnedMessages
import com.ericflo.winnow.ui.lock.LockActivity
import com.ericflo.winnow.ui.lock.LockScreen
import com.ericflo.winnow.ui.lock.AppLock
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.ericflo.winnow.WinnowApp
import com.ericflo.winnow.data.joinAddresses
import com.ericflo.winnow.sms.recipientsOf
import com.ericflo.winnow.sms.smsBodyOf
import com.ericflo.winnow.ui.theme.WinnowTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import android.view.Menu
import com.ericflo.winnow.KeyShortcut

class MainActivity : ComponentActivity() {
    private val container by lazy { (application as WinnowApp).container }
    private val pendingRoute get() = container.pendingRoute

    private val roleRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        container.refreshAccess()
        container.defaultRefused.value = !container.isDefaultSmsApp()
        requestCompanionPermissions()
    }

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        container.refreshAccess()
    }

    private val appLock get() = container.appLock

    /** First-run setup is done (see onKeyShortcut). */
    @Volatile private var onboarded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        container.appScope.launch(Dispatchers.IO) {
            container.mmsFiles.cleanUp()
            container.sharedFiles.cleanUp()
            container.codeCleaner.clean()
            runCatching { container.filteredCleaner.clean() }
            // A force-stop cancels alarms without a reboot to re-arm them. Each on its own: one
            // failing mustn't leave the others unarmed.
            runCatching { container.scheduler.rearmAll() }
            runCatching { container.reminders.rearmAll() }
            runCatching { container.autoBackup.ensureScheduled() }
        }
        // With app lock on, recents shows a blank card instead of the conversation list. Android 12
        // has no per-app switch for that, so there it's FLAG_SECURE (which also blocks screenshots).
        lifecycleScope.launch { container.settings.settings.collect { onboarded = it.onboarded } }
        lifecycleScope.launch {
            appLock.enabled.collect { on ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    setRecentsScreenshotEnabled(!on)
                } else if (on) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
        setContent {
            WinnowTheme {
                val lock by appLock.state.collectAsStateWithLifecycle()
                LaunchedEffect(lock) { if (lock == AppLock.State.LOCKED) showLock() }
                Box(Modifier.fillMaxSize()) {
                    WinnowNavHost(
                        container = container,
                        pendingRoute = pendingRoute,
                        onRouteConsumed = { pendingRoute.value = null },
                        onMakeDefault = ::requestDefaultSmsRole,
                    )
                    // Covered whenever not unlocked: while settings load, and under LockActivity, so
                    // nothing shows in the frames before it opens or if something clears it away.
                    if (lock != AppLock.State.UNLOCKED) {
                        LockScreen(checking = lock == AppLock.State.CHECKING, onUnlock = { appLock.authenticate(this@MainActivity) })
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        appLock.onStart()
    }

    override fun onStop() {
        super.onStop()
        appLock.onStop()
    }

    /**
     * A keyboard (a tablet's, a Chromebook's): Ctrl+N starts a chat, Ctrl+F searches (the inbox,
     * or within a conversation), Ctrl+, opens Settings. Only while unlocked.
     */
    override fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean {
        // Not over the lock, and not during first-run setup, which has its own way through.
        if (!event.isCtrlPressed || appLock.state.value != AppLock.State.UNLOCKED || !onboarded) return super.onKeyShortcut(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_N -> pendingRoute.value = NewChatRoute()
            KeyEvent.KEYCODE_F -> container.keyShortcuts.tryEmit(KeyShortcut.FIND)
            KeyEvent.KEYCODE_COMMA -> pendingRoute.value = SettingsRoute
            else -> return super.onKeyShortcut(keyCode, event)
        }
        return true
    }

    /** The shortcuts above, in Android's keyboard shortcuts list (Meta+/). */
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        data += KeyboardShortcutGroup(
            "Winnow",
            listOf(
                KeyboardShortcutInfo("Start chat", KeyEvent.KEYCODE_N, KeyEvent.META_CTRL_ON),
                KeyboardShortcutInfo("Search", KeyEvent.KEYCODE_F, KeyEvent.META_CTRL_ON),
                KeyboardShortcutInfo("Settings", KeyEvent.KEYCODE_COMMA, KeyEvent.META_CTRL_ON),
            ),
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        // A link, share or notification reaching this singleTask activity clears everything
        // above it in the task, LockActivity included.
        if (appLock.state.value == AppLock.State.LOCKED) showLock()
    }

    override fun onResume() {
        super.onResume()
        container.refreshAccess()
        if (appLock.state.value == AppLock.State.LOCKED) showLock()
    }

    private fun showLock() = LockActivity.show(this)

    private fun requestDefaultSmsRole() {
        val roles = getSystemService(RoleManager::class.java)
        if (roles.isRoleAvailable(RoleManager.ROLE_SMS) && !roles.isRoleHeld(RoleManager.ROLE_SMS)) {
            roleRequest.launch(roles.createRequestRoleIntent(RoleManager.ROLE_SMS))
        } else {
            requestCompanionPermissions()
        }
    }

    /**
     * Contacts power the never-classify-contacts rule, notifications are the point of an SMS
     * app, and our own number keeps us out of group MMS participant lists.
     */
    private fun requestCompanionPermissions() {
        // Notifications only became a runtime permission in Android 13.
        val wanted = listOfNotNull(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.POST_NOTIFICATIONS.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU },
            Manifest.permission.READ_PHONE_NUMBERS,
            // Only phones with room for two SIMs need to say which SIM a text goes out on.
            Manifest.permission.READ_PHONE_STATE.takeIf { container.sims.needsPermission() },
        )
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionRequest.launch(missing.toTypedArray())
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_NEW_CHAT -> pendingRoute.value = NewChatRoute()
            ACTION_OPEN_FILTERED -> pendingRoute.value = FilteredRoute
            ACTION_OPEN_TRAIN -> pendingRoute.value = TrainRoute
            ACTION_OPEN_RUN -> pendingRoute.value = intent.getLongExtra(EXTRA_RUN_ID, 0L).takeIf { it != 0L }?.let(::RunRoute) ?: TrainRoute
            ACTION_FORWARD -> container.forwards.remove(intent.getStringExtra(EXTRA_TOKEN) ?: return)?.let { (text, attachments) ->
                pendingRoute.value = NewChatRoute(draft = text, attachments = attachments)
            }
            ACTION_OPEN_THREAD -> {
                val recipients = intent.getStringExtra(EXTRA_ADDRESS) ?: return
                // A reminder opens at its message.
                pendingRoute.value = ThreadRoute(intent.getLongExtra(EXTRA_THREAD_ID, -1), recipients, focus = intent.getStringExtra(EXTRA_FOCUS).orEmpty())
            }
            Intent.ACTION_SENDTO, Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                val recipients = intent.data?.let(::recipientsOf).orEmpty()
                // Shared from another app with no one to send to yet: pick who in New chat.
                if (recipients.isEmpty() && intent.action != Intent.ACTION_SENDTO) return share(intent)
                if (recipients.isEmpty()) return
                val body = intent.getStringExtra("sms_body")
                    ?: intent.data?.let(::smsBodyOf)
                    ?: intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                pendingRoute.value = ThreadRoute(-1, joinAddresses(recipients), body)
            }
        }
    }

    private fun share(intent: Intent) {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        // IntentCompat: the typed getters are Android 13+, and Winnow supports 12.
        val streams = if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        }
        val type = intent.type
        // Picked by name in the share sheet: straight to that conversation.
        val directThread = intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID)?.removePrefix("thread-")?.toLongOrNull()
        // The app scope, not this activity's: a rotation mid-copy mustn't drop the share.
        container.appScope.launch {
            val attachments = withContext(Dispatchers.IO) { streams.mapNotNull { container.sharedFiles.import(it, type) } }
            if (text.isBlank() && attachments.isEmpty()) return@launch
            val recipients = directThread?.let { container.messages.recipientsFor(it) }.orEmpty()
            if (directThread != null && recipients.isNotEmpty()) {
                // Handed to the conversation's composer, open already or opened now (no second copy
                // of it), to join whatever draft is waiting there.
                container.returnedMessages.put(directThread, ReturnedMessages.Returned(text, attachments, separately = false, shared = true))
                pendingRoute.value = ThreadRoute(directThread, joinAddresses(recipients))
            } else {
                // The copied files ride in the route itself, which survives the process being killed.
                pendingRoute.value = NewChatRoute(draft = text, attachments = SharedAttachments.encode(attachments))
            }
        }
    }

    companion object {
        const val ACTION_OPEN_THREAD = "com.ericflo.winnow.OPEN_THREAD"
        /** The launcher's "New chat" shortcut (res/xml/shortcuts.xml). */
        const val ACTION_NEW_CHAT = "com.ericflo.winnow.NEW_CHAT"
        /** The daily summary's notification. */
        const val ACTION_OPEN_FILTERED = "com.ericflo.winnow.OPEN_FILTERED"
        /** The backlog-labeling notification (see BootstrapService). */
        const val ACTION_OPEN_TRAIN = "com.ericflo.winnow.OPEN_TRAIN"
        /** A backlog run's notification: its results (see RunScreen), under [EXTRA_RUN_ID]. */
        const val ACTION_OPEN_RUN = "com.ericflo.winnow.OPEN_RUN"
        const val EXTRA_RUN_ID = "run_id"
        /** A forward from a chat bubble; its text and files wait in [AppContainer.forwards] under [EXTRA_TOKEN]. */
        const val ACTION_FORWARD = "com.ericflo.winnow.FORWARD"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_THREAD_ID = "thread_id"
        /** A message key: the conversation opens scrolled to it. */
        const val EXTRA_FOCUS = "focus"
        /** Comma-joined recipients of the thread to open. */
        const val EXTRA_ADDRESS = "address"
    }
}
