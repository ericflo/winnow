package com.ericflo.winnow.classifier.providers

import com.ericflo.winnow.classifier.Choice
import com.ericflo.winnow.classifier.DecisionRequest
import com.ericflo.winnow.classifier.ProviderException
import com.ericflo.winnow.classifier.http.HttpResult
import com.ericflo.winnow.classifier.http.HttpTransport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChatCompletionsProviderTest {
    private val questions = mapOf("category" to Choice("Kind?", linkedMapOf("ham" to "fine", "spam" to "junk")))
    private val request = DecisionRequest(JsonPrimitive("WIN A PRIZE"), questions)

    private fun completion(content: String) = buildJsonObject {
        put("model", "some-model")
        put("choices", Json.parseToJsonElement("""[{"message":{"role":"assistant","content":${JsonPrimitive(content)}}}]"""))
        putJsonObject("usage") {
            put("prompt_tokens", 50)
            put("completion_tokens", 9)
        }
    }.toString()

    @Test
    fun `parses a probability table, even inside code fences`() {
        val r = ChatCompletionsProvider.parseCompletion(completion("```json\n{\"category\":{\"ham\":0.1,\"spam\":0.9}}\n```"), questions)
        assertEquals("spam", r.answers.getValue("category").top)
        assertEquals(0.9, r.answers.getValue("category").confidence, 1e-9)
        assertEquals(50, r.usage.inputTokens)
    }

    @Test
    fun `normalizes sloppy probabilities and accepts a bare label`() {
        val sloppy = ChatCompletionsProvider.parseCompletion(completion("""{"category":{"ham":1,"spam":3,"other":5}}"""), questions)
        assertEquals(0.75, sloppy.answers.getValue("category")["spam"], 1e-9)

        val label = ChatCompletionsProvider.parseCompletion(completion("""{"category":"ham"}"""), questions)
        assertEquals("ham", label.answers.getValue("category").top)
    }

    @Test
    fun `rejects unknown labels and non-JSON content`() {
        assertFailsWith<ProviderException> {
            ChatCompletionsProvider.parseCompletion(completion("""{"category":"eggs"}"""), questions)
        }
        assertFailsWith<ProviderException> {
            ChatCompletionsProvider.parseCompletion(completion("I think it's spam."), questions)
        }
    }

    @Test
    fun `merges extra body for vendor privacy routing`() = runTest {
        var sent = ""
        val http = HttpTransport { url, _, body ->
            assertEquals("https://openrouter.ai/api/v1/chat/completions", url)
            sent = body
            HttpResult(200, completion("""{"category":{"ham":0.2,"spam":0.8}}"""))
        }
        val config = ChatCompletionsConfig(
            baseUrl = "https://openrouter.ai/api/v1",
            model = "m",
            extraBody = buildJsonObject { putJsonObject("provider") { put("zdr", true) } },
        )
        ChatCompletionsProvider(config, http).decide(request)
        val body = Json.parseToJsonElement(sent).jsonObject
        assertEquals("true", body["provider"]!!.jsonObject["zdr"].toString())
        assertTrue("WIN A PRIZE" in body["messages"].toString())
    }
}
