package com.ericflo.winnow.classifier.local

import com.ericflo.winnow.classifier.Choice
import com.ericflo.winnow.classifier.DecisionProvider
import com.ericflo.winnow.classifier.DecisionRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Locale

/**
 * A sweep steered by a classifier service (Jev speaks this natively): one decision call a round,
 * whose state is the sweep so far (the data in counts, what each knob does, every try's settings
 * and scores) and whose questions are, for each knob, which value the next round's best try
 * should have, and whether another round is worth it. Nothing in it is a text: no message, no
 * sender, no word.
 */
class ServiceSteerer(private val provider: DecisionProvider, override val name: String = provider.descriptor.displayName) : Steerer {

    override suspend fun steer(state: SweepState): Steering {
        val response = provider.decide(request(state))
        val odds = Knob.entries.associateWith { k -> response.answers[k.key]?.probabilities ?: k.values.associateWith { 1.0 / k.values.size } }
        return Steering(odds, more = response.answers[MORE]?.get("yes") ?: 0.5, by = name, costUsd = response.usage.costUsd)
    }

    companion object {
        const val MORE = "another_round"
        /** The best tries it's shown, and the rest summed up in its knobs' odds: enough to steer by, short enough to pay for. */
        private const val TRIALS_SHOWN = 40

        fun request(state: SweepState): DecisionRequest = DecisionRequest(stateOf(state), questions())

        fun stateOf(state: SweepState): JsonObject = buildJsonObject {
            put(
                "goal",
                "Find training settings for a small text-message classifier that runs on one person's phone and must follow that person's own category labels. " +
                    "Each trial trains on their labels (plus any shipped examples and service labels, as weighted) and is scored on conversations it hadn't seen: " +
                    "accuracy (higher is better) and macro F1 across categories. Trials are listed best first.",
            )
            putJsonObject("data") {
                put("labeled_texts", state.labeled)
                put("labeled_conversations", state.conversations)
                putJsonObject("labels_by_category") { state.categories.forEach { (c, n) -> put(c, n) } }
                put("shipped_examples", state.shippedExamples)
                put("service_labels", state.serviceLabels)
            }
            putJsonObject("knobs") {
                Knob.entries.forEach { k ->
                    putJsonObject(k.key) {
                        put("meaning", k.meaning)
                        putJsonArray("values") { k.values.forEach { add(it) } }
                    }
                }
            }
            putJsonArray("trials") {
                state.trials.sortedByDescending { it.accuracy }.take(TRIALS_SHOWN).forEach { t ->
                    add(
                        buildJsonObject {
                            put("round", t.round)
                            val settings = t.settings
                            if (settings != null) putJsonObject("settings") { settings.forEach { (k, v) -> put(k, v) } } else put("recipe", t.recipe.describe())
                            put("accuracy", round3(t.accuracy))
                            put("macro_f1", round3(t.macroF1))
                            put("accuracy_from_words_alone", round3(t.wordsAccuracy))
                            put("seconds", round3(t.millis / 1000.0))
                        },
                    )
                }
            }
            put("trials_so_far", state.trials.size)
            put("round", state.round)
            put("rounds_left_after_this", state.roundsLeft)
        }

        fun questions(): Map<String, Choice> = Knob.entries.associate { k ->
            k.key to Choice(
                instructions = "For the next round of trials, give the odds that each value of \"${k.key}\" (${k.meaning}) belongs in the best trial. " +
                    "Lean toward values in the best-scoring trials and away from values that did clearly worse; keep some odds on values not tried yet that could plausibly do better." +
                    (if (k.neuralOnly) " Only neural trials use it." else "") + (if (k.linearOnly) " Only linear trials use it." else "") +
                    " Values at either end of the list are worth trying when the best trials sit near that end.",
                options = k.values.associateWith { null },
            )
        } + (
            MORE to Choice(
                instructions = "Is another round of trials likely to beat the best accuracy so far by half a point (0.005) or more? Weigh how much the latest rounds gained and how much is still untried.",
                options = mapOf("yes" to "another round is likely worth it", "no" to "the search has settled; little left to gain"),
            )
        )

        private fun round3(x: Double) = String.format(Locale.US, "%.3f", x).toDouble()
    }
}
