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
    entities = [VerdictEntity::class, SenderRuleEntity::class, ConversationStateEntity::class, ScheduledMessageEntity::class],
    version = 3,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class WinnowDatabase : RoomDatabase() {
    abstract fun verdicts(): VerdictDao
    abstract fun conversationStates(): ConversationStateDao
    abstract fun scheduled(): ScheduledMessageDao
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
    /** `rule`, `provider` or `heuristic`. */
    val sourceKind: String,
    /** The rule's reason, the provider id, or the heuristic's fallback reason. */
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

    @Upsert
    suspend fun upsert(verdict: VerdictEntity)

    @Query("UPDATE verdicts SET userAction = :userAction WHERE threadId = :threadId")
    suspend fun setUserAction(threadId: Long, userAction: String?)

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

    @Query("SELECT * FROM conversation_state WHERE threadId = :threadId")
    suspend fun get(threadId: Long): ConversationStateEntity?

    @Upsert
    suspend fun upsert(state: ConversationStateEntity)

    @Query("DELETE FROM conversation_state WHERE threadId IN (:threadIds)")
    suspend fun delete(threadIds: Collection<Long>)
}
