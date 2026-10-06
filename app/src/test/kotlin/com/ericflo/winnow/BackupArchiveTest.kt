package com.ericflo.winnow

import com.ericflo.winnow.backup.BackupArchive
import com.ericflo.winnow.backup.ConversationBackup
import com.ericflo.winnow.backup.CorrectionBackup
import com.ericflo.winnow.backup.LabRecipeBackup
import com.ericflo.winnow.backup.MessageBackup
import com.ericflo.winnow.backup.PartBackup
import com.ericflo.winnow.backup.SenderRuleBackup
import com.ericflo.winnow.backup.VerdictBackup
import com.ericflo.winnow.backup.WinnowBackup
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupArchiveTest {
    private val photo = ByteArray(5000) { (it % 251).toByte() }
    private val backup = WinnowBackup(
        createdAt = 1_791_000_000_000,
        senderRules = listOf(SenderRuleBackup("4155550123", "ALWAYS_FILTER", 1)),
        corrections = listOf(
            CorrectionBackup(listOf(17, 4096, 30001), "personal", featurizerVersion = 1, createdAt = 5),
            // A label, naming its message by the key it had on the phone it came from.
            CorrectionBackup(listOf(9, 2048), "phishing", featurizerVersion = 1, createdAt = 6, messageKey = "sms:41"),
        ),
        conversations = listOf(
            ConversationBackup(
                recipients = listOf("+14155550181", "+14155550182"),
                pinned = true,
                draft = "Bring snacks",
                draftSubject = "Lake house 🏡",
                draftAttachments = listOf(PartBackup("image/jpeg", "map.jpg", "draft-1-0.jpg")),
                messages = listOf(
                    MessageBackup("mms", 1_790_000_000_000, outgoing = false, sender = "+14155550181", body = "Lake house!",
                        parts = listOf(PartBackup("image/jpeg", "photo.jpg", "0.jpg"))),
                    MessageBackup("sms", 1_790_000_100_000, outgoing = true, body = "Count me in 🎉",
                        verdict = null),
                ),
            ),
            ConversationBackup(
                recipients = listOf("+13185550182"),
                messages = listOf(
                    MessageBackup("sms", 1_790_000_200_000, outgoing = false, sender = "+13185550182", body = "E-ZPass: pay now",
                        verdict = VerdictBackup("phishing", 0.98, "FILTER", "provider", "systemone:typesafe", "jev-1.13", userCategory = "phishing"),
                        labelKey = "sms:41"),
                ),
            ),
        ),
    )

    @Test
    fun `round trips the manifest and media`() {
        val out = ByteArrayOutputStream()
        val map = ByteArray(300) { 7 }
        BackupArchive.write(out, backup) { part ->
            when (part.file) {
                "0.jpg" -> ByteArrayInputStream(photo)
                "draft-1-0.jpg" -> ByteArrayInputStream(map)
                else -> null
            }
        }
        val media = HashMap<String, ByteArray>()
        val read = BackupArchive.read(ByteArrayInputStream(out.toByteArray())) { name, stream -> media[name] = stream.readBytes() }
        assertEquals(backup, read)
        assertEquals(3, read.messageCount)
        assertArrayEquals(photo, media.getValue("0.jpg"))
        // A draft's attachments travel with the media, not as a message's.
        assertArrayEquals(map, media.getValue("draft-1-0.jpg"))
        assertEquals(backup, BackupArchive.peek(ByteArrayInputStream(out.toByteArray())))
        val manifest = java.util.zip.ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { it.nextEntry; it.readBytes().decodeToString() }
        assert(manifest.startsWith("{\"format\":${WinnowBackup.FORMAT},")) { manifest.take(40) }
    }

    @Test
    fun `names given to rcs people and lab designs travel too, and older backups without them still read`() {
        val withMore = backup.copy(
            names = mapOf("3f9a0c1d2e4b5a69@rcs.google.com" to "Aunt Pat"),
            labRecipes = listOf(LabRecipeBackup("d5c2a3", "Neural, deeper", com.ericflo.winnow.classifier.local.Recipe.PRESETS[2].second, 7)),
        )
        val out = ByteArrayOutputStream()
        BackupArchive.write(out, withMore) { null }
        assertEquals(withMore, BackupArchive.read(ByteArrayInputStream(out.toByteArray())) { _, _ -> })
        // One made before either existed reads with none.
        val old = ByteArrayOutputStream()
        BackupArchive.write(old, backup) { null }
        val read = BackupArchive.read(ByteArrayInputStream(old.toByteArray())) { _, _ -> }
        assertEquals(emptyMap<String, String>(), read.names)
        assertEquals(emptyList<LabRecipeBackup>(), read.labRecipes)
    }

    @Test
    fun `rejects files that aren't backups, and newer formats`() {
        val notBackup = ByteArrayOutputStream().also { ZipOutputStream(it).use { z -> z.putNextEntry(ZipEntry("x.txt")); z.write(1) } }
        assertThrows(IllegalArgumentException::class.java) { BackupArchive.read(ByteArrayInputStream(notBackup.toByteArray())) }

        val future = ByteArrayOutputStream()
        BackupArchive.write(future, backup.copy(format = WinnowBackup.FORMAT + 1)) { null }
        assertThrows(IllegalArgumentException::class.java) { BackupArchive.read(ByteArrayInputStream(future.toByteArray())) }

        val garbage = ByteArrayOutputStream().also { ZipOutputStream(it).use { z -> z.putNextEntry(ZipEntry(BackupArchive.MANIFEST)); z.write("{nope".encodeToByteArray()) } }
        assertThrows(IllegalArgumentException::class.java) { BackupArchive.peek(ByteArrayInputStream(garbage.toByteArray())) }
    }

    @Test
    fun `ignores media entries that try to escape the folder`() {
        val sneaky = ByteArrayOutputStream()
        ZipOutputStream(sneaky).use { z ->
            z.putNextEntry(ZipEntry(BackupArchive.MANIFEST)); z.write("""{"createdAt":1}""".encodeToByteArray()); z.closeEntry()
            listOf("media/../../evil.so", "media/a/b.jpg", "media/..", "media/").forEach { z.putNextEntry(ZipEntry(it)); z.write(1); z.closeEntry() }
            z.putNextEntry(ZipEntry("media/ok.jpg")); z.write(1); z.closeEntry()
        }
        val names = mutableListOf<String>()
        BackupArchive.read(ByteArrayInputStream(sneaky.toByteArray())) { name, _ -> names += name }
        assertEquals(listOf("ok.jpg"), names)
    }

    @Test
    fun `drops parts whose files would climb out of the media folder`() {
        val manifest = """{"createdAt":1,"conversations":[{"recipients":["+14155550181"],
            "draftAttachments":[{"contentType":"image/jpeg","name":"photo.jpg","file":"../../databases/winnow.db"},
                                {"contentType":"image/jpeg","file":"draft-1-0.jpg"}],
            "messages":[{"kind":"mms","date":1,"outgoing":false,"body":"",
                "parts":[{"contentType":"image/jpeg","file":"../shared_prefs/x.xml"},{"contentType":"image/jpeg","file":"7.jpg"},
                         {"contentType":"image/jpeg","file":"a\\b.jpg"}]}]}]}"""
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { z -> z.putNextEntry(ZipEntry(BackupArchive.MANIFEST)); z.write(manifest.encodeToByteArray()); z.closeEntry() }
        val read = BackupArchive.read(ByteArrayInputStream(zip.toByteArray()))
        val conversation = read.conversations.single()
        assertEquals(listOf("draft-1-0.jpg"), conversation.draftAttachments.map { it.file })
        assertEquals(listOf("7.jpg"), conversation.messages.single().parts.map { it.file })
    }

    @Test
    fun `fingerprints distinguish messages that differ`() {
        val a = backup.conversations[0].messages[1]
        assertEquals(a.fingerprint, a.copy(verdict = null).fingerprint)
        assertNotEquals(a.fingerprint, a.copy(body = "Count me out").fingerprint)
        assertNotEquals(a.fingerprint, a.copy(date = a.date + 1).fingerprint)
    }
}
