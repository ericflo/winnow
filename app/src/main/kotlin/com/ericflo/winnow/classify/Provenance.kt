package com.ericflo.winnow.classify

import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.classifier.message.ActionPolicy
import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classifier.message.FilteredPhrases
import com.ericflo.winnow.classifier.message.Subcategories
import com.ericflo.winnow.classifier.message.VerdictSource
import com.ericflo.winnow.data.db.CorrectionEntity
import com.ericflo.winnow.data.db.ModelFitEntity
import com.ericflo.winnow.data.db.RunEntity
import com.ericflo.winnow.data.db.VerdictEntity
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Who made a verdict as it was made: before the user had their say, which [Provenance] tells apart. */
enum class Decider(val label: String) {
    RULE("A rule on this phone"),
    PROVIDER("Classifier service"),
    MODEL("On-device model"),
    FALLBACK("Keyword fallback"),
    YOU("You"),
}

/** Why the on-device model decided, rather than the classifier service. */
enum class ModelReason {
    /** No classifier service is set up: the model is what decides. */
    ONLY_ONE,

    /** It was sure enough not to ask (Settings: decide on this phone when sure). */
    SURE,

    /** The service was asked and didn't answer. */
    PROVIDER_FAILED,

    /** Nothing could be sent (privacy settings, or contacts unreadable). */
    KEPT_ON_PHONE,

    /** Recorded before Winnow kept why. */
    UNKNOWN,
}

/** One party's opinion of a text, as the explanation lists them side by side. */
data class Opinion(val who: String, val category: Category?, val confidence: Double?, val detail: String? = null)

/**
 * Everything Winnow knows about why one text went where it did: who decided, and why it was
 * them; what each of the user, the classifier service and the on-device model thought; how
 * the category became an action; and what the text taught the model. Built from the records
 * alone, so it says only what was actually kept.
 */
data class Explanation(
    val decider: Decider,
    /** "Filtered as Spam", "Delivered as Personal". */
    val outcome: String,
    /** Who decided, in a sentence, with when and how. */
    val decidedBy: String,
    /** Why it was them, and the details of what they went on. */
    val why: List<String>,
    val opinions: List<Opinion>,
    /** How the category and its sureness became the action, when there was a category. */
    val policy: String?,
    /** What the text taught the on-device model, or why it didn't. */
    val learning: List<String>,
    /** What wasn't recorded, for a verdict from before Winnow kept it. */
    val gaps: String? = null,
    /** The run that decided it, to open. */
    val runId: Long? = null,
)

object Provenance {
    fun decider(v: VerdictEntity): Decider = decider(v.sourceKind, v.sourceDetail)

    fun decider(sourceKind: String, sourceDetail: String): Decider = when (sourceKind) {
        VerdictEntity.KIND_PROVIDER -> Decider.PROVIDER
        VerdictEntity.KIND_LOCAL -> Decider.MODEL
        VerdictEntity.KIND_HEURISTIC -> Decider.FALLBACK
        // A label on a text Winnow had never decided becomes its only verdict.
        else -> if (sourceDetail == LABELED_BY_YOU) Decider.YOU else Decider.RULE
    }

    fun modelReason(v: VerdictEntity): ModelReason? = modelReason(v.sourceKind, v.fallbackReason, v.localModel)

    fun modelReason(sourceKind: String, fallbackReason: String?, localModel: String?): ModelReason? {
        if (sourceKind != VerdictEntity.KIND_LOCAL) return null
        return when {
            fallbackReason == VerdictSource.OnDevice.SURE -> ModelReason.SURE
            fallbackReason?.startsWith("Provider unavailable") == true -> ModelReason.PROVIDER_FAILED
            fallbackReason != null -> ModelReason.KEPT_ON_PHONE
            // Since these were kept, every model verdict names its model.
            localModel == null -> ModelReason.UNKNOWN
            else -> ModelReason.ONLY_ONE
        }
    }

    /**
     * Explains [v]. [taught]: the labels kept for its text (the user's, a service's); [run]: the
     * run that decided it; [fit]: the record of the model fit that judged it; [hasHook]: whether
     * its text has something a fraudster could use, if known. Pure, so it's unit-tested.
     */
    fun explain(
        v: VerdictEntity,
        taught: List<CorrectionEntity>,
        run: RunEntity?,
        fit: ModelFitEntity?,
        policy: ActionPolicy,
        providerName: (String) -> String,
        learnFromProvider: Boolean,
        hasHook: Boolean? = null,
        /** The run whose answer taught the model about this text, when that's not the run that decided it. */
        teachingRun: RunEntity? = null,
        /** How much one of the service's labels counts now (see WinnowSettings.providerWeight). */
        providerWeight: Double = Learner.PROVIDER_WEIGHT,
        format: (Long) -> String = { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) },
    ): Explanation {
        val decider = decider(v)
        val category = v.category?.let(Category::fromKey)
        val action = runCatching { Action.valueOf(v.action) }.getOrDefault(Action.ALLOW)
        val mine = v.userCategory?.let(Category::fromKey)
        val stood = v.userAction?.let { runCatching { Action.valueOf(it) }.getOrNull() } ?: action
        val shown = mine ?: category
        val outcome = listOfNotNull(shown?.label, outcomeWord(stood)).joinToString(" · ")
        val service = if (v.sourceKind == VerdictEntity.KIND_PROVIDER) providerName(v.sourceDetail) else null
        val modelName = v.localModel ?: v.model?.takeIf { v.sourceKind == VerdictEntity.KIND_LOCAL }
        val fitName = modelName?.substringAfter('·', "")?.ifEmpty { null }
        val gaps = if (v.localModel == null && v.sourceKind in setOf(VerdictEntity.KIND_PROVIDER, VerdictEntity.KIND_LOCAL)) {
            "Decided before Winnow kept the on-device model's own opinion and why it was asked: those aren't known for this text."
        } else null

        val when_ = when {
            run != null -> "in ${if (run.kind == RunEntity.KIND_REDO) "a redo" else "a backlog run"} on ${format(run.startedAt)}"
            v.atArrival -> "as it arrived"
            else -> "when Winnow looked back at older texts"
        }
        val why = mutableListOf<String>()
        // The user's say came after, and it's what stands.
        if (mine != null && decider != Decider.YOU) why += "You've since labeled it ${mine.label}, which is what stands."
        else if (v.userAction != null && mine == null) why += "You've since ${if (stood == Action.ALLOW) "let this sender through (Not spam)" else "filtered this sender"}, which is what stands."
        val decidedBy = when (decider) {
            Decider.YOU -> "You labeled it ${mine?.label ?: category?.label ?: ""}".trim() + "."
            Decider.RULE -> v.sourceDetail.let { r ->
                when {
                    r.startsWith(FilteredPhrases.REASON_PREFIX) -> "A word you filter: $r."
                    else -> "$r."
                }
            }
            Decider.PROVIDER -> buildString {
                append("$service decided, $when_")
                v.model?.let { append(", with its model $it") }
                append(".")
            }
            Decider.MODEL -> buildString {
                append("Winnow's on-device model decided, $when_")
                modelName?.let { append(" (${describeModel(it)})") }
                append(".")
            }
            Decider.FALLBACK -> "Winnow's keyword fallback decided, $when_: no classifier service answered and the on-device model wasn't there to."
        }
        when (decider) {
            Decider.PROVIDER -> {
                v.subcategory?.let(Subcategories::of)?.takeIf { it.parent == category }?.let {
                    why += "It picked “${it.key.replace('_', ' ')}”, one of the finer kinds that add up to ${category?.label}."
                }
                v.latencyMillis?.let { why += "It answered in ${millis(it)}" + (if (v.costUsd > 0) ", for ${money(v.costUsd)}." else ".") }
                why += if (v.promptExamples > 0) "The question carried ${v.promptExamples} texts you'd labeled, as examples of how you sort."
                else "The question carried none of your labels: as texts arrive, $service is asked the plain question, so your labels don't change how it answers."
            }
            Decider.MODEL -> {
                when (modelReason(v)) {
                    ModelReason.SURE -> why += "It was ${pct(v.confidence)} sure, so the classifier service wasn't asked (Settings: decide on this phone when it's sure, at 95%)."
                    ModelReason.PROVIDER_FAILED -> why += "The classifier service was asked and didn't answer: ${v.fallbackReason!!.removePrefix("Provider unavailable (").removeSuffix(")")}."
                    ModelReason.KEPT_ON_PHONE -> why += "Nothing was sent anywhere: ${v.fallbackReason!!.replaceFirstChar { it.lowercase() }}."
                    ModelReason.ONLY_ONE -> why += "No classifier service is set up, so the on-device model decides everything that no rule does."
                    ModelReason.UNKNOWN, null -> Unit
                }
                v.sourceDetail.takeIf { it.isNotBlank() }?.let { why += "What it went on most: $it." }
                fit?.let { why += "That fit learned from ${fitTally(it)}." }
            }
            Decider.FALLBACK -> why += "Its reason: ${v.sourceDetail}."
            else -> Unit
        }

        val opinions = buildList {
            if (mine != null) add(Opinion("You", mine, null, "your label"))
            if (v.sourceKind == VerdictEntity.KIND_PROVIDER) add(Opinion(service ?: "Classifier service", category, v.confidence, v.subcategory?.replace('_', ' ')))
            val local = v.localCategory?.let(Category::fromKey) ?: category.takeIf { v.sourceKind == VerdictEntity.KIND_LOCAL }
            val localSure = v.localConfidence ?: v.confidence.takeIf { v.sourceKind == VerdictEntity.KIND_LOCAL }
            if (local != null) add(Opinion("On-device model", local, localSure, modelName?.let(::describeModel)))
            if (v.sourceKind == VerdictEntity.KIND_HEURISTIC) add(Opinion("Keyword fallback", category, v.confidence))
        }

        val policyText = category?.takeIf { decider != Decider.YOU && decider != Decider.RULE }?.let { c -> policyLine(c, v.confidence, action, decider, policy, hasHook) }

        val learning = buildList {
            val mineRow = taught.firstOrNull { !it.fromProvider }
            val theirs = taught.firstOrNull { it.fromProvider }
            when {
                mineRow != null -> add("Your label teaches the on-device model, counting fully. It replaces anything a classifier service taught about this text.")
                theirs != null -> {
                    val label = Category.fromKey(theirs.label)?.label ?: theirs.label
                    val from = when (theirs.runId) {
                        null -> "as the text arrived"
                        0L -> "in an earlier backlog run"
                        run?.id -> "in this run"
                        teachingRun?.id -> "in ${if (teachingRun!!.kind == RunEntity.KIND_REDO) "a redo" else "a backlog run"} on ${format(teachingRun.startedAt)}"
                        else -> "in a backlog run"
                    }
                    add(
                        if (providerWeight <= 0) "${service ?: "The classifier service"}'s answer ($label) was kept $from, but teaches the model nothing: you set the weight of its labels to 0."
                        else "${service ?: "The classifier service"}'s answer ($label) taught the on-device model $from, counting for ${pct(providerWeight)} of one of your labels.",
                    )
                }
                decider == Decider.PROVIDER && v.confidence < Learner.MIN_TEACH_CONFIDENCE ->
                    add("Under ${pct(Learner.MIN_TEACH_CONFIDENCE)} sure, so it didn't teach the on-device model: it would teach a guess.")
                decider == Decider.PROVIDER && !learnFromProvider && run == null ->
                    add("It didn't teach the on-device model: teaching it from $service's answers is off in Settings.")
                decider == Decider.PROVIDER -> add("It didn't teach the on-device model.")
                else -> add("Nothing about this text teaches the on-device model. Labeling it would.")
            }
        }

        return Explanation(
            decider = decider,
            outcome = outcome,
            decidedBy = decidedBy,
            why = why,
            opinions = opinions,
            policy = policyText,
            learning = learning,
            gaps = gaps,
            runId = run?.id ?: v.runId ?: teachingRun?.id,
        )
    }

    /**
     * How [category] at [confidence] became [action]: the user's setting for the category, and
     * any softening (too unsure, the fallback's ceiling, spam with nothing to hook with).
     */
    fun policyLine(category: Category, confidence: Double, action: Action, decider: Decider, policy: ActionPolicy, hasHook: Boolean?): String {
        val set = policy.forCategory(category)
        val base = when (set) {
            Action.FILTER -> "Your settings filter ${category.label}."
            Action.SILENCE -> "Your settings deliver ${category.label} without a sound."
            Action.ALLOW -> "Your settings deliver ${category.label} to the inbox, with a notification."
        }
        if (set == action) return base
        val floor = if (decider == Decider.MODEL) policy.onDeviceMinConfidence else policy.minConfidence
        val why = when {
            decider == Decider.FALLBACK && action == policy.heuristicCeiling && confidence >= floor ->
                "The keyword fallback can never do more than ${verb(policy.heuristicCeiling)}, so it did that."
            decider == Decider.MODEL && category == Category.SPAM && hasHook == false && confidence < policy.hooklessNotifiesBelow ->
                "It had nothing a scammer could use (no link off a real company's site, money, number to call, or payment talk) and the model was only ${pct(confidence)} sure, so it was let through: that reads like a real person on a new number."
            decider == Decider.MODEL && category == Category.SPAM && hasHook == false ->
                "It had nothing a scammer could use (no link off a real company's site, money, number to call, or payment talk), so the model ${verb(action)} it rather than hiding it."
            confidence < floor -> "It was only ${pct(confidence)} sure (under ${pct(floor)}), so Winnow went a step gentler: ${verb(action)}."
            else -> "It went a step gentler: ${verb(action)}."
        }
        return "$base $why"
    }

    /** "winnow-local-1·3fa2c1" as words: the model as it ships, or a fit of what it was taught. */
    fun describeModel(name: String): String {
        val fit = name.substringAfter('·', "")
        return when {
            name.startsWith(Learner.LAB_NAME) -> "the model you trained in the Lab ($fit)"
            fit.isEmpty() -> "as it ships, before anything you taught it"
            else -> "fit $fit"
        }
    }

    fun fitTally(fit: ModelFitEntity): String = listOfNotNull(
        "${fit.userLabels} of your labels",
        fit.corrections.takeIf { it > 0 }?.let { "$it of your corrections" },
        fit.providerLabels.takeIf { it > 0 }?.let { "$it backlog-run answers" },
        fit.providerLive.takeIf { it > 0 }?.let { "$it answers as texts arrived" },
    ).joinToString(", ")

    /** Where an action puts a text. */
    fun place(action: Action): String = when (action) {
        Action.ALLOW -> "Inbox"
        Action.SILENCE -> "Inbox, without a sound"
        Action.FILTER -> "Filtered"
    }

    /** What happened to a text, in a word or three. */
    fun outcomeWord(action: Action): String = when (action) {
        Action.ALLOW -> "delivered"
        Action.SILENCE -> "delivered without a sound"
        Action.FILTER -> "filtered"
    }

    private fun verb(action: Action) = when (action) {
        Action.ALLOW -> "let it through"
        Action.SILENCE -> "silenced"
        Action.FILTER -> "filtered"
    }

    const val LABELED_BY_YOU = "Labeled by you"

    private fun pct(x: Double) = String.format(Locale.US, "%.0f%%", x * 100)
    private fun millis(ms: Long) = if (ms < 1000) "$ms ms" else String.format(Locale.US, "%.1f s", ms / 1000.0)
    private fun money(usd: Double) = if (usd < 0.01) "under a cent" else String.format(Locale.US, "$%.2f", usd)
}
