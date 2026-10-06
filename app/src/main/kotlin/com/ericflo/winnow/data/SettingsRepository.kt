package com.ericflo.winnow.data

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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
        "Nothing leaves the phone. Winnow's built-in model decides, and only filters when it's sure.",
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

/** What swiping a conversation in the inbox does. */
enum class SwipeChoice(val label: String) {
    ARCHIVE("Archive"),
    /** Opens the label sheet for the conversation (see LabelSheet). */
    LABEL("Label"),
    DELETE("Delete"),
    READ("Mark read or unread"),
    PIN("Pin or unpin"),
    NONE("Nothing"),
}

/** Light or dark, or whatever the phone is set to. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Message text size: a multiplier on the default, set in Settings or by pinching a conversation. */
object TextScale {
    const val MIN = 0.8f
    const val MAX = 1.8f
    fun clamp(value: Float): Float = if (value.isNaN()) 1f else value.coerceIn(MIN, MAX)

    /** [clamp], snapping to the default when it's close, so the default is easy to get back to. */
    fun settle(value: Float): Float = clamp(value).let { if (kotlin.math.abs(it - 1f) < 0.05f) 1f else it }
}

/** The replies Winnow starts with; the user edits them in Settings. */
val DEFAULT_QUICK_REPLIES = listOf("On my way", "Running a few minutes late", "Can't talk now, I'll call you later", "Sounds good!", "Thanks!")

data class WinnowSettings(
    val provider: ProviderKind = ProviderKind.ON_DEVICE,
    val providers: Map<ProviderKind, ProviderSettings> = emptyMap(),
    val privacy: PrivacyPolicy = PrivacyPolicy(),
    /** Only on-device and zero-retention providers may see message content. */
    val zdrOnly: Boolean = false,
    /** Let the on-device model decide, without asking the provider, when it's very sure. */
    val decideOnPhoneWhenSure: Boolean = false,
    /**
     * Keep teaching the on-device model from the classifier service's answers as texts arrive,
     * as a backlog run does: so it goes on learning while the service decides. On by default.
     */
    val learnFromProvider: Boolean = true,
    /**
     * How much one of the classifier service's labels counts against one of the user's (1), as
     * the on-device model is fitted: 0 leaves them out, 1 counts them the same. The user can try
     * others and pick one in Winnow's model (see Evaluator).
     */
    val providerWeight: Double = com.ericflo.winnow.classify.Learner.PROVIDER_WEIGHT,
    /**
     * How much the user's labels of a sender count with the model's answer for that sender's
     * next texts (see SenderMemory): 0 is off. Enough labels of a sender, one way, decide
     * whatever the strength; it shapes how fewer nudge. Changeable in the Lab.
     */
    val senderMemory: Double = com.ericflo.winnow.classifier.local.SenderMemory.DEFAULT_STRENGTH,
    /** How the personal layer is fitted: passes, step and pull toward zero (see Personalizer); the user can change them in the Lab. */
    val personalEpochs: Int = com.ericflo.winnow.classifier.local.Personalizer.EPOCHS,
    val personalStep: Double = com.ericflo.winnow.classifier.local.Personalizer.LEARNING_RATE,
    val personalL2: Double = com.ericflo.winnow.classifier.local.Personalizer.L2,
    /** A model the user trained in the Lab, in use in place of the shipped one (see ModelLab); this phone's only. */
    val labModel: String? = null,
    /** Train that model again after each Train round and backlog run, so it learns what they taught. */
    val labAutoRetrain: Boolean = true,
    /** Ask for a fingerprint, face or the screen lock to open Winnow. */
    val appLock: Boolean = false,
    /** Delete one-time codes from services a day after they arrive. Off unless the user turns it on. */
    val deleteOldCodes: Boolean = false,
    /** Move filtered conversations untouched for a month to Recently deleted (see FilteredCleaner). */
    val clearOldFiltered: Boolean = false,
    /** Seconds to hold a sent message so it can be undone; 0 sends at once. */
    val undoSendSeconds: Int = 0,
    /** Keep new-message notifications off the lock screen. */
    val hideOnLockScreen: Boolean = false,
    /** Ask the carrier to confirm delivery of each SMS. Off by default, as in Messages. */
    val deliveryReports: Boolean = false,
    /** Send plain stand-ins for characters that would make a text take more parts (see SimpleCharacters). */
    val simpleCharacters: Boolean = false,
    /** Reply reminders: unanswered questions back at the top of the inbox (see Nudge). On by default, as in Messages. */
    val nudges: Boolean = true,
    /** Fetch picture messages as they arrive; otherwise they wait for a tap. */
    val autoDownloadMms: Boolean = true,
    /** The same while roaming, where data can cost extra. Off by default, as in Messages. */
    val autoDownloadMmsRoaming: Boolean = false,
    /** Enter sends instead of starting a new line (Shift+Enter still does), for hardware keyboards. */
    val enterToSend: Boolean = false,
    /** Save received photos and videos to the phone's gallery: from contacts and people the user has texted, in the inbox. */
    val autoSaveMedia: Boolean = false,
    /** Canned replies: in the composer's attach menu, and as one-tap choices on notifications. */
    val quickReplies: List<String> = DEFAULT_QUICK_REPLIES,
    /** Words and phrases that send a stranger's text to Filtered, decided on the phone. */
    val filteredPhrases: List<String> = emptyList(),
    /** An evening notification saying how many texts were filtered or silenced that day. */
    val dailySummary: Boolean = false,
    /** "Not now" on the group conversations' card asking for the Phone numbers permission. This phone's only. */
    val ownNumberCardDismissed: Boolean = false,
    /** When the last daily summary went out (or had nothing to say); 0 for never. This phone's only. */
    val dailySummaryLastAt: Long = 0,
    /** Fetch link previews for texts from people you know. Off by default: fetching tells the site your IP. */
    val linkPreviews: Boolean = false,
    /** Reply ideas from Android's on-device text classifier, in the composer and on notifications. */
    val suggestedReplies: Boolean = true,
    /** A folder (SAF tree URI) for weekly automatic backups; this phone's only, never backed up. */
    val autoBackupFolder: String? = null,
    val autoBackupLast: Long = 0,
    /** Why the last automatic backup failed, until one succeeds. */
    val autoBackupError: String? = null,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** Swiping an inbox conversation toward the end (right, in left-to-right languages). */
    val swipeRight: SwipeChoice = SwipeChoice.ARCHIVE,
    // Labeling is how Winnow learns; a swipe makes it as quick as archiving.
    val swipeLeft: SwipeChoice = SwipeChoice.LABEL,
    /** See [TextScale]. */
    val textScale: Float = 1f,
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
            decideOnPhoneWhenSure = this[DECIDE_ON_PHONE] ?: false,
            learnFromProvider = this[LEARN_FROM_PROVIDER] ?: true,
            providerWeight = (this[PROVIDER_WEIGHT] ?: com.ericflo.winnow.classify.Learner.PROVIDER_WEIGHT).coerceIn(0.0, 1.0),
            senderMemory = (this[SENDER_MEMORY] ?: com.ericflo.winnow.classifier.local.SenderMemory.DEFAULT_STRENGTH).coerceIn(0.0, 4.0),
            personalEpochs = (this[PERSONAL_EPOCHS] ?: com.ericflo.winnow.classifier.local.Personalizer.EPOCHS).coerceIn(1, 500),
            personalStep = (this[PERSONAL_STEP] ?: com.ericflo.winnow.classifier.local.Personalizer.LEARNING_RATE).coerceIn(1e-4, 10.0),
            personalL2 = (this[PERSONAL_L2] ?: com.ericflo.winnow.classifier.local.Personalizer.L2).coerceIn(0.0, 1.0),
            labModel = this[LAB_MODEL],
            labAutoRetrain = this[LAB_AUTO_RETRAIN] ?: true,
            appLock = this[APP_LOCK] ?: false,
            hideOnLockScreen = this[HIDE_ON_LOCK_SCREEN] ?: false,
            undoSendSeconds = this[UNDO_SEND_SECONDS] ?: 0,
            deleteOldCodes = this[DELETE_OLD_CODES] ?: false,
            clearOldFiltered = this[CLEAR_OLD_FILTERED] ?: false,
            deliveryReports = this[DELIVERY_REPORTS] ?: false,
            simpleCharacters = this[SIMPLE_CHARACTERS] ?: false,
            nudges = this[NUDGES] ?: true,
            autoDownloadMms = this[AUTO_DOWNLOAD_MMS] ?: true,
            autoDownloadMmsRoaming = this[AUTO_DOWNLOAD_MMS_ROAMING] ?: false,
            linkPreviews = this[LINK_PREVIEWS] ?: false,
            suggestedReplies = this[SUGGESTED_REPLIES] ?: true,
            enterToSend = this[ENTER_TO_SEND] ?: false,
            autoSaveMedia = this[AUTO_SAVE_MEDIA] ?: false,
            quickReplies = this[QUICK_REPLIES]?.let { runCatching { Json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() } ?: DEFAULT_QUICK_REPLIES,
            dailySummary = this[DAILY_SUMMARY] ?: false,
            ownNumberCardDismissed = this[OWN_NUMBER_CARD_DISMISSED] ?: false,
            dailySummaryLastAt = this[DAILY_SUMMARY_LAST_AT] ?: 0,
            filteredPhrases = this[FILTERED_PHRASES]?.let { runCatching { Json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() }.orEmpty(),
            autoBackupFolder = this[AUTO_BACKUP_FOLDER],
            autoBackupLast = this[AUTO_BACKUP_LAST] ?: 0,
            autoBackupError = this[AUTO_BACKUP_ERROR],
            theme = this[THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: defaults.theme,
            textScale = TextScale.clamp(this[TEXT_SCALE] ?: 1f),
            swipeRight = this[SWIPE_RIGHT]?.let { runCatching { SwipeChoice.valueOf(it) }.getOrNull() } ?: defaults.swipeRight,
            // Stored under a new key since Label became the default: every settings write stored
            // the old default, Archive, so a choice of Archive can't be told from never choosing.
            // Any other choice was the user's own, and is kept.
            swipeLeft = (this[SWIPE_LEFT_V2] ?: this[SWIPE_LEFT]?.takeIf { it != SwipeChoice.ARCHIVE.name })
                ?.let { runCatching { SwipeChoice.valueOf(it) }.getOrNull() } ?: defaults.swipeLeft,
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
        this[DECIDE_ON_PHONE] = s.decideOnPhoneWhenSure
        this[LEARN_FROM_PROVIDER] = s.learnFromProvider
        this[PROVIDER_WEIGHT] = s.providerWeight
        this[SENDER_MEMORY] = s.senderMemory
        this[PERSONAL_EPOCHS] = s.personalEpochs
        this[PERSONAL_STEP] = s.personalStep
        this[PERSONAL_L2] = s.personalL2
        s.labModel?.let { this[LAB_MODEL] = it } ?: remove(LAB_MODEL)
        this[LAB_AUTO_RETRAIN] = s.labAutoRetrain
        this[APP_LOCK] = s.appLock
        this[HIDE_ON_LOCK_SCREEN] = s.hideOnLockScreen
        this[UNDO_SEND_SECONDS] = s.undoSendSeconds
        this[DELETE_OLD_CODES] = s.deleteOldCodes
        this[CLEAR_OLD_FILTERED] = s.clearOldFiltered
        this[DELIVERY_REPORTS] = s.deliveryReports
        this[SIMPLE_CHARACTERS] = s.simpleCharacters
        this[NUDGES] = s.nudges
        this[AUTO_DOWNLOAD_MMS] = s.autoDownloadMms
        this[AUTO_DOWNLOAD_MMS_ROAMING] = s.autoDownloadMmsRoaming
        this[LINK_PREVIEWS] = s.linkPreviews
        this[SUGGESTED_REPLIES] = s.suggestedReplies
        this[ENTER_TO_SEND] = s.enterToSend
        this[AUTO_SAVE_MEDIA] = s.autoSaveMedia
        this[QUICK_REPLIES] = Json.encodeToString(ListSerializer(String.serializer()), s.quickReplies)
        this[FILTERED_PHRASES] = Json.encodeToString(ListSerializer(String.serializer()), s.filteredPhrases)
        this[DAILY_SUMMARY] = s.dailySummary
        this[OWN_NUMBER_CARD_DISMISSED] = s.ownNumberCardDismissed
        this[DAILY_SUMMARY_LAST_AT] = s.dailySummaryLastAt
        s.autoBackupFolder?.let { this[AUTO_BACKUP_FOLDER] = it } ?: remove(AUTO_BACKUP_FOLDER)
        this[AUTO_BACKUP_LAST] = s.autoBackupLast
        s.autoBackupError?.let { this[AUTO_BACKUP_ERROR] = it } ?: remove(AUTO_BACKUP_ERROR)
        this[THEME] = s.theme.name
        this[TEXT_SCALE] = TextScale.clamp(s.textScale)
        this[SWIPE_RIGHT] = s.swipeRight.name
        this[SWIPE_LEFT_V2] = s.swipeLeft.name
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
        val DECIDE_ON_PHONE = booleanPreferencesKey("privacy.decide_on_phone_when_sure")
        val LEARN_FROM_PROVIDER = booleanPreferencesKey("model.learn_from_provider")
        val PROVIDER_WEIGHT = doublePreferencesKey("model.provider_weight")
        val SENDER_MEMORY = doublePreferencesKey("model.sender_memory")
        val PERSONAL_EPOCHS = intPreferencesKey("model.personal_epochs")
        val PERSONAL_STEP = doublePreferencesKey("model.personal_step")
        val PERSONAL_L2 = doublePreferencesKey("model.personal_l2")
        val LAB_MODEL = stringPreferencesKey("model.lab_model")
        val LAB_AUTO_RETRAIN = booleanPreferencesKey("model.lab_auto_retrain")
        val APP_LOCK = booleanPreferencesKey("security.app_lock")
        val HIDE_ON_LOCK_SCREEN = booleanPreferencesKey("security.hide_on_lock_screen")
        val UNDO_SEND_SECONDS = intPreferencesKey("compose.undo_send_seconds")
        val DELETE_OLD_CODES = booleanPreferencesKey("messages.delete_old_codes")
        val CLEAR_OLD_FILTERED = booleanPreferencesKey("filter.clear_old")
        val DELIVERY_REPORTS = booleanPreferencesKey("sms.delivery_reports")
        val SIMPLE_CHARACTERS = booleanPreferencesKey("sms.simple_characters")
        val NUDGES = booleanPreferencesKey("inbox.nudges")
        val AUTO_DOWNLOAD_MMS = booleanPreferencesKey("mms.auto_download")
        val AUTO_DOWNLOAD_MMS_ROAMING = booleanPreferencesKey("mms.auto_download_roaming")
        val LINK_PREVIEWS = booleanPreferencesKey("messages.link_previews")
        val SUGGESTED_REPLIES = booleanPreferencesKey("compose.suggested_replies")
        val ENTER_TO_SEND = booleanPreferencesKey("compose.enter_to_send")
        val QUICK_REPLIES = stringPreferencesKey("compose.quick_replies")
        val FILTERED_PHRASES = stringPreferencesKey("filter.phrases")
        val DAILY_SUMMARY = booleanPreferencesKey("filter.daily_summary")
        val OWN_NUMBER_CARD_DISMISSED = booleanPreferencesKey("ui.own_number_card_dismissed")
        val DAILY_SUMMARY_LAST_AT = longPreferencesKey("filter.daily_summary_last_at")
        val AUTO_SAVE_MEDIA = booleanPreferencesKey("mms.auto_save_media")
        val AUTO_BACKUP_FOLDER = stringPreferencesKey("backup.auto_folder")
        val AUTO_BACKUP_LAST = longPreferencesKey("backup.auto_last")
        val AUTO_BACKUP_ERROR = stringPreferencesKey("backup.auto_error")
        val THEME = stringPreferencesKey("display.theme")
        val TEXT_SCALE = floatPreferencesKey("display.text_scale")
        val SWIPE_RIGHT = stringPreferencesKey("inbox.swipe_right")
        val SWIPE_LEFT = stringPreferencesKey("inbox.swipe_left")
        val SWIPE_LEFT_V2 = stringPreferencesKey("inbox.swipe_left.v2")
        val REVIEW_DISMISSED = booleanPreferencesKey("review.prompt_dismissed")
        val ONBOARDED = booleanPreferencesKey("onboarding.done")

        fun apiKeyKey(k: ProviderKind) = stringPreferencesKey("provider.${k.name}.api_key_sealed")
        fun modelKey(k: ProviderKind) = stringPreferencesKey("provider.${k.name}.model")
        fun baseUrlKey(k: ProviderKind) = stringPreferencesKey("provider.${k.name}.base_url")
        fun zdrKey(k: ProviderKind) = booleanPreferencesKey("provider.${k.name}.zdr")
        fun actionKey(c: Category) = stringPreferencesKey("action.${c.key}")
    }
}
