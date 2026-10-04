package com.ericflo.winnow.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.PhoneLookup
import android.telephony.PhoneNumberUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ContactLookup(private val context: Context, scope: CoroutineScope? = null) {
    private data class Info(val name: String, val photoUri: String?)

    init {
        appContext = context.applicationContext
    }

    // NOT_FOUND caches "not a contact" so unknown senders aren't looked up on every frame.
    private val cache = ConcurrentHashMap<String, Info>()

    // Every contact's numbers, from one query. A PhoneLookup per address cost about a second
    // for an inbox of a few hundred conversations; this is a few milliseconds per thousand.
    @Volatile private var numbers: Map<String, Info>? = null

    /** Bumped by [clear]: a lookup that started before it doesn't store what it found. */
    private val generation = AtomicInteger()

    fun displayName(address: String): String? = info(address)?.name

    /** The contact's thumbnail photo, if they have one. */
    fun photoUri(address: String): String? = info(address)?.photoUri

    fun isContact(address: String): Boolean = info(address) != null

    /** Whether Winnow may read contacts at all; without it, everyone looks like a stranger. */
    fun canRead(): Boolean = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun clear() {
        generation.incrementAndGet()
        cache.clear()
        numbers = null
    }

    private val permissionChecks = MutableStateFlow(0)
    @Volatile private var couldRead = canRead()
    /** The last [permissionChecks] already announced as a change; restarting the watcher isn't one. */
    private val announced = AtomicInteger()

    /** After a possible permission change (every resume): contacts may only now be readable, and so watchable. */
    fun permissionsChanged() {
        clear()
        val can = canRead()
        if (can && !couldRead) permissionChecks.update { it + 1 }
        couldRead = can
    }

    /**
     * Emits whenever the contact list changes (a contact added, renamed, given a photo), after
     * forgetting what was looked up, so names and photos are read afresh. A burst (a sync) is one
     * change. One watcher however many listen; none without READ_CONTACTS, as Android won't let
     * an app watch what it can't read, until [permissionsChanged] says to look again.
     */
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private val shared: Flow<Unit> = permissionChecks.flatMapLatest { check ->
        callbackFlow {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    clear()
                    trySend(Unit)
                }
            }
            val watching = canRead() &&
                runCatching { context.contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer) }.isSuccess
            // Just allowed: whatever was shown without names is worth reading again.
            if (watching && announced.getAndSet(check) < check) trySend(Unit)
            awaitClose { if (watching) context.contentResolver.unregisterContentObserver(observer) }
        }
    }.debounce(SETTLE_MILLIS).let { flow -> if (scope != null) flow.shareIn(scope, SharingStarted.WhileSubscribed(5_000)) else flow }

    fun changes(): Flow<Unit> = shared

    private fun info(address: String): Info? {
        if (!canRead()) return null
        cache[address]?.let { return it.takeIf { found -> found !== NOT_FOUND } }
        val started = generation.get()
        val found = query(address, started) ?: NOT_FOUND
        // Not if the contacts changed meanwhile: it may be the old name. Checked again after
        // storing, as a change can land in between.
        if (generation.get() == started) {
            cache[address] = found
            if (generation.get() != started) cache.remove(address, found)
        }
        return found.takeIf { it !== NOT_FOUND }
    }

    private fun query(address: String, started: Int): Info? {
        // A phone number the contact list doesn't have isn't a contact; only short codes,
        // emails and the like still go to PhoneLookup.
        numberKey(address)?.let { key -> return index(started)[key] }
        val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
        return context.contentResolver.query(uri, arrayOf(PhoneLookup.DISPLAY_NAME, PhoneLookup.PHOTO_THUMBNAIL_URI), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { Info(it, c.getString(1)) } else null
        }
    }

    private fun index(started: Int): Map<String, Info> = numbers ?: loadIndex().also { loaded ->
        if (generation.get() == started) {
            numbers = loaded
            if (generation.get() != started) numbers = null
        }
    }

    private fun loadIndex(): Map<String, Info> {
        val index = HashMap<String, Info>()
        runCatching {
            context.contentResolver.query(
                Phone.CONTENT_URI,
                arrayOf(Phone.NUMBER, Phone.NORMALIZED_NUMBER, Phone.DISPLAY_NAME, Phone.PHOTO_THUMBNAIL_URI),
                null, null,
                // Primary numbers first, so a shared number goes to the contact it's primary for.
                "${Phone.IS_SUPER_PRIMARY} DESC, ${Phone.IS_PRIMARY} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(2)?.takeIf { it.isNotBlank() } ?: continue
                    val info = Info(name, c.getString(3))
                    listOfNotNull(c.getString(0), c.getString(1)).mapNotNull(::numberKey).forEach { index.putIfAbsent(it, info) }
                }
            }
        }
        return index
    }

    companion object {
        private val NOT_FOUND = Info("", null)
        private const val COUNTRY_RETRY_MILLIS = 60_000L
        private const val SETTLE_MILLIS = 500L

        /**
         * How numbers are matched: the last 10 digits, so "+1 415-555-0192", "(415) 555-0192"
         * and "14155550192" agree, as do "+44 7700 900123" and "07700 900123". Short codes
         * match exactly. Null for emails and alphanumeric senders, which aren't matched this way.
         */
        fun numberKey(address: String): String? {
            if (address.any(Char::isLetter) || '@' in address) return null
            val digits = address.filter(Char::isDigit)
            return when {
                digits.length >= 7 -> digits.takeLast(10)
                digits.isNotEmpty() -> "short:$digits"
                else -> null
            }
        }

        /** A full phone number (7+ digits), not a short code, email or alphanumeric sender. */
        fun isPersonalNumber(address: String): Boolean = numberKey(address)?.startsWith("short:") == false

        @Volatile private var appContext: Context? = null
        /** The SIM's country, once known; the network's isn't kept (a roaming phone is still from home). */
        @Volatile private var homeCountry: String? = null
        @Volatile private var network: String? = null
        @Volatile private var checkedAt = Long.MIN_VALUE

        /**
         * Where the phone is, for showing numbers: the SIM's country, else the network's, else the
         * language setting's (an English (UK) phone in Ohio is still in the US). Asked once it's
         * known; until then (a locked SIM, no SIM) at most once a minute.
         */
        private fun country(): String {
            homeCountry?.let { return it }
            val context = appContext ?: return network ?: Locale.getDefault().country.uppercase()
            // Elapsed time, not the clock: setting the clock back mustn't stop the retries.
            val now = android.os.SystemClock.elapsedRealtime()
            if (checkedAt == Long.MIN_VALUE || now - checkedAt > COUNTRY_RETRY_MILLIS) {
                checkedAt = now
                val telephony = runCatching { context.getSystemService(android.telephony.TelephonyManager::class.java) }.getOrNull()
                homeCountry = runCatching { telephony?.simCountryIso }.getOrNull()?.takeIf { it.isNotBlank() }?.uppercase()
                homeCountry?.let { return it }
                // Kept when a later look finds none (airplane mode), so formatting doesn't flip.
                network = runCatching { telephony?.networkCountryIso }.getOrNull()?.takeIf { it.isNotBlank() }?.uppercase() ?: network
            }
            return network ?: Locale.getDefault().country.uppercase()
        }

        fun formatAddress(address: String): String {
            if (address.any(Char::isLetter)) return address
            val country = country()
            // A home-country number reads the same however the carrier wrote it: "+14155550177"
            // and "4155550177" both as (415) 555-0177, as in Messages.
            return PhoneNumberUtils.formatNumber(nationalForm(address, country) ?: address, country) ?: address
        }

        /** [address] without its +1, for a phone in the US or Canada; null for anything else. */
        fun nationalForm(address: String, country: String): String? {
            if (country !in NANP) return null
            val digits = address.filter(Char::isDigit)
            return digits.drop(1).takeIf { digits.length == 11 && digits.startsWith('1') && address.trim().let { it.startsWith("+1") || it.startsWith("1") } }
        }

        /** The North American Numbering Plan: +1 everywhere here. */
        private val NANP = setOf(
            "US", "CA", "PR", "VI", "GU", "AS", "MP", "AG", "AI", "BB", "BM", "BS", "DM", "DO", "GD", "JM", "KN", "KY", "LC",
            "MS", "SX", "TC", "TT", "VC", "VG",
        )
    }
}
