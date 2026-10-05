package com.ericflo.winnow

import com.ericflo.winnow.classify.Bootstrap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapFailureTest {
    @Test
    fun aRefusedKeySaysSo() {
        val text = Bootstrap.failure("Jev (TypeSafe)", "Provider unavailable (systemone:typesafe: HTTP 401 invalid api key)")
        assertTrue(text, text.startsWith("Jev (TypeSafe) refused the API key. Check it in Settings."))
        assertTrue(text, text.endsWith("(systemone:typesafe: HTTP 401 invalid api key)"))
    }

    @Test
    fun rateLimitsCreditAndOutagesAreTold() {
        assertTrue(Bootstrap.failure("Jev", "x: HTTP 429 slow down").startsWith("Jev is limiting"))
        assertTrue(Bootstrap.failure("Jev", "x: HTTP 402 no credit").startsWith("Jev says the account needs credit"))
        assertTrue(Bootstrap.failure("Jev", "x: HTTP 503 oops").startsWith("Jev is having trouble"))
    }

    @Test
    fun anUnreachableServerKeepsItsDetailOnce() {
        val text = Bootstrap.failure("Other System One server", "Provider unavailable (systemone:custom: systemone:custom: unexpected end of stream on http://localhost:8765/...)")
        assertEquals(
            "Other System One server couldn't be reached. Check your connection and try again. (systemone:custom: unexpected end of stream on http://localhost:8765/...)",
            text,
        )
    }

    @Test
    fun aMissingModelAndATurnedDownRequestSayWhatToCheck() {
        val missing = Bootstrap.failure("Jev via OpenRouter", "Provider unavailable (systemone:openrouter: HTTP 404 No endpoints found matching your data policy)")
        assertTrue(missing, missing.startsWith("Jev via OpenRouter has nothing that matches what was asked for. Check the model in Settings"))
        assertTrue(missing, missing.endsWith("(systemone:openrouter: HTTP 404 No endpoints found matching your data policy)"))
        val rejected = Bootstrap.failure("Jev (TypeSafe)", "x: HTTP 422 criteria: too many options")
        assertTrue(rejected, rejected.startsWith("Jev (TypeSafe) turned down every text it was sent, so the run stopped there."))
        assertTrue(rejected, rejected.endsWith("(x: HTTP 422 criteria: too many options)"))
    }
}
