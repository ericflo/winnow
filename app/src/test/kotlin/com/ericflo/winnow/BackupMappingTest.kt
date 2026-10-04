package com.ericflo.winnow

import com.ericflo.winnow.backup.BackupArchive
import com.ericflo.winnow.backup.VerdictBackup
import com.ericflo.winnow.backup.WinnowBackup
import com.ericflo.winnow.backup.restoring
import com.ericflo.winnow.backup.toBackup
import com.ericflo.winnow.backup.toEntity
import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.data.ProviderKind
import com.ericflo.winnow.data.ProviderSettings
import com.ericflo.winnow.data.WinnowSettings
import com.ericflo.winnow.data.db.VerdictEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class BackupMappingTest {
    private val configured = WinnowSettings(
        provider = ProviderKind.OPENROUTER_JEV,
        providers = mapOf(
            ProviderKind.OPENROUTER_JEV to ProviderSettings(apiKey = "sk-or-SECRET", model = "typesafe/jev-1.13"),
            ProviderKind.CHAT_COMPLETIONS to ProviderSettings(apiKey = "sk-SECRET-2", baseUrl = "https://zdr.example/v1", zeroRetention = true),
        ),
        zdrOnly = true,
        deliveryReports = true,
        onboarded = true,
        categoryActions = Category.entries.associateWith { it.defaultAction } + (Category.MARKETING to Action.FILTER),
    ).let { it.copy(privacy = it.privacy.copy(shareSenderAddress = true, redaction = it.privacy.redaction.copy(maskEmails = false))) }

    @Test
    fun `backups never contain API keys`() {
        val out = ByteArrayOutputStream()
        BackupArchive.write(out, WinnowBackup(createdAt = 1, settings = configured.toBackup())) { null }
        val text = java.util.zip.ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { it.nextEntry; it.readBytes().decodeToString() }
        assertFalse(text, text.contains("SECRET"))
        assertTrue(text.contains("typesafe/jev-1.13"))
    }

    @Test
    fun `restoring on a fresh phone carries everything but keys and per-phone flags`() {
        val restored = WinnowSettings().restoring(configured.toBackup())
        assertEquals(ProviderKind.OPENROUTER_JEV, restored.provider)
        assertEquals("typesafe/jev-1.13", restored.settingsFor(ProviderKind.OPENROUTER_JEV).model)
        assertEquals("", restored.settingsFor(ProviderKind.OPENROUTER_JEV).apiKey)
        assertEquals("https://zdr.example/v1", restored.settingsFor(ProviderKind.CHAT_COMPLETIONS).baseUrl)
        assertTrue(restored.settingsFor(ProviderKind.CHAT_COMPLETIONS).zeroRetention)
        assertEquals(configured.privacy, restored.privacy)
        assertTrue(restored.zdrOnly && restored.deliveryReports)
        assertEquals(configured.categoryActions, restored.categoryActions)
        assertFalse(restored.onboarded)
    }

    @Test
    fun `restoring keeps keys already on this phone`() {
        val here = WinnowSettings(providers = mapOf(ProviderKind.OPENROUTER_JEV to ProviderSettings(apiKey = "sk-here")))
        val restored = here.restoring(configured.toBackup())
        assertEquals("sk-here", restored.settingsFor(ProviderKind.OPENROUTER_JEV).apiKey)
        assertEquals("typesafe/jev-1.13", restored.settingsFor(ProviderKind.OPENROUTER_JEV).model)
    }

    @Test
    fun `unknown names from a newer version are ignored, not stored`() {
        val backup = configured.toBackup().copy(provider = "QUANTUM", categoryActions = mapOf("future" to "FILTER", "marketing" to "LAUNCH"))
        val restored = WinnowSettings().restoring(backup)
        assertEquals(ProviderKind.ON_DEVICE, restored.provider)
        assertEquals(WinnowSettings().categoryActions, restored.categoryActions)

        assertNull(VerdictBackup(confidence = 1.0, action = "LAUNCH", sourceKind = "rule", sourceDetail = "x").toEntity("sms:1", 1, "a"))
    }

    @Test
    fun `verdicts survive a round trip under their new keys`() {
        val entity = VerdictEntity(
            messageKey = "sms:41", threadId = 7, address = "+13185550182", category = "phishing", confidence = 0.98,
            action = "FILTER", sourceKind = "provider", sourceDetail = "systemone:openrouter", model = "jev-1.13",
            costUsd = 0.0001, decidedAt = 1_790_000_000_000, userAction = "ALLOW",
        )
        assertEquals(entity.copy(messageKey = "sms:9001", threadId = 3), entity.toBackup().toEntity("sms:9001", 3, "+13185550182"))
    }
}
