package com.ericflo.winnow.backup

import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classifier.message.RedactionPolicy
import com.ericflo.winnow.data.ProviderKind
import com.ericflo.winnow.data.WinnowSettings
import com.ericflo.winnow.data.db.VerdictEntity

/** Everything worth carrying to another phone, minus API keys and per-phone flags like onboarding. */
fun WinnowSettings.toBackup() = SettingsBackup(
    provider = provider.name,
    providers = providers
        .filterValues { it.model.isNotBlank() || it.baseUrl.isNotBlank() || it.zeroRetention }
        .entries.associate { (kind, p) -> kind.name to ProviderBackup(p.model, p.baseUrl, p.zeroRetention) },
    classifyContacts = privacy.classifyContacts,
    classifyKnownConversations = privacy.classifyKnownConversations,
    classifyVerificationCodes = privacy.classifyVerificationCodes,
    shareSenderAddress = privacy.shareSenderAddress,
    maskDigitRuns = privacy.redaction.maskDigitRuns,
    maskEmails = privacy.redaction.maskEmails,
    stripUrlPaths = privacy.redaction.stripUrlPaths,
    zdrOnly = zdrOnly,
    decideOnPhoneWhenSure = decideOnPhoneWhenSure,
    deliveryReports = deliveryReports,
    categoryActions = categoryActions.entries.associate { (c, a) -> c.key to a.name },
)

/** These settings with [backup]'s applied. API keys already on this phone are kept. */
fun WinnowSettings.restoring(backup: SettingsBackup) = copy(
    provider = ProviderKind.entries.firstOrNull { it.name == backup.provider } ?: provider,
    providers = ProviderKind.entries.associateWith { kind ->
        val mine = settingsFor(kind)
        backup.providers[kind.name]?.let { mine.copy(model = it.model, baseUrl = it.baseUrl, zeroRetention = it.zeroRetention) } ?: mine
    },
    privacy = privacy.copy(
        classifyContacts = backup.classifyContacts,
        classifyKnownConversations = backup.classifyKnownConversations,
        classifyVerificationCodes = backup.classifyVerificationCodes,
        shareSenderAddress = backup.shareSenderAddress,
        redaction = RedactionPolicy(backup.maskDigitRuns, backup.maskEmails, backup.stripUrlPaths),
    ),
    zdrOnly = backup.zdrOnly,
    decideOnPhoneWhenSure = backup.decideOnPhoneWhenSure,
    deliveryReports = backup.deliveryReports,
    categoryActions = categoryActions + backup.categoryActions.mapNotNull { (key, action) ->
        val category = Category.fromKey(key) ?: return@mapNotNull null
        Action.entries.firstOrNull { it.name == action }?.let { category to it }
    },
)

fun VerdictEntity.toBackup() = VerdictBackup(
    category = category,
    confidence = confidence,
    action = action,
    sourceKind = sourceKind,
    sourceDetail = sourceDetail,
    model = model,
    userAction = userAction,
    costUsd = costUsd,
    decidedAt = decidedAt,
)

/** Null when the backup names an action this version doesn't know, rather than storing garbage. */
fun VerdictBackup.toEntity(messageKey: String, threadId: Long, address: String): VerdictEntity? {
    if (Action.entries.none { it.name == action }) return null
    if (userAction != null && Action.entries.none { it.name == userAction }) return null
    return VerdictEntity(
        messageKey = messageKey,
        threadId = threadId,
        address = address,
        category = category?.takeIf { Category.fromKey(it) != null },
        confidence = confidence,
        action = action,
        sourceKind = sourceKind,
        sourceDetail = sourceDetail,
        model = model,
        costUsd = costUsd,
        decidedAt = decidedAt,
        userAction = userAction,
    )
}
