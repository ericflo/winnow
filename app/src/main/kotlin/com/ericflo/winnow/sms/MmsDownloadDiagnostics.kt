package com.ericflo.winnow.sms

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import java.net.URI
import java.security.MessageDigest
import java.time.Instant

/** Snapshot taken before handing a request to Android, carried in that attempt's callback. */
internal class MmsDownloadDiagnostics(private val context: Context) {
    private val appInfo by lazy { context.packageManager.getPackageInfo(context.packageName, 0) }
    val version: String get() = "${appInfo.versionName} (${appInfo.longVersionCode})"

    fun describe(uri: Uri, location: String, transactionId: String, subId: Int, config: Bundle?, overrides: Bundle?, source: String, announcedLocation: String?, inputLocation: String): String {
        val now = System.currentTimeMillis() / 1000
        val dates = context.contentResolver.query(uri, arrayOf(Telephony.Mms.DATE, Telephony.Mms.EXPIRY), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getLong(0) to c.getLong(1) else null }
        fun flag(key: String) = if (config?.containsKey(key) == true) config.getBoolean(key).toString() else "unavailable"
        val tm = context.getSystemService(TelephonyManager::class.java).createForSubscriptionId(subId)
        return buildList {
            add("Message row: ${uri.lastPathSegment}; download source: $source")
            add("Request started: ${Instant.ofEpochSecond(now)}; app $version")
            add(MmsDownloadReport.timeline(dates?.first, dates?.second, appInfo.lastUpdateTime / 1000, now))
            add("Notification URL: ${announcedLocation?.let(MmsDownloadReport::url) ?: "not captured (existing stored notification)"}")
            add("Input URL before carrier rule: ${MmsDownloadReport.url(inputLocation)}")
            add("Attempted URL: ${MmsDownloadReport.url(location)}")
            add("Transaction ID: ${MmsDownloadReport.fingerprint(transactionId)}; chars=${transactionId.length}; " +
                "URL ends with ID=${transactionId.isNotEmpty() && location.endsWith(transactionId)}; " +
                "changed this attempt=${inputLocation != location}; changed from notification=${announcedLocation?.let { it != location } ?: "unknown"}")
            add("Carrier config for SIM $subId: enabledTransID=${flag(SmsManager.MMS_CONFIG_APPEND_TRANSACTION_ID)}, " +
                "enabledNotifyWapMMSC=${flag(SmsManager.MMS_CONFIG_NOTIFY_WAP_MMSC_ENABLED)}, " +
                "enabledMMS=${flag(SmsManager.MMS_CONFIG_MMS_ENABLED)}")
            add("HTTP configuration: userAgent=${config?.getString(SmsManager.MMS_CONFIG_USER_AGENT)?.takeIf { it.isNotBlank() } ?: "absent"}; " +
                "uaProfUrl=${config?.getString(SmsManager.MMS_CONFIG_UA_PROF_URL)?.takeIf { it.isNotBlank() }?.let(MmsDownloadReport::url) ?: "absent"}; " +
                "identity headers=${MmsDownloadReport.identityHeaders(config?.getString(SmsManager.MMS_CONFIG_HTTP_PARAMS))}")
            add("HTTP identity fallbacks: userAgent=${overrides?.getString(SmsManager.MMS_CONFIG_USER_AGENT) ?: "none"}; " +
                "uaProfUrl=${overrides?.getString(SmsManager.MMS_CONFIG_UA_PROF_URL)?.let(MmsDownloadReport::url) ?: "none"}")
            add("SIM operator: ${runCatching { tm.simOperator }.getOrDefault("unavailable")}; " +
                "carrier ID: ${runCatching { tm.simCarrierId }.getOrNull() ?: "unavailable"}; " +
                "default SMS SIM: ${SubscriptionManager.getDefaultSmsSubscriptionId()}; default data SIM: ${SubscriptionManager.getDefaultDataSubscriptionId()}")
            add("APN, MMS network and HTTP identity-header expansion: managed by Android; not exposed in the download callback. App overrides only missing User-Agent/profile.")
        }.joinToString("\n")
    }
}

/** Keep retrieval tokens and phone numbers out of shared reports; fingerprint exact, unmodified strings. */
internal object MmsDownloadReport {
    fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(24)

    fun url(value: String): String {
        val parsed = runCatching { URI(value) }.getOrNull()
        val origin = parsed?.host?.let { "${parsed.scheme}://$it${if (parsed.port >= 0) ":${parsed.port}" else ""}" } ?: "unparseable URL"
        return "$origin [path chars=${parsed?.rawPath?.length ?: 0}, query chars=${parsed?.rawQuery?.length ?: 0}, " +
            "fragment=${parsed?.rawFragment != null}, total chars=${value.length}, ends '='=${value.endsWith('=')}, " +
            "whitespace=${value.any { it.isWhitespace() }}, non-ASCII=${value.any { it.code > 127 }}, " +
            "percent escapes=${value.count { it == '%' }}, sha256=${fingerprint(value)}]"
    }

    fun identityHeaders(params: String?): String {
        if (params == null) return "unavailable"
        // Only these known names, never macro expansions (which can contain the user's number).
        return listOf("x-up-calling-line-id", "x-vzw-mdn").joinToString(", ") { name ->
            "$name=${params.split('|').any { it.substringBefore(':').trim().equals(name, ignoreCase = true) }}"
        }
    }

    fun timeline(received: Long?, expiry: Long?, updated: Long, now: Long): String {
        val arrived = received?.takeIf { it > 0 }
        val deadline = expiry?.takeIf { it > 0 }
        return "Notification received: ${arrived?.let(Instant::ofEpochSecond) ?: "unknown"}; " +
            "age seconds=${arrived?.let { now - it } ?: "unknown"}; " +
            "${if (arrived == null) "arrival relative to update unknown" else if (arrived < updated) "predates this app installation/update" else "received since this app installation/update"}\n" +
            "Stored expiry: ${deadline?.let(Instant::ofEpochSecond) ?: "unknown"}; " +
            "${if (deadline == null) "expiry unknown" else if (deadline <= now) "elapsed before request" else "not elapsed at request"}"
    }
}
