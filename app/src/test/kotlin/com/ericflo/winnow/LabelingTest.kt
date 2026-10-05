package com.ericflo.winnow

import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classify.Labeler
import com.ericflo.winnow.classify.Training
import com.ericflo.winnow.data.Attachment
import com.ericflo.winnow.data.ChatMessage
import com.ericflo.winnow.data.db.VerdictEntity
import com.ericflo.winnow.ui.train.sureness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LabelingTest {
    private fun message(
        id: Long,
        body: String = "hi",
        outgoing: Boolean = false,
        status: ChatMessage.Status = if (outgoing) ChatMessage.Status.SENT else ChatMessage.Status.RECEIVED,
        subject: String? = null,
        attachments: List<Attachment> = emptyList(),
    ) = ChatMessage(id, threadId = 1, body = body, timestamp = id, outgoing = outgoing, status = status, verdict = null, subject = subject, attachments = attachments)

    @Test
    fun onlyReceivedMessagesWithSomethingInThemAreLabelable() {
        assertTrue(Labeler.labelable(message(1)))
        assertFalse("the user's own", Labeler.labelable(message(2, outgoing = true)))
        assertFalse("not downloaded yet", Labeler.labelable(message(3, body = "", status = ChatMessage.Status.NOT_DOWNLOADED)))
        assertFalse("empty", Labeler.labelable(message(4, body = " ")))
        assertTrue("a photo alone", Labeler.labelable(message(5, body = "", attachments = listOf(Attachment("content://x", "image/jpeg")))))
        assertTrue("a subject alone", Labeler.labelable(message(6, body = "", subject = "Sale")))
    }

    @Test
    fun theModelReadsSubjectAndBodyOrAPhoto() {
        assertEquals("Sale\n50% off", Labeler.textOf(message(1, body = "50% off", subject = "Sale")))
        assertEquals("50% off", Labeler.textOf(message(1, body = "50% off", subject = " ")))
        assertEquals("[photo]", Labeler.textOf(message(1, body = "", attachments = listOf(Attachment("content://x", "image/png")))))
    }

    @Test
    fun aConversationLabelTeachesItsNewestReceivedMessages() {
        val messages = (1L..8L).map { message(it, outgoing = it % 3 == 0L) }
        val examples = Labeler.examplesFrom(messages, listOf("+14155550100"))
        assertEquals(Labeler.PER_CONVERSATION, examples.size)
        assertEquals(listOf(2L, 4L, 5L, 7L, 8L), examples.map { it.id })
    }

    @Test
    fun aRoundIsMostlyTheLeastSureWithSomeAtRandom() {
        val guesses = (1L..50L).map { it to it / 50.0 }
        val picked = Training.pick(guesses, size = 9, random = Random(7))
        assertEquals(9, picked.size)
        assertEquals(picked.size, picked.toSet().size)
        assertEquals("the six least sure come first", (1L..6L).toList(), picked.take(6))
        assertTrue("the rest come from what's left", picked.drop(6).all { it > 6 })
    }

    @Test
    fun aSmallBacklogIsOneRound() {
        val guesses = listOf(5L to 0.9, 6L to 0.2)
        assertEquals(listOf(5L, 6L), Training.pick(guesses, size = 20, random = Random(1)))
    }

    @Test
    fun guessesAreWordsNotPercentages() {
        assertEquals("fairly sure", sureness(1.0))
        assertEquals("leaning this way", sureness(0.7))
        assertEquals("unsure", sureness(0.4))
    }

    @Test
    fun theUsersLabelWinsOverTheModels() {
        val row = VerdictEntity(
            messageKey = "sms:1", threadId = 1, address = "+14155550100", category = Category.PERSONAL.key, confidence = 0.8,
            action = Action.ALLOW.name, sourceKind = "local", sourceDetail = "", model = null, costUsd = 0.0, decidedAt = 0,
        )
        val unlabeled = row.toStored { it }
        assertEquals(Category.PERSONAL, unlabeled.category)
        assertFalse(unlabeled.labeledByUser)
        val labeled = row.copy(userCategory = Category.SPAM.key, userAction = Action.FILTER.name).toStored { it }
        assertEquals(Category.SPAM, labeled.category)
        assertTrue(labeled.labeledByUser)
        assertEquals("Labeled by you", labeled.source)
        assertEquals("what Winnow did when it arrived is kept", Action.ALLOW, labeled.action)
        assertEquals(Action.FILTER, labeled.effectiveAction)
    }
}
