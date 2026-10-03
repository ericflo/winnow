package com.ericflo.winnow.mms

/**
 * One body part. [contentType] is the bare media type; [charset], [name] and [filename] travel
 * as its parameters. [contentId] has no angle brackets: the codec adds and strips them.
 */
data class MmsPart(
    val contentType: String,
    val data: ByteArray,
    val name: String? = null,
    val filename: String? = null,
    val contentId: String? = null,
    val contentLocation: String? = null,
    /** IANA MIBenum, e.g. [MmsCharsets.UTF_8]. */
    val charset: Int? = null,
) {
    /** The decoded body of a text part (UTF-8 unless [charset] says otherwise), or null for other types. */
    val text: String?
        get() = if (contentType.startsWith("text/", ignoreCase = true)) {
            MmsCharsets.decode(data, charset ?: MmsCharsets.UTF_8)
        } else {
            null
        }

    override fun equals(other: Any?): Boolean =
        this === other || other is MmsPart &&
            contentType == other.contentType &&
            data.contentEquals(other.data) &&
            name == other.name &&
            filename == other.filename &&
            contentId == other.contentId &&
            contentLocation == other.contentLocation &&
            charset == other.charset

    override fun hashCode(): Int =
        listOf(contentType, name, filename, contentId, contentLocation, charset).hashCode() * 31 +
            data.contentHashCode()

    override fun toString(): String =
        "MmsPart(contentType=$contentType, data=${data.size} bytes, name=$name, filename=$filename, " +
            "contentId=$contentId, contentLocation=$contentLocation, charset=$charset)"

    companion object {
        /** A UTF-8 text/plain part, named after its [contentLocation] as Android's composer does. */
        fun plainText(body: String, contentId: String? = "text", contentLocation: String? = "text.txt") =
            MmsPart(
                contentType = ContentTypes.TEXT_PLAIN,
                data = body.encodeToByteArray(),
                name = contentLocation,
                contentId = contentId,
                contentLocation = contentLocation,
                charset = MmsCharsets.UTF_8,
            )
    }
}
