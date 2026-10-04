package com.ericflo.winnow.data

import android.content.Context
import android.provider.ContactsContract
import java.time.LocalDate
import java.time.MonthDay

/**
 * Contacts' birthdays, for birthday reminders: on the day, a conversation with someone whose
 * contact card has a birthday says so (see Nudge.Kind.BIRTHDAY).
 */
class Birthdays(private val context: Context) {
    /** By normalized phone number (see [normalizeAddress]); read once per contacts change. */
    @Volatile private var byNumber: Map<String, MonthDay>? = null

    /** Contacts changed: read them again when next asked. */
    fun clear() {
        byNumber = null
    }

    /** Whether [address]'s contact has their birthday on [day]. */
    fun isBirthday(address: String, day: LocalDate): Boolean =
        all()[normalizeAddress(address)]?.let { it.month == day.month && (it.dayOfMonth == day.dayOfMonth || leapDayOnFeb28(it, day)) } == true

    private fun all(): Map<String, MonthDay> = byNumber ?: runCatching { read() }.getOrDefault(emptyMap()).also { byNumber = it }

    private fun read(): Map<String, MonthDay> {
        val resolver = context.contentResolver
        val birthdays = HashMap<Long, MonthDay>()
        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.CommonDataKinds.Event.START_DATE),
            "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.Event.TYPE} = ?",
            arrayOf(ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY.toString()),
            null,
        )?.use { c -> while (c.moveToNext()) parse(c.getString(1).orEmpty())?.let { birthdays[c.getLong(0)] = it } }
        if (birthdays.isEmpty()) return emptyMap()
        val byNumber = HashMap<String, MonthDay>()
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val day = birthdays[c.getLong(0)] ?: continue
                val number = c.getString(1)?.let(::normalizeAddress)?.takeIf { it.isNotEmpty() } ?: continue
                byNumber[number] = day
            }
        }
        return byNumber
    }

    companion object {
        /**
         * A contact card's birthday: "1990-07-14", or "--07-14" without a year, as Contacts
         * writes them; some apps add a time ("1990-07-14T00:00:00Z") or use "07/14". Null for
         * anything else.
         */
        fun parse(text: String): MonthDay? {
            val t = text.trim()
            val match = Regex("""^(?:\d{4}|-)-(\d{1,2})-(\d{1,2})""").find(t) ?: Regex("""^(\d{1,2})/(\d{1,2})(?:/\d{2,4})?$""").find(t) ?: return null
            val (month, day) = match.destructured
            return runCatching { MonthDay.of(month.toInt(), day.toInt()) }.getOrNull()
        }

        /** A Feb 29 birthday is kept on Feb 28 in other years. */
        private fun leapDayOnFeb28(birthday: MonthDay, day: LocalDate): Boolean =
            birthday == MonthDay.of(2, 29) && !day.isLeapYear && day.monthValue == 2 && day.dayOfMonth == 28
    }
}
