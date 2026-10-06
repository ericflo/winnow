package com.ericflo.winnow.data.db

import androidx.room.AutoMigration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.room.migration.AutoMigrationSpec
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classifier.message.Verdict
import com.ericflo.winnow.classifier.message.VerdictSource
import com.ericflo.winnow.data.StoredVerdict
import kotlinx.coroutines.flow.Flow

/**
 * Messages themselves live in the system Telephony provider. Winnow only stores what it
 * adds: a verdict per message and the user's per-sender rules.
 */
@Database(
    entities = [
        VerdictEntity::class, SenderRuleEntity::class, ConversationStateEntity::class, ScheduledMessageEntity::class,
        CorrectionEntity::class, StarredEntity::class, ReminderEntity::class,
        RunEntity::class, RunAnswerEntity::class, ModelFitEntity::class, EvalEntity::class, EvalItemEntity::class,
    ],
    version = 18,
    autoMigrations = [
        AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7), AutoMigration(from = 7, to = 8), AutoMigration(from = 8, to = 9),
        AutoMigration(from = 9, to = 10, spec = WinnowDatabase.EverythingSummarized::class),
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 11, to = 12),
        AutoMigration(from = 12, to = 13, spec = WinnowDatabase.RemindersFromMe::class),
        AutoMigration(from = 13, to = 14),
        AutoMigration(from = 14, to = 15, spec = WinnowDatabase.ArrivalsMarked::class),
        // 15 to 16 adds CorrectionEntity.source: every existing row is the user's.
        AutoMigration(from = 15, to = 16),
        // 16 to 17: six categories. Phishing and scam fold into spam; labels the user gave before
        // (but political ones, which stand) are marked to recheck; a provider's fine-grained answer.
        AutoMigration(from = 16, to = 17, spec = WinnowDatabase.SixCategories::class),
        // 17 to 18: what decided each text and what the on-device model thought of it; backlog runs
        // and each answer in them; every fit of the on-device model; evaluations and their items.
        AutoMigration(from = 17, to = 18, spec = WinnowDatabase.RunsKept::class),
    ],
)
abstract class WinnowDatabase : RoomDatabase() {
    // Specs take the connection, not a SupportSQLiteDatabase: Room hands the latter only to
    // Android's own SQLite, so a spec written that way would be skipped where migrations are tested.

    /**
     * 9 to 10 adds VerdictEntity.summarized. What came before is taken as reported, or the first
     * summary after the update would repeat the last one's.
     */
    class EverythingSummarized : AutoMigrationSpec {
        override fun onPostMigrate(connection: SQLiteConnection) {
            connection.execSQL("UPDATE verdicts SET summarized = 1")
        }
    }

    /** 12 to 13 adds ReminderEntity.fromMe: a reminder with no sender was on the user's own message. */
    class RemindersFromMe : AutoMigrationSpec {
        override fun onPostMigrate(connection: SQLiteConnection) {
            connection.execSQL("UPDATE reminders SET fromMe = 1 WHERE sender IS NULL")
        }
    }

    /**
     * 14 to 15: which verdicts Winnow made as the text arrived. Before this was recorded, a
     * review of older conversations, a correction's own row and a restored verdict were all
     * written as already summarized, and a live decision wasn't (until a daily summary, which is
     * off unless turned on), so that's the best there is: a live one that was summarized is left
     * out, which undercounts, never the other way.
     */
    class ArrivalsMarked : AutoMigrationSpec {
        override fun onPostMigrate(connection: SQLiteConnection) {
            connection.execSQL("UPDATE verdicts SET atArrival = 1 WHERE summarized = 0")
        }
    }

    /**
     * 16 to 17, the move to six categories: everything that said phishing or "likely scam" says
     * spam, in verdicts, labels and what the model learned; and the user's labels from before
     * (all but political, which they said stand) come back to Train Winnow to be confirmed or
     * changed.
     */
    class SixCategories : AutoMigrationSpec {
        override fun onPostMigrate(connection: SQLiteConnection) {
            connection.execSQL("UPDATE verdicts SET category = 'spam' WHERE category IN ('phishing', 'scam')")
            connection.execSQL("UPDATE verdicts SET userCategory = 'spam' WHERE userCategory IN ('phishing', 'scam')")
            connection.execSQL("UPDATE corrections SET label = 'spam' WHERE label IN ('phishing', 'scam')")
            connection.execSQL("UPDATE verdicts SET recheck = 1 WHERE userCategory IS NOT NULL AND userCategory != 'political'")
        }
    }

    /**
     * 17 to 18: runs are kept from now on. A classifier service's labels from before came from a
     * backlog run that wasn't, so they're marked as such (0), and never taken for ones learned as
     * texts arrived (null), which didn't happen before.
     */
    class RunsKept : AutoMigrationSpec {
        override fun onPostMigrate(connection: SQLiteConnection) {
            connection.execSQL("UPDATE corrections SET runId = 0 WHERE source = 'provider'")
        }
    }

    abstract fun verdicts(): VerdictDao
    abstract fun runs(): RunDao
    abstract fun fits(): ModelFitDao
    abstract fun evals(): EvalDao
    abstract fun conversationStates(): ConversationStateDao
    abstract fun scheduled(): ScheduledMessageDao
    abstract fun corrections(): CorrectionDao
    abstract fun starred(): StarredDao
    abstract fun reminders(): ReminderDao
}

/**
 * "Remind me" on a message: a notification at [remindAt] that brings it back. What it says is
 * kept here too, so the reminder still makes sense if the message is gone by then.
 */
@Entity(tableName = "reminders")
data class ReminderEntity(
    /** The message's `sms:<id>` / `mms:<id>` key. */
    @PrimaryKey val messageKey: String,
    val threadId: Long,
    /** The conversation's people, joined (see joinAddresses). */
    val recipients: String,
    val remindAt: Long,
    /** The message's words (or what it carries), for the notification. */
    val preview: String,
    /** Who wrote it, if known (null for the user's own, or a group sender the store didn't name). */
    val sender: String? = null,
    /** When the message was sent or received: a reused message id isn't this message. 0 when unknown. */
    @ColumnInfo(defaultValue = "0") val messageAt: Long = 0,
    /** The user's own message. */
    @ColumnInfo(defaultValue = "0") val fromMe: Boolean = false,
)

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders WHERE threadId = :threadId")
    fun observeForThread(threadId: Long): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders")
    suspend fun all(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE messageKey = :key")
    suspend fun get(key: String): ReminderEntity?

    @Upsert
    suspend fun upsert(reminder: ReminderEntity)

    @Query("DELETE FROM reminders WHERE messageKey = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM reminders WHERE threadId IN (:threadIds)")
    suspend fun deleteForThreads(threadIds: Collection<Long>)
}

/** A message the user starred, by its `sms:<id>` / `mms:<id>` key. */
@Entity(tableName = "starred")
data class StarredEntity(@PrimaryKey val messageKey: String, val threadId: Long, val starredAt: Long)

@Dao
interface StarredDao {
    @Query("SELECT * FROM starred ORDER BY starredAt DESC")
    fun observeAll(): Flow<List<StarredEntity>>

    @Query("SELECT messageKey FROM starred WHERE threadId = :threadId")
    fun observeKeys(threadId: Long): Flow<List<String>>

    @Query("SELECT * FROM starred")
    suspend fun all(): List<StarredEntity>

    @Upsert
    suspend fun star(starred: StarredEntity)

    @Query("DELETE FROM starred WHERE messageKey = :messageKey")
    suspend fun unstar(messageKey: String)

    @Query("DELETE FROM starred WHERE messageKey IN (:keys)")
    suspend fun unstarAll(keys: Collection<String>)

    @Query("DELETE FROM starred WHERE threadId IN (:threadIds)")
    suspend fun deleteForThreads(threadIds: Collection<Long>)

    @Query("SELECT messageKey FROM starred WHERE threadId = :threadId")
    suspend fun keysForThread(threadId: Long): List<String>
}

/**
 * Something the user taught the on-device model: a corrected message's feature buckets (not
 * its text) and the category it should have been.
 */
@Entity(tableName = "corrections")
data class CorrectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The conversation it came from, so correcting it again replaces it. Null when restored from a backup. */
    val threadId: Long?,
    /** Comma-joined bucket indices. */
    val buckets: String,
    /** A [Category] key. */
    val label: String,
    /** Buckets only mean something to the featurizer version that produced them. */
    val featurizerVersion: Int,
    val createdAt: Long,
    /**
     * A label the user gave this one message (see Labeler): set for labels, null for the
     * one-per-conversation corrections "Not spam" and "Filter sender" make, and for rows
     * restored from a backup.
     */
    val messageKey: String? = null,
    /**
     * Who taught it: [SOURCE_USER], or [SOURCE_PROVIDER] for a label a classifier service gave
     * the backlog (see Bootstrap). The user's count for more, replace a provider's on the same
     * message, and are the only ones the accuracy screen scores.
     */
    @ColumnInfo(defaultValue = SOURCE_USER) val source: String = SOURCE_USER,
    /**
     * For a classifier service's label: the backlog run that gave it ([RunEntity.id]; 0 for one
     * from before runs were kept), or null when it was learned from a text as it arrived.
     */
    val runId: Long? = null,
) {
    val fromProvider: Boolean get() = source == SOURCE_PROVIDER

    companion object {
        const val SOURCE_USER = "user"
        const val SOURCE_PROVIDER = "provider"
    }
}

@Dao
interface CorrectionDao {
    @Query("SELECT * FROM corrections ORDER BY createdAt")
    suspend fun all(): List<CorrectionEntity>

    @Query("SELECT COUNT(*) FROM corrections")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM corrections")
    fun observeAll(): Flow<List<CorrectionEntity>>

    /** Messages that already taught the model something, by anyone: a backlog run skips them. */
    @Query("SELECT messageKey FROM corrections WHERE messageKey IS NOT NULL")
    suspend fun taughtKeys(): List<String>

    @Query("SELECT COUNT(*) FROM corrections WHERE source = 'provider'")
    fun observeProviderCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM corrections WHERE source != 'provider'")
    fun observeUserCount(): Flow<Int>

    /** Forgets every label a classifier service gave, leaving the user's. */
    @Query("DELETE FROM corrections WHERE source = 'provider'")
    suspend fun deleteProviderLabels()

    @Query("SELECT * FROM corrections WHERE source = 'provider' AND threadId = :threadId")
    suspend fun providerForThread(threadId: Long): List<CorrectionEntity>

    @Insert
    suspend fun insert(correction: CorrectionEntity)

    @Insert
    suspend fun insertAll(corrections: List<CorrectionEntity>)

    /** A conversation's correction, replaced when it's corrected again. Its labels stay. */
    @Query("DELETE FROM corrections WHERE threadId = :threadId AND messageKey IS NULL")
    suspend fun deleteForThread(threadId: Long)

    @Query("SELECT * FROM corrections WHERE messageKey IN (:keys)")
    suspend fun forMessages(keys: Collection<String>): List<CorrectionEntity>

    @Query("DELETE FROM corrections WHERE messageKey IN (:keys)")
    suspend fun deleteForMessages(keys: Collection<String>)

    /**
     * Corrections a restore brought back for these exact features: a backup keeps no message
     * keys, so a label restored from one can only be recognized by what it taught.
     */
    @Query("SELECT * FROM corrections WHERE messageKey IS NULL AND threadId IS NULL AND buckets IN (:buckets)")
    suspend fun restoredFor(buckets: Collection<String>): List<CorrectionEntity>

    @Query("DELETE FROM corrections WHERE id IN (:ids)")
    suspend fun deleteIds(ids: Collection<Long>)

    /** Moves a label from key [from] to its message's key here, [to] (a restore gave it a new one). */
    @Query("UPDATE corrections SET messageKey = :to, threadId = :threadId WHERE messageKey = :from")
    suspend fun relink(from: String, to: String, threadId: Long)

    /** Labels still under a restore's placeholder keys: their messages didn't come back, so they keep teaching unlinked. */
    @Query("UPDATE corrections SET messageKey = NULL, threadId = NULL WHERE messageKey LIKE :prefix || '%'")
    suspend fun unlinkPrefixed(prefix: String)

    /** A conversation's own correction ("Not spam", "Filter sender"), if it has one. */
    @Query("SELECT * FROM corrections WHERE threadId = :threadId AND messageKey IS NULL")
    suspend fun forThread(threadId: Long): List<CorrectionEntity>

    @Query("DELETE FROM corrections")
    suspend fun deleteAll()
}

/** A text waiting for its send time. Lives here, not in the SMS store, until it's sent. */
@Entity(tableName = "scheduled_messages")
data class ScheduledMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val threadId: Long,
    /** Comma-joined addresses. */
    val recipients: String,
    val body: String,
    val sendAt: Long,
    /** The SIM to send from on dual-SIM phones; null for the default. */
    val subscriptionId: Int? = null,
)

@Dao
interface ScheduledMessageDao {
    @Query("SELECT * FROM scheduled_messages WHERE threadId = :threadId ORDER BY sendAt")
    fun observeForThread(threadId: Long): Flow<List<ScheduledMessageEntity>>

    @Query("SELECT * FROM scheduled_messages")
    suspend fun all(): List<ScheduledMessageEntity>

    @Query("SELECT * FROM scheduled_messages ORDER BY sendAt")
    fun observeAll(): Flow<List<ScheduledMessageEntity>>

    @Query("SELECT * FROM scheduled_messages WHERE id = :id")
    suspend fun get(id: Long): ScheduledMessageEntity?

    @Insert
    suspend fun insert(message: ScheduledMessageEntity): Long

    @Query("DELETE FROM scheduled_messages WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE scheduled_messages SET sendAt = :sendAt WHERE id = :id")
    suspend fun setSendAt(id: Long, sendAt: Long)
}

/** Winnow-only state for a thread. The system SMS store has no place for it. */
@Entity(tableName = "conversation_state")
data class ConversationStateEntity(
    @PrimaryKey val threadId: Long,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val muted: Boolean = false,
    val draft: String? = null,
    /** The SIM the user picked for this conversation on a dual-SIM phone. */
    val subscriptionId: Int? = null,
    /** A name the user gave a group conversation; MMS itself has no group names. */
    val title: String? = null,
    /** When a timed mute ends (epoch millis); null with [muted] means until turned off. */
    val mutedUntil: Long? = null,
    /** The composer's attachments, as [com.ericflo.winnow.data.DraftAttachments] encodes them. */
    val draftAttachments: String? = null,
    /** The composer's MMS subject: null for no subject field, "" for an empty one. */
    val draftSubject: String? = null,
) {
    /** Muted right now: a timed mute counts only until it ends. */
    fun isMuted(now: Long = System.currentTimeMillis()): Boolean = muted && (mutedUntil == null || now < mutedUntil)
}

@Entity(tableName = "verdicts")
data class VerdictEntity(
    /** `sms:<_id>` or `mms:<_id>` in the Telephony provider. */
    @PrimaryKey val messageKey: String,
    val threadId: Long,
    val address: String,
    val category: String?,
    val confidence: Double,
    val action: String,
    /** `rule`, `provider`, `local` (the on-device model) or `heuristic`. */
    val sourceKind: String,
    /** The rule's reason, the provider id, the on-device model's reasons, or the heuristic's fallback reason. */
    val sourceDetail: String,
    val model: String?,
    val costUsd: Double,
    val decidedAt: Long,
    val userAction: String? = null,
    /**
     * Already in a daily summary, or never news for one: a restored verdict, a review of older
     * conversations, a correction's own row. A fresh classification starts false.
     */
    @ColumnInfo(defaultValue = "0") val summarized: Boolean = false,
    /** A [Category] key the user labeled this message with (see Labeler); it outranks [category]. */
    val userCategory: String? = null,
    /**
     * Winnow decided this as the text arrived, so [action] is what really happened on the
     * phone then. False for a review of older texts, a label, a correction's own row and a
     * restore, none of which ever buzzed (or didn't) because of Winnow.
     */
    @ColumnInfo(defaultValue = "0") val atArrival: Boolean = false,
    /** A label from before the six categories, for the user to confirm or change in Train Winnow. */
    @ColumnInfo(defaultValue = "0") val recheck: Boolean = false,
    /** A provider's fine-grained answer ([com.ericflo.winnow.classifier.message.Subcategories]), when it gave one. */
    val subcategory: String? = null,
    /**
     * What the on-device model thought of the text when it was decided, whoever decided: a
     * [Category] key and how sure. Null where a rule decided before any model was asked.
     */
    val localCategory: String? = null,
    val localConfidence: Double? = null,
    /** Which on-device model that was, and which fit of it (see OnDeviceClassifier.version). */
    val localModel: String? = null,
    /**
     * Why the classifier service didn't decide, when the on-device model did instead of it: it
     * timed out, failed, wasn't allowed by the privacy settings, or the model was sure enough
     * ([com.ericflo.winnow.classifier.message.VerdictSource.OnDevice.SURE]). Null otherwise.
     */
    val fallbackReason: String? = null,
    /** How long the classifier service took to answer, when it decided. */
    val latencyMillis: Long? = null,
    /** The run that decided it ([RunEntity.id]): a backlog run's or a review's. Null as texts arrive. */
    val runId: Long? = null,
    /** The user's labeled texts sent with the question as examples (a backlog run's); 0 when none were. */
    @ColumnInfo(defaultValue = "0") val promptExamples: Int = 0,
) {
    fun toStored(providerNames: (String) -> String) = StoredVerdict(
        // The user's label wins: every badge, chip and list then follows it.
        category = (userCategory ?: category)?.let(Category::fromKey),
        confidence = confidence,
        action = Action.valueOf(action),
        source = if (userCategory != null) "Labeled by you" else when (sourceKind) {
            "provider" -> "Classified by ${providerNames(sourceDetail)}"
            "heuristic" -> "Guessed on this phone ($sourceDetail)"
            "local" -> if (sourceDetail.isBlank()) "Decided on this phone" else "Decided on this phone: $sourceDetail"
            else -> sourceDetail
        },
        userAction = userAction?.let(Action::valueOf),
        labeledByUser = userCategory != null,
    )

    companion object {
        fun from(messageKey: String, threadId: Long, address: String, verdict: Verdict, now: Long): VerdictEntity {
            val (kind, detail, model) = when (val s = verdict.source) {
                is VerdictSource.Rule -> Triple("rule", s.reason, null)
                is VerdictSource.Provider -> Triple("provider", s.providerId, s.model)
                is VerdictSource.Heuristic -> Triple("heuristic", s.reason, null)
                is VerdictSource.OnDevice -> Triple("local", s.reasons.joinToString(", "), s.model)
            }
            val fallback = when (val s = verdict.source) {
                is VerdictSource.OnDevice -> s.fallbackReason
                is VerdictSource.Heuristic -> s.reason
                else -> null
            }
            return VerdictEntity(
                messageKey = messageKey,
                threadId = threadId,
                address = address,
                category = verdict.category?.key,
                confidence = verdict.confidence,
                action = verdict.action.name,
                sourceKind = kind,
                sourceDetail = detail,
                model = model,
                costUsd = verdict.costUsd,
                decidedAt = now,
                subcategory = verdict.subcategory,
                localCategory = verdict.onDevice?.category?.key,
                localConfidence = verdict.onDevice?.confidence,
                localModel = verdict.onDevice?.model,
                fallbackReason = fallback,
                latencyMillis = verdict.latencyMillis,
                promptExamples = verdict.promptExamples,
            )
        }

        const val KIND_RULE = "rule"
        const val KIND_PROVIDER = "provider"
        const val KIND_LOCAL = "local"
        const val KIND_HEURISTIC = "heuristic"
    }
}

/** A conversation and a provider's fine-grained answer about it. */
data class ThreadDetail(val threadId: Long, val subcategory: String?)

@Entity(tableName = "sender_rules")
data class SenderRuleEntity(
    /** [com.ericflo.winnow.data.normalizeAddress] form. */
    @PrimaryKey val address: String,
    /** A [com.ericflo.winnow.classifier.message.SenderRule] name. */
    val rule: String,
    val createdAt: Long,
)

/** One of the user's labels and who sent the message it's on (see VerdictDao.senderLabels). */
data class SenderLabel(val address: String, val userCategory: String)

@Dao
interface VerdictDao {
    /** The user's labels with who sent each labeled message, for remembering what they say about senders (see SenderMemory). */
    @Query("SELECT address, userCategory FROM verdicts WHERE userCategory IS NOT NULL AND address != ''")
    suspend fun senderLabels(): List<SenderLabel>

    @Query("SELECT * FROM verdicts")
    fun observeAll(): Flow<List<VerdictEntity>>

    @Query("SELECT * FROM verdicts")
    suspend fun all(): List<VerdictEntity>

    @Query("SELECT * FROM sender_rules")
    suspend fun allSenderRules(): List<SenderRuleEntity>

    @Upsert
    suspend fun upsert(verdict: VerdictEntity)

    @Query("UPDATE verdicts SET userAction = :userAction WHERE threadId = :threadId")
    suspend fun setUserAction(threadId: Long, userAction: String?)

    @Query("SELECT * FROM verdicts WHERE messageKey IN (:keys)")
    suspend fun forKeys(keys: Collection<String>): List<VerdictEntity>

    @Query("SELECT * FROM verdicts WHERE threadId = :threadId")
    suspend fun forThread(threadId: Long): List<VerdictEntity>

    @Query("UPDATE verdicts SET userAction = :userAction, userCategory = :userCategory WHERE messageKey = :messageKey")
    suspend fun setUserState(messageKey: String, userAction: String?, userCategory: String?)

    @Query("UPDATE verdicts SET userCategory = NULL WHERE messageKey IN (:keys)")
    suspend fun clearLabels(keys: Collection<String>)

    /** How many messages the user has labeled. */
    @Query("SELECT COUNT(*) FROM verdicts WHERE userCategory IS NOT NULL")
    fun observeLabelCount(): Flow<Int>

    /**
     * Conversations the user has already judged: labeled, or corrected ("Not spam", "Filter
     * sender"), and not waiting to be rechecked.
     */
    @Query(
        "SELECT DISTINCT threadId FROM verdicts WHERE (userCategory IS NOT NULL OR userAction IS NOT NULL) " +
            "AND threadId NOT IN (SELECT threadId FROM verdicts WHERE recheck = 1)",
    )
    suspend fun judgedThreads(): List<Long>

    /** A conversation's labels from before the six categories, by message, with what they said. */
    @Query("SELECT * FROM verdicts WHERE recheck = 1")
    suspend fun toRecheck(): List<VerdictEntity>

    @Query("SELECT * FROM verdicts WHERE threadId = :threadId AND recheck = 1")
    suspend fun toRecheckIn(threadId: Long): List<VerdictEntity>

    @Query("SELECT DISTINCT threadId FROM verdicts WHERE recheck = 1")
    suspend fun recheckThreads(): List<Long>

    @Query("UPDATE verdicts SET recheck = 0 WHERE threadId = :threadId")
    suspend fun clearRecheck(threadId: Long)

    @Query("SELECT COUNT(DISTINCT threadId) FROM verdicts WHERE recheck = 1")
    fun observeRecheckCount(): Flow<Int>

    /** Each conversation's newest fine-grained answer from a provider, for Train Winnow's rows. */
    @Query("SELECT threadId, subcategory FROM verdicts WHERE subcategory IS NOT NULL GROUP BY threadId HAVING decidedAt = MAX(decidedAt)")
    suspend fun providerDetails(): List<ThreadDetail>

    @Query("DELETE FROM verdicts WHERE messageKey IN (:keys)")
    suspend fun deleteForMessages(keys: Collection<String>)

    @Query("SELECT * FROM verdicts WHERE threadId = :threadId ORDER BY decidedAt DESC LIMIT 1")
    suspend fun latestForThread(threadId: Long): VerdictEntity?

    @Query("SELECT userAction FROM verdicts WHERE threadId = :threadId AND userAction IS NOT NULL LIMIT 1")
    suspend fun userAction(threadId: Long): String?

    /** Which of [keys] already have a verdict. Callers keep [keys] under SQLite's 999-variable limit. */
    @Query("SELECT messageKey FROM verdicts WHERE messageKey IN (:keys)")
    suspend fun existingKeys(keys: List<String>): List<String>

    @Query("DELETE FROM verdicts WHERE threadId IN (:threadIds)")
    suspend fun deleteForThreads(threadIds: Collection<Long>)

    @Query("DELETE FROM verdicts WHERE messageKey = :messageKey")
    suspend fun deleteForMessage(messageKey: String)

    @Query("SELECT messageKey FROM verdicts WHERE threadId = :threadId")
    suspend fun keysForThread(threadId: Long): List<String>

    /** [threadId]'s messages whose verdict, as it stands (the user's correction, else Winnow's), is to filter. */
    @Query("SELECT messageKey FROM verdicts WHERE threadId = :threadId AND COALESCE(userAction, action) = 'FILTER'")
    suspend fun filteredKeysForThread(threadId: Long): List<String>

    @Query("SELECT * FROM verdicts WHERE messageKey = :messageKey")
    suspend fun forKey(messageKey: String): VerdictEntity?

    /** For each of [keys] not yet in a daily summary: its key and the action that stood (the user's correction, else Winnow's). */
    @Query("SELECT messageKey, COALESCE(userAction, action) AS stood FROM verdicts WHERE messageKey IN (:keys) AND summarized = 0")
    suspend fun unsummarized(keys: List<String>): List<KeyAction>

    @Query("UPDATE verdicts SET summarized = 1 WHERE messageKey IN (:keys)")
    suspend fun markSummarized(keys: List<String>)

    @Query("SELECT rule FROM sender_rules WHERE address = :address")
    suspend fun senderRule(address: String): String?

    @Query("SELECT * FROM sender_rules WHERE address = :address")
    suspend fun senderRuleEntity(address: String): SenderRuleEntity?

    @Query("SELECT * FROM sender_rules ORDER BY createdAt DESC")
    fun observeSenderRules(): Flow<List<SenderRuleEntity>>

    @Upsert
    suspend fun upsertSenderRule(rule: SenderRuleEntity)

    @Query("DELETE FROM sender_rules WHERE address = :address")
    suspend fun deleteSenderRule(address: String)
}

@Dao
interface ConversationStateDao {
    @Query("SELECT * FROM conversation_state")
    fun observeAll(): Flow<List<ConversationStateEntity>>

    @Query("SELECT * FROM conversation_state")
    suspend fun all(): List<ConversationStateEntity>

    @Query("SELECT * FROM conversation_state WHERE threadId = :threadId")
    suspend fun get(threadId: Long): ConversationStateEntity?

    @Query("SELECT * FROM conversation_state WHERE threadId IN (:threadIds)")
    suspend fun getAll(threadIds: List<Long>): List<ConversationStateEntity>

    @Upsert
    suspend fun upsert(state: ConversationStateEntity)

    /** In one transaction: one change for every list watching, not one per conversation. */
    @Upsert
    suspend fun upsertAll(states: List<ConversationStateEntity>)

    @Query("DELETE FROM conversation_state WHERE threadId IN (:threadIds)")
    suspend fun delete(threadIds: Collection<Long>)
}

/** A verdict's message and the action that stood for it. */
data class KeyAction(val messageKey: String, val stood: String)
