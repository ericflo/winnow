package com.ericflo.winnow.mms

/** Content types an MMS body is built from. */
object ContentTypes {
    const val MULTIPART_RELATED = "application/vnd.wap.multipart.related"
    const val MULTIPART_MIXED = "application/vnd.wap.multipart.mixed"
    const val MULTIPART_ALTERNATIVE = "application/vnd.wap.multipart.alternative"
    const val SMIL = "application/smil"
    const val TEXT_PLAIN = "text/plain"
    const val OCTET_STREAM = "application/octet-stream"

    fun isMultipart(type: String): Boolean =
        type.startsWith("application/vnd.wap.multipart.", ignoreCase = true) ||
            type.startsWith("multipart/", ignoreCase = true)
}

/** WSP well-known content types (WAP-230-WSP Table 40 and the OMNA registry): the index is the code. */
internal object WellKnownContentTypes {
    private val types = arrayOf(
        "*/*", "text/*", "text/html", "text/plain", // 0x00
        "text/x-hdml", "text/x-ttml", "text/x-vCalendar", "text/x-vCard", // 0x04
        "text/vnd.wap.wml", "text/vnd.wap.wmlscript", "text/vnd.wap.wta-event", "multipart/*", // 0x08
        "multipart/mixed", "multipart/form-data", "multipart/byteranges", "multipart/alternative", // 0x0C
        "application/*", "application/java-vm", "application/x-www-form-urlencoded", "application/x-hdmlc", // 0x10
        "application/vnd.wap.wmlc", "application/vnd.wap.wmlscriptc", "application/vnd.wap.wta-eventc",
        "application/vnd.wap.uaprof", // 0x14
        "application/vnd.wap.wtls-ca-certificate", "application/vnd.wap.wtls-user-certificate",
        "application/x-x509-ca-cert", "application/x-x509-user-cert", // 0x18
        "image/*", "image/gif", "image/jpeg", "image/tiff", // 0x1C
        "image/png", "image/vnd.wap.wbmp", "application/vnd.wap.multipart.*",
        "application/vnd.wap.multipart.mixed", // 0x20
        "application/vnd.wap.multipart.form-data", "application/vnd.wap.multipart.byteranges",
        "application/vnd.wap.multipart.alternative", "application/xml", // 0x24
        "text/xml", "application/vnd.wap.wbxml", "application/x-x968-cross-cert", "application/x-x968-ca-cert", // 0x28
        "application/x-x968-user-cert", "text/vnd.wap.si", "application/vnd.wap.sic", "text/vnd.wap.sl", // 0x2C
        "application/vnd.wap.slc", "text/vnd.wap.co", "application/vnd.wap.coc",
        "application/vnd.wap.multipart.related", // 0x30
        "application/vnd.wap.sia", "text/vnd.wap.connectivity-xml", "application/vnd.wap.connectivity-wbxml",
        "application/pkcs7-mime", // 0x34
        "application/vnd.wap.hashed-certificate", "application/vnd.wap.signed-certificate",
        "application/vnd.wap.cert-response", "application/xhtml+xml", // 0x38
        "application/wml+xml", "text/css", "application/vnd.wap.mms-message",
        "application/vnd.wap.rollover-certificate", // 0x3C
        "application/vnd.wap.locc+wbxml", "application/vnd.wap.loc+xml", "application/vnd.syncml.dm+wbxml",
        "application/vnd.syncml.dm+xml", // 0x40
        "application/vnd.syncml.notification", "application/vnd.wap.xhtml+xml", "application/vnd.wv.csp.cir",
        "application/vnd.oma.dd+xml", // 0x44
        "application/vnd.oma.drm.message", "application/vnd.oma.drm.content", "application/vnd.oma.drm.rights+xml",
        "application/vnd.oma.drm.rights+wbxml", // 0x48
        "application/vnd.wv.csp+xml", "application/vnd.wv.csp+wbxml", "application/vnd.syncml.ds.notification",
        "audio/*", // 0x4C
        "video/*", "application/vnd.oma.dd2+xml", "application/mikey", // 0x50
    )
    private val codes = types.withIndex().associate { (code, type) -> type.lowercase() to code }

    /** The type for [code], or application/octet-stream for an unassigned code. */
    fun name(code: Long): String = if (code >= 0 && code < types.size) types[code.toInt()] else ContentTypes.OCTET_STREAM

    fun code(type: String): Int? = codes[type.lowercase()]
}
