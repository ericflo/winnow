package com.ericflo.winnow.classifier.providers

import com.ericflo.winnow.classifier.Choice
import com.ericflo.winnow.classifier.DecisionRequest
import com.ericflo.winnow.classifier.ProviderException
import com.ericflo.winnow.classifier.http.HttpResult
import com.ericflo.winnow.classifier.http.HttpTransport
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class SystemOneProviderTest {
    private val question = Choice("Which?", linkedMapOf("a" to "first", "b" to null))
    private val request = DecisionRequest(JsonPrimitive("hello"), mapOf("q" to question))

    @Test
    fun `encodes the System One request shape`() {
        val body = Json.parseToJsonElement(SystemOneWire.encodeRequest("jev-latest", request)).jsonObject
        assertEquals("jev-latest", body["model"]!!.jsonPrimitive.content)
        assertEquals("hello", body["state"]!!.jsonPrimitive.content)
        val q = body["questions"]!!.jsonObject["q"]!!.jsonObject
        assertEquals("choice", q["type"]!!.jsonPrimitive.content)
        assertEquals("Which?", q["instructions"]!!.jsonPrimitive.content)
        val criteria = q["criteria"]!!.jsonObject
        assertEquals(listOf("a", "b"), criteria.keys.toList())
        assertEquals("first", criteria["a"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, criteria["b"])
    }

    @Test
    fun `decodes probability maps, aligned lists and bare choices`() {
        val map = SystemOneWire.decodeResponse(
            """{"model":"jev-1.13","answers":{"q":{"type":"choice","choice":"a","confidence":0.8,"probabilities":{"a":0.8,"b":0.2}}},
               "usage":{"input_tokens":12,"output_tokens":1,"cost":0.0001}}""",
            request.questions,
        )
        assertEquals(0.8, map.answers.getValue("q")["a"], 1e-9)
        assertEquals("jev-1.13", map.model)
        assertEquals(12, map.usage.inputTokens)
        assertEquals(0.0001, map.usage.costUsd, 1e-12)

        val list = SystemOneWire.decodeResponse("""{"answers":{"q":{"probabilities":[0.25,0.75]}}}""", request.questions)
        assertEquals("b", list.answers.getValue("q").top)

        val bare = SystemOneWire.decodeResponse("""{"answers":{"q":{"choice":"b","confidence":0.9}}}""", request.questions)
        assertEquals(0.9, bare.answers.getValue("q")["b"], 1e-9)
        assertEquals(0.1, bare.answers.getValue("q")["a"], 1e-9)
    }

    @Test
    fun `missing answer is a provider error`() {
        assertFailsWith<ProviderException> { SystemOneWire.decodeResponse("""{"answers":{}}""", request.questions) }
        assertFailsWith<ProviderException> { SystemOneWire.decodeResponse("<html>", request.questions) }
    }

    @Test
    fun `sends bearer auth to the configured url and retries transient failures`() = runTest {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        var n = 0
        val http = HttpTransport { url, headers, _ ->
            calls += url to headers
            when (n++) {
                0 -> throw IOException("reset")
                1 -> HttpResult(503, "cold start")
                else -> HttpResult(200, """{"answers":{"q":{"probabilities":{"a":1,"b":0}}}}""")
            }
        }
        val provider = SystemOneProvider(SystemOneConfig.typeSafe("k-123").copy(maxAttempts = 3), http)
        val response = provider.decide(request)
        assertEquals("a", response.answers.getValue("q").top)
        assertEquals(3, calls.size)
        assertEquals("https://api.typesafe.ai/v1/systemone", calls[0].first)
        assertEquals("Bearer k-123", calls[0].second["Authorization"])
    }

    @Test
    fun `client errors fail fast and are not retryable`() = runTest {
        var n = 0
        val http = HttpTransport { _, _, _ -> n++; HttpResult(401, """{"error":{"message":"bad key"}}""") }
        val provider = SystemOneProvider(SystemOneConfig.openRouter("k").copy(maxAttempts = 3), http)
        val e = assertFailsWith<ProviderException> { provider.decide(request) }
        assertEquals(1, n)
        assertFalse(e.retryable)
        assertTrue("bad key" in e.message!!)
    }

    @Test
    fun `config toString never prints the key`() {
        assertFalse("secret" in SystemOneConfig.typeSafe("secret").toString())
    }

    @Test
    fun `zero retention asks the router for ZDR endpoints, and only then`() {
        val request = DecisionRequest(buildJsonObject { put("message", "hi") }, mapOf("category" to Choice("Which?", mapOf("a" to "A"))))
        val plain = Json.parseToJsonElement(SystemOneWire.encodeRequest("typesafe/jev-1.13", request)).jsonObject
        assertEquals(null, plain["provider"])
        val zdr = Json.parseToJsonElement(SystemOneWire.encodeRequest("typesafe/jev-1.13", request, zeroRetention = true)).jsonObject
        assertEquals("""{"zdr":true}""", zdr["provider"].toString())
        assertEquals(setOf("model", "provider", "state", "questions"), zdr.keys)
    }
}
