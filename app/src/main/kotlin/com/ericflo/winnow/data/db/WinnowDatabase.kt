package com.ericflo.winnow.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
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
@Database(entities = [VerdictEntity::class, SenderRuleEntity::class], version = 1)
abstract class WinnowDatabase : RoomDatabase() {
    abstract fun verdicts(): VerdictDao
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

    @Query("SELECT rule FROM sender_rules WHERE address = :address")
    suspend fun senderRule(address: String): String?

    @Upsert
    suspend fun upsertSenderRule(rule: SenderRuleEntity)
}
