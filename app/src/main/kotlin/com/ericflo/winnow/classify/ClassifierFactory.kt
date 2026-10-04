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

/** The only place that maps user settings to concrete providers. Add a provider here and in [ProviderKind]. */
class ClassifierFactory(
    private val http: HttpTransport,
    /** The on-device model, with what the user has taught it. */
    private val onDevice: suspend () -> OnDeviceClassifier,
) {

    suspend fun create(settings: WinnowSettings, timeoutMillis: Long = 5_000): MessageClassifier =
        MessageClassifier(
            providers = listOfNotNull(provider(settings)),
            privacy = settings.effectivePrivacy,
            actions = settings.actionPolicy,
            timeoutMillis = timeoutMillis,
            // Without its learned adjustments the model still works; without the model, rules still do.
            onDevice = runCatching { onDevice() }.getOrNull(),
            decideOnDeviceAbove = SURE.takeIf { settings.decideOnPhoneWhenSure },
            filteredPhrases = FilteredPhrases(settings.filteredPhrases),
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
                SystemOneProvider(SystemOneConfig.openRouter(it, model).copy(dataHandling = handling), http)
            }
            ProviderKind.SYSTEM_ONE -> baseUrl?.let {
                SystemOneProvider(SystemOneConfig.custom(it, model, key, handling), http)
            }
            ProviderKind.CHAT_COMPLETIONS -> baseUrl?.takeIf { model.isNotBlank() }?.let {
                ChatCompletionsProvider(ChatCompletionsConfig(baseUrl = it, model = model, apiKey = key, dataHandling = handling), http)
            }
        }
    }

    private companion object {
        /** How sure the on-device model must be to decide without the provider. See classifier/training/REPORT.md. */
        const val SURE = 0.95
    }
}
