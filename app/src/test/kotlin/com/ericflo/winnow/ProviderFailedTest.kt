package com.ericflo.winnow

import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classifier.message.Verdict
import com.ericflo.winnow.classifier.message.VerdictSource
import com.ericflo.winnow.classify.providerFailed
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderFailedTest {
    private fun verdict(source: VerdictSource, contacted: Boolean) =
        Verdict(Category.SPAM, 0.9, Action.FILTER, source, providerContacted = contacted)

    @Test
    fun aServiceThatWasAskedAndDidntAnswerFailed() {
        assertTrue(providerFailed(verdict(VerdictSource.OnDevice("m", emptyList(), "Provider unavailable (timeout)"), contacted = true)))
    }

    @Test
    fun anAnswerOrNeverAskingIsNoFailure() {
        assertFalse(providerFailed(verdict(VerdictSource.Provider("systemone:typesafe", "jev"), contacted = true)))
        // Decided on the phone without asking (sure enough, a contact, nothing may leave): not a failure.
        assertFalse(providerFailed(verdict(VerdictSource.OnDevice("m", emptyList(), "Winnow can't see your contacts"), contacted = false)))
        assertFalse(providerFailed(verdict(VerdictSource.OnDevice("m", emptyList()), contacted = false)))
    }

    @Test
    fun anAnswerYourLabelsOutweighedIsNoFailure() {
        val outweighed = verdict(VerdictSource.OnDevice("m", emptyList(), "${VerdictSource.OnDevice.OVER_SERVICE} (it said personal)"), contacted = true)
        assertTrue(outweighed.decidedByYourLabels)
        assertFalse(providerFailed(outweighed))
    }
}
