package com.ericflo.winnow.sms

import com.ericflo.winnow.mms.AcknowledgeInd
import com.ericflo.winnow.mms.Expiry
import com.ericflo.winnow.mms.MmsPdu
import com.ericflo.winnow.mms.NotifyRespInd
import com.ericflo.winnow.mms.ResponseStatus
import com.ericflo.winnow.mms.RetrieveConf

/** App-side MMS rules: SmsManager handles the APN/HTTP transport, not these PDU conventions. */
internal object MmsProtocol {
    /** Matches AOSP Messaging's enabledTransID handling, including its trailing '=' condition. */
    fun downloadLocation(location: String, transactionId: String, appendTransactionId: Boolean): String =
        if (appendTransactionId && location.endsWith('=') && transactionId.isNotEmpty() && !location.endsWith("=$transactionId")) location + transactionId else location

    fun responseLocation(location: String?, notifyWapMmsc: Boolean): String? =
        location?.takeIf { notifyWapMmsc && it.isNotBlank() }

    /** Telephony.Mms.EXPIRY is an epoch timestamp, even when the push carries a duration. */
    fun expiryAt(expiry: Expiry, receivedAtSeconds: Long): Long =
        if (expiry.absolute) expiry.seconds else receivedAtSeconds + expiry.seconds

    /** Older Winnow stored durations verbatim. Repair only unmistakable pre-2000 values. */
    fun repairStoredExpiry(expiry: Long, receivedAtSeconds: Long): Long =
        if (expiry in 1 until YEAR_2000 && receivedAtSeconds >= YEAR_2000) receivedAtSeconds + expiry else expiry

    /** A carrier error can be a valid RetrieveConf. Never save/acknowledge that as a message. */
    fun retrieved(conf: RetrieveConf): Boolean = conf.retrieveStatus == null || conf.retrieveStatus == ResponseStatus.OK

    fun response(conf: RetrieveConf, notificationTransactionId: String?, deferred: Boolean): MmsPdu? {
        if (!retrieved(conf)) return null
        // A deferred retrieval acknowledges the retrieval's ID, not the original notification's.
        val id = (if (deferred) conf.transactionId else notificationTransactionId)?.takeIf { it.isNotBlank() } ?: return null
        return if (deferred) AcknowledgeInd(id) else NotifyRespInd(id)
    }

    private const val YEAR_2000 = 946_684_800L
}
