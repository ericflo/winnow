package com.ericflo.winnow.classifier.providers

import com.ericflo.winnow.classifier.Choice
import com.ericflo.winnow.classifier.DataHandling
import com.ericflo.winnow.classifier.DecisionProvider
import com.ericflo.winnow.classifier.DecisionRequest
import com.ericflo.winnow.classifier.DecisionResponse
import com.ericflo.winnow.classifier.Distribution
import com.ericflo.winnow.classifier.ProviderDescriptor
import com.ericflo.winnow.classifier.ProviderException
import com.ericflo.winnow.classifier.Usage
import com.ericflo.winnow.classifier.http.HttpTransport
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException

/**
 * Settings for an OpenAI-compatible chat-completions endpoint: a zero-retention hosted
 * model, OpenRouter with ZDR routing (`extraBody = {"provider": {"zdr": true}}`), or a
 * llama.cpp / vLLM server on your own hardware.
 */
data class ChatCompletionsConfig(
    val id: String = "chat:custom",
    val displayName: String = "Chat-completions model",
    val baseUrl: String,
    val model: String,
    val apiKey: String? = null,
    val dataHandling: DataHandling = DataHandling.REMOTE,
    /** Merged into the request body last, for vendor routing or privacy flags. */
    val extraBody: JsonObject = JsonObject(emptyMap()),
    val maxAttempts: Int = 2,
) {
    val url: String get() = baseUrl.trimEnd('/') + "/chat/completions"

    override fun toString() =
        "ChatCompletionsConfig(id=$id, url=$url, model=$model, apiKey=${if (apiKey == null) "none" else "set"}, dataHandling=$dataHandling)"
}

/**
 * Adapts a general-purpose LLM to the decision shape. The model is asked for a JSON
 * probability table per question; whatever comes back is normalized, so a sloppy model
 * degrades to a less calibrated answer instead of an error.
 */
class ChatCompletionsProvider(
    private val config: ChatCompletionsConfig,
    private val http: HttpTransport,
) : DecisionProvider {

    override val descriptor = ProviderDescriptor(config.id, config.displayName, config.dataHandling)

    override suspend fun decide(request: DecisionRequest): DecisionResponse {
        val body = buildJsonObject {
            put("model", config.model)
            put("temperature", 0)
            putJsonObject("response_format") { put("type", "json_object") }
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", SYSTEM_PROMPT)
                }
                addJsonObject {
                    put("role", "user")
                    put("content", userPrompt(request))
                }
            }
            config.extraBody.forEach { (k, v) -> put(k, v) }
        }.toString()
        val headers = buildMap { config.apiKey?.let { put("Authorization", "Bearer $it") } }

        var lastError = ProviderException("no attempts made", retryable = true)
        repeat(config.maxAttempts.coerceAtLeast(1)) { attempt ->
            if (attempt > 0) delay(500L shl (attempt - 1))
            val result = try {
                http.postJson(config.url, headers, body)
            } catch (e: IOException) {
                lastError = ProviderException("${config.id}: ${e.message ?: e::class.simpleName}", retryable = true, cause = e)
                return@repeat
            }
            when {
                result.status == 429 || result.status >= 500 ->
                    lastError = ProviderException("${config.id}: HTTP ${result.status} ${SystemOneWire.errorMessage(result.body)}", retryable = true)
                result.status >= 400 ->
                    throw ProviderException("${config.id}: HTTP ${result.status} ${SystemOneWire.errorMessage(result.body)}", retryable = false)
                else -> return parseCompletion(result.body, request.questions)
            }
        }
        throw lastError
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Probability given to an option when the model names it without a score. */
        private const val UNSCORED_LABEL_CONFIDENCE = 0.8

        internal const val SYSTEM_PROMPT =
            "You are a careful classifier. You answer multiple-choice questions about a state " +
                "with calibrated probabilities. Respond with a single JSON object and nothing else."

        internal fun userPrompt(request: DecisionRequest): String = buildString {
            appendLine("STATE:")
            appendLine(request.state.toString())
            appendLine()
            appendLine("QUESTIONS:")
            request.questions.forEach { (key, q) ->
                appendLine("- \"$key\": ${q.instructions}")
                q.options.forEach { (option, rubric) ->
                    appendLine("    - \"$option\"${if (rubric != null) ": $rubric" else ""}")
                }
            }
            appendLine()
            append("Return a JSON object mapping each question key to an object that gives every option key ")
            append("a probability between 0 and 1, summing to 1. ")
            val (k, q) = request.questions.entries.first()
            append("Example: {\"$k\": {")
            append(q.options.keys.joinToString(", ") { "\"$it\": 0.0" })
            append("}}")
        }

        internal fun parseCompletion(body: String, questions: Map<String, Choice>): DecisionResponse {
            val root = try {
                json.parseToJsonElement(body).jsonObject
            } catch (e: Exception) {
                throw ProviderException("completion is not JSON: ${body.take(120)}", retryable = false, cause = e)
            }
            val content = (root["choices"] as? JsonArray)?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
                ?: throw ProviderException("completion has no message content", retryable = false)
            val table = try {
                json.parseToJsonElement(stripFences(content)).jsonObject
            } catch (e: Exception) {
                throw ProviderException("model did not return a JSON object: ${content.take(120)}", retryable = false, cause = e)
            }
            val answers = questions.mapValues { (key, q) ->
                when (val v = table[key]) {
                    is JsonObject -> Distribution.of(v.mapValues { it.value.jsonPrimitive.doubleOrNull ?: 0.0 }, q.options.keys)
                    is JsonPrimitive -> v.contentOrNull?.takeIf { it in q.options }
                        ?.let { Distribution.fromChoice(it, UNSCORED_LABEL_CONFIDENCE, q.options.keys) }
                        ?: throw ProviderException("model answered '$key' with an unknown option", retryable = false)
                    else -> throw ProviderException("model gave no answer for '$key'", retryable = false)
                }
            }
            val usage = root["usage"] as? JsonObject
            return DecisionResponse(
                answers = answers,
                model = root["model"]?.jsonPrimitive?.contentOrNull,
                usage = Usage(
                    inputTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.longOrNull ?: 0,
                    outputTokens = usage?.get("completion_tokens")?.jsonPrimitive?.longOrNull ?: 0,
                    costUsd = usage?.get("cost")?.jsonPrimitive?.doubleOrNull ?: 0.0,
                ),
            )
        }

        private fun stripFences(s: String): String =
            s.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    }
}
