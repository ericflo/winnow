package com.ericflo.winnow.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ericflo.winnow.SIMULATE_SECOND_SIM

/**
 * Debug builds only. Emulators have one SIM, so this pretends there's a second, to exercise
 * the SIM picker. Texts "sent" on the pretend SIM go out on the real one:
 *
 *     adb shell am broadcast -n com.ericflo.winnow/.debug.DebugSimReceiver --ez on true
 */
class DebugSimReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val on = intent.getBooleanExtra("on", true)
        context.getSharedPreferences("debug", Context.MODE_PRIVATE).edit().putBoolean(SIMULATE_SECOND_SIM, on).apply()
        Log.i("WinnowDebugSim", "Simulated second SIM ${if (on) "on" else "off"}")
    }
}
