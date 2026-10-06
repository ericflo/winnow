package com.ericflo.winnow.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

/**
 * This phone's own numbers, to leave the user out of group MMS participant lists. Android tells
 * them with READ_PHONE_NUMBERS, when the carrier put the number on the SIM; failing that, they're
 * learned from picture messages sent to this phone alone (see [learn]). With neither, the set is
 * empty and callers fall back to heuristics.
 *
 * Listing every active subscription would also need READ_PHONE_STATE, so this asks only about
 * the default SMS, voice and data subscriptions, which needs nothing more.
 */
class OwnNumbers(private val context: Context) {
    private val prefs by lazy { context.getSharedPreferences("own_numbers", Context.MODE_PRIVATE) }

    /** Numbers learned from messages (see [learn]), normalized. */
    private fun learned(): Set<String> = runCatching { prefs.getStringSet(KEY_LEARNED, null) }.getOrNull().orEmpty()

    /**
     * [number] received a picture message sent to it alone: it's this phone's. Only a phone
     * number is taken (an email or a short code can't be), and kept on this phone only.
     */
    fun learn(number: String) {
        val digits = number.filter(Char::isDigit)
        if (number.any(Char::isLetter) || '@' in number || digits.length < 10) return
        val normalized = normalizeAddress(number)
        val known = learned()
        if (normalized in known) return
        runCatching { prefs.edit().putStringSet(KEY_LEARNED, known + normalized).apply() }
    }

    fun all(): Set<String> = fromAndroid() + learned()

    private fun fromAndroid(): Set<String> {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS) != PackageManager.PERMISSION_GRANTED) return emptySet()
        val subscriptions = context.getSystemService(SubscriptionManager::class.java)
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val ids = listOf(
            SubscriptionManager.getDefaultSmsSubscriptionId(),
            SubscriptionManager.getDefaultVoiceSubscriptionId(),
            SubscriptionManager.getDefaultSubscriptionId(),
        ).filter { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }.distinct()
        // getPhoneNumber is Android 13+; on 12, line1Number is all there is.
        val perSubscription = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ids.mapNotNull { id -> runCatching { subscriptions.getPhoneNumber(id) }.getOrNull() }
        } else {
            emptyList()
        }
        // Our own number, used only to drop ourselves from participant lists; never stored or sent.
        @SuppressLint("HardwareIds")
        val line1 = runCatching { @Suppress("DEPRECATION") telephony.line1Number }.getOrNull()
        val numbers = perSubscription + listOfNotNull(line1)
        return numbers.filter { it.isNotBlank() }.map(::normalizeAddress).toSet()
    }

    private companion object {
        const val KEY_LEARNED = "learned"
    }
}
