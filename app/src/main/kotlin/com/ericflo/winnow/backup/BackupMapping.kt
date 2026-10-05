package com.ericflo.winnow.backup

import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classifier.message.FilteredPhrases
import com.ericflo.winnow.classifier.message.RedactionPolicy
import com.ericflo.winnow.data.ProviderKind
import com.ericflo.winnow.data.SwipeChoice
import com.ericflo.winnow.data.TextScale
import com.ericflo.winnow.data.ThemeMode
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
    learnFromProvider = learnFromProvider,
    providerWeight = providerWeight,
    hideOnLockScreen = hideOnLockScreen,
    undoSendSeconds = undoSendSeconds,
    deleteOldCodes = deleteOldCodes,
    clearOldFiltered = clearOldFiltered,
    deliveryReports = deliveryReports,
    simpleCharacters = simpleCharacters,
    nudges = nudges,
    categoryActions = categoryActions.entries.associate { (c, a) -> c.key to a.name },
    theme = theme.name,
    textScale = textScale,
    swipeRight = swipeRight.name,
    swipeLeft = swipeLeft.name,
    autoDownloadMms = autoDownloadMms,
    autoDownloadMmsRoaming = autoDownloadMmsRoaming,
    linkPreviews = linkPreviews,
    suggestedReplies = suggestedReplies,
    enterToSend = enterToSend,
    quickReplies = quickReplies,
    autoSaveMedia = autoSaveMedia,
    filteredPhrases = filteredPhrases,
    dailySummary = dailySummary,
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
    learnFromProvider = backup.learnFromProvider ?: learnFromProvider,
    providerWeight = backup.providerWeight?.coerceIn(0.0, 1.0) ?: providerWeight,
    hideOnLockScreen = backup.hideOnLockScreen,
    undoSendSeconds = backup.undoSendSeconds.coerceIn(0, 30),
    deleteOldCodes = backup.deleteOldCodes,
    clearOldFiltered = backup.clearOldFiltered,
    deliveryReports = backup.deliveryReports,
    simpleCharacters = backup.simpleCharacters,
    nudges = backup.nudges ?: nudges,
    theme = ThemeMode.entries.firstOrNull { it.name == backup.theme } ?: theme,
    textScale = TextScale.clamp(backup.textScale),
    swipeRight = SwipeChoice.entries.firstOrNull { it.name == backup.swipeRight } ?: swipeRight,
    swipeLeft = SwipeChoice.entries.firstOrNull { it.name == backup.swipeLeft } ?: swipeLeft,
    autoDownloadMms = backup.autoDownloadMms,
    autoDownloadMmsRoaming = backup.autoDownloadMmsRoaming,
    linkPreviews = backup.linkPreviews,
    suggestedReplies = backup.suggestedReplies ?: suggestedReplies,
    enterToSend = backup.enterToSend,
    quickReplies = backup.quickReplies?.map(String::trim)?.filter(String::isNotEmpty)?.distinct() ?: quickReplies,
    // Added to this phone's, never fewer: a restore shouldn't let through what's filtered here.
    filteredPhrases = (filteredPhrases + backup.filteredPhrases.orEmpty().map(FilteredPhrases::normalize).filter(String::isNotEmpty))
        .distinctBy { it.lowercase() },
    autoSaveMedia = backup.autoSaveMedia,
    dailySummary = backup.dailySummary,
    categoryActions = categoryActions + backup.categoryActions.mapNotNull { (key, action) ->
        // A former category's choice (phishing, scam) mustn't overwrite what the backup says of spam.
        val category = Category.fromCurrentKey(key) ?: return@mapNotNull null
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
    userCategory = userCategory,
    atArrival = atArrival,
    recheck = recheck,
    subcategory = subcategory,
)

/** Null when the backup names an action this version doesn't know, rather than storing garbage. */
/**
 * [sixCategories]: the backup was made under the six categories. One from before has its labels
 * (but political ones, which stand) marked to recheck, as the database migration does.
 */
fun VerdictBackup.toEntity(messageKey: String, threadId: Long, address: String, sixCategories: Boolean = true): VerdictEntity? {
    if (Action.entries.none { it.name == action }) return null
    if (userAction != null && Action.entries.none { it.name == userAction }) return null
    return VerdictEntity(
        messageKey = messageKey,
        threadId = threadId,
        address = address,
        // Under its current name: a backup from before phishing and scam were folded into spam says so.
        category = category?.let { Category.fromKey(it)?.key },
        confidence = confidence,
        action = action,
        sourceKind = sourceKind,
        sourceDetail = sourceDetail,
        model = model,
        costUsd = costUsd,
        decidedAt = decidedAt,
        userAction = userAction,
        userCategory = userCategory?.let { Category.fromKey(it)?.key },
        atArrival = atArrival,
        recheck = recheck || (!sixCategories && userCategory != null && userCategory != Category.POLITICAL.key),
        subcategory = subcategory,
    )
}
