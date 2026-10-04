package com.ericflo.winnow.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ericflo.winnow.WinnowApp
import kotlinx.coroutines.launch

/**
 * Debug builds only. Runs the verification-code cleanup as if [hours] had passed, since an
 * emulator can't wait a day. Still honors the setting:
 *
 *     adb shell am broadcast -n com.ericflo.winnow/.debug.DebugCodesReceiver --el hours 48
 */
class DebugCodesReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val hours = intent.getLongExtra("hours", 48)
        val container = (context.applicationContext as WinnowApp).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                val deleted = container.codeCleaner.clean(now = System.currentTimeMillis() + hours * 3_600_000)
                Log.i("WinnowDebugCodes", "Cleanup as if $hours h later deleted $deleted codes")
            } finally {
                pending.finish()
            }
        }
    }
}
