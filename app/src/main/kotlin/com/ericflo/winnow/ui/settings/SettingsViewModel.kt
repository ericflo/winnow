package com.ericflo.winnow.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ericflo.winnow.AppContainer
import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classifier.message.InboundMessage
import com.ericflo.winnow.classifier.message.MessageClassifier
import com.ericflo.winnow.classifier.message.PrivacyPolicy
import com.ericflo.winnow.classifier.message.Verdict
import com.ericflo.winnow.data.ProviderKind
import com.ericflo.winnow.data.ProviderSettings
import com.ericflo.winnow.data.WinnowSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

sealed interface TrialState {
    data object Idle : TrialState
    data object Running : TrialState

    /** [payload] is the exact JSON state sent (or attempted) to the provider; null when nothing left the phone. */
    data class Done(val verdict: Verdict, val payload: String?) : TrialState
}

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val settings: StateFlow<WinnowSettings?> =
        container.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _isDefault = MutableStateFlow(container.isDefaultSmsApp())
    val isDefault: StateFlow<Boolean> = _isDefault.asStateFlow()

    private val _trial = MutableStateFlow<TrialState>(TrialState.Idle)
    val trial: StateFlow<TrialState> = _trial.asStateFlow()

    /** Reviewing older, never-classified conversations. */
    val review = container.historyReviewer.status

    val backup = container.backups.status

    /** How many corrections the on-device model has learned from. */
    val learned: StateFlow<Int> = container.learner.count.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun forgetLearning() {
        viewModelScope.launch { container.learner.forget() }
    }

    private val _canBackUpMessages = MutableStateFlow(container.backups.canReadMessages())
    val canBackUpMessages: StateFlow<Boolean> = _canBackUpMessages.asStateFlow()

    fun refresh() {
        _isDefault.value = container.isDefaultSmsApp()
        _canBackUpMessages.value = container.backups.canReadMessages()
        container.historyReviewer.refresh()
    }

    fun exportBackup(uri: Uri) = container.backups.export(uri)

    fun openBackup(uri: Uri) = container.backups.open(uri)

    fun restoreBackup(uri: Uri, includeSettings: Boolean) = container.backups.restore(uri, includeSettings)

    fun dismissBackup() = container.backups.dismiss()

    fun startReview() = container.historyReviewer.start()

    fun selectProvider(kind: ProviderKind) = update { it.copy(provider = kind) }

    fun saveProvider(kind: ProviderKind, value: ProviderSettings) = update { it.copy(providers = it.providers + (kind to value)) }

    fun setPrivacy(transform: (PrivacyPolicy) -> PrivacyPolicy) = update { it.copy(privacy = transform(it.privacy)) }

    fun setZdrOnly(value: Boolean) = update { it.copy(zdrOnly = value) }

    fun setDecideOnPhoneWhenSure(value: Boolean) = update { it.copy(decideOnPhoneWhenSure = value) }

    fun setDeliveryReports(value: Boolean) = update { it.copy(deliveryReports = value) }

    fun setAction(category: Category, action: Action) = update { it.copy(categoryActions = it.categoryActions + (category to action)) }

    /** Runs the real pipeline, with the saved settings, on a message typed into Settings. */
    fun tryClassify(sender: String, body: String) {
        viewModelScope.launch {
            _trial.value = TrialState.Running
            val current = container.settings.current()
            val message = InboundMessage(sender = sender.ifBlank { "+15555550123" }, body = body)
            val verdict = container.classifiers.create(current).classify(message)
            val payload = if (verdict.providerContacted) {
                prettyJson.encodeToString(JsonElement.serializer(), MessageClassifier.buildRequest(message, current.effectivePrivacy).state)
            } else {
                null
            }
            _trial.value = TrialState.Done(verdict, payload)
        }
    }

    private fun update(transform: (WinnowSettings) -> WinnowSettings) {
        viewModelScope.launch { container.settings.update(transform) }
    }

    private companion object {
        val prettyJson = Json { prettyPrint = true }
    }
}
