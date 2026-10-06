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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ContactLookup(private val context: Context, private val scope: CoroutineScope? = null) {
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

    /**
     * Names the user gave people Winnow can't name itself: someone in an RCS group chat, known
     * only by an id Google Messages left in the store ("<id>@rcs.google.com"). This phone's only.
     */
    private val namesPrefs by lazy { context.getSharedPreferences("people_names", Context.MODE_PRIVATE) }
    private val names = MutableStateFlow(0)

    fun givenName(address: String): String? = namesPrefs.getString(address.trim().lowercase(), null)

    /** Every name the user gave, by address, for backups. */
    fun givenNames(): Map<String, String> = namesPrefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    /** Names from a backup, for addresses not named here already; how many were added. */
    fun restoreGivenNames(given: Map<String, String>): Int {
        val fresh = given.filterKeys { namesPrefs.getString(it.trim().lowercase(), null) == null }.filterValues { it.isNotBlank() }
        if (fresh.isEmpty()) return 0
        namesPrefs.edit().apply { fresh.forEach { (k, v) -> putString(k.trim().lowercase(), v.trim()) } }.apply()
        names.update { it + 1 }
        return fresh.size
    }

    /** Names [address] (null takes the name away); every list showing them reads names again. */
    fun setGivenName(address: String, name: String?) {
        namesPrefs.edit().apply { if (name.isNullOrBlank()) remove(address.trim().lowercase()) else putString(address.trim().lowercase(), name.trim()) }.apply()
        names.update { it + 1 }
    }

    // A colleague in a work profile is named once they're known to be one (see [isContact]): looking
    // every stranger up there to name them would cost a call to Android each.
    fun displayName(address: String): String? = givenName(address) ?: info(address)?.name ?: knownColleague(address)?.name

    /**
     * [displayName], if it's known without reading anything (looked up already, or in the list
     * as loaded): for the main thread, where reading the whole contact list would stall a frame.
     * Null when it isn't known yet, whether or not there's a name.
     */
    fun nameIfKnown(address: String): String? = givenName(address) ?: known(address)?.name

    /** [photoUri], under the same terms as [nameIfKnown]. */
    fun photoIfKnown(address: String): String? = known(address)?.photoUri

    private fun known(address: String): Info? {
        if (!canRead()) return null
        cache[address]?.let { return it.takeIf { found -> found !== NOT_FOUND } }
        numberKey(address)?.let { key -> numbers?.get(key)?.let { return it } }
        return knownColleague(address)
    }

    /** The contact's thumbnail photo, if they have one. */
    fun photoUri(address: String): String? = info(address)?.photoUri ?: knownColleague(address)?.photoUri

    /**
     * Whether [address] vouches for itself as a contact: what lets a text skip the classifier,
     * its photos show and save, and its links preview. Never an email address, which anyone can
     * put on a message (carriers' email gateways rarely check it); those get the contact's name
     * and photo, not the trust.
     */
    fun isContact(address: String): Boolean =
        !isEmailAddress(address) && (info(address) != null || (canRead() && numberKey(address) != null && workContact(address) != null))

    /**
     * Whether [address] is in the phone's own contact list: [isContact] without asking about a
     * work profile, so cheap enough to go through every conversation with, to count or choose.
     * Never what decides whether a text may leave the phone; [isContact] is.
     */
    fun inContactList(address: String): Boolean = !isEmailAddress(address) && info(address) != null

    private fun knownColleague(address: String): Info? = work[address]?.first?.takeIf { it !== NOT_FOUND }

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
        // Read the list again now, off the main thread, rather than on the first name a screen
        // asks for (a conversation opened from a notification asks on the main thread: see nameIfKnown).
        if (can) scope?.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { index(generation.get()) } }
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

    /** The contact list changing, or a name given (see [setGivenName]). */
    fun changes(): Flow<Unit> = kotlinx.coroutines.flow.merge(shared, names.drop(1).map { })

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
        // An RCS id: no contact has one, so there's nothing to look up (and a lookup each costs).
        if (isRcsAddress(address)) return null
        // An email address (an MMS from or to one) is looked up among contacts' emails.
        if (isEmailAddress(address)) {
            val byEmail = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Email.CONTENT_LOOKUP_URI, Uri.encode(address.trim()))
            return context.contentResolver.query(
                byEmail,
                arrayOf(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME, ContactsContract.CommonDataKinds.Email.PHOTO_THUMBNAIL_URI, ContactsContract.CommonDataKinds.Email.PHOTO_URI),
                null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0)?.let { Info(it, remember(c.getString(1), c.getString(2))) } else null }
        }
        val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
        return context.contentResolver.query(uri, arrayOf(PhoneLookup.DISPLAY_NAME, PhoneLookup.PHOTO_THUMBNAIL_URI, PhoneLookup.PHOTO_URI), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { Info(it, remember(c.getString(1), c.getString(2))) } else null
        }
    }

    /**
     * A colleague in a work profile, which [index] can't hold: Android lets an app look work
     * contacts up only one number at a time (and only if the work profile allows it). Answers are
     * kept a while, not cleared on every resume like the rest, as each costs a call to Android;
     * and while work apps are paused, when Android hides work contacts, someone found to be a
     * colleague before still is one, so their texts aren't taken for a stranger's.
     */
    private fun workContact(address: String): Info? {
        val users = context.getSystemService(android.os.UserManager::class.java)
        val now = android.os.SystemClock.elapsedRealtime()
        // Whether there's a work profile at all, asked of Android once a minute, not once a number.
        if (now - workProfileAt >= PROFILE_CHECK_MILLIS) {
            workProfile = users.userProfiles.firstOrNull { it != android.os.Process.myUserHandle() }
            workProfileAt = now
        }
        val profile = workProfile ?: return null
        val paused = runCatching { users.isQuietModeEnabled(profile) }.getOrDefault(false)
        val known = work[address]
        if (known != null && workAnswerStands(known.second, known.first !== NOT_FOUND, now, paused)) return known.first.takeIf { it !== NOT_FOUND }
        val found = runCatching {
            val uri = Uri.withAppendedPath(PhoneLookup.ENTERPRISE_CONTENT_FILTER_URI, Uri.encode(address))
            context.contentResolver.query(uri, arrayOf(PhoneLookup.DISPLAY_NAME, PhoneLookup.PHOTO_THUMBNAIL_URI, PhoneLookup.PHOTO_URI), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { Info(it, remember(c.getString(1), c.getString(2))) } else null
            }
        }.getOrNull()
        work[address] = (found ?: NOT_FOUND) to now
        return found
    }

    @Volatile private var workProfile: android.os.UserHandle? = null
    @Volatile private var workProfileAt = Long.MIN_VALUE / 2

    /** Work-profile answers, by address, with when they were looked up (see [workContact]). */
    private val work = ConcurrentHashMap<String, Pair<Info, Long>>()

    private fun index(started: Int): Map<String, Info> = numbers ?: loadIndex().also { loaded ->
        if (generation.get() == started) {
            numbers = loaded
            if (generation.get() != started) numbers = null
        }
    }

    /**
     * [thumbnail], noting its full-size [photo] for [displayPhoto] when the contact has a real one
     * (and forgetting one it no longer has). Kept across [clear], which runs on every resume: what
     * was found stays right until a lookup says otherwise, and avatars read it without a lookup.
     */
    private fun remember(thumbnail: String?, photo: String?): String? {
        if (thumbnail != null) {
            if (photo != null && photo != thumbnail) largePhotos[thumbnail] = photo else largePhotos.remove(thumbnail)
        }
        return thumbnail
    }

    private fun loadIndex(): Map<String, Info> {
        val index = HashMap<String, Info>()
        runCatching {
            context.contentResolver.query(
                Phone.CONTENT_URI,
                arrayOf(Phone.NUMBER, Phone.NORMALIZED_NUMBER, Phone.DISPLAY_NAME, Phone.PHOTO_THUMBNAIL_URI, Phone.PHOTO_URI),
                null, null,
                // Primary numbers first, so a shared number goes to the contact it's primary for.
                "${Phone.IS_SUPER_PRIMARY} DESC, ${Phone.IS_PRIMARY} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(2)?.takeIf { it.isNotBlank() } ?: continue
                    val info = Info(name, remember(c.getString(3), c.getString(4)))
                    listOfNotNull(c.getString(0), c.getString(1)).mapNotNull(::numberKey).forEach { index.putIfAbsent(it, info) }
                }
            }
        }
        return index
    }

    companion object {
        private val NOT_FOUND = Info("", null)

        private const val PROFILE_CHECK_MILLIS = 60_000L

        /** How long a work-profile lookup's answer is used before asking again. */
        const val WORK_ANSWER_MILLIS = 10 * 60_000L

        /**
         * Whether a work-profile answer looked up at [at] still stands at [now]: for a while, and
         * a colleague found (a [contact]) for as long as work apps are [paused]. Pure, so it's
         * unit-tested.
         */
        fun workAnswerStands(at: Long, contact: Boolean, now: Long, paused: Boolean): Boolean =
            now - at < WORK_ANSWER_MILLIS || (contact && paused)
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

        /**
         * The full-size photo for a contact's [thumbnail] (the 96-pixel PHOTO_THUMBNAIL_URI), when
         * the contact has one bigger than that; null when it doesn't, or it hasn't been looked up.
         */
        fun displayPhoto(thumbnail: String?): String? = thumbnail?.let(largePhotos::get)

        /**
         * Thumbnail → full-size photo, for the contacts that have one (Contacts gives PHOTO_URI
         * as the thumbnail itself for the rest), as lookups find them.
         */
        private val largePhotos = java.util.concurrent.ConcurrentHashMap<String, String>()

        /** A full phone number (7+ digits), not a short code, email or alphanumeric sender. */
        fun isPersonalNumber(address: String): Boolean = numberKey(address)?.startsWith("short:") == false

        /** Someone who can be written back to: a full phone number, or an email address (by MMS). */
        fun isReachable(address: String): Boolean = isPersonalNumber(address) || (isEmailAddress(address) && !isRcsAddress(address))

        @Volatile private var appContext: Context? = null
        /** The SIM's country, once known. The network's is only a fallback, asked again later (a roaming phone is still from home). */
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
            // An RCS id says nothing to a person: its last few characters, to tell people apart.
            if (isRcsAddress(address)) return rcsLabel(address)
            if (address.any(Char::isLetter)) return address
            val country = country()
            // Formatting a number costs about half a millisecond, and listing the conversations
            // formats every number in them: 1,800 conversations took a second for this alone.
            // Kept by country too, since that's what it depends on.
            formatted[address]?.takeIf { it.first == country }?.let { return it.second }
            // A home-country number reads the same however the carrier wrote it: "+14155550177"
            // and "4155550177" both as (415) 555-0177, as in Messages.
            val national = nationalForm(address, country) ?: address.takeIf { country in NANP && it.length == 10 && it.all(Char::isDigit) }
            val result = national?.let(::nanpFormat) ?: PhoneNumberUtils.formatNumber(national ?: address, country) ?: address
            if (formatted.size > MAX_FORMATTED) formatted.clear()
            formatted[address] = country to result
            return result
        }

        private val formatted = ConcurrentHashMap<String, Pair<String, String>>()
        private const val MAX_FORMATTED = 20_000

        /**
         * A ten-digit North American number as Android formats one, "(415) 555-0177", without
         * asking Android (which takes about half a millisecond a number); null if it isn't one.
         * Pure, so it's unit-tested against what Android gives.
         */
        fun nanpFormat(national: String): String? {
            if (national.length != 10 || !national.all(Char::isDigit) || national[0] < '2' || national[3] < '2') return null
            return "(${national.substring(0, 3)}) ${national.substring(3, 6)}-${national.substring(6)}"
        }

        /**
         * Someone known only by an RCS id: its last four characters, to tell people apart, in one
         * word so a group's name (first names only) keeps all of it: "RCS·a1b2".
         */
        fun rcsLabel(address: String): String = "RCS·" + address.trim().substringBefore('@').takeLast(4)

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
