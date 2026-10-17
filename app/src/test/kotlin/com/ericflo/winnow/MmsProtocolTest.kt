package com.ericflo.winnow

import com.ericflo.winnow.mms.AcknowledgeInd
import com.ericflo.winnow.mms.Expiry
import com.ericflo.winnow.mms.NotificationInd
import com.ericflo.winnow.mms.NotifyRespInd
import com.ericflo.winnow.mms.PduComposer
import com.ericflo.winnow.mms.PduParser
import com.ericflo.winnow.mms.RetrieveConf
import com.ericflo.winnow.sms.MmsProtocol
import org.junit.Assert.*
import org.junit.Test

class MmsProtocolTest {
    @Test fun verizonStylePushNeedsItsTransactionIdBeforeDownloadingOrSaving() {
        // This is the shape AOSP's enabledTransID rule supports. Both messages announce the
        // same incomplete URL; persisting it verbatim also makes them look like duplicates.
        fun notification(id: String) = PduParser.parse(PduComposer.compose(NotificationInd(id, "http://mmsc.example/get?message="))) as NotificationInd
        val first = notification("first")
        val second = notification("second")
        assertEquals("http://mmsc.example/get?message=first", MmsProtocol.downloadLocation(first.contentLocation, first.transactionId, true))
        assertEquals("http://mmsc.example/get?message=second", MmsProtocol.downloadLocation(second.contentLocation, second.transactionId, true))
    }

    @Test fun retriesDoNotAppendTheTransactionIdAgain() {
        val url = "http://mmsc.example/get?message=transaction"
        assertEquals(url, MmsProtocol.downloadLocation(url, "transaction", true))
        val paddedId = "transaction=="
        val paddedUrl = "http://mmsc.example/get?message=$paddedId"
        assertEquals(paddedUrl, MmsProtocol.downloadLocation(paddedUrl, paddedId, true))
    }

    @Test fun carrierRuleDoesNotChangeCompleteUrlsOrOtherCarriers() {
        val incomplete = "http://mmsc.example/get?message="
        assertEquals(incomplete, MmsProtocol.downloadLocation(incomplete, "T", false))
        assertEquals(incomplete, MmsProtocol.downloadLocation(incomplete, "", true))
        for (url in listOf("http://mmsc.example/message/T", "http://mmsc.example/get?message=T&extra=1", "")) {
            assertEquals(url, MmsProtocol.downloadLocation(url, "T", true))
        }
    }

    @Test fun relativeExpiryIsStoredAsAnEpochTime() {
        val push = PduParser.parse(PduComposer.compose(NotificationInd("T", "http://m/1", expiry = Expiry(86_400)))) as NotificationInd
        assertEquals(receivedAt + 86_400, MmsProtocol.expiryAt(push.expiry!!, receivedAt))
    }

    @Test fun absoluteExpiryIsNotExtended() {
        assertEquals(receivedAt + 3600, MmsProtocol.expiryAt(Expiry(receivedAt + 3600, absolute = true), receivedAt))
        assertEquals(receivedAt, MmsProtocol.expiryAt(Expiry(0), receivedAt))
    }

    @Test fun legacyExpiryRepairUsesOriginalReceiptTimeAndIsIdempotent() {
        val repaired = MmsProtocol.repairStoredExpiry(604_800, receivedAt)
        assertEquals(receivedAt + 604_800, repaired)
        assertEquals(repaired, MmsProtocol.repairStoredExpiry(repaired, receivedAt))
        // Don't extend an actually expired absolute deadline or invent a missing expiry.
        assertEquals(receivedAt - 60, MmsProtocol.repairStoredExpiry(receivedAt - 60, receivedAt))
        assertEquals(0, MmsProtocol.repairStoredExpiry(0, receivedAt))
    }

    @Test fun responsesGoToThePushUrlOnlyWhenTheCarrierRequiresIt() {
        val url = "http://mmsc.example/get?message=T"
        assertEquals(url, MmsProtocol.responseLocation(url, true))
        assertNull(MmsProtocol.responseLocation(url, false))
        assertNull(MmsProtocol.responseLocation(null, true))
        assertNull(MmsProtocol.responseLocation("", true))
    }

    @Test fun automaticRetrievalConfirmsTheOriginalNotification() {
        val conf = RetrieveConf(transactionId = "retrieval")
        assertEquals(NotifyRespInd("notification"), MmsProtocol.response(conf, "notification", false))
    }

    @Test fun deferredRetrievalAcknowledgesTheRetrievalTransaction() {
        val conf = RetrieveConf(transactionId = "retrieval")
        assertEquals(AcknowledgeInd("retrieval"), MmsProtocol.response(conf, "notification", true))
        assertNull(MmsProtocol.response(RetrieveConf(), "notification", true))
    }

    @Test fun carrierErrorPdusAreNeverAcknowledgedAsRetrieved() {
        for (status in listOf(0xc0, 0xc1, 0xc2, 0xe0, 0xe1, 0xe2, 0xe3)) {
            val conf = PduParser.parse(PduComposer.compose(RetrieveConf(transactionId = "T", retrieveStatus = status))) as RetrieveConf
            assertFalse(MmsProtocol.retrieved(conf))
            assertNull(MmsProtocol.response(conf, "T", false))
            assertNull(MmsProtocol.response(conf, "T", true))
        }
        assertTrue(MmsProtocol.retrieved(RetrieveConf()))
        assertTrue(MmsProtocol.retrieved(RetrieveConf(retrieveStatus = 0x80)))
    }

    private val receivedAt = 1_792_267_200L
}
