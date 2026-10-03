package com.ericflo.winnow.mms

/** X-Mms-Message-Class values. Unrecognized classes pass through as their token text. */
object MessageClass {
    const val PERSONAL = "personal"
    const val ADVERTISEMENT = "advertisement"
    const val INFORMATIONAL = "informational"
    const val AUTO = "auto"

    /** Wire order: index + 0x80 is the token. */
    internal val TOKENS = listOf(PERSONAL, ADVERTISEMENT, INFORMATIONAL, AUTO)
}

/** X-Mms-Status values, as used by [NotifyRespInd] and [DeliveryInd]. */
object MmsStatus {
    const val EXPIRED = 0x80
    const val RETRIEVED = 0x81
    const val REJECTED = 0x82
    const val DEFERRED = 0x83
    const val UNRECOGNISED = 0x84
    const val INDETERMINATE = 0x85
    const val FORWARDED = 0x86
    const val UNREACHABLE = 0x87
}

/** X-Mms-Response-Status and X-Mms-Retrieve-Status values. */
object ResponseStatus {
    const val OK = 0x80
    const val ERROR_TRANSIENT_FAILURE = 0xC0
    const val ERROR_PERMANENT_FAILURE = 0xE0

    /** 0xC0..0xDF: the same request may succeed later. */
    fun isTransientFailure(status: Int): Boolean = status in 0xC0..0xDF
}
