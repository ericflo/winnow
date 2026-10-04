package com.ericflo.winnow.ui.lock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ericflo.winnow.WinnowApp
import com.ericflo.winnow.ui.theme.WinnowTheme

/**
 * The app lock, as its own activity on top of whatever was open. Being a separate window it
 * covers everything below it, including dialogs, sheets and menus, which an overlay drawn
 * inside the app's own window can't. The app's state underneath is untouched. Back sends the
 * task home rather than revealing anything, and this is what recents shows while locked.
 */
class LockActivity : ComponentActivity() {
    private val lock by lazy { (application as WinnowApp).container.appLock }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        live.incrementAndGet()
        counted = true
        requestedAt = 0L
        enableEdgeToEdge()
        onBackPressedDispatcher.addCallback(this) { moveTaskToBack(true) }
        setContent {
            WinnowTheme {
                val state by lock.state.collectAsStateWithLifecycle()
                LaunchedEffect(state) { if (state == AppLock.State.UNLOCKED) finish() }
                LockScreen(checking = false, onUnlock = { lock.authenticate(this@LockActivity) })
            }
        }
        if (savedInstanceState == null) lock.authenticate(this)
    }

    // Uncounted as soon as it's on its way out: when a link clears it, the activity underneath
    // resumes (and asks for a lock) before this one is destroyed.
    private var counted = false

    private fun uncount() {
        if (counted) live.decrementAndGet()
        counted = false
    }

    override fun onPause() {
        super.onPause()
        if (isFinishing) uncount()
    }

    override fun onDestroy() {
        super.onDestroy()
        uncount()
    }

    companion object {
        private val live = java.util.concurrent.atomic.AtomicInteger()

        // A start in flight: onNewIntent, onResume and the state change can all ask within one
        // frame, before the first LockActivity exists. Expires in case a start never lands.
        @Volatile private var requestedAt = 0L

        /** Opens the lock unless one is up or on its way. */
        fun show(from: android.app.Activity) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (live.get() > 0 || (requestedAt != 0L && now - requestedAt < 2_000)) return
            requestedAt = now
            from.startActivity(android.content.Intent(from, LockActivity::class.java))
        }
    }
}
