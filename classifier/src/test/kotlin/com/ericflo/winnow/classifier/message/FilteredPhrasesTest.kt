package com.ericflo.winnow.classifier.message

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FilteredPhrasesTest {
    @Test
    fun `whole words, any case, any spacing`() {
        val phrases = FilteredPhrases(listOf("vote", " chip  in ", "50% off"))
        assertEquals("vote", phrases.find("VOTE today for Measure 9"))
        assertNull(phrases.find("She's devoted to the team"))
        assertNull(phrases.find("voters guide attached"))
        assertEquals("chip in", phrases.find("Can you chip\nin $5 before midnight?"))
        assertEquals("50% off", phrases.find("Everything 50% OFF this weekend"))
        assertNull(phrases.find("Dinner at 7?"))
    }

    @Test
    fun `blank and repeated phrases are ignored`() {
        assertEquals(true, FilteredPhrases(listOf("", "   ")).isEmpty)
        assertEquals("Promo", FilteredPhrases(listOf("Promo", "promo")).find("promo code inside"))
    }

    @Test
    fun `a filtered phrase filters a stranger's text on the phone, but not a contact's`() = runTest {
        val classifier = MessageClassifier(providers = emptyList(), filteredPhrases = FilteredPhrases(listOf("toll")))
        val stranger = classifier.classify(InboundMessage(sender = "+15555550142", body = "Unpaid TOLL balance, pay now"))
        assertEquals(Action.FILTER, stranger.action)
        assertEquals(false, stranger.providerContacted)
        val friend = classifier.classify(InboundMessage(sender = "+15555550143", body = "The toll road was empty", senderInContacts = true))
        assertEquals(Action.ALLOW, friend.action)
    }
}
