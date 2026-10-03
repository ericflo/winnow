package com.ericflo.winnow.mms

/**
 * A decoded MMS PDU (OMA-MMS-ENC 1.2/1.3). Addresses are clean ("+15551234567", not
 * "+15551234567/TYPE=PLMN"; see [MmsAddress]), and times are seconds since the epoch.
 */
sealed interface MmsPdu {
    val transactionId: String?

    /** X-Mms-MMS-Version as "major.minor", e.g. "1.2". */
    val mmsVersion: String

    companion object {
        const val DEFAULT_VERSION = "1.2"
    }
}

/**
 * m-send-req: an outgoing message, POSTed to the MMSC. A null [from] asks the MMSC to insert
 * the sender's own number. Include a [Smil] part, first by convention, to send
 * multipart/related; without one the body goes out as multipart/mixed.
 */
data class SendReq(
    override val transactionId: String,
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val from: String? = null,
    val subject: String? = null,
    val dateSeconds: Long? = null,
    val messageClass: String? = MessageClass.PERSONAL,
    val deliveryReport: Boolean? = null,
    val readReport: Boolean? = null,
    val parts: List<MmsPart> = emptyList(),
    override val mmsVersion: String = MmsPdu.DEFAULT_VERSION,
) : MmsPdu

/** m-send-conf: the MMSC's answer to a [SendReq]. [responseStatus] is a [ResponseStatus] value. */
data class SendConf(
    override val transactionId: String,
    val responseStatus: Int = ResponseStatus.OK,
    val messageId: String? = null,
    val responseText: String? = null,
    override val mmsVersion: String = MmsPdu.DEFAULT_VERSION,
) : MmsPdu

/** m-notification-ind: a message is waiting at [contentLocation]. Arrives by WAP push. */
data class NotificationInd(
    override val transactionId: String,
    val contentLocation: String,
    val from: String? = null,
    val subject: String? = null,
    val messageClass: String = MessageClass.PERSONAL,
    val messageSize: Long = 0,
    val expiry: Expiry? = null,
    override val mmsVersion: String = MmsPdu.DEFAULT_VERSION,
) : MmsPdu

/** m-notifyresp-ind: answers a [NotificationInd]. [status] is an [MmsStatus] value. */
data class NotifyRespInd(
    override val transactionId: String,
    val status: Int = MmsStatus.RETRIEVED,
    val reportAllowed: Boolean? = null,
    override val mmsVersion: String = MmsPdu.DEFAULT_VERSION,
) : MmsPdu

/**
 * m-retrieve-conf: a downloaded message. [contentType] is the body's type; a non-multipart body
 * comes back as a single part. [dateSeconds] is mandatory in the spec but tolerated missing.
 */
data class RetrieveConf(
    override val transactionId: String? = null,
    val messageId: String? = null,
    val dateSeconds: Long? = null,
    val from: String? = null,
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val subject: String? = null,
    val contentType: String = ContentTypes.MULTIPART_RELATED,
    /** The multipart/related `type` parameter: the root part's content type. */
    val rootType: String? = null,
    /** The multipart/related `start` parameter: the root part's content-id, without angle brackets. */
    val rootContentId: String? = null,
    val parts: List<MmsPart> = emptyList(),
    /** X-Mms-Retrieve-Status, a [ResponseStatus] value; usually absent on success. */
    val retrieveStatus: Int? = null,
    override val mmsVersion: String = MmsPdu.DEFAULT_VERSION,
) : MmsPdu

/** m-acknowledge-ind: confirms a [RetrieveConf] fetched after a deferred [NotifyRespInd]. */
data class AcknowledgeInd(
    override val transactionId: String,
    val reportAllowed: Boolean? = null,
    override val mmsVersion: String = MmsPdu.DEFAULT_VERSION,
) : MmsPdu

/** m-delivery-ind: a delivery report for a sent message. [status] is an [MmsStatus] value. */
data class DeliveryInd(
    val messageId: String,
    val status: Int,
    val to: String? = null,
    val dateSeconds: Long? = null,
    override val mmsVersion: String = MmsPdu.DEFAULT_VERSION,
) : MmsPdu {
    override val transactionId: String? get() = null
}

/**
 * A well-formed PDU of a type this codec doesn't model, such as m-read-orig-ind (0x88). Only
 * its envelope is decoded; [PduComposer] refuses it.
 */
data class UnsupportedPdu(
    /** The raw X-Mms-Message-Type octet. */
    val messageType: Int,
    override val transactionId: String?,
    override val mmsVersion: String,
) : MmsPdu

/** X-Mms-Expiry: [seconds] from when the PDU was sent, or epoch seconds when [absolute]. */
data class Expiry(val seconds: Long, val absolute: Boolean = false)

/** The bytes are not a valid MMS PDU: truncated, malformed, or missing a header the type requires. */
class MmsPduException(message: String, cause: Throwable? = null) : Exception(message, cause)
