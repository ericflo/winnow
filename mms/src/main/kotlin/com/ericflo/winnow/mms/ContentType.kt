package com.ericflo.winnow.mms

/** A Content-type-value: media type plus the parameters MMS uses. */
internal data class ContentType(
    val mediaType: String,
    val charset: Int? = null,
    val name: String? = null,
    val filename: String? = null,
    /** multipart/related `type`: the root part's content type. */
    val type: String? = null,
    /** multipart/related `start`: the root part's content-id, without angle brackets. */
    val start: String? = null,
)

// Well-known parameter numbers (WAP-230-WSP Table 38). On the wire each is an Integer-value,
// almost always the short form (0x80 | number). 0x17..0x19 are the WSP 1.4 Text-value variants.
private const val PARAM_Q = 0x00L
private const val PARAM_CHARSET = 0x01L
private const val PARAM_TYPE = 0x03L
private const val PARAM_NAME = 0x05L
private const val PARAM_FILENAME = 0x06L
private const val PARAM_RELATED_TYPE = 0x09L
private const val PARAM_START = 0x0AL
private const val PARAM_NAME_V14 = 0x17L
private const val PARAM_FILENAME_V14 = 0x18L
private const val PARAM_START_V14 = 0x19L

/** Content-type-value (WSP 8.4.2.24): a well-known code, extension media text, or the general form. */
internal fun WspReader.contentType(): ContentType {
    val first = peek()
    if (first >= 0x80) return ContentType(WellKnownContentTypes.name(shortInteger().toLong()))
    if (first > LENGTH_QUOTE) return ContentType(text())
    val value = lengthPrefixed()
    return value.parameters(ContentType(value.media()))
}

/** Reads parameters to the end of this reader. A malformed parameter ends the list without failing the value. */
internal fun WspReader.parameters(base: ContentType): ContentType {
    var ct = base
    while (hasMore()) {
        ct = try {
            parameter(ct)
        } catch (e: MmsPduException) {
            return ct
        }
    }
    return ct
}

private fun WspReader.media(): String {
    val b = peek()
    return if (b >= 0x80 || b < LENGTH_QUOTE) WellKnownContentTypes.name(integerValue()) else text()
}

private fun WspReader.parameter(ct: ContentType): ContentType {
    if (peek() in 0x20..0x7F) return untypedParameter(ct)
    return when (integerValue()) {
        PARAM_Q -> ct.also { uintvar() }
        PARAM_CHARSET -> ct.copy(charset = charset().takeIf { it != 0 })
        PARAM_TYPE, PARAM_RELATED_TYPE -> ct.copy(type = media())
        PARAM_NAME, PARAM_NAME_V14 -> ct.copy(name = text())
        PARAM_FILENAME, PARAM_FILENAME_V14 -> ct.copy(filename = text())
        PARAM_START, PARAM_START_V14 -> ct.copy(start = text().withoutAngles())
        else -> ct.also { skipValue() }
    }
}

/** Untyped-parameter: Token-text name, then an Integer-value or Text-value. */
private fun WspReader.untypedParameter(ct: ContentType): ContentType {
    val key = text().lowercase()
    val b = peek()
    val number = if (b >= 0x80 || b in 1 until LENGTH_QUOTE) integerValue() else null
    val value = number?.toString() ?: text()
    return when (key) {
        "charset" -> ct.copy(charset = number?.toInt() ?: MmsCharsets.mibOf(value))
        "type" -> ct.copy(type = value)
        "start" -> ct.copy(start = value.withoutAngles())
        "name" -> ct.copy(name = value)
        "filename" -> ct.copy(filename = value)
        else -> ct
    }
}

/**
 * Writes the constrained form (a table code or extension text) when there are no parameters,
 * otherwise the general form. Types in [WellKnownContentTypes] always go out as their code.
 */
internal fun WspWriter.contentType(ct: ContentType) {
    require(ct.mediaType.isNotBlank()) { "content type is blank" }
    val params = WspWriter().apply {
        ct.start?.let { shortInteger(PARAM_START.toInt()); text("<${it.withoutAngles()}>") }
        ct.type?.let { shortInteger(PARAM_RELATED_TYPE.toInt()); text(it) }
        ct.charset?.let { shortInteger(PARAM_CHARSET.toInt()); integerValue(it.toLong()) }
        ct.name?.let { shortInteger(PARAM_NAME.toInt()); text(it) }
        ct.filename?.let { shortInteger(PARAM_FILENAME.toInt()); text(it) }
    }.toByteArray()
    val code = WellKnownContentTypes.code(ct.mediaType)
    val media: WspWriter.() -> Unit = { if (code != null) shortInteger(code) else text(ct.mediaType) }
    if (params.isEmpty()) {
        media()
    } else {
        lengthPrefixed {
            media()
            bytes(params)
        }
    }
}

internal fun String.withoutAngles(): String =
    if (length >= 2 && startsWith('<') && endsWith('>')) substring(1, length - 1) else this
