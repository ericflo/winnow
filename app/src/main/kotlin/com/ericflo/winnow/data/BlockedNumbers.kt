package com.ericflo.winnow.data

import android.content.ContentValues
import android.content.Context
import android.provider.BlockedNumberContract
import android.provider.BlockedNumberContract.BlockedNumbers as Table
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android's own block list. Blocked numbers never reach Winnow at all: the system drops
 * their texts and calls. Only the default SMS or dialer app may change it.
 */
class BlockedNumbers(private val context: Context) {

    fun available(): Boolean = runCatching { BlockedNumberContract.canCurrentUserBlockNumbers(context) }.getOrDefault(false)

    suspend fun isBlocked(number: String): Boolean = io { BlockedNumberContract.isBlocked(context, number) } ?: false

    suspend fun block(number: String) {
        io { context.contentResolver.insert(Table.CONTENT_URI, ContentValues().apply { put(Table.COLUMN_ORIGINAL_NUMBER, number) }) }
    }

    suspend fun unblock(number: String) {
        io { BlockedNumberContract.unblock(context, number) }
    }

    suspend fun all(): List<String> = io {
        context.contentResolver.query(Table.CONTENT_URI, arrayOf(Table.COLUMN_ORIGINAL_NUMBER), null, null, null)?.use { c ->
            buildList { while (c.moveToNext()) c.getString(0)?.let(::add) }
        }
    }.orEmpty()

    /** The block list throws SecurityException unless Winnow holds the SMS role; treat that as "unavailable". */
    private suspend fun <T> io(block: () -> T): T? = withContext(Dispatchers.IO) {
        if (!available()) null else runCatching(block).getOrNull()
    }
}
