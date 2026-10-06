package com.ericflo.winnow

import com.ericflo.winnow.classify.Bootstrap
import com.ericflo.winnow.data.db.CorrectionEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class BootstrapExamplesTest {
    private fun label(thread: Long, key: String?, label: String, at: Long, source: String = CorrectionEntity.SOURCE_USER) = CorrectionEntity(
        threadId = thread, buckets = "1", label = label, featurizerVersion = 1, createdAt = at, messageKey = key, source = source,
    )

    @Test
    fun onlyTheUsersOwnSettledTextLabelsAreExamplesNewestFirst() {
        val rows = listOf(
            label(1, "sms:10", "personal", at = 1),
            label(2, "sms:20", "marketing", at = 5), // labeled before the six categories, not yet confirmed
            label(3, "sms:30", "political", at = 2), // political labels stand, so never wait
            label(4, "sms:40", "spam", at = 9, source = CorrectionEntity.SOURCE_PROVIDER), // the service's, not the user's
            label(5, "mms:50", "transactional", at = 8), // a photo message has no text to show
            label(6, "sms:60", "transactional", at = 7), // confirmed since: its conversation no longer waits
        )
        assertEquals(
            listOf(60L to "transactional", 30L to "political", 10L to "personal"),
            Bootstrap.exampleLabels(rows, recheckThreads = setOf(2L)),
        )
    }
}
