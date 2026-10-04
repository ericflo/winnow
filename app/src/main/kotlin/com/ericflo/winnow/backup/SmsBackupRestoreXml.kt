package com.ericflo.winnow.backup

import com.ericflo.winnow.data.normalizeAddress
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.Base64

/**
 * Reads an "SMS Backup & Restore" XML file, the format most Android texting backups use, into
 * Winnow's own backup model so the usual restore can add whatever's missing. MMS media is decoded
 * into [spool] as it streams past, so a file with thousands of photos never sits in memory whole.
 *
 * The format: `<smses>` holding `<sms address date type body read status …/>` and
 * `<mms date msg_box address …><parts><part ct text data …/></parts><addrs><addr address type/></addrs></mms>`,
 * with the literal string "null" for missing values.
 */
object SmsBackupRestoreXml {
    data class Result(val backup: WinnowBackup, val skipped: Int)

    // SMS types: 1 inbox, 2 sent, 3 draft, 4 outbox, 5 failed, 6 queued.
    private const val SMS_INBOX = 1
    private const val SMS_DRAFT = 3
    private const val SMS_FAILED = 5
    // MMS boxes: 1 inbox, 2 sent, 3 drafts, 4 outbox, 5 failed.
    private const val MMS_INBOX = 1
    private const val MMS_DRAFT = 3
    private const val MMS_FAILED = 5
    // PduHeaders address types.
    private const val ADDR_FROM = 137
    private const val ADDR_TO = 151
    private const val ADDR_CC = 130

    /**
     * [parser] must already have its input. [ownNumbers] keeps this phone's own number out of
     * group conversations, since a received group text lists it among the recipients.
     * [onProgress] gets the number of messages read so far and the file's declared total.
     */
    fun read(parser: XmlPullParser, spool: File, ownNumbers: Set<String>, onProgress: (Int, Int) -> Unit = { _, _ -> }): Result {
        val own = ownNumbers.map(::normalizeAddress).toSet()
        val byParticipants = LinkedHashMap<String, Pair<List<String>, MutableList<MessageBackup>>>()
        var total = 0
        var read = 0
        var skipped = 0
        var mediaFiles = 0

        fun add(participants: List<String>, message: MessageBackup) {
            val key = participants.map(::normalizeAddress).sorted().joinToString(",")
            byParticipants.getOrPut(key) { participants to mutableListOf() }.second += message
        }

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "smses" -> total = parser.attr("count")?.toIntOrNull() ?: 0
                "sms" -> {
                    val type = parser.attr("type")?.toIntOrNull()
                    val address = parser.attr("address")
                    val date = parser.attr("date")?.toLongOrNull()
                    if (type == null || type == SMS_DRAFT || address == null || date == null) {
                        skipped++
                    } else {
                        val incoming = type == SMS_INBOX
                        add(
                            listOf(address),
                            MessageBackup(
                                kind = "sms",
                                date = date,
                                outgoing = !incoming,
                                sender = address.takeIf { incoming },
                                body = parser.attr("body").orEmpty(),
                                subject = parser.attr("subject"),
                                status = when {
                                    type == SMS_FAILED -> "failed"
                                    !incoming && parser.attr("status") == "0" -> "delivered"
                                    else -> null
                                },
                                read = parser.attr("read") != "0",
                            ),
                        )
                    }
                    read++
                    onProgress(read, total)
                }
                "mms" -> {
                    val box = parser.attr("msg_box")?.toIntOrNull()
                    // Whole seconds: that's what the MMS store keeps, and duplicates are matched on it.
                    val date = parser.attr("date")?.toLongOrNull()?.let { it / 1000 * 1000 }
                    val listed = parser.attr("address")?.split('~').orEmpty().map(String::trim).filter(::isAddress)
                    // Attributes of <mms> itself, read before moving on to its children.
                    val subject = parser.attr("sub")
                    val wasRead = parser.attr("read") != "0"
                    val texts = mutableListOf<String>()
                    val parts = mutableListOf<PartBackup>()
                    val addrs = mutableListOf<Pair<Int, String>>()
                    // The <mms> element's children: <parts> and <addrs>.
                    val depth = parser.depth
                    while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
                        if (parser.eventType == XmlPullParser.END_DOCUMENT) break
                        if (parser.eventType != XmlPullParser.START_TAG) continue
                        when (parser.name) {
                            "part" -> {
                                val type = parser.attr("ct")?.lowercase().orEmpty()
                                when {
                                    type == "text/plain" -> parser.attr("text")?.let(texts::add)
                                    type == "application/smil" || type.isEmpty() -> Unit
                                    else -> parser.attr("data")?.let { data ->
                                        val bytes = runCatching { Base64.getMimeDecoder().decode(data) }.getOrNull() ?: return@let
                                        val file = "xml-${mediaFiles++}"
                                        File(spool, file).writeBytes(bytes)
                                        val name = listOf("fn", "name", "cl").firstNotNullOfOrNull { parser.attr(it) }
                                        parts += PartBackup(type, name, file)
                                    }
                                }
                            }
                            "addr" -> {
                                val type = parser.attr("type")?.toIntOrNull()
                                val address = parser.attr("address")
                                if (type != null && address != null && isAddress(address)) addrs += type to address
                            }
                        }
                    }
                    val incoming = box == MMS_INBOX
                    val sender = addrs.firstOrNull { it.first == ADDR_FROM }?.second
                    // Everyone in the conversation but this phone: the listed addresses, else From/To/Cc.
                    val participants = listed.ifEmpty { addrs.filter { it.first in setOf(ADDR_FROM, ADDR_TO, ADDR_CC) }.map { it.second } }
                        .filter { normalizeAddress(it) !in own }
                        .distinctBy(::normalizeAddress)
                    if (box == null || box == MMS_DRAFT || date == null || participants.isEmpty() || (texts.isEmpty() && parts.isEmpty())) {
                        skipped++
                    } else {
                        add(
                            participants,
                            MessageBackup(
                                kind = "mms",
                                date = date,
                                outgoing = !incoming,
                                sender = sender.takeIf { incoming },
                                body = texts.joinToString("\n"),
                                subject = subject,
                                status = if (box == MMS_FAILED) "failed" else null,
                                read = wasRead,
                                parts = parts,
                            ),
                        )
                    }
                    read++
                    onProgress(read, total)
                }
            }
        }
        val conversations = byParticipants.values.map { (recipients, messages) ->
            ConversationBackup(recipients = recipients, messages = messages.sortedBy { it.date })
        }
        return Result(WinnowBackup(createdAt = System.currentTimeMillis(), conversations = conversations), skipped)
    }

    /** The attribute, with SMS Backup & Restore's literal "null" read as missing. */
    private fun XmlPullParser.attr(name: String): String? = getAttributeValue(null, name)?.takeIf { it != "null" }

    private fun isAddress(value: String) = value.isNotBlank() && value != "insert-address-token"
}
