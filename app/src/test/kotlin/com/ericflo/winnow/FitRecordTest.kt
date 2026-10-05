package com.ericflo.winnow

import com.ericflo.winnow.classify.Learner
import com.ericflo.winnow.data.db.CorrectionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FitRecordTest {
    private fun row(id: Long, source: String = CorrectionEntity.SOURCE_USER, key: String? = "sms:$id", thread: Long? = 1, runId: Long? = null) =
        CorrectionEntity(id = id, threadId = thread, buckets = "1,2", label = "spam", featurizerVersion = 4, createdAt = id, messageKey = key, source = source, runId = runId)

    @Test
    fun aFitCountsWhatItLearnedFromByWhoTaughtIt() {
        val rows = listOf(
            row(1),
            row(2),
            // A conversation's correction ("Not spam"), and a label restored from a backup.
            row(3, key = null),
            row(4, key = null, thread = null),
            // A service's: from a backlog run (one before runs were kept is 0), and learned as a text arrived.
            row(5, source = CorrectionEntity.SOURCE_PROVIDER, runId = 7),
            row(6, source = CorrectionEntity.SOURCE_PROVIDER, runId = 0),
            row(7, source = CorrectionEntity.SOURCE_PROVIDER, runId = null),
        )
        val fit = Learner.fitRecord("abc123", rows, buckets = 42, millis = 900, at = 1000)
        assertEquals(3, fit.userLabels)
        assertEquals(1, fit.corrections)
        assertEquals(2, fit.providerLabels)
        assertEquals(1, fit.providerLive)
        assertEquals(7, fit.labels)
        assertEquals(42, fit.buckets)
        assertEquals(Learner.PROVIDER_WEIGHT, fit.providerWeight, 0.0)
    }

    @Test
    fun aFitIsNamedBySixHexDigitsOfItsStamp() {
        assertEquals("000001", Learner.fitName(1))
        assertEquals("abcdef", Learner.fitName(0x1234abcdefL))
        assertEquals("ffffff", Learner.fitName(-1))
        assertNotEquals(Learner.fitName(2), Learner.fitName(3))
    }
}
