package com.ericflo.winnow.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

/** A SIM the phone can text from. [color] is the system's tint for it, as ARGB. */
data class SimCard(val subscriptionId: Int, val slot: Int, val label: String, val number: String?, val color: Int) {
    /** "SIM 1", as the phone's own settings call it. */
    val slotName: String get() = "SIM ${slot + 1}"
}

/**
 * Which SIM a message to a conversation goes out on. With fewer than two SIMs there's
 * nothing to choose, and null means "let Android use its default". Otherwise: the SIM picked
 * for this conversation, else the one its last incoming text arrived on, else the system
 * default for texts, else the first SIM.
 */
object SimChoice {
    fun pick(available: List<Int>, preferred: Int?, lastIncoming: Int?, systemDefault: Int): Int? {
        if (available.size < 2) return null
        return listOfNotNull(preferred, lastIncoming, systemDefault).firstOrNull { it in available } ?: available.first()
    }
}

/**
 * The phone's active SIMs. Listing them needs READ_PHONE_STATE, which Winnow only asks for on
 * phones with two or more SIM slots; without it there's one choice, the default.
 */
class SimCards(private val context: Context, private val debugFlags: () -> Boolean = { false }) {
    private val subscriptions = context.getSystemService(SubscriptionManager::class.java)
    private val telephony = context.getSystemService(TelephonyManager::class.java)

    // Lint can't follow canList() into the call; it checks READ_PHONE_STATE first.
    @SuppressLint("MissingPermission")
    fun available(): List<SimCard> {
        val real = if (canList()) {
            runCatching { subscriptions.activeSubscriptionInfoList.orEmpty().map { it.toCard() } }.getOrDefault(emptyList())
        } else {
            emptyList()
        }.sortedBy { it.slot }
        // Debug builds can pretend there's a second SIM, since emulators only ever have one.
        return if (simulatingSecond() && real.size <= 1) {
            val first = real.firstOrNull() ?: SimCard(systemDefault(), 0, telephony.simOperatorName.ifBlank { "SIM 1" }, null, 0xFF1565C0.toInt())
            listOf(first, SimCard(SIMULATED_ID, 1, "Work (simulated)", null, 0xFFEF6C00.toInt()))
        } else {
            real
        }
    }

    fun systemDefault(): Int = SubscriptionManager.getDefaultSmsSubscriptionId()

    /** The phone has room for two SIMs but Winnow can't see which are in. */
    fun needsPermission(): Boolean = !canList() && telephony.activeModemCount > 1

    /**
     * The subscription to hand to SmsManager, or null for its default. The simulated SIM sends
     * through the default one, so debug builds still go out the emulator's virtual modem.
     */
    fun forSending(subscriptionId: Int?): Int? = subscriptionId
        ?.takeIf { it != SIMULATED_ID && it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
        // A SIM that's gone (swapped, or an old eSIM) has no slot: use the default instead.
        ?.takeIf { SubscriptionManager.getSlotIndex(it) != SubscriptionManager.INVALID_SIM_SLOT_INDEX }

    private fun canList() = context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    private fun simulatingSecond() = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 && debugFlags()

    private fun SubscriptionInfo.toCard() = SimCard(
        subscriptionId = subscriptionId,
        slot = simSlotIndex,
        label = displayName?.toString()?.takeIf { it.isNotBlank() } ?: carrierName?.toString() ?: "SIM ${simSlotIndex + 1}",
        number = null,
        color = iconTint,
    )

    companion object {
        /** Debug builds' pretend second SIM. Real subscription ids are small positive numbers. */
        const val SIMULATED_ID = 9_999
    }
}
