package com.ericflo.winnow.sms

import android.content.Context
import android.os.Bundle
import android.telephony.SmsManager
import android.telephony.TelephonyManager

/** Supply missing device identity as AOSP Messaging does; leave APN and carrier headers alone. */
internal object MmsHttpConfig {
    fun overrides(context: Context, manager: SmsManager, carrier: Bundle? = runCatching { manager.carrierConfigValues }.getOrNull()): Bundle? {
        // An unavailable configuration isn't evidence that the carrier left these values empty.
        if (carrier == null || carrier.isEmpty) return null
        val phone = context.getSystemService(TelephonyManager::class.java).createForSubscriptionId(manager.subscriptionId)
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "unknown"
        val identity = MmsProtocol.httpIdentityOverrides(
            carrier.getString(SmsManager.MMS_CONFIG_USER_AGENT), carrier.getString(SmsManager.MMS_CONFIG_UA_PROF_URL),
            runCatching { phone.mmsUserAgent }.getOrNull(), runCatching { phone.mmsUAProfUrl }.getOrNull(), "Winnow/$version",
        )
        return Bundle().apply {
            identity.userAgent?.let { putString(SmsManager.MMS_CONFIG_USER_AGENT, it) }
            identity.profileUrl?.let { putString(SmsManager.MMS_CONFIG_UA_PROF_URL, it) }
        }.takeUnless { it.isEmpty }
    }
}
