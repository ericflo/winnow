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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException

/**
 * Settings for any server that implements the System One decisions API:
 * `POST {baseUrl}{path}` with `{model, state, questions}`.
 *
 * That covers TypeSafe's hosted Jev, OpenRouter's Jev surfaces, and self-hosted servers
 * (openjev-sglang, decider.serve, `pairsort serve`), so moving off a vendor is a config
 * change, not a code change.
 */
data class SystemOneConfig(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val path: String = "/v1/systemone",
    val model: String = "jev-latest",
    val apiKey: String? = null,
    val dataHandling: DataHandling = DataHandling.REMOTE,
    val extraHeaders: Map<String, String> = emptyMap(),
    val maxAttempts: Int = 2,
    /**
     * Ask the router to use zero-data-retention endpoints only: OpenRouter's
     * `"provider": {"zdr": true}`, which it accepts on its Jev endpoints. Set when the user
     * turns on zero retention for this provider, so that switch is a request, not just a label.
     */
    val zeroRetentionRouting: Boolean = false,
) {
    val url: String get() = baseUrl.trimEnd('/') + path

    override fun toString() =
        "SystemOneConfig(id=$id, url=$url, model=$model, apiKey=${if (apiKey == null) "none" else "set"}, dataHandling=$dataHandling)"

    companion object {
        fun typeSafe(apiKey: String, model: String = "jev-latest") = SystemOneConfig(
            id = "systemone:typesafe",
            displayName = "Jev (TypeSafe)",
            baseUrl = "https://api.typesafe.ai",
            model = model,
            apiKey = apiKey,
        )

        /** OpenRouter serves Jev on `/alpha/decisions` and `/v1/systemone` with identical shapes. */
        fun openRouter(apiKey: String, model: String = "typesafe/jev-1.13") = SystemOneConfig(
            id = "systemone:openrouter",
            displayName = "Jev via OpenRouter",
            baseUrl = "https://openrouter.ai/api",
            path = "/alpha/decisions",
            model = model,
            apiKey = apiKey,
        )

        /** A self-hosted or third-party System One server. */
        fun custom(
            baseUrl: String,
            model: String,
            apiKey: String? = null,
            dataHandling: DataHandling = DataHandling.REMOTE,
        ) = SystemOneConfig(
            id = "systemone:custom",
            displayName = "System One endpoint",
            baseUrl = baseUrl,
            model = model,
            apiKey = apiKey,
            dataHandling = dataHandling,
        )
    }
}

class SystemOneProvider(
    private val config: SystemOneConfig,
    private val http: HttpTransport,
) : DecisionProvider {

    override val descriptor = ProviderDescriptor(config.id, config.displayName, config.dataHandling)

    override suspend fun decide(request: DecisionRequest): DecisionResponse {
        val body = SystemOneWire.encodeRequest(config.model, request, zeroRetention = config.zeroRetentionRouting)
        val headers = buildMap {
            config.apiKey?.let { put("Authorization", "Bearer $it") }
            putAll(config.extraHeaders)
        }
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
                else -> return SystemOneWire.decodeResponse(result.body, request.questions)
            }
        }
        throw lastError
    }
}

/**
 * The System One wire format.
 *
 * Request: `{"model", "state", "questions": {key: {"type": "choice", "instructions", "criteria": {option: rubric}}}}`
 *
 * Response: `{"model", "answers": {key: {"type": "choice", "choice", "confidence", "probabilities": {option: p}}}, "usage"}`
 */
object SystemOneWire {
    private val json = Json { ignoreUnknownKeys = true }

    fun encodeRequest(model: String, request: DecisionRequest, zeroRetention: Boolean = false): String = buildJsonObject {
        put("model", model)
        if (zeroRetention) putJsonObject("provider") { put("zdr", true) }
        put("state", request.state)
        putJsonObject("questions") {
            request.questions.forEach { (key, q) ->
                putJsonObject(key) {
                    put("type", "choice")
                    put("instructions", q.instructions)
                    putJsonObject("criteria") { q.options.forEach { (option, rubric) -> put(option, rubric) } }
                }
            }
        }
    }.toString()

    fun decodeResponse(body: String, questions: Map<String, Choice>): DecisionResponse {
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: SerializationException) {
            throw ProviderException("response is not JSON: ${body.take(120)}", retryable = false, cause = e)
        } catch (e: IllegalArgumentException) {
            throw ProviderException("response is not a JSON object: ${body.take(120)}", retryable = false, cause = e)
        }
        val answers = root["answers"] as? JsonObject
            ?: throw ProviderException("response has no answers: ${body.take(120)}", retryable = false)
        val parsed = questions.mapValues { (key, q) ->
            val answer = answers[key] as? JsonObject
                ?: throw ProviderException("response has no answer for '$key'", retryable = false)
            parseAnswer(answer, q.options.keys)
        }
        val usage = root["usage"] as? JsonObject
        return DecisionResponse(
            answers = parsed,
            model = root["model"]?.jsonPrimitive?.contentOrNull,
            usage = Usage(
                inputTokens = usage?.get("input_tokens")?.jsonPrimitive?.longOrNull ?: 0,
                outputTokens = usage?.get("output_tokens")?.jsonPrimitive?.longOrNull ?: 0,
                costUsd = usage?.get("cost")?.jsonPrimitive?.doubleOrNull ?: 0.0,
            ),
        )
    }

    /** Reads a Choice answer, tolerating the variants servers emit. */
    fun parseAnswer(answer: JsonObject, options: Collection<String>): Distribution {
        when (val probs = answer["probabilities"] ?: answer["probs"]) {
            is JsonObject -> return Distribution.of(probs.mapValues { it.value.jsonPrimitive.doubleOrNull ?: 0.0 }, options)
            is JsonArray -> return Distribution.of(options.zip(probs.map { it.jsonPrimitive.doubleOrNull ?: 0.0 }).toMap(), options)
            else -> Unit
        }
        val choice = answer["choice"]?.jsonPrimitive?.contentOrNull
            ?: throw ProviderException("answer has neither probabilities nor a choice", retryable = false)
        val confidence = answer["confidence"]?.jsonPrimitive?.doubleOrNull ?: 1.0
        return Distribution.fromChoice(choice, confidence, options)
    }

    fun errorMessage(body: String): String = try {
        val error = json.parseToJsonElement(body).jsonObject["error"]
        (error as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: body.take(200)
    } catch (_: Exception) {
        body.take(200)
    }
}
