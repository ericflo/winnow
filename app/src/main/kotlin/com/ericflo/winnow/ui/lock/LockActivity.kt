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
}
