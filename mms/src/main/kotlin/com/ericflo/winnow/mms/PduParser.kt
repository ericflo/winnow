package com.ericflo.winnow.mms

/** Decodes binary MMS PDUs (OMA-MMS-ENC 1.2/1.3, WAP-230-WSP encodings). */
object PduParser {
    /**
     * Parses one PDU. Headers may come in any order. Unknown headers and part headers, and known
     * ones with a malformed value, are skipped by the WSP length rules. Message types this codec
     * doesn't model come back as [UnsupportedPdu].
     *
     * @throws MmsPduException when [bytes] are truncated or malformed, or lack a header their type requires.
     */
    @Throws(MmsPduException::class)
    fun parse(bytes: ByteArray): MmsPdu =
        try {
            readHeaders(WspReader(bytes)).toPdu()
        } catch (e: MmsPduException) {
            throw e
        } catch (e: RuntimeException) {
            // The reader reports malformations itself; this only keeps a bug from crashing on hostile input.
            throw MmsPduException("malformed PDU", e)
        }

    private class Fields {
        var messageType: Int? = null
        var transactionId: String? = null
        var mmsVersion: String? = null
        var messageId: String? = null
        var contentLocation: String? = null
        var date: Long? = null
        var from: String? = null
        val to = mutableListOf<String>()
        val cc = mutableListOf<String>()
        val bcc = mutableListOf<String>()
        var subject: String? = null
        var messageClass: String? = null
        var messageSize: Long? = null
        var expiry: Expiry? = null
        var deliveryReport: Boolean? = null
        var readReport: Boolean? = null
        var reportAllowed: Boolean? = null
        var responseStatus: Int? = null
        var responseText: String? = null
        var status: Int? = null
        var retrieveStatus: Int? = null
        var contentType: ContentType? = null
        var parts: List<MmsPart> = emptyList()
    }

    private fun readHeaders(reader: WspReader): Fields {
        val f = Fields()
        while (reader.hasMore()) {
            val field = reader.peek()
            when {
                field == Header.CONTENT_TYPE -> {
                    reader.octet()
                    val ct = reader.contentType()
                    f.contentType = ct
                    f.parts = readBody(reader, ct)
                    return f
                }
                field >= 0x80 -> {
                    reader.octet()
                    val value = reader.value()
                    // Every MMS header value is delimited by the generic WSP rules, so a malformed one
                    // costs only its own header. A required header lost this way fails later as missing.
                    try {
                        f.read(field, value)
                    } catch (e: MmsPduException) {
                    }
                }
                field >= 0x20 -> {
                    reader.text() // Application-header: Token-text name, Text-string value
                    reader.skipValue()
                }
                else -> throw MmsPduException("invalid header field octet 0x%02x".format(field))
            }
        }
        return f
    }

    private fun Fields.read(field: Int, r: WspReader) {
        when (field) {
            Header.MESSAGE_TYPE -> messageType = r.token()
            Header.TRANSACTION_ID -> transactionId = r.text()
            Header.MMS_VERSION -> mmsVersion = r.version()
            Header.MESSAGE_ID -> messageId = r.text()
            Header.CONTENT_LOCATION -> contentLocation = r.text()
            Header.DATE -> date = r.integerValue()
            Header.FROM -> from = r.from()
            Header.TO -> to += r.address()
            Header.CC -> cc += r.address()
            Header.BCC -> bcc += r.address()
            Header.SUBJECT -> subject = r.encodedString()
            Header.MESSAGE_CLASS -> messageClass = r.messageClass()
            Header.MESSAGE_SIZE -> messageSize = r.integerValue()
            Header.EXPIRY -> expiry = r.expiry()
            Header.DELIVERY_REPORT -> deliveryReport = r.yesNo()
            Header.READ_REPORT -> readReport = r.yesNo()
            Header.REPORT_ALLOWED -> reportAllowed = r.yesNo()
            Header.RESPONSE_STATUS -> responseStatus = r.token()
            Header.RESPONSE_TEXT -> responseText = r.encodedString()
            Header.STATUS -> status = r.token()
            Header.RETRIEVE_STATUS -> retrieveStatus = r.token()
        }
    }

    private fun readBody(reader: WspReader, ct: ContentType): List<MmsPart> =
        if (ContentTypes.isMultipart(ct.mediaType)) {
            Multipart.read(reader)
        } else {
            listOf(MmsPart(ct.mediaType, reader.rest(), ct.name, ct.filename, charset = ct.charset))
        }

    private fun Fields.toPdu(): MmsPdu {
        val type = messageType ?: throw MmsPduException("missing X-Mms-Message-Type")
        val version = mmsVersion ?: MmsPdu.DEFAULT_VERSION
        return when (type) {
            MessageType.SEND_REQ -> SendReq(
                transactionId = required(transactionId, "X-Mms-Transaction-ID"),
                to = to.toList(),
                cc = cc.toList(),
                bcc = bcc.toList(),
                from = from,
                subject = subject,
                dateSeconds = date,
                messageClass = messageClass,
                deliveryReport = deliveryReport,
                readReport = readReport,
                parts = parts,
                mmsVersion = version,
            )
            MessageType.SEND_CONF -> SendConf(
                transactionId = required(transactionId, "X-Mms-Transaction-ID"),
                responseStatus = required(responseStatus, "X-Mms-Response-Status"),
                messageId = messageId,
                responseText = responseText,
                mmsVersion = version,
            )
            MessageType.NOTIFICATION_IND -> NotificationInd(
                transactionId = required(transactionId, "X-Mms-Transaction-ID"),
                contentLocation = required(contentLocation, "X-Mms-Content-Location"),
                from = from,
                subject = subject,
                messageClass = messageClass ?: MessageClass.PERSONAL,
                messageSize = messageSize ?: 0,
                expiry = expiry,
                mmsVersion = version,
            )
            MessageType.NOTIFYRESP_IND -> NotifyRespInd(
                transactionId = required(transactionId, "X-Mms-Transaction-ID"),
                status = required(status, "X-Mms-Status"),
                reportAllowed = reportAllowed,
                mmsVersion = version,
            )
            MessageType.RETRIEVE_CONF -> RetrieveConf(
                transactionId = transactionId,
                messageId = messageId,
                dateSeconds = date,
                from = from,
                to = to.toList(),
                cc = cc.toList(),
                subject = subject,
                contentType = contentType?.mediaType ?: ContentTypes.MULTIPART_RELATED,
                rootType = contentType?.type,
                rootContentId = contentType?.start,
                parts = parts,
                retrieveStatus = retrieveStatus,
                mmsVersion = version,
            )
            MessageType.ACKNOWLEDGE_IND -> AcknowledgeInd(
                transactionId = required(transactionId, "X-Mms-Transaction-ID"),
                reportAllowed = reportAllowed,
                mmsVersion = version,
            )
            MessageType.DELIVERY_IND -> DeliveryInd(
                messageId = required(messageId, "Message-ID"),
                status = required(status, "X-Mms-Status"),
                to = to.firstOrNull(),
                dateSeconds = date,
                mmsVersion = version,
            )
            else -> UnsupportedPdu(type, transactionId, version)
        }
    }

    private fun <T : Any> required(value: T?, header: String): T =
        value ?: throw MmsPduException("missing $header")

    /** A single-octet token (>= 0x80), else null. */
    private fun WspReader.token(): Int? = if (peek() >= 0x80) octet() else null

    private fun WspReader.yesNo(): Boolean? = when (token()) {
        Token.YES -> true
        Token.NO -> false
        else -> null
    }

    /** MMS-Version-value: major in bits 4-6, minor in bits 0-3 (0xF meaning none). */
    private fun WspReader.version(): String {
        if (peek() < 0x80) return text()
        val value = shortInteger()
        val major = value shr 4 and 0x7
        val minor = value and 0xF
        return if (minor == 0xF) "$major" else "$major.$minor"
    }

    private fun WspReader.messageClass(): String? = when {
        peek() >= 0x80 -> MessageClass.TOKENS.getOrNull(octet() - Token.CLASS_BASE)
        peek() > LENGTH_QUOTE -> text()
        else -> null
    }

    private fun WspReader.address(): String = MmsAddress.normalize(encodedString())

    /** From-value: Value-length, then Address-present-token and an address, or Insert-address-token. */
    private fun WspReader.from(): String? {
        val value = lengthPrefixed()
        return when (value.octet()) {
            Token.ADDRESS_PRESENT -> value.address().ifEmpty { null }
            else -> null
        }
    }

    /** Expiry-value: Value-length, then an absolute or relative token and seconds. */
    private fun WspReader.expiry(): Expiry? {
        val value = lengthPrefixed()
        val token = value.octet()
        val seconds = value.integerValue()
        return when (token) {
            Token.ABSOLUTE -> Expiry(seconds, absolute = true)
            Token.RELATIVE -> Expiry(seconds)
            else -> null
        }
    }
}
