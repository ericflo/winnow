package com.ericflo.winnow.mms

/** MMS header field codes (OMA-MMS-ENC Table 25), with the short-integer high bit set. */
internal object Header {
    const val BCC = 0x81
    const val CC = 0x82
    const val CONTENT_LOCATION = 0x83
    const val CONTENT_TYPE = 0x84
    const val DATE = 0x85
    const val DELIVERY_REPORT = 0x86
    const val EXPIRY = 0x88
    const val FROM = 0x89
    const val MESSAGE_CLASS = 0x8A
    const val MESSAGE_ID = 0x8B
    const val MESSAGE_TYPE = 0x8C
    const val MMS_VERSION = 0x8D
    const val MESSAGE_SIZE = 0x8E
    const val READ_REPORT = 0x90
    const val REPORT_ALLOWED = 0x91
    const val RESPONSE_STATUS = 0x92
    const val RESPONSE_TEXT = 0x93
    const val STATUS = 0x95
    const val SUBJECT = 0x96
    const val TO = 0x97
    const val TRANSACTION_ID = 0x98
    const val RETRIEVE_STATUS = 0x99
}

/** X-Mms-Message-Type values. */
internal object MessageType {
    const val SEND_REQ = 0x80
    const val SEND_CONF = 0x81
    const val NOTIFICATION_IND = 0x82
    const val NOTIFYRESP_IND = 0x83
    const val RETRIEVE_CONF = 0x84
    const val ACKNOWLEDGE_IND = 0x85
    const val DELIVERY_IND = 0x86
}

/** Single-octet tokens shared by several headers. */
internal object Token {
    const val YES = 0x80
    const val NO = 0x81
    const val ADDRESS_PRESENT = 0x80
    const val INSERT_ADDRESS = 0x81
    const val ABSOLUTE = 0x80
    const val RELATIVE = 0x81
    const val CLASS_BASE = 0x80
}
