package com.ericflo.winnow.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ericflo.winnow.WinnowApp
import com.ericflo.winnow.data.splitAddresses
import com.ericflo.winnow.ui.lock.AppLock
import com.ericflo.winnow.ui.lock.LockActivity
import com.ericflo.winnow.ui.lock.LockScreen
import com.ericflo.winnow.ui.theme.WinnowTheme
import com.ericflo.winnow.ui.thread.ThreadScreen
import com.ericflo.winnow.ui.thread.ThreadViewModel

/**
 * One conversation in a chat bubble, floating over other apps. Anything bigger than replying
 * (details, forwarding, reporting) opens the full app. The app lock applies here too.
 */
class BubbleActivity : ComponentActivity() {
    private val container by lazy { (application as WinnowApp).container }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val threadId = intent.getLongExtra(MainActivity.EXTRA_THREAD_ID, -1)
        val recipients = intent.getStringExtra(MainActivity.EXTRA_ADDRESS)
        if (threadId < 0 || recipients == null) return finish()
        setContent {
            WinnowTheme {
                val lock by container.appLock.state.collectAsStateWithLifecycle()
                LaunchedEffect(lock) { if (lock == AppLock.State.LOCKED) LockActivity.show(this@BubbleActivity) }
                Box(Modifier.fillMaxSize()) {
                    ThreadScreen(
                        viewModel = viewModel { ThreadViewModel(container, threadId, splitAddresses(recipients), inBubble = true) },
                        onBack = ::finish,
                        onForward = { text, attachments ->
                            val token = java.util.UUID.randomUUID().toString()
                            container.forwards[token] = text to attachments
                            openApp(Intent(MainActivity.ACTION_FORWARD).putExtra(MainActivity.EXTRA_TOKEN, token))
                        },
                        onReportSpam = { text ->
                            openApp(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$CARRIER_SPAM_SHORT_CODE")).putExtra("sms_body", text))
                        },
                        onOpenDetails = {
                            openApp(
                                Intent(MainActivity.ACTION_OPEN_THREAD)
                                    .putExtra(MainActivity.EXTRA_THREAD_ID, threadId)
                                    .putExtra(MainActivity.EXTRA_ADDRESS, recipients),
                            )
                        },
                        onMessageNumber = { number -> openApp(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}"))) },
                    )
                    if (lock != AppLock.State.UNLOCKED) {
                        LockScreen(checking = lock == AppLock.State.CHECKING, onUnlock = { container.appLock.authenticate(this@BubbleActivity) })
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        container.appLock.onStart()
    }

    override fun onStop() {
        super.onStop()
        container.appLock.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (container.appLock.state.value == AppLock.State.LOCKED) LockActivity.show(this)
    }

    private fun openApp(intent: Intent) {
        startActivity(intent.setClass(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
