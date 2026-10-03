package com.ericflo.winnow.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ericflo.winnow.classifier.DataHandling
import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.classifier.message.ActionPolicy
import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classifier.message.PrivacyPolicy
import com.ericflo.winnow.classifier.message.RedactionPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** The provider choices offered in Settings. Each maps to a [com.ericflo.winnow.classifier.DecisionProvider]. */
enum class ProviderKind(
    val providerId: String?,
    val label: String,
    val blurb: String,
    val defaultModel: String,
    val needsBaseUrl: Boolean,
    val needsApiKey: Boolean,
) {
    ON_DEVICE(
        null, "On this phone only",
        "Nothing leaves the phone. Keyword rules can silence messages but never hide them.",
        "", needsBaseUrl = false, needsApiKey = false,
    ),
    TYPESAFE_JEV(
        "systemone:typesafe", "Jev (TypeSafe)",
        "TypeSafe's hosted decision model: calibrated probabilities for a fraction of a cent.",
        "jev-latest", needsBaseUrl = false, needsApiKey = true,
    ),
    OPENROUTER_JEV(
        "systemone:openrouter", "Jev via OpenRouter",
        "The same Jev model, billed through OpenRouter.",
        "typesafe/jev-1.13", needsBaseUrl = false, needsApiKey = true,
    ),
    SYSTEM_ONE(
        "systemone:custom", "Other System One server",
        "Any server speaking the same decision API, including self-hosted open models.",
        "jev-latest", needsBaseUrl = true, needsApiKey = false,
    ),
    CHAT_COMPLETIONS(
        "chat:custom", "OpenAI-compatible model",
        "Any chat-completions endpoint: a zero-retention vendor or your own llama.cpp or vLLM server.",
        "", needsBaseUrl = true, needsApiKey = false,
    ),
    ;

    companion object {
        fun labelFor(providerId: String): String = entries.firstOrNull { it.providerId == providerId }?.label ?: providerId
    }
}

data class ProviderSettings(
    val apiKey: String = "",
    val model: String = "",
    val baseUrl: String = "",
    /** The user attests this provider keeps no data. Enables it in ZDR-only mode. */
    val zeroRetention: Boolean = false,
)

data class WinnowSettings(
    val provider: ProviderKind = ProviderKind.ON_DEVICE,
    val providers: Map<ProviderKind, ProviderSettings> = emptyMap(),
    val privacy: PrivacyPolicy = PrivacyPolicy(),
    /** Only on-device and zero-retention providers may see message content. */
    val zdrOnly: Boolean = false,
    /** Ask the carrier to confirm delivery of each SMS. Off by default, as in Messages. */
    val deliveryReports: Boolean = false,
    /** The user said "Not now" to reviewing older conversations. */
    val reviewPromptDismissed: Boolean = false,
    /** First-run onboarding finished or skipped. */
    val onboarded: Boolean = false,
    val categoryActions: Map<Category, Action> = Category.entries.associateWith { it.defaultAction },
) {
    fun settingsFor(kind: ProviderKind): ProviderSettings = providers[kind] ?: ProviderSettings()

    val effectivePrivacy: PrivacyPolicy
        get() = if (zdrOnly) {
            privacy.copy(allowedDataHandling = setOf(DataHandling.ON_DEVICE, DataHandling.REMOTE_ZERO_RETENTION))
        } else {
            privacy
        }

    val actionPolicy: ActionPolicy get() = ActionPolicy(byCategory = categoryActions)
}

private val Context.settingsStore by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context, private val secrets: SecretBox) {
    private val store = context.applicationContext.settingsStore

    val settings: Flow<WinnowSettings> = store.data.map { it.read() }

    suspend fun current(): WinnowSettings = settings.first()

    suspend fun update(transform: (WinnowSettings) -> WinnowSettings) {
        store.edit { prefs -> prefs.write(transform(prefs.read())) }
    }

    private fun Preferences.read(): WinnowSettings {
        val defaults = WinnowSettings()
        val privacy = PrivacyPolicy(
            classifyContacts = this[CLASSIFY_CONTACTS] ?: defaults.privacy.classifyContacts,
            classifyKnownConversations = this[CLASSIFY_KNOWN] ?: defaults.privacy.classifyKnownConversations,
            classifyVerificationCodes = this[CLASSIFY_CODES] ?: defaults.privacy.classifyVerificationCodes,
            shareSenderAddress = this[SHARE_SENDER] ?: defaults.privacy.shareSenderAddress,
            redaction = RedactionPolicy(
                maskDigitRuns = this[MASK_DIGITS] ?: true,
                maskEmails = this[MASK_EMAILS] ?: true,
                stripUrlPaths = this[STRIP_URLS] ?: true,
            ),
        )
        return WinnowSettings(
            provider = this[PROVIDER]?.let { runCatching { ProviderKind.valueOf(it) }.getOrNull() } ?: defaults.provider,
            providers = ProviderKind.entries.associateWith { kind ->
                ProviderSettings(
                    apiKey = this[apiKeyKey(kind)]?.let(secrets::open).orEmpty(),
                    model = this[modelKey(kind)].orEmpty(),
                    baseUrl = this[baseUrlKey(kind)].orEmpty(),
                    zeroRetention = this[zdrKey(kind)] ?: false,
                )
            },
            privacy = privacy,
            zdrOnly = this[ZDR_ONLY] ?: false,
            deliveryReports = this[DELIVERY_REPORTS] ?: false,
            reviewPromptDismissed = this[REVIEW_DISMISSED] ?: false,
            onboarded = this[ONBOARDED] ?: false,
            categoryActions = Category.entries.associateWith { c ->
                this[actionKey(c)]?.let { runCatching { Action.valueOf(it) }.getOrNull() } ?: c.defaultAction
            },
        )
    }

    private fun MutablePreferences.write(s: WinnowSettings) {
        this[PROVIDER] = s.provider.name
        s.providers.forEach { (kind, p) ->
            if (p.apiKey.isBlank()) remove(apiKeyKey(kind)) else this[apiKeyKey(kind)] = secrets.seal(p.apiKey)
            this[modelKey(kind)] = p.model
            this[baseUrlKey(kind)] = p.baseUrl
            this[zdrKey(kind)] = p.zeroRetention
        }
        this[CLASSIFY_CONTACTS] = s.privacy.classifyContacts
        this[CLASSIFY_KNOWN] = s.privacy.classifyKnownConversations
        this[CLASSIFY_CODES] = s.privacy.classifyVerificationCodes
        this[SHARE_SENDER] = s.privacy.shareSenderAddress
        this[MASK_DIGITS] = s.privacy.redaction.maskDigitRuns
        this[MASK_EMAILS] = s.privacy.redaction.maskEmails
        this[STRIP_URLS] = s.privacy.redaction.stripUrlPaths
        this[ZDR_ONLY] = s.zdrOnly
        this[DELIVERY_REPORTS] = s.deliveryReports
        this[REVIEW_DISMISSED] = s.reviewPromptDismissed
        this[ONBOARDED] = s.onboarded
        s.categoryActions.forEach { (c, a) -> this[actionKey(c)] = a.name }
    }

    private companion object {
        val PROVIDER = stringPreferencesKey("provider")
        val CLASSIFY_CONTACTS = booleanPreferencesKey("privacy.classify_contacts")
        val CLASSIFY_KNOWN = booleanPreferencesKey("privacy.classify_known_conversations")
        val CLASSIFY_CODES = booleanPreferencesKey("privacy.classify_codes")
        val SHARE_SENDER = booleanPreferencesKey("privacy.share_sender")
        val MASK_DIGITS = booleanPreferencesKey("privacy.mask_digits")
        val MASK_EMAILS = booleanPreferencesKey("privacy.mask_emails")
        val STRIP_URLS = booleanPreferencesKey("privacy.strip_urls")
        val ZDR_ONLY = booleanPreferencesKey("privacy.zdr_only")
        val DELIVERY_REPORTS = booleanPreferencesKey("sms.delivery_reports")
        val REVIEW_DISMISSED = booleanPreferencesKey("review.prompt_dismissed")
        val ONBOARDED = booleanPreferencesKey("onboarding.done")

        fun apiKeyKey(k: ProviderKind) = stringPreferencesKey("provider.${k.name}.api_key_sealed")
        fun modelKey(k: ProviderKind) = stringPreferencesKey("provider.${k.name}.model")
        fun baseUrlKey(k: ProviderKind) = stringPreferencesKey("provider.${k.name}.base_url")
        fun zdrKey(k: ProviderKind) = booleanPreferencesKey("provider.${k.name}.zdr")
        fun actionKey(c: Category) = stringPreferencesKey("action.${c.key}")
    }
}
