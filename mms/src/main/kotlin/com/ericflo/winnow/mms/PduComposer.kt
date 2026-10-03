package com.ericflo.winnow.mms

/** Encodes [MmsPdu]s as binary MMS PDUs (OMA-MMS-ENC 1.2/1.3, WAP-230-WSP encodings). */
object PduComposer {
    private val VERSION = Regex("""([0-7])(?:\.(1[0-4]|\d))?""")

    /**
     * Encodes [pdu] with the type, transaction id and version first and any body last. Addresses
     * go through [MmsAddress.encode]; non-ASCII strings are sent as UTF-8 Encoded-string-values.
     * A [SendReq] body is multipart/related (type and start from its SMIL part) when it has a SMIL
     * part, otherwise multipart/mixed.
     *
     * @throws IllegalArgumentException for values the encoding can't carry, such as a negative
     *   date, a malformed [MmsPdu.mmsVersion], or an [UnsupportedPdu].
     */
    fun compose(pdu: MmsPdu): ByteArray {
        val w = WspWriter()
        when (pdu) {
            is SendReq -> w.sendReq(pdu)
            is SendConf -> w.sendConf(pdu)
            is NotificationInd -> w.notificationInd(pdu)
            is NotifyRespInd -> w.notifyRespInd(pdu)
            is RetrieveConf -> w.retrieveConf(pdu)
            is AcknowledgeInd -> w.acknowledgeInd(pdu)
            is DeliveryInd -> w.deliveryInd(pdu)
            is UnsupportedPdu ->
                throw IllegalArgumentException("can't compose message type 0x%02x".format(pdu.messageType))
        }
        return w.toByteArray()
    }

    private fun WspWriter.sendReq(pdu: SendReq) {
        envelope(MessageType.SEND_REQ, pdu)
        pdu.dateSeconds?.let { octet(Header.DATE); longInteger(it) }
        octet(Header.FROM)
        from(pdu.from)
        addresses(Header.TO, pdu.to)
        addresses(Header.CC, pdu.cc)
        addresses(Header.BCC, pdu.bcc)
        pdu.subject?.let { octet(Header.SUBJECT); encodedString(it) }
        pdu.messageClass?.let { octet(Header.MESSAGE_CLASS); messageClass(it) }
        pdu.deliveryReport?.let { octet(Header.DELIVERY_REPORT); yesNo(it) }
        pdu.readReport?.let { octet(Header.READ_REPORT); yesNo(it) }
        val smil = pdu.parts.firstOrNull { it.contentType.equals(ContentTypes.SMIL, ignoreCase = true) }
        val ct = if (smil == null) {
            ContentType(ContentTypes.MULTIPART_MIXED)
        } else {
            ContentType(ContentTypes.MULTIPART_RELATED, type = ContentTypes.SMIL, start = smil.contentId)
        }
        body(ct, pdu.parts)
    }

    private fun WspWriter.sendConf(pdu: SendConf) {
        envelope(MessageType.SEND_CONF, pdu)
        octet(Header.RESPONSE_STATUS)
        token(pdu.responseStatus)
        pdu.responseText?.let { octet(Header.RESPONSE_TEXT); encodedString(it) }
        pdu.messageId?.let { octet(Header.MESSAGE_ID); text(it) }
    }

    private fun WspWriter.notificationInd(pdu: NotificationInd) {
        envelope(MessageType.NOTIFICATION_IND, pdu)
        pdu.from?.let { octet(Header.FROM); from(it) }
        pdu.subject?.let { octet(Header.SUBJECT); encodedString(it) }
        octet(Header.MESSAGE_CLASS)
        messageClass(pdu.messageClass)
        octet(Header.MESSAGE_SIZE)
        longInteger(pdu.messageSize)
        pdu.expiry?.let { expiry ->
            octet(Header.EXPIRY)
            lengthPrefixed {
                octet(if (expiry.absolute) Token.ABSOLUTE else Token.RELATIVE)
                longInteger(expiry.seconds)
            }
        }
        octet(Header.CONTENT_LOCATION)
        text(pdu.contentLocation)
    }

    private fun WspWriter.notifyRespInd(pdu: NotifyRespInd) {
        envelope(MessageType.NOTIFYRESP_IND, pdu)
        octet(Header.STATUS)
        token(pdu.status)
        pdu.reportAllowed?.let { octet(Header.REPORT_ALLOWED); yesNo(it) }
    }

    private fun WspWriter.retrieveConf(pdu: RetrieveConf) {
        envelope(MessageType.RETRIEVE_CONF, pdu)
        pdu.messageId?.let { octet(Header.MESSAGE_ID); text(it) }
        pdu.dateSeconds?.let { octet(Header.DATE); longInteger(it) }
        pdu.from?.let { octet(Header.FROM); from(it) }
        addresses(Header.TO, pdu.to)
        addresses(Header.CC, pdu.cc)
        pdu.subject?.let { octet(Header.SUBJECT); encodedString(it) }
        pdu.retrieveStatus?.let { octet(Header.RETRIEVE_STATUS); token(it) }
        body(ContentType(pdu.contentType, type = pdu.rootType, start = pdu.rootContentId), pdu.parts)
    }

    private fun WspWriter.acknowledgeInd(pdu: AcknowledgeInd) {
        envelope(MessageType.ACKNOWLEDGE_IND, pdu)
        pdu.reportAllowed?.let { octet(Header.REPORT_ALLOWED); yesNo(it) }
    }

    private fun WspWriter.deliveryInd(pdu: DeliveryInd) {
        envelope(MessageType.DELIVERY_IND, pdu)
        octet(Header.MESSAGE_ID)
        text(pdu.messageId)
        pdu.to?.let { addresses(Header.TO, listOf(it)) }
        pdu.dateSeconds?.let { octet(Header.DATE); longInteger(it) }
        octet(Header.STATUS)
        token(pdu.status)
    }

    /** X-Mms-Message-Type, X-Mms-Transaction-ID and X-Mms-MMS-Version, which must lead in that order. */
    private fun WspWriter.envelope(type: Int, pdu: MmsPdu) {
        octet(Header.MESSAGE_TYPE)
        octet(type)
        pdu.transactionId?.let { octet(Header.TRANSACTION_ID); text(it) }
        octet(Header.MMS_VERSION)
        val match = requireNotNull(VERSION.matchEntire(pdu.mmsVersion)) { "bad MMS version \"${pdu.mmsVersion}\"" }
        val (major, minor) = match.destructured
        shortInteger(major.toInt() shl 4 or (minor.toIntOrNull() ?: 0xF))
    }

    /** Content-Type, then the body: multipart entries, or the single part's data for any other type. */
    private fun WspWriter.body(ct: ContentType, parts: List<MmsPart>) {
        octet(Header.CONTENT_TYPE)
        if (ContentTypes.isMultipart(ct.mediaType)) {
            contentType(ct)
            Multipart.write(this, parts)
        } else {
            val part = requireNotNull(parts.singleOrNull()) { "a ${ct.mediaType} body needs exactly one part" }
            contentType(ContentType(part.contentType, part.charset, part.name, part.filename))
            bytes(part.data)
        }
    }

    private fun WspWriter.from(address: String?) = lengthPrefixed {
        if (address == null) {
            octet(Token.INSERT_ADDRESS)
        } else {
            octet(Token.ADDRESS_PRESENT)
            encodedString(MmsAddress.encode(address))
        }
    }

    private fun WspWriter.addresses(field: Int, addresses: List<String>) {
        for (address in addresses) {
            octet(field)
            encodedString(MmsAddress.encode(address))
        }
    }

    private fun WspWriter.messageClass(value: String) {
        val index = MessageClass.TOKENS.indexOf(value.lowercase())
        if (index >= 0) octet(Token.CLASS_BASE + index) else text(value)
    }

    private fun WspWriter.yesNo(value: Boolean) = octet(if (value) Token.YES else Token.NO)

    private fun WspWriter.token(value: Int) {
        require(value in 0x80..0xFF) { "status token out of range: $value" }
        octet(value)
    }
}
