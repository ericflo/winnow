package com.ericflo.winnow.data.db

import androidx.room.AutoMigration
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
        CorrectionEntity::class, StarredEntity::class,
    ],
    version = 6,
    autoMigrations = [
        AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
    ],
)
abstract class WinnowDatabase : RoomDatabase() {
    abstract fun verdicts(): VerdictDao
    abstract fun conversationStates(): ConversationStateDao
    abstract fun scheduled(): ScheduledMessageDao
    abstract fun corrections(): CorrectionDao
    abstract fun starred(): StarredDao
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

    @Query("DELETE FROM starred WHERE threadId IN (:threadIds)")
    suspend fun deleteForThreads(threadIds: Collection<Long>)
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
)

@Dao
interface CorrectionDao {
    @Query("SELECT * FROM corrections ORDER BY createdAt")
    suspend fun all(): List<CorrectionEntity>

    @Query("SELECT COUNT(*) FROM corrections")
    fun observeCount(): Flow<Int>

    @Insert
    suspend fun insert(correction: CorrectionEntity)

    @Query("DELETE FROM corrections WHERE threadId = :threadId")
    suspend fun deleteForThread(threadId: Long)

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

    @Query("SELECT * FROM scheduled_messages WHERE id = :id")
    suspend fun get(id: Long): ScheduledMessageEntity?

    @Insert
    suspend fun insert(message: ScheduledMessageEntity): Long

    @Query("DELETE FROM scheduled_messages WHERE id = :id")
    suspend fun delete(id: Long)
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
)

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
) {
    fun toStored(providerNames: (String) -> String) = StoredVerdict(
        category = category?.let(Category::fromKey),
        confidence = confidence,
        action = Action.valueOf(action),
        source = when (sourceKind) {
            "provider" -> "Classified by ${providerNames(sourceDetail)}"
            "heuristic" -> "Guessed on this phone ($sourceDetail)"
            "local" -> if (sourceDetail.isBlank()) "Decided on this phone" else "Decided on this phone: $sourceDetail"
            else -> sourceDetail
        },
        userAction = userAction?.let(Action::valueOf),
    )

    companion object {
        fun from(messageKey: String, threadId: Long, address: String, verdict: Verdict, now: Long): VerdictEntity {
            val (kind, detail, model) = when (val s = verdict.source) {
                is VerdictSource.Rule -> Triple("rule", s.reason, null)
                is VerdictSource.Provider -> Triple("provider", s.providerId, s.model)
                is VerdictSource.Heuristic -> Triple("heuristic", s.reason, null)
                is VerdictSource.OnDevice -> Triple("local", s.reasons.joinToString(", "), s.model)
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
            )
        }
    }
}

@Entity(tableName = "sender_rules")
data class SenderRuleEntity(
    /** [com.ericflo.winnow.data.normalizeAddress] form. */
    @PrimaryKey val address: String,
    /** A [com.ericflo.winnow.classifier.message.SenderRule] name. */
    val rule: String,
    val createdAt: Long,
)

@Dao
interface VerdictDao {
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

    /** Which of [keys] already have a verdict. Callers keep [keys] under SQLite's 999-variable limit. */
    @Query("SELECT messageKey FROM verdicts WHERE messageKey IN (:keys)")
    suspend fun existingKeys(keys: List<String>): List<String>

    @Query("DELETE FROM verdicts WHERE threadId IN (:threadIds)")
    suspend fun deleteForThreads(threadIds: Collection<Long>)

    @Query("DELETE FROM verdicts WHERE messageKey = :messageKey")
    suspend fun deleteForMessage(messageKey: String)

    @Query("SELECT rule FROM sender_rules WHERE address = :address")
    suspend fun senderRule(address: String): String?

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

    @Upsert
    suspend fun upsert(state: ConversationStateEntity)

    @Query("DELETE FROM conversation_state WHERE threadId IN (:threadIds)")
    suspend fun delete(threadIds: Collection<Long>)
}
