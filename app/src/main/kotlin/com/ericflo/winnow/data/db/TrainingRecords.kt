package com.ericflo.winnow.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A run of a classifier service over many texts at once, which the user started: a backlog run
 * (see Bootstrap) or a redo of one. Kept for good, with each answer (see [RunAnswerEntity]), so
 * what it did can be looked at whenever, not only while Winnow happens to still be running.
 */
@Entity(tableName = "runs")
data class RunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** [KIND_BACKLOG] or [KIND_REDO]. */
    val kind: String,
    /** The service as the user knows it ("Jev (TypeSafe)"). */
    val provider: String,
    /** The model that answered, once one has. */
    val model: String? = null,
    val startedAt: Long,
    /** When progress was last saved: a run with no [finishedAt] that isn't running ended with Winnow. */
    val updatedAt: Long,
    val finishedAt: Long? = null,
    /** Texts it set out to send, from [conversations] conversations. */
    val planned: Int,
    val conversations: Int,
    /** Texts it has gone through so far, whatever became of them. */
    @ColumnInfo(defaultValue = "0") val done: Int = 0,
    /** Answered and taught to the on-device model. */
    val labeled: Int = 0,
    /** Answered, but too unsure to teach. */
    val unsure: Int = 0,
    /** Kept on the phone by a privacy rule when it came to send them. */
    val kept: Int = 0,
    /** No answer: offered again next run. */
    val failed: Int = 0,
    val costUsd: Double = 0.0,
    /** The user's labeled texts sent with each question as examples of how they sort. */
    val examples: Int = 0,
    val stopped: Boolean = false,
    val error: String? = null,
    /** When the user saw how it went (its results, or its card in Train Winnow); null until then. */
    val seenAt: Long? = null,
) {
    val finished: Boolean get() = finishedAt != null

    companion object {
        const val KIND_BACKLOG = "backlog"
        const val KIND_REDO = "redo"
    }
}

/** One answer a run got: what the service said of a text, and what the on-device model said just before. */
@Entity(tableName = "run_answers", primaryKeys = ["runId", "messageKey"])
data class RunAnswerEntity(
    val runId: Long,
    val messageKey: String,
    val threadId: Long,
    val address: String,
    /** The service's answer: a category key, its fine-grained kind, and how sure it was. */
    val category: String,
    val subcategory: String?,
    val confidence: Double,
    /** Sure enough to teach the on-device model (see Bootstrap.MIN_CONFIDENCE). */
    val taught: Boolean,
    /** What the on-device model made of the text just before this answer taught it. */
    val modelCategory: String?,
    val modelConfidence: Double?,
    /** The service's earlier answer about this text, on a redo; null the first time it was asked. */
    val previous: String?,
    val answeredAt: Long,
    /** How long it took to answer. */
    val latencyMillis: Long? = null,
    /** The user had written to the sender when it was asked: the model reads that too. */
    @ColumnInfo(defaultValue = "0") val repliedTo: Boolean = false,
)

@Dao
interface RunDao {
    @Insert
    suspend fun insert(run: RunEntity): Long

    @Update
    suspend fun update(run: RunEntity)

    @Query("SELECT * FROM runs WHERE id = :id")
    suspend fun get(id: Long): RunEntity?

    @Query("SELECT * FROM runs WHERE id = :id")
    fun observe(id: Long): Flow<RunEntity?>

    @Query("SELECT * FROM runs ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<RunEntity>>

    @Query("SELECT * FROM runs ORDER BY startedAt DESC LIMIT 1")
    fun observeLatest(): Flow<RunEntity?>

    @Query("UPDATE runs SET seenAt = :at WHERE id = :id AND seenAt IS NULL")
    suspend fun markSeen(id: Long, at: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnswers(answers: List<RunAnswerEntity>)

    @Query("SELECT * FROM run_answers WHERE runId = :runId ORDER BY answeredAt")
    suspend fun answers(runId: Long): List<RunAnswerEntity>

    @Query("SELECT * FROM run_answers WHERE runId = :runId ORDER BY answeredAt")
    fun observeAnswers(runId: Long): Flow<List<RunAnswerEntity>>

    /** Every answer any run got, newest first: what the service said of the user's texts. */
    @Query("SELECT * FROM run_answers ORDER BY answeredAt DESC")
    suspend fun allAnswers(): List<RunAnswerEntity>
}

/**
 * One fit of the on-device model: what it learned from and how it was fitted. Kept so its
 * history can be shown (how it has grown), and named by [fit], the name every verdict it made
 * records (see OnDeviceClassifier.version), so a verdict can be traced to what taught it.
 */
@Entity(tableName = "model_fits")
data class ModelFitEntity(
    /** The fit's name (see Learner.fitName). */
    @PrimaryKey val fit: String,
    val fittedAt: Long,
    /** The user's labels on texts, and their corrections of whole conversations ("Not spam", "Filter sender"). */
    val userLabels: Int,
    val corrections: Int,
    /** A classifier service's labels: from backlog runs, and learned from texts as they arrived. */
    val providerLabels: Int,
    val providerLive: Int,
    /** Feature buckets it adjusted. */
    val buckets: Int,
    /** How long fitting took on this phone; 0 when it was loaded as kept. */
    val millis: Long,
    /** How much one of a service's labels counted against one of the user's (1), and the fit's settings. */
    val providerWeight: Double,
    val epochs: Int,
    val l2: Double,
    /** Its learned adjustments are kept (see ModelSnapshots), so it can be evaluated and used again later. */
    @ColumnInfo(defaultValue = "0") val kept: Boolean = false,
    /** A name the user gave it. A named fit is never pruned. */
    val name: String? = null,
) {
    val labels: Int get() = userLabels + corrections + providerLabels + providerLive
}

@Dao
interface ModelFitDao {
    @Upsert
    suspend fun upsert(fit: ModelFitEntity)

    @Query("SELECT * FROM model_fits WHERE fit = :fit")
    suspend fun get(fit: String): ModelFitEntity?

    @Query("SELECT * FROM model_fits ORDER BY fittedAt DESC")
    fun observeAll(): Flow<List<ModelFitEntity>>

    @Query("SELECT * FROM model_fits ORDER BY fittedAt DESC")
    suspend fun all(): List<ModelFitEntity>

    @Query("UPDATE model_fits SET kept = :kept WHERE fit = :fit")
    suspend fun setKept(fit: String, kept: Boolean)

    @Query("UPDATE model_fits SET name = :name WHERE fit = :fit")
    suspend fun setName(fit: String, name: String?)

    /** Unkept, unnamed fits beyond the newest [keep]: a label a minute makes a lot of them. */
    @Query(
        "DELETE FROM model_fits WHERE kept = 0 AND name IS NULL AND fit NOT IN " +
            "(SELECT fit FROM model_fits ORDER BY fittedAt DESC LIMIT :keep)",
    )
    suspend fun prune(keep: Int)
}

/**
 * One evaluation the user ran: a model (a fit, the model as it ships, a variant fitted with other
 * settings, or a classifier service's answers) scored against an answer key, by a stated method.
 */
@Entity(tableName = "evals")
data class EvalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    /** What was scored: a fit's name, [MODEL_BASE], "variant:…", or "provider:…". */
    val model: String,
    /** Its name as shown. */
    val label: String,
    /** The answer key: [DATASET_MINE] (the user's labels) or [DATASET_PROVIDER] (a service's answers). */
    val dataset: String,
    /** How it was scored: [METHOD_CROSS_VALIDATED], [METHOD_SINCE], [METHOD_TRAINED_ON] or [METHOD_RECORDED]. */
    val method: String,
    val examples: Int,
    val accuracy: Double,
    val macroF1: Double,
    val kappa: Double,
    /** Unwanted against wanted: ROC AUC, and the share of wanted texts Winnow's rule would filter. Null when one side has none. */
    val unwantedAuc: Double?,
    val falsePositiveRate: Double?,
    val costUsd: Double = 0.0,
    /** The whole result (ClassifierMetrics as JSON), for its charts. */
    val metrics: String?,
    val note: String? = null,
) {
    companion object {
        const val MODEL_BASE = "base"
        const val DATASET_MINE = "mine"
        const val DATASET_PROVIDER = "provider"
        const val METHOD_CROSS_VALIDATED = "cross-validated"
        const val METHOD_SINCE = "since"
        const val METHOD_TRAINED_ON = "trained-on"
        const val METHOD_RECORDED = "recorded"
    }
}

/** One text an evaluation scored: what it really was, and what the model said. */
@Entity(tableName = "eval_items", primaryKeys = ["evalId", "messageKey"])
data class EvalItemEntity(
    val evalId: Long,
    val messageKey: String,
    val threadId: Long?,
    val label: String,
    val predicted: String,
    val confidence: Double,
)

@Dao
interface EvalDao {
    @Insert
    suspend fun insert(eval: EvalEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<EvalItemEntity>)

    @Query("SELECT * FROM evals ORDER BY at DESC")
    fun observeAll(): Flow<List<EvalEntity>>

    @Query("SELECT * FROM evals WHERE id = :id")
    suspend fun get(id: Long): EvalEntity?

    @Query("SELECT * FROM eval_items WHERE evalId = :evalId")
    suspend fun items(evalId: Long): List<EvalItemEntity>

    @Query("DELETE FROM evals WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM eval_items WHERE evalId = :id")
    suspend fun deleteItems(id: Long)
}
