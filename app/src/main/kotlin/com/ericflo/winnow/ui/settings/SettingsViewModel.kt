package com.ericflo.winnow.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ericflo.winnow.AppContainer
import com.ericflo.winnow.classifier.message.FilteredPhrases
import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classifier.message.InboundMessage
import com.ericflo.winnow.classifier.message.MessageClassifier
import com.ericflo.winnow.classifier.message.PrivacyPolicy
import com.ericflo.winnow.classifier.message.Verdict
import com.ericflo.winnow.data.ProviderKind
import com.ericflo.winnow.data.ProviderSettings
import com.ericflo.winnow.data.TextScale
import com.ericflo.winnow.data.ThemeMode
import com.ericflo.winnow.data.WinnowSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import com.ericflo.winnow.data.SwipeChoice
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

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
        viewModelScope.launch { runCatching { container.learner.forget() } }
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

    fun unlockBackup(uri: Uri, password: CharArray) = container.backups.unlock(uri, password)

    val backupPasswordSet: StateFlow<Boolean> = container.backupPassword.isSet

    /**
     * Slow (about a second): run in the app's scope, so leaving Settings doesn't stop it, while
     * the dialog waits for the answer. Whether it was kept.
     */
    suspend fun setBackupPassword(password: CharArray): Boolean = container.appScope.async {
        try {
            runCatching { container.backupPassword.set(password) }
                .onFailure { android.util.Log.w("WinnowSettings", "Couldn't set the backup password", it) }
                .isSuccess
        } finally {
            password.fill(' ')
        }
    }.await()

    /** Whether new backups are unprotected now. */
    suspend fun removeBackupPassword(): Boolean = container.appScope.async {
        runCatching { container.backupPassword.clear() }
            .onFailure { android.util.Log.w("WinnowSettings", "Couldn't turn off the backup password", it) }
            .isSuccess
    }.await()

    fun importSmsBackupRestore(uri: Uri) = container.backups.importSmsBackupRestore(uri)

    fun exportSmsBackupRestore(uri: Uri) = container.backups.exportSmsBackupRestore(uri)

    /** The automatic-backup folder's name, for display; null when off. */
    val autoBackupFolderName: StateFlow<String?> = container.settings.settings
        .map { it.autoBackupFolder }
        .distinctUntilChanged()
        .map { folder -> folder?.let { withContext(Dispatchers.IO) { container.autoBackup.folderName(it) } ?: "the chosen folder" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _autoBackupRunning = MutableStateFlow(false)
    val autoBackupRunning: StateFlow<Boolean> = _autoBackupRunning.asStateFlow()

    private val _autoBackupNotice = MutableStateFlow<String?>(null)
    /** The outcome of "Back up now", until the user dismisses it. */
    val autoBackupNotice: StateFlow<String?> = _autoBackupNotice.asStateFlow()

    fun enableAutoBackup(folder: Uri) {
        viewModelScope.launch { runCatching { container.autoBackup.enable(folder) }.onFailure { _autoBackupNotice.value = "Couldn't use that folder" } }
    }

    fun disableAutoBackup() {
        viewModelScope.launch { container.autoBackup.disable() }
    }

    fun backUpNow() {
        if (_autoBackupRunning.value) return
        _autoBackupRunning.value = true
        // The app's scope: leaving Settings mustn't cancel a backup halfway.
        container.appScope.launch {
            _autoBackupNotice.value = runCatching { container.autoBackup.runNow() }.getOrElse { "Couldn't back up: ${it.message ?: "unknown error"}" }
            _autoBackupRunning.value = false
        }
    }

    fun dismissAutoBackupNotice() {
        _autoBackupNotice.value = null
    }

    fun startReview() = container.historyReviewer.start()

    fun selectProvider(kind: ProviderKind) = update { it.copy(provider = kind) }

    fun saveProvider(kind: ProviderKind, value: ProviderSettings) = update { it.copy(providers = it.providers + (kind to value)) }

    fun setPrivacy(transform: (PrivacyPolicy) -> PrivacyPolicy) = update { it.copy(privacy = transform(it.privacy)) }

    fun setZdrOnly(value: Boolean) = update { it.copy(zdrOnly = value) }

    fun setDecideOnPhoneWhenSure(value: Boolean) = update { it.copy(decideOnPhoneWhenSure = value) }

    fun setAppLock(value: Boolean) = update { it.copy(appLock = value) }

    fun setHideOnLockScreen(value: Boolean) = update { it.copy(hideOnLockScreen = value) }

    fun setUndoSend(seconds: Int) = update { it.copy(undoSendSeconds = seconds) }

    fun setClearOldFiltered(value: Boolean) {
        // Saved, then run (which reads the setting); in the app's scope, so leaving Settings
        // doesn't stop a first long run partway.
        container.appScope.launch {
            container.settings.update { it.copy(clearOldFiltered = value) }
            if (value) runCatching { container.filteredCleaner.clean(force = true) }
        }
    }

    fun setDeleteOldCodes(value: Boolean) {
        // Saved, then tidied right away rather than at the next launch (the cleaner reads the setting).
        container.appScope.launch {
            container.settings.update { it.copy(deleteOldCodes = value) }
            if (value) runCatching { container.codeCleaner.clean() }
        }
    }

    /** App lock needs a screen lock to check against. */
    fun deviceIsSecure(): Boolean = container.deviceIsSecure()

    fun setDeliveryReports(value: Boolean) = update { it.copy(deliveryReports = value) }
    fun setSimpleCharacters(value: Boolean) = update { it.copy(simpleCharacters = value) }

    fun setAutoDownloadMms(value: Boolean) = update { it.copy(autoDownloadMms = value) }

    fun setLinkPreviews(value: Boolean) = update { it.copy(linkPreviews = value) }

    fun setSuggestedReplies(value: Boolean) = update { it.copy(suggestedReplies = value) }

    fun setEnterToSend(value: Boolean) = update { it.copy(enterToSend = value) }

    fun setAutoSaveMedia(value: Boolean) = update { it.copy(autoSaveMedia = value) }

    fun setQuickReplies(value: List<String>) = update { it.copy(quickReplies = value.map(String::trim).filter(String::isNotEmpty).distinct()) }

    fun setDailySummary(value: Boolean) {
        // The app's scope: leaving Settings straight away mustn't skip arming it.
        container.appScope.launch {
            container.settings.update { it.copy(dailySummary = value) }
            container.dailySummary.rearm()
        }
    }

    fun setFilteredPhrases(value: List<String>) = update {
        it.copy(filteredPhrases = value.map(FilteredPhrases::normalize).filter(String::isNotEmpty).distinctBy(String::lowercase))
    }

    fun setAutoDownloadMmsRoaming(value: Boolean) = update { it.copy(autoDownloadMmsRoaming = value) }

    fun setTheme(value: ThemeMode) = update { it.copy(theme = value) }

    fun setTextScale(value: Float) = update { it.copy(textScale = TextScale.clamp(value)) }

    fun setSwipe(right: Boolean, value: SwipeChoice) = update { if (right) it.copy(swipeRight = value) else it.copy(swipeLeft = value) }

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
