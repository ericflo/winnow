package com.ericflo.winnow.classify

import com.ericflo.winnow.classifier.DataHandling
import com.ericflo.winnow.classifier.DecisionProvider
import com.ericflo.winnow.classifier.http.HttpTransport
import com.ericflo.winnow.classifier.local.OnDeviceClassifier
import com.ericflo.winnow.classifier.message.FilteredPhrases
import com.ericflo.winnow.classifier.message.MessageClassifier
import com.ericflo.winnow.classifier.providers.ChatCompletionsConfig
import com.ericflo.winnow.classifier.providers.ChatCompletionsProvider
import com.ericflo.winnow.classifier.providers.SystemOneConfig
import com.ericflo.winnow.classifier.providers.SystemOneProvider
import com.ericflo.winnow.data.ProviderKind
import com.ericflo.winnow.data.WinnowSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** The only place that maps user settings to concrete providers. Add a provider here and in [ProviderKind]. */
class ClassifierFactory(
    private val http: HttpTransport,
    /** The on-device model, with what the user has taught it. */
    private val onDevice: suspend () -> OnDeviceClassifier,
    /** Why no text may leave the phone right now, if so (see MessageClassifier's keepOnPhone). */
    private val keepOnPhone: () -> String? = { null },
) {

    suspend fun create(
        settings: WinnowSettings,
        timeoutMillis: Long = 5_000,
        /** The user's labeled texts to send as examples (a backlog run only; see MessageClassifier). */
        examples: Map<com.ericflo.winnow.classifier.message.Category, List<String>> = emptyMap(),
        /** A sample the user typed to try the classifier: no one's message, so never held back. */
        sample: Boolean = false,
        /**
         * The service's own answers, for measuring it: no on-device model, so neither its being
         * sure nor the user's labels of a sender stand in for the service.
         */
        serviceOnly: Boolean = false,
    ): MessageClassifier =
        MessageClassifier(
            providers = listOfNotNull(provider(settings)),
            privacy = settings.effectivePrivacy,
            actions = settings.actionPolicy,
            timeoutMillis = timeoutMillis,
            // Without its learned adjustments the model still works; without the model, rules still do.
            onDevice = if (serviceOnly) null else runCatching { onDevice() }.getOrNull(),
            decideOnDeviceAbove = SURE.takeIf { settings.decideOnPhoneWhenSure },
            filteredPhrases = FilteredPhrases(settings.filteredPhrases),
            examples = examples,
            keepOnPhone = if (sample) null else keepOnPhone(),
        )

    /** Null when the choice is on-device only or the chosen provider isn't configured yet. */
    fun provider(settings: WinnowSettings): DecisionProvider? {
        val kind = settings.provider
        val p = settings.settingsFor(kind)
        val handling = if (p.zeroRetention) DataHandling.REMOTE_ZERO_RETENTION else DataHandling.REMOTE
        val key = p.apiKey.ifBlank { null }
        val model = p.model.ifBlank { kind.defaultModel }
        val baseUrl = p.baseUrl.ifBlank { null }
        return when (kind) {
            ProviderKind.ON_DEVICE -> null
            ProviderKind.TYPESAFE_JEV -> key?.let {
                SystemOneProvider(SystemOneConfig.typeSafe(it, model).copy(dataHandling = handling), http)
            }
            ProviderKind.OPENROUTER_JEV -> key?.let {
                // OpenRouter is told, not just trusted: zero retention routes to ZDR endpoints only.
                SystemOneProvider(SystemOneConfig.openRouter(it, model).copy(dataHandling = handling, zeroRetentionRouting = p.zeroRetention), http)
            }
            ProviderKind.SYSTEM_ONE -> baseUrl?.let {
                SystemOneProvider(SystemOneConfig.custom(it, model, key, handling), http)
            }
            ProviderKind.CHAT_COMPLETIONS -> baseUrl?.takeIf { model.isNotBlank() }?.let {
                // The same routing request where the endpoint is OpenRouter's; other servers might reject it.
                val extra = if (p.zeroRetention && it.contains("openrouter.ai")) {
                    buildJsonObject { putJsonObject("provider") { put("zdr", true) } }
                } else {
                    JsonObject(emptyMap())
                }
                ChatCompletionsProvider(ChatCompletionsConfig(baseUrl = it, model = model, apiKey = key, dataHandling = handling, extraBody = extra), http)
            }
        }
    }

    companion object {
        /** How sure the on-device model must be to decide without the provider. See classifier/training/REPORT.md. */
        private const val SURE = 0.95

        /** Why texts stay on the phone while Winnow can't read contacts: a contact's would look like a stranger's. */
        const val CONTACTS_HIDDEN = "Winnow can't see your contacts, so no text is sent anywhere"
    }
}
