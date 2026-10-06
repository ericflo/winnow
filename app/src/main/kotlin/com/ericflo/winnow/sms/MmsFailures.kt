package com.ericflo.winnow.sms

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import android.telephony.TelephonyManager

/**
 * Why each picture message couldn't be downloaded, in words: shown under it in its conversation,
 * and kept for the problem report with what the phone's network was doing, so a failure says
 * where it broke (starting the download, Android's MMS service or the carrier, or reading what
 * came back) instead of only that it did. Nothing of the message itself.
 */
class MmsFailures(private val context: Context) {
    private val prefs by lazy { context.getSharedPreferences("mms_failures", Context.MODE_PRIVATE) }

    /** Records why [mmsId] failed; true when that's new for it (the first failure, or a different reason). */
    fun record(mmsId: Long, why: String): Boolean {
        val before = prefs.getString(mmsId.toString(), null)
        prefs.edit().putString(mmsId.toString(), why).apply()
        return before != why
    }

    fun why(mmsId: Long): String? = prefs.getString(mmsId.toString(), null)

    fun clear(mmsId: Long) {
        if (prefs.contains(mmsId.toString())) prefs.edit().remove(mmsId.toString()).apply()
    }

    /** What the phone's network was doing, for the report: the network in use, mobile data, airplane mode, roaming, the carrier. */
    @android.annotation.SuppressLint("MissingPermission") // Checked first: canReadPhoneState.
    fun network(subscriptionId: Int): String = buildList {
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            add(
                when {
                    caps == null -> "no network"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "on Wi-Fi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "on mobile data"
                    else -> "on another network"
                },
            )
        }
        val tm = runCatching {
            context.getSystemService(TelephonyManager::class.java).let { if (subscriptionId >= 0) it.createForSubscriptionId(subscriptionId) else it }
        }.getOrNull()
        if (canReadPhoneState()) runCatching { add(if (tm?.isDataEnabled == true) "mobile data on" else "mobile data off") }
        runCatching { if (Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1) add("airplane mode") }
        runCatching { if (tm?.isNetworkRoaming == true) add("roaming") }
        runCatching { tm?.simOperatorName?.takeIf { it.isNotBlank() }?.let { add("carrier $it") } }
    }.joinToString(", ")

    /** Whether mobile data is off for [subscriptionId]: picture messages come over it. */
    @android.annotation.SuppressLint("MissingPermission") // Checked first: canReadPhoneState.
    fun mobileDataOff(subscriptionId: Int): Boolean = canReadPhoneState() && runCatching {
        context.getSystemService(TelephonyManager::class.java).let { if (subscriptionId >= 0) it.createForSubscriptionId(subscriptionId) else it }.isDataEnabled == false
    }.getOrDefault(false)

    // Whether mobile data is on needs this; without it the report leaves that out.
    private fun canReadPhoneState() =
        androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED

    companion object {
        /**
         * Android's MMS service's result code for a download, in words (SmsManager.MMS_ERROR_*,
         * by number: the newer ones aren't on every Android). [http] is the carrier's answer, when
         * the service said there was one.
         */
        fun describe(resultCode: Int, http: Int): String = when (resultCode) {
            2 -> "the carrier's MMS settings (its APN) are missing or wrong"
            3 -> "couldn't connect to the carrier's MMS network"
            4 -> "the carrier's MMS server refused it" + (if (http > 0) " (HTTP $http)" else "")
            5 -> "the download was cut off"
            6 -> "Android asked to try again later"
            7 -> "the carrier's MMS settings aren't set up for this SIM"
            8 -> "no mobile data network to download it over"
            9 -> "the SIM it came on isn't usable"
            10 -> "the SIM it came on isn't active"
            11 -> "mobile data is off, and picture messages come over mobile data"
            12 -> "the carrier has picture messages turned off"
            else -> "Android's MMS service failed (code $resultCode${if (http > 0) ", HTTP $http" else ""})"
        }
    }
}
