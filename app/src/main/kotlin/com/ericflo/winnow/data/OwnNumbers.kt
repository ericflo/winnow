package com.ericflo.winnow.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

/**
 * This phone's own numbers, to leave the user out of group MMS participant lists. Needs
 * READ_PHONE_NUMBERS; without it the set is empty and callers fall back to heuristics.
 *
 * Listing every active subscription would also need READ_PHONE_STATE, so this asks only about
 * the default SMS, voice and data subscriptions, which needs nothing more.
 */
class OwnNumbers(private val context: Context) {
    fun all(): Set<String> {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS) != PackageManager.PERMISSION_GRANTED) return emptySet()
        val subscriptions = context.getSystemService(SubscriptionManager::class.java)
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val ids = listOf(
            SubscriptionManager.getDefaultSmsSubscriptionId(),
            SubscriptionManager.getDefaultVoiceSubscriptionId(),
            SubscriptionManager.getDefaultSubscriptionId(),
        ).filter { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }.distinct()
        val numbers = ids.mapNotNull { id -> runCatching { subscriptions.getPhoneNumber(id) }.getOrNull() } +
            listOfNotNull(runCatching { @Suppress("DEPRECATION") telephony.line1Number }.getOrNull())
        return numbers.filter { it.isNotBlank() }.map(::normalizeAddress).toSet()
    }
}
