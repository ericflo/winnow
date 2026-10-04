package com.ericflo.winnow.backup

import com.ericflo.winnow.data.normalizeAddress
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlSerializer
import java.io.File
import java.util.Base64

/**
 * Reads and writes "SMS Backup & Restore" XML, the format most Android texting backups use.
 * Reading turns it into Winnow's own backup model so the usual restore can add whatever's
 * missing; MMS media is decoded into a spool as it streams past, so a file with thousands of
 * photos never sits in memory whole. Writing is the way out: any app that reads the format can
 * take the messages along.
 *
 * The format: `<smses>` holding `<sms address date type body read status …/>` and
 * `<mms date msg_box address …><parts><part ct text data …/></parts><addrs><addr address type/></addrs></mms>`,
 * with the literal string "null" for missing values.
 */
object SmsBackupRestoreXml {
    data class Result(val backup: WinnowBackup, val skipped: Int)

    // SMS types: 1 inbox, 2 sent, 3 draft, 4 outbox, 5 failed, 6 queued.
    private const val SMS_INBOX = 1
    private const val SMS_SENT = 2
    private const val SMS_OUTBOX = 4
    private const val SMS_QUEUED = 6
    private const val SMS_DRAFT = 3
    private const val SMS_FAILED = 5
    // MMS boxes: 1 inbox, 2 sent, 3 drafts, 4 outbox, 5 failed.
    private const val MMS_INBOX = 1
    private const val MMS_SENT = 2
    private const val MMS_OUTBOX = 4
    // PDU message types: m-send-req for sent messages, m-retrieve-conf for received ones.
    private const val M_SEND_REQ = 128
    private const val M_RETRIEVE_CONF = 132
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
                                kind = KIND_SMS,
                                date = date,
                                outgoing = !incoming,
                                sender = address.takeIf { incoming },
                                // Raw: a text that just says "null" is still a text.
                                body = parser.getAttributeValue(null, "body").orEmpty(),
                                subject = parser.attr("subject"),
                                status = when {
                                    // Outbox and queued never went out either.
                                    type == SMS_FAILED || type == SMS_OUTBOX || type == SMS_QUEUED -> STATUS_FAILED
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
                                    type == "text/plain" -> parser.getAttributeValue(null, "text")?.let(texts::add)
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
                                kind = KIND_MMS,
                                date = date,
                                outgoing = !incoming,
                                sender = sender.takeIf { incoming },
                                body = texts.joinToString("\n"),
                                subject = subject,
                                status = if (box == MMS_FAILED || box == MMS_OUTBOX) STATUS_FAILED else null,
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

    /**
     * Writes [conversations] as one SMS Backup & Restore file, oldest message first. [media] gives
     * a picture message part's bytes (null leaves it out); [ownNumber], if known, is listed as a
     * recipient of received group messages, as phones record them. [onProgress] gets messages
     * written so far and the total.
     */
    fun write(
        out: XmlSerializer,
        conversations: List<ConversationBackup>,
        ownNumber: String?,
        media: (PartBackup) -> ByteArray?,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ) {
        val messages = conversations.flatMap { c -> c.messages.map { c.recipients to it } }.sortedBy { it.second.date }
        val now = System.currentTimeMillis()
        out.startDocument("UTF-8", true)
        out.startTag(null, "smses")
        out.attr("count", messages.size)
        out.attr("backup_set", java.util.UUID.randomUUID().toString())
        out.attr("backup_date", now)
        out.attr("type", "full")
        messages.forEachIndexed { i, (recipients, m) ->
            if (m.kind == KIND_MMS) writeMms(out, recipients, m, ownNumber, media) else writeSms(out, recipients, m)
            onProgress(i + 1, messages.size)
        }
        out.endTag(null, "smses")
        out.endDocument()
        out.flush()
    }

    private fun writeSms(out: XmlSerializer, recipients: List<String>, m: MessageBackup) {
        out.startTag(null, "sms")
        out.attr("protocol", 0)
        out.attr("address", (if (m.outgoing) m.to else m.sender) ?: recipients.firstOrNull())
        out.attr("date", m.date)
        out.attr("type", when {
            !m.outgoing -> SMS_INBOX
            m.status == STATUS_FAILED -> SMS_FAILED
            else -> SMS_SENT
        })
        out.attr("subject", m.subject)
        out.attr("body", m.body)
        out.attr("toa", null)
        out.attr("sc_toa", null)
        out.attr("service_center", null)
        out.attr("read", if (m.read) 1 else 0)
        out.attr("status", if (m.status == "delivered") 0 else -1)
        out.attr("locked", 0)
        out.attr("date_sent", 0)
        out.endTag(null, "sms")
    }

    private fun writeMms(out: XmlSerializer, recipients: List<String>, m: MessageBackup, ownNumber: String?, media: (PartBackup) -> ByteArray?) {
        val files = m.parts.mapNotNull { part -> media(part)?.let { part to it } }
        out.startTag(null, "mms")
        out.attr("date", m.date)
        out.attr("msg_box", when {
            !m.outgoing -> MMS_INBOX
            m.status == STATUS_FAILED -> MMS_FAILED
            else -> MMS_SENT
        })
        out.attr("address", recipients.joinToString("~"))
        out.attr("m_type", if (m.outgoing) M_SEND_REQ else M_RETRIEVE_CONF)
        out.attr("read", if (m.read) 1 else 0)
        out.attr("seen", 1)
        out.attr("sub", m.subject)
        out.attr("ct_t", "application/vnd.wap.multipart.related")
        out.attr("text_only", if (files.isEmpty()) 1 else 0)
        out.attr("locked", 0)
        out.attr("date_sent", 0)
        out.startTag(null, "parts")
        var seq = 0
        if (m.body.isNotEmpty()) {
            out.startTag(null, "part")
            out.attr("seq", seq++)
            out.attr("ct", "text/plain")
            out.attr("chset", 106)
            out.attr("cl", "text0.txt")
            out.attr("text", m.body)
            out.endTag(null, "part")
        }
        files.forEachIndexed { i, (part, bytes) ->
            val name = part.name ?: "attachment$i"
            out.startTag(null, "part")
            out.attr("seq", seq++)
            out.attr("ct", part.contentType)
            out.attr("name", name)
            out.attr("cl", name)
            out.attr("cid", "<$name>")
            out.attr("data", Base64.getEncoder().encodeToString(bytes))
            out.endTag(null, "part")
        }
        out.endTag(null, "parts")
        out.startTag(null, "addrs")
        if (m.outgoing) {
            out.addr("insert-address-token", ADDR_FROM)
            recipients.forEach { out.addr(it, ADDR_TO) }
        } else {
            val sender = m.sender ?: recipients.firstOrNull()
            sender?.let { out.addr(it, ADDR_FROM) }
            (recipients.filter { it != sender } + listOfNotNull(ownNumber)).forEach { out.addr(it, ADDR_TO) }
        }
        out.endTag(null, "addrs")
        out.endTag(null, "mms")
    }

    private fun XmlSerializer.addr(address: String, type: Int) {
        startTag(null, "addr")
        attr("address", address)
        attr("type", type)
        attr("charset", 106)
        endTag(null, "addr")
    }

    /** Missing values are written as the format's literal "null". */
    private fun XmlSerializer.attr(name: String, value: Any?) {
        attribute(null, name, value?.toString()?.let(::xmlSafe) ?: "null")
    }

    /**
     * [text] as XML can carry it: control characters other than tab and newlines dropped, and a
     * lone half of a surrogate pair or U+FFFE/U+FFFF replaced with U+FFFD. (Android's serializer
     * throws on those, which would end the whole export over one odd message.)
     */
    internal fun xmlSafe(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> {
                    out.append(c).append(text[i + 1])
                    i++
                }
                c.isSurrogate() || c == '\uFFFE' || c == '\uFFFF' -> out.append('\uFFFD')
                c < ' ' && c != '\n' && c != '\r' && c != '\t' -> Unit
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    /** The attribute, with SMS Backup & Restore's literal "null" read as missing. */
    private fun XmlPullParser.attr(name: String): String? = getAttributeValue(null, name)?.takeIf { it != "null" }

    private fun isAddress(value: String) = value.isNotBlank() && value != "insert-address-token"
}
