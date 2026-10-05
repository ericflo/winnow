package com.ericflo.winnow

import android.view.textclassifier.TextClassifier
import com.ericflo.winnow.data.SmartLink
import com.ericflo.winnow.ui.components.tidySmartLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartLinkTidyTest {
    private fun shown(text: String, vararg links: SmartLink) = tidySmartLinks(text, links.toList()).map { text.substring(it.start, it.end) }

    private fun date(text: String, word: String, cut: Int = 0) =
        text.indexOf(word).let { SmartLink(it, it + word.length - cut, TextClassifier.TYPE_DATE_TIME) }

    @Test
    fun aSpanCutShortCoversTheWholeWord() {
        val text = "Lunch Thursday at noon?"
        assertEquals(listOf("Thursday at noon"), shown(text, date(text, "Thursday at noon", cut = 1)))
    }

    @Test
    fun aSpanRunningIntoTheNextWordStopsAtItsEdge() {
        val text = "See you Friday."
        val friday = text.indexOf("Friday")
        assertEquals(listOf("Friday"), shown(text, SmartLink(friday, friday + 7, TextClassifier.TYPE_DATE)))
    }

    @Test
    fun aLoneRelativeWordIsNoDate() {
        val text = "Can you call me now? Or later tonight"
        assertTrue(shown(text, date(text, "now"), date(text, "now", cut = 1), date(text, "later")).isEmpty())
        // A real time stays, even with a relative word in it.
        assertEquals(listOf("tonight at 9"), shown("Free tonight at 9", date("Free tonight at 9", "tonight at 9")))
    }

    @Test
    fun placesAreKeptAndTidiedButNeverDroppedAsVague() {
        val text = "Meet at Now Cafe"
        val at = text.indexOf("Now Cafe")
        assertEquals(listOf("Now Cafe"), shown(text, SmartLink(at, at + 7, TextClassifier.TYPE_ADDRESS)))
    }

    @Test
    fun spansOutsideTheTextAreDropped() {
        assertTrue(shown("hi", SmartLink(0, 9, TextClassifier.TYPE_DATE)).isEmpty())
    }
}
